package com.minecolonies.api.colony.requestsystem.data;

import com.minecolonies.api.colony.requestsystem.token.IToken;

/**
 * Core class that describes the datastores that are part of the RS.
 */
public interface IDataStore
{
    /**
     * What {@link #viewVersion()} returns for a store that does not count its changes.
     */
    long UNVERSIONED = -1L;

    /**
     * Method to get the {@link IToken} used to identify this {@link IDataStore}.
     *
     * @return The {@link IToken} for this {@link IDataStore}
     */
    IToken<?> getId();

    /**
     * Method to set the {@link IToken} used to identify this {@link IDataStore}. Used during creation to setup the initial Id.
     *
     * @param id The new Id.
     */
    void setId(IToken<?> id);

    /**
     * A counter that changes whenever anything in this store changes (its content, its id). The view sync serializes a store
     * again only when the counter moved since it last wrote it. A store that cannot say returns {@link #UNVERSIONED} and is
     * serialized every time.
     *
     * @return the change counter, or {@link #UNVERSIONED}.
     */
    default long viewVersion()
    {
        return UNVERSIONED;
    }
}
