package com.minecolonies.core.tileentities;

import org.junit.Test;

import static org.junit.Assert.assertEquals;

public class BlueprintPathNormalizerTest
{
    @Test
    public void preservesAbsentAndBlankPaths()
    {
        assertEquals(null, BlueprintPathNormalizer.ensureBlueprintExtension(null));
        assertEquals("", BlueprintPathNormalizer.ensureBlueprintExtension(""));
        assertEquals(" \t ", BlueprintPathNormalizer.ensureBlueprintExtension(" \t "));
    }

    @Test
    public void addsExtensionOnlyToNonblankPaths()
    {
        assertEquals("fundamentals/cook5.blueprint",
          BlueprintPathNormalizer.ensureBlueprintExtension("fundamentals/cook5"));
        assertEquals("fundamentals/cook5.blueprint",
          BlueprintPathNormalizer.ensureBlueprintExtension("fundamentals/cook5.blueprint"));
    }
}
