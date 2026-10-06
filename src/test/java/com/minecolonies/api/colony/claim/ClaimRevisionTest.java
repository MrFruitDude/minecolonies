package com.minecolonies.api.colony.claim;

import org.junit.Test;

import java.util.List;

import static org.junit.Assert.assertTrue;

/**
 * FX2 R5 #12: the border grid cache is invalidated by {@link ClaimRevision}, so every claim mutation must bump it.
 * {@code setStaticColonyClaim} is the one mutator that needs no live chunk; the others are covered in the client run.
 */
public class ClaimRevisionTest
{
    @Test
    public void aClaimChangeBumpsTheRevision()
    {
        final long before = ClaimRevision.current();
        new ChunkClaimData().setStaticColonyClaim(List.of(3));
        assertTrue("setStaticColonyClaim bumps the claim revision", ClaimRevision.current() > before);
        final long mid = ClaimRevision.current();
        ClaimRevision.bump();
        assertTrue(ClaimRevision.current() > mid);
    }
}
