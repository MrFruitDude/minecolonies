package com.minecolonies.core.gametest;

import com.ldtteam.structurize.util.RotationMirror;
import com.minecolonies.api.blocks.ModBlocks;
import com.minecolonies.api.colony.ColonyState;
import com.minecolonies.api.colony.ICitizenData;
import com.minecolonies.api.colony.IColony;
import com.minecolonies.api.colony.IColonyManager;
import com.minecolonies.api.colony.buildings.IBuilding;
import com.minecolonies.api.colony.faction.FactionActionResult;
import com.minecolonies.api.colony.faction.IFactionColonyActions;
import com.minecolonies.api.colony.permissions.Action;
import com.minecolonies.api.colony.permissions.FactionPlayerRank;
import com.minecolonies.api.colony.permissions.IPermissions;
import com.minecolonies.api.util.WorldUtil;
import com.minecolonies.core.MineColonies;
import com.minecolonies.core.colony.Colony;
import com.minecolonies.core.colony.ColonyLimits;
import com.minecolonies.core.colony.FactionConfig;
import com.minecolonies.core.colony.buildings.modules.BuildingModules;
import com.minecolonies.core.colony.buildings.modules.settings.BoolSetting;
import com.minecolonies.core.colony.buildings.workerbuildings.BuildingTownHall;
import com.minecolonies.core.colony.workorders.WorkOrderBuilding;
import com.minecolonies.core.tileentities.TileEntityColonyBuilding;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.DyeColor;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BannerPatternLayers;
import net.minecraft.world.level.block.entity.BannerPatterns;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;

import static com.minecolonies.core.gametest.MinecoloniesGameTests.makeConnectedSurvivalPlayer;

/**
 * P5b-MC: the NPC faction owner type for AI neighbour colonies (faction-owned colonies, simulation driver, actions without a player,
 * no raids). Each test runs in its own batch (see the registrar); factions colonies are created through the production API
 * {@link IColonyManager#createFactionColony}.
 */
public final class FactionColonyGameTests
{
    private FactionColonyGameTests()
    {
    }

    private static final String PACK = "Minecolonies Original";

    /**
     * Relative x of colonies that must be far enough from the first one: minColonyDistance is 8 chunks (128 blocks).
     */
    private static final int FAR_X = 200;

    /**
     * Lays a stone pad around the position (so the town hall and huts stand on something) and loads its chunk.
     *
     * @return the absolute position of the relative position.
     */
    private static BlockPos pad(final GameTestHelper helper, final int relX, final int relZ, final int radius)
    {
        final ServerLevel level = helper.getLevel();
        final BlockPos pos = helper.absolutePos(new BlockPos(relX, 1, relZ));
        level.setChunkForced(pos.getX() >> 4, pos.getZ() >> 4, true);
        for (int x = -radius; x <= radius; x++)
        {
            for (int z = -radius; z <= radius; z++)
            {
                level.setBlock(pos.offset(x, -1, z), Blocks.STONE.defaultBlockState(), 3);
                for (int y = 0; y < 12; y++)
                {
                    level.setBlock(pos.offset(x, y, z), Blocks.AIR.defaultBlockState(), 3);
                }
            }
        }
        return pos;
    }

    private static IColony faction(final GameTestHelper helper, final BlockPos hall, final String factionId, final String name)
    {
        return IColonyManager.getInstance().createFactionColony(helper.getLevel(), hall, factionId, Component.literal(name), PACK, 0xAA0000, null);
    }

    // ------------------------------------------------------------------------------------------------------------

    /**
     * A faction colony has the faction owner, the faction's identity, a town hall, and players are neutral in it and can
     * never be owner or officer; the claim distance applies; a player taking it over ends the faction ownership.
     */
    public static void create_and_permissions(final GameTestHelper helper)
    {
        final ServerLevel level = helper.getLevel();
        final IColonyManager manager = IColonyManager.getInstance();
        final BlockPos hall = pad(helper, 2, 2, 8);
        final BannerPatternLayers banner = new BannerPatternLayers.Builder().add(com.minecolonies.api.util.Utils.getRegistryValue(BannerPatterns.BASE, level), DyeColor.RED).build();

        final IColony colony = manager.createFactionColony(level, hall, "orcs", Component.literal("Orc Hold"), PACK, 0xAA0000, banner);
        helper.assertTrue(colony != null, "createFactionColony returned null");
        final IPermissions permissions = colony.getPermissions();
        helper.assertTrue(permissions.isFactionOwned() && "orcs".equals(permissions.getFactionId()), "not faction owned: " + permissions.getFactionId());
        final UUID expectedOwner = UUID.nameUUIDFromBytes("minecolonies:faction:orcs".getBytes(java.nio.charset.StandardCharsets.UTF_8));
        helper.assertTrue(permissions.getOwner().equals(expectedOwner) && expectedOwner.equals(IPermissions.factionOwnerId("orcs")),
          "owner is " + permissions.getOwner() + ", expected " + expectedOwner);
        helper.assertTrue(colony.getName().equals("Orc Hold"), "name " + colony.getName());
        helper.assertTrue(PACK.equals(colony.getStructurePack()), "pack " + colony.getStructurePack());
        helper.assertTrue(colony.getTeamColonyColor() == ChatFormatting.DARK_RED, "colour " + colony.getTeamColonyColor());
        helper.assertTrue(banner.equals(colony.getColonyFlag()), "banner differs");
        helper.assertTrue(colony.getServerBuildingManager().hasTownHall(), "no town hall registered");
        helper.assertTrue(colony.getServerBuildingManager().getTownHall().getPosition().equals(hall), "town hall at " + colony.getServerBuildingManager().getTownHall().getPosition());
        helper.assertTrue(colony.getServerBuildingManager().getTownHall().getBuildingLevel() == 0, "town hall level " + colony.getServerBuildingManager().getTownHall().getBuildingLevel());
        helper.assertTrue(colony.getCenter().equals(hall), "center " + colony.getCenter());
        helper.assertTrue(manager.getFactionColonies("orcs").size() == 1 && manager.getFactionColonies("orcs").get(0) == colony, "faction lookup");
        helper.assertTrue(manager.getFactionColonies("elves").isEmpty(), "other faction has colonies");
        helper.assertTrue(manager.getColonyByPosFromWorld(level, hall) == colony, "colony not found by position (claim)");

        // Players: neutral, never a manager.
        final ServerPlayer player = makeConnectedSurvivalPlayer(helper);
        helper.assertTrue(permissions.getRank(player) == permissions.getRankNeutral(), "default rank is " + permissions.getRank(player).getName());
        helper.assertTrue(!permissions.hasPermission(player, Action.PLACE_HUTS) && !permissions.hasPermission(player, Action.EDIT_PERMISSIONS)
                            && !permissions.hasPermission(player, Action.BREAK_BLOCKS) && !permissions.hasPermission(player, Action.MANAGE_HUTS),
          "a neutral player may manage the faction colony");
        helper.assertTrue(!permissions.setPlayerRank(player.getUUID(), permissions.getRankOwner(), level), "a player was made owner");
        helper.assertTrue(!permissions.setPlayerRank(player.getUUID(), permissions.getRankOfficer(), level), "a player was made officer");
        helper.assertTrue(!permissions.addPlayer(player.nameAndId(), permissions.getRankOfficer()), "a player was added as officer");
        helper.assertTrue(!permissions.addPlayer(player.getUUID(), "test-mock-player", permissions.getRankOwner()), "a player was added as owner");
        helper.assertTrue(permissions.getRank(player) == permissions.getRankNeutral(), "rank changed by refused attempts");
        helper.assertTrue(permissions.getPlayersByRank(permissions.getRankOwner()).size() == 1
                            && permissions.getPlayersByRank(permissions.getRankOfficer()).isEmpty(), "managers: " + permissions.getPlayers());
        // A player may be friend or hostile individually.
        helper.assertTrue(permissions.setPlayerRank(player.getUUID(), permissions.getRankHostile(), level) && permissions.getRank(player).isHostile(), "hostile not set");
        helper.assertTrue(permissions.setPlayerRank(player.getUUID(), permissions.getRankFriend(), level), "friend not set");
        permissions.removePlayer(player.getUUID());
        helper.assertTrue(permissions.getRank(player) == permissions.getRankNeutral(), "rank after remove");

        // The configured default.
        try
        {
            FactionConfig.overrideDefaultPlayerRank(FactionPlayerRank.FRIEND);
            helper.assertTrue(permissions.getRank(player) == permissions.getRankFriend(), "configured friend default: " + permissions.getRank(player).getName());
            FactionConfig.overrideDefaultPlayerRank(FactionPlayerRank.HOSTILE);
            helper.assertTrue(permissions.getRank(player).isHostile(), "configured hostile default");
        }
        finally
        {
            FactionConfig.overrideDefaultPlayerRank(null);
        }
        helper.assertTrue(permissions.getRank(player) == permissions.getRankNeutral(), "default restored");

        // Distance rule: another colony inside minColonyDistance is refused, one far away is fine.
        final BlockPos near = pad(helper, 40, 2, 3);
        helper.assertTrue(faction(helper, near, "orcs", "Too near") == null, "colony created inside minColonyDistance");
        final BlockPos far = pad(helper, FAR_X, 2, 3);
        final IColony second = faction(helper, far, "orcs", "Orc Outpost");
        helper.assertTrue(second != null, "far faction colony not created");
        helper.assertTrue(manager.getFactionColonies("orcs").size() == 2 && manager.getFactionColonies("orcs").get(0) == colony, "two colonies, oldest first");
        helper.assertTrue(second.getID() != colony.getID(), "same id");

        // A player taking the colony over ends the faction ownership.
        permissions.setOwner(player);
        helper.assertTrue(!permissions.isFactionOwned() && permissions.getFactionId() == null && permissions.getOwner().equals(player.getUUID()), "still faction owned after setOwner");
        helper.assertTrue(manager.getFactionColonies("orcs").size() == 1, "taken colony still listed for the faction");
        helper.assertTrue(manager.getIColoniesByOwner(player.getUUID()).contains(colony), "taken colony not in the player's index");

        level.setChunkForced(hall.getX() >> 4, hall.getZ() >> 4, false);
        level.setChunkForced(far.getX() >> 4, far.getZ() >> 4, false);
        helper.succeed();
    }

    /**
     * The faction id, owner, permissions and banner survive a save and load of the colony's data; a faction colony stays a
     * faction colony and a player colony does not become one.
     */
    public static void persistence(final GameTestHelper helper)
    {
        final ServerLevel level = helper.getLevel();
        final BlockPos hall = pad(helper, 2, 2, 8);
        final IColony colony = faction(helper, hall, "elves", "Elf Haven");
        helper.assertTrue(colony != null, "no faction colony");
        final ServerPlayer player = makeConnectedSurvivalPlayer(helper);
        colony.getPermissions().setPlayerRank(player.getUUID(), colony.getPermissions().getRankFriend(), level);

        final CompoundTag tag = colony.getColonyTag();
        final Colony loaded = Colony.loadColony(tag, null, level.registryAccess());
        helper.assertTrue(loaded != null, "colony did not load");
        final IPermissions permissions = loaded.getPermissions();
        helper.assertTrue(permissions.isFactionOwned() && "elves".equals(permissions.getFactionId()), "faction id lost: " + permissions.getFactionId());
        helper.assertTrue(permissions.getOwner().equals(IPermissions.factionOwnerId("elves")), "owner after load " + permissions.getOwner());
        helper.assertTrue(permissions.getRank(IPermissions.factionOwnerId("elves")).getId() == IPermissions.OWNER_RANK_ID, "faction is not the owner after load");
        helper.assertTrue(permissions.getRank(player.getUUID()).getId() == IPermissions.FRIEND_RANK_ID, "individual rank lost");
        helper.assertTrue(permissions.getRank(UUID.randomUUID()) == permissions.getRankNeutral(), "default rank after load");
        helper.assertTrue(loaded.getName().equals("Elf Haven") && loaded.getCenter().equals(hall), "identity after load");
        helper.assertTrue(!permissions.getOwnerName().equals("[abandoned]"), "loaded as abandoned: " + permissions.getOwnerName());

        // A tag without a faction id (a colony of an old save) is no faction colony.
        final CompoundTag old = colony.getColonyTag();
        old.remove("faction_id");
        final Colony notFaction = Colony.loadColony(old, null, level.registryAccess());
        helper.assertTrue(notFaction != null && !notFaction.getPermissions().isFactionOwned(), "colony without faction id is faction owned");

        level.setChunkForced(hall.getX() >> 4, hall.getZ() >> 4, false);
        helper.succeed();
    }

    /**
     * Faction colonies are not in the owner index of any player, do not count towards colonies.maxPerPlayer, do not make
     * founding refuse a player, and are not what a player's lookup finds. The player's own colony is unaffected.
     */
    public static void index_and_limits(final GameTestHelper helper)
    {
        final ServerLevel level = helper.getLevel();
        final IColonyManager manager = IColonyManager.getInstance();
        ColonyLimits.overrideMaxPerPlayer(1);
        final ServerPlayer player = makeConnectedSurvivalPlayer(helper);

        final BlockPos factionHall = pad(helper, 2, 2, 8);
        final IColony faction = faction(helper, factionHall, "dwarves", "Dwarf Hold");
        helper.assertTrue(faction != null, "no faction colony");
        final UUID factionOwner = faction.getPermissions().getOwner();
        helper.assertTrue(manager.getIColoniesByOwner(factionOwner).isEmpty(), "faction colony is in the owner index of the faction owner id");
        helper.assertTrue(manager.getIColonyByOwner(level, factionOwner) == null, "faction colony found by owner lookup");
        helper.assertTrue(manager.getIColoniesByOwner(player.getUUID()).isEmpty(), "player owns colonies");
        helper.assertTrue(manager.checkFounding(level, helper.absolutePos(new BlockPos(FAR_X, 1, 2)), player, true).isEmpty(), "a faction colony counts towards the founding limit");

        // The player founds a colony of their own.
        final BlockPos playerHall = pad(helper, FAR_X, 2, 3);
        level.setBlock(playerHall, ModBlocks.blockHutTownHall.defaultBlockState(), 3);
        final TileEntityColonyBuilding hut = (TileEntityColonyBuilding) level.getBlockEntity(playerHall);
        hut.setPackName(PACK);
        hut.setBlueprintPath("fundamentals/townhall1.blueprint");
        final IColony own = manager.createColony(level, playerHall, player, "Player colony", PACK);
        helper.assertTrue(own != null, "player colony not created");
        own.getServerBuildingManager().addNewBuilding(hut, level);
        helper.assertTrue(manager.getIColoniesByOwner(player.getUUID()).size() == 1 && manager.getIColoniesByOwner(player.getUUID()).get(0) == own, "player index: " + manager.getIColoniesByOwner(player.getUUID()));
        helper.assertTrue(manager.getIColonyByOwner(level, player.getUUID()) == own, "player lookup finds " + manager.getIColonyByOwner(level, player.getUUID()));
        helper.assertTrue(manager.checkFounding(level, helper.absolutePos(new BlockPos(2 * FAR_X, 1, 2)), player, true).isPresent(), "limit 1 not reached with one player colony");
        helper.assertTrue(manager.getSelectedColony(player.getUUID()) == own, "selected colony");
        helper.assertTrue(manager.computeOwnedColonySummaries(player.getUUID()).size() == 1, "owned colony summaries include the faction colony");
        helper.assertTrue(own.getPermissions().getRank(player).getId() == IPermissions.OWNER_RANK_ID && !own.getPermissions().isFactionOwned(), "player colony changed");
        helper.assertTrue(manager.getFactionColonies("dwarves").size() == 1 && manager.getFactionColonies("dwarves").get(0) == faction, "faction lookup finds a player colony");

        // Deleting the faction colony leaves the player's alone.
        manager.deleteColonyByWorld(faction.getID(), false, level);
        helper.assertTrue(manager.getFactionColonies("dwarves").isEmpty(), "deleted faction colony still listed");
        helper.assertTrue(manager.getIColoniesByOwner(player.getUUID()).size() == 1, "player colony gone");
        level.setChunkForced(factionHall.getX() >> 4, factionHall.getZ() >> 4, false);
        level.setChunkForced(playerHall.getX() >> 4, playerHall.getZ() >> 4, false);
        helper.succeed();
    }

    /**
     * The simulation driver: a faction colony is ACTIVE with no player when the driver wants it and its chunks are loaded,
     * UNLOADED (not ticking) when the driver wants it and they are not, INACTIVE when it does not, and the force active flag
     * needs the driver instead of a manager. A colony that is not faction-owned takes no driver.
     */
    public static void driver_state(final GameTestHelper helper)
    {
        final ServerLevel level = helper.getLevel();
        final BlockPos hall = pad(helper, 2, 2, 8);
        final IColony colony = faction(helper, hall, "goblins", "Goblin Camp");
        helper.assertTrue(colony != null, "no faction colony");
        helper.assertTrue(colony.getPackageManager().getImportantColonyPlayers().isEmpty() && colony.getPackageManager().getCloseSubscribers().isEmpty(), "fixture: players around");
        helper.assertTrue(colony.getLoadedChunkCount() > 0, "fixture: no loaded chunks");
        helper.assertTrue(colony.getSimulationDriver() == null, "driver by default");
        final long hallChunk = ChunkPos.containing(hall).pack();
        helper.assertTrue(colony.getLoadedChunks().contains(hallChunk), "fixture: the town hall chunk is not loaded");
        final AtomicBoolean wants = new AtomicBoolean(false);
        final List<Long> removed = new ArrayList<>(colony.getLoadedChunks());

        // No driver: a colony nobody is close to does not simulate.
        helper.runAfterDelay(130, () -> {
            helper.assertTrue(colony.getState() == ColonyState.INACTIVE, "without a driver: " + colony.getState());

            // A driver that does not want: still not simulating.
            colony.setSimulationDriver(wants::get);
            helper.assertTrue(colony.getSimulationDriver() != null && colony.getState() == ColonyState.INACTIVE, "driver that does not want: " + colony.getState());

            // A driver that wants and loaded chunks: ACTIVE at once, without any player.
            wants.set(true);
            colony.setSimulationDriver(wants::get);
            helper.assertTrue(colony.getState() == ColonyState.ACTIVE, "driver wants, chunks loaded: " + colony.getState());
            helper.runAfterDelay(130, () -> {
                helper.assertTrue(colony.getState() == ColonyState.ACTIVE, "left ACTIVE by itself: " + colony.getState());
                helper.assertTrue(colony.getPackageManager().getCloseSubscribers().isEmpty() && colony.getPackageManager().getImportantColonyPlayers().isEmpty(), "a player appeared");

                // The driver stops wanting: back to INACTIVE at the next state check.
                wants.set(false);
                helper.runAfterDelay(130, () -> {
                    helper.assertTrue(colony.getState() == ColonyState.INACTIVE, "driver stopped wanting: " + colony.getState());

                    // The driver wants, but the chunks are not loaded: no simulation.
                    new ArrayList<>(colony.getLoadedChunks()).forEach(colony::removeLoadedChunk);
                    wants.set(true);
                    helper.runAfterDelay(130, () -> {
                        helper.assertTrue(colony.getState() == ColonyState.UNLOADED, "driver wants, chunks unloaded: " + colony.getState());
                        for (final long chunk : removed)
                        {
                            colony.addLoadedChunk(chunk, level.getChunk(ChunkPos.getX(chunk), ChunkPos.getZ(chunk)));
                        }
                        helper.runAfterDelay(130, () -> {
                            helper.assertTrue(colony.getState() == ColonyState.ACTIVE, "chunks loaded again: " + colony.getState());

                            // Force active needs the driver to want it (there is no manager).
                            colony.setSimulationDriver(null);
                            helper.assertTrue(colony.getState() == ColonyState.INACTIVE, "driver removed: " + colony.getState());
                            colony.setForceActive(true);
                            helper.assertTrue(colony.getState() == ColonyState.INACTIVE && colony.getTicketedChunks().isEmpty(), "force active without a driver simulates: " + colony.getState());
                            colony.setSimulationDriver(wants::get);
                            helper.assertTrue(colony.getState() == ColonyState.ACTIVE, "force active with the driver wanting: " + colony.getState());
                            helper.assertTrue(colony.getTicketedChunks().contains(hallChunk), "town hall chunk not ticketed: " + colony.getTicketedChunks());
                            helper.assertTrue(colony.getTicketedChunks().size() <= Colony.MAX_FORCE_ACTIVE_CHUNKS, "ticket bound");
                            colony.setForceActive(false);
                            helper.assertTrue(colony.getState() == ColonyState.ACTIVE, "driver keeps it active: " + colony.getState());

                            // A throwing driver does not simulate and does not break the colony.
                            colony.setSimulationDriver(() -> { throw new IllegalStateException("test driver"); });
                            wants.set(false);
                            helper.runAfterDelay(130, () -> {
                                helper.assertTrue(colony.getState() != ColonyState.ACTIVE, "throwing driver: " + colony.getState());

                                // Only faction colonies take a driver.
                                colony.setSimulationDriver(null);
                                final ServerPlayer player = makeConnectedSurvivalPlayer(helper);
                                colony.getPermissions().setOwner(player);
                                boolean threw = false;
                                try
                                {
                                    colony.setSimulationDriver(() -> true);
                                }
                                catch (final IllegalStateException e)
                                {
                                    threw = true;
                                }
                                helper.assertTrue(threw && colony.getSimulationDriver() == null, "a player colony took a driver");
                                level.setChunkForced(hall.getX() >> 4, hall.getZ() >> 4, false);
                                helper.succeed();
                            });
                        });
                    });
                });
            });
        });
    }

    /**
     * Without a player: a hut is placed from a supplied item, its building registered, an upgrade requested (a work order
     * a builder takes), a citizen hired and fired, settings changed. Every action refuses a colony that a player owns.
     */
    public static void actions_without_player(final GameTestHelper helper)
    {
        final ServerLevel level = helper.getLevel();
        final IColonyManager manager = IColonyManager.getInstance();
        final IFactionColonyActions actions = manager.getFactionColonyActions();
        // Flat ground for the town hall and the huts, before anything is placed on it.
        pad(helper, 14, 2, 14);
        pad(helper, 32, 2, 8);
        final BlockPos hall = pad(helper, 2, 2, 3);
        final IColony colony = faction(helper, hall, "traders", "Trade Post");
        helper.assertTrue(colony != null, "no faction colony");

        // Place a builder hut from a supplied item.
        final ItemStack builderItem = new ItemStack(ModBlocks.blockHutBuilder, 2);
        final BlockPos builderPos = helper.absolutePos(new BlockPos(14, 1, 2));
        final FactionActionResult placed = actions.placeHut(colony, builderItem, builderPos, RotationMirror.NONE, PACK, "fundamentals/builder1.blueprint");
        helper.assertTrue(placed == FactionActionResult.OK, "builder hut not placed: " + placed);
        helper.assertTrue(builderItem.getCount() == 1, "hut item count " + builderItem.getCount());
        helper.assertBlockPresent(ModBlocks.blockHutBuilder, new BlockPos(14, 1, 2));
        final IBuilding builder = colony.getServerBuildingManager().getBuilding(builderPos);
        helper.assertTrue(builder != null && builder.getBuildingLevel() == 0 && builder.getBuildingType() == com.minecolonies.api.colony.buildings.ModBuildings.builder.get(), "builder building: " + builder);
        helper.assertTrue(PACK.equals(builder.getStructurePack()) && "fundamentals/builder1.blueprint".equals(builder.getBlueprintPath()), "pack/path on the building");

        // Refusals.
        helper.assertTrue(actions.placeHut(colony, ItemStack.EMPTY, builderPos.offset(6, 0, 0), RotationMirror.NONE, PACK, "fundamentals/builder1.blueprint") == FactionActionResult.NO_HUT_ITEM, "empty item");
        helper.assertTrue(actions.placeHut(colony, new ItemStack(ModBlocks.blockHutWareHouse), builderPos.offset(6, 0, 0), RotationMirror.NONE, PACK, "fundamentals/builder1.blueprint") == FactionActionResult.BLUEPRINT_UNAVAILABLE, "wrong hut for the blueprint");
        helper.assertTrue(actions.placeHut(colony, new ItemStack(ModBlocks.blockHutBuilder), builderPos.offset(6, 0, 0), RotationMirror.NONE, "No such pack", "fundamentals/builder1.blueprint") == FactionActionResult.BLUEPRINT_UNAVAILABLE, "unknown pack");
        helper.assertTrue(actions.placeHut(colony, new ItemStack(ModBlocks.blockHutBuilder), builderPos.offset(120, 0, 0), RotationMirror.NONE, PACK, "fundamentals/builder1.blueprint") == FactionActionResult.OUTSIDE_COLONY, "outside the claim");
        helper.assertTrue(actions.placeHut(colony, new ItemStack(ModBlocks.blockHutTownHall), builderPos.offset(6, 0, 0), RotationMirror.NONE, PACK, "fundamentals/townhall1.blueprint") == FactionActionResult.PLACEMENT_REFUSED, "second town hall");

        // No builder yet: nothing builds the hut, and a level 0 building has nothing to repair.
        helper.assertTrue(actions.requestUpgrade(builder, BlockPos.ZERO) == FactionActionResult.REFUSED, "upgrade without a builder");
        helper.assertTrue(actions.requestRepair(builder, BlockPos.ZERO) == FactionActionResult.REFUSED, "repair of a level 0 building");
        helper.assertTrue(colony.getWorkManager().getWorkOrdersOfType(WorkOrderBuilding.class).isEmpty(), "work order without a builder");

        // Hire a (data only) builder through the API; the builder hut can build itself.
        final ICitizenData citizen = colony.getCitizenManager().createAndRegisterCivilianData();
        final int moduleId = builder.getModule(BuildingModules.BUILDER_WORK).getProducer().getRuntimeID();
        helper.assertTrue(actions.hire(builder, moduleId, citizen) == FactionActionResult.OK, "hire");
        helper.assertTrue(builder.getModule(BuildingModules.BUILDER_WORK).hasAssignedCitizen(citizen), "citizen not assigned");
        helper.assertTrue(actions.hire(builder, -12345, citizen) == FactionActionResult.NOT_POSSIBLE, "hire into a missing module");
        helper.assertTrue(actions.requestUpgrade(builder, BlockPos.ZERO) == FactionActionResult.OK, "builder hut upgrade request");
        helper.assertTrue(colony.getWorkManager().getWorkOrdersOfType(WorkOrderBuilding.class).stream().anyMatch(o -> o.getLocation().equals(builder.getID())), "no work order for the builder hut");
        helper.assertTrue(actions.requestUpgrade(builder, BlockPos.ZERO) == FactionActionResult.ALREADY_REQUESTED, "second upgrade request");

        final ItemStack wareItem = new ItemStack(ModBlocks.blockHutWareHouse);
        final BlockPos warePos = helper.absolutePos(new BlockPos(30, 1, 2));
        helper.assertTrue(actions.placeHut(colony, wareItem, warePos, RotationMirror.NONE, PACK, "craftsmanship/storage/warehouse1.blueprint") == FactionActionResult.OK, "warehouse not placed");
        helper.assertTrue(wareItem.isEmpty(), "warehouse item not used up");
        final IBuilding warehouse = colony.getServerBuildingManager().getBuilding(warePos);
        helper.assertTrue(warehouse != null && warehouse.getBuildingLevel() == 0, "warehouse building");

        // A builder hut that is not built yet can not build the warehouse; once it is (level 1), the request goes through.
        helper.assertTrue(actions.requestUpgrade(warehouse, BlockPos.ZERO) == FactionActionResult.REFUSED, "warehouse upgrade by an unbuilt builder hut");
        builder.setBuildingLevel(1);
        helper.assertTrue(actions.requestUpgrade(warehouse, BlockPos.ZERO) == FactionActionResult.OK, "warehouse upgrade request");
        helper.assertTrue(colony.getWorkManager().getWorkOrdersOfType(WorkOrderBuilding.class).stream().anyMatch(o -> o.getLocation().equals(warehouse.getID())), "no work order for the warehouse");
        helper.assertTrue(actions.requestRepair(warehouse, BlockPos.ZERO) == FactionActionResult.ALREADY_REQUESTED, "repair while ordered");
        helper.assertTrue(actions.pickUpHut(warehouse).isEmpty(), "picked up a building that is not deconstructed");
        helper.assertTrue(actions.fire(builder, moduleId, citizen) == FactionActionResult.OK && !builder.getModule(BuildingModules.BUILDER_WORK).hasAssignedCitizen(citizen), "fire");

        // Settings: colony and building.
        final boolean before = colony.getSettings().getSetting(BuildingTownHall.MOVE_IN).getValue();
        helper.assertTrue(actions.setColonySetting(colony, BuildingTownHall.MOVE_IN, new BoolSetting(!before)) == FactionActionResult.OK, "colony setting");
        helper.assertTrue(colony.getSettings().getSetting(BuildingTownHall.MOVE_IN).getValue() == !before, "colony setting not changed");
        helper.assertTrue(actions.setColonySetting(colony, com.minecolonies.core.colony.buildings.AbstractBuilding.BREEDING, new BoolSetting(true)) == FactionActionResult.NOT_POSSIBLE, "a setting the colony does not have");

        // Every action refuses a colony that a player owns, and a building of another colony.
        final ServerPlayer player = makeConnectedSurvivalPlayer(helper);
        final BlockPos playerHall = pad(helper, FAR_X, 2, 8);
        level.setBlock(playerHall, ModBlocks.blockHutTownHall.defaultBlockState(), 3);
        final TileEntityColonyBuilding hut = (TileEntityColonyBuilding) level.getBlockEntity(playerHall);
        hut.setPackName(PACK);
        hut.setBlueprintPath("fundamentals/townhall1.blueprint");
        final IColony own = manager.createColony(level, playerHall, player, "Player colony", PACK);
        helper.assertTrue(own != null, "player colony not created");
        final IBuilding ownHall = own.getServerBuildingManager().addNewBuilding(hut, level);
        final ItemStack item = new ItemStack(ModBlocks.blockHutBuilder);
        helper.assertTrue(actions.placeHut(own, item, playerHall.offset(8, 0, 0), RotationMirror.NONE, PACK, "fundamentals/builder1.blueprint") == FactionActionResult.NOT_FACTION_COLONY && item.getCount() == 1, "placeHut in a player colony");
        helper.assertTrue(actions.requestUpgrade(ownHall, BlockPos.ZERO) == FactionActionResult.NOT_FACTION_COLONY, "upgrade in a player colony");
        helper.assertTrue(actions.requestRepair(ownHall, BlockPos.ZERO) == FactionActionResult.NOT_FACTION_COLONY, "repair in a player colony");
        helper.assertTrue(actions.requestRemoval(ownHall, BlockPos.ZERO) == FactionActionResult.NOT_FACTION_COLONY, "removal in a player colony");
        helper.assertTrue(actions.hire(ownHall, 0, citizen) == FactionActionResult.NOT_FACTION_COLONY, "hire in a player colony");
        helper.assertTrue(actions.setColonySetting(own, BuildingTownHall.MOVE_IN, new BoolSetting(false)) == FactionActionResult.NOT_FACTION_COLONY, "setting in a player colony");
        helper.assertTrue(actions.setBuildingSetting(ownHall, BuildingTownHall.MOVE_IN, new BoolSetting(false)) == FactionActionResult.NOT_FACTION_COLONY, "building setting in a player colony");
        helper.assertTrue(own.getWorkManager().getWorkOrdersOfType(WorkOrderBuilding.class).isEmpty(), "work order in the player colony");
        // The permission-checked player path is untouched: a player is not a manager of the faction colony.
        helper.assertTrue(!colony.getPermissions().hasPermission(player, Action.PLACE_HUTS), "player may place huts in the faction colony");

        for (final BlockPos pos : List.of(hall, playerHall))
        {
            level.setChunkForced(pos.getX() >> 4, pos.getZ() >> 4, false);
        }
        helper.succeed();
    }

    /**
     * Faction colonies are not raided by default, even while they simulate; the factions.canBeRaided config lifts it for
     * the colonies that simulate.
     */
    public static void raids_off(final GameTestHelper helper)
    {
        final ServerLevel level = helper.getLevel();
        final BlockPos hall = pad(helper, 2, 2, 8);
        final IColony colony = faction(helper, hall, "pirates", "Pirate Cove");
        helper.assertTrue(colony != null, "no faction colony");
        colony.setSimulationDriver(() -> true);
        helper.assertTrue(colony.getState() == ColonyState.ACTIVE, "fixture: not ACTIVE: " + colony.getState());
        try
        {
            helper.assertTrue(!MineColonies.getConfig().getServer().factionColoniesCanBeRaided.get(), "raids on by default");
            helper.assertTrue(!colony.getRaiderManager().canRaid(), "an active faction colony can be raided by default");
            helper.assertTrue(!FactionConfig.canBeRaided(), "config default");

            // A player colony with a manager online can be raided under the same world settings, a faction colony only if the config says so.
            FactionConfig.overrideCanBeRaided(true);
            final boolean expected = !WorldUtil.isPeaceful(level)
                                       && MineColonies.getConfig().getServer().enableColonyRaids.get()
                                       && colony.getRaiderManager().canHaveRaiderEvents();
            helper.assertTrue(colony.getRaiderManager().canRaid() == expected, "with canBeRaided: " + colony.getRaiderManager().canRaid() + ", expected " + expected);
            colony.setSimulationDriver(null);
            helper.assertTrue(colony.getState() != ColonyState.ACTIVE && !colony.getRaiderManager().canRaid(), "raidable while not simulating");
        }
        finally
        {
            FactionConfig.overrideCanBeRaided(null);
        }
        helper.assertTrue(!colony.getRaiderManager().canRaid(), "raids back off");
        level.setChunkForced(hall.getX() >> 4, hall.getZ() >> 4, false);
        helper.succeed();
    }
}
