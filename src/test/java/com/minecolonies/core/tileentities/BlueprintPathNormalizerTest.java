package com.minecolonies.core.tileentities;

import org.junit.Test;

import static org.junit.Assert.assertEquals;

public class BlueprintPathNormalizerTest
{
    @Test
    public void absentPathBecomesBlankNotAFileName()
    {
        assertEquals("", BlueprintPathNormalizer.ensureBlueprintExtension(null));
    }

    @Test
    public void preservesBlankPaths()
    {
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
