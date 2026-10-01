package com.minecolonies.core.client.render;

import java.util.HashMap;
import java.util.Map;

/**
 * Parses a citizen's render metadata string into prop-visibility bits once per distinct string.
 * The metadata only changes when a worker's AI state changes, but the renderer extracts it every frame
 * for every citizen, so the 17 substring checks are cached per metadata value. Pure Java so it can be
 * tested on a dedicated server.
 */
public final class CitizenRenderFlags
{
    public static final int WORKING  = 1;
    public static final int STUDYING = 1 << 1;
    public static final int BOOK     = 1 << 2;
    public static final int FLOWERS  = 1 << 3;
    public static final int POTION   = 1 << 4;
    public static final int CARROT   = 1 << 5;
    public static final int LOGS     = 1 << 6;
    public static final int ARROW    = 1 << 7;
    public static final int BUCKET   = 1 << 8;
    public static final int BACKPACK = 1 << 9;
    public static final int STONE    = 1 << 10;
    public static final int TORCH    = 1 << 11;
    public static final int SHOVEL   = 1 << 12;
    public static final int PICKAXE  = 1 << 13;
    public static final int ROD      = 1 << 14;
    public static final int FISH     = 1 << 15;

    /**
     * Metadata keys in bit order (bit i = KEYS[i]). Same strings the worker AIs write.
     */
    public static final String[] KEYS = {
        "working", "study", "book", "flowers", "potion", "carrot", "logs", "arrow",
        "bucket", "backpack", "stone", "torch", "shovel", "pickaxe", "rod", "fish"};

    /**
     * Metadata values are concatenations of the fixed keys above, so the set of distinct values is small;
     * the bound only guards against an add-on writing unbounded strings.
     */
    private static final int MAX_CACHED = 256;

    /**
     * Only touched from the render thread (entity render-state extraction).
     */
    private static final Map<String, Integer> CACHE = new HashMap<>();

    private CitizenRenderFlags()
    {
    }

    /**
     * @param renderMetadata the citizen's render metadata, may be null.
     * @return the visibility bits for that metadata.
     */
    public static int of(final String renderMetadata)
    {
        if (renderMetadata == null || renderMetadata.isEmpty())
        {
            return 0;
        }
        final Integer cached = CACHE.get(renderMetadata);
        if (cached != null)
        {
            return cached;
        }
        final int flags = parse(renderMetadata);
        if (CACHE.size() >= MAX_CACHED)
        {
            CACHE.clear();
        }
        CACHE.put(renderMetadata, flags);
        return flags;
    }

    /**
     * Uncached parse, same substring semantics as the checks it replaces.
     *
     * @param renderMetadata the metadata string.
     * @return the visibility bits.
     */
    public static int parse(final String renderMetadata)
    {
        int flags = 0;
        for (int i = 0; i < KEYS.length; i++)
        {
            if (renderMetadata.contains(KEYS[i]))
            {
                flags |= 1 << i;
            }
        }
        return flags;
    }
}
