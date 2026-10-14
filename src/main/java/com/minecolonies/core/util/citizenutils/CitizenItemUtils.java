package com.minecolonies.core.util.citizenutils;
import net.minecraft.world.item.component.SwingAnimation;

import com.minecolonies.api.entity.citizen.AbstractEntityCitizen;
import com.minecolonies.api.util.*;
import com.minecolonies.core.network.messages.client.BlockParticleEffectMessage;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.enchantment.EnchantmentHelper;
import net.minecraft.world.item.enchantment.Enchantments;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import static com.minecolonies.api.research.util.ResearchConstants.ARMOR_DURABILITY;
import static com.minecolonies.api.research.util.ResearchConstants.TOOL_DURABILITY;
import static com.minecolonies.api.util.constant.CitizenConstants.*;
import static com.minecolonies.api.util.constant.Constants.DEFAULT_PITCH_MULTIPLIER;
import static com.minecolonies.api.util.constant.Constants.DEFAULT_VOLUME;

/**
 * Handles the citizens interaction with an item with the world.
 */
@SuppressWarnings("PMD.ExcessiveImports")
public class CitizenItemUtils
{
    /**
     * Citizen will try to pick up a certain item.
     *
     * @param itemEntity the item he wants to pickup.
     */
    public static void tryPickupItemEntity(@NotNull final AbstractEntityCitizen citizen, @NotNull final ItemEntity itemEntity)
    {
        if (!CompatibilityUtils.getWorldFromCitizen(citizen).isClientSide())
        {
            if (itemEntity.hasPickUpDelay())
            {
                return;
            }

            final ItemStack itemStack = itemEntity.getItem();
            final ItemStack compareStack = itemStack.copy();

            if (citizen.getCitizenJobHandler().getColonyJob() == null || citizen.getCitizenJobHandler().getColonyJob().pickupSuccess(compareStack))
            {
                final ItemStack resultStack = InventoryUtils.addItemStackToItemHandlerWithResult(citizen.getInventoryCitizen(), itemStack);
                final int resultingStackSize = ItemStackUtils.isEmpty(resultStack) ? 0 : ItemStackUtils.getSize(resultStack);

                if (ItemStackUtils.isEmpty(resultStack) || ItemStackUtils.getSize(resultStack) != ItemStackUtils.getSize(compareStack))
                {
                    CompatibilityUtils.getWorldFromCitizen(citizen).playSound(null,
                      citizen.blockPosition(),
                      SoundEvents.ITEM_PICKUP,
                      SoundSource.AMBIENT,
                      (float) DEFAULT_VOLUME,
                      (float) ((citizen.getRandom().nextGaussian() * DEFAULT_PITCH_MULTIPLIER + 1.0D) * 2.0D));
                    citizen.take(itemEntity, ItemStackUtils.getSize(itemStack) - resultingStackSize);

                    final ItemStack overrulingStack = itemStack.copy();
                    overrulingStack.setCount(ItemStackUtils.getSize(itemStack) - resultingStackSize);

                    if (citizen.getCitizenJobHandler().getColonyJob() != null)
                    {
                        citizen.getCitizenJobHandler().getColonyJob().onStackPickUp(overrulingStack);
                    }

                    if (ItemStackUtils.isEmpty(resultStack))
                    {
                        itemEntity.remove(Entity.RemovalReason.DISCARDED);
                    }
                }
            }
            else
            {
                itemEntity.remove(Entity.RemovalReason.DISCARDED);
            }
        }
    }

    /**
     * Removes the currently held item.
     */
    public static void removeHeldItem(AbstractEntityCitizen citizen)
    {
        citizen.setItemSlot(EquipmentSlot.MAINHAND, ItemStackUtils.EMPTY);
    }

    /**
     * Sets the currently held item.
     *
     * @param hand what hand we're setting
     * @param slot from the inventory slot.
     */
    public static void setHeldItem(@NotNull final AbstractEntityCitizen citizen, final InteractionHand hand, final int slot)
    {
        citizen.getCitizenData().getInventory().setHeldItem(hand, slot);
        if (hand.equals(InteractionHand.MAIN_HAND))
        {
            citizen.setItemSlot(EquipmentSlot.MAINHAND, citizen.getCitizenData().getInventory().getStackInSlot(slot));
        }
        else if (hand.equals(InteractionHand.OFF_HAND))
        {
            citizen.setItemSlot(EquipmentSlot.OFFHAND, citizen.getCitizenData().getInventory().getStackInSlot(slot));
        }
    }

    /**
     * Sets the currently held for mainHand item.
     *
     * @param slot from the inventory slot.
     */
    public static void setMainHeldItem(@NotNull final AbstractEntityCitizen citizen, final int slot)
    {
        citizen.getCitizenData().getInventory().setHeldItem(InteractionHand.MAIN_HAND, slot);
        citizen.setItemSlot(EquipmentSlot.MAINHAND, citizen.getCitizenData().getInventory().getStackInSlot(slot));
    }

    /**
     * Swing entity arm, create sound and particle effects.
     * <p>
     * Will not break the block.
     *
     * @param blockPos Block position.
     */
    public static void hitBlockWithToolInHand(@NotNull final AbstractEntityCitizen citizen, @Nullable final BlockPos blockPos)
    {
        if (blockPos == null)
        {
            return;
        }
        hitBlockWithToolInHand(citizen, blockPos, false);
    }

    /**
     * Swing entity arm, create sound and particle effects.
     * <p>
     * If breakBlock is true then it will break the block (different sound and particles), and wear the tool in the citizens hand the way mining the block wears it
     * for a player (see {@link #damageItemInHandForMinedBlock}).
     *
     * @param blockPos   Block position.
     * @param breakBlock if we want to break this block.
     */

    public static void hitBlockWithToolInHand(@NotNull final AbstractEntityCitizen citizen, @Nullable final BlockPos blockPos, final boolean breakBlock)
    {
        if (blockPos == null)
        {
            return;
        }

        citizen.getLookControl().setLookAt(blockPos.getX(), blockPos.getY(), blockPos.getZ(), FACING_DELTA_YAW, citizen.getMaxHeadXRot());

        citizen.swing(citizen.getUsedItemHand(), SwingAnimation.DEFAULT, false);

        final BlockState blockState = CompatibilityUtils.getWorldFromCitizen(citizen).getBlockState(blockPos);
        final Block block = blockState.getBlock();
        if (breakBlock)
        {
            if (!CompatibilityUtils.getWorldFromCitizen(citizen).isClientSide())
            {
                new BlockParticleEffectMessage(blockPos, CompatibilityUtils.getWorldFromCitizen(citizen).getBlockState(blockPos), BlockParticleEffectMessage.BREAK_BLOCK)
                    .sendToTargetPoint((ServerLevel) citizen.level(), null, blockPos.getX(), blockPos.getY(), blockPos.getZ(), BLOCK_BREAK_SOUND_RANGE);
            }
            CompatibilityUtils.getWorldFromCitizen(citizen).playSound(null,
              blockPos,
              block.getSoundType(blockState, CompatibilityUtils.getWorldFromCitizen(citizen), blockPos, citizen).getBreakSound(),
              SoundSource.BLOCKS,
              block.getSoundType(blockState, CompatibilityUtils.getWorldFromCitizen(citizen), blockPos, citizen).getVolume(),
              block.getSoundType(blockState, CompatibilityUtils.getWorldFromCitizen(citizen), blockPos, citizen).getPitch());
            // Like ServerPlayerGameMode#destroyBlock: the tool is worn by the state that is mined, before the block is gone
            damageItemInHandForMinedBlock(citizen, citizen.getUsedItemHand(), blockState, blockPos);
            WorldUtil.removeBlock(CompatibilityUtils.getWorldFromCitizen(citizen), blockPos, false);
        }
        else
        {
            if (!CompatibilityUtils.getWorldFromCitizen(citizen).isClientSide())
            {
                final BlockPos vector = blockPos.subtract(citizen.blockPosition());
                final Direction facing = BlockPosUtil.directionFromDelta(vector.getX(), vector.getY(), vector.getZ()).getOpposite();

                new BlockParticleEffectMessage(blockPos, CompatibilityUtils.getWorldFromCitizen(citizen).getBlockState(blockPos), facing.ordinal())
                    .sendToTargetPoint((ServerLevel) citizen.level(), null, blockPos.getX(), blockPos.getY(), blockPos.getZ(), BLOCK_BREAK_PARTICLE_RANGE);
            }
            CompatibilityUtils.getWorldFromCitizen(citizen).playSound(null,
              blockPos,
              block.getSoundType(blockState, CompatibilityUtils.getWorldFromCitizen(citizen), blockPos, citizen).getBreakSound(),
              SoundSource.BLOCKS,
              block.getSoundType(blockState, CompatibilityUtils.getWorldFromCitizen(citizen), blockPos, citizen).getVolume(),
              block.getSoundType(blockState, CompatibilityUtils.getWorldFromCitizen(citizen), blockPos, citizen).getPitch());
        }
    }

    /**
     * Damage the current held item.
     *
     * @param damage amount of damage.
     */
    public static void damageItemInHand(@NotNull final AbstractEntityCitizen citizen, final InteractionHand hand, final int damage)
    {
        final ItemStack heldItem = citizen.getCitizenData().getInventory().getHeldItem(hand);
        //If we hit with bare hands, ignore
        if (heldItem == null || heldItem.isEmpty())
        {
            return;
        }

        if (savedByToolDurabilityResearch(citizen))
        {
            return;
        }

        //check if tool breaks
        if (citizen.getCitizenData()
              .getInventory()
              .damageInventoryItem(citizen.getCitizenData().getInventory().getHeldItemSlot(hand), damage, citizen, item -> {}))
        {
            clearHeldItemSlot(citizen, hand);
        }
    }

    /**
     * Wears the item in the hand for a block that was mined with it, exactly as the item does when a player mines the block: the item's own
     * {@link net.minecraft.world.item.Item#mineBlock} decides (a tool with the tool component costs its damage per block, only when the block has a destroy speed above 0;
     * an item without the component costs nothing). Unbreaking applies through ItemStack#hurtAndBreak. The colony research "Citizen Tools +X% Durability" is the only
     * extra protection.
     *
     * @param citizen the citizen that mined the block.
     * @param hand    the hand with the tool.
     * @param state   the state of the block that was mined (before it was removed).
     * @param pos     the position of the block.
     */
    public static void damageItemInHandForMinedBlock(
      @NotNull final AbstractEntityCitizen citizen,
      final InteractionHand hand,
      @NotNull final BlockState state,
      @NotNull final BlockPos pos)
    {
        final ItemStack heldItem = citizen.getCitizenData().getInventory().getHeldItem(hand);
        if (heldItem == null || heldItem.isEmpty())
        {
            return;
        }

        if (savedByToolDurabilityResearch(citizen))
        {
            return;
        }

        final Level level = CompatibilityUtils.getWorldFromCitizen(citizen);
        if (citizen.getCitizenData()
              .getInventory()
              .wearInventoryItem(citizen.getCitizenData().getInventory().getHeldItemSlot(hand), stack -> stack.getItem().mineBlock(stack, level, state, pos, citizen)))
        {
            clearHeldItemSlot(citizen, hand);
        }
    }

    /**
     * The colony research "Citizen Tools +X% Durability": with strength s a wear of a citizen's tool is only applied with the chance 1 / (1 + s). It can only save wear.
     *
     * @param citizen the citizen whose tool is about to wear.
     * @return true if the research saves this wear.
     */
    private static boolean savedByToolDurabilityResearch(@NotNull final AbstractEntityCitizen citizen)
    {
        final double strength = citizen.getCitizenColonyHandler().getColonyOrRegister().getResearchManager().getResearchEffects().getEffectStrength(TOOL_DURABILITY);
        //Check if the effect exists first, to avoid unnecessary calls to random number generator.
        return strength > 0 && citizen.getRandom().nextDouble() > (1 / (1 + strength));
    }

    /**
     * The tool in the hand broke: the citizen holds nothing any more.
     */
    private static void clearHeldItemSlot(@NotNull final AbstractEntityCitizen citizen, final InteractionHand hand)
    {
        if (hand == InteractionHand.MAIN_HAND)
        {
            citizen.setItemSlot(EquipmentSlot.MAINHAND, ItemStackUtils.EMPTY);
        }
        else
        {
            citizen.setItemSlot(EquipmentSlot.OFFHAND, ItemStackUtils.EMPTY);
        }
    }

    /**
     * Pick up all items in a range around the citizen.
     */
    public static void pickupItems(AbstractEntityCitizen citizen)
    {
        for (final ItemEntity item : CompatibilityUtils.getWorldFromCitizen(citizen).getEntitiesOfClass(ItemEntity.class,
          new AABB(citizen.blockPosition())
            .expandTowards(2.0F, 1.0F, 2.0F)
            .expandTowards(-2.0F, -1.0F, -2.0F)))
        {
            if (item != null && item.isAlive())
            {
                tryPickupItemEntity(citizen, item);
            }
        }
    }

    /**
     * Swing entity arm, create sound and particle effects.
     * <p>
     * This will break the block (different sound and particles), and damage the tool in the citizens hand.
     *
     * @param blockPos Block position.
     */
    public static void breakBlockWithToolInHand(@NotNull final AbstractEntityCitizen citizen, @Nullable final BlockPos blockPos)
    {
        if (blockPos == null)
        {
            return;
        }
        hitBlockWithToolInHand(citizen, blockPos, true);
    }

    /**
     * Handles the dropping of items from the entity.
     *
     * @param itemstack to drop.
     * @return the dropped item.
     */
    public static ItemEntity entityDropItem(@NotNull final AbstractEntityCitizen citizen, @NotNull final ItemStack itemstack)
    {
        if (citizen.level() instanceof final ServerLevel serverLevel)
        {
            return citizen.spawnAtLocation(serverLevel, itemstack);
        }
        return null;
    }

    /**
     * Updates the armour damage after being hit.
     *
     * @param damage damage dealt.
     */
    public static void updateArmorDamage(@NotNull final AbstractEntityCitizen citizen, final double damage)
    {
        if (citizen.getCitizenColonyHandler().getColonyOrRegister().getResearchManager().getResearchEffects().getEffectStrength(ARMOR_DURABILITY) > 0)
        {
            if (citizen.getRandom().nextDouble() > (1 / (1 + citizen.getCitizenColonyHandler()
                                                               .getColonyOrRegister()
                                                               .getResearchManager()
                                                               .getResearchEffects()
                                                               .getEffectStrength(ARMOR_DURABILITY))))
            {
                return;
            }
        }

        final int armorDmg = Math.max(1, (int) (damage / 4));
        for (EquipmentSlot equipmentSlot : EquipmentSlot.values())
        {
            if (equipmentSlot.getType() == EquipmentSlot.Type.HUMANOID_ARMOR)
            {
                final ItemStack equipment = citizen.getInventoryCitizen().getArmorInSlot(equipmentSlot);
                equipment.hurtAndBreak(armorDmg, (ServerLevel) citizen.level(), citizen, (s) -> {
                    citizen.onArmorRemove(equipment, equipmentSlot);
                    citizen.getInventoryCitizen().markDirty();
                });
            }
        }
    }

    public static double applyMending(@NotNull final AbstractEntityCitizen citizen, final double xp)
    {
        double localXp = xp;

        for (final EquipmentSlot equipmentSlot : EquipmentSlot.values())
        {
            if (localXp <= 0)
            {
                break;
            }

            final ItemStack tool;
            if (equipmentSlot.isArmor())
            {
                tool = citizen.getInventoryCitizen().getArmorInSlot(equipmentSlot);
            }
            else
            {
                tool = citizen.getInventoryCitizen().getHeldItem(equipmentSlot == EquipmentSlot.MAINHAND ? InteractionHand.MAIN_HAND : InteractionHand.OFF_HAND);
            }

            if (!ItemStackUtils.isEmpty(tool) && tool.isDamaged() && tool.isEnchanted() && EnchantmentHelper.getTagEnchantmentLevel(Utils.getRegistryValue(Enchantments.MENDING, citizen.level()), tool) > 0)
            {
                //2 xp to heal 1 dmg
                final double dmgHealed = Math.min(localXp / 2, tool.getDamageValue());
                localXp -= dmgHealed * 2;
                tool.setDamageValue(tool.getDamageValue() - (int) Math.ceil(dmgHealed));
            }
        }

        return localXp;
    }
}
