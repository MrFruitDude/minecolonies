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
import com.minecolonies.api.util.WorldUtil;
import com.minecolonies.api.tileentities.AbstractTileEntityRack;
import com.minecolonies.core.colony.idle.IdleCitizenArbiter;
import com.minecolonies.core.colony.jobs.JobDeliveryman;
import com.minecolonies.core.colony.labour.LabourClaim;
import com.minecolonies.core.colony.labour.LabourSession;
import com.minecolonies.core.network.messages.client.ItemParticleEffectMessage;
import net.minecraft.core.BlockPos;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.SwingAnimation;
import net.minecraft.world.level.Level;

import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Predicate;

import static com.minecolonies.api.util.constant.CitizenConstants.FULL_SATURATION;
import static com.minecolonies.api.util.constant.GuardConstants.BASIC_VOLUME;

/**
 * Eating at work (round 10): a working citizen whose saturation falls below 30% eats suitable food he carries, or takes it from the racks of his work hut
 * when he stands in it, without leaving his work: one item every few seconds, until he is at half. The trip to a restaurant (the eat task at 2.5) stays for
 * a citizen who has no such food. The food rules of the eat task hold: home level, diversity and quality (a citizen on the brink of complaining keeps his
 * food and goes to the restaurant), the history of what he ate. Server side only.
 * <p>
 * Round 11: a courier with a task in his queue never eats from his pockets (they hold the cargo); he may still take one item from his hut's racks. An unemployed
 * labourer at work (the LABOUR state) eats from his pockets too, never what his claim brings. A worker who found nothing for him in his hut's racks looks there
 * again only after {@link #RACK_BACKOFF} ticks or when a rack of the hut changed.
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

    /**
     * Scans of a work hut's racks for food (for the tests).
     */
    public static final AtomicInteger rackScans = new AtomicInteger();

    /**
     * Ticks a worker does not scan his hut's racks again after a scan found nothing for him (unless a rack of the hut changed).
     */
    public static final int RACK_BACKOFF = 600;

    private final EntityCitizen citizen;

    /**
     * Whether he is eating at work (between START and UNTIL).
     */
    private boolean eating = false;

    /**
     * After a rack scan that found nothing: the game time until which he does not scan again, and the racks' fingerprint at that scan.
     */
    private long noRackFoodUntil = Long.MIN_VALUE;
    private long noRackFoodPrint = 0L;

    public CitizenSnack(final EntityCitizen citizen)
    {
        this.citizen = citizen;
    }

    /**
     * The best scored slot of food he carries that the rule lets him eat outside a restaurant (the score of the eat task: eaten long ago or never
     * first, MineColonies food counted half), or -1.
     */
    private int acceptedFoodSlot(final ICitizenData data, final Predicate<ItemStack> reserved)
    {
        int best = -1;
        int bestScore = Integer.MAX_VALUE;
        for (int i = 0; i < citizen.getInventoryCitizen().getSlots(); i++)
        {
            final ItemStack stack = citizen.getInventoryCitizen().getStackInSlot(i);
            if (!stack.isEmpty() && !reserved.test(stack) && FoodUtils.wouldEatOutsideRestaurant(data, stack))
            {
                final int score = data.getCitizenFoodHandler().checkLastEaten(stack.getItem()) * (stack.getItem() instanceof com.minecolonies.api.items.IMinecoloniesFoodItem ? 1 : 2);
                if (score < bestScore)
                {
                    bestScore = score;
                    best = i;
                }
            }
        }
        return best;
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
     * @return whether the citizen's pockets hold goods of a task: a courier with a task in his queue (round 11, W1-02).
     */
    public static boolean carriesCargo(final ICitizenData data)
    {
        return data.getJob() instanceof JobDeliveryman courier && !courier.getTaskQueue().isEmpty();
    }

    /**
     * @return what a labourer may not eat: what the claim of his labour session brings (nothing for anybody else).
     */
    private static Predicate<ItemStack> reservedFor(final ICitizenData data)
    {
        if (data.getJob() == null && IdleCitizenArbiter.sessionOf(data) instanceof LabourSession session)
        {
            final LabourClaim claim = session.claim();
            return claim::accepts;
        }
        return stack -> false;
    }

    /**
     * A cheap fingerprint of the racks of a building: their positions and how often their content changed.
     */
    static long rackPrint(final IBuilding building)
    {
        final Level level = building.getColony().getWorld();
        long print = 17L;
        for (final BlockPos pos : building.getContainers())
        {
            print = print * 31L + pos.asLong();
            if (level != null && WorldUtil.isBlockLoaded(level, pos) && level.getBlockEntity(pos) instanceof AbstractTileEntityRack rack)
            {
                print = print * 31L + rack.getContentVersion();
            }
        }
        return print;
    }

    /**
     * One check: eats one item when he is working (or labouring), hungry enough and has suitable food at hand.
     *
     * @return false (a ticking transition that never changes the state).
     */
    public boolean tick()
    {
        final ICitizenData data = citizen.getCitizenData();
        final var state = citizen.getCitizenAI().getState();
        if (data == null || citizen.level().isClientSide() || data.isChild())
        {
            eating = false;
            return false;
        }
        final boolean working = data.getJob() != null && (state == CitizenAIState.WORKING || state == CitizenAIState.WORK);
        final boolean labouring = data.getJob() == null && state == CitizenAIState.LABOUR;
        if (!working && !labouring)
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

        // a courier's pockets hold his cargo: nothing of it is eaten (he may still take from his hut's racks); a labourer keeps what his claim brings
        final boolean cargo = carriesCargo(data);
        final Predicate<ItemStack> reserved = reservedFor(data);
        int slot = -1;
        if (!cargo)
        {
            slot = FoodUtils.getBestFoodForCitizen(citizen.getInventoryCitizen(), data, null);
            if (slot != -1 && reserved.test(citizen.getInventoryCitizen().getStackInSlot(slot)))
            {
                slot = -1;
            }
            if (slot == -1)
            {
                // the best scored food in his pockets is kept for the restaurant: another item he carries may still pass the rule (also what is left of an
                // earlier rack take); while he carries such food he does not go to the racks
                slot = acceptedFoodSlot(data, reserved);
            }
        }
        if (slot == -1 && working)
        {
            final IBuilding work = data.getWorkBuilding();
            if (work != null && work.isInBuilding(citizen.blockPosition()))
            {
                final long now = citizen.level().getGameTime();
                final long print = rackPrint(work);
                if (now >= noRackFoodUntil || print != noRackFoodPrint)
                {
                    // decided before anything moves (the food rules and the restaurant choice), and he takes only what this snack needs (one item for a
                    // courier with cargo), never a whole slot; then he eats exactly that item
                    rackScans.incrementAndGet();
                    final ItemStorage food = FoodUtils.checkForFoodInBuilding(data, null, work);
                    if (food != null && FoodUtils.wouldEatOutsideRestaurant(data, food.getItemStack())
                          && InventoryUtils.transferItemStackIntoNextBestSlotInItemHandler(work, food, cargo ? 1 : itemsNeeded(food.getItemStack(), saturation),
                      citizen.getInventoryCitizen()))
                    {
                        slot = InventoryUtils.findFirstSlotInItemHandlerWith(citizen.getInventoryCitizen(),
                          stack -> !stack.isEmpty() && food.equals(new ItemStorage(stack)));
                        pulls.incrementAndGet();
                        noRackFoodUntil = Long.MIN_VALUE;
                    }
                    else
                    {
                        // nothing for him there: no new scan for a while, unless a rack of the hut changes (the fingerprint after the scan)
                        noRackFoodUntil = now + RACK_BACKOFF;
                        noRackFoodPrint = rackPrint(work);
                    }
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
