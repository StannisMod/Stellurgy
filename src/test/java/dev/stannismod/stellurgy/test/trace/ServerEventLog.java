package dev.stannismod.stellurgy.test.trace;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import net.minecraft.server.MinecraftServer;
import net.minecraftforge.fml.common.FMLCommonHandler;

/**
 * An ORDERED log of things that HAPPENED on one server, so a test can wait for an event instead of
 * sampling a value.
 *
 * <h2>Why this exists</h2>
 *
 * <p>Every other waiting primitive in this suite polls: it asks "is this true now?" every N ticks.
 * Four things follow, and all four have cost real time. A poll cannot see anything that does not
 * PERSIST — an event that fires and is over between two samples never existed. A poll races its own
 * start: {@code doThing(); pollUntil(X)} cannot tell "X happened before the first sample" from "X
 * never happened". A poll cannot express ORDER, which is the shape of nearly every contract here — a
 * boarding, a crossing, a jump, a re-seat. And a poll's failure carries only its last sample.</p>
 *
 * <p>The worked example: a bed scenario whose only witness was the world clock stayed red for a month
 * reporting {@code worldTime=20622}, while the actual chain was <i>client has no chunk &rarr; player
 * falls &rarr; he is out of reach &rarr; the server drops his click silently &rarr; the client still
 * reports SUCCESS from its own prediction &rarr; the sleep never starts</i>. Six of those seven links
 * were observable and none was observed.</p>
 *
 * <h2>The shape</h2>
 *
 * <p>{@link #mark()} is taken BEFORE the action. That is what removes the start race: records are
 * BUFFERED, so a reader that arrives late still sees everything that happened after its mark.
 * {@link #since(long)} returns them in order, so a chain and its ORDER are directly assertable.</p>
 *
 * <p><b>An ABSENCE is a first-class answer.</b> {@code PlayerInteractEvent.RightClickBlock} is not
 * fired at all when the server rejects a click on its reach check
 * ({@code NetHandlerPlayServer.processTryUseItemOnBlock} guards the call behind the distance test),
 * so "no such record since the mark" is a precise statement about the game, not a gap in the
 * instrument.</p>
 *
 * <h2>Who owns it</h2>
 *
 * <p>The server it describes: one log is an instance field of that server's {@link SideTrace}, which
 * is itself an instance field of the {@code MinecraftServer} (a test mixin), so it is created with the
 * server and released with it, and two servers in one JVM never share a sequence. The client's
 * ordered log is a different object, kept by the harness bridge.</p>
 *
 * <p>A test reads it from its own JVM through the probe's {@code invoke-static} verb, calling
 * {@link #markReply()} / {@link #sinceReply(long)} on the server thread. A server whose
 * {@code MinecraftServer} carries no trace — the test mixin configuration was not applied — answers
 * {@code recording:false, mixins:false} rather than throwing, so a reader can say which half is
 * missing.</p>
 *
 * <p>Test source set: absent from a released jar, so a shipped game builds no record and pays
 * nothing for it.</p>
 */
public final class ServerEventLog {

    /**
     * How many records are kept OF EACH TYPE. Bounded so a long session cannot grow the log without
     * limit; what fell off the end is REPORTED, because a truncated log that reads as a quiet one is
     * the same false negative a recorder that cannot say it was off produces.
     *
     * <p><b>Per type, not per log, and that is the whole point.</b> One shared ring is emptied by
     * whichever type is chattiest, so a rare event is evicted by a common one and the log then
     * answers "it never happened" about something it merely threw away. Measured 2026-08-21 on the
     * client half: a ship crossing loads a thousand chunks, {@code chunk_data_applied} filled the
     * ring, and the position writes the timeline exists for were gone before anything read them —
     * with {@code dropped} honestly reporting 173, which made the log honest and useless at the same
     * time. A ring per type costs a small map and leaves a chatty type unable to silence a quiet
     * one.</p>
     *
     * <p><b>256 &rarr; 4096 on 2026-09-12, and the number is SIZED rather than raised until a
     * symptom stopped.</b> The bound is not a memory constraint and never was: at roughly 300 bytes
     * a record and the ~19 types a server run registers, the whole log's ceiling was single-digit
     * megabytes against a 1 GB child, three orders below anything memory forces. Its only stated
     * reason is that a long session must not grow without limit, and that reason is satisfied at any
     * of these numbers.</p>
     *
     * <p>The measurement it is sized against: one scenario's window evicted <b>2179</b> records, all
     * of a single type, so that type alone overran 256 about ninefold. 4096 covers it whole instead
     * of moving the ceiling to the next miss, and costs about 23 MB at the theoretical maximum —
     * still an order below what the child can spare.</p>
     *
     * <p><b>What this does NOT fix</b>, because a bound that reads as a guarantee is worse than a
     * small one: a genuinely unbounded type still overruns. The ring stays a ring,
     * {@code dropped}/{@code droppedByType} stay in every reply, and a reader that does not carry
     * them into its failure message still cannot tell an absent record from an evicted one.</p>
     */
    public static final int CAPACITY_PER_TYPE = 4096;

    private final Object lock = new Object();
    /** One ring per type, insertion-ordered so a dump lists types in first-seen order. */
    private final Map<String, Deque<Record>> records = new LinkedHashMap<>();
    /** Evictions per type. WHICH type is being truncated is the half a reader can act on. */
    private final Map<String, Long> dropped = new LinkedHashMap<>();
    private long nextSeq;

    /**
     * Whether the bus recorder was attached to this server. Reported on every read: "nothing
     * happened" and "nobody was listening" must never be the same reply, or an empty log reads as a
     * finding. Records are dropped while it is false, mixin-sourced ones included, so the flag
     * covers the whole log and not only the bus half.
     */
    private volatile boolean recording;

    /**
     * Observation points that have EXECUTED at least once on this server, by name.
     *
     * <p>"The configuration was accepted" and "this code ran" are different claims, and until both can
     * be asked, an empty log means nothing: a mixin that was never woven, one that was woven but whose
     * method never ran, and one that ran and saw nothing are indistinguishable — and the first two
     * read as the third. So an instrument announces itself on ENTRY, before any threshold or
     * condition. Not gated on {@link #recording}: that code ran is worth keeping either way.</p>
     */
    private final Set<String> instrumentsEntered =
            Collections.synchronizedSet(new LinkedHashSet<String>());

    /** Built by the server's {@link SideTrace}; a unit test of the rings builds its own. */
    public ServerEventLog() {
    }

    /** One thing that happened, in order. */
    public static final class Record {
        public final long seq;
        public final long tick;
        public final String side;
        public final String type;
        /** A JSON fragment (no braces) describing this event, or empty. */
        public final String payload;

        Record(long seq, long tick, String side, String type, String payload) {
            this.seq = seq;
            this.tick = tick;
            this.side = side;
            this.type = type;
            this.payload = payload;
        }
    }

    // ---- which log ----------------------------------------------------------------------------

    /** The log of {@code server}. */
    public static ServerEventLog of(MinecraftServer server) {
        return SideTrace.server(server).events();
    }

    /**
     * The log of the server running in this JVM, or {@code null} when none runs or its
     * {@code MinecraftServer} carries no trace. For an observation point with no world in hand — a
     * packet decoded on a netty thread, a ledger write — and for the read verbs below.
     */
    static ServerEventLog current() {
        MinecraftServer server = FMLCommonHandler.instance().getMinecraftServerInstance();
        return server instanceof SideTraceOwner ? of(server) : null;
    }

    // ---- writing ------------------------------------------------------------------------------

    /** Start keeping records — called by {@link ServerEventRecorder#attach} once the bus recorder
     *  is subscribed for this server. */
    public void startRecording() {
        recording = true;
    }

    /** Append one record. No-op until {@link #startRecording}. */
    public void record(String side, long tick, String type, String payload) {
        if (!recording) {
            return;
        }
        synchronized (lock) {
            Deque<Record> ring = records.get(type);
            if (ring == null) {
                ring = new ArrayDeque<>();
                records.put(type, ring);
            }
            ring.addLast(new Record(nextSeq++, tick, side, type, payload == null ? "" : payload));
            while (ring.size() > CAPACITY_PER_TYPE) {
                ring.removeFirst();
                Long was = dropped.get(type);
                dropped.put(type, was == null ? 1L : was + 1L);
            }
        }
    }

    /** Called by an observation point the first thing it does, whether or not it goes on to record. */
    public void noteInstrumentEntered(String name) {
        if (name != null && !name.isEmpty()) {
            instrumentsEntered.add(name);
        }
    }

    // ---- reading ------------------------------------------------------------------------------

    /**
     * The sequence a reader should ask {@link #since(long)} for. Taken BEFORE the action under test:
     * everything recorded from now on has a sequence greater than or equal to this.
     */
    public long mark() {
        synchronized (lock) {
            return nextSeq;
        }
    }

    /**
     * Everything recorded at or after {@code fromSeq}, oldest first.
     *
     * <p>Merged across the per-type rings and re-ordered by sequence, because ORDER is what a chain
     * assertion reads and the sequence is the only thing that carries it once the rings are
     * separate.</p>
     */
    public List<Record> since(long fromSeq) {
        List<Record> out = new ArrayList<>();
        synchronized (lock) {
            for (Deque<Record> ring : records.values()) {
                for (Record r : ring) {
                    if (r.seq >= fromSeq) {
                        out.add(r);
                    }
                }
            }
        }
        Collections.sort(out, new Comparator<Record>() {
            @Override
            public int compare(Record a, Record b) {
                return Long.compare(a.seq, b.seq);
            }
        });
        return out;
    }

    /**
     * The recorded chain as one readable line — {@code type t=<tick> <payload>}, oldest first,
     * separated by {@code |} — filtered to {@code types} when any are given. For a failure message,
     * where a whole timeline in a sentence beats a structure to parse.
     */
    public String dump(String... types) {
        StringBuilder sb = new StringBuilder();
        for (Record r : since(0)) {
            if (types != null && types.length > 0 && !matches(r.type, types)) {
                continue;
            }
            if (sb.length() > 0) {
                sb.append(" | ");
            }
            sb.append(r.type).append(" t=").append(r.tick);
            if (!r.payload.isEmpty()) {
                sb.append(' ').append(r.payload.replace('"', '\''));
            }
        }
        return sb.toString();
    }

    /** How many records of {@code types} the log currently holds. */
    public int count(String... types) {
        int n = 0;
        for (Record r : since(0)) {
            if (types == null || types.length == 0 || matches(r.type, types)) {
                n++;
            }
        }
        return n;
    }

    private static boolean matches(String type, String[] types) {
        for (String t : types) {
            if (type.equals(t)) {
                return true;
            }
        }
        return false;
    }

    /** How many records fell off the end of any ring in this server's lifetime. */
    public long dropped() {
        long total = 0;
        synchronized (lock) {
            for (Long n : dropped.values()) {
                total += n;
            }
        }
        return total;
    }

    /**
     * Which types were truncated and by how much, as a JSON object body — {@code "chunk_data_applied":173}.
     * A bare total says a log is incomplete; this says WHERE.
     */
    public String droppedByType() {
        StringBuilder sb = new StringBuilder();
        synchronized (lock) {
            for (Map.Entry<String, Long> e : dropped.entrySet()) {
                if (sb.length() > 0) {
                    sb.append(',');
                }
                sb.append('"').append(e.getKey()).append("\":").append(e.getValue());
            }
        }
        return sb.toString();
    }

    /** Whether the bus recorder is attached — see the field's own note on why this is reported. */
    public boolean isRecording() {
        return recording;
    }

    /** The names of every observation point that has executed, as a JSON array. */
    public String instrumentsEntered() {
        StringBuilder out = new StringBuilder("[");
        synchronized (instrumentsEntered) {
            for (String name : instrumentsEntered) {
                if (out.length() > 1) {
                    out.append(',');
                }
                out.append('"').append(name).append('"');
            }
        }
        return out.append(']').toString();
    }

    // ---- the replies a test reads, through the probe's invoke-static ----------------------------

    /**
     * {@code {"ok":true,"seq":N,"recording":B,"mixins":true}} for the server running in this JVM.
     *
     * <p>{@code mixins} is true whenever there is a log to answer at all: the log hangs on the
     * server by a mixin of the same, required, configuration as every other test mixin, so its
     * existence is the proof that configuration was applied. A server without one answers
     * {@code mixins:false} and no {@code seq}, so a mark cannot be taken on a log that is not
     * there.</p>
     */
    public static String markReply() {
        ServerEventLog log = current();
        if (log == null) {
            return NO_LOG;
        }
        return "{\"ok\":true,\"seq\":" + log.mark() + ",\"recording\":" + log.isRecording()
                + ",\"mixins\":true}";
    }

    /** Every record since {@code from}, as an envelope — see {@link #sinceReply(long, String)}. */
    public static String sinceReply(long from) {
        return sinceReply(from, null);
    }

    /**
     * The records since {@code from}, of {@code type} when it is not null, inside one JSON envelope
     * of {@code recording} / {@code mixins} / {@code dropped} / {@code droppedByType} /
     * {@code instruments} / {@code from} / {@code count} / {@code events}.
     *
     * <p>The records are rendered FIRST and the envelope assembled around them, so that every
     * envelope key — {@code count} above all — precedes the records in the reply. A reader that took
     * the first {@code "count":} it saw once believed a record's own payload over the envelope
     * (measured 2026-09-05 on {@code crew_captured} with a crew of 0).</p>
     */
    public static String sinceReply(long from, String type) {
        ServerEventLog log = current();
        if (log == null) {
            return NO_LOG_SINCE;
        }
        StringBuilder rendered = new StringBuilder();
        int n = 0;
        for (Record r : log.since(from)) {
            if (type != null && !type.equalsIgnoreCase(r.type)) {
                continue;
            }
            if (n++ > 0) {
                rendered.append(',');
            }
            rendered.append("{\"seq\":").append(r.seq)
                    .append(",\"tick\":").append(r.tick)
                    .append(",\"side\":\"").append(r.side).append('"')
                    .append(",\"type\":\"").append(r.type).append('"');
            if (!r.payload.isEmpty()) {
                rendered.append(',').append(r.payload);
            }
            rendered.append('}');
        }
        return "{\"ok\":true,\"recording\":" + log.isRecording()
                + ",\"mixins\":true"
                + ",\"dropped\":" + log.dropped()
                + ",\"droppedByType\":{" + log.droppedByType() + '}'
                // Which observation points have EXECUTED. Carried on every read because an empty
                // `events` list is only an answer once this says somebody was looking.
                + ",\"instruments\":" + log.instrumentsEntered()
                + ",\"from\":" + from
                + ",\"count\":" + n
                + ",\"events\":[" + rendered + "]}";
    }

    /** The reply of a server with no log to read: neither half of the honesty pair holds. */
    private static final String NO_LOG = "{\"ok\":true,\"recording\":false,\"mixins\":false,"
            + "\"why\":\"this server carries no test trace - the test mixin configuration was not"
            + " applied in its JVM (is -Dfml.coreMods.load set?)\"}";

    /** {@link #NO_LOG} as a {@code since} reply: an envelope with nothing in it, which a reader's
     *  own triage reads as "never armed" from {@code recording:false}. */
    private static final String NO_LOG_SINCE = "{\"ok\":true,\"recording\":false,\"mixins\":false,"
            + "\"why\":\"this server carries no test trace - the test mixin configuration was not"
            + " applied in its JVM (is -Dfml.coreMods.load set?)\",\"count\":0,\"events\":[]}";
}
