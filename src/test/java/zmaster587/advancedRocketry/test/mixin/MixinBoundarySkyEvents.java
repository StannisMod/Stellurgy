package zmaster587.advancedRocketry.test.mixin;

import java.util.List;

import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.WorldClient;
import net.minecraft.client.renderer.BufferBuilder;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import zmaster587.advancedRocketry.client.render.planet.BoundarySky;
import zmaster587.advancedRocketry.network.PacketSystemBodiesSync;
import zmaster587.advancedRocketry.test.trace.TestTrace;

/**
 * What the slot-world sky renderer DREW on a frame, as a client event: {@code sky_frame_drawn}.
 *
 * <h2>The fact it observes</h2>
 *
 * <p>{@link BoundarySky#render} is the whole sky of a settled tier-2 ship: the backdrop (clouds and
 * the starfield), one descent-boundary ring per body that has a shell, and one billboard per synced
 * body, with a label under it when labels are on. Each of those is a call production makes and a
 * verdict production returns — {@code drawBackdrop} answers how many clouds it emitted,
 * {@code drawBoundary} whether it drew a ring, {@code drawBody} whether it wrote a label — and the
 * event is those verdicts added up over ONE frame, published at the frame's end. It records on the
 * CLIENT only: the class exists on no other side.</p>
 *
 * <h2>Payload</h2>
 *
 * <p>{@code dim} — the dimension the frame was drawn in, off the frame's own {@code WorldClient};
 * {@code bodies} — billboards attempted (one {@code drawBody} call per synced body);
 * {@code nebulae} — clouds the backdrop emitted; {@code boundaries} — rings drawn;
 * {@code labels} — labels written; {@code edge} — why THIS frame was recorded (below).</p>
 *
 * <h2>An edge, not a heartbeat</h2>
 *
 * <p>The seam runs every frame, and a per-frame record would turn the ring over in seconds and bury
 * every other chain's readout under sky frames. A record is therefore taken only when the frame's
 * tuple ({@code dim, bodies, nebulae, boundaries, labels}) DIFFERS from the last one recorded
 * ({@code "edge":"changed"}), or when the renderer RESUMED after not having drawn for more than
 * {@link #RESUME_GAP_TICKS} of the client world's clock, which is the frame on which the sky pass
 * came back after being gated off, e.g. by the render distance ({@code "edge":"resumed"}). The first
 * frame a world's renderer draws is a resume. The gap threshold is
 * a guess about how a hitch differs from an off-renderer and is confirmed only on a run.</p>
 *
 * <h2>What it is SILENT about</h2>
 *
 * <ul>
 * <li>A hyperspace frame. {@code render} takes an early return there (the corridor is drawn
 *     instead, {@code HyperspaceTunnel.render}), and this mixin injects at TAIL — the single final
 *     return — so a transit frame never records. {@code MixinHyperspaceTunnelDiag} is the corridor's
 *     witness.</li>
 * <li>A steady frame. Two identical frames in a row produce ONE record; "the renderer drew a frame
 *     since my mark" in a steady sky is not a question this event answers — the frame count
 *     {@code MixinBoundarySkyDiag} feeds into an open {@code RenderFrameWindow} is, and the
 *     instrument {@code sky_frame_events} says the seam has run at all.</li>
 * <li>A shared client whose new scenario draws exactly the sky the previous one ended in, in the
 *     SAME world, without the renderer ever pausing: no change, no resume, no record. The
 *     last-recorded tuple is a field of the renderer — one per slot world, since each world's
 *     provider builds its own — so a new world starts from a resume, and the same world carries its
 *     memory across a scenario boundary. A resume is a hint, never a premise: what a test asserts on
 *     is the tuple, and {@code edge} only says why the record was kept.</li>
 * <li>Whether a body was VISIBLE. {@code drawBody} returns whether a LABEL was written, so a body
 *     drawn with labels off counts in {@code bodies} and not in {@code labels}; a body so close its
 *     bearing degenerates ({@code len < 1e-6}) counts in {@code bodies} though nothing was
 *     emitted for it. Pixels are not observed here at all.</li>
 * </ul>
 *
 * <p>Sits BESIDE {@code MixinBoundarySkyDiag}, which counts frames into an open
 * {@code RenderFrameWindow}; neither needs the other.</p>
 */
@Mixin(BoundarySky.class)
public abstract class MixinBoundarySkyEvents {

    private static final String INSTRUMENT = "sky_frame_events";

    /** Client-world ticks without a drawn frame after which the next frame is a RESUME, not a hitch. */
    private static final long RESUME_GAP_TICKS = 20L;

    // Fields of THIS renderer — one per slot world, built by that world's provider — so the memory
    // lives and dies with the world whose sky it describes.

    // In-flight tallies for the frame being drawn right now; reset at render HEAD, read at TAIL.
    @Unique
    private int arTest$bodiesThisFrame;
    @Unique
    private int arTest$nebulaeThisFrame;
    @Unique
    private int arTest$boundariesThisFrame;
    @Unique
    private int arTest$labelsThisFrame;

    // The last tuple that was recorded, so a steady sky records nothing.
    @Unique
    private boolean arTest$everRecorded;
    @Unique
    private int arTest$lastDim;
    @Unique
    private int arTest$lastBodies;
    @Unique
    private int arTest$lastNebulae;
    @Unique
    private int arTest$lastBoundaries;
    @Unique
    private int arTest$lastLabels;

    // When this renderer last reached TAIL, on its world's clock — the resume edge.
    @Unique
    private long arTest$lastFrameTime;

    @Inject(method = "render", at = @At("HEAD"))
    private void arTest$frameBegun(float partialTicks, WorldClient world, Minecraft mc,
                                   CallbackInfo ci) {
        TestTrace.instrumentHere(INSTRUMENT);
        arTest$bodiesThisFrame = 0;
        arTest$nebulaeThisFrame = 0;
        arTest$boundariesThisFrame = 0;
        arTest$labelsThisFrame = 0;
    }

    @Inject(method = "drawBackdrop", at = @At("RETURN"))
    private void arTest$backdropDrawn(List<PacketSystemBodiesSync.RenderNebula> clouds,
                                      CallbackInfoReturnable<Integer> cir) {
        arTest$nebulaeThisFrame = cir.getReturnValue();
    }

    @Inject(method = "drawBoundary", at = @At("RETURN"))
    private void arTest$boundaryDrawn(BufferBuilder buffer,
                                      PacketSystemBodiesSync.RenderBody body,
                                      CallbackInfoReturnable<Boolean> cir) {
        // RETURN fires at every exit, and the early ones answer false: read the verdict, not the fact
        // of returning.
        if (cir.getReturnValue()) {
            arTest$boundariesThisFrame++;
        }
    }

    @Inject(method = "drawBody", at = @At("RETURN"))
    private void arTest$bodyDrawn(BufferBuilder buffer,
                                  PacketSystemBodiesSync.RenderBody body, boolean labels,
                                  CallbackInfoReturnable<Boolean> cir) {
        arTest$bodiesThisFrame++;
        if (cir.getReturnValue()) {
            arTest$labelsThisFrame++;
        }
    }

    /**
     * TAIL, not RETURN: what a frame drew is final only once the frame is, and the hyperspace exit
     * above the body loops is deliberately not a sky frame.
     */
    @Inject(method = "render", at = @At("TAIL"))
    private void arTest$frameEnded(float partialTicks, WorldClient world, Minecraft mc,
                                   CallbackInfo ci) {
        if (world == null || world.provider == null) {
            return;
        }
        int dim = world.provider.getDimension();
        long now = world.getTotalWorldTime();

        boolean resumed = !arTest$everRecorded
                || now - arTest$lastFrameTime > RESUME_GAP_TICKS
                || now < arTest$lastFrameTime;
        arTest$lastFrameTime = now;

        boolean changed = !arTest$everRecorded || dim != arTest$lastDim
                || arTest$bodiesThisFrame != arTest$lastBodies
                || arTest$nebulaeThisFrame != arTest$lastNebulae
                || arTest$boundariesThisFrame != arTest$lastBoundaries
                || arTest$labelsThisFrame != arTest$lastLabels;
        if (!changed && !resumed) {
            return;
        }

        arTest$everRecorded = true;
        arTest$lastDim = dim;
        arTest$lastBodies = arTest$bodiesThisFrame;
        arTest$lastNebulae = arTest$nebulaeThisFrame;
        arTest$lastBoundaries = arTest$boundariesThisFrame;
        arTest$lastLabels = arTest$labelsThisFrame;

        TestTrace.recordHere("sky_frame_drawn", "\"dim\":" + dim
                + ",\"bodies\":" + arTest$bodiesThisFrame
                + ",\"nebulae\":" + arTest$nebulaeThisFrame
                + ",\"boundaries\":" + arTest$boundariesThisFrame
                + ",\"labels\":" + arTest$labelsThisFrame
                + ",\"edge\":\"" + (changed ? "changed" : "resumed") + "\"");
    }
}
