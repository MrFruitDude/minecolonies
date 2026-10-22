package com.minecolonies.api.configuration;

import com.ldtteam.common.config.AbstractConfiguration;
import com.minecolonies.api.colony.permissions.Explosions;
import com.minecolonies.api.colony.permissions.FactionPlayerRank;
import com.minecolonies.api.util.constant.CitizenConstants;
import net.neoforged.neoforge.common.ModConfigSpec.*;

import java.util.List;
import java.util.function.Predicate;

import static com.minecolonies.api.util.constant.Constants.*;

/**
 * Mod server configuration. Loaded serverside, synced on connection.
 */
public class ServerConfiguration extends AbstractConfiguration
{
    /*  --------------------------------------------------------------------------- *
     *  ------------------- ######## Gameplay settings ######## ------------------- *
     *  --------------------------------------------------------------------------- */

    public final IntValue     initialCitizenAmount;

    /**
     * The free citizen floor: once a colony has had its starting citizens, deaths are refilled for free (no bed needed) up to this many. Also the population below which
     * citizens neither fall sick nor need two to reproduce. {@link #initialCitizenAmount} is only the starting count.
     */
    public final IntValue     respawnCitizenAmount;
    public final BooleanValue allowInfiniteSupplyChests;
    public final BooleanValue allowInfiniteColonies;

    /**
     * How many colonies one player may own at once (all dimensions). 1 is the classic MineColonies rule.
     */
    public final IntValue maxColoniesPerPlayer;

    /**
     * The rank players have in a faction-owned colony (an AI neighbour) unless given another one. Never owner or officer.
     */
    public final EnumValue<FactionPlayerRank> factionColonyDefaultPlayerRank;

    /**
     * Whether faction-owned colonies (AI neighbours) can be raided. Off by default.
     */
    public final BooleanValue factionColoniesCanBeRaided;
    public final BooleanValue allowOtherDimColonies;
    public final IntValue     maxCitizenPerColony;
    public final BooleanValue enableInDevelopmentFeatures;
    public final BooleanValue alwaysRenderNameTag;
    public final BooleanValue workersAlwaysWorkInRain;
    public final IntValue     luckyBlockChance;
    public final IntValue     minThLevelToTeleport;
    public final DoubleValue  foodModifier;

    /**
     * Beds a residence has on top of its level (a level 1 residence with 1 extra holds 2 citizens). Taverns keep their own count.
     */
    public final IntValue     residenceExtraBeds;

    /**
     * Below this many citizens the guard security happiness factor is never below neutral, so a young colony is not unhappy for lacking guards. 0 = off.
     */
    public final IntValue     securityGraceCitizens;
    public final IntValue     diseaseModifier;
    public final BooleanValue forceLoadColony;
    public final IntValue     loadtime;
    public final IntValue     colonyLoadStrictness;
    public final IntValue     maxTreeSize;
    public final BooleanValue noSupplyPlacementRestrictions;
    public final BooleanValue skyRaiders;

    /*  --------------------------------------------------------------------------- *
     *  ------------------- ######## Research settings ######## ------------------- *
     *  --------------------------------------------------------------------------- */
    public final BooleanValue                        researchCreativeCompletion;
    public final BooleanValue                        researchDebugLog;
    public final ConfigValue<List<? extends String>> researchResetCost;

    /*  --------------------------------------------------------------------------- *
     *  ------------------- ######## Command settings ######## ------------------- *
     *  --------------------------------------------------------------------------- */

    public final BooleanValue canPlayerUseRTPCommand;
    public final BooleanValue canPlayerUseColonyTPCommand;
    public final BooleanValue canPlayerUseAllyTHTeleport;
    public final BooleanValue canPlayerUseHomeTPCommand;
    public final BooleanValue canPlayerUseShowColonyInfoCommand;
    public final BooleanValue canPlayerUseKillCitizensCommand;
    public final BooleanValue canPlayerUseModifyCitizensCommand;
    public final BooleanValue canPlayerUseAddOfficerCommand;
    public final BooleanValue canPlayerUseDeleteColonyCommand;
    public final BooleanValue canPlayerUseResetCommand;

    /*  --------------------------------------------------------------------------- *
     *  ------------------- ######## Claim settings ######## ------------------- *
     *  --------------------------------------------------------------------------- */

    public final IntValue     maxColonySize;
    public final IntValue     minColonyDistance;
    public final IntValue     initialColonySize;
    public final IntValue     maxDistanceFromWorldSpawn;
    public final IntValue     minDistanceFromWorldSpawn;

    /*  ------------------------------------------------------------------------- *
     *  ------------------- ######## Combat Settings ######## ------------------- *
     *  ------------------------------------------------------------------------- */

    public final BooleanValue enableColonyRaids;
    public final IntValue     raidDifficulty;
    public final IntValue     maxRaiders;
    public final BooleanValue raidersbreakblocks;
    public final IntValue     averageNumberOfNightsBetweenRaids;
    public final IntValue     minimumNumberOfNightsBetweenRaids;
    public final BooleanValue raidersbreakdoors;
    public final BooleanValue mobAttackCitizens;
    public final DoubleValue  guardDamageMultiplier;
    public final DoubleValue  guardHealthMult;
    public final BooleanValue pvp_mode;

    /*  ----------------------------------------------------------------------------- *
     *  ------------------- ######## Permission Settings ######## ------------------- *
     *  ----------------------------------------------------------------------------- */

    public final BooleanValue          enableColonyProtection;
    public final EnumValue<Explosions> turnOffExplosionsInColonies;
    public final IntValue              permissionEventMinBypassPermLevel;

    /*  -------------------------------------------------------------------------------- *
     *  ------------------- ######## Compatibility Settings ######## ------------------- *
     *  -------------------------------------------------------------------------------- */

    public final BooleanValue                        auditCraftingTags;
    public final BooleanValue                        debugInventories;
    public final BooleanValue                        blueprintBuildMode;

    /*  ------------------------------------------------------------------------------ *
     *  ------------------- ######## Pathfinding Settings ######## ------------------- *
     *  ------------------------------------------------------------------------------ */

    public final IntValue minimumRailsToPath;
    public final DoubleValue pathNodeLimitMultiplier;
    public final IntValue pathfindingWorkerThreads;

    /*  --------------------------------------------------------------------------------- *
     *  ------------------- ######## Request System Settings ######## ------------------- *
     *  --------------------------------------------------------------------------------- */

    public final BooleanValue creativeResolve;

    /*  ------------------------------------------------------------------------- *
     *  ------------------- ######## Worker settings ######## ------------------- *
     *  ------------------------------------------------------------------------- */

    /**
     * How fast every worker does its work (waits count down this many times faster), on top of the colony pace of another mod.
     */
    public final DoubleValue workerSpeedMultiplier;

    /**
     * Length of the workers' shift in hours of the 24 hour day (1000 ticks each): they work from sunrise until this hour.
     */
    public final DoubleValue workerHoursPerDay;

    /**
     * Whether long walks of workers are replaced by a timed relocation.
     */
    public final EnumValue<com.minecolonies.api.colony.WorkerWalking> workerAbstractWalking;

    /**
     * Walks longer than this many blocks are abstracted in the LONG_ONLY mode.
     */
    public final IntValue workerAbstractWalkingMinBlocks;

    /*  --------------------------------------------------------------------------- *
     *  ------------------- ######## Builder settings ######## --------------------- *
     *  --------------------------------------------------------------------------- */

    public final BooleanValue builderCollaboration;
    public final IntValue     builderCollaborationMaxHelpers;
    public final IntValue     builderCollaborationBlocksPerHelper;
    public final IntValue     builderCollaborationMinRemaining;
    public final IntValue     builderCollaborationMinOrderScore;
    public final IntValue     builderCollaborationStaySeconds;
    public final IntValue     builderCollaborationLeaseSize;
    public final IntValue     builderCollaborationLeaseSeconds;
    public final DoubleValue  builderCollaborationIdleHelperSpeed;
    public final BooleanValue builderFastClear;

    /**
     * How much faster builders place and break blocks (on top of the level scaling and the worker speed).
     */
    public final DoubleValue builderSpeedMultiplier;

    /**
     * Physical reach of builders: a block is placed or broken only within this many blocks (3D, from the eye) of where the builder stands.
     */
    public final DoubleValue  builderReach;
    public final BooleanValue builderPhysicalReach;

    /**
     * Whether builders put up temporary scaffolding (vanilla scaffolding blocks) to reach blocks they cannot reach from the ground, and
     * how many scaffolding items they keep in stock for it.
     */
    public final BooleanValue builderScaffolding;
    public final IntValue     builderScaffoldingStock;

    /* ---------------------------------------------------------------------------- *
     *  ------------------- ######## Labourer settings ######## -------------------- *
     *  --------------------------------------------------------------------------- */

    public final BooleanValue labourersEnabled;
    public final IntValue     labourersWorldMax;
    public final IntValue     labourersMaxTotalCeiling;
    public final EnumValue<com.minecolonies.api.colony.labour.LabourModes.PostMode>     labourersPostMode;
    public final IntValue     labourersPostRange;
    public final IntValue     labourersGatherRadius;
    public final EnumValue<com.minecolonies.api.colony.labour.LabourModes.CoverageMode> labourersCoverageMode;
    public final IntValue     labourersCoverageGrace;
    public final IntValue     labourersLeaseTicks;
    public final BooleanValue labourersPocketCrafting;
    public final EnumValue<com.minecolonies.api.colony.labour.LabourModes.SmeltMode>    labourersSmelt;
    public final BooleanValue labourersStoneTools;
    public final IntValue     labourersKeepTrees;
    public final BooleanValue labourersDigUnprovenGround;
    public final IntValue     labourersSupplyGraceTicks;
    public final IntValue     labourersSupplyMaxTicks;
    public final IntValue     labourersQuarryDepth;
    public final IntValue     labourersEarlyBuildings;
    public final IntValue     labourersEarlyGraceTicks;
    public final BooleanValue labourersEarlyAllUnemployed;
    public final BooleanValue labourersHutCredit;

    /**
     * RS1: stock and space are reserved in a per-colony ledger when a delivery is planned, and released when it ends.
     */
    public final BooleanValue reservations;

    /**
     * RS2: named wait reasons, event-driven re-evaluation of waiting requests, exponential back-off, courier deadlines.
     */
    public final BooleanValue smartRetry;

    /**
     * RS3: couriers pick tasks by (importance, building priority, wait time), distance only as the tiebreak.
     */
    public final BooleanValue priorityClasses;

    /**
     * RS4: a courier that just finished a task decides on its next one at once, instead of idling for most of the decision
     * delay (5 s) after every task. Off = the original timing.
     */
    public final BooleanValue courierImmediateNextTask;

    /**
     * A courier that holds a task finishes that delivery before it goes to eat, sleep or hide from the rain (it would otherwise leave the task standing in
     * its queue for the whole break, with whoever waits for it). Safety caps: a critically hungry courier, and one whose delivery has taken too long, goes
     * anyway and hands the task back to the pool. Sickness and raids still interrupt at once. Off = the original behaviour.
     */
    public final BooleanValue courierFinishesDelivery;

    /**
     * Global storage network: the colony's warehouses are one storage (one stock index, one courier pool, source and
     * drop-off chosen by estimated completion time).
     */
    public final BooleanValue storageNetwork;

    /**
     * Storage network: how far from its own warehouse a courier serves other warehouses, in blocks. 0 = the whole colony.
     */
    public final IntValue storageServiceRange;

    /**
     * Storage network: soft cap per item type over the whole colony (0 = off). Couriers do not collect more of an item
     * from buildings once the colony holds this many.
     */
    public final IntValue storageItemCap;

    /**
     * Builds server configuration.
     *
     * @param builder config builder
     */
    public ServerConfiguration(final Builder builder)
    {
        super(builder, MOD_ID);
        final Predicate<Object> stringValidator = s -> s instanceof String;

        createCategory("gameplay");

        initialCitizenAmount = defineInteger("initialcitizenamount", 8, 1, 10);
        respawnCitizenAmount = defineInteger("respawncitizenamount", 4, 1, 10);
        allowInfiniteSupplyChests = defineBoolean("allowinfinitesupplychests", false);
        allowInfiniteColonies = defineBoolean("allowinfinitecolonies", false);
        allowOtherDimColonies = defineBoolean("allowotherdimcolonies", true);
        maxCitizenPerColony = defineInteger("maxcitizenpercolony", 250, 25, CitizenConstants.CITIZEN_LIMIT_MAX);
        enableInDevelopmentFeatures = defineBoolean("enableindevelopmentfeatures", false);
        alwaysRenderNameTag = defineBoolean("alwaysrendernametag", true);
        // round 13 decision: citizens keep working in the rain by default (an existing config file keeps its value)
        workersAlwaysWorkInRain = defineBoolean("workersalwaysworkinrain", true);
        luckyBlockChance = defineInteger("luckyblockchance", 1, 0, 100);
        minThLevelToTeleport = defineInteger("minthleveltoteleport", 3, 0, 5);
        foodModifier = defineDouble("foodmodifier", 0.6, 0.1, 100);
        residenceExtraBeds = defineInteger("residenceextrabeds", 1, 0, 5);
        securityGraceCitizens = defineInteger("securitygracecitizens", 15, 0, 250);
        diseaseModifier = defineInteger("diseasemodifier", 5, 1, 100);
        forceLoadColony = defineBoolean("forceloadcolony", true);
        loadtime = defineInteger("loadtime", 20, 1, 1440);
        colonyLoadStrictness = defineInteger("colonyloadstrictness", 3, 1, 15);
        maxTreeSize = defineInteger("maxtreesize", 400, 1, 1000);
        noSupplyPlacementRestrictions = defineBoolean("nosupplyplacementrestrictions", false);
        skyRaiders = defineBoolean("skyraiders", false);

        swapToCategory("colonies");
        // Founding more than one colony stays refused by the founding rules of a mod that raises this; the config alone only lifts the cap.
        maxColoniesPerPlayer = defineInteger("maxPerPlayer", 1, 1, 64);

        swapToCategory("factions");
        factionColonyDefaultPlayerRank = defineEnum("defaultPlayerRank", FactionPlayerRank.NEUTRAL);
        factionColoniesCanBeRaided = defineBoolean("canBeRaided", false);

        swapToCategory("research");
        researchCreativeCompletion = defineBoolean("researchcreativecompletion", true);
        researchDebugLog = defineBoolean("researchdebuglog", false);
        researchResetCost = defineList("researchresetcost", () -> "item ID, possibly with data", stringValidator, "minecolonies:ancienttome:1");

        swapToCategory("commands");

        canPlayerUseRTPCommand = defineBoolean("canplayerusertpcommand", false);
        canPlayerUseColonyTPCommand = defineBoolean("canplayerusecolonytpcommand", false);
        canPlayerUseAllyTHTeleport = defineBoolean("canplayeruseallytownhallteleport", true);
        canPlayerUseHomeTPCommand = defineBoolean("canplayerusehometpcommand", false);
        canPlayerUseShowColonyInfoCommand = defineBoolean("canplayeruseshowcolonyinfocommand", true);
        canPlayerUseKillCitizensCommand = defineBoolean("canplayerusekillcitizenscommand", false);
        canPlayerUseModifyCitizensCommand = defineBoolean("canplayerusemodifycitizenscommand", false);
        canPlayerUseAddOfficerCommand = defineBoolean("canplayeruseaddofficercommand", true);
        canPlayerUseDeleteColonyCommand = defineBoolean("canplayerusedeletecolonycommand", false);
        canPlayerUseResetCommand = defineBoolean("canplayeruseresetcommand", false);

        swapToCategory("claims");

        maxColonySize = defineInteger("maxColonySize", 20, 1, 250);
        minColonyDistance = defineInteger("minColonyDistance", 8, 1, 200);
        initialColonySize = defineInteger("initialColonySize", 4, 1, 15);
        maxDistanceFromWorldSpawn = defineInteger("maxdistancefromworldspawn", 30000, 1000, Integer.MAX_VALUE);
        minDistanceFromWorldSpawn = defineInteger("mindistancefromworldspawn", 0, 0, 1000);

        swapToCategory("combat");

        enableColonyRaids = defineBoolean("dobarbariansspawn", true);
        raidDifficulty = defineInteger("barbarianhordedifficulty", DEFAULT_BARBARIAN_DIFFICULTY, MIN_BARBARIAN_DIFFICULTY, MAX_BARBARIAN_DIFFICULTY);
        maxRaiders = defineInteger("maxBarbarianSize", 80, MIN_BARBARIAN_HORDE_SIZE, MAX_BARBARIAN_HORDE_SIZE);
        raidersbreakblocks = defineBoolean("dobarbariansbreakthroughwalls", true);
        averageNumberOfNightsBetweenRaids = defineInteger("averagenumberofnightsbetweenraids", 14, 1, 50);
        minimumNumberOfNightsBetweenRaids = defineInteger("minimumnumberofnightsbetweenraids", 10, 1, 30);
        mobAttackCitizens = defineBoolean("mobattackcitizens", true);
        raidersbreakdoors = defineBoolean("shouldraiderbreakdoors", true);
        guardDamageMultiplier = defineDouble("guardDamageMultiplier", 1.0, 0.1, 15.0);
        guardHealthMult = defineDouble("guardhealthmult", 1.0, 0.1, 5.0);
        pvp_mode = defineBoolean("pvp_mode", false);

        swapToCategory("permissions");

        enableColonyProtection = defineBoolean("enablecolonyprotection", true);
        turnOffExplosionsInColonies = defineEnum("turnoffexplosionsincolonies", Explosions.DAMAGE_ENTITIES);
        permissionEventMinBypassPermLevel = defineInteger("permissioneventbypassminpermlevel", 2, 0, 4);

        swapToCategory("compatibility");

        auditCraftingTags = defineBoolean("auditcraftingtags", false);
        debugInventories = defineBoolean("debuginventories", false);
        blueprintBuildMode = defineBoolean("blueprintbuildmode", false);

        pathNodeLimitMultiplier = defineDouble("pathNodeLimitMultiplier", 1, 1, 4);
        pathfindingWorkerThreads = defineInteger("pathfindingworkerthreads", 2, 1, 4);
        minimumRailsToPath = defineInteger("minimumrailstopath", 8, 5, 100);

        swapToCategory("requestSystem");

        creativeResolve = defineBoolean("creativeresolve", false);
        reservations = defineBoolean("reservations", true);
        smartRetry = defineBoolean("smartRetry", true);
        priorityClasses = defineBoolean("priorityClasses", true);
        courierImmediateNextTask = defineBoolean("courierImmediateNextTask", true);
        courierFinishesDelivery = defineBoolean("courierFinishesDelivery", true);
        storageNetwork = defineBoolean("storageNetwork", true);
        storageServiceRange = defineInteger("storageServiceRange", 0, 0, 100000);
        storageItemCap = defineInteger("storageItemCap", 0, 0, 100000000);

        swapToCategory("workers");

        // The worker efficiency settings (per world). Worker speed is multiplied with the colony pace of another mod (Colony Command).
        workerSpeedMultiplier = defineDouble("speedMultiplier", 1.5, 0.5, 4.0);
        workerHoursPerDay = defineDouble("hoursPerDay", 12.6, 6.0, 22.0);
        workerAbstractWalking = defineEnum("abstractWalking", com.minecolonies.api.colony.WorkerWalking.LONG_ONLY);
        workerAbstractWalkingMinBlocks = defineInteger("abstractWalkingMinBlocks", 48, 8, 400);

        swapToCategory("builders");

        // Idle builders help the builder who leads an order, and take a new order when one arrives.
        builderCollaboration = defineBoolean("collaboration", true);
        builderCollaborationMaxHelpers = defineInteger("collaborationMaxHelpers", 3, 1, 8);
        builderCollaborationBlocksPerHelper = defineInteger("collaborationBlocksPerHelper", 1500, 1, 1000000);
        builderCollaborationMinRemaining = defineInteger("collaborationMinRemaining", 64, 1, 1000000);
        // A structure gets helpers when its blocks plus twice the blocks that need a tower (out of reach from the ground) reach this (0: every structure does).
        builderCollaborationMinOrderScore = defineInteger("collaborationMinOrderScore", 600, 0, 1000000);
        builderCollaborationStaySeconds = defineInteger("collaborationStaySeconds", 120, 0, 3600);
        builderCollaborationLeaseSize = defineInteger("collaborationLeaseSize", 12, 1, 64);
        builderCollaborationLeaseSeconds = defineInteger("collaborationLeaseSeconds", 120, 10, 3600);
        // The pace of a citizen without a job who helps a builder, as a share of the builder's own (places and breaks at this share of his speed).
        builderCollaborationIdleHelperSpeed = defineDouble("collaborationIdleHelperSpeed", 0.6, 0.1, 1.0);
        // The clear stage leaves blocks alone that already are what the blueprint wants there (instead of mining and placing them again).
        builderFastClear = defineBoolean("fastClear", true);
        builderSpeedMultiplier = defineDouble("speedMultiplier", 1.5, 0.5, 4.0);
        // 3D reach from the eye (a worker places and breaks only what he can reach; out of reach he walks to a better spot or builds scaffolding)
        builderReach = defineDouble("reach", 5.0, 2.0, 16.0);
        builderPhysicalReach = defineBoolean("physicalReach", true);
        builderScaffolding = defineBoolean("scaffolding", true);
        builderScaffoldingStock = defineInteger("scaffoldingStock", 32, 8, 128);

        swapToCategory("labourers");

        // Unemployed citizens (and idle builders, for their own build) gather and craft the basic goods nobody in the colony is set up to make.
        labourersEnabled = defineBoolean("enabled", true);
        labourersWorldMax = defineInteger("worldMax", 64, 0, 4096);
        labourersMaxTotalCeiling = defineInteger("maxTotalCeiling", 16, 1, 256);
        labourersPostMode = defineEnum("postMode", com.minecolonies.api.colony.labour.LabourModes.PostMode.ANY_IN_RANGE);
        labourersPostRange = defineInteger("postRange", 64, 8, 256);
        labourersGatherRadius = defineInteger("gatherRadius", 96, 8, 160);
        labourersCoverageMode = defineEnum("coverageMode", com.minecolonies.api.colony.labour.LabourModes.CoverageMode.BUILT);
        labourersCoverageGrace = defineInteger("coverageGrace", 2400, 0, 72000);
        labourersLeaseTicks = defineInteger("leaseTicks", 2400, 200, 72000);
        labourersPocketCrafting = defineBoolean("pocketCrafting", true);
        labourersSmelt = defineEnum("smelt", com.minecolonies.api.colony.labour.LabourModes.SmeltMode.BASIC);
        labourersStoneTools = defineBoolean("stoneTools", true);
        labourersKeepTrees = defineInteger("keepTrees", 4, 0, 64);
        // Off: labourers dig sand, dirt, gravel and stone from the world only inside the colony's quarry zones (a manager marks them in the Labour panel), automatic
        // or directed; elsewhere a player's old build of them cannot be told from the world's own ground, and a directed order's area alone does not allow it.
        labourersDigUnprovenGround = defineBoolean("digUnprovenGround", false);
        labourersSupplyGraceTicks = defineInteger("supplyGraceTicks", 1200, 0, 72000);
        labourersSupplyMaxTicks = defineInteger("supplyMaxTicks", 6000, 200, 72000);
        // Quarry zones: a manager marks an area where labour may dig the ground even with digUnprovenGround off; never deeper than this below the zone's surface.
        labourersQuarryDepth = defineInteger("quarryDepth", 8, 1, 32);
        // Early pace: while the colony has fewer built buildings than this, every unemployed citizen may labour and builder requests wait only earlyGraceTicks;
        // from this count on the pace eases to the normal one, one step per built building, and is normal from twice this count less one.
        labourersEarlyBuildings = defineInteger("earlyBuildings", 10, 0, 100);
        labourersEarlyGraceTicks = defineInteger("earlyGraceTicks", 200, 0, 72000);
        labourersEarlyAllUnemployed = defineBoolean("earlyAllUnemployed", true);
        // Hut credit: a hut placed from the overview with no hut item in hand and none in the warehouse goes ahead and owes its item (at most 2 open per colony).
        labourersHutCredit = defineBoolean("hutCredit", true);

        finishCategory();
    }
}
