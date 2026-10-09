package com.minecolonies.core.tileentities;

/** Path normalization shared by building NBT loading and its unit tests. */
final class BlueprintPathNormalizer
{
    private BlueprintPathNormalizer()
    {
    }

    static String ensureBlueprintExtension(final String path)
    {
        if (path == null || path.isBlank() || path.endsWith(".blueprint"))
        {
            return path;
        }
        return path + ".blueprint";
    }
}
