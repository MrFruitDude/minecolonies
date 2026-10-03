package com.minecolonies.core.client.render.worldevent;

import org.junit.Test;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertSame;

/**
 * FX2 R5 #12: the colony border overlay read its whole chunk/claim grid every frame the build tool was held. The grid
 * is now read once per change of its {@link ColonyBorderGrid.Key}.
 */
public class ColonyBorderGridTest
{
    private static final int RANGE = 20;
    private static final int CELLS = (2 * RANGE + 1) * (2 * RANGE + 1);
    private static final Object LEVEL = new Object();
    private static final Object COLONY = new Object();

    /** Chunks with x in 0..3 belong to colony 7, x in 4..5 to colony 9, the rest unclaimed. Chunks with z == 5 are not loaded. */
    private static int owner(final int x, final int z)
    {
        return x >= 0 && x <= 3 ? 7 : x >= 4 && x <= 5 ? 9 : 0;
    }

    private static boolean loaded(final int x, final int z)
    {
        return z != 5;
    }

    private static ColonyBorderGrid.Key key(final int px, final int pz, final boolean tickets, final long claimRev, final long chunkRev, final Object ticketSet)
    {
        return new ColonyBorderGrid.Key(LEVEL, COLONY, px, pz, tickets, RANGE, claimRev, chunkRev, ticketSet);
    }

    /** One frame as ColonyBorderRenderer#render does it, counting claim lookups. */
    private static List<ColonyBorderGrid.Cell> frame(final ColonyBorderGrid.Cache<List<ColonyBorderGrid.Cell>> cache, final ColonyBorderGrid.Key key,
      final AtomicInteger claimReads, final Set<Long> ticketed)
    {
        return cache.get(key, () -> ColonyBorderGrid.compute(key.playerChunkX(), key.playerChunkZ(), key.range(), key.showTickets(), 7,
          ColonyBorderGridTest::loaded,
          (x, z) -> {
              claimReads.incrementAndGet();
              return owner(x, z);
          },
          (x, z) -> ticketed.contains(((long) x << 32) ^ z)));
    }

    @Test
    public void unchangedFramesReadTheGridOnce()
    {
        final ColonyBorderGrid.Cache<List<ColonyBorderGrid.Cell>> cache = new ColonyBorderGrid.Cache<>();
        final AtomicInteger claimReads = new AtomicInteger();
        List<ColonyBorderGrid.Cell> first = null;
        for (int f = 0; f < 300; f++)
        {
            final List<ColonyBorderGrid.Cell> cells = frame(cache, key(1, 1, false, 4, 2, null), claimReads, Set.of());
            if (first == null)
            {
                first = cells;
            }
            assertSame("frame " + f + " reused the grid", first, cells);
        }
        assertEquals("grid builds over 300 unchanged frames", 1, cache.builds());
        // One read per loaded cell, once (the pre-cache renderer did this every frame: 300x).
        final int loadedCells = CELLS - (2 * RANGE + 1);
        assertEquals("claim lookups over 300 unchanged frames", loadedCells, claimReads.get());
    }

    @Test
    public void everyInputOfTheKeyReadsTheGridAgain()
    {
        final ColonyBorderGrid.Cache<List<ColonyBorderGrid.Cell>> cache = new ColonyBorderGrid.Cache<>();
        final AtomicInteger reads = new AtomicInteger();
        final Set<Long> tickets = new HashSet<>();
        frame(cache, key(1, 1, false, 4, 2, null), reads, tickets);
        frame(cache, key(1, 1, false, 5, 2, null), reads, tickets);   // a claim changed (sync or server mutation)
        frame(cache, key(1, 1, false, 5, 3, null), reads, tickets);   // a chunk loaded or unloaded
        frame(cache, key(2, 1, false, 5, 3, null), reads, tickets);   // the player moved to another chunk
        frame(cache, key(2, 1, true, 5, 3, Set.copyOf(tickets)), reads, tickets); // Ctrl: tickets (the renderer keys a copy)
        tickets.add(12L);
        frame(cache, key(2, 1, true, 5, 3, new HashSet<>(tickets)), reads, tickets); // the ticket set changed
        frame(cache, new ColonyBorderGrid.Key(LEVEL, new Object(), 2, 1, true, RANGE, 5, 3, new HashSet<>(tickets)), reads, tickets); // another colony
        frame(cache, new ColonyBorderGrid.Key(new Object(), COLONY, 2, 1, true, RANGE, 5, 3, new HashSet<>(tickets)), reads, tickets); // another level
        frame(cache, new ColonyBorderGrid.Key(new Object(), COLONY, 2, 1, true, RANGE + 1, 5, 3, new HashSet<>(tickets)), reads, tickets); // another range
        assertEquals("one build per changed input", 9, cache.builds());
        // An equal ticket set in a new object (the view replaced it with the same chunks) is not a change.
        final ColonyBorderGrid.Key same = new ColonyBorderGrid.Key(LEVEL, COLONY, 3, 3, true, RANGE, 5, 3, new HashSet<>(tickets));
        frame(cache, same, reads, tickets);
        frame(cache, new ColonyBorderGrid.Key(LEVEL, COLONY, 3, 3, true, RANGE, 5, 3, new HashSet<>(tickets)), reads, tickets);
        assertEquals(10, cache.builds());
        cache.clear();
        frame(cache, same, reads, tickets);
        assertEquals("clear() forces a fresh read", 11, cache.builds());
    }

    @Test
    public void theGridHasTheCellsTheOldPerFrameLoopDrew()
    {
        final Set<Long> ticketed = Set.of(((long) 2 << 32) ^ 3, ((long) -4 << 32) ^ 6, ((long) 1 << 32) ^ 5);
        for (final boolean tickets : new boolean[] {false, true})
        {
            final List<ColonyBorderGrid.Cell> expected = new ArrayList<>();
            for (int dx = -RANGE; dx <= RANGE; dx++)
            {
                for (int dz = -RANGE; dz <= RANGE; dz++)
                {
                    final int x = 1 + dx;
                    final int z = 1 + dz;
                    if (!loaded(x, z))
                    {
                        continue;
                    }
                    if (!tickets && owner(x, z) != 0)
                    {
                        expected.add(new ColonyBorderGrid.Cell(x, z, owner(x, z)));
                    }
                    else if (tickets && ticketed.contains(((long) x << 32) ^ z))
                    {
                        expected.add(new ColonyBorderGrid.Cell(x, z, 7));
                    }
                }
            }
            final List<ColonyBorderGrid.Cell> got = ColonyBorderGrid.compute(1, 1, RANGE, tickets, 7, ColonyBorderGridTest::loaded, ColonyBorderGridTest::owner,
              (x, z) -> ticketed.contains(((long) x << 32) ^ z));
            assertEquals("cells with tickets=" + tickets, expected, got);
        }
        // The ticket grid: (1,5) is ticketed but not loaded, so 2 cells.
        assertEquals(2, ColonyBorderGrid.compute(1, 1, RANGE, true, 7, ColonyBorderGridTest::loaded, ColonyBorderGridTest::owner,
          (x, z) -> ticketed.contains(((long) x << 32) ^ z)).size());
    }
}
