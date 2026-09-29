package zmaster587.advancedRocketry.test.trace;

import java.util.List;
import java.util.Locale;

import net.minecraft.entity.EntityLivingBase;

/**
 * What the model-rotation gate decided, over a window a test opens and closes.
 *
 * <p><b>The question.</b> A body standing on a rolled deck must be DRAWN ship-aligned; a body on
 * world terrain beside that ship must not. Every such decision is
 * {@code ShipFrameCamera.modelRotationFor}, which takes the body and returns the rotation, so
 * watching that method sees the decisions with the same numbers production used to accumulate — and
 * the argument is what lets the local player be told from a remote body.</p>
 *
 * <p><b>Three counts, because three different zeroes.</b> {@code calls} is the gate being reached at
 * all: {@code MixinRenderLivingBaseShipRoll} is {@code require = 0}, so a render mod that rewrites
 * {@code applyRotations} disables the whole feature without a word, and zero calls means there is no
 * gate rather than a gate that declined. {@code samples} is decisions about a body that is NOT this
 * client's own player: calls without samples means the hook ran and nothing but the local player was
 * drawn — the subject never reached the screen. {@code rotated} is the decisions that pushed a
 * non-identity rotation, which is the thing under test. A bare "rotated == 0" cannot tell the three
 * apart, and each is a different bug.</p>
 *
 * <p><b>Why an accumulator and not a record per decision.</b> The gate is consulted once per drawn
 * body per frame. A record each would turn its own 256-deep ring over in a second or two and a
 * reader asking about a sixty-tick window would be reading the tail. The window's SUMMARY is the
 * record, written once at {@link #close()}.</p>
 *
 * <p><b>A mid-window reading is a RECORD.</b> {@link #peek(int)} writes the window's numbers without
 * ending it. A field read across the socket cannot be attributed to a moment, so "the client did not
 * answer" and "the gate has decided nothing yet" would arrive as the same zero. And the window's
 * first decision about each remote body is an edge of its own, {@code remote_model_first_sample}
 * carrying the body's id — what a scenario waits for, for ITS subject, before it starts
 * measuring.</p>
 *
 * <p><b>Whose it is.</b> An instance per window, created by the scenario that asks, registered with
 * the client's {@link SideTrace} and addressed by the handle {@link #open()} returned. The gate feeds
 * every open window; two scenarios never share a count.</p>
 *
 * <p>Client render thread only. Test source set: absent from a released jar.</p>
 */
public final class RemoteModelWindow implements TraceWindow {

    private RemoteModelWindow() {}

    /**
     * Whether the model-roll hook is actually WOVEN into {@code RenderLivingBase} right now:
     * {@code 1} installed, {@code 0} absent.
     *
     * <p>{@code MixinRenderLivingBaseShipRoll} is declared {@code require = 0} so that a render mod
     * rewriting {@code applyRotations} cannot abort the whole mixin config. The price is that a miss
     * — an ordinal drift, a competing transformer, a mapping change — is completely SILENT: the
     * feature is simply gone and every symptom looks like "nothing was drawn". This asks the
     * TRANSFORMED CLASS ITSELF, so the answer survives a deleted harness log and a scenario can
     * separate "the gate decided not to rotate" from "there is no gate".</p>
     *
     * <p>It used to be a {@code public static final int} on {@code ShipFrameCamera}, computed at
     * class-init and read by exactly one scenario over the socket. Nothing in production called it.
     * It is a question about the woven bytecode, not about production's logic, so asking it here is
     * a read and not a re-derivation — and it is evaluated once, on first touch, rather than on
     * every client's class-init.</p>
     */
    private static final int modelGateInstalledFlag = probeModelGate();

    private static int probeModelGate() {
        try {
            for (java.lang.reflect.Method m
                    : net.minecraft.client.renderer.entity.RenderLivingBase.class
                            .getDeclaredMethods()) {
                if (m.getName().contains("rollWithShip")) {
                    return 1;
                }
            }
            return 0;
        } catch (Throwable t) {
            return 0;
        }
    }

    /** Trace budget in characters — the FAILING case names its first few subjects and then stops.
     *  A green run never appends at all; a red one diagnoses without unbounded churn. */
    private static final int TRACE_BUDGET = 400;

    /** Decisions about a remote body in this window. */
    private long samples;

    /** The remote bodies this window has already announced a first sample for. */
    private final java.util.Set<Integer> drawnBodies = new java.util.HashSet<Integer>();

    private long calls;
    private long rotated;
    private double maxDeg;
    private String trace = "";

    /** Start a window; answers its handle. Invoked from a test through the static-invoke bridge. */
    public static int open() {
        return SideTrace.client().open(new RemoteModelWindow());
    }

    /** End the window and record its summary as {@code remote_model_window}; returns the decisions
     *  it saw about remote bodies. */
    public static int close(int handle) {
        return SideTrace.client().close(handle, RemoteModelWindow.class).record();
    }

    /** Write the window's numbers as they stand, without ending it — for a reader polling for the
     *  first sample. Same record type as {@link #close(int)}: the reader asks for the last one in its
     *  own window either way, and a different type would make "the last reading" depend on which
     *  call produced it. */
    public static int peek(int handle) {
        return SideTrace.client().window(handle, RemoteModelWindow.class).record();
    }

    private int record() {
        TestTrace.recordHere("remote_model_window", String.format(Locale.ROOT,
                "\"calls\":%d,\"samples\":%d,\"rotated\":%d,\"maxDeg\":%.2f"
                        + ",\"modelGateInstalled\":%d,\"trace\":\"%s\"",
                calls, samples, rotated, maxDeg, modelGateInstalledFlag, TestTrace.json(trace)));
        return (int) samples;
    }

    /**
     * One decision, from the gate's own return value, to every open window.
     *
     * @param entity the body the gate was asked about
     * @param local  whether that body is this client's own player — decided by the caller, which
     *               has the client's player at hand
     * @param rotation the axis-angle the gate returned, or null for no/identity rotation
     */
    public static void sample(EntityLivingBase entity, boolean local, double[] rotation) {
        List<RemoteModelWindow> open = SideTrace.client().windows(RemoteModelWindow.class);
        for (int i = 0; i < open.size(); i++) {
            open.get(i).add(entity, local, rotation);
        }
    }

    private void add(EntityLivingBase entity, boolean local, double[] rotation) {
        calls++;
        if (entity == null || local) {
            return;
        }
        samples++;
        if (drawnBodies.add(entity.getEntityId())) {
            // An EDGE per body: the first decision this window made about THAT body. A scenario that
            // must not open its measurement before its own subject is on screen waits for the one
            // carrying the subject's id — a first sample of any body would be satisfied by a
            // neighbour's while the subject was culled.
            TestTrace.recordHere("remote_model_first_sample", "\"e\":" + entity.getEntityId());
        }
        if (rotation == null) {
            return; // the common (and correct) case
        }
        rotated++;
        if (rotation[0] > maxDeg) {
            maxDeg = rotation[0];
        }
        if (trace.length() < TRACE_BUDGET) {
            trace = trace + String.format(Locale.ROOT, "[%s@%.1f,%.1f,%.1f=%.0fdeg]",
                    entity.getName(), entity.posX, entity.posY, entity.posZ, rotation[0]);
        }
    }
}
