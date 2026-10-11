package com.minecolonies.core.colony.requestsystem.reservation;

/**
 * What a reservation holds back.
 */
public enum ReservationKind
{
    /**
     * Items at a source, promised to a delivery: they may not be promised again.
     */
    STOCK,
    /**
     * Room at a destination, promised to a delivery: it may not be promised again.
     */
    SPACE
}
