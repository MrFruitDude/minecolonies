package com.minecolonies.core.gametest;

import com.minecolonies.api.colony.ICitizenData;
import com.minecolonies.api.colony.IColony;
import com.minecolonies.api.entity.citizen.AbstractEntityCitizen;
import com.minecolonies.api.entity.other.AbstractFastMinecoloniesEntity;
import com.minecolonies.api.util.Log;
import com.minecolonies.core.colony.buildings.workerbuildings.BuildingTownHall;
import com.minecolonies.core.colony.buildings.modules.settings.BoolSetting;
import com.minecolonies.core.entity.pathfinding.navigation.EntityNavigationUtils;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.clock.WorldClocks;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.monster.zombie.Zombie;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.BiConsumer;

import static com.minecolonies.core.gametest.MinecoloniesGameTests.foundGameTestColony;
import static com.minecolonies.core.gametest.MinecoloniesGameTests.makeConnectedSurvivalPlayer;

/**
 * CA-9: idle on-ground citizens skip vanilla travel / move / collision, and wake at once when something should move them.
 * <p>
 * Fixture: a colony with no houses, the clock pinned to midnight, citizens standing 1 block apart within the town hall's
 * home range. Each one heads home (it is home already) and waits in its sleep routine for a bed it never gets, so it
 * stands still and only the test moves it. Moves are counted through {@link AbstractFastMinecoloniesEntity#moveObserver}.
 * <p>
 * The single-citizen tests act on the tick of the citizen's 10-tick re-check, so the next 9 ticks have no re-check and a
 * missing wake cannot be hidden by the periodic re-check landing inside the assertion window.
 */
public final class IdleTravelGameTests
{
    /** The colony's town hall (relative), which is also every homeless citizen's home position. */
    private static final BlockPos TOWN_HALL = new BlockPos(2, 1, 2);

    /** A homeless citizen counts as home within this squared block distance of the town hall (RANGE_TO_BE_HOME 16). */
    private static final int HOME_RANGE_SQ = 16;

    /** Ticks after the spawn before anything is measured: entity init (40), AI goes to sleep and settles. */
    private static final int SETTLE_TICKS = 200;

    /** Measured window, a multiple of the 10-tick re-check. */
    private static final int WINDOW_TICKS = 200;

    /** Idle bar: the idle citizens may call Entity.move at most this often per tick on average (from ~1 per citizen). */
    private static final double MAX_MOVES_PER_TICK = 5.0D;

    /** Where the single-citizen tests stand their citizen (2 blocks east of the town hall). */
    private static final BlockPos SINGLE_SPOT = TOWN_HALL.east(2);

    /** Midnight. */
    private static final long MIDNIGHT = 18000L;

    private IdleTravelGameTests()
    {
    }

    private static void pinNight(final ServerLevel level)
    {
        level.getServer().clockManager().setTotalTicks(level.registryAccess().getOrThrow(WorldClocks.OVERWORLD), MIDNIGHT);
    }

    /**
     * Every block 1 apart (so no two citizens push each other) within the home range of the town hall: 48 spots.
     */
    private static List<BlockPos> homeSpots()
    {
        final List<BlockPos> spots = new ArrayList<>();
        for (int dx = -4; dx <= 4; dx++)
        {
            for (int dz = -4; dz <= 4; dz++)
            {
                if ((dx != 0 || dz != 0) && dx * dx + dz * dz <= HOME_RANGE_SQ)
                {
                    spots.add(TOWN_HALL.offset(dx, 0, dz));
                }
            }
        }
        return spots;
    }

    /**
     * Founds the colony (no natural move-ins), a connected player close by so the colony is active, forces the chunks,
     * pins the clock to midnight and spawns one citizen per spot. The colony has no houses, so at night each citizen
     * walks home (the town hall), is home already, and waits in its sleep routine for a bed it never gets: it stands
     * still, the real "sleeping colony" idle case. Runs {@code body} after {@link #SETTLE_TICKS}.
     */
    private static void homelessNightFixture(
      final GameTestHelper helper,
      final String name,
      final List<BlockPos> spots,
      final BiConsumer<IColony, List<AbstractEntityCitizen>> body)
    {
        final ServerLevel level = helper.getLevel();
        final IColony colony = foundGameTestColony(helper, name);
        final BoolSetting moveIn = colony.getSettings().getSetting(BuildingTownHall.MOVE_IN);
        if (moveIn.getValue())
        {
            moveIn.trigger();
        }
        pinNight(level);
        final BlockPos min = helper.absolutePos(new BlockPos(-8, 0, -8));
        final BlockPos max = helper.absolutePos(new BlockPos(40, 0, 32));
        for (int cx = Math.min(min.getX(), max.getX()) >> 4; cx <= Math.max(min.getX(), max.getX()) >> 4; cx++)
        {
            for (int cz = Math.min(min.getZ(), max.getZ()) >> 4; cz <= Math.max(min.getZ(), max.getZ()) >> 4; cz++)
            {
                level.setChunkForced(cx, cz, true);
            }
        }
        final ServerPlayer player = makeConnectedSurvivalPlayer(helper);
        final BlockPos playerPos = helper.absolutePos(new BlockPos(20, 1, 24));
        player.teleportTo(playerPos.getX() + 0.5D, playerPos.getY(), playerPos.getZ() + 0.5D);
        colony.getPackageManager().addCloseSubscriber(player);

        helper.runAfterDelay(20, () -> {
            final List<AbstractEntityCitizen> citizens = new ArrayList<>();
            for (final BlockPos spot : spots)
            {
                final BlockPos abs = helper.absolutePos(spot);
                final ICitizenData data = colony.getCitizenManager().spawnOrCreateCivilian(null, level, List.of(abs), true);
                helper.assertTrue(data != null && data.getEntity().isPresent(), "citizen did not spawn at " + spot);
                data.setSaturation(20);
                final AbstractEntityCitizen citizen = data.getEntity().get();
                citizen.setPos(abs.getX() + 0.5D, abs.getY(), abs.getZ() + 0.5D);
                citizen.setDeltaMovement(Vec3.ZERO);
                citizens.add(citizen);
            }
            keepNight(helper, citizens);
            // Before the night routine takes over, the first AI pass may already have sent a citizen on a random walk.
            // Once they are all in it, put everyone back on its spot with no path left, and let that settle too.
            helper.runAfterDelay(SETTLE_TICKS, () -> {
                for (int i = 0; i < citizens.size(); i++)
                {
                    final AbstractEntityCitizen citizen = citizens.get(i);
                    final BlockPos abs = helper.absolutePos(spots.get(i));
                    citizen.getNavigation().stop();
                    citizen.setPos(abs.getX() + 0.5D, abs.getY(), abs.getZ() + 0.5D);
                    citizen.setDeltaMovement(Vec3.ZERO);
                }
                helper.runAfterDelay(60, () -> body.accept(colony, citizens));
            });
        });
    }

    /**
     * Keeps the clock at midnight and the citizens fed, every tick until the test ends. A fresh lambda each tick: the
     * GameTest scheduler keys its callbacks by Runnable, so re-registering the same instance would drop it.
     */
    private static void keepNight(final GameTestHelper helper, final List<AbstractEntityCitizen> citizens)
    {
        pinNight(helper.getLevel());
        for (final AbstractEntityCitizen c : citizens)
        {
            if (c.getCitizenData() != null)
            {
                c.getCitizenData().setSaturation(20);
            }
        }
        helper.runAfterDelay(1, () -> keepNight(helper, citizens));
    }

    /**
     * idle_citizen_skips_move: 48 homeless citizens waiting for a bed at midnight. Counts Entity.move calls on them over
     * 200 ticks. Today every on-ground citizen runs travel -> move -> collide every tick (~48 per tick); the bar is at most
     * 5 per tick (one re-check per citizen per 10 ticks is 4.8). Also checks the scenario really is idle: no citizen
     * changed position.
     */
    public static void idleCitizenSkipsMove(final GameTestHelper helper)
    {
        homelessNightFixture(helper, "CA9 idle colony", homeSpots(), (colony, citizens) -> {
            final ServerLevel level = helper.getLevel();
            final Set<AbstractFastMinecoloniesEntity> ours = new HashSet<>(citizens);
            final Map<AbstractFastMinecoloniesEntity, Integer> perCitizen = new HashMap<>();
            final int[] perTick = new int[WINDOW_TICKS];
            final long start = level.getGameTime();
            final Map<AbstractEntityCitizen, Vec3> startPos = new HashMap<>();
            citizens.forEach(c -> startPos.put(c, c.position()));
            AbstractFastMinecoloniesEntity.moveObserver = e -> {
                if (ours.contains(e))
                {
                    final long t = level.getGameTime() - start;
                    if (t >= 0 && t < WINDOW_TICKS)
                    {
                        perTick[(int) t]++;
                        perCitizen.merge(e, 1, Integer::sum);
                    }
                }
            };
            helper.runAfterDelay(WINDOW_TICKS + 1, () -> {
                AbstractFastMinecoloniesEntity.moveObserver = null;
                int total = 0;
                int maxTick = 0;
                for (final int n : perTick)
                {
                    total += n;
                    maxTick = Math.max(maxTick, n);
                }
                final double mean = total / (double) WINDOW_TICKS;
                int alive = 0;
                final List<String> moved = new ArrayList<>();
                for (final AbstractEntityCitizen c : citizens)
                {
                    if (c.isAlive() && !c.isRemoved())
                    {
                        alive++;
                    }
                    if (c.position().distanceToSqr(startPos.get(c)) > 1.0E-6D)
                    {
                        moved.add(c.getId() + " " + (c instanceof com.minecolonies.core.entity.citizen.EntityCitizen ec ? ec.getCitizenAI().getState() : "?") + ":" + helper.relativeVec(startPos.get(c)) + "->" + helper.relativeVec(c.position()));
                    }
                }
                final List<Integer> counts = new ArrayList<>(perCitizen.values());
                counts.sort((a, b) -> b - a);
                final String summary = String.format(
                  "CA9 idle: %d citizens (%d alive), %d moves in %d ticks = %.2f/tick (max %d in one tick); top per-citizen %s; moved %d %s",
                  citizens.size(), alive, total, WINDOW_TICKS, mean, maxTick, counts.subList(0, Math.min(8, counts.size())), moved.size(),
                  moved);
                Log.getLogger().info(summary);
                helper.assertTrue(alive == citizens.size(), "fixture: only " + alive + " of " + citizens.size() + " citizens alive. " + summary);
                helper.assertTrue(moved.isEmpty(), "fixture: idle citizens changed position, so the scenario is not idle. " + summary);
                helper.assertTrue(mean <= MAX_MOVES_PER_TICK, String.format("idle citizens called Entity.move %.2f times per tick (bar %.1f). %s",
                  mean, MAX_MOVES_PER_TICK, summary));
                helper.succeed();
            });
        });
    }

    /**
     * One idle citizen at its home spot; once it has stood still (same position, path done) for 40 ticks, waits for the tick of
     * its 10-tick re-check and runs {@code act} right after it.
     */
    private static void singleIdleCitizen(final GameTestHelper helper, final String name, final BiConsumer<IColony, AbstractEntityCitizen> act)
    {
        homelessNightFixture(helper, name, List.of(SINGLE_SPOT), (colony, citizens) -> {
            final AbstractEntityCitizen citizen = citizens.get(0);
            final int[] still = {0};
            final int[] waited = {0};
            final Vec3[] last = {citizen.position()};
            final Runnable[] poll = new Runnable[1];
            poll[0] = () -> {
                if (citizen.position().equals(last[0]) && citizen.getNavigation().isDone() && citizen.onGround())
                {
                    still[0]++;
                }
                else
                {
                    still[0] = 0;
                    last[0] = citizen.position();
                }
                if (still[0] >= 40 && citizen.tickCount % 10 == citizen.randomVariance % 10)
                {
                    act.accept(colony, citizen);
                    return;
                }
                if (++waited[0] > 600)
                {
                    throw helper.assertionException(Component.literal("fixture: citizen never stood still at home (" + citizen.position()
                                                                      + ", path done " + citizen.getNavigation().isDone() + ")"));
                }
                helper.runAfterDelay(1, () -> poll[0].run());
            };
            poll[0].run();
        });
    }

    /**
     * Runs {@code check} after {@code ticks} ticks and passes the test if it holds.
     */
    private static void assertAfter(final GameTestHelper helper, final int ticks, final Runnable check)
    {
        helper.runAfterDelay(ticks, () -> {
            check.run();
            helper.succeed();
        });
    }

    /**
     * idle_citizen_push_moves: an idle citizen given a push (Entity.push, what doPush and explosions use) moves on the next
     * ticks.
     */
    public static void idleCitizenPushMoves(final GameTestHelper helper)
    {
        singleIdleCitizen(helper, "CA9 push colony", (colony, citizen) -> {
            final Vec3 before = citizen.position();
            citizen.push(0.0D, 0.0D, 0.3D);
            assertAfter(helper, 3, () -> {
                final double dz = citizen.getZ() - before.z;
                Log.getLogger().info("CA9 push: dz after 3 ticks = " + dz);
                helper.assertTrue(dz >= 0.05D, "pushed idle citizen did not move within 3 ticks: dz=" + dz + " (" + before + " -> " + citizen.position() + ")");
            });
        });
    }

    /**
     * idle_citizen_floor_removed_falls: removing the floor under an idle citizen makes it fall at once (position assert).
     */
    public static void idleCitizenFloorRemovedFalls(final GameTestHelper helper)
    {
        singleIdleCitizen(helper, "CA9 floor colony", (colony, citizen) -> {
            final Vec3 before = citizen.position();
            for (int y = 0; y >= -3; y--)
            {
                helper.setBlock(new BlockPos(SINGLE_SPOT.getX(), y, SINGLE_SPOT.getZ()), Blocks.AIR.defaultBlockState());
            }
            assertAfter(helper, 3, () -> {
                final double dy = before.y - citizen.getY();
                Log.getLogger().info("CA9 floor: fell " + dy + " in 3 ticks");
                helper.assertTrue(dy >= 0.2D, "idle citizen did not fall within 3 ticks of its floor being removed: fell " + dy + " ("
                                                + before + " -> " + citizen.position() + ")");
            });
        });
    }

    /**
     * idle_citizen_hurt_knockback: an idle citizen hit by a zombie gets knocked back (vanilla knockback lifts it 0.4).
     */
    public static void idleCitizenHurtKnockback(final GameTestHelper helper)
    {
        singleIdleCitizen(helper, "CA9 hurt colony", (colony, citizen) -> {
            final ServerLevel level = helper.getLevel();
            final Zombie zombie = new Zombie(level);
            zombie.setPos(citizen.getX() + 2.0D, citizen.getY(), citizen.getZ());
            final Vec3 before = citizen.position();
            final boolean hurt = citizen.hurtServer(level, level.damageSources().mobAttack(zombie), 1.0F);
            helper.assertTrue(hurt, "fixture: the zombie's hit did not hurt the citizen");
            assertAfter(helper, 2, () -> {
                final double dy = citizen.getY() - before.y;
                Log.getLogger().info("CA9 hurt: rose " + dy + " in 2 ticks, dx " + (citizen.getX() - before.x));
                helper.assertTrue(dy >= 0.1D, "hurt idle citizen got no knockback within 2 ticks: rose " + dy + " (" + before + " -> " + citizen.position() + ")");
            });
        });
    }

    /**
     * idle_citizen_path_walks: send the idle citizen 6 blocks east; it walks there.
     */
    public static void idleCitizenPathWalks(final GameTestHelper helper)
    {
        singleIdleCitizen(helper, "CA9 path colony", (colony, citizen) -> {
            final BlockPos target = helper.absolutePos(SINGLE_SPOT.east(6));
            final Vec3 before = citizen.position();
            final int[] waited = {0};
            final Runnable[] walk = new Runnable[1];
            walk[0] = () -> {
                final boolean arrived = EntityNavigationUtils.walkToPos(citizen, target, 1, true);
                final double left = Math.sqrt(citizen.position().distanceToSqr(Vec3.atBottomCenterOf(target)));
                if (arrived || left < 1.5D)
                {
                    Log.getLogger().info("CA9 path: reached " + citizen.position() + " in " + waited[0] + " ticks");
                    helper.succeed();
                    return;
                }
                if (++waited[0] > 200)
                {
                    throw helper.assertionException(Component.literal("idle citizen sent 6 blocks east did not get there in 200 ticks: "
                                                                      + before + " -> " + citizen.position() + ", " + left + " left"));
                }
                helper.runAfterDelay(1, () -> walk[0].run());
            };
            walk[0].run();
        });
    }

    /**
     * idle_citizen_gravity_recheck: the cheap wake checks do not watch attributes, so a change only the full travel sees
     * (gravity turned upward) must be picked up by the 10-tick re-check: the citizen floats up within 15 ticks.
     */
    public static void idleCitizenGravityRecheck(final GameTestHelper helper)
    {
        singleIdleCitizen(helper, "CA9 recheck colony", (colony, citizen) -> {
            final Vec3 before = citizen.position();
            citizen.getAttribute(Attributes.GRAVITY).setBaseValue(-0.08D);
            assertAfter(helper, 15, () -> {
                final double dy = citizen.getY() - before.y;
                Log.getLogger().info("CA9 recheck: rose " + dy + " in 15 ticks with gravity -0.08");
                helper.assertTrue(dy >= 0.05D, "idle citizen with upward gravity did not rise within 15 ticks: rose " + dy);
            });
        });
    }
}
