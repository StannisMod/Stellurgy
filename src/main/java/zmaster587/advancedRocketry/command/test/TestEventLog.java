package zmaster587.advancedRocketry.command.test;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;

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
     * How many records are kept. Bounded so a long client cannot grow it without limit; the count of
     * what fell off the end is REPORTED, because a truncated log that reads as a quiet one is the
     * same false negative a recorder that cannot say it was off produces.
     */
    public static final int CAPACITY = 512;

    private static final Object LOCK = new Object();
    private static final Deque<Record> RECORDS = new ArrayDeque<>();
    private static long nextSeq;
    private static long dropped;

    /**
     * Whether anything is subscribed. Reported on every read, on the same principle as the client
     * harness's {@code managerLoaded}: "nothing happened" and "nobody was listening" must never be
     * the same reply, or an empty log reads as a finding.
     */
    private static volatile boolean recording;

    private TestEventLog() {}

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
            RECORDS.addLast(new Record(nextSeq++, tick, side, type, payload == null ? "" : payload));
            while (RECORDS.size() > CAPACITY) {
                RECORDS.removeFirst();
                dropped++;
            }
        }
    }

    /** Everything recorded at or after {@code fromSeq}, oldest first. */
    public static List<Record> since(long fromSeq) {
        List<Record> out = new ArrayList<>();
        synchronized (LOCK) {
            for (Record r : RECORDS) {
                if (r.seq >= fromSeq) {
                    out.add(r);
                }
            }
        }
        return out;
    }

    /** How many records fell off the end of the ring since the process started. */
    public static long dropped() {
        synchronized (LOCK) {
            return dropped;
        }
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
            dropped = 0;
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
