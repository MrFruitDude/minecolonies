package com.minecolonies.core.tileentities;

/** Path normalization shared by building NBT loading and its unit tests. */
final class BlueprintPathNormalizer
{
    private BlueprintPathNormalizer()
    {
    }

    /**
     * Adds the ".blueprint" extension to a non-blank path. A null or blank path (source stashes and postboxes have no schematic)
     * stays blank: it must not become the file name ".blueprint".
     *
     * @param path the stored path, may be null.
     * @return the path with its extension, or "" for a null or blank path (a blank path is returned as it was).
     */
    static String ensureBlueprintExtension(final String path)
    {
        if (path == null)
        {
            return "";
        }
        if (path.isBlank() || path.endsWith(".blueprint"))
        {
            return path;
        }
        return path + ".blueprint";
    }
}
