package com.minecolonies.api.equipment;
import org.jetbrains.annotations.Nullable;
import net.neoforged.neoforge.common.DataMapHooks;
import net.minecraft.core.Direction;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.core.Holder;
import net.minecraft.world.item.component.BlockTransformers;
import net.minecraft.core.component.BlockTransformer;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.item.Item;
import net.minecraft.tags.TagKey;
import net.minecraft.tags.BlockTags;
import net.minecraft.core.HolderSet;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.item.component.ItemAttributeModifiers;
import net.minecraft.world.item.component.Tool;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;

import com.minecolonies.api.IMinecoloniesAPI;
import com.minecolonies.api.util.Log;
import com.minecolonies.api.compatibility.Compatibility;
import com.minecolonies.api.equipment.registry.EquipmentTypeEntry;
import com.minecolonies.api.items.ModItems;
import com.minecolonies.api.util.ItemStackUtils;
import com.minecolonies.api.util.constant.Constants;
import com.minecolonies.api.util.constant.translation.ToolTranslationConstants;
import com.minecolonies.apiimp.CommonMinecoloniesAPIImpl;
import net.minecraft.core.Registry;
import net.minecraft.core.component.DataComponents;
import net.minecraft.tags.ItemTags;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.item.*;
import net.minecraft.world.item.enchantment.Repairable;
import net.neoforged.neoforge.common.ItemAbilities;
import net.neoforged.neoforge.common.ItemAbility;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredRegister;

import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;

/**
 * Class used for storing and registering any EquipmentTypes.
 */
public class ModEquipmentTypes
{
    public final static DeferredRegister<EquipmentTypeEntry> DEFERRED_REGISTER = DeferredRegister.create(CommonMinecoloniesAPIImpl.EQUIPMENT_TYPES, Constants.MOD_ID);

    public static final DeferredHolder<EquipmentTypeEntry, EquipmentTypeEntry> none;
    public static final DeferredHolder<EquipmentTypeEntry, EquipmentTypeEntry> pickaxe;
    public static final DeferredHolder<EquipmentTypeEntry, EquipmentTypeEntry> shovel;
    public static final DeferredHolder<EquipmentTypeEntry, EquipmentTypeEntry> axe;
    public static final DeferredHolder<EquipmentTypeEntry, EquipmentTypeEntry> hoe;
    public static final DeferredHolder<EquipmentTypeEntry, EquipmentTypeEntry> sword;
    public static final DeferredHolder<EquipmentTypeEntry, EquipmentTypeEntry> bow;
    public static final DeferredHolder<EquipmentTypeEntry, EquipmentTypeEntry> fishing_rod;
    public static final DeferredHolder<EquipmentTypeEntry, EquipmentTypeEntry> shears;
    public static final DeferredHolder<EquipmentTypeEntry, EquipmentTypeEntry> shield;
    public static final DeferredHolder<EquipmentTypeEntry, EquipmentTypeEntry> helmet;
    public static final DeferredHolder<EquipmentTypeEntry, EquipmentTypeEntry> leggings;
    public static final DeferredHolder<EquipmentTypeEntry, EquipmentTypeEntry> chestplate;
    public static final DeferredHolder<EquipmentTypeEntry, EquipmentTypeEntry> boots;
    public static final DeferredHolder<EquipmentTypeEntry, EquipmentTypeEntry> flint_and_steel;
    public static final DeferredHolder<EquipmentTypeEntry, EquipmentTypeEntry> lead;
    public static final DeferredHolder<EquipmentTypeEntry, EquipmentTypeEntry> spear;
    public static final DeferredHolder<EquipmentTypeEntry, EquipmentTypeEntry> crossbow;

    static
    {
        none = register("none",
          builder -> builder.setDisplayName(Component.translatable(ToolTranslationConstants.TOOL_TYPE_NONE))
                       .setIsEquipment((itemStack, equipmentType) -> true)
                       .setEquipmentLevel((itemStack, equipmentType) -> -1)
                   .build());

        pickaxe = register("pickaxe",
          builder -> builder.setDisplayName(Component.translatable(ToolTranslationConstants.TOOL_TYPE_PICKAXE))
                       .setIsEquipment((itemStack, equipmentType) -> itemStack.is(ItemTags.PICKAXES) || Compatibility.isTinkersTool(
                         itemStack,
                         equipmentType))
                       .setEquipmentLevel(ModEquipmentTypes::vanillaToolLevel)
                  .build());

        shovel = register("shovel",
          builder -> builder.setDisplayName(Component.translatable(ToolTranslationConstants.TOOL_TYPE_SHOVEL))
                       .setIsEquipment((itemStack, equipmentType) -> isToolOfKind(itemStack, ItemTags.SHOVELS, BlockTransformers.SHOVEL) || Compatibility.isTinkersTool(
                         itemStack,
                         equipmentType))
                       .setEquipmentLevel(ModEquipmentTypes::vanillaToolLevel)
                  .build());

        axe = register("axe",
          builder -> builder.setDisplayName(Component.translatable(ToolTranslationConstants.TOOL_TYPE_AXE))
                       .setIsEquipment((itemStack, equipmentType) -> isToolOfKind(itemStack, ItemTags.AXES, BlockTransformers.AXE) || Compatibility.isTinkersTool(itemStack,
                         equipmentType))
                       .setEquipmentLevel(ModEquipmentTypes::vanillaToolLevel)
                  .build());

        hoe = register("hoe",
          builder -> builder.setDisplayName(Component.translatable(ToolTranslationConstants.TOOL_TYPE_HOE))
                       .setIsEquipment((itemStack, equipmentType) -> isToolOfKind(itemStack, ItemTags.HOES, BlockTransformers.HOE) || Compatibility.isTinkersTool(itemStack,
                         equipmentType))
                       .setEquipmentLevel(ModEquipmentTypes::vanillaToolLevel)
                  .build());

        sword = register("sword",
          builder -> builder.setDisplayName(Component.translatable(ToolTranslationConstants.TOOL_TYPE_SWORD))
                       .setIsEquipment((itemStack, equipmentType) -> itemStack.is(ItemTags.SWORDS)
                                                                     || Compatibility.isTinkersWeapon(itemStack)
                                                                     || Compatibility.isCustomWeapon(itemStack))
                       .setEquipmentLevel(ModEquipmentTypes::vanillaToolLevel)
                  .build());

        bow = register("bow",
          builder -> builder.setDisplayName(Component.translatable(ToolTranslationConstants.TOOL_TYPE_BOW))
                       .setIsEquipment((itemStack, equipmentType) -> itemStack.getItem() instanceof BowItem)
                       .setEquipmentLevel((itemStack, equipmentType) -> Compatibility.getItemLevel(itemStack))
                  .build());

        crossbow = register("crossbow",
            builder -> builder.setDisplayName(Component.translatable(ToolTranslationConstants.TOOL_TYPE_CROSSBOW))
                .setIsEquipment((itemStack, equipmentType) -> itemStack.getItem() instanceof CrossbowItem)
                .setEquipmentLevel((itemStack, equipmentType) -> durabilityBasedLevel(itemStack, Items.CROSSBOW.getMaxDamage(itemStack)))
                .build());

        fishing_rod = register("rod",
          builder -> builder.setDisplayName(Component.translatable(ToolTranslationConstants.TOOL_TYPE_FISHING_ROD))
                       .setIsEquipment((itemStack, equipmentType) -> canPerformDefaultActions(itemStack, ItemAbilities.DEFAULT_FISHING_ROD_ACTIONS))
                       .setEquipmentLevel((itemStack, equipmentType) -> Compatibility.getItemLevel(itemStack))
                  .build());

        shears = register("shears",
          builder -> builder.setDisplayName(Component.translatable(ToolTranslationConstants.TOOL_TYPE_SHEARS))
                       .setIsEquipment((itemStack, equipmentType) -> canPerformDefaultActions(itemStack, ItemAbilities.DEFAULT_SHEARS_ACTIONS))
                       .setEquipmentLevel((itemStack, equipmentType) -> Compatibility.getItemLevel(itemStack))
                  .build());

        shield = register("shield",
          builder -> builder.setDisplayName(Component.translatable(ToolTranslationConstants.TOOL_TYPE_SHIELD))
                       .setIsEquipment((itemStack, equipmentType) -> itemStack.getItem() instanceof ShieldItem)
                       .setEquipmentLevel((itemStack, equipmentType) -> Compatibility.getItemLevel(itemStack))
                  .build());

        helmet = register("helmet",
          builder -> builder.setDisplayName(Component.translatable(ToolTranslationConstants.TOOL_TYPE_HELMET))
                       .setIsEquipment((itemStack, equipmentType) -> ItemStackUtils.isArmorForSlot(itemStack, EquipmentSlot.HEAD))
                       .setEquipmentLevel((itemStack, equipmentType) -> Compatibility.getItemLevel(itemStack))
                  .build());

        leggings = register("leggings",
          builder -> builder.setDisplayName(Component.translatable(ToolTranslationConstants.TOOL_TYPE_LEGGINGS))
                       .setIsEquipment((itemStack, equipmentType) -> ItemStackUtils.isArmorForSlot(itemStack, EquipmentSlot.LEGS))
                       .setEquipmentLevel((itemStack, equipmentType) -> Compatibility.getItemLevel(itemStack))
                  .build());

        chestplate = register("chestplate",
          builder -> builder.setDisplayName(Component.translatable(ToolTranslationConstants.TOOL_TYPE_CHEST_PLATE))
                       .setIsEquipment((itemStack, equipmentType) -> ItemStackUtils.isArmorForSlot(itemStack, EquipmentSlot.CHEST))
                       .setEquipmentLevel((itemStack, equipmentType) -> Compatibility.getItemLevel(itemStack))
                  .build());

        boots = register("boots",
          builder -> builder.setDisplayName(Component.translatable(ToolTranslationConstants.TOOL_TYPE_BOOTS))
                       .setIsEquipment((itemStack, equipmentType) -> ItemStackUtils.isArmorForSlot(itemStack, EquipmentSlot.FEET))
                       .setEquipmentLevel((itemStack, equipmentType) -> Compatibility.getItemLevel(itemStack))
                  .build());

        flint_and_steel = register("flintandsteel",
          builder -> builder.setDisplayName(Component.translatable(ToolTranslationConstants.TOOL_TYPE_LIGHTER))
                       .setIsEquipment((itemStack, equipmentType) -> itemStack.getItem() instanceof FlintAndSteelItem)
                       .setEquipmentLevel((itemStack, equipmentType) -> Compatibility.getItemLevel(itemStack))
                  .build());

        lead = register("lead",
          builder -> builder.setDisplayName(Component.translatable(ToolTranslationConstants.TOOL_TYPE_LEAD))
                       .setIsEquipment((itemStack, equipmentType) -> itemStack.getItem() instanceof LeadItem)
                       .setEquipmentLevel((itemStack, equipmentType) -> -1)
                  .build());

        spear = register("spear",
          builder -> builder.setDisplayName(Component.translatable(ToolTranslationConstants.TOOL_TYPE_SPEAR))
                      // MineColonies' spear plus vanilla's (1.21.11, #minecraft:spears), which level by tool material.
                      .setIsEquipment((itemStack, equipmentType) -> itemStack.is(ModItems.spear) || itemStack.is(ItemTags.SPEARS))
                      .setEquipmentLevel((itemStack, equipmentType) -> itemStack.is(ModItems.spear)
                                                                       ? durabilityBasedLevel(itemStack, ModItems.spear.getMaxDamage(itemStack))
                                                                       : vanillaToolLevel(itemStack, equipmentType))
                  .build());

    }

    /**
     * Get the equipmentType registry.
     *
     * @return The equipmentType registry
     */
    public static Registry<EquipmentTypeEntry> getRegistry()
    {
        return IMinecoloniesAPI.getInstance().getEquipmentTypeRegistry();
    }

    /**
     * Register a new equipmentType to the registry.
     *
     * @param id The unique ID of the equipment type
     * @param consumer The consumer that builds the equipment type
     * @return The registry entry
     */
    private static DeferredHolder<EquipmentTypeEntry, EquipmentTypeEntry> register(final String id, final Consumer<EquipmentTypeEntry.Builder> consumer)
    {
        EquipmentTypeEntry.Builder equipmentType = new EquipmentTypeEntry.Builder()
                                           .setRegistryName(Identifier.fromNamespaceAndPath(Constants.MOD_ID, id));
        consumer.accept(equipmentType);
        return DEFERRED_REGISTER.register(id, equipmentType::build);
    }

    /**
     * Get the equipment level for vanilla tools.
     *
     * @param equipmentType  The type of vanilla tool
     * @param itemStack The item stack to check
     * @return The tool level
     */
    public static int vanillaToolLevel(final ItemStack itemStack, final EquipmentTypeEntry equipmentType)
    {
        if (Compatibility.isTinkersTool(itemStack, equipmentType) || Compatibility.isTinkersWeapon(itemStack))
        {
            return Compatibility.getToolLevel(itemStack);
        }
        final int registeredLevel = Compatibility.getItemLevel(itemStack);
        if (registeredLevel >= 0)
        {
            return registeredLevel;
        }
        // Item-material data is the 26.x replacement for the removed
        // TieredItem/Tier metadata.  Some of it (custom block tags) is only
        // readable once tags are bound, after the one-time common setup scan,
        // so resolve it lazily here and remember it for unmodified stacks.
        final int level = getToolMaterialLevel(itemStack);
        if (level >= 0 && itemStack.isComponentsPatchEmpty())
        {
            Compatibility.registerItemTierIfAbsent(itemStack.getItem(), level);
        }
        return level;
    }

    /**
     * Get the durability based item level.
     *
     * @param itemStack The item stack to check
     * @return The item level
     */
    public static int durabilityBasedLevel(ItemStack itemStack, int vanillaItemDurability)
    {
        if (!itemStack.isDamageableItem())
        {
            return 5;
        }

        return Math.min(itemStack.getMaxDamage() / vanillaItemDurability, 5);
    }

    /**
     * Populate the tier registry with every item currently in the game.
     * Called once during FMLCommonSetupEvent via MineColonies.preInit.
     */
    @SuppressWarnings("null")
    public static void initRegisterEquipmentTiers()
    {
        int bowRef    = 0;
        int rodRef    = 0;
        int shearsRef = 0;
        int shieldRef = 0;
        int flintRef  = 0;
        try
        {
            bowRef    = new ItemStack(Items.BOW).getMaxDamage();
            rodRef    = new ItemStack(Items.FISHING_ROD).getMaxDamage();
            shearsRef = new ItemStack(Items.SHEARS).getMaxDamage();
            shieldRef = new ItemStack(Items.SHIELD).getMaxDamage();
            flintRef  = new ItemStack(Items.FLINT_AND_STEEL).getMaxDamage();
        }
        catch (Exception e)
        {
            // In case something goes wrong with fetching durability references, we can still continue and just won't have durability based tiers for those items.
            Log.getLogger().error("Failed to fetch getMaxDamage references for equipment tier registration, durability based tiers for certain items will not be registered.", e);
            return;
        }
        

        for (final Item item : BuiltInRegistries.ITEM)
        {
            try
            {
                final ItemStack dummy = new ItemStack(item);

                // Minecraft 26.2 no longer exposes the old TieredItem/Tier
                // hierarchy.  ToolMaterial stores its repair tag in the
                // REPAIRABLE component, so use that tag key to register the
                // same level mapping that the 1.21 TieredItem path used.
                final int toolMaterialLevel = getToolMaterialLevel(dummy);
                if (toolMaterialLevel >= 0)
                {
                    Compatibility.registerItemTierIfAbsent(item, toolMaterialLevel);
                }
                else if (ItemStackUtils.getEquippable(dummy) != null)
                {
                    final int level = ItemStackUtils.getArmorLevel(dummy);
                    if (level > 0)
                    {
                        Compatibility.registerItemTierIfAbsent(item, level);
                    }
                }
                else if (item instanceof BowItem)
                {
                    Compatibility.registerItemTierIfAbsent(item, durabilityBasedLevel(dummy, bowRef));
                }
                else if (canPerformDefaultActions(dummy, ItemAbilities.DEFAULT_FISHING_ROD_ACTIONS))
                {
                    Compatibility.registerItemTierIfAbsent(item, durabilityBasedLevel(dummy, rodRef));
                }
                else if (canPerformDefaultActions(dummy, ItemAbilities.DEFAULT_SHEARS_ACTIONS))
                {
                    Compatibility.registerItemTierIfAbsent(item, durabilityBasedLevel(dummy, shearsRef));
                }
                else if (dummy.getItem() instanceof ShieldItem)
                {
                    Compatibility.registerItemTierIfAbsent(item, durabilityBasedLevel(dummy, shieldRef));
                }
                else if (item instanceof FlintAndSteelItem)
                {
                    Compatibility.registerItemTierIfAbsent(item, durabilityBasedLevel(dummy, flintRef));
                }
            }
            catch (Exception e)
            {
                Log.getLogger().error("Failed to register equipment tiers for item: " + BuiltInRegistries.ITEM.getKey(item), e);
            }
        }
    }

    /**
     * Vanilla ToolMaterial repair tags and "incorrect for drops" block tags, mapped to the level 1.21 derived from the
     * tier's attack-damage bonus (wood/gold 0, stone/copper 1, iron 2, diamond 3, netherite 4).
     */
    private static final Map<TagKey<Item>, Integer> REPAIR_TAG_LEVELS = Map.of(
      ItemTags.WOODEN_TOOL_MATERIALS, 0,
      ItemTags.GOLD_TOOL_MATERIALS, 0,
      ItemTags.STONE_TOOL_MATERIALS, 1,
      ItemTags.COPPER_TOOL_MATERIALS, 1,
      ItemTags.IRON_TOOL_MATERIALS, 2,
      ItemTags.DIAMOND_TOOL_MATERIALS, 3,
      ItemTags.NETHERITE_TOOL_MATERIALS, 4);

    private static final Map<TagKey<Block>, Integer> INCORRECT_TAG_LEVELS = Map.of(
      BlockTags.INCORRECT_FOR_WOODEN_TOOL, 0,
      BlockTags.INCORRECT_FOR_GOLD_TOOL, 0,
      BlockTags.INCORRECT_FOR_STONE_TOOL, 1,
      BlockTags.INCORRECT_FOR_COPPER_TOOL, 1,
      BlockTags.INCORRECT_FOR_IRON_TOOL, 2,
      BlockTags.INCORRECT_FOR_DIAMOND_TOOL, 3,
      BlockTags.INCORRECT_FOR_NETHERITE_TOOL, 4);

    /**
     * Vanilla sword attack-damage baseline ({@code Items.*_SWORD} use {@code sword(material, 3.0F, ...)}); a sword's base
     * attack damage minus this is its material's attack-damage bonus.
     */
    private static final double SWORD_DAMAGE_BASELINE = 3.0D;

    /**
     * Non-vanilla materials already reported, so each is logged once.
     */
    private static final Set<TagKey<Item>> LOGGED_MATERIALS = ConcurrentHashMap.newKeySet();

    /**
     * Resolve the MineColonies equipment level for a 26.x ToolMaterial item. 1.21 used the {@code TieredItem}'s tier
     * attack-damage bonus; 26.x has no tier object on the item, only the components {@code ToolMaterial} wrote:
     * <ol>
     * <li>a vanilla material's repair tag (REPAIRABLE), read by key so it works before tags are bound;</li>
     * <li>otherwise (modded material) the TOOL component's "denies drops" rule: a vanilla {@code incorrect_for_*}
     * tag gives that tier, a custom block set is probed with the blocks that separate the vanilla tiers;</li>
     * <li>swords and spears carry no drop rule, so their level is the material's attack-damage bonus recovered
     * from the base attack damage, as in 1.21.</li>
     * </ol>
     *
     * @param stack item stack to inspect
     * @return the level, or -1 when the stack is not a material-based tool (or its material is not readable yet)
     */
    public static int getToolMaterialLevel(final ItemStack stack)
    {
        final Repairable repairable = stack.get(DataComponents.REPAIRABLE);
        final Optional<TagKey<Item>> repairTag = repairable == null ? Optional.empty() : repairable.items().unwrapKey();
        if (repairTag.isPresent() && REPAIR_TAG_LEVELS.containsKey(repairTag.get()))
        {
            return REPAIR_TAG_LEVELS.get(repairTag.get());
        }
        if (repairTag.isEmpty())
        {
            // ToolMaterial always repairs from an item tag. Items repaired from a direct item list (mace, trident)
            // are not material tools and had no tier in 1.21 either.
            return -1;
        }

        final int level = moddedMaterialLevel(stack);
        if (level >= 0 && LOGGED_MATERIALS.add(repairTag.get()))
        {
            Log.getLogger().info("Non-vanilla tool material {} ({}): using MineColonies tool level {}",
              repairTag.get().location(), BuiltInRegistries.ITEM.getKey(stack.getItem()), level);
        }
        return level;
    }

    /**
     * Level of a tool whose material is not one of vanilla's, from the components {@code ToolMaterial} applied.
     *
     * @param stack the stack.
     * @return the level, or -1.
     */
    private static int moddedMaterialLevel(final ItemStack stack)
    {
        final Tool tool = stack.get(DataComponents.TOOL);
        if (tool != null)
        {
            for (final Tool.Rule rule : tool.rules())
            {
                if (rule.correctForDrops().isPresent() && !rule.correctForDrops().get() && rule.speed().isEmpty())
                {
                    final Optional<TagKey<Block>> key = rule.blocks().unwrapKey();
                    if (key.isPresent() && INCORRECT_TAG_LEVELS.containsKey(key.get()))
                    {
                        return INCORRECT_TAG_LEVELS.get(key.get());
                    }
                    return probeDeniedBlocks(rule.blocks());
                }
            }
            // Sword shape (ToolMaterial#applySwordProperties): no drop rule, 2 damage per block, no creative breaking.
            if (tool.damagePerBlock() == 2 && !tool.canDestroyBlocksInCreative())
            {
                return attackBonusLevel(stack, SWORD_DAMAGE_BASELINE);
            }
            return -1;
        }
        // Spear shape (Item.Properties#spear): base attack damage is the material bonus alone.
        if (stack.has(DataComponents.KINETIC_WEAPON) && stack.has(DataComponents.REPAIRABLE))
        {
            return attackBonusLevel(stack, 0.0D);
        }
        return -1;
    }

    /**
     * Level from a custom "denies drops" block set: obsidian needs diamond (level 3), diamond ore needs iron (2),
     * iron ore needs stone (1). A tag that is not bound yet gives -1 so the lazy runtime lookup retries.
     *
     * @param denied the blocks the tool cannot harvest.
     * @return the level, or -1.
     */
    private static int probeDeniedBlocks(final HolderSet<Block> denied)
    {
        if (denied instanceof HolderSet.Named<Block> named && !named.isBound())
        {
            return -1;
        }
        if (denied.contains(Blocks.IRON_ORE.builtInRegistryHolder()))
        {
            return 0;
        }
        if (denied.contains(Blocks.DIAMOND_ORE.builtInRegistryHolder()))
        {
            return 1;
        }
        if (denied.contains(Blocks.OBSIDIAN.builtInRegistryHolder()))
        {
            return 2;
        }
        return 3;
    }

    /**
     * Material attack-damage bonus as a level, like 1.21's {@code (int) tier.getAttackDamageBonus()}.
     *
     * @param stack    the stack.
     * @param baseline the attack-damage baseline of this tool shape.
     * @return the level (at least 0), or -1 without a base attack damage modifier.
     */
    private static int attackBonusLevel(final ItemStack stack, final double baseline)
    {
        final ItemAttributeModifiers modifiers = stack.get(DataComponents.ATTRIBUTE_MODIFIERS);
        if (modifiers == null)
        {
            return -1;
        }
        for (final ItemAttributeModifiers.Entry entry : modifiers.modifiers())
        {
            if (entry.matches(Attributes.ATTACK_DAMAGE, Item.BASE_ATTACK_DAMAGE_ID))
            {
                return Math.max(0, (int) (entry.modifier().amount() - baseline));
            }
        }
        return -1;
    }

    /**
     * Determine whether an item stack can perform the default actions of a given tool.
     *
     * @param itemStack The item stack to check
     * @param actions   The set of actions to compare
     * @return Whether the item stack can perform the actions
     */
    /**
     * MC 26.3: ItemAbilities.DEFAULT_SHOVEL/AXE/HOE_ACTIONS were removed; shovels, axes and hoes are the
     * vanilla item tag, or anything carrying the matching BLOCK_TRANSFORMER component.
     *
     * @param itemStack   the stack.
     * @param tag         the vanilla tool tag.
     * @param transformer the vanilla block transformer of that tool.
     * @return true if the stack is such a tool.
     */
    public static boolean isToolOfKind(final ItemStack itemStack, final TagKey<Item> tag, final ResourceKey<BlockTransformer> transformer)
    {
        if (itemStack.is(tag))
        {
            return true;
        }
        final Holder<BlockTransformer> component = itemStack.get(DataComponents.BLOCK_TRANSFORMER);
        return component != null && component.is(transformer);
    }

    /**
     * Simulate the block transform the given tool would apply to a block (no side effects).
     * Replaces 26.2's getToolModifiedState(context, ability, simulate=true).
     *
     * @param tool  the tool stack.
     * @param level the level.
     * @param pos   the position.
     * @param face  the clicked face.
     * @return the resulting state, or null if the tool does not transform it.
     */
    @Nullable
    public static BlockState simulateBlockTransform(final ItemStack tool, final Level level, final BlockPos pos, final Direction face)
    {
        final Holder<BlockTransformer> component = tool.get(DataComponents.BLOCK_TRANSFORMER);
        if (component == null)
        {
            return null;
        }
        // NeoForge 26.3.0.48 (#3575): the data-map transforms are looked up by the transformer holder, not the stack.
        for (final BlockTransformer.BlockTransformData data : DataMapHooks.getAllTransformers(component))
        {
            if (data.disallowedFaces().contains(face))
            {
                continue;
            }
            final BlockState result = data.blockStateProvider().value().getOptionalState(level, level.getRandom(), pos);
            if (result != null)
            {
                return result;
            }
        }
        return null;
    }

    public static boolean canPerformDefaultActions(ItemStack itemStack, Set<ItemAbility> actions)
    {
        for (final ItemAbility toolAction : actions)
        {
            if (!itemStack.canPerformAction(toolAction))
            {
                return false;
            }
        }
        return true;
    }
}
