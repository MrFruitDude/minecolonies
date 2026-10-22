package com.minecolonies.core.gametest;

import com.minecolonies.api.colony.ICitizenData;
import com.minecolonies.api.colony.IColony;
import com.minecolonies.api.entity.ModEntities;
import com.minecolonies.api.entity.ai.statemachine.AIEventTarget;
import com.minecolonies.api.entity.ai.statemachine.states.AIBlockingEventType;
import com.minecolonies.api.entity.ai.statemachine.states.CitizenAIState;
import com.minecolonies.api.entity.ai.statemachine.states.IState;
import com.minecolonies.api.entity.ai.statemachine.tickratestatemachine.TickingTransition;
import com.minecolonies.api.entity.citizen.VisibleCitizenStatus;
import com.minecolonies.api.util.Log;
import com.minecolonies.core.MineColonies;
import com.minecolonies.core.colony.CitizenData;
import com.minecolonies.core.datalistener.DiseasesListener;
import com.minecolonies.core.datalistener.model.Disease;
import com.minecolonies.core.entity.ai.minimal.CachedBuildingLookup;
import com.minecolonies.core.entity.ai.minimal.EntityAISickTask;
import com.minecolonies.core.entity.ai.minimal.EntityAISleep;
import com.minecolonies.core.entity.ai.workers.CitizenAI;
import com.minecolonies.core.entity.citizen.CitizenAIStateMachine;
import com.minecolonies.core.entity.citizen.EntityCitizen;
import com.minecolonies.core.entity.citizen.citizenhandlers.CitizenDiseaseHandler;
import com.minecolonies.core.colony.buildings.workerbuildings.BuildingHospital;
import com.minecolonies.core.entity.pathfinding.navigation.PathingStuckHandler;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.player.Player;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Round 13 lane mc-life: citizen daily life and robustness (B2, B4, B5, D1-D7). Group "life" (MINECOLONIES_GAMETEST_ONLY=life).
 */
public final class Round13LifeGameTests
{
    public static final String[] NAMES = {"life_pure_rules", "life_cache_rescue_watch", "life_sick_home", "life_statuses", "life_raid_proximity", "life_ai_exception"};

    private Round13LifeGameTests()
    {
    }

    public static java.util.function.Consumer<GameTestHelper> test(final String name)
    {
        return switch (name)
        {
            case "life_pure_rules" -> Round13LifeGameTests::pureRules;
            case "life_cache_rescue_watch" -> Round13LifeGameTests::cacheRescueWatch;
            case "life_sick_home" -> Round13LifeGameTests::sickHome;
            case "life_statuses" -> Round13LifeGameTests::statuses;
            case "life_raid_proximity" -> Round13LifeGameTests::raidProximity;
            default -> Round13LifeGameTests::aiException;
        };
    }

    /**
     * D1 rain default, D2 flat leisure, D5 wake delay, D6 homeless spots, B5 idle statuses and ids, D3 ranges, D4 timing, B4 state machine (back-off, reset,
     * log limit) on a stand-alone machine.
     */
    public static void pureRules(final GameTestHelper helper)
    {
        helper.assertTrue(MineColonies.getConfig().getServer().workersAlwaysWorkInRain.getDefault(), "D1: workersalwaysworkinrain does not default to true");

        final int bound = CitizenData.leisureRollBound(1, 20);
        helper.assertTrue(bound == 20 * 60 * 120 / 20, "D2: level 1 bound " + bound);
        for (int level = 0; level <= 5; level++)
        {
            helper.assertTrue(CitizenData.leisureRollBound(level, 20) == bound, "D2: home level " + level + " has bound " + CitizenData.leisureRollBound(level, 20));
        }

        final Set<Integer> delays = new HashSet<>();
        for (int id = 1; id <= 200; id++)
        {
            final int delay = CitizenAI.wakeDelay(id);
            helper.assertTrue(delay >= 0 && delay <= CitizenAI.MAX_WAKE_DELAY, "D5: delay " + delay + " of " + id);
            helper.assertTrue(CitizenAI.stillSleepingAfterDawn(0, id) == (delay > 0), "D5: at dawn " + id);
            helper.assertTrue(!CitizenAI.stillSleepingAfterDawn(CitizenAI.MAX_WAKE_DELAY, id) && !CitizenAI.stillSleepingAfterDawn(13000, id), "D5: still asleep late " + id);
            delays.add(delay);
        }
        helper.assertTrue(delays.size() >= 100, "D5: only " + delays.size() + " different delays for 200 citizens");

        final BlockPos center = new BlockPos(100, 64, -40);
        final Set<BlockPos> spots = new HashSet<>();
        for (int id = 1; id <= 20; id++)
        {
            final BlockPos spot = EntityAISleep.homelessSpot(center, id);
            final double d = Math.sqrt(spot.distSqr(center));
            helper.assertTrue(d >= 5 && d <= 15 && spot.getY() == center.getY(), "D6: spot " + spot + " at " + d);
            spots.add(spot);
        }
        helper.assertTrue(spots.size() == 20 && EntityAISleep.homelessSpot(null, 3) == null, "D6: spots " + spots.size());

        helper.assertTrue(CitizenAI.idleStatus(true, 100, true) == VisibleCitizenStatus.LEISURE && CitizenAI.idleStatus(true, 100, false) == VisibleCitizenStatus.LEISURE
                            && CitizenAI.idleStatus(true, 0, false) == VisibleCitizenStatus.HOMELESS && CitizenAI.idleStatus(false, 100, false) == VisibleCitizenStatus.HOMELESS
                            && CitizenAI.idleStatus(false, 0, true) == VisibleCitizenStatus.HOUSE && CitizenAI.idleStatus(true, 0, true) == VisibleCitizenStatus.HOUSE, "B5: idle statuses");
        helper.assertTrue(VisibleCitizenStatus.LEISURE.getId() == 9 && VisibleCitizenStatus.HOMELESS.getId() == 10
                            && VisibleCitizenStatus.getForId(9) == VisibleCitizenStatus.LEISURE && VisibleCitizenStatus.getForId(10) == VisibleCitizenStatus.HOMELESS,
          "B5: ids " + VisibleCitizenStatus.LEISURE.getId() + " " + VisibleCitizenStatus.HOMELESS.getId());

        helper.assertTrue(CitizenAI.raidHideRange(false, false) == 32 && CitizenAI.raidHideRange(true, false) == 40 && CitizenAI.raidHideRange(false, true) == 8,
          "D3: ranges");
        helper.assertTrue(PathingStuckHandler.quickRescueDue(1200, 0) && !PathingStuckHandler.quickRescueDue(1190, 0) && PathingStuckHandler.quickRescueDue(2000, 800),
          "D4: timing");

        helper.assertTrue(CitizenAIStateMachine.backoff(1) == 40 && CitizenAIStateMachine.backoff(2) == 80 && CitizenAIStateMachine.backoff(5) == 400
                            && CitizenAIStateMachine.backoff(40) == 400, "B4: back-off");

        // B4: a transition that always fails: back-off 40, then 80, then the machine goes back to IDLE (3 failures in a row) and runs its reset.
        final AtomicLong time = new AtomicLong(1000);
        final CitizenAIStateMachine machine = new CitizenAIStateMachine(time::get, () -> "life test", 1);
        final AtomicInteger resets = new AtomicInteger();
        final AtomicInteger entered = new AtomicInteger();
        final List<Long> failedAt = new ArrayList<>();
        machine.setOnReset(resets::incrementAndGet);
        machine.addTransition(new TickingTransition<>(CitizenAIState.IDLE, () -> entered.get() == 0, () -> {
            entered.incrementAndGet();
            return CitizenAIState.SICK;
        }, 1));
        machine.addTransition(new TickingTransition<>(CitizenAIState.SICK, () -> true, () -> {
            failedAt.add(time.get());
            throw new IllegalStateException("life test failure " + failedAt.size());
        }, 1));
        long resetAt = -1;
        for (int t = 0; t < 400; t++)
        {
            machine.tick();
            if (resetAt < 0 && resets.get() > 0)
            {
                resetAt = time.get();
            }
            time.incrementAndGet();
        }
        helper.assertTrue(resets.get() == 1 && machine.getResets() == 1 && machine.getState() == CitizenAIState.IDLE, "B4: resets " + resets + " state " + machine.getState());
        helper.assertTrue(failedAt.size() == 3, "B4: failures " + failedAt);
        helper.assertTrue(failedAt.get(1) - failedAt.get(0) >= 39 && failedAt.get(2) - failedAt.get(1) >= 79, "B4: back-off not applied " + failedAt);
        helper.assertTrue(machine.getLogged() == 1 && machine.getSuppressed() == 2, "B4: log lines " + machine.getLogged() + " suppressed " + machine.getSuppressed());
        Log.getLogger().info("R13 LIFE RESULT pure_rules leisure_bound={} delays={} spots={} failures_at={} reset_at={}", bound, delays.size(), spots.size(), failedAt, resetAt);
        helper.succeed();
    }

    /**
     * D7 building cache (hits within 30 s, a new search after), D4 quick rescue (home trip moved when nobody is near, refused near a player or for a
     * destination that is not home, bed or restaurant), D7 watched flag.
     */
    public static void cacheRescueWatch(final GameTestHelper helper)
    {
        final IColony colony = MinecoloniesGameTests.foundGameTestColony(helper, "r13-life-rescue");
        helper.runAfterDelay(60, () -> {
            final ServerLevel level = helper.getLevel();
            final AtomicInteger searched = new AtomicInteger();
            final CachedBuildingLookup lookup = new CachedBuildingLookup(BuildingHospital.class);
            lookup.get(colony, 0, () -> { searched.incrementAndGet(); return null; });
            lookup.get(colony, 300, () -> { searched.incrementAndGet(); return null; });
            lookup.get(colony, CachedBuildingLookup.TTL - 1, () -> { searched.incrementAndGet(); return null; });
            helper.assertTrue(searched.get() == 1 && lookup.searches() == 1, "D7: searched " + searched + " times within the TTL");
            lookup.get(colony, CachedBuildingLookup.TTL, () -> { searched.incrementAndGet(); return null; });
            lookup.invalidate();
            lookup.get(colony, CachedBuildingLookup.TTL + 1, () -> { searched.incrementAndGet(); return null; });
            helper.assertTrue(searched.get() == 3, "D7: searched " + searched + " times after the TTL and an invalidate");
            final BlockPos hall = colony.getServerBuildingManager().getTownHall().getID();
            helper.assertTrue(lookup.get(colony, 10, () -> hall) == null, "D7: a cached none was replaced too early");

            final ICitizenData data = spawn(helper, colony, new BlockPos(34, 1, 26));
            final EntityCitizen citizen = (EntityCitizen) data.getEntity().get();
            citizen.setNoAi(true);
            final BlockPos home = data.getHomePosition();
            helper.assertTrue(home != null && citizen.getQuickRescueTarget(home) != null, "D4: the home position is not a rescue target: " + home);
            final BlockPos field = helper.absolutePos(new BlockPos(36, 1, 28));
            helper.assertTrue(citizen.getQuickRescueTarget(field) == null, "D4: an open field is a rescue target");

            final boolean playerNear = level.getNearestPlayer(citizen, PathingStuckHandler.QUICK_RESCUE_PLAYER_RANGE) != null
                                         || level.getNearestPlayer(home.getX(), home.getY(), home.getZ(), PathingStuckHandler.QUICK_RESCUE_PLAYER_RANGE, false) != null;
            final BlockPos before = citizen.blockPosition();
            final boolean moved = PathingStuckHandler.tryQuickRescue(citizen, home);
            final double toHome = Math.sqrt(citizen.blockPosition().distSqr(home));
            helper.assertTrue(moved != playerNear, "D4: moved " + moved + " with a player near " + playerNear);
            helper.assertTrue(!moved || toHome <= 9, "D4: rescued to " + citizen.blockPosition() + ", " + toHome + " from home");
            final BlockPos landed = citizen.blockPosition();
            helper.assertTrue(!moved || (level.getBlockState(landed).getCollisionShape(level, landed).isEmpty()
                                           && level.getBlockState(landed.above()).getCollisionShape(level, landed.above()).isEmpty()),
              "D4: rescued into a solid block at " + landed + ": " + level.getBlockState(landed));
            helper.assertTrue(moved || citizen.blockPosition().equals(before), "D4: moved without a rescue");

            // a player next to the citizen: no rescue, the citizen stays
            final BlockPos away = helper.absolutePos(new BlockPos(34, 1, 26));
            citizen.teleportTo(away.getX() + 0.5D, away.getY(), away.getZ() + 0.5D);
            final ServerPlayer player = MinecoloniesGameTests.makeConnectedSurvivalPlayer(helper);
            player.teleportTo(away.getX() + 2.5D, away.getY(), away.getZ() + 0.5D);
            helper.assertTrue(!PathingStuckHandler.tryQuickRescue(citizen, home) && citizen.blockPosition().equals(away), "D4: rescued in sight of a player");

            helper.runAfterDelay(45, () -> {
                helper.assertTrue(citizen.isWatchedByPlayer(), "D7: a citizen next to a player is not watched");
                final double range = Math.max(4, level.getServer().getPlayerList().getViewDistance()) * 16.0D;
                boolean any = false;
                for (final Player p : level.players())
                {
                    final double dx = p.getX() - citizen.getX();
                    final double dz = p.getZ() - citizen.getZ();
                    any |= !p.isSpectator() && dx * dx + dz * dz <= range * range;
                }
                helper.assertTrue(any, "D7: precondition");
                Log.getLogger().info("R13 LIFE RESULT cache_rescue_watch searched={} moved={} player_near={} to_home={}", searched, moved, playerNear, toHome);
                player.discard();
                helper.succeed();
            });
        });
    }

    /**
     * B2: without a hospital a sick citizen counts the time, recovers after a game day with full health, keeps the count in its save, and its AI rests at home.
     */
    public static void sickHome(final GameTestHelper helper)
    {
        final IColony colony = MinecoloniesGameTests.foundGameTestColony(helper, "r13-life-sick");
        helper.runAfterDelay(60, () -> {
            final Disease disease = DiseasesListener.getRandomDisease(RandomSource.create(13));
            helper.assertTrue(disease != null, "no disease loaded");
            final ICitizenData data = spawn(helper, colony, new BlockPos(20, 1, 16));
            data.getEntity().get().setNoAi(true);
            final CitizenDiseaseHandler handler = (CitizenDiseaseHandler) data.getCitizenDiseaseHandler();
            forceDisease(handler, disease);
            data.getEntity().get().setHealth(4.0F);
            handler.update(CitizenDiseaseHandler.HOME_RECOVERY_TICKS - 1);
            helper.assertTrue(handler.isSick() && handler.getSickWithoutHospitalTicks() == CitizenDiseaseHandler.HOME_RECOVERY_TICKS - 1, "B2: counted "
                                                                                                                                             + handler.getSickWithoutHospitalTicks());
            final CompoundTag tag = new CompoundTag();
            handler.write(tag);
            final CitizenDiseaseHandler copy = new CitizenDiseaseHandler(data);
            copy.read(tag);
            helper.assertTrue(copy.getSickWithoutHospitalTicks() == CitizenDiseaseHandler.HOME_RECOVERY_TICKS - 1 && copy.isSick(), "B2: the count is not saved");
            handler.update(1);
            helper.assertTrue(!handler.isSick() && handler.getSickWithoutHospitalTicks() == 0, "B2: not cured after a game day");
            helper.assertTrue(data.getEntity().get().getHealth() >= data.getEntity().get().getMaxHealth(), "B2: health " + data.getEntity().get().getHealth());

            // the AI of a sick citizen without a hospital ends in REST_AT_HOME, with the SICK status
            final ICitizenData walker = spawn(helper, colony, new BlockPos(24, 1, 20));
            walker.setSaturation(20);
            forceDisease((CitizenDiseaseHandler) walker.getCitizenDiseaseHandler(), disease);
            final EntityCitizen entity = (EntityCitizen) walker.getEntity().get();
            final List<String> seen = new ArrayList<>();
            final boolean[] rested = {false};
            for (int i = 1; i <= 12; i++)
            {
                helper.runAfterDelay(i * 40, () -> {
                    final IState state = entity.getCitizenAI().getState();
                    seen.add(String.valueOf(state));
                    rested[0] |= state == EntityAISickTask.DiseaseState.REST_AT_HOME;
                });
            }
            helper.runAfterDelay(12 * 40 + 5, () -> {
                helper.assertTrue(rested[0], "B2: the AI never rested at home: " + seen);
                helper.assertTrue(walker.getStatus() == VisibleCitizenStatus.SICK, "B2: status " + walker.getStatus());
                Log.getLogger().info("R13 LIFE RESULT sick_home states={}", seen);
                helper.succeed();
            });
        });
    }

    /**
     * B5: the status of a hungry citizen stays EAT, a mourner's stays MOURNING, an idle citizen without a home shows HOMELESS.
     */
    public static void statuses(final GameTestHelper helper)
    {
        final IColony colony = MinecoloniesGameTests.foundGameTestColony(helper, "r13-life-status");
        helper.runAfterDelay(60, () -> {
            final ICitizenData idle = spawn(helper, colony, new BlockPos(10, 1, 20));
            idle.setSaturation(20);
            final ICitizenData mourner = spawn(helper, colony, new BlockPos(20, 1, 20));
            mourner.setSaturation(20);
            mourner.getCitizenMournHandler().addDeceasedCitizen("Ann Smith");
            mourner.getCitizenMournHandler().setMourning(true);
            final ICitizenData hungry = spawn(helper, colony, new BlockPos(30, 1, 20));
            hungry.setSaturation(0);
            final List<String> log = new ArrayList<>();
            final int[] idleChecks = {0};
            for (int i = 1; i <= 6; i++)
            {
                helper.runAfterDelay(40 + i * 50, () -> {
                    log.add(id(idle.getStatus()) + "/" + id(mourner.getStatus()) + "/" + id(hungry.getStatus()));
                    helper.assertTrue(mourner.getStatus() == VisibleCitizenStatus.MOURNING, "B5: mourner shows " + mourner.getStatus() + " " + log);
                    helper.assertTrue(hungry.getStatus() == VisibleCitizenStatus.EAT, "B5: hungry citizen shows " + hungry.getStatus() + " " + log);
                    final EntityCitizen e = (EntityCitizen) idle.getEntity().get();
                    if (e.getCitizenAI().getState() == CitizenAIState.IDLE && idle.getHomeBuilding() == null && idle.getJob() == null)
                    {
                        idleChecks[0]++;
                        helper.assertTrue(idle.getStatus() == VisibleCitizenStatus.HOMELESS, "B5: idle citizen without home shows " + idle.getStatus() + " " + log);
                    }
                });
            }
            helper.runAfterDelay(40 + 6 * 50 + 5, () -> {
                Log.getLogger().info("R13 LIFE RESULT statuses samples={} idle_checks={}", log, idleChecks[0]);
                helper.succeed();
            });
        });
    }

    /**
     * D3: a raider 10 blocks away is within the hiding range; one about 45 blocks away is not.
     */
    public static void raidProximity(final GameTestHelper helper)
    {
        final IColony colony = MinecoloniesGameTests.foundGameTestColony(helper, "r13-life-raid");
        helper.runAfterDelay(60, () -> {
            final ICitizenData near = spawn(helper, colony, new BlockPos(6, 1, 6));
            final ICitizenData far = spawn(helper, colony, new BlockPos(38, 1, 30));
            near.getEntity().get().setNoAi(true);
            far.getEntity().get().setNoAi(true);
            final Entity raider = ModEntities.BARBARIAN.create(helper.getLevel(), EntitySpawnReason.EVENT);
            helper.assertTrue(raider != null, "no barbarian");
            final BlockPos at = helper.absolutePos(new BlockPos(-6, 1, -4));
            raider.setPos(at.getX() + 0.5D, at.getY(), at.getZ() + 0.5D);
            ((Mob) raider).setNoAi(true);
            helper.getLevel().addFreshEntity(raider);
            final boolean nearHides = CitizenAI.raiderWithin(near.getEntity().get(), CitizenAI.raidHideRange(false, false));
            final boolean farHides = CitizenAI.raiderWithin(far.getEntity().get(), CitizenAI.raidHideRange(false, false));
            final boolean nearIndoors = CitizenAI.raiderWithin(near.getEntity().get(), CitizenAI.raidHideRange(false, true));
            raider.discard();
            helper.assertTrue(nearHides && !farHides && !nearIndoors, "D3: near " + nearHides + " far " + farHides + " near indoors " + nearIndoors);
            Log.getLogger().info("R13 LIFE RESULT raid near={} far={} indoors={}", nearHides, farHides, nearIndoors);
            helper.succeed();
        });
    }

    /**
     * B4: a citizen whose AI fails three times in a row goes back to IDLE and carries on.
     */
    public static void aiException(final GameTestHelper helper)
    {
        final IColony colony = MinecoloniesGameTests.foundGameTestColony(helper, "r13-life-exception");
        helper.runAfterDelay(60, () -> {
            final ICitizenData data = spawn(helper, colony, new BlockPos(16, 1, 16));
            data.setSaturation(20);
            final EntityCitizen citizen = (EntityCitizen) data.getEntity().get();
            helper.assertTrue(citizen.getCitizenAI() instanceof CitizenAIStateMachine, "B4: the citizen AI is a " + citizen.getCitizenAI().getClass());
            final CitizenAIStateMachine machine = (CitizenAIStateMachine) citizen.getCitizenAI();
            final AtomicInteger thrown = new AtomicInteger();
            final int resetsBefore = machine.getResets();
            citizen.getCitizenAI().addTransition(new AIEventTarget<IState>(AIBlockingEventType.EVENT, () -> {
                if (thrown.get() < 3)
                {
                    thrown.incrementAndGet();
                    throw new IllegalStateException("life test: citizen AI failure " + thrown.get());
                }
                return false;
            }, () -> null, 1));
            helper.runAfterDelay(400, () -> {
                helper.assertTrue(thrown.get() == 3, "B4: thrown " + thrown);
                helper.assertTrue(machine.getResets() == resetsBefore + 1, "B4: resets " + machine.getResets());
                helper.assertTrue(citizen.isAlive() && machine.getState() != null, "B4: citizen gone");
                Log.getLogger().info("R13 LIFE RESULT ai_exception thrown={} resets={} state={} logged={}", thrown, machine.getResets(), machine.getState(), machine.getLogged());
                helper.succeed();
            });
        });
    }

    private static String id(final VisibleCitizenStatus status)
    {
        return status == null ? "none" : String.valueOf(status.getId());
    }

    private static ICitizenData spawn(final GameTestHelper helper, final IColony colony, final BlockPos rel)
    {
        final BlockPos anchor = helper.absolutePos(rel);
        helper.getLevel().setChunkForced(anchor.getX() >> 4, anchor.getZ() >> 4, true);
        final ICitizenData citizen = colony.getCitizenManager().spawnOrCreateCivilian(null, helper.getLevel(), List.of(anchor), true);
        helper.assertTrue(citizen != null && citizen.getEntity().isPresent(), "could not spawn a citizen at " + anchor);
        return citizen;
    }

    /**
     * Sets a disease without the population floor check (setDisease refuses in a small colony).
     */
    private static void forceDisease(final CitizenDiseaseHandler handler, final Disease disease)
    {
        try
        {
            final java.lang.reflect.Field field = CitizenDiseaseHandler.class.getDeclaredField("disease");
            field.setAccessible(true);
            field.set(handler, disease);
        }
        catch (final ReflectiveOperationException e)
        {
            throw new IllegalStateException(e);
        }
    }
}
