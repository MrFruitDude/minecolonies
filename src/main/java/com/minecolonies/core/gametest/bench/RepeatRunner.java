package com.minecolonies.core.gametest.bench;

import com.minecolonies.core.gametest.ColonyIsolationEnvironment;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.phys.AABB;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;
import java.util.function.Supplier;

/**
 * Runs the repeats of one benchmark test one after the other inside a single GameTest, in a fixed order (the GameTest
 * framework picks the order of separate tests itself, which would put the warm-up repeat anywhere). Repeat 0 is the
 * warm-up; between repeats the colony is deleted and the plot cleaned, so every repeat starts from the same world.
 */
public final class RepeatRunner
{
    private RepeatRunner()
    {
    }

    /**
     * One repeat of a test, started by the runner; calls {@code done} with null on success.
     */
    public interface Repeat
    {
        void start(int index, java.util.function.Consumer<String> done);
    }

    /**
     * Calls {@code body} for repeat 0..reps in order. The test succeeds when every repeat did; the failures of all repeats are
     * reported together (a failed repeat does not stop the others, so one run measures as much as it can).
     *
     * @param plot a box of the test plot that is cleaned of loose items between repeats.
     */
    public static void run(final GameTestHelper helper, final int reps, final Supplier<AABB> plot, final Function<Integer, Repeat> body)
    {
        final List<String> failures = new ArrayList<>();
        final Runnable[] next = new Runnable[1];
        final int[] index = {0};
        next[0] = () -> {
            final int i = index[0]++;
            final Repeat repeat = body.apply(i);
            repeat.start(i, failure -> {
                if (failure != null)
                {
                    failures.add(failure);
                }
                ColonyIsolationEnvironment.deleteAllColonies(helper.getLevel());
                for (final ItemEntity stray : helper.getLevel().getEntitiesOfClass(ItemEntity.class, plot.get()))
                {
                    stray.discard();
                }
                if (i < reps)
                {
                    helper.runAfterDelay(40, next[0]);
                }
                else if (failures.isEmpty())
                {
                    helper.succeed();
                }
                else
                {
                    helper.fail(String.join(" || ", failures));
                }
            });
        };
        next[0].run();
    }
}
