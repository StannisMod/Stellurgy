package zmaster587.advancedRocketry.test.mixin;

import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import com.github.stannismod.affs.te.TileEntityFieldGenerator;
import com.github.stannismod.affs.world.FieldFrame;
import com.github.stannismod.affs.world.ShipFieldFrame;

import zmaster587.advancedRocketry.test.trace.TestTrace;

/**
 * A shield emitter's FRAME — standalone or on one VS ship, and whether that frame currently resolves —
 * as an event, on whichever side the emitter ticks.
 *
 * <h2>The event</h2>
 *
 * <p>{@code field_frame_resolved} — RETURN of the emitter's private {@code resolveFieldFrame()}, the one
 * place its {@code fieldFrame} is written (from {@code onLoad} once, then every tick from
 * {@code update}, on both sides). The record is read off the shadowed field the method just assigned:
 * {@code shipFramed} is whether the frame is a {@code ShipFieldFrame} (production's own
 * {@code isShipFramed()} test), {@code shipId} that frame's ship id (empty standalone), {@code ready}
 * the frame's {@code isReady()} (production's own {@code isFrameReady()} read — always true standalone,
 * and on a ship only while the ship is loaded on THIS side; a not-ready emitter contributes no shell).
 * {@code pos} is the emitter's block position — SUBSPACE coordinates when it sits on a ship.</p>
 *
 * <p>Every "wait for the shield to ride the ship" in the suite polled {@code isShipFramed()} on the tile
 * after each assembly and re-asked until it flipped; the flip is now an event with the frame it flipped
 * to, so an arrangement can await the emitter's adoption by the ship — or its readiness once the ship
 * loads on the client — instead of counting ticks.</p>
 *
 * <h2>An edge, not a state</h2>
 *
 * <p>The seam runs every tick per emitter, which would turn a 256-deep ring over in 13 s. So the record
 * is taken only when the triple {@code (shipFramed, shipId, ready)} DIFFERS from the previous resolution
 * of the same tile, kept in a private field on the mixin (one per tile instance). The first resolution
 * after the tile is constructed is always recorded — the previous value is unset — so a freshly loaded
 * emitter announces its first frame, and a re-resolution that changes nothing is silent.</p>
 *
 * <p>Routed by the calling thread's side ({@code recordHere}): a tile's {@code update} and
 * {@code onLoad} run on its world's own thread, so the client emitter's records land in the client log
 * and the server's in the server log; {@code remote} carries the world's own flag beside it so a
 * misroute would be readable.</p>
 *
 * <p>The target is a vendored class the project compiles, and its names are not vanilla's, so nothing
 * here may be SRG-remapped.</p>
 *
 * <h2>What this mixin is silent about</h2>
 *
 * <p>It does not say WHY a frame is not ready (VS absent, ship not yet loaded on this side, or the ship
 * gone) — {@code isReady()} folds those into one boolean and so does the record. It does not see the
 * ship's subspace claim being written; only the emitter's next resolution of it. It does not see the
 * frame being USED — the world centre, the shell velocity, the powered state and the active set are
 * downstream and unobserved here. And a tile that stops ticking (unloaded, removed) records no final
 * event: absence of a record is "no change seen", never "the frame is gone". Order between this and
 * any other event is measured on a run, never assumed here.</p>
 *
 * <p>Two silences worth naming because they read like the third. The field's CONSTRUCTION-time value
 * — the standalone world frame the declaration assigns — is not a resolution and is never recorded,
 * so the first record is the first RESOLUTION and may already differ from what the tile was born
 * with; and a tile whose {@code world} is still null never resolves at all ({@code update} returns
 * above the call), so it produces no record and no instrument note either. In both cases the
 * emitter HAS a frame that nothing here has said anything about.</p>
 */
@Mixin(value = TileEntityFieldGenerator.class, remap = false)
public abstract class MixinTileEntityFieldGeneratorEvents {

    private static final String INSTRUMENT = "field_frame_events";

    @Shadow
    private FieldFrame fieldFrame;

    /** The {@code (shipFramed, shipId, ready)} triple as of this tile's previous resolution; null until
     *  the first one. An instance field, one per emitter, merged into the target like the shadow. */
    private String arTest$lastFrameKey;

    @Inject(method = "resolveFieldFrame", at = @At("RETURN"), remap = false)
    private void arTest$frameResolved(CallbackInfo ci) {
        TestTrace.instrumentHere(INSTRUMENT);
        TileEntityFieldGenerator self = (TileEntityFieldGenerator) (Object) this;
        World world = self.getWorld();
        BlockPos pos = self.getPos();
        FieldFrame frame = fieldFrame;
        if (frame == null || pos == null) {
            return;
        }
        // The same reads production makes in isShipFramed() / isFrameReady(), off the field the method
        // just wrote — nothing re-resolved.
        boolean shipFramed = frame instanceof ShipFieldFrame;
        String shipId = shipFramed ? ((ShipFieldFrame) frame).getShipId() : null;
        boolean ready = frame.isReady();

        String key = shipFramed + "|" + shipId + "|" + ready;
        if (key.equals(arTest$lastFrameKey)) {
            return;
        }
        arTest$lastFrameKey = key;
        TestTrace.recordHere("field_frame_resolved", "\"pos\":\"" + pos.getX() + "," + pos.getY() + ","
                + pos.getZ() + "\",\"shipFramed\":" + shipFramed
                + ",\"shipId\":\"" + TestTrace.json(shipId) + "\""
                + ",\"ready\":" + ready
                + ",\"remote\":" + (world != null && world.isRemote));
    }
}
