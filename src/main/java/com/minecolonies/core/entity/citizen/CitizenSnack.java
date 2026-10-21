package com.minecolonies.core.entity.citizen;

import com.minecolonies.api.colony.ICitizenData;
import com.minecolonies.api.colony.buildings.IBuilding;
import com.minecolonies.api.crafting.ItemStorage;
import com.minecolonies.api.entity.ai.statemachine.states.CitizenAIState;
import com.minecolonies.api.entity.citizen.citizenhandlers.ICitizenFoodHandler;
import com.minecolonies.api.util.FoodUtils;
import com.minecolonies.api.util.InventoryUtils;
import com.minecolonies.api.util.ItemStackUtils;
import com.minecolonies.api.util.SoundUtils;
import com.minecolonies.core.network.messages.client.ItemParticleEffectMessage;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.SwingAnimation;

import java.util.concurrent.atomic.AtomicInteger;

import static com.minecolonies.api.util.constant.CitizenConstants.FULL_SATURATION;
import static com.minecolonies.api.util.constant.GuardConstants.BASIC_VOLUME;

/**
 * Eating at work (round 10): a working citizen whose saturation falls below 30% eats suitable food he carries, or takes it from the racks of his work hut
 * when he stands in it, without leaving his work: one item every few seconds, until he is at half. The trip to a restaurant (the eat task at 2.5) stays for
 * a citizen who has no such food. The food rules of the eat task hold: home level, diversity and quality (a citizen on the brink of complaining keeps his
 * food and goes to the restaurant), the history of what he ate. Server side only.
 */
public class CitizenSnack
{
    /**
     * Below this saturation a working citizen starts eating at work (30%).
     */
    public static final double START = FULL_SATURATION * 0.3;

    /**
     * Once he eats, he goes on until this saturation (50%), so he does not eat one bite at every check.
     */
    public static final double UNTIL = FULL_SATURATION * 0.5;

    /**
     * Ticks between two checks (and between two items: about the five seconds of the eat task).
     */
    public static final int INTERVAL = 100;

    /**
     * Items eaten at work (for the tests).
     */
    public static final AtomicInteger eaten = new AtomicInteger();

    /**
     * Takes of food from the racks of a work hut (for the tests).
     */
    public static final AtomicInteger pulls = new AtomicInteger();

    private final EntityCitizen citizen;

    /**
     * Whether he is eating at work (between START and UNTIL).
     */
    private boolean eating = false;

    public CitizenSnack(final EntityCitizen citizen)
    {
        this.citizen = citizen;
    }

    /**
     * A slot of food he carries that the rule lets him eat outside a restaurant, or -1.
     */
    private int acceptedFoodSlot(final ICitizenData data)
    {
        for (int i = 0; i < citizen.getInventoryCitizen().getSlots(); i++)
        {
            final ItemStack stack = citizen.getInventoryCitizen().getStackInSlot(i);
            if (!stack.isEmpty() && FoodUtils.wouldEatOutsideRestaurant(data, stack))
            {
                return i;
            }
        }
        return -1;
    }

    /**
     * How many items of {@code food} take him from {@code saturation} to {@link #UNTIL} (at least one, at most a stack).
     */
    private int itemsNeeded(final ItemStack food, final double saturation)
    {
        final double value = FoodUtils.getFoodValue(food, citizen);
        if (value <= 0)
        {
            return 1;
        }
        return Math.max(1, Math.min(food.getMaxStackSize(), (int) Math.ceil((UNTIL - saturation) / value)));
    }

    /**
     * One check: eats one item when he is working, hungry enough and has suitable food at hand.
     *
     * @return false (a ticking transition that never changes the state).
     */
    public boolean tick()
    {
        final ICitizenData data = citizen.getCitizenData();
        final var state = citizen.getCitizenAI().getState();
        if (data == null || citizen.level().isClientSide() || data.getJob() == null || data.isChild() || (state != CitizenAIState.WORKING && state != CitizenAIState.WORK))
        {
            eating = false;
            return false;
        }
        final double saturation = data.getSaturation();
        if (saturation >= UNTIL || (!eating && saturation >= START))
        {
            eating = false;
            return false;
        }

        int slot = FoodUtils.getBestFoodForCitizen(citizen.getInventoryCitizen(), data, null);
        if (slot == -1)
        {
            // the best scored food in his pockets is kept for the restaurant: another item he carries may still pass the rule (also what is left of an
            // earlier rack take); while he carries such food he does not go to the racks
            slot = acceptedFoodSlot(data);
        }
        if (slot == -1)
        {
            final IBuilding work = data.getWorkBuilding();
            if (work != null && work.isInBuilding(citizen.blockPosition()))
            {
                // decided before anything moves (the food rules and the restaurant choice), and he takes only what this snack needs, never a whole slot;
                // then he eats exactly that item
                final ItemStorage food = FoodUtils.checkForFoodInBuilding(data, null, work);
                if (food != null && FoodUtils.wouldEatOutsideRestaurant(data, food.getItemStack())
                      && InventoryUtils.transferItemStackIntoNextBestSlotInItemHandler(work, food, itemsNeeded(food.getItemStack(), saturation), citizen.getInventoryCitizen()))
                {
                    slot = InventoryUtils.findFirstSlotInItemHandlerWith(citizen.getInventoryCitizen(),
                      stack -> !stack.isEmpty() && food.equals(new ItemStorage(stack)));
                    pulls.incrementAndGet();
                }
            }
        }
        if (slot == -1)
        {
            eating = false;
            return false;
        }

        final ItemStack food = citizen.getInventoryCitizen().getStackInSlot(slot);
        final Item item = food.getItem();
        citizen.swing(InteractionHand.MAIN_HAND, SwingAnimation.DEFAULT, false);
        citizen.playSound(SoundEvents.GENERIC_EAT.value(), (float) BASIC_VOLUME, (float) SoundUtils.getRandomPitch(citizen.getRandom()));
        new ItemParticleEffectMessage(food.copy(), citizen.getX(), citizen.getY(), citizen.getZ(), citizen.getXRot(), citizen.getYRot(), citizen.getEyeHeight()).sendToTrackingEntity(citizen);
        ItemStackUtils.consumeFood(food, citizen, null);
        final ICitizenFoodHandler foodHandler = data.getCitizenFoodHandler();
        if (foodHandler.getLastEaten() != item)
        {
            foodHandler.addLastEaten(item);
        }
        // like after a meal: no meal decision until his saturation drops again
        data.setJustAte(true);
        data.markDirty(0);
        eaten.incrementAndGet();
        eating = data.getSaturation() < UNTIL;
        return false;
    }
}
