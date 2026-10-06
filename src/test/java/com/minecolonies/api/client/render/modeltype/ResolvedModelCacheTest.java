package com.minecolonies.api.client.render.modeltype;

import org.junit.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertSame;

/**
 * FX2 R5 #16b: the citizen renderer resolved each citizen's model from the model type registry every frame. The
 * resolution is kept on the citizen ({@link ResolvedModelCache}) until its inputs change.
 */
public class ResolvedModelCacheTest
{
    @Test
    public void fiftyCitizensOverAHundredFramesResolveFiftyTimes()
    {
        final Object renderer = new Object();
        final List<ResolvedModelCache<String>> citizens = new ArrayList<>();
        for (int i = 0; i < 50; i++)
        {
            citizens.add(new ResolvedModelCache<>());
        }
        final AtomicInteger registryReads = new AtomicInteger();
        for (int frame = 0; frame < 100; frame++)
        {
            for (int i = 0; i < citizens.size(); i++)
            {
                final int n = i;
                final String model = citizens.get(i).get(renderer, "minecolonies:" + (n % 5), n % 2 == 0, false, () -> {
                    registryReads.incrementAndGet();
                    return "model-" + (n % 5) + (n % 2 == 0 ? "f" : "m");
                });
                assertEquals("model-" + (n % 5) + (n % 2 == 0 ? "f" : "m"), model);
            }
        }
        assertEquals("registry reads for 50 citizens over 100 frames", 50, registryReads.get());
    }

    @Test
    public void eachInputChangeResolvesAgain()
    {
        final ResolvedModelCache<String> cache = new ResolvedModelCache<>();
        final Object renderer = new Object();
        final String first = cache.get(renderer, "minecolonies:builder", false, false, () -> "builder-m");
        assertSame(first, cache.get(renderer, new String("minecolonies:builder"), false, false, () -> "other"));
        assertEquals(1, cache.resolves());
        assertEquals("builder-f", cache.get(renderer, "minecolonies:builder", true, false, () -> "builder-f"));       // gender
        assertEquals("custom", cache.get(renderer, "minecolonies:builder", true, true, () -> "custom"));              // custom texture
        assertEquals("farmer", cache.get(renderer, "minecolonies:farmer", true, true, () -> "farmer"));               // job model
        assertEquals("reloaded", cache.get(new Object(), "minecolonies:farmer", true, true, () -> "reloaded"));        // a new renderer (reload)
        assertEquals(5, cache.resolves());
        assertEquals("null model type", "default", cache.get(renderer, null, true, true, () -> "default"));
        assertEquals("default", cache.get(renderer, null, true, true, () -> "x"));
        assertEquals(6, cache.resolves());
    }
}
