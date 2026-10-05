package org.valkyrienskies.mod.common.ships.entity_interaction;

import org.joml.Vector3dc;
import org.valkyrienskies.mod.common.entity.EntityShipMovementData;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.UUID;

public interface IDraggable {
    @Nonnull
    EntityShipMovementData getEntityShipMovementData();

    void setEntityShipMovementData(@Nullable EntityShipMovementData entityShipMovementData);

    boolean getInAirPocket();

    void setTicksAirPocket(int ticksInAirPocket);

    void decrementTicksAirPocket();

    /**
     * Declare where this entity stands IN A SHIP'S FRAME for the movement packet built during the
     * entity's current tick ({@code ticksExisted}). A player's movement packet then carries this
     * point and ship instead of a world position computed through this side's pose, and the server
     * maps it through its own. Whoever holds the entity on the deck declares it, AFTER placing the
     * entity in the world; a declaration from an earlier tick, or one the entity's position has been
     * written away from since, is never sent.
     */
    void setMovementClaimInShip(@Nonnull UUID shipId, double x, double y, double z);

    /** The ship of a claim declared during THIS tick of the entity, or {@code null}. */
    @Nullable
    UUID getMovementClaimShip();

    /** The point of the claim {@link #getMovementClaimShip} names, in that ship's frame. */
    @Nonnull
    Vector3dc getMovementClaimInShip();
}
