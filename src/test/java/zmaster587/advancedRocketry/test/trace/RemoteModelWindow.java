package zmaster587.advancedRocketry.test.trace;

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
 * <p><b>The one live field, and why it is public.</b> {@link #samples} is polled while the window is
 * open — a scenario waits for the subject to be drawn at all before it starts measuring, and that
 * wait needs a value it can watch grow. Everything else is read off the closing record.</p>
 *
 * <p><b>What it is, said plainly.</b> Mutable static state, per client JVM, shared by every scenario
 * in a shared-harness class — as the production fields it replaces were. The difference is that it
 * is no longer in shipped code, and that a window must be OPENED before it means anything.</p>
 *
 * <p>Client render thread only. Test source set: absent from a released jar.</p>
 */
public final class RemoteModelWindow {

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

    /** Decisions about a remote body since {@link #open()} — public because it is POLLED while the
     *  window is open; see the class note. */
    private static long samples;

    private static long calls;
    private static long rotated;
    private static double maxDeg;
    private static String trace = "";

    /** Start a window. Invoked from a test through the harness's static-invoke bridge. */
    public static int open() {
        calls = 0;
        samples = 0;
        rotated = 0;
        maxDeg = 0.0;
        trace = "";
        return 0;
    }

    /** End the window and record its summary as {@code remote_model_window}; returns the decisions
     *  it saw about remote bodies. */
    public static int close() {
        return record();
    }

    /** Write the window's numbers as they stand, without ending it — for a reader polling for the
     *  first sample. Same record type as {@link #close()}: the reader asks for the last one in its
     *  own window either way, and a different type would make "the last reading" depend on which
     *  call produced it. */
    public static int peek() {
        return record();
    }

    private static int record() {
        TestTrace.recordHere("remote_model_window", String.format(Locale.ROOT,
                "\"calls\":%d,\"samples\":%d,\"rotated\":%d,\"maxDeg\":%.2f"
                        + ",\"modelGateInstalled\":%d,\"trace\":\"%s\"",
                calls, samples, rotated, maxDeg, modelGateInstalledFlag, TestTrace.json(trace)));
        return (int) samples;
    }

    /**
     * One decision, from the gate's own return value.
     *
     * @param entity the body the gate was asked about
     * @param local  whether that body is this client's own player — decided by the caller, which
     *               has the client at hand; a window class must not reach for {@code Minecraft}
     * @param rotation the axis-angle the gate returned, or null for no/identity rotation
     */
    public static void sample(EntityLivingBase entity, boolean local, double[] rotation) {
        calls++;
        if (entity == null || local) {
            return;
        }
        samples++;
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
