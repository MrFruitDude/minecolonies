package com.minecolonies.core.gametest;

import com.minecolonies.api.blocks.ModBlocks;
import com.minecolonies.api.colony.ICitizenData;
import com.minecolonies.api.colony.IColony;
import com.minecolonies.api.colony.buildings.IBuilding;
import com.minecolonies.api.entity.citizen.AbstractEntityCitizen;
import com.minecolonies.api.inventory.InventoryCitizen;
import com.minecolonies.api.research.util.ResearchConstants;
import com.minecolonies.api.util.Log;
import com.minecolonies.api.util.Utils;
import com.minecolonies.api.util.constant.Constants;
import com.minecolonies.core.colony.buildings.modules.BuildingModules;
import com.minecolonies.core.colony.workorders.WorkOrderDecoration;
import com.minecolonies.core.colony.workorders.collab.BuilderCollab;
import com.minecolonies.core.entity.ai.workers.AbstractEntityAIBasic;
import com.minecolonies.core.entity.ai.workers.AbstractEntityAIInteract;
import com.minecolonies.core.entity.ai.workers.guard.AbstractEntityAIGuard;
import com.minecolonies.core.entity.ai.workers.guard.MeleeCombatAI;
import com.minecolonies.core.entity.ai.workers.util.WorkerSettings;
import com.minecolonies.core.entity.citizen.EntityCitizen;
import com.minecolonies.core.research.GlobalResearchEffect;
import com.minecolonies.core.util.citizenutils.CitizenItemUtils;
import net.minecraft.core.BlockPos;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.Registries;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.Tool;
import net.minecraft.world.item.component.Weapon;
import net.minecraft.world.item.enchantment.Enchantments;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.event.RegisterGameTestsEvent;
import net.neoforged.neoforge.registries.DeferredItem;
import net.neoforged.neoforge.registries.DeferredRegister;
import org.jetbrains.annotations.Nullable;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.function.BiConsumer;
import java.util.function.BiPredicate;
import java.util.function.Consumer;

import static com.minecolonies.core.gametest.MinecoloniesGameTests.foundGameTestColony;
import static com.minecolonies.core.gametest.MinecoloniesGameTests.placeProductionBuilding;

/**
 * Citizens' tools wear exactly like the same tool in the hands of a player doing the same action: the reference is the vanilla code itself
 * (a connected survival player wearing the tool for the same blocks with {@code ItemStack#mineBlock}, the call of {@code ServerPlayerGameMode#destroyBlock}, or attacking with {@code Player#attack}).
 * The only things that make a citizen's tool last longer are enchantments (Unbreaking) and the colony research "Citizen Tools +X% Durability".
 * Tests are named {@code minecolonies:tooldur_*}; a development run of just these: {@code MINECOLONIES_GAMETEST_ONLY=tooldur}.
 */
public final class ToolDurabilityGameTests
{
    private ToolDurabilityGameTests()
    {
    }

    /**
     * The mod tool of the hook test. An item can only be created while the registries are open, so it is registered with the mod (the registrar of the
     * GameTests links this class only when GameTests are enabled).
     */
    private static final DeferredRegister.Items TEST_ITEMS = DeferredRegister.createItems(Constants.MOD_ID);
    private static final DeferredItem<ProbeTool> PROBE = TEST_ITEMS.register("tooldur_probe", ProbeTool::new);

    /**
     * Hooks the registration of the probe item onto the mod bus. Call during mod construction only.
     */
    static void registerItems(final IEventBus modBus)
    {
        TEST_ITEMS.register(modBus);
    }

    static void register(final RegisterGameTestsEvent event)
    {
        add(event, "tooldur_vanilla_rules", ToolDurabilityGameTests::vanillaRules);
        add(event, "tooldur_unbreaking", ToolDurabilityGameTests::unbreaking);
        add(event, "tooldur_research_only_reduces", ToolDurabilityGameTests::researchOnlyReduces);
        add(event, "tooldur_item_hooks_once", ToolDurabilityGameTests::itemHooksRunOnce);
        add(event, "tooldur_ai_sawmill_mine", helper -> aiMine(helper, "sawmill", ModBlocks.blockHutSawmill, "craftsmanship/carpentry/sawmill1.blueprint",
          (hut, citizen) -> hut.getModule(BuildingModules.SAWMILL_WORK).assignCitizen(citizen), Items.WOODEN_PICKAXE, Blocks.STONE.defaultBlockState()));
        add(event, "tooldur_ai_miner_mine", helper -> aiMine(helper, "miner", ModBlocks.blockHutMiner, "fundamentals/miner1.blueprint",
          (hut, citizen) -> hut.getModule(BuildingModules.MINER_WORK).assignCitizen(citizen), Items.WOODEN_PICKAXE, Blocks.STONE.defaultBlockState()));
        add(event, "tooldur_ai_lumberjack_mine", helper -> aiMine(helper, "lumberjack", ModBlocks.blockHutLumberjack, "fundamentals/lumberjack1.blueprint",
          (hut, citizen) -> hut.getModule(BuildingModules.FORESTER_WORK).assignCitizen(citizen), Items.WOODEN_AXE, Blocks.OAK_LOG.defaultBlockState()));
        add(event, "tooldur_builder_plain", helper -> builderClearsStone(helper, "plain", 1.0D, 1.0D, false));
        add(event, "tooldur_builder_default", helper -> builderClearsStone(helper, "default", null, null, null));
        add(event, "tooldur_builder_max", helper -> builderClearsStone(helper, "max", 4.0D, 4.0D, true));
        add(event, "tooldur_guard_melee", ToolDurabilityGameTests::guardMelee);
        add(event, "tooldur_guard_shield", ToolDurabilityGameTests::guardShield);
        add(event, "tooldur_armor_wear", ToolDurabilityGameTests::armorWear);
        add(event, "tooldur_armor_research", ToolDurabilityGameTests::armorResearchOnlyReduces);
    }

    private static void add(final RegisterGameTestsEvent event, final String name, final Consumer<GameTestHelper> test)
    {
        event.registerTest(
          Identifier.fromNamespaceAndPath(Constants.MOD_ID, name),
          info -> new MinecoloniesGameTestInstance(info, test),
          MinecoloniesGameTestRegistrar.isolatedColonyData(event, name));
    }

    // ------------------------------------------------------------------ fixtures

    /**
     * A connected player in survival mode with the abilities of one: the mock player the other tests use takes the default game mode of the GameTest server
     * (creative) when it joins, and a creative player's tools do not wear.
     */
    private static ServerPlayer makeConnectedSurvivalPlayer(final GameTestHelper helper)
    {
        final ServerPlayer player = MinecoloniesGameTests.makeConnectedSurvivalPlayer(helper);
        player.gameMode.changeGameModeForPlayer(GameType.SURVIVAL);
        helper.assertTrue(!player.hasInfiniteMaterials(), "fixture: the mock player still has infinite materials");
        return player;
    }

    /**
     * Founds a colony, places the hut and, 20 ticks later, runs {@code body} with a spawned citizen assigned to it (its real work AI exists).
     */
    private static void withWorker(
      final GameTestHelper helper,
      final String name,
      final Block hutBlock,
      final String blueprint,
      final BiPredicate<IBuilding, ICitizenData> assign,
      final BiConsumer<IColony, ICitizenData> body)
    {
        final ServerLevel level = helper.getLevel();
        final IColony colony = foundGameTestColony(helper, "tooldur " + name);
        final IBuilding hut = placeProductionBuilding(helper, colony, hutBlock, new BlockPos(20, 1, 2), blueprint);
        final BlockPos anchor = helper.absolutePos(new BlockPos(22, 1, 10));
        level.setChunkForced(anchor.getX() >> 4, anchor.getZ() >> 4, true);
        helper.runAfterDelay(20, () -> {
            final ICitizenData worker = colony.getCitizenManager().spawnOrCreateCivilian(null, level, List.of(anchor), true);
            helper.assertTrue(worker != null && worker.getEntity().isPresent(), name + " citizen did not spawn");
            helper.assertTrue(assign.test(hut, worker), name + " assignment failed");
            worker.getEntity().get().setPos(anchor.getX() + 0.5D, anchor.getY(), anchor.getZ() + 0.5D);
            body.accept(colony, worker);
        });
    }

    /**
     * The generic fixture of the tests of the utility: a sawmill worker (any citizen with a colony does).
     */
    private static void withCitizen(final GameTestHelper helper, final String name, final BiConsumer<IColony, AbstractEntityCitizen> body)
    {
        withWorker(helper, name, ModBlocks.blockHutSawmill, "craftsmanship/carpentry/sawmill1.blueprint",
          (hut, citizen) -> hut.getModule(BuildingModules.SAWMILL_WORK).assignCitizen(citizen),
          (colony, worker) -> body.accept(colony, worker.getEntity().get()));
    }

    /** Where the blocks of the tests are mined, and the spot a worker mines them from. */
    private static BlockPos minePos(final GameTestHelper helper)
    {
        return helper.absolutePos(new BlockPos(12, 1, 20));
    }

    private static int wearOf(final ItemStack stack, final ItemStack original)
    {
        return stack.isEmpty() ? original.getMaxDamage() : stack.getDamageValue();
    }

    /**
     * The wear a citizen's tool takes from breaking the block {@code n} times the way the work AIs break it ({@code breakBlockWithToolInHand}).
     */
    private static int citizenBreaks(final AbstractEntityCitizen citizen, final ServerLevel level, final ItemStack tool, final BlockState state, final int n, final BlockPos pos)
    {
        final InventoryCitizen inventory = citizen.getInventoryCitizen();
        inventory.setStackInSlot(0, tool.copy());
        CitizenItemUtils.setMainHeldItem(citizen, 0);
        level.setBlock(pos.below(), Blocks.GRASS_BLOCK.defaultBlockState(), 2);
        for (int i = 0; i < n; i++)
        {
            level.setBlock(pos, state, 2);
            CitizenItemUtils.breakBlockWithToolInHand(citizen, pos);
        }
        final int wear = wearOf(inventory.getStackInSlot(0), tool);
        inventory.setStackInSlot(0, ItemStack.EMPTY);
        CitizenItemUtils.removeHeldItem(citizen);
        return wear;
    }

    /**
     * The wear the same tool takes in the hand of a player who breaks the block {@code n} times (vanilla: {@code ItemStack#mineBlock} from {@code ServerPlayerGameMode#destroyBlock}).
     */
    private static int playerBreaks(final ServerPlayer player, final ServerLevel level, final ItemStack tool, final BlockState state, final int n, final BlockPos pos)
    {
        player.setItemInHand(InteractionHand.MAIN_HAND, tool.copy());
        level.setBlock(pos.below(), Blocks.GRASS_BLOCK.defaultBlockState(), 2);
        for (int i = 0; i < n; i++)
        {
            level.setBlock(pos, state, 2);
            // exactly the call that wears the tool in ServerPlayerGameMode#destroyBlock (line "itemStack.mineBlock(level, state, pos, player)"). destroyBlock
            // itself also asks the block break event, which the colony protection of any colony nearby in the shared test world may cancel
            player.getMainHandItem().mineBlock(level, state, pos, player);
            level.setBlock(pos, Blocks.AIR.defaultBlockState(), 2);
        }
        final int wear = wearOf(player.getMainHandItem(), tool);
        player.setItemInHand(InteractionHand.MAIN_HAND, ItemStack.EMPTY);
        return wear;
    }

    // ------------------------------------------------------------------ the utility against the player

    private record Case(String label, ItemStack tool, BlockState state, int n, int expected)
    {
    }

    /**
     * The vanilla rules, case by case, with the player's code as the reference: a mined block costs the tool's damage per block (1, a sword 2), and
     * only when the tool is a tool and the block has a destroy speed above 0 (shears: any block but fire); an item that is no tool costs nothing.
     */
    public static void vanillaRules(final GameTestHelper helper)
    {
        withCitizen(helper, "rules", (colony, citizen) -> {
            final ServerLevel level = helper.getLevel();
            final ServerPlayer player = makeConnectedSurvivalPlayer(helper);
            final BlockPos pos = minePos(helper);
            final List<Case> cases = List.of(
              new Case("iron pickaxe on stone", new ItemStack(Items.IRON_PICKAXE), Blocks.STONE.defaultBlockState(), 30, 30),
              new Case("iron pickaxe on a poppy (instant break)", new ItemStack(Items.IRON_PICKAXE), Blocks.POPPY.defaultBlockState(), 30, 0),
              new Case("iron pickaxe on short grass (instant break)", new ItemStack(Items.IRON_PICKAXE), Blocks.SHORT_GRASS.defaultBlockState(), 30, 0),
              new Case("iron axe on a log", new ItemStack(Items.IRON_AXE), Blocks.OAK_LOG.defaultBlockState(), 30, 30),
              new Case("iron shovel on dirt", new ItemStack(Items.IRON_SHOVEL), Blocks.DIRT.defaultBlockState(), 30, 30),
              new Case("iron hoe on dirt", new ItemStack(Items.IRON_HOE), Blocks.DIRT.defaultBlockState(), 30, 30),
              new Case("iron sword on stone (a sword costs 2 per block)", new ItemStack(Items.IRON_SWORD), Blocks.STONE.defaultBlockState(), 10, 20),
              new Case("shears on short grass (shears wear on every block)", new ItemStack(Items.SHEARS), Blocks.SHORT_GRASS.defaultBlockState(), 30, 30),
              new Case("fishing rod on stone (no tool: no wear)", new ItemStack(Items.FISHING_ROD), Blocks.STONE.defaultBlockState(), 30, 0),
              new Case("bow on stone (no tool: no wear)", new ItemStack(Items.BOW), Blocks.STONE.defaultBlockState(), 30, 0));
            final List<String> failures = new ArrayList<>();
            for (final Case c : cases)
            {
                final int citizenWear = citizenBreaks(citizen, level, c.tool(), c.state(), c.n(), pos);
                final int playerWear = playerBreaks(player, level, c.tool(), c.state(), c.n(), pos);
                Log.getLogger().info("[tooldur_vanilla_rules] {}: citizen {} player {} expected {}", c.label(), citizenWear, playerWear, c.expected());
                if (playerWear != c.expected())
                {
                    failures.add("fixture: " + c.label() + ": the player's tool wore " + playerWear + ", expected " + c.expected());
                }
                if (citizenWear != playerWear)
                {
                    failures.add(c.label() + ": the citizen's tool wore " + citizenWear + ", the player's " + playerWear);
                }
            }

            // damageItemInHand (guards, herders, farmer, fisherman ...) wears by exactly the amount asked, like hurtAndBreak(amount)
            citizen.getInventoryCitizen().setStackInSlot(0, new ItemStack(Items.IRON_SWORD));
            CitizenItemUtils.setMainHeldItem(citizen, 0);
            CitizenItemUtils.damageItemInHand(citizen, InteractionHand.MAIN_HAND, 7);
            final int hand = citizen.getInventoryCitizen().getStackInSlot(0).getDamageValue();
            if (hand != 7)
            {
                failures.add("damageItemInHand(7) wore " + hand);
            }
            helper.assertTrue(failures.isEmpty(), "citizen tool wear differs from the player's: " + failures);
            helper.succeed();
        });
    }

    /**
     * Unbreaking works on a citizen's tool like on a player's: Unbreaking III keeps 3 of 4 uses.
     */
    public static void unbreaking(final GameTestHelper helper)
    {
        withCitizen(helper, "unbreaking", (colony, citizen) -> {
            final ServerLevel level = helper.getLevel();
            final ServerPlayer player = makeConnectedSurvivalPlayer(helper);
            final ItemStack pickaxe = new ItemStack(Items.NETHERITE_PICKAXE);
            pickaxe.enchant(Utils.getRegistryValue(Enchantments.UNBREAKING, level), 3);
            final int n = 400;
            final int citizenWear = citizenBreaks(citizen, level, pickaxe, Blocks.STONE.defaultBlockState(), n, minePos(helper));
            final int playerWear = playerBreaks(player, level, pickaxe, Blocks.STONE.defaultBlockState(), n, minePos(helper));
            Log.getLogger().info("[tooldur_unbreaking] {} stone with Unbreaking III: citizen {} player {}", n, citizenWear, playerWear);
            // 1 in 4 uses costs durability: 100 expected, 8.7 standard deviation
            helper.assertTrue(playerWear > n * 0.15D && playerWear < n * 0.35D, "fixture: the player lost " + playerWear + " of " + n);
            helper.assertTrue(citizenWear > n * 0.15D && citizenWear < n * 0.35D, "Unbreaking III on a citizen's pickaxe: lost " + citizenWear + " of " + n + " uses, expected about " + n / 4);
            helper.succeed();
        });
    }

    /**
     * The research only reduces the wear: without it a citizen's pickaxe loses exactly one per block, with the strongest level (0.9) it loses about 1 / 1.9 of it
     * and never more than a block's worth.
     */
    public static void researchOnlyReduces(final GameTestHelper helper)
    {
        withCitizen(helper, "research", (colony, citizen) -> {
            final ServerLevel level = helper.getLevel();
            final ItemStack pickaxe = new ItemStack(Items.DIAMOND_PICKAXE);
            final BlockPos pos = minePos(helper);
            final int n = 300;
            final int plain = citizenBreaks(citizen, level, pickaxe, Blocks.STONE.defaultBlockState(), n, pos);
            helper.assertTrue(plain == n, "without research a citizen's pickaxe wears " + plain + " for " + n + " blocks");

            colony.getResearchManager().getResearchEffects().applyEffect(new GlobalResearchEffect(ResearchConstants.TOOL_DURABILITY, "tooldur", "tooldur", 0.9D, 0.9D));
            helper.assertTrue(colony.getResearchManager().getResearchEffects().getEffectStrength(ResearchConstants.TOOL_DURABILITY) == 0.9D, "fixture: the research effect is not applied");
            final int researched = citizenBreaks(citizen, level, pickaxe, Blocks.STONE.defaultBlockState(), n, pos);
            Log.getLogger().info("[tooldur_research_only_reduces] {} stone: without research {}, with +90% {}", n, plain, researched);
            // each use costs durability with the chance 1 / 1.9 = 0.526: 158 expected, 8.6 standard deviation
            helper.assertTrue(researched > n * 0.40D && researched < n * 0.65D, "research +90%: the pickaxe lost " + researched + " of " + n + ", expected about " + (int) (n / 1.9D));
            helper.assertTrue(researched < plain, "the research did not reduce the wear");

            // instant-break blocks stay free with the research, damageItemInHand never wears more than asked
            final int free = citizenBreaks(citizen, level, pickaxe, Blocks.POPPY.defaultBlockState(), 50, pos);
            helper.assertTrue(free == 0, "an instant-break block wore a researched pickaxe by " + free);
            citizen.getInventoryCitizen().setStackInSlot(0, new ItemStack(Items.DIAMOND_SWORD));
            CitizenItemUtils.setMainHeldItem(citizen, 0);
            for (int i = 0; i < 100; i++)
            {
                final int before = citizen.getInventoryCitizen().getStackInSlot(0).getDamageValue();
                CitizenItemUtils.damageItemInHand(citizen, InteractionHand.MAIN_HAND, 3);
                final int cost = citizen.getInventoryCitizen().getStackInSlot(0).getDamageValue() - before;
                helper.assertTrue(cost == 0 || cost == 3, "damageItemInHand(3) with research cost " + cost);
            }
            helper.succeed();
        });
    }

    /**
     * A tool of a mod: the item's own {@code damageItem} hook runs once per wear (not twice, as it did when the citizen's code asked for it before
     * ItemStack#hurtAndBreak did), and its damage per block is what a block costs, like for a player.
     */
    public static void itemHooksRunOnce(final GameTestHelper helper)
    {
        withCitizen(helper, "hooks", (colony, citizen) -> {
            final ServerLevel level = helper.getLevel();
            final ServerPlayer player = makeConnectedSurvivalPlayer(helper);
            final ProbeTool probe = PROBE.get();
            probe.damageHooks = 0;
            probe.mineCalls = 0;
            final ItemStack tool = new ItemStack(probe);
            final BlockPos pos = minePos(helper);

            final int citizenWear = citizenBreaks(citizen, level, tool, Blocks.STONE.defaultBlockState(), 10, pos);
            final int citizenHooks = probe.damageHooks;
            final int citizenMined = probe.mineCalls;
            probe.damageHooks = 0;
            probe.mineCalls = 0;
            final int playerWear = playerBreaks(player, level, tool, Blocks.STONE.defaultBlockState(), 10, pos);
            Log.getLogger().info("[tooldur_item_hooks_once] 10 stone: citizen wear {} hooks {} mineBlock {}, player wear {} hooks {} mineBlock {}",
              citizenWear, citizenHooks, citizenMined, playerWear, probe.damageHooks, probe.mineCalls);
            helper.assertTrue(playerWear == 10 * ProbeTool.DAMAGE_PER_BLOCK, "fixture: the player's probe tool wore " + playerWear);
            helper.assertTrue(citizenWear == playerWear, "the citizen's mod tool wore " + citizenWear + ", the player's " + playerWear);
            helper.assertTrue(citizenHooks == probe.damageHooks, "the damageItem hook ran " + citizenHooks + " times for a citizen, " + probe.damageHooks + " for a player");
            helper.assertTrue(citizenMined == probe.mineCalls && citizenMined == 10, "Item#mineBlock ran " + citizenMined + " times for a citizen, " + probe.mineCalls + " for a player");

            // the inventory API the guards and herders use: one hook run per wear
            probe.damageHooks = 0;
            citizen.getInventoryCitizen().setStackInSlot(0, new ItemStack(probe));
            CitizenItemUtils.setMainHeldItem(citizen, 0);
            CitizenItemUtils.damageItemInHand(citizen, InteractionHand.MAIN_HAND, 5);
            final int direct = citizen.getInventoryCitizen().getStackInSlot(0).getDamageValue();
            helper.assertTrue(direct == 5 && probe.damageHooks == 1, "damageItemInHand(5) on a mod tool wore " + direct + " and ran the hook " + probe.damageHooks + " times");
            citizen.getInventoryCitizen().setStackInSlot(0, ItemStack.EMPTY);
            CitizenItemUtils.removeHeldItem(citizen);
            helper.succeed();
        });
    }

    /**
     * A tool of a mod that counts the calls of its hooks and costs {@link #DAMAGE_PER_BLOCK} per mined block.
     */
    private static final class ProbeTool extends Item
    {
        static final int DAMAGE_PER_BLOCK = 3;

        int damageHooks;
        int mineCalls;

        ProbeTool()
        {
            super(new Item.Properties()
                    .setId(ResourceKey.create(Registries.ITEM, Identifier.fromNamespaceAndPath(Constants.MOD_ID, "tooldur_probe")))
                    .durability(1000)
                    .component(DataComponents.TOOL, new Tool(List.of(), 1.0F, DAMAGE_PER_BLOCK, true)));
        }

        @Override
        public <T extends LivingEntity> int damageItem(final ItemStack stack, final int amount, @Nullable final T entity, final Consumer<ItemStack> onBroken)
        {
            damageHooks++;
            return amount;
        }

        @Override
        public boolean mineBlock(final ItemStack stack, final net.minecraft.world.level.Level level, final BlockState state, final BlockPos pos, final LivingEntity owner)
        {
            mineCalls++;
            return super.mineBlock(stack, level, state, pos, owner);
        }
    }

    // ------------------------------------------------------------------ the work AIs

    private static AbstractEntityAIBasic<?, ?> workAi(final ICitizenData worker)
    {
        final Object ai = worker.getEntity().map(e -> (Object) e.getCitizenJobHandler().getWorkAI()).orElse(null);
        return ai instanceof AbstractEntityAIBasic<?, ?> basic ? basic : null;
    }

    /**
     * The shared block-mining step of every work AI (AbstractEntityAIInteract#mineBlock: builder, miner, quarrier, lumberjack, farmer ...), called
     * on the real AI of the given worker: the tool loses what the same tool loses in a player's hand for the same blocks.
     */
    private static void aiMine(
      final GameTestHelper helper,
      final String name,
      final Block hutBlock,
      final String blueprint,
      final BiPredicate<IBuilding, ICitizenData> assign,
      final Item toolItem,
      final BlockState state)
    {
        withWorker(helper, name, hutBlock, blueprint, assign, (colony, worker) -> {
            final ServerLevel level = helper.getLevel();
            final AbstractEntityCitizen citizen = worker.getEntity().get();
            final AbstractEntityAIBasic<?, ?> ai = workAi(worker);
            helper.assertTrue(ai instanceof AbstractEntityAIInteract<?, ?>, name + " worker has no interacting AI: " + ai);
            final ServerPlayer player = makeConnectedSurvivalPlayer(helper);
            final int n = 20;
            final BlockPos pos = minePos(helper);
            level.setBlock(pos.below(), Blocks.GRASS_BLOCK.defaultBlockState(), 2);
            citizen.getInventoryCitizen().setStackInSlot(0, new ItemStack(toolItem));
            final Method mine;
            try
            {
                mine = AbstractEntityAIInteract.class.getDeclaredMethod("mineBlock", BlockPos.class, BlockPos.class);
                mine.setAccessible(true);
                for (int i = 0; i < n; i++)
                {
                    level.setBlock(pos, state, 2);
                    // the first call sets the mining delay and waits, the second one mines (see AbstractEntityAIBasic#hasNotDelayed)
                    final boolean first = (Boolean) mine.invoke(ai, pos, citizen.blockPosition());
                    final boolean second = (Boolean) mine.invoke(ai, pos, citizen.blockPosition());
                    helper.assertTrue(!first && second, name + " block " + i + ": mineBlock returned " + first + " then " + second + " (miningWait)");
                    helper.assertTrue(level.getBlockState(pos).isAir(), name + " block " + i + " was not mined");
                }
            }
            catch (final ReflectiveOperationException e)
            {
                throw new IllegalStateException(e);
            }
            final int aiWear = citizen.getInventoryCitizen().getStackInSlot(0).getDamageValue();
            final int playerWear = playerBreaks(player, level, new ItemStack(toolItem), state, n, pos);
            Log.getLogger().info("[tooldur_ai_{}_mine] {} blocks with {}: the AI's tool wore {}, the player's {}", name, n, toolItem, aiWear, playerWear);
            helper.assertTrue(playerWear == n, "fixture: the player's tool wore " + playerWear + " for " + n + " blocks");
            helper.assertTrue(aiWear == playerWear, name + " AI: " + toolItem + " wore " + aiWear + " for " + n + " blocks, a player's wears " + playerWear);
            helper.succeed();
        });
    }

    /**
     * A real builder clears stone (the clear stage of a work order: real AI, real pathing) and loses exactly one use of its pickaxe per block it mines, as a
     * player does, at every setting of the worker speeds (the speeds only shorten waits, they must not multiply hits or wear).
     */
    private static void builderClearsStone(final GameTestHelper helper, final String name, @Nullable final Double speed, @Nullable final Double builderSpeed, @Nullable final Boolean fastClear)
    {
        WorkerSettings.overrideForTests(speed, builderSpeed, null, null, null, fastClear);
        WorkerEfficiencyGameTests.scene(helper, "tooldur builder " + name, live -> {
            live.setSkills(40, 40);
            final int[] pickaxes = new int[2];
            for (int i = 0; i < 2; i++)
            {
                pickaxes[i] = i;
                helper.assertTrue(com.minecolonies.api.util.InventoryUtils.addItemStackToItemHandler(live.citizen.getInventory(), new ItemStack(Items.WOODEN_PICKAXE)), "no room for the pickaxe");
            }
            live.stock(WorkerEfficiencyGameTests.BUILD_BLOCK.asItem(), live.blocksInBox() + WorkerEfficiencyGameTests.SPARE);
            final BlockPos rel = WorkerEfficiencyGameTests.SITE;
            // 4 x 4 columns of stone, two layers deep, in the footprint: 32 blocks the clear stage has to mine with the pickaxe
            for (int x = 0; x < 4; x++)
            {
                for (int z = 0; z < 4; z++)
                {
                    helper.setBlock(rel.offset(x, 0, z), Blocks.STONE.defaultBlockState());
                    helper.setBlock(rel.offset(x, 1, z), Blocks.STONE.defaultBlockState());
                }
            }
            final WorkOrderDecoration order = live.order("tooldur_" + name, rel);
            final BlockPos origin = order.getLocation();
            final int stone = 32;
            final int[] cleared = {0};
            // the box is built when the order is gone; the builder clears the stone where the box stands (23 of the 32 blocks: floor and walls), the
            // stone in the hollow inside the walls is none of his business
            WorkerEfficiencyGameTests.everyTick(live, 30_000, tick -> {
                int left = 0;
                for (int y = 0; y < 2; y++)
                {
                    for (int x = 0; x < 4; x++)
                    {
                        for (int z = 0; z < 4; z++)
                        {
                            if (live.level.getBlockState(origin.offset(x, y, z)).is(Blocks.STONE))
                            {
                                left++;
                            }
                        }
                    }
                }
                cleared[0] = stone - left;
                return live.colony.getWorkManager().getWorkOrder(order.getID()) == null && !live.hut.hasWorkOrder();
            }, () -> {
                int wear = 0;
                int found = 0;
                final InventoryCitizen inventory = live.citizen.getInventory();
                for (int slot = 0; slot < inventory.getSlots(); slot++)
                {
                    final ItemStack stack = inventory.getStackInSlot(slot);
                    if (stack.is(Items.WOODEN_PICKAXE))
                    {
                        found++;
                        wear += stack.getDamageValue();
                    }
                }
                Log.getLogger().info("[tooldur_builder_{}] cleared {} stone, {} pickaxes wore {} in total", name, cleared[0], found, wear);
                WorkerSettings.clearOverrides();
                BuilderCollab.overrideForTests(null, null, null, null, null, null, null);
                // (a builder hands a spare pickaxe back to the hut: what counts is that none broke and the one in use lost one use per block)
                helper.assertTrue(found >= 1, "the wooden pickaxes broke or went missing: " + found + " left, wear " + wear);
                helper.assertTrue(cleared[0] >= 23, "the builder cleared only " + cleared[0] + " stone blocks");
                helper.assertTrue(wear == cleared[0], "the builder mined " + cleared[0] + " stone blocks and its pickaxes lost " + wear + " uses, a player's lose " + cleared[0]);
                helper.succeed();
            }, () -> {
                WorkerSettings.clearOverrides();
                return "cleared " + cleared[0] + " of " + stone + " stone, " + live.ai();
            });
        });
    }

    // ------------------------------------------------------------------ guards

    @SuppressWarnings("unchecked")
    private static <E extends Mob> E spawnTestMob(final ServerLevel level, final EntityType<E> type, final BlockPos pos)
    {
        final E mob = type.create(level, EntitySpawnReason.COMMAND);
        mob.snapTo(pos.getX() + 0.5D, pos.getY(), pos.getZ() + 0.5D, 0, 0);
        mob.setNoAi(true);
        mob.setPersistenceRequired();
        level.addFreshEntity(mob);
        return mob;
    }

    /**
     * The knight's real melee attack ({@code MeleeCombatAI#doAttack}) wears the sword like an attack of a player does: one use. (With an axe, the huscarl's weapon, a
     * player loses 2 per attack, the citizen 1: it never wears more than the player.)
     */
    public static void guardMelee(final GameTestHelper helper)
    {
        withWorker(helper, "guard", ModBlocks.blockHutGuardTower, "military/guardtower1.blueprint",
          (hut, citizen) -> hut.getModule(BuildingModules.KNIGHT_TOWER_WORK).assignCitizen(citizen),
          (colony, worker) -> {
              final ServerLevel level = helper.getLevel();
              final EntityCitizen guard = (EntityCitizen) worker.getEntity().get();
              helper.assertTrue(guard.getCitizenJobHandler().getWorkAI() instanceof AbstractEntityAIGuard<?, ?>, "knight has no guard AI: " + guard.getCitizenJobHandler().getWorkAI());
              final MeleeCombatAI combat = new MeleeCombatAI(
                guard,
                new com.minecolonies.api.entity.ai.statemachine.tickratestatemachine.TickRateStateMachine<>(com.minecolonies.api.entity.ai.combat.CombatAIStates.NO_TARGET, e -> { throw e; }),
                (AbstractEntityAIGuard<?, ?>) guard.getCitizenJobHandler().getWorkAI());
              final Method attack;
              try
              {
                  attack = MeleeCombatAI.class.getDeclaredMethod("doAttack", LivingEntity.class);
                  attack.setAccessible(true);
              }
              catch (final ReflectiveOperationException e)
              {
                  throw new IllegalStateException(e);
              }
              final ServerPlayer player = makeConnectedSurvivalPlayer(helper);
              final BlockPos anchor = guard.blockPosition();
              final List<String> failures = new ArrayList<>();
              for (final Item weapon : List.of(Items.IRON_SWORD, Items.DIAMOND_SWORD, Items.IRON_AXE))
              {
                  final int perAttack = new ItemStack(weapon).get(DataComponents.WEAPON) instanceof final Weapon w ? w.itemDamagePerAttack() : 0;
                  final int attacks = 5;
                  guard.getInventoryCitizen().setStackInSlot(0, new ItemStack(weapon));
                  CitizenItemUtils.setMainHeldItem(guard, 0);
                  player.setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(weapon));
                  for (int i = 0; i < attacks; i++)
                  {
                      final var zombie = spawnTestMob(level, EntityTypes.ZOMBIE, anchor.offset(1, 0, 0));
                      try
                      {
                          attack.invoke(combat, zombie);
                      }
                      catch (final ReflectiveOperationException e)
                      {
                          throw new IllegalStateException(e);
                      }
                      zombie.discard();
                      final var target = spawnTestMob(level, EntityTypes.ZOMBIE, anchor.offset(0, 0, 3));
                      target.setHealth(target.getMaxHealth());
                      player.resetAttackStrengthTicker();
                      player.setPos(target.getX() - 1.0D, target.getY(), target.getZ());
                      player.attack(target);
                      target.discard();
                  }
                  final int guardWear = guard.getInventoryCitizen().getStackInSlot(0).getDamageValue();
                  final int playerWear = player.getMainHandItem().getDamageValue();
                  Log.getLogger().info("[tooldur_guard_melee] {} x{}: guard wore {}, player {} (weapon component: {} per attack)", weapon, attacks, guardWear, playerWear, perAttack);
                  if (playerWear != attacks * perAttack)
                  {
                      failures.add("fixture: " + weapon + ": the player's attacks wore " + playerWear + ", the weapon component says " + attacks * perAttack);
                  }
                  if (weapon == Items.IRON_AXE)
                  {
                      if (guardWear > playerWear)
                      {
                          failures.add(weapon + ": the guard's weapon wore " + guardWear + ", the player's " + playerWear);
                      }
                  }
                  else if (guardWear != playerWear)
                  {
                      failures.add(weapon + ": the guard's weapon wore " + guardWear + " in " + attacks + " attacks, the player's " + playerWear);
                  }
              }
              guard.getInventoryCitizen().setStackInSlot(0, ItemStack.EMPTY);
              CitizenItemUtils.removeHeldItem(guard);
              helper.assertTrue(failures.isEmpty(), "guard melee wear differs from the player's: " + failures);
              helper.succeed();
          });
    }

    /** The counter of the ticks an item has been in use (LivingEntity#useItemRemaining); the mock player is not ticked, so a blocking shield is set up by hand. */
    private static void raiseShield(final ServerPlayer player)
    {
        player.setItemInHand(InteractionHand.OFF_HAND, new ItemStack(Items.SHIELD));
        player.startUsingItem(InteractionHand.OFF_HAND);
        try
        {
            final java.lang.reflect.Field remaining = LivingEntity.class.getDeclaredField("useItemRemaining");
            remaining.setAccessible(true);
            remaining.setInt(player, player.getUseItem().getUseDuration(player) - 20);
        }
        catch (final ReflectiveOperationException e)
        {
            throw new IllegalStateException(e);
        }
    }

    /**
     * A shield wears for a blocked hit exactly as a player's shield does: 1 + floor(blocked damage) from 3 blocked damage up, nothing below (BlocksAttacks item damage
     * function). The guard and a connected survival player, both with a raised shield, take the same hits from a zombie in front of them; the shields end with the same wear
     * after every hit (hits up to the 20% of the guard's health that the colony lets through at once).
     */
    public static void guardShield(final GameTestHelper helper)
    {
        withWorker(helper, "shield", ModBlocks.blockHutGuardTower, "military/guardtower1.blueprint",
          (hut, citizen) -> hut.getModule(BuildingModules.KNIGHT_TOWER_WORK).assignCitizen(citizen),
          (colony, worker) -> {
              final ServerLevel level = helper.getLevel();
              final EntityCitizen guard = (EntityCitizen) worker.getEntity().get();
              final ServerPlayer player = makeConnectedSurvivalPlayer(helper);
              final float cap = guard.getMaxHealth() * 0.2F;
              final float[] hits = {2.0F, 3.0F, cap, cap, 3.0F, 2.0F};
              final int[] done = {0};
              final int[] raised = {0};
              final int[] last = {0, 0};
              final List<String> failures = new ArrayList<>();
              final Runnable[] next = new Runnable[1];
              next[0] = () -> {
                  if (done[0] == 0)
                  {
                      helper.assertTrue(player.isBlocking(), "fixture: the player's shield is not up (using " + player.isUsingItem() + ", ticks " + player.getTicksUsingItem() + ")");
                  }
                  if (done[0] == hits.length)
                  {
                      guard.stopUsingItem();
                      guard.getInventoryCitizen().setStackInSlot(1, ItemStack.EMPTY);
                      helper.assertTrue(failures.isEmpty(), "a guard's shield wears differently from a player's: " + failures);
                      helper.succeed();
                      return;
                  }
                  // the guard's own AI may lower its shield between the hits: raise it again and let the block delay pass
                  if (!guard.isBlocking())
                  {
                      helper.assertTrue(++raised[0] <= 20, "fixture: the guard keeps lowering its shield");
                      CitizenItemUtils.setHeldItem(guard, InteractionHand.OFF_HAND, 1);
                      guard.setYRot(0.0F);
                      guard.setYHeadRot(0.0F);
                      guard.startUsingItem(InteractionHand.OFF_HAND);
                      helper.runAfterDelay(7, () -> next[0].run());
                      return;
                  }
                  final float hit = hits[done[0]];
                  // the guard turns away from a hard hit (its flight reaction): face the attacker again
                  guard.setYRot(0.0F);
                  guard.setYHeadRot(0.0F);
                  guard.setHealth(guard.getMaxHealth());
                  player.setHealth(player.getMaxHealth());
                  // attackers two blocks in front (south) of each
                  final var onGuard = spawnTestMob(level, EntityTypes.ZOMBIE, guard.blockPosition().offset(0, 0, 2));
                  final var onPlayer = spawnTestMob(level, EntityTypes.ZOMBIE, player.blockPosition().offset(0, 0, 2));
                  guard.hurtServer(level, level.damageSources().mobAttack(onGuard), hit);
                  player.hurtServer(level, level.damageSources().mobAttack(onPlayer), hit);
                  onGuard.discard();
                  onPlayer.discard();
                  final int guardWear = guard.getInventoryCitizen().getStackInSlot(1).getDamageValue() - last[0];
                  final int playerWear = player.getOffhandItem().getDamageValue() - last[1];
                  last[0] += guardWear;
                  last[1] += playerWear;
                  Log.getLogger().info("[tooldur_guard_shield] a hit of {}: the guard's shield wore {}, the player's {} (guard health {}, yaw {}, using {})", hit, guardWear, playerWear, guard.getHealth(), guard.getYHeadRot(), guard.isUsingItem());
                  if (hit >= 3.0F && playerWear <= 0)
                  {
                      failures.add("fixture: the player's shield did not wear for a blocked hit of " + hit);
                  }
                  if (guardWear != playerWear)
                  {
                      failures.add("hit " + hit + ": the guard's shield wore " + guardWear + ", the player's " + playerWear);
                  }
                  done[0]++;
                  // the damage cooldown of both has to pass before the next hit counts
                  helper.runAfterDelay(25, () -> next[0].run());
              };
              // a mock player is hurt only once its connection counts as loaded (about 60 ticks); then the shields go up and the block delay (5 ticks) passes
              helper.runAfterDelay(70, () -> {
                  guard.getInventoryCitizen().setStackInSlot(1, new ItemStack(Items.SHIELD));
                  CitizenItemUtils.setHeldItem(guard, InteractionHand.OFF_HAND, 1);
                  guard.setYRot(0.0F);
                  guard.setYHeadRot(0.0F);
                  guard.startUsingItem(InteractionHand.OFF_HAND);
                  final BlockPos at = guard.blockPosition();
                  player.setPos(at.getX() + 4.5D, at.getY(), at.getZ() + 0.5D);
                  player.setYRot(0.0F);
                  player.setYHeadRot(0.0F);
                  raiseShield(player);
                  helper.runAfterDelay(12, () -> next[0].run());
              });
          });
    }

    /**
     * Armor wears for a hit as a player's armor does (LivingEntity#getDamageAfterArmorAbsorb): damage that bypasses armor (falling, drowning, magic, starving) does
     * not wear it at all, other damage wears every piece by max(1, damage / 4). A guard and a connected survival player with the same iron armor take the same hits.
     * With the colony research for the durability of armor at its top level the wear can only shrink.
     */
    public static void armorWear(final GameTestHelper helper)
    {
        withWorker(helper, "armor", ModBlocks.blockHutGuardTower, "military/guardtower1.blueprint",
          (hut, citizen) -> hut.getModule(BuildingModules.KNIGHT_TOWER_WORK).assignCitizen(citizen),
          (colony, worker) -> {
              final ServerLevel level = helper.getLevel();
              final EntityCitizen guard = (EntityCitizen) worker.getEntity().get();
              final ServerPlayer player = makeConnectedSurvivalPlayer(helper);
              final net.minecraft.world.entity.EquipmentSlot[] slots = {net.minecraft.world.entity.EquipmentSlot.HEAD, net.minecraft.world.entity.EquipmentSlot.CHEST,
                net.minecraft.world.entity.EquipmentSlot.LEGS, net.minecraft.world.entity.EquipmentSlot.FEET};
              final Item[] pieces = {Items.IRON_HELMET, Items.IRON_CHESTPLATE, Items.IRON_LEGGINGS, Items.IRON_BOOTS};
              for (int i = 0; i < slots.length; i++)
              {
                  guard.getInventoryCitizen().forceArmorStackToSlot(slots[i], new ItemStack(pieces[i]));
                  player.setItemSlot(slots[i], new ItemStack(pieces[i]));
              }
              final var zombie = spawnTestMob(level, EntityTypes.ZOMBIE, guard.blockPosition().offset(0, 0, 3));
              final float hit = 4.0F;
              final var sources = List.of(
                new Object[] {"mob attack", level.damageSources().mobAttack(zombie), true},
                new Object[] {"cactus", level.damageSources().cactus(), true},
                new Object[] {"fall", level.damageSources().fall(), false},
                new Object[] {"drowning", level.damageSources().drown(), false},
                new Object[] {"magic (poison, potions)", level.damageSources().magic(), false},
                new Object[] {"starvation", level.damageSources().starve(), false},
                new Object[] {"mob attack", level.damageSources().mobAttack(zombie), true});
              final int[] done = {0};
              final int[] last = {0, 0};
              final List<String> failures = new ArrayList<>();
              final Runnable[] next = new Runnable[1];
              next[0] = () -> {
                  if (done[0] == sources.size())
                  {
                      zombie.discard();
                      helper.assertTrue(failures.isEmpty(), "a guard's armor wears differently from a player's: " + failures);
                      helper.succeed();
                      return;
                  }
                  final Object[] entry = sources.get(done[0]);
                  guard.setHealth(guard.getMaxHealth());
                  player.setHealth(player.getMaxHealth());
                  guard.hurtServer(level, (net.minecraft.world.damagesource.DamageSource) entry[1], hit);
                  player.hurtServer(level, (net.minecraft.world.damagesource.DamageSource) entry[1], hit);
                  int guardTotal = 0;
                  int playerTotal = 0;
                  for (int i = 0; i < slots.length; i++)
                  {
                      guardTotal += guard.getInventoryCitizen().getArmorInSlot(slots[i]).getDamageValue();
                      playerTotal += player.getItemBySlot(slots[i]).getDamageValue();
                  }
                  final int guardWear = guardTotal - last[0];
                  final int playerWear = playerTotal - last[1];
                  last[0] = guardTotal;
                  last[1] = playerTotal;
                  Log.getLogger().info("[tooldur_armor_wear] {} {}: guard armor wore {}, player armor {}", entry[0], hit, guardWear, playerWear);
                  if ((boolean) entry[2] && playerWear <= 0)
                  {
                      failures.add("fixture: the player's armor did not wear for " + entry[0]);
                  }
                  if (!(boolean) entry[2] && playerWear != 0)
                  {
                      failures.add("fixture: the player's armor wore " + playerWear + " for " + entry[0]);
                  }
                  if (guardWear != playerWear)
                  {
                      failures.add(entry[0] + ": the guard's armor wore " + guardWear + ", the player's " + playerWear);
                  }
                  done[0]++;
                  helper.runAfterDelay(25, () -> next[0].run());
              };
              // (a mock player is hurt only once its connection counts as loaded, about 60 ticks)
              helper.runAfterDelay(70, () -> next[0].run());
          });
    }

    /**
     * The research for the durability of armor only saves wear: with +100% the iron armor of a guard never wears more than without, and bypassing damage still costs nothing.
     */
    public static void armorResearchOnlyReduces(final GameTestHelper helper)
    {
        withWorker(helper, "armor research", ModBlocks.blockHutGuardTower, "military/guardtower1.blueprint",
          (hut, citizen) -> hut.getModule(BuildingModules.KNIGHT_TOWER_WORK).assignCitizen(citizen),
          (colony, worker) -> {
              final EntityCitizen guard = (EntityCitizen) worker.getEntity().get();
              final ItemStack chest = new ItemStack(Items.DIAMOND_CHESTPLATE);
              guard.getInventoryCitizen().forceArmorStackToSlot(net.minecraft.world.entity.EquipmentSlot.CHEST, chest);
              final var level = helper.getLevel();
              final int hits = 200;
              for (int i = 0; i < hits; i++)
              {
                  CitizenItemUtils.updateArmorDamage(guard, level.damageSources().fall(), 8.0D);
              }
              helper.assertTrue(chest.getDamageValue() == 0, "falling wore a guard's armor by " + chest.getDamageValue());
              for (int i = 0; i < hits; i++)
              {
                  CitizenItemUtils.updateArmorDamage(guard, level.damageSources().cactus(), 4.0D);
              }
              final int plain = chest.getDamageValue();
              helper.assertTrue(plain == hits, "without research the armor wore " + plain + " in " + hits + " hits");
              colony.getResearchManager().getResearchEffects().applyEffect(
                new com.minecolonies.core.research.GlobalResearchEffect(ResearchConstants.ARMOR_DURABILITY, "geardur", "geardur", 1.0D, 1.0D));
              for (int i = 0; i < hits; i++)
              {
                  CitizenItemUtils.updateArmorDamage(guard, level.damageSources().cactus(), 4.0D);
              }
              final int researched = chest.getDamageValue() - plain;
              Log.getLogger().info("[tooldur_armor_research] {} hits: without research {}, with +100% {}", hits, plain, researched);
              // half of the hits wear: 100 expected, 7 standard deviation
              helper.assertTrue(researched > hits * 0.30D && researched < hits * 0.70D, "research +100%: the armor wore " + researched + " in " + hits + " hits, expected about " + hits / 2);
              helper.succeed();
          });
    }
}
