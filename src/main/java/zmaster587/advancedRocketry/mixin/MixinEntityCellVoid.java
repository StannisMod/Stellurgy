package zmaster587.advancedRocketry.mixin;

import net.minecraft.entity.Entity;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

import zmaster587.advancedRocketry.space.WorldProviderSpaceSlot;

/**
 * Inside a space cell, a negative Y is not the void — it is the lower half of the cell.
 *
 * <h2>What vanilla does, and why it does it</h2>
 *
 * <p>{@code Entity.onEntityUpdate} kills anything whose {@code posY} falls below -64. The number is
 * the world's floor plus a margin: an ordinary world's blocks start at 0, so a body below -64 has
 * fallen out of it and will never come back, and killing it is how vanilla keeps such a body from
 * falling forever. That reasoning is exactly right, and it is a statement about a world whose
 * contents sit at Y 0..255.</p>
 *
 * <h2>Why a cell world is not that world</h2>
 *
 * <p>A cell realizes ship and entity POSES across the whole cell, centred on the world origin, so
 * its canonical range is {@code [-HALF_CELL, HALF_CELL)} on every axis — sixteen million blocks
 * below the origin is the cell's own lower half, as ordinary a place to be as sixteen million
 * above. There is nothing to fall out of: a craft at negative Y is inside the cell, inside the
 * ledger, inside its slot world, and on its way somewhere.</p>
 *
 * <p>The alternative was to keep the band above zero by shifting it, which is what the mapping did
 * until 2026-09-11 — and it cost the top of every cell: shifted, Y ran to a full {@code CELL} above
 * the origin, past the 3.0e7 beyond which vanilla disconnects a player outright
 * ({@code NetHandlerPlayServer.isMovePlayerPacketInvalid}). A band that is centred fits under that
 * limit on every axis with the cell size unchanged, and this is the one guard that has to move for
 * it.</p>
 *
 * <h2>What this does NOT change</h2>
 *
 * <p>Only the void kill, and only in a cell slot world. Every other world keeps vanilla's floor
 * exactly — an ordinary planet, the hyperspace world, a station dimension. {@code outOfWorld} keeps
 * all its other callers: this redirect replaces one call site, the one inside the update tick, so a
 * body killed for any other reason still is.</p>
 */
@Mixin(Entity.class)
public abstract class MixinEntityCellVoid {

    @Shadow
    protected abstract void outOfWorld();

    /**
     * The receiver is the redirect's contract — Mixin hands an instance call's target to the
     * handler as its first argument — and here it is this same entity.
     */
    @Redirect(method = "onEntityUpdate",
            at = @At(value = "INVOKE", target = "Lnet/minecraft/entity/Entity;outOfWorld()V"))
    private void advancedRocketry$theCellHasNoVoid(Entity self) {
        if (self.world != null && self.world.provider instanceof WorldProviderSpaceSlot) {
            return;
        }
        outOfWorld();
    }
}
