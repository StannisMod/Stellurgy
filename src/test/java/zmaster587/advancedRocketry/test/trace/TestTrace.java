package zmaster587.advancedRocketry.test.trace;

import java.util.List;
import java.util.Locale;

import net.minecraft.entity.Entity;

import zmaster587.advancedRocketry.command.test.TestEventLog;

/**
 * The sink a test-only mixin writes an observation into, and the side routing that picks which
 * event log receives it.
 *
 * <h2>Why a router is needed at all</h2>
 *
 * <p>There are two event logs, one per side, because cross-side ordering within a tick is undefined
 * and shipping client records to the server would lose exactly the records a relog test needs. The
 * server half lives in the mod's probe ({@link TestEventLog}) and is registered under
 * {@code -Dadvancedrocketry.tests=true}; the client half lives in the harness bootstrap and is
 * enabled by the harness's own coremod. A mixin runs on whichever side the world it is looking at
 * belongs to, so it cannot pick a log at compile time — it asks here.</p>
 *
 * <h2>Both halves self-gate</h2>
 *
 * <p>Neither sink needs to be asked whether it is recording: each drops the record when nothing
 * subscribed. What must never be lost is the DIFFERENCE — "nothing happened" and "nobody was
 * listening" are separate answers, and both logs report their own {@code recording} flag on every
 * read.</p>
 *
 * <h2>This class never reaches production</h2>
 *
 * <p>It is in the test source set and referenced only from test mixins, which are queued only by
 * the harness coremod. A released jar carries none of it.</p>
 */
public final class TestTrace {

    private TestTrace() {}

    /**
     * Record one observation into the log belonging to {@code entity}'s side.
     *
     * @param payload a JSON fragment WITHOUT braces, or empty
     */
    /**
     * Announce that an observation point EXECUTED, before it decides whether it has anything to say.
     *
     * <p>Call it first thing, above every threshold and condition. What it buys is the difference
     * between "nothing happened" and "nobody was looking" — three separate silences (a mixin that
     * never wove, one that wove but whose method never ran, one that ran and saw nothing) otherwise
     * produce the same empty reply, and the first two read as the third. Measured 2026-08-23: three
     * runs and one wrong ledger entry were spent on exactly that confusion.</p>
     *
     * <p>Routed by side like {@link #record}, and deliberately NOT gated on the log recording: the
     * fact that code ran is worth keeping even when nothing is subscribed to hear about it.</p>
     */
    public static void instrument(Entity entity, String name) {
        if (entity != null && entity.world != null && entity.world.isRemote) {
            com.github.stannismod.forge.testing.client.bridge.ForgeTestClientBootstrap
                    .noteInstrumentEntered(name);
        } else {
            TestEventLog.noteInstrumentEntered(name);
        }
    }

    /**
     * The side-explicit forms, for an observation point that has no entity to route by.
     *
     * <p>Some of the code worth watching is pure geometry — a sweep takes boxes and vectors and knows
     * nothing about a world. Forge's effective side answers for the calling thread, which is what the
     * router needs and all it needs. Kept separate from the entity-routed pair rather than folded into
     * it: where an entity IS available its world is the exact answer, and a thread-derived one would be
     * a silent approximation of it.</p>
     */
    public static void instrumentHere(String name) {
        if (isRemoteThread()) {
            com.github.stannismod.forge.testing.client.bridge.ForgeTestClientBootstrap
                    .noteInstrumentEntered(name);
        } else {
            TestEventLog.noteInstrumentEntered(name);
        }
    }

    /** {@link #record} for an observation point with no entity — see {@link #instrumentHere}. */
    public static void recordHere(String type, String payload) {
        if (isRemoteThread()) {
            com.github.stannismod.forge.testing.client.bridge.ForgeTestClientBootstrap
                    .recordEvent(type, payload);
        } else {
            TestEventLog.record("server", 0L, type, payload);
        }
    }

    private static boolean isRemoteThread() {
        return net.minecraftforge.fml.common.FMLCommonHandler.instance().getEffectiveSide()
                == net.minecraftforge.fml.relauncher.Side.CLIENT;
    }

    public static void record(Entity entity, String type, String payload) {
        if (entity.world.isRemote) {
            com.github.stannismod.forge.testing.client.bridge.ForgeTestClientBootstrap
                    .recordEvent(type, payload);
        } else {
            TestEventLog.record("server", entity.world.getTotalWorldTime(), type, payload);
        }
    }

    /**
     * The nearest game-code frames above this call — the CALLER, which for a position write or a
     * dismount is exactly the writer an arrival timeline exists to name. Infrastructure frames (the
     * JRE, the event bus, this class, the mixin's own synthetic method) are skipped; the rest keep
     * {@code Class.method:line} so a vanilla writer and a mod writer are distinguishable at a
     * glance.
     *
     * <p>Walking the stack is expensive, so it is only ever called on a record that is actually
     * being kept — never on the path that filters one out.</p>
     */
    public static String callerTrail() {
        StringBuilder sb = new StringBuilder();
        int kept = 0;
        for (StackTraceElement f : Thread.currentThread().getStackTrace()) {
            String c = f.getClassName();
            // A mixin's methods are MERGED into the target, so they arrive on the stack as frames of
            // the target class and cannot be filtered by package. Nor by an exact name: mixin
            // rewrites an injected handler to `handler$<hash>$<owner>$<ourName>`, so the marker every
            // method here carries has to be matched anywhere in the name, not only at its start.
            if (c.startsWith("java.") || c.startsWith("sun.")
                    || f.getMethodName().contains("arTest$")
                    || c.startsWith("zmaster587.advancedRocketry.test.trace")
                    || c.contains("EventBus") || c.contains("ForgeEventFactory")
                    || c.contains("ASMEventHandler")) {
                continue;
            }
            if (kept > 0) {
                sb.append(" < ");
            }
            sb.append(c.substring(c.lastIndexOf('.') + 1))
                    .append('.').append(f.getMethodName()).append(':').append(f.getLineNumber());
            if (++kept >= 5) {
                break;
            }
        }
        return sb.toString();
    }

    /** One decimal place — enough to see a metre, short enough to read a whole chain on one line. */
    public static String fmt(double v) {
        // SIX SIGNIFICANT FIGURES, not one decimal place — the precision is part of what an
        // instrument measures.
        //
        // This was "%.1f", which suits a distance in blocks and destroys anything smaller. An angle
        // is measured in hundredths of a radian: a pose turning 0.05 rad in a tick and one turning
        // 0.1 both printed as "0.1", and a trace built to tell a rotational LURCH from a rotational
        // LAG could not resolve either. The first reading taken with it showed every column
        // quantised to the same tenth — which is how a formatter announces that it, and not the
        // subject, is what the numbers describe.
        //
        // Significant figures rather than decimals, because the same call renders 0.0004 rad and
        // 1 537 600 blocks.
        return String.format(Locale.ROOT, "%.6g", v);
    }

    /** Compact entity-id list, for tagging which passengers a mount carried through a write. */
    public static String ids(List<Entity> entities) {
        StringBuilder sb = new StringBuilder("[");
        for (Entity e : entities) {
            if (sb.length() > 1) {
                sb.append(',');
            }
            sb.append(e.getEntityId());
        }
        return sb.append(']').toString();
    }

    /** JSON-safe: the trails and names recorded here carry no quotes, but a payload must not lie. */
    public static String json(String raw) {
        return raw == null ? "" : raw.replace('\\', '/').replace('"', '\'');
    }
}
