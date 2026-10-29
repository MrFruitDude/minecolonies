package com.minecolonies.core.entity.ai.workers.production;
import net.minecraft.world.item.component.SwingAnimation;

import com.minecolonies.api.colony.IColonyManager;
import com.minecolonies.api.compatibility.Compatibility;
import com.minecolonies.api.entity.ai.statemachine.AITarget;
import com.minecolonies.api.entity.ai.statemachine.states.IAIState;
import com.minecolonies.api.entity.citizen.VisibleCitizenStatus;
import com.minecolonies.api.equipment.ModEquipmentTypes;
import com.minecolonies.api.items.ModTags;
import com.minecolonies.api.util.*;
import com.minecolonies.api.util.constant.ColonyConstants;
import com.minecolonies.api.util.constant.Constants;
import com.minecolonies.core.colony.buildings.AbstractBuilding;
import com.minecolonies.core.colony.buildings.modules.BuildingModules;
import com.minecolonies.core.colony.buildings.modules.ItemListModule;
import com.minecolonies.core.colony.buildings.workerbuildings.BuildingLumberjack;
import com.minecolonies.core.colony.jobs.JobLumberjack;
import com.minecolonies.core.entity.ai.workers.crafting.AbstractEntityAICrafting;
import com.minecolonies.core.entity.ai.workers.util.Tree;
import com.minecolonies.core.entity.ai.workers.util.TreeClaims;
import com.minecolonies.core.entity.ai.workers.util.TreeFootprint;
import com.minecolonies.api.compatibility.treechop.TreeChopProxy;
import com.minecolonies.api.colony.IColony;
import com.minecolonies.api.colony.buildings.IBuilding;
import com.minecolonies.api.crafting.ItemStorage;
import com.minecolonies.core.MineColonies;
import com.ldtteam.structurize.api.util.Tuple;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.phys.Vec3;
import com.minecolonies.core.entity.pathfinding.PathfindingUtils;
import com.minecolonies.core.entity.pathfinding.navigation.MinecoloniesAdvancedPathNavigate;
import com.minecolonies.core.entity.pathfinding.pathjobs.PathJobMoveToWithPassable;
import com.minecolonies.core.entity.pathfinding.pathresults.PathResult;
import com.minecolonies.core.entity.pathfinding.pathresults.TreePathResult;
import com.minecolonies.core.util.WorkerUtil;
import com.minecolonies.core.util.citizenutils.CitizenItemUtils;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.resources.Identifier;
import net.minecraft.sounds.SoundSource;
import net.minecraft.tags.BlockTags;
import net.minecraft.tags.ItemTags;
import net.minecraft.tags.TagKey;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.*;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.pathfinder.Node;
import net.minecraft.world.level.pathfinder.Path;
import net.minecraft.world.phys.AABB;
import net.neoforged.neoforge.common.Tags;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

import static com.minecolonies.api.entity.ai.statemachine.states.AIWorkerState.*;
import static com.minecolonies.api.items.ModTags.fungi;
import static com.minecolonies.api.util.constant.Constants.TICKS_SECOND;
import static com.minecolonies.api.util.constant.StatisticsConstants.ITEM_OBTAINED;
import static com.minecolonies.api.util.constant.StatisticsConstants.TREE_CUT;
import static com.minecolonies.core.colony.buildings.modules.BuildingModules.STATS_MODULE;

/**
 * The lumberjack AI class.
 */
public class EntityAIWorkLumberjack extends AbstractEntityAICrafting<JobLumberjack, BuildingLumberjack>
{
    /**
     * The render name to render logs.
     */
    public static final String RENDER_META_LOGS = "logs";

    /**
     * The range in which the lumberjack searches for trees.
     */
    public static final int SEARCH_RANGE = 50;

    /**
     * Forestry: the search radius around the hut is this plus {@link #SEARCH_PER_LEVEL} per hut level.
     */
    private static final int SEARCH_BASE      = 32;
    private static final int SEARCH_PER_LEVEL = 8;

    /**
     * Forestry: leaves broken per AI call by the leaf sweep, and how many of them show particles.
     */
    private static final int LEAF_BATCH   = 64;
    private static final int LEAF_EFFECTS = 4;

    /**
     * Forestry: drops are caught this far around the tree's logs (TreeChop breaks leaves up to 7 blocks out).
     */
    private static final int CAPTURE_MARGIN = 9;

    /**
     * Forestry: saplings planted per idle round, the grid of the planted forest, and the ring around the hut when no zone is set.
     */
    private static final int PLANT_PER_IDLE  = 4;
    private static final int PLANT_GRID      = 4;
    private static final int RING_GAP        = 3;
    private static final int RING_WIDTH      = 12;
    private static final int PLANT_MAX_SPOTS = 512;

    /**
     * List of saplings.
     */
    public static final String SAPLINGS_LIST = "saplings";

    /**
     * Vertical range in which the worker picks up items.
     */
    public static final float RANGE_VERTICAL_PICKUP = 2.0F;

    /**
     * Horizontal range in which the worker picks up items.
     */
    public static final float RANGE_HORIZONTAL_PICKUP = 5.0F;

    /**
     * The minimum range the lumberjack has to reach in order to construct or clear.
     */
    private static final int MIN_WORKING_RANGE   = 2;

    /**
     * Forestry: this long without getting nearer to his tree or breaking a block of it, and he gives the tree up (two minutes).
     */
    private static final long TREE_GIVE_UP_TICKS = 2 * 60 * 20L;

    /**
     * Forestry: he is at his work spot only within this many blocks up or down of it (on a roof above the tree he is not).
     */
    private static final int MAX_WORK_HEIGHT = 3;

    /**
     * Forestry: this close to the stump (around, and up or down) and this long without getting nearer, he works from where he stands.
     */
    private static final double CLOSE_ENOUGH        = 3.0D;
    private static final int    CLOSE_ENOUGH_HEIGHT = 2;
    private static final long   CLOSE_ENOUGH_TICKS  = 10 * 20L;

    /**
     * Forestry: the tree work runs every second; a longer gap means he was away (dumping, eating, a tool), and the give-up clock starts again.
     */
    private static final long CHOP_AWAY_TICKS = 3 * 20L;

    /**
     * Forestry: an idle planting spot he does not reach in this time is dropped (thirty seconds).
     */
    private static final long PLANT_WALK_TICKS = 30 * 20L;

    /**
     * Forestry: the sweep takes caps at most this far from the tree's box (a cap further out is no part of his tree).
     */
    private static final int SWEEP_CAP_MARGIN = 4;
    /**
     * Time in ticks to wait before placing a sapling. Is used to collect falling saplings from the ground.
     */
    private static final int WAIT_BEFORE_SAPLING = 50;
    /**
     * Time in ticks to wait before placing a sapling. Is used to collect falling saplings from the ground.
     */
    private static final int MAX_WAITING_TIME    = 50;

    /**
     * Number of ticks to wait for tree.
     */
    private static final int TIMEOUT_DELAY      = 10;
    /**
     * Time in ticks to wait before rechecking if there are trees in the range of the lumberjack.
     */
    private static final int WAIT_BEFORE_SEARCH = 400;

    /**
     * Return to chest after half a stack.
     */
    private static final int MAX_BLOCKS_MINED = 32;

    /**
     * Delay before going to gather after cutting a tree.
     */
    private static final int GATHERING_DELAY = 3;

    /**
     * Searching icon
     */
    private final static VisibleCitizenStatus SEARCH =
      new VisibleCitizenStatus(Identifier.fromNamespaceAndPath(Constants.MOD_ID, "textures/icons/work/lumberjack_search.png"), "com.minecolonies.gui.visiblestatus.lumberjack_search");

    /**
     * Xp bonus per finished tree
     */
    private static final double XP_PER_TREE = 1;

    /**
     * Position where the Builders constructs from.
     */
    private BlockPos workFrom;

    /**
     * The time in ticks the lumberjack has waited already. Directly connected with the MAX_WAITING_TIME.
     */
    private int timeWaited = 0;

    /**
     * Variable describing if the lj looked in his hut for a certain sapling.
     */
    private boolean checkedInHut = false;

    /**
     * W11: the tree the sapling check before felling was made for.
     */
    @Nullable
    private Object saplingCheckedFor = null;

    /**
     * W11: walk to the hut for a sapling before felling the current tree.
     */
    private boolean fetchSaplingFirst = false;

    /**
     * W11: blocks from the hut within which the lumberjack takes a sapling along before felling.
     */
    private static final int SAPLING_FETCH_RANGE = 24;

    /**
     * W11: saplings taken from the hut before a felling (this worker, tests).
     */
    private int saplingsFetchedAhead = 0;

    /**
     * @return saplings taken from the hut before a felling (this worker only, since his AI started).
     */
    public int getSaplingsFetchedAhead()
    {
        return saplingsFetchedAhead;
    }

    /**
     * The active pathfinding job used to walk to trees.
     */
    @Nullable
    private TreePathResult pathResult;

    /**
     * Forestry: this Forester's claim on his current tree.
     */
    @Nullable
    private TreeClaims.Claim claim;

    /**
     * Forestry: the leaf sweep of the felled tree, and the tree it is done for.
     */
    @Nullable
    private TreeFootprint.LeafSweep sweep;
    @Nullable
    private Tree sweptTree;

    /**
     * Forestry: the current tree is chopped through TreeChop, at this log, with this many chops per action.
     */
    private boolean  useTreeChop = false;
    @Nullable
    private BlockPos treeChopTarget;
    private int      treeChopChops = 1;

    /**
     * Forestry: chops and fellings through TreeChop by this Forester (tests).
     */
    private int treeChopChopCount = 0;
    private int treeChopFellCount = 0;

    /**
     * Forestry: chops TreeChop did not get because its tree reached past his (tests).
     */
    private int treeChopRefusedCount = 0;

    /**
     * Forestry: the give-up clock of the current tree: when he last got nearer to it or broke a block of it, when the tree work last ran, his best
     * distance to the work spot (and for which spot), since when he stands close to the stump without getting nearer, and whether he got there.
     */
    private long     treeProgressAt   = -1;
    private long     lastChopAt       = -1;
    private double   treeBestDistance = Double.MAX_VALUE;
    @Nullable
    private BlockPos treeProgressFrom;
    private long     closeSince       = -1;
    private boolean  reachedTree      = false;

    /**
     * Forestry: trees he gave up because he could not get to them or nothing came off, and what he said about the last one (tests, status).
     */
    private int    treesGivenUp = 0;
    @NotNull
    private String giveUpNote   = "";

    /**
     * Forestry: the idle planting spot he walks to, and since when.
     */
    @Nullable
    private BlockPos plantWalkSpot;
    private long     plantWalkSince = -1;

    /**
     * Forestry: leaves and caps the sweep left because they touch a build, a hut box or another colony's land (tests).
     */
    private int sweepKept = 0;

    /**
     * Forestry: blocks of chosen trees left alone because they changed since the choice, and trees given up for that before the first cut (tests).
     */
    private int changedBlocksLeft = 0;
    private int changedTreesLeft  = 0;

    /**
     * Forestry: items that did not fit and dropped at his feet (tests).
     */
    private int overflowDropped = 0;

    /**
     * Forestry: idle planting of this round.
     */
    @Nullable
    private List<BlockPos> plantSpots;
    private int            planted = 0;
    private boolean        plantingDone = false;
    private int            plantedTotal = 0;
    @NotNull
    private String         plantNote = "not tried";

    /**
     * @return chops through TreeChop by this Forester.
     */
    public int getTreeChopChopCount()
    {
        return treeChopChopCount;
    }

    /**
     * @return trees felled through TreeChop by this Forester.
     */
    public int getTreeChopFellCount()
    {
        return treeChopFellCount;
    }

    /**
     * @return chops TreeChop did not get because its tree reached past the Forester's tree.
     */
    public int getTreeChopRefusedCount()
    {
        return treeChopRefusedCount;
    }

    /**
     * @return blocks of chosen trees he left alone because they changed after the choice.
     */
    public int getChangedBlocksLeft()
    {
        return changedBlocksLeft;
    }

    /**
     * @return chosen trees he gave up before the first cut because they changed.
     */
    public int getChangedTreesLeft()
    {
        return changedTreesLeft;
    }

    /**
     * @return trees he gave up because he could not get to them or nothing of them came off.
     */
    public int getTreesGivenUp()
    {
        return treesGivenUp;
    }

    /**
     * @return what he said about the last tree he gave up (empty if none).
     */
    @NotNull
    public String getGiveUpNote()
    {
        return giveUpNote;
    }

    /**
     * @return leaves and caps the sweep left standing because they touch a build, a hut box or another colony's land.
     */
    public int getSweepKept()
    {
        return sweepKept;
    }

    /**
     * @return items dropped at his feet because his inventory was full.
     */
    public int getOverflowDropped()
    {
        return overflowDropped;
    }

    /**
     * @return saplings planted when idle by this Forester.
     */
    public int getPlantedTotal()
    {
        return plantedTotal;
    }

    /**
     * The current path to the tree
     */
    private PathResult<?> pathToTree;

    @Override
    protected int getActionRewardForCraftingSuccess()
    {
        return getActionsDoneUntilDumping();
    }

    /**
     * Create a new LumberjackAI.
     *
     * @param job the lumberJackJob
     */
    public EntityAIWorkLumberjack(@NotNull final JobLumberjack job)
    {

        // Override state machine, otherwise the lumberjack will never check for wood to cut.
        super(job);
        super.registerTargets(
          new AITarget(LUMBERJACK_START_WORKING, this::startWorkingAtOwnBuilding, TICKS_SECOND),
          new AITarget(PREPARING, this::prepareForWoodcutting, TICKS_SECOND),
          new AITarget(LUMBERJACK_SEARCHING_TREE, this::findTrees, TICKS_SECOND),
          new AITarget(LUMBERJACK_CHOP_TREE, this::chopWood, TICKS_SECOND),
          new AITarget(LUMBERJACK_GATHERING, this::gathering, TICKS_SECOND),
          new AITarget(LUMBERJACK_NO_TREES_FOUND, this::waitBeforeCheckingAgain, TICKS_SECOND),
          new AITarget(LUMBERJACK_GATHERING_2, this::gathering2, TICKS_SECOND)
        );
        worker.setCanPickUpLoot(true);
    }

    @Override
    public Class<BuildingLumberjack> getExpectedBuildingClass()
    {
        return BuildingLumberjack.class;
    }

    @Override
    public boolean hasWorkToDo()
    {
        return true;
    }

    /**
     * {@inheritDoc}
     * <p>
     * The lumberjack is a special worker. In their decision state, they will try to add lumberjack cycles If there's nothing left to craft, they will proceed with woodworking
     * </p>
     */
    @Override
    protected IAIState decide()
    {
        if (checkIfStuck())
        {
            tryUnstuck();
            return getState();
        }

        if (!walkToBuilding())
        {
            return START_WORKING;
        }

        if (job.getActionsDone() >= getActionsDoneUntilDumping())
        {
            // Wait to dump before continuing.
            return getState();
        }

        if (building.getModule(BuildingModules.ITEMLIST_SAPLING).getList().size()
            < IColonyManager.getInstance().getCompatibilityManager().getNumberOfSaplings())
        {
            // This got moved downwards compared to the AICrafting-implementation,
            // because in this case waiting for dumping is more important
            // than restarting chopping
            if (job.getTaskQueue().isEmpty())
            {
                return LUMBERJACK_START_WORKING;
            }

            if (job.getCurrentTask() == null)
            {
                return LUMBERJACK_START_WORKING;
            }
        }

        return getNextCraftingState();
    }

    /**
     * Checks if a stack is a type of log.
     *
     * @param stack the stack to check.
     * @return true if it is a log type.
     */
    private boolean isStackLog(@Nullable final ItemStack stack)
    {
        return !ItemStackUtils.isEmpty(stack) && stack.getItem() instanceof BlockItem && ((BlockItem) stack.getItem()).getBlock().defaultBlockState().is(BlockTags.LOGS);
    }

    /**
     * Walk to own building to check for tools.
     *
     * @return PREPARING once at the building.
     */
    private IAIState startWorkingAtOwnBuilding()
    {
        if (!walkToBuilding())
        {
            return getState();
        }
        return PREPARING;
    }

    /**
     * Checks if lumberjack has all necessary tools.
     *
     * @return next IAIState.
     */
    private IAIState prepareForWoodcutting()
    {
        if (checkForToolOrWeapon(ModEquipmentTypes.axe.get()) || checkForToolOrWeapon(building.getSetting(AbstractBuilding.USE_SHEARS).getValue() ? ModEquipmentTypes.shears.get() : ModEquipmentTypes.hoe.get()))
        {
            // Reset everything, maybe there are new crafting requests
            return START_WORKING;
        }
        return LUMBERJACK_SEARCHING_TREE;
    }

    /**
     * If the search radius was exceeded, we have to wait dome time before searching again.
     *
     * @return LUMBERJACK_SEARCHING_TREE once waited enough.
     */
    private IAIState waitBeforeCheckingAgain()
    {
        pathResult = null;

        // Forestry: nothing to cut, so plant a few saplings from the hut first
        if (building.shouldPlantForest() && plantForest())
        {
            return getState();
        }

        if (hasNotDelayed(WAIT_BEFORE_SEARCH))
        {
            return getState();
        }
        plantSpots = null;
        plantingDone = false;

        // Check again for saplings
        resetGatheringItems();
        return LUMBERJACK_GATHERING_2;
    }

    /**
     * After we ran out of trees, and waited a bit, double-check if there are any saplings to gather
     * anywhere within our restriction zone.
     */
    private IAIState gathering2()
    {
        if (building.shouldRestrict())
        {
            if (getItemsForPickUp() == null)
            {
                // search for interesting items in our restriction zone, if we ran out of trees
                searchForItems(AABB.encapsulatingFullBlocks(building.getStartRestriction(), building.getEndRestriction())
                                 .inflate(RANGE_HORIZONTAL_PICKUP, RANGE_VERTICAL_PICKUP, RANGE_HORIZONTAL_PICKUP));
            }

            if (getItemsForPickUp() != null && !getItemsForPickUp().isEmpty())
            {
                gatherItems();
                return getState();
            }
        }

        // Reset everything, maybe there are new crafting requests
        resetGatheringItems();
        if (ColonyConstants.rand.nextInt(100) <= 10)
        {
            return START_WORKING;
        }
        else
        {
            return LUMBERJACK_SEARCHING_TREE;
        }
    }

    @Override
    protected boolean isItemWorthPickingUp(final ItemStack stack)
    {
        if (getState() == LUMBERJACK_GATHERING_2)
        {
            // we're only interested in saplings at this point
            return stack.is(ItemTags.SAPLINGS) || stack.is(Tags.Items.MUSHROOMS) || stack.is(fungi);
        }

        return super.isItemWorthPickingUp(stack);
    }

    /**
     * Checks if lumberjack has already found some trees. If not, check task queue for crafting
     * requests - if none, search trees.
     * @return next IAIState
     */
    private IAIState findTrees()
    {
        if (job.getTree() == null)
        {
            if (!job.getTaskQueue().isEmpty())
            {
                return START_WORKING;
            }

            return findTree();
        }

        worker.getCitizenData().setVisibleStatus(VisibleCitizenStatus.WORKING);
        return LUMBERJACK_CHOP_TREE;
    }

    /**
     * Search for a tree.
     *
     * @return LUMBERJACK_GATHERING if job was canceled.
     */
    private IAIState findTree()
    {
        worker.getCitizenData().setVisibleStatus(SEARCH);

        if (pathResult != null && pathResult.isComputing())
        {
            return getState();
        }
        if (pathResult == null)
        {
            if (building.shouldRestrict())
            {
                final BlockPos startPos = building.getStartRestriction();
                final BlockPos endPos = building.getEndRestriction();

                pathResult = worker.getNavigation()
                    .walkToTree(startPos,
                                 endPos,
                                 1.0D,
                                 building.getModuleMatching(ItemListModule.class, m -> m.getId().equals(SAPLINGS_LIST)).getList(),
                                 building.getSetting(BuildingLumberjack.DYNAMIC_TREES_SIZE).getValue(),
                                 worker.getCitizenColonyHandler().getColonyOrRegister());
            }
            else
            {
                pathResult = worker.getNavigation()
                    .walkToTree(getSearchRadius(),
                                 1.0D,
                                 building.getModuleMatching(ItemListModule.class, m -> m.getId().equals(SAPLINGS_LIST)).getList(),
                                 building.getSetting(BuildingLumberjack.DYNAMIC_TREES_SIZE).getValue(),
                                 worker.getCitizenColonyHandler().getColonyOrRegister());
            }
            return getState();
        }
        if (pathResult.isDone())
        {
            return setNewTree(building);
        }

        // None of the above yielded a result, report no trees found.
        return LUMBERJACK_NO_TREES_FOUND;
    }

    /**
     * @return the tree search radius around the hut, growing with the hut level.
     */
    public int getSearchRadius()
    {
        return SEARCH_BASE + SEARCH_PER_LEVEL * Math.max(1, building.getBuildingLevel());
    }

    private IAIState setNewTree(final BuildingLumberjack building)
    {
        if (pathResult.treeLocation == null)
        {
            pathResult = null;
            return LUMBERJACK_NO_TREES_FOUND;
        }
        else
        {
            // The colony's hut boxes are kept out in both modes (forestry: the restricted zone used to skip this check).
            final Tree tree = new Tree(world, pathResult.treeLocation, building.getColony());
            final int colonyId = building.getColony().getID();
            final int citizenId = worker.getCitizenData().getId();

            if (tree.isTree() && tree.getSkip() != TreeFootprint.Skip.NONE)
            {
                // Forestry: never cut part of a tree; a tree next to a build stays whole and no Forester tries it again for a while
                TreeClaims.block(world, tree.getAllLogs().isEmpty() ? List.of(tree.getLocation()) : tree.getAllLogs(), colonyId);
                if (tree.getSkip() == TreeFootprint.Skip.BUILD || tree.getSkip() == TreeFootprint.Skip.HUT)
                {
                    building.addSkippedTree(tree.getLocation());
                }
                else if (tree.getSkip() == TreeFootprint.Skip.TOO_BIG)
                {
                    building.addSkippedTree(tree.getLocation(), BuildingLumberjack.SkipReason.TOO_BIG, "");
                }
                Log.getLogger().debug("Forester {} leaves the tree at {} standing: {} ({})", citizenId, tree.getLocation(), tree.getSkip(), tree.getSkipDetail());
            }
            else if (tree.isTree() && tree.getAllLogs().stream().noneMatch(pos -> TreeClaims.isTakenByOther(world, pos, colonyId, citizenId)))
            {
                job.setTree(tree);
                tree.findLogs(world, building.getColony());
                claim = TreeClaims.claim(world, tree.getAllLogs().isEmpty() ? List.of(tree.getLocation()) : tree.getAllLogs(), colonyId, citizenId);
                building.removeSkippedTree(tree.getLocation());
                chooseChopPath(tree);
                startTreeClock();
                pathResult = null;
                return LUMBERJACK_CHOP_TREE;
            }
            else if (!tree.isTree() && !tree.getAllLogs().isEmpty())
            {
                // The search took it for a tree, the full scan does not (too few leaves): leave it alone for a while instead of picking it again
                TreeClaims.block(world, tree.getAllLogs(), colonyId);
            }
        }
        pathResult = null;

        return getState();
    }

    /**
     * Forestry: chop through TreeChop when it is loaded and on, the server setting allows it, and TreeChop can chop the lowest log of the tree.
     */
    private void chooseChopPath(@NotNull final Tree tree)
    {
        useTreeChop = false;
        treeChopTarget = null;
        if (!MineColonies.getConfig().getServer().lumberjacksUseTreeChop.get() || !Compatibility.treeChop.isEnabled() || tree.isDynamicTree() || !tree.isFootprintTree())
        {
            return;
        }
        BlockPos target = null;
        for (final BlockPos pos : tree.getAllLogs())
        {
            if ((target == null || pos.getY() < target.getY() || (pos.getY() == target.getY() && pos.distSqr(tree.getLocation()) < target.distSqr(tree.getLocation())))
                  && Compatibility.treeChop.isChoppable(world, pos))
            {
                target = pos;
            }
        }
        if (target != null && treeChopFits(tree, target))
        {
            useTreeChop = true;
            treeChopTarget = target;
            treeChopChops = Compatibility.treeChop.chopsPerAction((ServerLevel) world, target, tree.getAllLogs().size());
        }
    }

    /**
     * Forestry: TreeChop fells every log it takes for one tree, of any wood and with no colony or build check. It may chop only when its tree is part of
     * his: no log in another colony's claim, of another Forester's tree or of a player's log build beyond his footprint.
     *
     * @param tree   his tree.
     * @param target the log TreeChop would chop.
     * @return true if every log of TreeChop's tree is a log of his tree.
     */
    private boolean treeChopFits(@NotNull final Tree tree, @NotNull final BlockPos target)
    {
        final Set<BlockPos> logs = Compatibility.treeChop.getTreeLogs((ServerLevel) world, target);
        if (logs != null && !tree.getAllLogs().isEmpty() && new HashSet<>(tree.getAllLogs()).containsAll(logs))
        {
            return true;
        }
        treeChopRefusedCount++;
        Log.getLogger().debug("Forester {} cuts the tree at {} the normal way: TreeChop's tree reaches past it ({} logs)", worker.getCitizenData().getId(),
          tree.getLocation(), logs == null ? "unknown" : logs.size());
        return false;
    }

    /**
     * Forestry: every log TreeChop would fell with this chop, checked now as a direct cut checks its block (same wood and bark, no build next to it, no
     * hut box, no other colony's land) and held by no other Forester. One that fails and the chop is not made: the normal path cuts the tree block by
     * block and leaves that log.
     *
     * @param tree   his tree.
     * @param target the log TreeChop would chop.
     * @return true if TreeChop may fell them all.
     */
    private boolean treeChopLogsFree(@NotNull final Tree tree, @NotNull final BlockPos target)
    {
        final Set<BlockPos> logs = Compatibility.treeChop.getTreeLogs((ServerLevel) world, target);
        if (logs == null)
        {
            return false;
        }
        final int colonyId = building.getColony().getID();
        final int citizenId = worker.getCitizenData().getId();
        for (final BlockPos pos : logs)
        {
            final TreeFootprint.Recheck recheck = TreeFootprint.recheck(world, pos, TreeFootprint.Part.LOG, tree.getFamily(), tree.isStrippedBase(), building.getColony());
            if ((recheck != TreeFootprint.Recheck.OK && recheck != TreeFootprint.Recheck.GONE) || TreeClaims.isTakenByOther(world, pos, colonyId, citizenId))
            {
                treeChopRefusedCount++;
                Log.getLogger().debug("Forester {} cuts the tree at {} the normal way: TreeChop would fell {} ({})", citizenId, tree.getLocation(), pos.toShortString(),
                  recheck);
                return false;
            }
        }
        return true;
    }

    /**
     * Forestry: the next block of the tree, checked again right before he breaks it (it may have changed since he chose the tree: planks in place of a
     * log, a build next to it, a new claim). A changed block is left alone; a change before his first cut makes him leave the whole tree standing.
     *
     * @param tree the tree.
     * @param pos  the block.
     * @param part which part of the tree it is.
     * @return true if he may break it; false if he must leave it (the caller drops it, unless the tree was given up).
     */
    private boolean mayBreak(@NotNull final Tree tree, @NotNull final BlockPos pos, @NotNull final TreeFootprint.Part part)
    {
        if (!tree.isFootprintTree() || tree.isDynamicTree())
        {
            return true;
        }
        final TreeFootprint.Recheck recheck = TreeFootprint.recheck(world, pos, part, tree.getFamily(), tree.isStrippedBase(), building.getColony());
        if (recheck == TreeFootprint.Recheck.OK)
        {
            return true;
        }
        if (recheck == TreeFootprint.Recheck.GONE)
        {
            return false;
        }
        if (recheck == TreeFootprint.Recheck.BUILD && part != TreeFootprint.Part.LOG)
        {
            // A leaf or cap touching a player's block stays; the tree goes on
            Log.getLogger().debug("Forester {} leaves {} of the tree at {}: it touches a build", worker.getCitizenData().getId(), pos.toShortString(), tree.getLocation());
            return false;
        }
        changedBlocksLeft++;
        if (!tree.isStarted() && !tree.getAllLogs().isEmpty())
        {
            // Nothing cut yet: the whole tree stays, and no Forester picks it again for a while
            changedTreesLeft++;
            Log.getLogger().debug("Forester {} leaves the tree at {} standing: {} at {}", worker.getCitizenData().getId(), tree.getLocation(), recheck, pos.toShortString());
            final List<BlockPos> logs = new ArrayList<>(tree.getAllLogs());
            final BlockPos location = tree.getLocation();
            finishTree();
            TreeClaims.block(world, logs, building.getColony().getID());
            if (recheck != TreeFootprint.Recheck.CLAIM)
            {
                building.addSkippedTree(location);
            }
            return false;
        }
        Log.getLogger().debug("Forester {} leaves {} of the tree at {}: {}", worker.getCitizenData().getId(), pos.toShortString(), tree.getLocation(), recheck);
        return false;
    }

    /**
     * Forestry: let go of the current tree (done or given up).
     */
    private void finishTree()
    {
        job.setTree(null);
        pathResult = null;
        checkedInHut = false;
        TreeClaims.release(claim);
        claim = null;
        sweep = null;
        sweptTree = null;
        useTreeChop = false;
        treeChopTarget = null;
        workFrom = null;
        startTreeClock();
    }

    /**
     * Forestry: start the give-up clock of the tree again (new tree, or back at it after being away).
     */
    private void startTreeClock()
    {
        treeProgressAt = world.getGameTime();
        treeBestDistance = Double.MAX_VALUE;
        treeProgressFrom = null;
        closeSince = -1;
        reachedTree = false;
    }

    /**
     * Forestry: he got on with the tree (a block came off or was dropped from the list).
     */
    private void noteTreeProgress()
    {
        treeProgressAt = world.getGameTime();
    }

    /**
     * Forestry: give the current tree up, because he could not get to it or nothing of it came off for too long. No Forester picks it again for a while;
     * the hut counts it with the reason.
     *
     * @param tree the tree.
     * @return the next state.
     */
    private IAIState giveUpTree(@NotNull final Tree tree)
    {
        final BlockPos location = tree.getLocation();
        final String note = (reachedTree ? "Left the tree at " + location.toShortString() + ": nothing of it came off for two minutes"
                                         : "Left the tree at " + location.toShortString() + ": could not get to it for two minutes");
        final List<BlockPos> logs = new ArrayList<>(tree.getAllLogs());
        logs.add(location);
        Log.getLogger().debug("Forester {} gives up: {}", worker.getCitizenData().getId(), note);
        finishTree();
        TreeClaims.block(world, logs, building.getColony().getID());
        building.addSkippedTree(location, BuildingLumberjack.SkipReason.UNREACHABLE, note);
        treesGivenUp++;
        giveUpNote = note;
        worker.getNavigation().stop();
        worker.getCitizenData().setVisibleStatus(SEARCH);
        return LUMBERJACK_SEARCHING_TREE;
    }

    /**
     * Again checks if all preconditions are given to execute chopping. If yes go chopping, else return to previous AIStates.
     *
     * @return next IAIState
     */
    private IAIState chopWood()
    {
        if (checkForToolOrWeapon(ModEquipmentTypes.axe.get()))
        {
            return IDLE;
        }

        if (job.getTree() == null)
        {
            return LUMBERJACK_SEARCHING_TREE;
        }
        return chopTree();
    }

    //TODO: On walking to the zone(no tree found) leaves are not getting broken
    /**
     * Work on the tree. First find your way to the tree trunk. Then chop away and wait for saplings to drop then place a sapling if shouldReplant is true
     *
     * @return LUMBERJACK_GATHERING if tree is done
     */
    private IAIState chopTree()
    {
        final boolean shouldBreakLeaves = building.shouldDefoliate() || job.getTree().isNetherTree();

        if (building.shouldRestrict() && !BlockPosUtil.isInArea(building.getStartRestriction(), building.getEndRestriction(), job.getTree().getLocation()))
        {
            finishTree();
            return START_WORKING;
        }

        // Forestry: hold the claim on this tree while working on it (after a reload the claim is made again)
        if (claim == null)
        {
            final List<BlockPos> logs = new ArrayList<>(job.getTree().getAllLogs());
            logs.add(job.getTree().getLocation());
            claim = TreeClaims.claim(world, logs, building.getColony().getID(), worker.getCitizenData().getId());
        }
        claim.refresh();

        // Forestry: the give-up clock runs only while he is at the tree work
        final long now = world.getGameTime();
        if (treeProgressAt < 0 || lastChopAt < 0 || now - lastChopAt > CHOP_AWAY_TICKS)
        {
            startTreeClock();
        }
        lastChopAt = now;

        // W11: no sapling for this tree with him, but his hut has one and he is still near it: take it now, before the felling, instead of
        // waiting at the stump for one to drop and walking back for it
        if (building.shouldReplant() && saplingCheckedFor != job.getTree())
        {
            saplingCheckedFor = job.getTree();
            fetchSaplingFirst = countCorrectSaplings() < Math.max(1, job.getTree().getStumpLocations().size()) && !ItemStackUtils.isEmpty(job.getTree().getSapling())
                                  && worker.blockPosition().distSqr(building.getPosition()) <= SAPLING_FETCH_RANGE * SAPLING_FETCH_RANGE
                                  && InventoryUtils.hasItemInProvider(building, this::isCorrectSapling);
        }
        if (fetchSaplingFirst)
        {
            if (!walkToBuilding())
            {
                noteTreeProgress();
                return getState();
            }
            fetchSaplingFirst = false;
            startTreeClock();
            if (checkAndTransferFromHut(job.getTree().getSapling()))
            {
                saplingsFetchedAhead++;
            }
        }

        final boolean treeWork = job.getTree().hasLogs() || job.getTree().hasDecorations() || (shouldBreakLeaves && job.getTree().hasLeaves());
        if (treeWork && now - treeProgressAt > TREE_GIVE_UP_TICKS)
        {
            // Forestry: never stand at a tree for ever
            return giveUpTree(job.getTree());
        }
        if (treeWork || checkedInHut)
        {
            if (!walkToTree(job.getTree().getStumpLocations().isEmpty() ? job.getTree().getLocation() : job.getTree().getStumpLocations().get(0)))
            {
                if (checkIfStuck())
                {
                    tryUnstuck();
                }
                return getState();
            }
            reachedTree = true;
        }

        if (!job.getTree().hasLogs() && !job.getTree().hasDecorations() && (!shouldBreakLeaves || !job.getTree().hasLeaves()))
        {
            // Forestry: the leaves the tree held go now, with their drops into his inventory, before he replants
            if (sweepLeaves(job.getTree()))
            {
                return getState();
            }

            // Drops of a forestry tree are caught, nothing falls to wait for
            if (!job.getTree().isFootprintTree() && hasNotDelayed(WAIT_BEFORE_SAPLING))
            {
                return getState();
            }

            if (building.shouldReplant())
            {
                plantSapling();
            }
            else
            {
                finishTree();
            }

            building.getColony().getStatisticsManager().increment(TREE_CUT, building.getColony().getDay());
            worker.getCitizenExperienceHandler().addExperience(XP_PER_TREE);
            incrementActionsDoneAndDecSaturation();
            workFrom = null;
            setDelay(TICKS_SECOND * GATHERING_DELAY);
            return LUMBERJACK_GATHERING;
        }

        if (isOnSapling())
        {
            @Nullable final BlockPos spawnPoint = EntityUtils.getSpawnPoint(world, workFrom);
            if (spawnPoint != null)
            {
                WorkerUtil.setSpawnPoint(spawnPoint, worker);
            }
        }

        if (job.getTree().hasDecorations())
        {
            // Forestry: a bee nest (taken whole, bees inside) or cocoa goes before the log it hangs on
            final Tree tree = job.getTree();
            final BlockPos decoration = tree.peekNextDecoration();
            if ((!world.getBlockState(decoration).is(Blocks.BEE_NEST) && !world.getBlockState(decoration).is(Blocks.COCOA))
                  || !mayBreak(tree, decoration, TreeFootprint.Part.DECORATION))
            {
                if (job.getTree() == tree)
                {
                    tree.pollNextDecoration();
                    noteTreeProgress();
                }
                return getState();
            }
            if (!mineBlock(decoration, workFrom))
            {
                waitingForTool();
                return getState();
            }
            tree.markStarted();
            noteTreeProgress();
            tree.pollNextDecoration();
        }
        else if (job.getTree().hasLeaves() && shouldBreakLeaves)
        {
            final Tree tree = job.getTree();
            final BlockPos leaf = tree.peekNextLeaf();
            if (!mayBreak(tree, leaf, TreeFootprint.Part.LEAF))
            {
                if (job.getTree() == tree)
                {
                    tree.pollNextLeaf();
                    noteTreeProgress();
                }
                return getState();
            }
            if (!mineBlock(leaf, workFrom))
            {
                waitingForTool();
                return getState();
            }
            tree.markStarted();
            noteTreeProgress();
            tree.pollNextLeaf();
        }
        else if (job.getTree().hasLogs())
        {
            //take first log from queue
            final Tree tree = job.getTree();
            final BlockPos log = tree.peekNextLog();
            if (!tree.isDynamicTree() && !(useTreeChop && tree.isFootprintTree()) && !mayBreak(tree, log, TreeFootprint.Part.LOG))
            {
                if (job.getTree() == tree)
                {
                    tree.pollNextLog();
                    noteTreeProgress();
                }
                return getState();
            }

            if (job.getTree().isDynamicTree())
            {
                // Dynamic Trees handles drops/tool dmg upon tree break, so those are set to false here
                if (!mineBlock(log, workFrom, false, false, Compatibility.getDynamicTreeBreakAction(
                  world,
                  log,
                  worker.getItemInHand(InteractionHand.MAIN_HAND),
                  worker.blockPosition())))
                {
                    waitingForTool();
                    return getState();
                }
                // Successfully mined Dynamic tree, count as 6 actions done(1+5)
                for (int i = 0; i < 6; i++)
                {
                    this.incrementActionsDone();
                }
                // Wait 5 sec for falling trees(dyn tree feature)/drops
                setDelay(100);
            }
            else if (useTreeChop && job.getTree().isFootprintTree())
            {
                // Forestry: chop through TreeChop, chop by chop at the base, the whole tree falls at once
                return chopWithTreeChop(job.getTree());
            }
            else
            {
                // Forestry: what pops off with the log (cocoa, propagules) goes to him as well
                final AABB box = captureBox(job.getTree());
                final List<ItemStack> caught = new ArrayList<>();
                if (!mineBlock(log, workFrom, true, true, () -> caught.addAll(DropCapture.capture(world, box, () -> CitizenItemUtils.breakBlockWithToolInHand(worker, log)))))
                {
                    waitingForTool();
                    return getState();
                }
                deliverCaught(caught);
            }
            job.getTree().markStarted();
            noteTreeProgress();
            job.getTree().pollNextLog();
            worker.decreaseSaturationForContinuousAction();
        }
        return getState();
    }

    /**
     * Forestry: the leaf sweep after the last log, in batches. Breaks only leaves vanilla would decay anyway; their drops go to him.
     *
     * @param tree the felled tree.
     * @return true while sweeping.
     */
    private boolean sweepLeaves(@NotNull final Tree tree)
    {
        if (!tree.isFootprintTree() || sweptTree == tree)
        {
            return false;
        }
        if (sweep == null)
        {
            // Only the logs that are gone hold no leaf: a log he had to leave standing keeps its leaves and caps
            final List<BlockPos> removed = new ArrayList<>();
            for (final BlockPos log : tree.getAllLogs())
            {
                if (!TreeFootprint.isTreeBlock(world.getBlockState(log)))
                {
                    removed.add(log);
                }
            }
            sweep = new TreeFootprint.LeafSweep(world, removed, tree.getBox(), tree.isNetherTree());
        }
        final List<BlockPos> batch = sweep.next(LEAF_BATCH);
        if (!batch.isEmpty())
        {
            final AABB box = captureBox(tree);
            final ItemStack tool = worker.getMainHandItem();
            final List<ItemStack> drops = new ArrayList<>();
            int effects = 0;
            for (final BlockPos pos : batch)
            {
                if (!maySweep(tree, pos))
                {
                    sweepKept++;
                    continue;
                }
                final BlockState state = world.getBlockState(pos);
                drops.addAll(Block.getDrops(state, (ServerLevel) world, pos, world.getBlockEntity(pos), worker, tool));
                if (effects++ < LEAF_EFFECTS)
                {
                    world.levelEvent(2001, pos, Block.getId(state));
                }
                drops.addAll(DropCapture.capture(world, box, () -> WorldUtil.removeBlock(world, pos, false)));
                sweep.markBroken();
            }
            deliverCaught(drops);
            noteTreeProgress();
        }
        if (sweep.isDone())
        {
            sweptTree = tree;
            sweep = null;
            return false;
        }
        return true;
    }

    /**
     * Forestry: the sweep's block, checked as a direct cut is: still a natural leaf or a cap, outside hut boxes and other colonies' land, touching no
     * player's block (a torch, a fence; a placed leaf is kept by itself, never swept), and a cap only near the tree (a cap far out is somebody's, even
     * when it touches the canopy).
     *
     * @param tree the felled tree.
     * @param pos  the leaf or cap.
     * @return true if he may break it.
     */
    private boolean maySweep(@NotNull final Tree tree, @NotNull final BlockPos pos)
    {
        if (TreeFootprint.recheck(world, pos, TreeFootprint.Part.LEAF, tree.getFamily(), tree.isStrippedBase(), building.getColony()) != TreeFootprint.Recheck.OK)
        {
            return false;
        }
        if (TreeFootprint.isCap(world.getBlockState(pos)))
        {
            final AABB box = tree.getBox();
            return box != null && box.inflate(SWEEP_CAP_MARGIN).contains(Vec3.atCenterOf(pos));
        }
        return true;
    }

    /**
     * Forestry: a block of the tree did not come off because he waits for a tool: that is no stall at the tree.
     */
    private void waitingForTool()
    {
        if (miningWait == MiningWait.TOOL)
        {
            noteTreeProgress();
        }
    }

    /**
     * @return the box in which the drops of this tree are caught.
     */
    private AABB captureBox(@NotNull final Tree tree)
    {
        final AABB box = tree.getBox();
        return (box == null ? new AABB(tree.getLocation()) : box).inflate(CAPTURE_MARGIN);
    }

    /**
     * Forestry: caught drops go into his inventory; what does not fit drops at his feet (never deleted).
     */
    private void deliverCaught(@NotNull final List<ItemStack> stacks)
    {
        if (stacks.isEmpty())
        {
            return;
        }
        onBlockDropReception(stacks);
        overflowDropped += DropCapture.deliver(world, worker.getInventoryCitizen(), stacks, worker.position());
    }

    /**
     * Forestry: one chop through TreeChop at the tree's base, with the mining delay of the log. TreeChop turns the log into a chopped log, or fells the
     * whole tree when it has enough chops; every drop goes to him. When TreeChop does not take the tree, the normal path cuts it.
     *
     * @param tree the tree.
     * @return the next state.
     */
    private IAIState chopWithTreeChop(@NotNull final Tree tree)
    {
        final BlockPos target = treeChopTarget;
        if (target == null || !Compatibility.treeChop.isChoppable(world, target))
        {
            useTreeChop = false;
            return getState();
        }
        // The tree may have changed since he chose it: the chop must still take nothing but his tree, and every log it fells must pass the check a
        // direct cut makes
        if (!mayBreak(tree, target, TreeFootprint.Part.LOG) || !treeChopFits(tree, target) || !treeChopLogsFree(tree, target))
        {
            useTreeChop = false;
            return getState();
        }
        final AABB box = captureBox(tree);
        final TreeChopProxy.ChopOutcome[] outcome = {TreeChopProxy.ChopOutcome.IGNORED};
        final List<ItemStack> caught = new ArrayList<>();
        if (!mineBlock(target, workFrom, false, false, () -> {
            final BlockState state = world.getBlockState(target);
            final ItemStack copy = worker.getMainHandItem().copy();
            caught.addAll(DropCapture.capture(world, box, () -> outcome[0] = Compatibility.treeChop.chop((ServerLevel) world, target, treeChopAgentId(), worker.position(), copy, treeChopChops)));
            if (outcome[0] == TreeChopProxy.ChopOutcome.CHOPPED || outcome[0] == TreeChopProxy.ChopOutcome.FELLED)
            {
                // TreeChop wore the copy; the real axe wears by MineColonies' rule, once per chop
                CitizenItemUtils.damageItemInHandForMinedBlock(worker, InteractionHand.MAIN_HAND, state, target);
            }
        }))
        {
            return getState();
        }
        deliverCaught(caught);
        if (outcome[0] == TreeChopProxy.ChopOutcome.CHOPPED || outcome[0] == TreeChopProxy.ChopOutcome.FELLED)
        {
            tree.markStarted();
            noteTreeProgress();
        }
        switch (outcome[0])
        {
            case CHOPPED -> treeChopChopCount++;
            case FELLED ->
            {
                treeChopChopCount++;
                treeChopFellCount++;
                // What TreeChop does not know (roots, a creaking heart) is cut the normal way
                tree.keepOnlyStandingLogs(world);
                useTreeChop = false;
            }
            default -> useTreeChop = false;
        }
        worker.decreaseSaturationForContinuousAction();
        return getState();
    }

    /**
     * Forestry: the id of this Forester's TreeChop agent, stable over restarts and respawns. TreeChop takes one chop per player and game tick, so each
     * Forester chops as his own player.
     */
    private UUID treeChopAgentId()
    {
        return UUID.nameUUIDFromBytes(("minecolonies_lumberjack:" + building.getColony().getID() + ":" + worker.getCitizenData().getId()).getBytes(StandardCharsets.UTF_8));
    }

    @Override
    public boolean shouldSilkTouchBlock(final BlockState curBlockState)
    {
        // Forestry: a natural bee nest comes to the hut whole, with its bees inside
        return curBlockState.is(Blocks.BEE_NEST) || super.shouldSilkTouchBlock(curBlockState);
    }

    @Override
    protected int getMostEfficientTool(@NotNull final BlockState target, final BlockPos pos)
    {
        final int slot = super.getMostEfficientTool(target, pos);
        if (slot < 0 && slot != NO_TOOL && !target.requiresCorrectToolForDrops() && (TreeFootprint.isTreeBlock(target) || TreeFootprint.isDecoration(target)))
        {
            // Forestry: a part of the tree that drops without the right tool (muddy mangrove roots want a shovel) is taken with the axe or by hand,
            // he does not stop the felling to ask for a shovel
            final int axe = InventoryUtils.findFirstSlotInItemHandlerWith(getInventory(), stack -> ModEquipmentTypes.axe.get().getMiningLevel(stack) > -1);
            return axe >= 0 ? axe : NO_TOOL;
        }
        return slot;
    }

    /**
     * @return how many saplings for the current tree he carries.
     */
    private int countCorrectSaplings()
    {
        return job.getTree() == null ? 0 : InventoryUtils.getItemCountInItemHandler(getInventory(), this::isCorrectSapling);
    }

    /**
     * Forestry 4.5: with no tree to cut, plant up to {@link #PLANT_PER_IDLE} saplings from the hut on a grid, inside the zone or, without a zone, in a
     * ring around the hut. Never in hut boxes, on paths or farmland, next to builds or other trees, or in another colony's claim.
     *
     * @return true while planting.
     */
    private boolean plantForest()
    {
        if (plantingDone)
        {
            return false;
        }
        if (plantSpots == null)
        {
            plantSpots = findPlantSpots();
            planted = 0;
            plantWalkSpot = null;
        }
        if (plantSpots.isEmpty() || planted >= PLANT_PER_IDLE)
        {
            return stopPlanting(planted > 0 ? "planted " + planted : plantNote);
        }
        int slot = findPlantableSaplingSlot();
        if (slot == -1)
        {
            final ItemStack wanted = plantableSaplingInHut();
            if (wanted.isEmpty())
            {
                return stopPlanting("no sapling to plant in the hut, " + plantSpots.size() + " spots");
            }
            if (!walkToBuilding())
            {
                plantNote = "walking to the hut for " + wanted.getItem() + ", " + plantSpots.size() + " spots";
                return true;
            }
            if (!checkAndTransferFromHut(wanted) || findPlantableSaplingSlot() == -1)
            {
                // The rack's own count can lag behind its inventory: take them straight from the hut's item handlers
                InventoryUtils.transferXOfFirstSlotInProviderWithIntoNextFreeSlotInItemHandler(building, stack -> ItemStack.isSameItem(stack, wanted), PLANT_PER_IDLE,
                  getInventory());
            }
            slot = findPlantableSaplingSlot();
            if (slot == -1)
            {
                return stopPlanting("could not take " + wanted.getItem() + " from the hut");
            }
        }
        final ItemStack stack = getInventory().getStackInSlot(slot);
        final Block saplingBlock = ((BlockItem) stack.getItem()).getBlock();
        final BlockPos spot = plantSpots.get(0);
        if (!isPlantSpot(spot, saplingBlock.defaultBlockState()))
        {
            plantSpots.remove(0);
            return true;
        }
        if (!spot.equals(plantWalkSpot))
        {
            plantWalkSpot = spot;
            plantWalkSince = world.getGameTime();
        }
        if (walkWithProxy(spot, MIN_WORKING_RANGE))
        {
            if (world.getGameTime() - plantWalkSince > PLANT_WALK_TICKS)
            {
                // A spot he cannot get to is dropped, he never walks at it for ever
                plantNote = "could not get to " + spot.toShortString() + ", " + (plantSpots.size() - 1) + " spots left";
                plantSpots.remove(0);
            }
            return true;
        }
        CitizenItemUtils.setHeldItem(worker, InteractionHand.MAIN_HAND, slot);
        if (world.setBlockAndUpdate(spot, saplingBlock.defaultBlockState()))
        {
            plantNote = "planted at " + spot.toShortString();
            getInventory().extractItem(slot, 1, false);
            worker.swing(InteractionHand.MAIN_HAND, SwingAnimation.DEFAULT, false);
            final SoundType soundType = saplingBlock.getSoundType(world.getBlockState(spot), world, spot, worker);
            world.playSound(null, spot, soundType.getPlaceSound(), SoundSource.BLOCKS, soundType.getVolume(), soundType.getPitch());
            planted++;
            plantedTotal++;
        }
        plantSpots.remove(0);
        setDelay(TIMEOUT_DELAY);
        return true;
    }

    /**
     * Forestry: planting is over for this idle round.
     *
     * @param note why, for the status and tests.
     * @return false.
     */
    private boolean stopPlanting(@NotNull final String note)
    {
        plantingDone = true;
        plantNote = note;
        return false;
    }

    /**
     * @return what the last idle planting round did or why it stopped (tests).
     */
    @NotNull
    public String getPlantNote()
    {
        return plantNote;
    }

    /**
     * @param stack a stack.
     * @return true for a sapling he plants a forest with (one sapling grows a tree on dirt, not on the do-not-cut list).
     */
    private boolean isPlantableSapling(final ItemStack stack)
    {
        return ItemStackUtils.isStackSapling(stack) && stack.is(ItemTags.SAPLINGS) && stack.getItem() instanceof BlockItem
                 && !stack.is(ModTags.fourSaplingTrees) && !stack.is(Items.MANGROVE_PROPAGULE)
                 && !building.getModuleMatching(ItemListModule.class, m -> m.getId().equals(SAPLINGS_LIST)).isItemInList(new ItemStorage(stack));
    }

    private int findPlantableSaplingSlot()
    {
        for (int slot = 0; slot < getInventory().getSlots(); slot++)
        {
            if (isPlantableSapling(getInventory().getStackInSlot(slot)))
            {
                return slot;
            }
        }
        return -1;
    }

    private ItemStack plantableSaplingInHut()
    {
        for (final ItemStorage storage : IColonyManager.getInstance().getCompatibilityManager().getCopyOfSaplings())
        {
            final ItemStack sapling = storage.getItemStack();
            if (isPlantableSapling(sapling) && InventoryUtils.hasItemInProvider(building, stack -> ItemStack.isSameItem(stack, sapling)))
            {
                return sapling;
            }
        }
        return ItemStack.EMPTY;
    }

    /**
     * The planting spots: the zone, or a ring around the hut (from {@link #RING_GAP} outside the hut box, {@link #RING_WIDTH} wide), on a
     * {@link #PLANT_GRID} grid so the trees have room to grow, nearest first.
     */
    private List<BlockPos> findPlantSpots()
    {
        final BlockPos hut = building.getPosition();
        final int minX, maxX, minZ, maxZ, minY, maxY, inner;
        if (building.shouldRestrict())
        {
            final BlockPos a = building.getStartRestriction();
            final BlockPos b = building.getEndRestriction();
            minX = Math.min(a.getX(), b.getX());
            maxX = Math.max(a.getX(), b.getX());
            minZ = Math.min(a.getZ(), b.getZ());
            maxZ = Math.max(a.getZ(), b.getZ());
            minY = Math.min(a.getY(), b.getY());
            maxY = Math.max(a.getY(), b.getY());
            inner = -1;
        }
        else
        {
            final Tuple<BlockPos, BlockPos> corners = building.getCorners();
            int half = 4;
            if (corners != null && corners.getA() != null && corners.getB() != null)
            {
                for (final BlockPos corner : List.of(corners.getA(), corners.getB()))
                {
                    half = Math.max(half, Math.max(Math.abs(corner.getX() - hut.getX()), Math.abs(corner.getZ() - hut.getZ())));
                }
            }
            inner = half + RING_GAP;
            minX = hut.getX() - inner - RING_WIDTH;
            maxX = hut.getX() + inner + RING_WIDTH;
            minZ = hut.getZ() - inner - RING_WIDTH;
            maxZ = hut.getZ() + inner + RING_WIDTH;
            minY = hut.getY() - 8;
            maxY = hut.getY() + 8;
        }
        final List<BlockPos> grid = new ArrayList<>();
        for (int x = Math.floorDiv(minX + PLANT_GRID - 1, PLANT_GRID) * PLANT_GRID; x <= maxX; x += PLANT_GRID)
        {
            for (int z = Math.floorDiv(minZ + PLANT_GRID - 1, PLANT_GRID) * PLANT_GRID; z <= maxZ; z += PLANT_GRID)
            {
                if (inner >= 0 && Math.max(Math.abs(x - hut.getX()), Math.abs(z - hut.getZ())) < inner)
                {
                    continue;
                }
                grid.add(new BlockPos(x, hut.getY(), z));
            }
        }
        grid.sort(Comparator.comparingDouble(pos -> pos.distSqr(hut)));
        final List<BlockPos> spots = new ArrayList<>();
        final BlockState probe = Blocks.OAK_SAPLING.defaultBlockState();
        int examined = 0;
        int unloaded = 0;
        int height = 0;
        final java.util.Map<String, Integer> problems = new java.util.TreeMap<>();
        for (final BlockPos column : grid)
        {
            if (spots.size() >= PLANT_PER_IDLE * 4 || examined++ >= PLANT_MAX_SPOTS)
            {
                break;
            }
            if (!WorldUtil.isBlockLoaded(world, column))
            {
                unloaded++;
                continue;
            }
            final BlockPos spot = new BlockPos(column.getX(), world.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, column.getX(), column.getZ()), column.getZ());
            if (spot.getY() < minY || spot.getY() > maxY)
            {
                height++;
            }
            else
            {
                final String problem = plantSpotProblem(spot, probe);
                if (problem == null)
                {
                    spots.add(spot);
                }
                else
                {
                    problems.merge(problem, 1, Integer::sum);
                }
            }
        }
        plantNote = (spots.isEmpty() ? "no free spot: " : spots.size() + " free spots: ") + examined + " of " + grid.size() + " grid spots checked, " + unloaded + " not loaded, " + height + " at another height, "
                      + problems;
        return spots;
    }

    /**
     * @param spot    where a sapling would go.
     * @param sapling the sapling block.
     * @return true if a sapling may be planted there.
     */
    private boolean isPlantSpot(@NotNull final BlockPos spot, @NotNull final BlockState sapling)
    {
        return plantSpotProblem(spot, sapling) == null;
    }

    /**
     * @param spot    where a sapling would go.
     * @param sapling the sapling block.
     * @return why no sapling may be planted there, or null if one may.
     */
    @Nullable
    private String plantSpotProblem(@NotNull final BlockPos spot, @NotNull final BlockState sapling)
    {
        final BlockState at = world.getBlockState(spot);
        if (!(at.isAir() || (at.is(BlockTags.REPLACEABLE_BY_TREES) && !at.is(BlockTags.FLOWERS) && at.getFluidState().isEmpty())))
        {
            return "taken";
        }
        final BlockState ground = world.getBlockState(spot.below());
        // In 26.3 minecraft:dirt is only dirt, coarse and rooted dirt: grass, podzol, mud and moss are in the overworld substrate tag
        if (!ground.is(BlockTags.SUBSTRATE_OVERWORLD) || ground.is(Blocks.DIRT_PATH) || ground.is(Blocks.FARMLAND) || ground.is(ModTags.pathingBlocks))
        {
            return "ground";
        }
        for (int i = 1; i <= 4; i++)
        {
            if (!world.getBlockState(spot.above(i)).isAir())
            {
                return "no room above";
            }
        }
        if (!sapling.canSurvive(world, spot))
        {
            return "sapling cannot stay";
        }
        for (final IBuilding hutBuilding : building.getColony().getServerBuildingManager().getBuildings().values())
        {
            final Tuple<BlockPos, BlockPos> corners = hutBuilding.getCorners();
            if (corners != null && corners.getA() != null && corners.getB() != null
                  && AABB.encapsulatingFullBlocks(corners.getA(), corners.getB()).inflate(2).contains(Vec3.atCenterOf(spot)))
            {
                return "hut box";
            }
        }
        final IColony owner = IColonyManager.getInstance().getColonyByPosFromWorld(world, spot);
        if (owner != null && owner.getID() != building.getColony().getID())
        {
            return "other colony";
        }
        for (final BlockPos near : BlockPos.betweenClosed(spot.offset(-2, -1, -2), spot.offset(2, 2, 2)))
        {
            final BlockState state = world.getBlockState(near);
            if (state.is(Blocks.DIRT_PATH) || state.is(Blocks.FARMLAND) || state.is(ModTags.pathingBlocks) || state.is(BlockTags.SAPLINGS)
                  || TreeFootprint.isTreeBlock(state) || TreeFootprint.isBuildSignal(state, false))
            {
                return "next to " + state.getBlock().getDescriptionId().replaceFirst("^block\\.minecraft\\.", "");
            }
        }
        return null;
    }

    @Override
    public void onBlockDropReception(final List<ItemStack> blockDrops)
    {
        super.onBlockDropReception(blockDrops);
        for (final ItemStack stack : blockDrops)
        {
            building.getModule(STATS_MODULE).incrementBy(ITEM_OBTAINED + ";" + stack.getItem().getDescriptionId(), stack.getCount());
        }
    }

    /**
     * Drop fungus instead of wart block
     *
     * @param drops the drops.
     * @return the list of additional drops.
     */
    @Override
    protected List<ItemStack> increaseBlockDrops(final List<ItemStack> drops)
    {
        final List<ItemStack> newDrops = new ArrayList<>();
        for (final ItemStack stack : drops)
        {
            if (world.getRandom().nextInt(100) > 95)
            {
                if (stack.is(ItemTags.WART_BLOCKS))
                {
                    final Block wartBlock = ((BlockItem) stack.getItem()).getBlock();
                    final ItemStack fungus = IColonyManager.getInstance().getCompatibilityManager().getSaplingForLeaf(wartBlock);
                    if (fungus != null)
                    {
                        newDrops.add(fungus);
                    }
                }
            }
        }
        if (newDrops.isEmpty())
        {
            return drops;
        }
        return newDrops;
    }

    /**
     * Walk to the current construction site.
     * <p>
     * Calculates and caches the position where to walk to.
     *
     * @param workAt block to work at.
     * @return true while walking to the site.
     */
    public boolean walkToTree(final BlockPos workAt)
    {
        if (workFrom == null || isSapling(world.getBlockState(workFrom.above())) || isSapling(world.getBlockState(workFrom)))
        {
            workFrom = getWorkingPosition(workAt);
        }

        if (MathUtils.twoDimDistance(worker.blockPosition(), workFrom) <= MIN_WORKING_RANGE && Math.abs(worker.blockPosition().getY() - workFrom.getY()) <= MAX_WORK_HEIGHT)
        {
            return true;
        }

        // Forestry: the give-up clock moves on while he gets nearer to the work spot
        final double distance = Math.sqrt(worker.blockPosition().distSqr(workFrom));
        if (!workFrom.equals(treeProgressFrom))
        {
            treeProgressFrom = workFrom;
            treeBestDistance = distance;
            noteTreeProgress();
        }
        else if (distance < treeBestDistance - 1.0D)
        {
            treeBestDistance = distance;
            noteTreeProgress();
            closeSince = -1;
        }

        // Forestry: right next to the stump, but the work spot cannot be reached (it lies inside a mushroom's cap, behind a corner): he works from where
        // he stands once he gets no nearer
        if (MathUtils.twoDimDistance(worker.blockPosition(), workAt) <= CLOSE_ENOUGH && Math.abs(worker.blockPosition().getY() - workAt.getY()) <= CLOSE_ENOUGH_HEIGHT)
        {
            final long now = world.getGameTime();
            if (closeSince < 0)
            {
                closeSince = now;
            }
            else if (now - closeSince >= CLOSE_ENOUGH_TICKS)
            {
                workFrom = worker.blockPosition();
                treeProgressFrom = workFrom;
                closeSince = -1;
                worker.getNavigation().stop();
                return true;
            }
        }
        else
        {
            closeSince = -1;
        }

        if (pathToTree == null || !pathToTree.isInProgress())
        {
            pathToTree = ((MinecoloniesAdvancedPathNavigate) worker.getNavigation()).setPathJob(new PathJobMoveToWithPassable(world,
              PathfindingUtils.prepareStart(worker),
              workFrom,
              SEARCH_RANGE,
              worker,
              this::isPassable), workFrom, 1.0d, true);
        }

        return false;
    }

    /**
     * Checks whether the given blockstate is passable: natural leaves he may clear on the way, never placed leaves (a hedge he must walk around).
     *
     * @param blockState to check
     * @return true if passable
     */
    private Boolean isPassable(final BlockState blockState)
    {
        return (blockState.is(BlockTags.LEAVES) && !blockState.getOptionalValue(LeavesBlock.PERSISTENT).orElse(false)) || blockState.is(ModTags.hugeMushroomBlocks);
    }

    /**
     * Check if distance to block changed and if we are not moving for too long, try to get unstuck.
     *
     * @return false
     */
    private boolean checkIfStuck()
    {
        if (!worker.getNavigation().isDone())
        {
            final Path path = worker.getNavigation().getPath();
            if (path != null)
            {
                if (path.getNodeCount() > path.getNextNodeIndex())
                {
                    return true;
                }
                return path.getNodeCount() == 0;
            }
            else
            {
                return true;
            }
        }
        return false;
    }

    /**
     * Mines leaves on path and above.
     */
    private void tryUnstuck()
    {
        if (!worker.getNavigation().isDone())
        {
            Path path = worker.getNavigation().getPath();
            if (path != null)
            {
                // Unstuck with path
                final List<BlockPos> checkPositions = new ArrayList<>();
                Node next = path.getNode(Math.min(path.getNextNodeIndex() + 1, path.getNodeCount() - 1));

                // Blocks in front of the worker
                for (int i = 0; i < 2; i++)
                {
                    checkPositions.add(new BlockPos(next.x, next.y + i, next.z));
                }
                if (next.cameFrom != null)
                {
                    addCorners(checkPositions, next.cameFrom, next);
                    next = next.cameFrom;
                    for (int i = 0; i < 2; i++)
                    {
                        checkPositions.add(new BlockPos(next.x, next.y + i, next.z));
                    }
                    if (next.cameFrom != null)
                    {
                        addCorners(checkPositions, next.cameFrom, next);
                        next = next.cameFrom;
                        for (int i = 0; i < 2; i++)
                        {
                            checkPositions.add(new BlockPos(next.x, next.y + i, next.z));
                        }
                    }
                }

                mineIfEqualsBlockTag(checkPositions, BlockTags.LEAVES);
                mineIfEqualsBlockTag(checkPositions, ModTags.hugeMushroomBlocks);
                return;
            }
        }

        // General unstuck
        ArrayList<BlockPos> checkPositions = new ArrayList<>();

        for (Direction direction : Direction.Plane.HORIZONTAL)
        {
            checkPositions.add(new BlockPos(worker.blockPosition().getX(), worker.blockPosition().getY(), worker.blockPosition().getZ()).relative(direction));
            checkPositions.add(new BlockPos(worker.blockPosition().getX(), worker.blockPosition().getY() + 1, worker.blockPosition().getZ()).relative(direction));
        }

        mineIfEqualsBlockTag(checkPositions, BlockTags.LEAVES);
        mineIfEqualsBlockTag(checkPositions, ModTags.hugeMushroomBlocks);
    }

    /**
     * Forestry: a diagonal step squeezes past the two blocks at its corners (the edge of a mushroom's cap stands there, beside the path, not on it).
     */
    private static void addCorners(@NotNull final List<BlockPos> positions, @NotNull final Node from, @NotNull final Node to)
    {
        if (from.x == to.x || from.z == to.z)
        {
            return;
        }
        final int y = Math.max(from.y, to.y);
        for (int i = 0; i < 2; i++)
        {
            positions.add(new BlockPos(from.x, y + i, to.z));
            positions.add(new BlockPos(to.x, y + i, from.z));
        }
    }

    /**
     * Checks blocks for tag and mines the first it fines if its the same
     *
     * @param blockPositions block positions
     * @param tag            tag to check
     */
    private boolean mineIfEqualsBlockTag(List<BlockPos> blockPositions, TagKey<Block> tag)
    {
        for (BlockPos currentPos : blockPositions)
        {
            if (world.getBlockState(currentPos).is(tag) && isClearableObstruction(currentPos))
            {
                mineBlock(currentPos);
                return true;
            }
        }
        return false;
    }

    /**
     * Forestry: what he may break to get through: natural leaves (vanilla would let them decay) or a cap of his own mushroom or fungus (the side of a red
     * mushroom's cap stands between him and the stem), never placed leaves (a hedge), never in a hut box or another colony's claim, never touching a
     * player build.
     *
     * @param pos the block in his way.
     * @return true if he may break it.
     */
    private boolean isClearableObstruction(@NotNull final BlockPos pos)
    {
        final BlockState state = world.getBlockState(pos);
        final boolean natural = TreeFootprint.isDecayingLeaves(state)
                                  ? !state.getValue(LeavesBlock.PERSISTENT)
                                  : TreeFootprint.isCap(state) && job.getTree() != null && job.getTree().ownsCap(pos);
        if (!natural || TreeFootprint.guardPosition(world, pos, building.getColony()) != TreeFootprint.Recheck.OK)
        {
            return false;
        }
        for (final Direction dir : Direction.values())
        {
            if (TreeFootprint.isBuildSignal(world.getBlockState(pos.relative(dir)), false))
            {
                return false;
            }
        }
        return true;
    }

    /**
     * Place a sappling for the current tree.
     */
    private void plantSapling()
    {
        if (plantSapling(job.getTree().getLocation()))
        {
            finishTree();
        }
    }

    /**
     * Check if the worker is standing on a sapling.
     *
     * @return true if so.
     */
    private boolean isOnSapling()
    {
        return isSapling(world.getBlockState(worker.blockPosition()))
                 || isSapling(world.getBlockState(worker.blockPosition().above()))
                 || isSapling(world.getBlockState(worker.blockPosition().below()));
    }

    private static boolean isSapling(final BlockState state)
    {
        return state.is(ModTags.vanillaSaplings);
    }

    /**
     * Plant a sapling at said location.
     *
     * @param location the location to plant the sapling at
     * @return true if a sapling was planted
     */
    private boolean plantSapling(@NotNull final BlockPos location)
    {
        final BlockState worldState = world.getBlockState(location);
        final Block worldBlock = worldState.getBlock();
        if (!(worldBlock instanceof AirBlock) && !isSapling(worldState) && !worldState.canBeReplaced())
        {
            return true;
        }

        // Forestry 4.3: a tree of several stumps is replanted whole; with too few saplings even after the hut, spruce and jungle get one (they also
        // grow from one), dark and pale oak none (one sapling of them never grows)
        final List<BlockPos> stumps = job.getTree().getStumpLocations();
        if (stumps.size() > 1 && countCorrectSaplings() < stumps.size() && !job.getTree().isDynamicTree())
        {
            if (!checkedInHut)
            {
                if (!walkToBuilding())
                {
                    return false;
                }
                checkAndTransferFromHut(job.getTree().getSapling());
                checkedInHut = true;
            }
            if (countCorrectSaplings() < stumps.size())
            {
                final boolean needsAll = job.getTree().getSapling().is(ModTags.fourSaplingTrees);
                for (int i = needsAll ? 0 : 1; i < stumps.size(); i++)
                {
                    job.getTree().removeStump(stumps.get(i));
                }
            }
        }

        final int saplingSlot = findSaplingSlot();

        if (saplingSlot != -1)
        {
            final ItemStack stack = getInventory().getStackInSlot(saplingSlot);
            CitizenItemUtils.setHeldItem(worker, InteractionHand.MAIN_HAND, saplingSlot);

            if (job.getTree().isDynamicTree() && Compatibility.isDynamicTreeSapling(stack))
            {
                Compatibility.plantDynamicSapling(world, location, stack);
                getInventory().extractItem(saplingSlot, 1, false);
                worker.swing(worker.getUsedItemHand(), SwingAnimation.DEFAULT, false);
                timeWaited = 0;
                incrementActionsDoneAndDecSaturation();
                setDelay(TIMEOUT_DELAY);
                return true;
            }
            else
            {
                final Block block = ((BlockItem) stack.getItem()).getBlock();
                placeSaplings(block);

                final SoundType soundType = block.getSoundType(world.getBlockState(location), world, location, worker);
                world.playSound(null,
                  this.worker.blockPosition(),
                  soundType.getPlaceSound(),
                  SoundSource.BLOCKS,
                  soundType.getVolume(),
                  soundType.getPitch());
            }

            worker.swing(worker.getUsedItemHand(), SwingAnimation.DEFAULT, false);
        }

        if (timeWaited >= MAX_WAITING_TIME / 2 && !checkedInHut && walkToBuilding())
        {
            checkAndTransferFromHut(job.getTree().getSapling());
            checkedInHut = true;
        }

        if (job.getTree().getStumpLocations().isEmpty() || timeWaited >= MAX_WAITING_TIME)
        {
            timeWaited = 0;
            incrementActionsDoneAndDecSaturation();
            setDelay(TIMEOUT_DELAY);
            return true;
        }
        timeWaited += 10;
        return false;
    }

    @Override
    public void fillItemsList()
    {
        if (job.getTree() != null)
        {
            searchForItems(new AABB(job.getTree().getLocation())
                             .expandTowards(RANGE_HORIZONTAL_PICKUP, RANGE_VERTICAL_PICKUP, RANGE_HORIZONTAL_PICKUP)
                             .expandTowards(-RANGE_HORIZONTAL_PICKUP, -RANGE_VERTICAL_PICKUP, -RANGE_HORIZONTAL_PICKUP));
        }
        else
        {
            searchForItems(worker.getBoundingBox()
                             .expandTowards(RANGE_HORIZONTAL_PICKUP, RANGE_VERTICAL_PICKUP, RANGE_HORIZONTAL_PICKUP)
                             .expandTowards(-RANGE_HORIZONTAL_PICKUP, -RANGE_VERTICAL_PICKUP, -RANGE_HORIZONTAL_PICKUP));
        }
    }

    private int findSaplingSlot()
    {
        for (int slot = 0; slot < getInventory().getSlots(); slot++)
        {
            final ItemStack stack = getInventory().getStackInSlot(slot);
            if (isCorrectSapling(stack))
            {
                return slot;
            }
        }
        return -1;
    }

    /**
     * @param block a sapling block.
     * @return a slot that holds at least one sapling of the current tree placing that block, or -1.
     */
    private int findSaplingSlot(@NotNull final Block block)
    {
        for (int slot = 0; slot < getInventory().getSlots(); slot++)
        {
            final ItemStack stack = getInventory().getStackInSlot(slot);
            if (isCorrectSapling(stack) && stack.getItem() instanceof BlockItem item && item.getBlock() == block)
            {
                return slot;
            }
        }
        return -1;
    }

    /**
     * Place saplings to the stump locations, each paid with one sapling from his inventory. The slot is picked again for every stump, so saplings split
     * over several slots are all used and none is ever placed without one taken.
     *
     * @param block sapling block
     */
    private void placeSaplings(@NotNull final Block block)
    {
        while (!job.getTree().getStumpLocations().isEmpty())
        {
            final BlockPos pos = job.getTree().getStumpLocations().get(0);
            final int saplingSlot = findSaplingSlot(block);
            if (saplingSlot == -1)
            {
                return;
            }
            final ItemStack sapling = getInventory().getStackInSlot(saplingSlot);
            final BlockState at = world.getBlockState(pos);
            if (!mayPlantStump(pos, at))
            {
                // A stump that became protected (a hut box, another colony's land) or holds a player's block or flower now is left as it is
                job.getTree().removeStump(pos);
                continue;
            }
            if (sapling.is(Tags.Items.MUSHROOMS))
            {
                building.addNetherTree(pos);
            }
            else if (sapling.is(fungi) && world.getBlockState(pos.below()).getBlock() instanceof NetherrackBlock)
            {
                final Block new_block = sapling.is(Items.WARPED_FUNGUS) ? Blocks.WARPED_NYLIUM : Blocks.CRIMSON_NYLIUM;
                world.setBlockAndUpdate(pos.below(), new_block.defaultBlockState());
                building.addNetherTree(pos);
            }
            if (!block.defaultBlockState().canSurvive(world, pos) || Objects.equals(at, block.defaultBlockState()))
            {
                // A stump he cannot plant is left as it is
                job.getTree().removeStump(pos);
                continue;
            }

            if (getInventory().extractItem(saplingSlot, 1, true).isEmpty())
            {
                return;
            }
            CitizenItemUtils.setHeldItem(worker, InteractionHand.MAIN_HAND, saplingSlot);
            if (!world.setBlockAndUpdate(pos, block.defaultBlockState()))
            {
                return;
            }
            getInventory().extractItem(saplingSlot, 1, false);
            job.getTree().removeStump(pos);
        }
    }

    /**
     * Forestry: a stump may be replanted only where a direct cut would be allowed now (no hut box, no other colony's land, the ground under it too),
     * and only on air or a block anything replaces that is no flower.
     *
     * @param pos the stump.
     * @param at  the block there now.
     * @return true if he may plant there.
     */
    private boolean mayPlantStump(@NotNull final BlockPos pos, @NotNull final BlockState at)
    {
        if (!(at.isAir() || (at.canBeReplaced() && !at.is(BlockTags.FLOWERS))))
        {
            return false;
        }
        return TreeFootprint.guardPosition(world, pos, building.getColony()) == TreeFootprint.Recheck.OK
                 && TreeFootprint.guardPosition(world, pos.below(), building.getColony()) == TreeFootprint.Recheck.OK;
    }

    /**
     * Checks if this is the correct Sapling.
     *
     * @param stack incoming stack.
     * @return true if so.
     */
    private boolean isCorrectSapling(final ItemStack stack)
    {
        if (!ItemStackUtils.isStackSapling(stack))
        {
            return false;
        }

        if (ItemStackUtils.isEmpty(job.getTree().getSapling()))
        {
            return false;
        }
        else
        {
            return ItemStack.isSameItem(job.getTree().getSapling(), stack);
        }
    }

    /**
     * Checks if the lumberjack found items on the ground, if yes collect them, if not search for them.
     *
     * @return LUMBERJACK_GATHERING as long as gathering takes.
     */
    private IAIState gathering()
    {

        if (getItemsForPickUp() == null)
        {
            fillItemsList();
        }

        if (getItemsForPickUp() != null && !getItemsForPickUp().isEmpty())
        {
            gatherItems();
            return getState();
        }
        resetGatheringItems();
        return LUMBERJACK_SEARCHING_TREE;
    }

    @Override
    protected int getActionsDoneUntilDumping()
    {
        return MAX_BLOCKS_MINED;
    }

    @Override
    protected void updateRenderMetaData()
    {
        worker.setRenderMetadata(hasLogs() ? RENDER_META_LOGS : "");
    }

    @Override
    public BlockPos getWorkingPosition(final BlockPos targetPosition)
    {
        return getWorkingPosition(2, targetPosition, 0);
    }

    /**
     * Checks if the lumberjack has logs in it's inventory.
     *
     * @return true if he has logs.
     */
    private boolean hasLogs()
    {
        return InventoryUtils.hasItemInItemHandler(getInventory(), this::isStackLog);
    }
}
