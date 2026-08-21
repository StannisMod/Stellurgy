package zmaster587.advancedRocketry.command.test;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import net.minecraft.util.math.BlockPos;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.entity.player.PlayerInteractEvent;
import net.minecraftforge.event.entity.player.PlayerSleepInBedEvent;
import net.minecraftforge.event.entity.player.PlayerWakeUpEvent;
import net.minecraftforge.fml.common.eventhandler.EventPriority;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;

/**
 * An ORDERED log of things that HAPPENED on this side, so a test can wait for an event instead of
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
 * <p>{@link #mark()} is taken BEFORE the action. That is what removes the start race, and it is the
 * whole reason this is not a poll with better manners: records are BUFFERED, so a reader that arrives
 * late still sees everything that happened after its mark.</p>
 *
 * <p>{@link #since(long)} returns them in order, so a chain and its ORDER are directly assertable —
 * and, on a failure, the caller prints what DID happen rather than one stale number.</p>
 *
 * <p><b>An ABSENCE is a first-class answer.</b> {@code PlayerInteractEvent.RightClickBlock} is not
 * fired at all when the server rejects a click on its reach check
 * ({@code NetHandlerPlayServer.processTryUseItemOnBlock} guards the call behind the distance test),
 * so "no such record since the mark" is a precise statement about the game, not a gap in the
 * instrument.</p>
 *
 * <h2>Costs nothing in a shipped game</h2>
 *
 * <p>The subscriber is registered only from {@link TestProbeCommandRegistration}, i.e. only under
 * {@code -Dadvancedrocketry.tests=true} or inside a harness-spawned JVM. Nothing here is referenced
 * from production paths, and with the recorder unregistered no event handler runs and no record is
 * built. Observation belongs to the test side; a shipped game must not pay for it.</p>
 */
public final class TestEventLog {

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
     */
    public static final int CAPACITY_PER_TYPE = 256;

    private static final Object LOCK = new Object();
    /** One ring per type, insertion-ordered so a dump lists types in first-seen order. */
    private static final Map<String, Deque<Record>> RECORDS = new LinkedHashMap<>();
    /** Evictions per type. WHICH type is being truncated is the half a reader can act on. */
    private static final Map<String, Long> DROPPED = new LinkedHashMap<>();
    private static long nextSeq;

    /**
     * Whether anything is subscribed. Reported on every read, on the same principle as the client
     * harness's {@code managerLoaded}: "nothing happened" and "nobody was listening" must never be
     * the same reply, or an empty log reads as a finding.
     */
    private static volatile boolean recording;

    /**
     * Whether the test-only mixin configuration was ACCEPTED in this JVM.
     *
     * <p>Separate from {@link #recording}, because they can fail independently and their failures
     * look identical from a test: the Forge-bus recorder covers events the bus already fires, while
     * everything observed by a mixin — a position write, a mount refusal — appears only if the
     * launch-time coremod queued the config. Set from the config's own plugin at the moment mixin
     * prepares it, so it reports a checkable fact rather than an intention. A shipped game never
     * queues the config and this stays false, which is correct: nothing there is instrumented.</p>
     */
    private static volatile boolean mixinsInstalled;

    private TestEventLog() {}

    /** Called from the test mixin config's plugin when mixin prepares that configuration. */
    public static void markMixinsInstalled() {
        mixinsInstalled = true;
    }

    /** Whether the test-only mixin configuration was accepted — see the field's own note. */
    public static boolean areMixinsInstalled() {
        return mixinsInstalled;
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

    /**
     * The sequence a reader should ask {@link #since(long)} for. Taken BEFORE the action under test:
     * everything recorded from now on has a sequence greater than or equal to this.
     */
    public static long mark() {
        synchronized (LOCK) {
            return nextSeq;
        }
    }

    /** Append one record. No-op unless a recorder is registered. */
    public static void record(String side, long tick, String type, String payload) {
        if (!recording) {
            return;
        }
        synchronized (LOCK) {
            Deque<Record> ring = RECORDS.get(type);
            if (ring == null) {
                ring = new ArrayDeque<>();
                RECORDS.put(type, ring);
            }
            ring.addLast(new Record(nextSeq++, tick, side, type, payload == null ? "" : payload));
            while (ring.size() > CAPACITY_PER_TYPE) {
                ring.removeFirst();
                Long was = DROPPED.get(type);
                DROPPED.put(type, was == null ? 1L : was + 1L);
            }
        }
    }

    /**
     * Everything recorded at or after {@code fromSeq}, oldest first.
     *
     * <p>Merged across the per-type rings and re-ordered by sequence, because ORDER is what a chain
     * assertion reads and the sequence is the only thing that carries it once the rings are
     * separate.</p>
     */
    public static List<Record> since(long fromSeq) {
        List<Record> out = new ArrayList<>();
        synchronized (LOCK) {
            for (Deque<Record> ring : RECORDS.values()) {
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
     * separated by {@code |}.
     *
     * <p>For a failure message, where a caller wants the whole timeline in a sentence rather than a
     * structure to parse. Filters to {@code types} when any are given.</p>
     */
    public static String dump(String... types) {
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
    public static int count(String... types) {
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

    /** How many records fell off the end of any ring since the process started. */
    public static long dropped() {
        long total = 0;
        synchronized (LOCK) {
            for (Long n : DROPPED.values()) {
                total += n;
            }
        }
        return total;
    }

    /**
     * Which types were truncated and by how much, as a JSON object body — {@code "chunk_data_applied":173}.
     *
     * <p>A bare total says a log is incomplete; this says WHERE, which is the difference between a
     * reader who knows to raise a cap and one who quietly believes a short answer.</p>
     */
    public static String droppedByType() {
        StringBuilder sb = new StringBuilder();
        synchronized (LOCK) {
            for (Map.Entry<String, Long> e : DROPPED.entrySet()) {
                if (sb.length() > 0) {
                    sb.append(',');
                }
                sb.append('"').append(e.getKey()).append("\":").append(e.getValue());
            }
        }
        return sb.toString();
    }

    /** Whether a recorder is subscribed — see the field's own note on why this is reported. */
    public static boolean isRecording() {
        return recording;
    }

    /**
     * Forget everything and start again. For a harness that reuses a JVM across scenarios; a test
     * that only wants "since here" should use {@link #mark()} instead, which costs nothing.
     */
    public static void reset() {
        synchronized (LOCK) {
            RECORDS.clear();
            DROPPED.clear();
            nextSeq = 0;
        }
    }

    /**
     * The Forge-bus recorder. Registered ONLY in test mode, from
     * {@link TestProbeCommandRegistration}.
     *
     * <p>Deliberately nothing but bus subscriptions: under the project's rule an observation a test
     * wants is a harness mixin or the Forge bus, never a line added to production logic, because a
     * shipped game must pay nothing for it.</p>
     */
    public static final class ServerRecorder {

        private static boolean registered;

        private ServerRecorder() {}

        public static void ensureRegistered() {
            if (registered) {
                return;
            }
            MinecraftForge.EVENT_BUS.register(new ServerRecorder());
            registered = true;
            recording = true;
        }

        /**
         * A right-click that REACHED the server. Its absence is the signal: the reach check upstream
         * drops a click from too far away before this event is ever fired.
         */
        @SubscribeEvent
        public void onRightClickBlock(PlayerInteractEvent.RightClickBlock event) {
            BlockPos p = event.getPos();
            record(event.getWorld().isRemote ? "client" : "server",
                    event.getWorld().getTotalWorldTime(), "right_click_block",
                    "\"x\":" + p.getX() + ",\"y\":" + p.getY() + ",\"z\":" + p.getZ()
                            + ",\"player\":\"" + event.getEntityPlayer().getName() + "\"");
        }

        /**
         * The bed attempt, WITH the result a handler put on it.
         *
         * <p>{@code LOWEST} on purpose: the result is what the last handler leaves, and AR's own
         * planet handler is one of them. Reading it earlier would record a verdict nobody reached.
         * A result of {@code null} means no handler objected — which is not the same as "he slept",
         * because vanilla's own gates run after this event; that half of the story is
         * {@code player_wake_up}.</p>
         */
        @SubscribeEvent(priority = EventPriority.LOWEST)
        public void onSleepInBed(PlayerSleepInBedEvent event) {
            BlockPos p = event.getPos();
            record(event.getEntityPlayer().world.isRemote ? "client" : "server",
                    event.getEntityPlayer().world.getTotalWorldTime(), "sleep_in_bed",
                    "\"x\":" + p.getX() + ",\"y\":" + p.getY() + ",\"z\":" + p.getZ()
                            + ",\"result\":\"" + (event.getResultStatus() == null
                                    ? "none" : event.getResultStatus().name()) + "\""
                            + ",\"player\":\"" + event.getEntityPlayer().getName() + "\"");
        }

        /** He was asleep and is not any more — the only bus-visible proof the sleep happened. */
        @SubscribeEvent
        public void onWakeUp(PlayerWakeUpEvent event) {
            record(event.getEntityPlayer().world.isRemote ? "client" : "server",
                    event.getEntityPlayer().world.getTotalWorldTime(), "player_wake_up",
                    "\"player\":\"" + event.getEntityPlayer().getName() + "\"");
        }
    }
}
