package zmaster587.advancedRocketry.test.trace;

import java.lang.ref.Reference;
import java.lang.reflect.Field;
import java.util.Locale;
import java.util.Map;

import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityLivingBase;
import net.minecraft.util.math.AxisAlignedBB;

import zmaster587.advancedRocketry.integration.vs.ShipFrameTravel;

/**
 * What the deck-capture gate decided about ONE named body, per tick, on BOTH sides, over a window a
 * test opens and closes.
 *
 * <h2>The question, and why an existing record could not answer it</h2>
 *
 * <p>A body teleported to a ship's reported position + 4 is not taken by that ship's deck — but only
 * when a scenario ran in that world before it. The reading at the instant of the red is complete and
 * says nothing: {@code containingShipIds:[]}, {@code firstContactCandidate:null},
 * {@code supportedByWorldTerrain:false}, {@code verdict:false}. Every one of those is a NEGATIVE, and
 * a negative taken once cannot say whether the gate was asked, what it was asked about, or what it
 * was answering from.</p>
 *
 * <p><b>{@code deck_gate_decided} is the wrong record for it, and this class exists because that was
 * nearly not noticed.</b> That record is a HEARTBEAT: written on a verdict CHANGE and otherwise at
 * most once per hundred ticks ({@code MixinShipFrameTravelEvents.ARTEST_GATE_HEARTBEAT_TICKS}). The
 * failing window is the three or four ticks between the teleport and the capture read, which is far
 * shorter than the heartbeat, so a body whose verdict is STABLE across the scenario boundary — the
 * very thing under suspicion — writes nothing at all in it. An empty window then reads as "the frame
 * was never asked", which is a different finding entirely. The same record was chosen for the same
 * shape of question on 2026-09-08 and the choice was defended in a comment quoting its own javadoc;
 * it was about to be chosen a second time from a hand-off note.</p>
 *
 * <h2>What it records</h2>
 *
 * <p>{@code deck_gate_explained}, once per world tick per SIDE while the window is open, plus again
 * within a tick whenever {@code handled} CHANGES — three consumers ask {@code handles} in one tick
 * ({@code travel}, {@code jump}, {@code GravityHandler}) and they are not obliged to agree. Each
 * record carries three things that have to be read together and therefore have to be written
 * together:</p>
 *
 * <ul>
 *   <li><b>{@code handled}</b> — the gate's REAL return value on the call being observed;</li>
 *   <li><b>production's own breakdown</b>, every key of {@link ShipFrameTravel#explainHandles} verbatim,
 *       including its {@code verdict}. That is a SECOND evaluation, deliberately kept beside the
 *       first: {@code explainHandles} replicates the decision without the capture/release side
 *       effects, so {@code handled != verdict} is a reading in its own right and not a defect in
 *       either. It is also the entry point that documents which point it asks the support question
 *       at (the gate's own {@code gatePointFor}), which is why it is preferred to a hand-rolled
 *       invoker — this mixin already carried one that asked a NEIGHBOURING question for months;</li>
 *   <li><b>the second subject.</b> The verdict under examination is about a RELATION — "no ship holds
 *       this body" — so the record names the ships as well as the body: every LOADED hull on this
 *       side with its world box ({@code ships}, capped). {@code containingShipIds:[]} beside a box
 *       that does or does not cover the body's position is the difference between a geometry
 *       question and a registry question, and neither can be told from the other by the empty list
 *       alone.</li>
 * </ul>
 *
 * <p>And beside them, on the SAME record because two reads a tick apart attribute one to whatever
 * the other was: the four process-wide statics {@code ShipFrameTravel} holds
 * ({@code pendingSeed}, {@code CAPTURE_EPOCH}, {@code clientLookSource}, {@code walkTraceTicks}).
 * None of them names an owner or a release, and on an integrated server both sides share all four —
 * so "what the gate was answering FROM" is as much a part of this window as what it answered.</p>
 *
 * <h2>Both sides from one call, and why that works here</h2>
 *
 * <p>The window is armed by ENTITY ID, not by name or by side. A client test runs against an
 * INTEGRATED server — one JVM — and the client's copy of a player and the server's copy carry the
 * same entity id (it is what {@code Entity.equals} compares, which is why production's own capture
 * map is built with {@code weakKeys()} to keep the two apart). So one {@code open(id)} from the bot
 * arms both resolvers, and {@link TestTrace#record} routes each side's records to its own log by the
 * body's world. One window, both sides, same body.</p>
 *
 * <h2>What it is SILENT about</h2>
 *
 * <ul>
 *   <li><b>Everything outside the window.</b> Nothing is recorded while closed — that is the point,
 *       and it means this record can never answer a question about what happened BEFORE
 *       {@link #open}. A reader wanting the standing verdict reads {@code deck_gate_decided}, which
 *       is what a heartbeat is for.</li>
 *   <li><b>The two logs are separate sequences.</b> The client's records land in the client log and
 *       the server's in the server log; a reader takes a mark from each log for its own half and
 *       never carries one across.</li>
 *   <li><b>On a DEDICATED server the client half simply never fires</b> — there is no client in that
 *       JVM. That is not an error and not a gap in the arrangement; it is the shape of the tier.
 *       Nothing here opens a window on a dedicated server, because nothing can invoke it there.</li>
 *   <li><b>It does not say WHY {@code shipIdsAt} answered as it did</b>, only what it answered, and
 *       it cannot see a hull the substrate does not report as loaded at all.</li>
 *   <li><b>The statics are read REFLECTIVELY</b> and a rename makes the read fail, not disappear:
 *       the failure is reported in the record's own {@code staticsRead} field. An instrument that
 *       cannot see something says so.</li>
 * </ul>
 *
 * <h2>What it is, said plainly</h2>
 *
 * <p>Mutable static state, per JVM, shared by every scenario in a shared-harness class — and, on an
 * integrated server, by both sides at once. That is the same property the production fields it
 * observes have; what differs is that this one is in the test source set, absent from a released
 * jar, and means nothing until a window is OPENED.</p>
 *
 * <p>The cost is one {@code explainHandles} per tick per side for ONE body while open, and zero
 * otherwise — a closed window is an int compare on the gate's existing return hook.</p>
 */
public final class DeckGateWindow {

    private DeckGateWindow() {}

    /** No body: the window is closed. Entity ids are non-negative, so this cannot collide. */
    private static final int NOT_ARMED = -1;

    /** How many loaded hulls a record names before it stops. The failing arrangement holds one and
     *  the question is whether that one covers the body; a class whose world has filled up with
     *  craft is a different finding and the count below tells the reader which case this is. */
    private static final int SHIP_BUDGET = 6;

    /** The body both sides are asked about, or {@link #NOT_ARMED}. Volatile: the client and the
     *  server tick on different threads and both read it. */
    private static volatile int armedEntityId = NOT_ARMED;

    /** How many records each side may write before the window stops writing. Volatile for the same
     *  reason as the id above. */
    private static volatile int recordBudget = 0;

    /** Per SIDE, so the two never deduplicate each other: index 0 server, 1 client. */
    private static final long[] lastTick = {Long.MIN_VALUE, Long.MIN_VALUE};
    private static final boolean[] lastHandled = {false, false};
    private static final long[] calls = {0L, 0L};
    private static final long[] records = {0L, 0L};

    /**
     * Arm the window for one body, on both sides, and clear the counters.
     *
     * <p><b>The budget is not a convenience and there is no overload that picks one.</b> A window
     * armed before a stimulus and left open records once a tick per side; the log's ring is 256 deep
     * PER TYPE, so an unbounded window over a scenario that runs for minutes would turn its own
     * history over and hand a reader the tail of it — the exact failure the ring's own size makes
     * invisible. The caller states how many ticks of gate decisions its question is about, and when
     * that many have been written the window writes one FINAL record saying it stopped
     * ({@code budgetExhausted}) and then goes quiet. An instrument that runs out silently is
     * indistinguishable from a subject that went quiet.</p>
     *
     * @param entityId    the subject's entity id — the same number on the client and on the
     *                    integrated server; see the class note
     * @param recordsEach how many records each side may write before it stops
     * @return the id it armed, so a caller can see its own argument came back
     */
    public static int open(int entityId, int recordsEach) {
        synchronized (DeckGateWindow.class) {
            calls[0] = calls[1] = 0L;
            records[0] = records[1] = 0L;
            lastTick[0] = lastTick[1] = Long.MIN_VALUE;
            recordBudget = recordsEach;
            armedEntityId = entityId;
        }
        summary("open", entityId);
        return entityId;
    }

    /** Close the window and write its summary; answers how many records it wrote on the side the
     *  caller is on. */
    public static int close() {
        int was = armedEntityId;
        armedEntityId = NOT_ARMED;
        return summary("close", was);
    }

    /** Write the window's counters without closing it — for a reader checking the gate is being
     *  reached at all before it starts believing a silence. Same record type as {@link #close()}:
     *  a reader asks for the last one in its own window either way. */
    public static int peek() {
        return summary("peek", armedEntityId);
    }

    private static int summary(String phase, int entityId) {
        int side = sideHere();
        // No `side` key here either — the envelope carries it, and a duplicate overwrites it.
        TestTrace.recordHere("deck_gate_window", String.format(Locale.ROOT,
                "\"phase\":\"%s\",\"e\":%d,\"calls\":%d,\"records\":%d,\"budget\":%d",
                phase, entityId, calls[side], records[side], recordBudget));
        return (int) records[side];
    }

    /**
     * One gate decision, from the injector on {@code ShipFrameTravel.handles}' RETURN.
     *
     * @param entity  the body the gate was asked about
     * @param handled what the gate actually returned for it on this call
     */
    public static void sample(EntityLivingBase entity, boolean handled) {
        int armed = armedEntityId;
        if (armed == NOT_ARMED || entity == null || entity.world == null
                || entity.getEntityId() != armed) {
            return;
        }
        int side = entity.world.isRemote ? 1 : 0;
        calls[side]++;
        long tick = entity.world.getTotalWorldTime();
        // Once per tick per side, plus any answer that CHANGES inside a tick: three consumers ask
        // per tick and a disagreement between them is exactly the kind of thing this window exists
        // to make visible. Bounded at three records per tick per side by construction.
        if (tick == lastTick[side] && handled == lastHandled[side]) {
            return;
        }
        lastTick[side] = tick;
        lastHandled[side] = handled;
        int budget = recordBudget;
        if (records[side] > budget) {
            return; // already said so, once, below
        }
        records[side]++;
        if (records[side] > budget) {
            // The window stops HERE and says it stopped. Silence from an exhausted instrument and
            // silence from a body nobody asked about are the same empty log otherwise.
            TestTrace.record(entity, "deck_gate_explained", "\"e\":" + entity.getEntityId()
                    + ",\"worldTick\":" + tick
                    + ",\"calls\":" + calls[side] + ",\"budgetExhausted\":true,\"budget\":" + budget);
            return;
        }
        TestTrace.record(entity, "deck_gate_explained", "\"e\":" + entity.getEntityId()
                + ",\"who\":\"" + TestTrace.json(entity.getName())
                // NOT `tick`, and NOT `side`: the log's ENVELOPE already carries both, and a payload
                // key that collides with one silently OVERWRITES it. Measured on this recorder's own
                // calibration run — the envelope's client tick 138 came out as 712, which is this
                // body's world time, and nothing in the record said which clock a reader had. A
                // recorder that corrupts the envelope it is written into is worse than one that
                // omits a field, because both look like data.
                + "\",\"worldTick\":" + tick
                + ",\"calls\":" + calls[side]
                + ",\"handled\":" + handled
                + ",\"bodyX\":" + TestTrace.fmt(entity.posX)
                + ",\"bodyY\":" + TestTrace.fmt(entity.posY)
                + ",\"bodyZ\":" + TestTrace.fmt(entity.posZ)
                + "," + explained(entity)
                + "," + shipsOn(entity)
                + "," + statics());
    }

    /**
     * Production's own breakdown, flattened. Every key {@link ShipFrameTravel#explainHandles}
     * returns, under its own name — including {@code verdict}, which sits beside this record's
     * {@code handled} rather than replacing it.
     */
    private static String explained(EntityLivingBase entity) {
        Map<String, Object> gate;
        try {
            gate = ShipFrameTravel.explainHandles(entity);
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
     * Every loaded hull on the body's own side, with the world box the CONTAINMENT TEST uses — the
     * RELATION's second subject. A body reported to be contained by nothing, beside the boxes of
     * everything that is loaded, is a reading; the empty list on its own is not.
     *
     * <p><b>Asked of the same source the gate's own {@code containingShipIds} is asked of, and that
     * is not a detail.</b> {@code VSBridge.shipIdsAt} walks
     * {@code ValkyrienUtils.getPhysosLoadedInWorld} and tests {@code PhysicsObject.getShipBB()};
     * {@code VSIntegration.loadedShipBoxes} walks {@code getServerShipManager} and reads
     * {@code getShipBoundingBox()} — a different accessor, from a different manager. This recorder
     * was written against the second one first, and the calibration run said so out loud:
     * {@code shipsRead:"threw: WorldClientShipManager cannot be cast to WorldServerShipManager"}.
     * On the client — the side the question is about — it answered nothing at all, and had it been
     * reading a merely NEIGHBOURING box instead of throwing, the record would have looked like
     * evidence. (That production method promises "an empty map when the substrate is absent" and
     * says nothing about a side; it is ledgered separately.)</p>
     *
     * <p><b>Blind spot, stated because the numbers invite the wrong subtraction:</b> the printed box
     * is the RAW hull box, while the containment test grows it by a margin before asking. A body
     * just outside a printed box may still be inside for the gate's purposes, so "the body is not in
     * any of these boxes" is not by itself a contradiction of a non-empty {@code containingShipIds}
     * — and a body far outside all of them is.</p>
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

    // ---- the resolver's process-wide statics, read (not re-derived) ------------------------------

    private static final Field PENDING_SEED = declared("pendingSeed");
    private static final Field CAPTURE_EPOCH = declared("CAPTURE_EPOCH");
    private static final Field CLIENT_LOOK_SOURCE = declared("clientLookSource");
    private static final Field WALK_TRACE_TICKS = declared("walkTraceTicks");

    private static Field declared(String name) {
        try {
            Field f = ShipFrameTravel.class.getDeclaredField(name);
            f.setAccessible(true);
            return f;
        } catch (Throwable t) {
            return null; // reported per record as `staticsRead`, never silently omitted
        }
    }

    /**
     * The four statics, as they stand at this decision. A field that could not be resolved is named
     * in {@code staticsRead} rather than left out — a missing key and a null value are the same
     * character on the wire, and the whole point of reading these is to tell "there is no stale
     * seed" from "nobody looked".
     */
    private static String statics() {
        StringBuilder missing = new StringBuilder();
        Object seed = read(PENDING_SEED, "pendingSeed", missing);
        Object epoch = read(CAPTURE_EPOCH, "CAPTURE_EPOCH", missing);
        Object look = read(CLIENT_LOOK_SOURCE, "clientLookSource", missing);
        Object walk = read(WALK_TRACE_TICKS, "walkTraceTicks", missing);
        return "\"staticsRead\":\"" + (missing.length() == 0 ? "ok" : missing.toString()) + "\""
                + ",\"captureEpoch\":" + (epoch == null ? "null" : "\"" + TestTrace.json(
                        String.valueOf(epoch)) + "\"")
                + ",\"clientLookSource\":" + (look != null)
                + ",\"walkTraceTicks\":" + (walk == null ? "null" : String.valueOf(walk))
                + ",\"pendingSeed\":" + (seed == null ? "null" : seedOf(seed));
    }

    private static Object read(Field f, String name, StringBuilder missing) {
        if (f == null) {
            missing.append(missing.length() == 0 ? "" : "+").append("noField:").append(name);
            return null;
        }
        try {
            return f.get(null);
        } catch (Throwable t) {
            missing.append(missing.length() == 0 ? "" : "+").append("threw:").append(name);
            return null;
        }
    }

    /**
     * The pending seed's own fields. {@code PendingSeed} is a private nested class the test source
     * set cannot name, so this reads it reflectively too — and the body it names is the field that
     * decides the hypothesis: a seed still pointing at the PREDECESSOR's player is a predecessor
     * crossing the scenario boundary, in one field, visible.
     */
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
                // A cleared WeakReference is NOT the same as an empty slot: the seed is still
                // installed and still occupies the one slot the resolver has.
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

    // ---- wire helpers ---------------------------------------------------------------------------

    /** One value as JSON. Numbers stay numbers: a number answered through a quoted matcher is
     *  indistinguishable from absent, which has cost this suite a defect before. */
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

    /** Which side's counters a {@code peek}/{@code close} is reporting. The RECORD's side comes from
     *  the envelope; this only picks the slot, and it is the calling thread's side because a summary
     *  has no entity to route by. */
    private static int sideHere() {
        return net.minecraftforge.fml.common.FMLCommonHandler.instance().getEffectiveSide()
                == net.minecraftforge.fml.relauncher.Side.CLIENT ? 1 : 0;
    }
}
