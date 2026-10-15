package dev.stannismod.stellurgy.test.trace;

import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityLivingBase;
import net.minecraft.util.math.AxisAlignedBB;

import java.lang.ref.Reference;
import java.lang.reflect.Field;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import dev.stannismod.stellurgy.integration.vs.DeckBoarding;
import dev.stannismod.stellurgy.integration.vs.DeckHeld;
/**
 * What the client's deck frame decided about taking ONE named body, per tick, over a window a test
 * opens and closes.
 *
 * <h2>The question</h2>
 *
 * <p>"No deck took this body" is a NEGATIVE, and a negative taken once cannot say whether the deck
 * frame was asked, what it was asked about, or what it was answering from. A scenario whose subject is
 * a body no deck should take — a walker on the ground beside a hull — needs the deck frame to have
 * been ASKED about that body, tick after tick, before its "never taken" means anything; and a scenario
 * whose body is unexpectedly NOT taken needs the decisions of the ticks between the stimulus and the
 * read.</p>
 *
 * <h2>What it records</h2>
 *
 * <p>{@code deck_gate_explained}, once per world tick while the window is open, plus again within a
 * tick whenever the answer CHANGES. It is taken at the deck frame's admission decision
 * ({@code DeckFrameTick.admissionFor}), which runs on every update of a body no deck holds. Each record
 * carries what has to be read together and therefore has to be written together:</p>
 *
 * <ul>
 *   <li><b>{@code admitted}</b> — the decision's REAL return value on the call being observed: the
 *       craft whose deck takes the body, or {@code null};</li>
 *   <li><b>production's own breakdown</b>, every key of {@link DeckBoarding#explainDeckCapture}
 *       verbatim, including its {@code verdict}. That is a SECOND evaluation, deliberately kept beside
 *       the first: it asks the standing-support question its own way, so {@code admitted} and
 *       {@code verdict} disagreeing is a reading in its own right and not a defect in either;</li>
 *   <li><b>the second subject.</b> "No deck takes this body" is a RELATION, so the record names the
 *       ships as well as the body: every LOADED hull on this side with its world box ({@code ships},
 *       capped);</li>
 *   <li><b>the body's own hold stamp</b> and the pending deck seeds of its world — this body's own
 *       ({@code pendingSeed}) and how many that world holds in all ({@code pendingSeedsInWorld}).</li>
 * </ul>
 *
 * <h2>The CLIENT's decision only</h2>
 *
 * <p>The window is armed by ENTITY ID, on the side it is opened on, and the only side a test can open
 * one on is the client: the harness's static-invoke bridge runs in the client JVM, and the shared
 * client base boots a dedicated server in a second JVM. The server's half of this question has no
 * window.</p>
 *
 * <h2>What it is SILENT about</h2>
 *
 * <ul>
 *   <li><b>Everything outside the window</b>, and every tick on which a deck already HOLDS the body —
 *       admission is not asked then; the hold's own records ({@code deck_carry},
 *       {@code deck_released}) answer for those ticks.</li>
 *   <li><b>It does not say WHY {@code shipIdsAt} answered as it did</b>, only what it answered, and
 *       it cannot see a hull the substrate does not report as loaded at all.</li>
 *   <li><b>The pending seeds are read REFLECTIVELY</b> and a rename makes the read fail, not
 *       disappear: the failure is reported in the record's own {@code seedsRead} field.</li>
 * </ul>
 *
 * <p>An instance per window, created by the scenario that asks and registered with the client's
 * {@link SideTrace} under the handle {@link #open} returns. The cost is one
 * {@code explainDeckCapture} per tick for ONE body while open.</p>
 */
public final class DeckGateWindow implements TraceWindow {

    /** How many loaded hulls a record names before it stops; the count beside it says how many
     *  there were. */
    private static final int SHIP_BUDGET = 6;

    /** The body this window is asked about. */
    private final int entityId;

    /** How many records this window may write before it stops writing. */
    private final int recordBudget;

    private long lastTick = Long.MIN_VALUE;
    private String lastAdmitted;
    private long calls;
    private long records;

    private DeckGateWindow(int entityId, int recordBudget) {
        this.entityId = entityId;
        this.recordBudget = recordBudget;
    }

    /**
     * Arm a window for one body; answers its handle.
     *
     * <p><b>The budget is not a convenience and there is no overload that picks one.</b> A window
     * armed before a stimulus and left open records once a tick; the log's ring is 256 deep PER TYPE,
     * so an unbounded window over a scenario that runs for minutes would turn its own history over.
     * The caller states how many ticks of decisions its question is about, and when that many have
     * been written the window writes one FINAL record saying it stopped ({@code budgetExhausted}) and
     * then goes quiet. An instrument that runs out silently is indistinguishable from a subject that
     * went quiet.</p>
     *
     * @param entityId    the subject's entity id on the client
     * @param recordsEach how many records the window may write before it stops
     * @return the window's handle
     */
    public static int open(int entityId, int recordsEach) {
        DeckGateWindow w = new DeckGateWindow(entityId, recordsEach);
        int handle = SideTrace.client().open(w);
        w.summary("open");
        return handle;
    }

    /** Close the window and write its summary; answers how many records it wrote. */
    public static int close(int handle) {
        return SideTrace.client().close(handle, DeckGateWindow.class).summary("close");
    }

    /** Write the window's counters without closing it. Same record type as {@link #close}. */
    public static int peek(int handle) {
        return SideTrace.client().window(handle, DeckGateWindow.class).summary("peek");
    }

    private int summary(String phase) {
        // No `side` key — the envelope carries it, and a duplicate overwrites it.
        TestTrace.recordHere("deck_gate_window", String.format(Locale.ROOT,
                "\"phase\":\"%s\",\"e\":%d,\"calls\":%d,\"records\":%d,\"budget\":%d",
                phase, entityId, calls, records, recordBudget));
        return (int) records;
    }

    /**
     * One admission decision, from the injector on {@code DeckFrameTick.admissionFor}'s RETURN, to
     * every window open on this body on the side the decision was taken.
     *
     * @param entity   the body the deck frame was asked about
     * @param admitted what the decision returned for it on this call
     */
    public static void sample(Entity entity, String admitted) {
        if (entity == null || entity.world == null) {
            return;
        }
        List<DeckGateWindow> open = SideTrace.of(entity.world).windows(DeckGateWindow.class);
        for (int i = 0; i < open.size(); i++) {
            DeckGateWindow w = open.get(i);
            if (w.entityId == entity.getEntityId()) {
                w.decided(entity, admitted);
            }
        }
    }

    private void decided(Entity entity, String admitted) {
        calls++;
        long tick = entity.world.getTotalWorldTime();
        boolean same = admitted == null ? lastAdmitted == null : admitted.equals(lastAdmitted);
        if (tick == lastTick && same) {
            return;
        }
        lastTick = tick;
        lastAdmitted = admitted;
        int budget = recordBudget;
        if (records > budget) {
            return; // already said so, once, below
        }
        records++;
        if (records > budget) {
            TestTrace.record(entity, "deck_gate_explained", "\"e\":" + entity.getEntityId()
                    + ",\"worldTick\":" + tick
                    + ",\"calls\":" + calls + ",\"budgetExhausted\":true,\"budget\":" + budget);
            return;
        }
        TestTrace.record(entity, "deck_gate_explained", "\"e\":" + entity.getEntityId()
                + ",\"who\":\"" + TestTrace.json(entity.getName())
                // NOT `tick`, and NOT `side`: the log's ENVELOPE already carries both, and a payload
                // key that collides with one silently OVERWRITES it.
                + "\",\"worldTick\":" + tick
                + ",\"calls\":" + calls
                + ",\"admitted\":" + (admitted == null ? "null" : "\"" + TestTrace.json(admitted) + "\"")
                + ",\"bodyX\":" + TestTrace.fmt(entity.posX)
                + ",\"bodyY\":" + TestTrace.fmt(entity.posY)
                + ",\"bodyZ\":" + TestTrace.fmt(entity.posZ)
                + "," + explained(entity)
                + "," + shipsOn(entity)
                + "," + seeds(entity));
    }

    /** Production's own breakdown, flattened: every key {@link DeckBoarding#explainDeckCapture}
     *  returns, under its own name. */
    private static String explained(Entity entity) {
        if (!(entity instanceof EntityLivingBase)) {
            return "\"gateRead\":\"notLiving\"";
        }
        Map<String, Object> gate;
        try {
            gate = DeckBoarding.explainDeckCapture((EntityLivingBase) entity);
        } catch (Throwable t) {
            return "\"gateRead\":\"threw:" + TestTrace.json(String.valueOf(t)) + "\"";
        }
        if (gate == null) {
            return "\"gateRead\":\"null\"";
        }
        StringBuilder out = new StringBuilder("\"gateRead\":\"ok\"");
        for (Map.Entry<String, Object> e : gate.entrySet()) {
            out.append(',').append('"').append(TestTrace.json(e.getKey())).append("\":")
                    .append(value(e.getValue()));
        }
        return out.toString();
    }

    /**
     * Every loaded hull on the body's own side, with the world box the containment test uses — the
     * RELATION's second subject. Asked of the same source the containment test is asked of
     * ({@code ValkyrienUtils.getPhysosLoadedInWorld}, {@code PhysicsObject.getShipBB()}), which on the
     * client is the only one that answers at all.
     *
     * <p><b>Blind spot:</b> the printed box is the RAW hull box, while the containment test grows it
     * by a margin before asking. A body just outside a printed box may still be inside for the
     * decision's purposes; a body far outside all of them is not.</p>
     */
    private static String shipsOn(Entity entity) {
        StringBuilder out = new StringBuilder();
        int written = 0;
        int count = 0;
        try {
            for (org.valkyrienskies.mod.common.ships.ship_world.PhysicsObject physo
                    : org.valkyrienskies.mod.common.util.ValkyrienUtils
                            .getPhysosLoadedInWorld(entity.world)) {
                count++;
                if (written >= SHIP_BUDGET) {
                    continue;
                }
                if (written > 0) {
                    out.append(',');
                }
                AxisAlignedBB b = physo.getShipBB();
                out.append("{\"id\":\"")
                        .append(TestTrace.json(String.valueOf(physo.getShipData().getUuid())))
                        .append("\",\"box\":\"");
                if (b == null) {
                    out.append("null");
                } else {
                    out.append(TestTrace.fmt(b.minX)).append(',').append(TestTrace.fmt(b.minY))
                            .append(',').append(TestTrace.fmt(b.minZ)).append("..")
                            .append(TestTrace.fmt(b.maxX)).append(',').append(TestTrace.fmt(b.maxY))
                            .append(',').append(TestTrace.fmt(b.maxZ));
                }
                out.append("\"}");
                written++;
            }
        } catch (Throwable t) {
            return "\"shipsRead\":\"threw:" + TestTrace.json(String.valueOf(t)) + "\"";
        }
        return "\"shipsRead\":\"ok\",\"shipCount\":" + count + ",\"shipsShown\":" + written
                + ",\"ships\":[" + out + "]";
    }

    /**
     * The body's hold stamp and its world's pending deck seeds, as they stand at this decision. A
     * read that could not be made is named in {@code seedsRead} rather than left out.
     */
    private static String seeds(Entity entity) {
        StringBuilder missing = new StringBuilder();
        Map<?, ?> seeds = pendingSeedsOf(entity, missing);
        Object seed = seeds == null ? null : seeds.get(entity);
        long stamp = entity instanceof DeckHeld ? ((DeckHeld) entity).stellurgy$holdStamp() : -1L;
        return "\"seedsRead\":\"" + (missing.length() == 0 ? "ok" : missing.toString()) + "\""
                + ",\"holdStamp\":" + stamp
                + ",\"pendingSeedsInWorld\":" + (seeds == null ? "null" : String.valueOf(seeds.size()))
                + ",\"pendingSeed\":" + (seed == null ? "null" : seedOf(seed));
    }

    /**
     * The pending-seed index of {@code entity}'s world, read without creating it: a world nobody
     * seeded has no such part, and that reads as an empty index, not as a failure. Reflective because
     * both the part and the runtime's part map are private to production.
     */
    private static Map<?, ?> pendingSeedsOf(Entity entity, StringBuilder missing) {
        try {
            Field capField = dev.stannismod.stellurgy.world.WorldRuntime.class.getDeclaredField("capability");
            capField.setAccessible(true);
            @SuppressWarnings("unchecked")
            net.minecraftforge.common.capabilities.Capability<Object> cap =
                    (net.minecraftforge.common.capabilities.Capability<Object>) capField.get(null);
            Object runtime = cap == null ? null : entity.world.getCapability(cap, null);
            if (runtime == null) {
                missing.append(missing.length() == 0 ? "" : "+").append("noWorldRuntime");
                return null;
            }
            Field partsField = runtime.getClass().getDeclaredField("parts");
            partsField.setAccessible(true);
            Class<?> partType = Class.forName(DeckBoarding.class.getName() + "$PendingSeeds");
            Object part = ((Map<?, ?>) partsField.get(runtime)).get(partType);
            if (part == null) {
                return java.util.Collections.emptyMap();
            }
            Field byBody = partType.getDeclaredField("byBody");
            byBody.setAccessible(true);
            return (Map<?, ?>) byBody.get(part);
        } catch (Throwable t) {
            missing.append(missing.length() == 0 ? "" : "+").append("threw:pendingSeeds");
            return null;
        }
    }

    /** The pending seed's own fields, read reflectively ({@code PendingSeed} is a private nested
     *  class), including the body it names — a seed pointing at another body is visible in one
     *  field. */
    private static String seedOf(Object seed) {
        StringBuilder out = new StringBuilder("{");
        appendSeedField(out, seed, "shipId", true);
        appendSeedField(out, seed, "ticksLeft", false);
        appendSeedField(out, seed, "epoch", false);
        appendSeedField(out, seed, "restore", false);
        out.append(",\"body\":");
        try {
            Field bodyField = seed.getClass().getDeclaredField("body");
            bodyField.setAccessible(true);
            Object ref = bodyField.get(seed);
            Object body = ref instanceof Reference ? ((Reference<?>) ref).get() : null;
            if (body instanceof Entity) {
                Entity e = (Entity) body;
                out.append("\"").append(TestTrace.json(e.getName())).append('#')
                        .append(e.getEntityId()).append('"');
            } else {
                // A cleared WeakReference is NOT the same as no seed: the seed is still installed.
                out.append("\"collected\"");
            }
        } catch (Throwable t) {
            out.append("\"unreadable\"");
        }
        return out.append('}').toString();
    }

    private static void appendSeedField(StringBuilder out, Object seed, String name, boolean first) {
        if (!first) {
            out.append(',');
        }
        out.append('"').append(name).append("\":");
        try {
            Field f = seed.getClass().getDeclaredField(name);
            f.setAccessible(true);
            out.append(value(f.get(seed)));
        } catch (Throwable t) {
            out.append("\"unreadable\"");
        }
    }

    /** One value as JSON. Numbers stay numbers: a number answered through a quoted matcher is
     *  indistinguishable from absent. */
    private static String value(Object v) {
        if (v == null) {
            return "null";
        }
        if (v instanceof Boolean || v instanceof Integer || v instanceof Long) {
            return String.valueOf(v);
        }
        if (v instanceof Double || v instanceof Float) {
            return TestTrace.fmt(((Number) v).doubleValue());
        }
        if (v instanceof Iterable) {
            StringBuilder out = new StringBuilder("[");
            boolean first = true;
            for (Object o : (Iterable<?>) v) {
                if (!first) {
                    out.append(',');
                }
                out.append(value(o));
                first = false;
            }
            return out.append(']').toString();
        }
        return "\"" + TestTrace.json(String.valueOf(v)) + "\"";
    }
}
