package zmaster587.advancedRocketry.api;

import net.minecraft.entity.Entity;

import java.util.OptionalDouble;

public interface IGravityManager {

    /**
     * @param entity     entity to apply custom gravity on
     * @param multiplier magnitude of the gravitational effects on the entity, 1 is earthlike
     */
    void setGravityMultiplier(Entity entity, double multiplier);

    /**
     * Removes the specified entity from the list of entities with custom gravity effects and normal planetary gravity takes over
     *
     * @param entity entity to remove from custom gravity
     */
    void clearGravityEffect(Entity entity);

    /**
     * The custom multiplier this entity currently carries, if it carries one.
     *
     * <p>The pair above could only be WRITTEN: a mod that set a multiplier had no way to ask what
     * it was, and could not tell its own override from another mod's, or from none at all. This
     * completes them.</p>
     *
     * <p><b>Empty is not 1.0.</b> A multiplier of exactly 1 is a legitimate value — "earthlike,
     * pinned, and not subject to this dimension" — and it must stay distinguishable from "no
     * override, so the dimension's own gravity applies". That is why this returns an
     * {@link OptionalDouble} rather than a number with a magic default: the two cases produce
     * different motion, and a caller that cannot tell them apart will eventually assert one and
     * observe the other.</p>
     *
     * @param entity entity to query; a null or untracked entity answers empty
     * @return the multiplier set by {@link #setGravityMultiplier}, or empty after
     *         {@link #clearGravityEffect} or if none was ever set
     */
    OptionalDouble gravityMultiplier(Entity entity);
}
