package com.minecolonies.api.client.render.modeltype;

import java.util.function.Supplier;

/**
 * One citizen's resolved render model, kept on the citizen between frames. The renderer chose the model in
 * {@code submit} for every citizen every frame with one or two model type registry lookups; the choice depends only on
 * the renderer (a resource reload makes a new one with new models), the citizen's model type, its gender and whether
 * it has a custom texture, so it is resolved again only when one of those changes.
 * <p>
 * Pure (the model type is generic), so it is unit tested without a client.
 *
 * @param <M> the model type
 */
public final class ResolvedModelCache<M>
{
    private Object owner;
    private Object modelType;
    private boolean female;
    private boolean custom;
    private M model;
    private int resolves;

    /**
     * The model for these inputs: the kept one when nothing changed, else {@code resolve} (which reads the registry).
     *
     * @param owner     the renderer asking (identity): a new renderer resolves again
     * @param modelType the citizen's model type id (compared with equals)
     */
    public M get(final Object owner, final Object modelType, final boolean female, final boolean custom, final Supplier<M> resolve)
    {
        if (model == null || owner != this.owner || female != this.female || custom != this.custom || !java.util.Objects.equals(modelType, this.modelType))
        {
            model = resolve.get();
            this.owner = owner;
            this.modelType = modelType;
            this.female = female;
            this.custom = custom;
            resolves++;
        }
        return model;
    }

    /** How many times a model was resolved (tests). */
    public int resolves()
    {
        return resolves;
    }
}
