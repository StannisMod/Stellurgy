package dev.stannismod.stellurgy.libvulpes.util;

/**
 * Whether a player is holding the jetpack's thrust key — held by the player itself, on whichever
 * side that player object lives. Implemented on {@code EntityPlayer} by a mixin; a player is always
 * one.
 */
public interface JetpackKeys {

    boolean stellurgy$isSpaceDown();

    void stellurgy$setSpaceDown(boolean down);
}
