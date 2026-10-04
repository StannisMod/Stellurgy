package dev.stannismod.stellurgy.test.trace;

import net.minecraft.entity.Entity;

import dev.stannismod.stellurgy.integration.vs.ShipFrameTravel;
import dev.stannismod.stellurgy.integration.vs.VSIntegration;

/**
 * A fixed deck point of the current aboard episode and its world image at the last two client ticks —
 * the "where the deck is" reference {@link FrameStepWindow} measures the body's RELATIVE motion
 * against.
 *
 * <p>The point is the subspace position the body held on the episode's first engaged tick, so it never
 * moves in the ship frame; its world image is taken once per client tick, the same rate production
 * samples the ship at, so the reference costs the frame nothing. It used to live in production's
 * deck look, which held it only for this measurement.</p>
 *
 * <p>The client's, kept in its {@link SideTrace} ({@link #client()}): created with the client and
 * released with it. Client thread only: written from the deck-look tick recorder, read from the render
 * probe. Test source set.</p>
 */
public final class DeckReference {

    private boolean set;
    private double subX, subY, subZ;
    private double[] worldPrev;
    private double[] worldCur;

    /** The client's reference. */
    public static DeckReference client() {
        return SideTrace.client().memory(DeckReference.class, DeckReference::new);
    }

    /** One engaged tick of {@code player}'s deck look: anchor the point if new, sample its image. */
    public void tick(Entity player) {
        String shipId = ShipFrameTravel.aboardShipId(player);
        if (!set) {
            double[] sub = VSIntegration.toShipFrameFor(player.world, shipId, player.posX, player.posY, player.posZ);
            if (sub != null) {
                subX = sub[0];
                subY = sub[1];
                subZ = sub[2];
                set = true;
            }
        }
        if (set) {
            double[] world = VSIntegration.toWorldFrameFor(player.world, shipId, subX, subY, subZ);
            if (world != null) {
                worldPrev = worldCur == null ? world : worldCur;
                worldCur = world;
            }
        }
    }

    /** The deck look is not engaged: the episode, and its reference, are over. */
    public void clear() {
        set = false;
        worldPrev = null;
        worldCur = null;
    }

    /** The reference point's world position this frame, lerped between the tick samples, or
     *  {@code null} while no episode holds one. */
    public double[] worldAt(float partialTicks) {
        double[] cur = worldCur;
        double[] prev = worldPrev;
        if (cur == null || prev == null) {
            return null;
        }
        return new double[]{
                prev[0] + (cur[0] - prev[0]) * partialTicks,
                prev[1] + (cur[1] - prev[1]) * partialTicks,
                prev[2] + (cur[2] - prev[2]) * partialTicks};
    }
}
