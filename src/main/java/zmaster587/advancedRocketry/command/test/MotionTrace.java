package zmaster587.advancedRocketry.command.test;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * A flight recorder for MOTION SMOOTHNESS: bounded rings of where a tier-2 ship, its pilot and the
 * pilot's own camera actually were, sampled on each of the four clocks the craft's motion passes
 * through, each sample carrying both the TICK it was taken in and its wall-clock nanoTime.
 *
 * <p><b>The tick is what a verdict may rest on; the nanoTime is for the failure message.</b> A
 * wall-clock interval belongs to the HOST, so a summary built from it measures the box with the
 * code as one contributor among several — see {@code perTick} in the summary, and the {@code tick}
 * array's own note. The two are kept side by side on purpose: a reader chasing a real hitch wants
 * the milliseconds, and no test may assert on them.</p>
 *
 * <p><b>Why four clocks.</b> A ship the pilot describes as "flying in jerks" can be jerking on any
 * one of them, and they are fixed by different things:</p>
 * <ul>
 *   <li>{@link #PHYS} — the physics loop's own thread, one sample per physics step. This is where
 *       the ship's velocity actually integrates, so an irregular interval here means the physics
 *       loop is not keeping its rate, and a displacement that alternates between much and nothing
 *       means the force controller is fighting something.</li>
 *   <li>{@link #GAME} — the server world tick, one sample per flight-computer tick. The command the
 *       controller chases is republished here, so a stalled server tick starves the controller of
 *       fresh commands even while the physics loop runs on perfectly.</li>
 *   <li>{@link #CLIENT_TICK} — the client's own tick, one sample per tick, carrying where the
 *       client believes the player is. The server can be perfectly smooth and this still jerk: the
 *       ship pose arrives over the wire and is smoothed by a filter.</li>
 *   <li>{@link #CLIENT_FRAME} — one sample per rendered frame, carrying the pilot's eye point. This
 *       is the only channel that sees what he actually looks at, and the only one that sees a
 *       frame-time hitch (chunk meshing, a GC pause) as what it is.</li>
 * </ul>
 *
 * <p>A magnitude here always ships with the components it came from, because a distance that will
 * not move cannot say WHICH axis is stuck.</p>
 *
 * <p><b>Always on, deliberately.</b> The rings are allocated on first use and every sample is a
 * handful of stores into preallocated primitive arrays — no allocation, no formatting, nothing that
 * can itself perturb the timing it measures. Gating this on test mode would have made it useless in
 * the one place the symptom has ever been seen, which is a real play session: the point of a flight
 * recorder is that it was already running when the thing happened.</p>
 *
 * <p><b>Thread model.</b> One ring per (channel, key), and each ring is written by exactly one
 * thread — physics thread, server thread, or client thread. Readers (a probe, a reflective harness
 * read) run on a different thread and may catch a half-written newest sample; a diagnostic can
 * afford that, and every summary below is computed over the samples BEHIND the write cursor.</p>
 */
public final class MotionTrace {

    /** Physics-thread channel: one sample per physics step, per flight computer. */
    public static final int PHYS = 0;
    /** Server-thread channel: one sample per flight-computer world tick, per flight computer. */
    public static final int GAME = 1;
    /** Client-thread channel: one sample per client tick (global — there is one local player). */
    public static final int CLIENT_TICK = 2;
    /** Client-thread channel: one sample per rendered frame (global). */
    public static final int CLIENT_FRAME = 3;

    private static final int CHANNELS = 4;
    /** Ring depth per channel. At 60 Hz physics / 60 fps this is ~68 s of history; at 20 Hz, ~3.5 min. */
    private static final int[] CAPACITY = {4096, 2048, 2048, 4096};
    /**
     * The period each channel's clock is SUPPOSED to run at, in milliseconds; 0 where there is no
     * declared rate and the channel's own median is the only yardstick (a frame rate is whatever the
     * machine manages).
     *
     * <p>This exists because a late beat has to be late against something, and the channel's own
     * median is the wrong something on a clock whose beats are legitimately uneven. A client tick is
     * the case that forced it: vanilla runs the tick inside the render loop, so at 30 fps the ticks
     * land on frame boundaries at 33.7 or 67.4 ms and average the 50 ms they are meant to — against
     * their own median of 33.7 every second beat "arrives late", and a hitch count built that way
     * reports a healthy client as stuttering, in every run, forever.</p>
     */
    private static final double[] NOMINAL_MS = {1000.0 / 60.0, 50.0, 50.0, 0.0};
    /**
     * Where a channel keeps a SECOND pose, or -1 where it keeps only its own.
     *
     * <p>Only the client tick has one: the pilot's position in columns 1..3 and the MOUNT he rides
     * in 6..8. Two poses in one sample is how a freeze is localised without a second run — the
     * pilot's view is the mount's position plus an offset, so a tick where the pilot did not move
     * either had a mount that did not move or did not apply one that did, and those are different
     * defects.</p>
     */
    private static final int[] SECOND_POSE_COL = {-1, -1, 6, -1};
    /**
     * Where a channel keeps the COMMANDED speed, or -1 where it keeps none.
     *
     * <p>Reported as a maximum over the window so a caller can bound the craft's motion by what was
     * actually ASKED of it rather than by the ceiling anyone could have asked for. The difference
     * decides whether a bound can see anything: a craft cruising at 2 blocks/tick whose client pose
     * jumps 4 in one tick is inside the 3-block setpoint ceiling once the two clocks' phase beat is
     * allowed for, and outside its own command by a factor of two.</p>
     */
    private static final int[] CMD_SPEED_COL = {5, 1, -1, -1};
    /**
     * Columns carried per sample. Their meaning is per-channel; see the recording methods.
     *
     * <p>Widened from 10 to 12 on 2026-09-21, when the client tick gained the MOUNT's pose: its
     * three columns landed on 6..8 and 8 was {@link #COL_WRITER}, so that channel began reporting
     * sixty distinct writers — a reading that on the physics channel means two craft claiming one
     * flight computer. The identity columns now sit at the END, past every channel's payload,
     * where a new payload column cannot reach them.</p>
     */
    private static final int COLUMNS = 12;
    /**
     * The column every channel reserves for the IDENTITY of whatever produced the sample, or 0 where
     * a channel has only one possible producer. Counting the distinct values of this inside a window
     * turns "this ring is being written twice as fast as its clock runs" — which reads like an
     * instrument fault — into "two named things are writing here", which is a finding.
     */
    private static final int COL_WRITER = 10;
    /**
     * A SECOND identity, so the two ways of having two writers can be told apart without another
     * run. On the physics channel the writer is the controller OBJECT and this is its SHIP: two
     * writers sharing one ship are two tile instances at one block — a stale one that outlived its
     * replacement — while two writers carrying two ships are two craft claiming one flight computer.
     * The fixes for those are not remotely the same.
     */
    private static final int COL_WRITER2 = 11;
    /**
     * How many distinct flight computers each per-ship channel keeps at once.
     *
     * <p>Reaching the limit evicts the LEAST RECENTLY WRITTEN key instead of refusing the new one.
     * Refusing was the wrong way round: keys are minted per (dimension, block), a jump re-pastes a
     * craft at a fresh address, and the server channel is sampled before the gates below it — so a
     * couple of transits, or merely five flight-computer blocks ticking in loaded chunks, used up
     * the budget and the recorder went quiet on the craft actually being flown. A recorder that
     * forgets a craft you left behind is worth far more than one that stops recording the one you
     * are in.</p>
     *
     * <p>Every eviction is counted per channel and reported beside the samples, because a dropped
     * key renders exactly like a controller that never ran, and those two readings ask for opposite
     * investigations.</p>
     */
    private static final int MAX_KEYS = 4;

    /**
     * Cumulative count of chunks that finished loading, both sides, since the game started. Sampled
     * into {@link #GAME} and {@link #CLIENT_TICK} so a tick that took too long can be attributed to
     * chunk work rather than merely correlated with it by eye.
     */
    public static volatile long serverChunkLoads = 0L;
    public static volatile long clientChunkLoads = 0L;

    /**
     * Ship transform updates the CLIENT has applied, cumulative.
     *
     * <p>The client's ship pose is an exponential filter chasing whatever the last packet said, so
     * how many packets landed inside a window is the difference between "the ship moved unevenly"
     * and "the ship's pose ARRIVED unevenly". Those are different defects with different owners, and
     * a displacement series alone cannot tell them apart: a filter fed one update where it expected
     * two produces exactly the same shape as a ship that genuinely lurched.</p>
     *
     * <p>Cumulative rather than per-window on purpose — a counter read only at the end of a window
     * supports a correlation between legs and never an attribution inside one.</p>
     */
    public static volatile long clientShipTransformUpdates = 0L;

    /**
     * The client half of the recording, rendered on demand. Exposed as an object whose
     * {@code toString()} does the work, because the harness's client channel can read a static
     * FIELD but cannot call a method — and rendering eagerly every frame would cost more than the
     * recording does.
     */
    public static final Object CLIENT_SUMMARY = new Object() {
        @Override
        public String toString() {
            return clientSummary();
        }
    };

    /** How many rings each channel has dropped to stay inside the key budget. Never reset by a read. */
    private static final long[] KEY_EVICTIONS = new long[CHANNELS];

    /** Per-channel rings, keyed by flight-computer position for the per-ship channels, 0 for global. */
    private static final Map<Long, Ring>[] RINGS = newRingMaps();

    private MotionTrace() {
    }


    @SuppressWarnings("unchecked")
    private static Map<Long, Ring>[] newRingMaps() {
        Map<Long, Ring>[] maps = new Map[CHANNELS];
        for (int i = 0; i < CHANNELS; i++) {
            final int channel = i;
            // Access-ordered, so "eldest" means least recently touched rather than first seen: the
            // ring that gets dropped is the one nothing has written or read for the longest.
            maps[i] = new LinkedHashMap<Long, Ring>(16, 0.75f, true) {
                @Override
                protected boolean removeEldestEntry(Map.Entry<Long, Ring> eldest) {
                    if (size() > MAX_KEYS) {
                        KEY_EVICTIONS[channel]++;
                        return true;
                    }
                    return false;
                }
            };
        }
        return maps;
    }

    // -- recording -------------------------------------------------------------------------------

    /**
     * One physics step of a ship driven by the flight computer at {@code key}.
     *
     * <p>Columns: dt(s), world x, y, z, |v|, |commanded v|, mass, 1 if the controller's acceleration
     * was clamped to its authority this step else 0, and the SHIP the step belonged to.</p>
     *
     * <p>The two tags are the load-bearing ones. A flight computer is supposed to be driven once per
     * step by one controller on one ship; anything else shows up otherwise only as a sample rate
     * that has quietly doubled, which reads like an instrument fault rather than a defect.</p>
     */
    public static void phys(long key, long worldTick, double controllerTag, double shipTag, double dt,
                            double x, double y, double z,
                            double speed, double cmdSpeed, double mass, boolean clamped) {
        ring(PHYS, key).add(System.nanoTime(), worldTick, dt, x, y, z, speed, cmdSpeed, mass,
                clamped ? 1.0 : 0.0, 0.0, 0.0, controllerTag, shipTag);
    }

    /**
     * One world tick of the flight computer at {@code key}.
     *
     * <p>Columns: 1 if a seated pilot's input was present else 0, |commanded v|, |velocity
     * setpoint|, cumulative server chunk loads, then four zeroes. The INTERVAL between samples is
     * the server tick period as this tile experiences it, which is the point of the channel.</p>
     */
    public static void game(long key, long worldTick, boolean pilotInput, double cmdSpeed,
                            double setpointSpeed) {
        ring(GAME, key).add(System.nanoTime(), worldTick, pilotInput ? 1.0 : 0.0, cmdSpeed,
                setpointSpeed, serverChunkLoads, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0);
    }

    /**
     * One client tick, carrying where the CLIENT believes the local player is.
     *
     * <p>Columns: 1 if riding something else 0, then x, y, z, then cumulative client chunk loads,
     * then arriving ship poses, then the MOUNT's own x, y, z. Every POSITIONAL channel keeps its
     * pose in columns 1..3 so one displacement routine reads them all — a channel that put its pose
     * elsewhere would be measured silently wrong rather than loudly.</p>
     *
     * <p><b>The mount's position is here to localise a freeze, and it is a different question from
     * every other column.</b> A pilot's client position is the mount's plus an offset, and the
     * mount's is the ship's pose plus another — so when the pilot's view stands still for a tick
     * and then covers two ticks of ground, which it does intermittently while the server flies the
     * craft perfectly evenly, the freeze entered at one of three places and only a reading of the
     * MIDDLE one says which. Taken in the same sample as the pilot's, so the two are the same tick
     * by construction rather than by argument.</p>
     */
    public static void clientTick(long clientTick, double x, double y, double z, boolean riding,
                                  double mountX, double mountY, double mountZ) {
        ring(CLIENT_TICK, 0L).add(System.nanoTime(), clientTick, riding ? 1.0 : 0.0, x, y, z,
                clientChunkLoads, clientShipTransformUpdates, mountX, mountY, mountZ, 0.0,
                0.0, 0.0);
    }

    /**
     * One rendered frame, carrying the pilot's interpolated EYE point — the only sample in this
     * class that is what he actually looks at.
     *
     * <p>Columns: partial ticks, then eye x, y, z (the shared positional layout), then four
     * zeroes.</p>
     */
    public static void clientFrame(long clientTick, double eyeX, double eyeY, double eyeZ,
                                   double partialTicks) {
        ring(CLIENT_FRAME, 0L).add(System.nanoTime(), clientTick, partialTicks, eyeX, eyeY, eyeZ,
                0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0);
    }

    /**
     * A flight computer's identity for the per-ship rings: its DIMENSION and its block position.
     *
     * <p>The dimension is not decoration. A ship's subspace coordinates are assigned by its own
     * shipyard, so two ships in two worlds routinely carry a flight computer at the SAME block — a
     * crossing in particular re-pastes a craft into the destination's paste lane, which is the same
     * local address every time. Keyed on position alone, the departed ship and the arrived one write
     * into ONE ring: the sample rate doubles and the displacement between consecutive samples
     * ping-pongs between two craft thousands of blocks apart. Measured on the second run of the
     * smoothness e2e — 364 samples over a 3 s window on a 60 Hz clock, and a ship reported as having
     * travelled 2 blocks when it had flown 119.</p>
     */
    public static long keyOf(int dim, int x, int y, int z) {
        long pos = ((long) x & 0x3FFFFFFL) << 38 | ((long) y & 0xFFFL) << 26 | ((long) z & 0x3FFFFFFL);
        return pos * 31L + dim;
    }

    // -- reading ---------------------------------------------------------------------------------

    /**
     * The server-side summary for one flight computer: both server channels over the trailing
     * {@code windowMs}, as a JSON object body (no braces).
     */
    public static String serverSummary(long key, long windowMs) {
        return "\"phys\":" + summary(PHYS, key, windowMs, true)
                + ",\"game\":" + summary(GAME, key, windowMs, false);
    }

    /**
     * The client-side summary over three trailing windows at once. Three because the harness reads
     * this AFTER a flight leg rather than during it, so the leg's own length has to be selectable
     * at read time rather than at record time.
     */
    public static String clientSummary() {
        StringBuilder sb = new StringBuilder("{");
        long[] windows = {1000L, 3000L, 10000L};
        for (int i = 0; i < windows.length; i++) {
            if (i > 0) {
                sb.append(',');
            }
            sb.append("\"w").append(windows[i]).append("\":{")
                    .append("\"tick\":").append(summary(CLIENT_TICK, 0L, windows[i], true))
                    .append(",\"frame\":").append(summary(CLIENT_FRAME, 0L, windows[i], true))
                    .append('}');
        }
        sb.append(",\"chunkLoads\":").append(clientChunkLoads).append('}');
        return sb.toString();
    }

    /**
     * Statistics over the samples of one ring inside a trailing wall-clock window.
     *
     * <p>Reports, beside the obvious counts: the interval distribution (how regular the clock this
     * channel rides actually is), and — when {@code positional} — the per-sample displacement
     * distribution with its components, plus two named pathologies:</p>
     * <ul>
     *   <li>{@code hitches}: intervals longer than twice the median, with {@code hitchMs} the total
     *       time they cost over a regular clock. A frame or tick that arrives late IS the jerk.</li>
     *   <li>{@code stalls}: samples that moved less than a tenth of the median displacement while
     *       the median was not itself ~zero. A craft that stops and starts under a held key.</li>
     * </ul>
     */
    private static String summary(int channel, long key, long windowMs, boolean positional) {
        Ring r = peek(channel, key);
        if (r == null) {
            // "Nothing here" has two causes that ask for opposite investigations: this key was
            // never driven at all, or its ring was evicted to stay inside the key budget. The
            // eviction count separates them, so a silence is never read as a dead controller.
            return "{\"n\":0,\"evicted\":" + evictions(channel) + "}";
        }
        return r.summary(windowMs, positional);
    }

    /** Forget everything recorded so far — a fresh leg starts from an empty ring. */
    public static synchronized void reset() {
        for (int i = 0; i < CHANNELS; i++) {
            RINGS[i].clear();
            KEY_EVICTIONS[i] = 0L;
        }
    }

    /** How many rings this channel has evicted since the last {@link #reset()}. */
    private static synchronized long evictions(int channel) {
        return KEY_EVICTIONS[channel];
    }

    private static synchronized Ring ring(int channel, long key) {
        Map<Long, Ring> byKey = RINGS[channel];
        Ring r = byKey.get(key);
        if (r == null) {
            r = new Ring(CAPACITY[channel], NOMINAL_MS[channel], SECOND_POSE_COL[channel],
                    CMD_SPEED_COL[channel]);
            byKey.put(key, r); // over budget: the put evicts the least recently used ring, loudly
        }
        return r;
    }

    private static synchronized Ring peek(int channel, long key) {
        return RINGS[channel].get(key);
    }

    /**
     * A fixed-capacity ring of wall-clock-stamped samples. {@link #add} is the only writer and is
     * called from exactly one thread per instance; it allocates nothing.
     */
    private static final class Ring {
        private final int cap;
        private final double nominalMs;
        private final long[] time;
        /**
         * The TICK each sample was taken in, beside its nanoTime.
         *
         * <p><b>This is the column the verdicts are built on, and the nanoTime is not.</b> A
         * wall-clock interval is a property of the HOST — CPU contention, GC, chunk I/O, whatever
         * else the box is running — so a summary derived from it measures the machine with the code
         * as one contributor. What the CODE produces is a sequence of poses indexed by tick, and a
         * gap, a double step or a step the drive could not have commanded are all properties of that
         * sequence. On a box ten times slower the same code produces the same sequence.</p>
         *
         * <p>It is a separate array rather than a column because a tick is a long and the columns are
         * doubles: at the far end of a long-running save a world time would start losing integers,
         * and a tick index that silently rounds is worse than none.</p>
         *
         * <p>It must come from a clock the SAMPLER does not drive. A counter incremented here would
         * make a gap undetectable by construction — it would count samples and call them ticks.</p>
         */
        private final long[] tick;
        private final double[] cols;
        private volatile int write;
        private volatile long seen;

        private final int secondPoseCol;
        private final int cmdSpeedCol;

        Ring(int cap, double nominalMs, int secondPoseCol, int cmdSpeedCol) {
            this.cap = cap;
            this.nominalMs = nominalMs;
            this.secondPoseCol = secondPoseCol;
            this.cmdSpeedCol = cmdSpeedCol;
            this.time = new long[cap];
            this.tick = new long[cap];
            this.cols = new double[cap * COLUMNS];
        }

        void add(long ns, long tickIndex, double c0, double c1, double c2, double c3,
                 double c4, double c5, double c6, double c7, double c8, double c9,
                 double c10, double c11) {
            int i = write;
            int base = i * COLUMNS;
            time[i] = ns;
            tick[i] = tickIndex;
            cols[base] = c0;
            cols[base + 1] = c1;
            cols[base + 2] = c2;
            cols[base + 3] = c3;
            cols[base + 4] = c4;
            cols[base + 5] = c5;
            cols[base + 6] = c6;
            cols[base + 7] = c7;
            cols[base + 8] = c8;
            cols[base + 9] = c9;
            cols[base + 10] = c10;
            cols[base + 11] = c11;
            write = (i + 1) % cap;
            seen++;
        }

        /** Indices of the samples inside the window, oldest first, excluding the one being written. */
        private int[] window(long windowMs) {
            int end = write;                  // one past the newest COMPLETE sample
            long total = seen;
            int have = (int) Math.min(total, cap);
            if (have <= 1) {
                return new int[0];
            }
            long cutoff = System.nanoTime() - windowMs * 1_000_000L;
            int[] idx = new int[have];
            int n = 0;
            for (int back = have; back >= 1; back--) {
                int i = ((end - back) % cap + cap) % cap;
                if (time[i] >= cutoff) {
                    idx[n++] = i;
                }
            }
            int[] out = new int[n];
            System.arraycopy(idx, 0, out, 0, n);
            return out;
        }

        String summary(long windowMs, boolean positional) {
            int[] idx = window(windowMs);
            if (idx.length < 2) {
                return "{\"n\":" + idx.length + ",\"seen\":" + seen + "}";
            }
            int n = idx.length;
            double[] gapMs = new double[n - 1];
            for (int i = 1; i < n; i++) {
                gapMs[i - 1] = (time[idx[i]] - time[idx[i - 1]]) / 1.0e6;
            }
            double[] sortedGap = gapMs.clone();
            java.util.Arrays.sort(sortedGap);
            double medGap = median(sortedGap);

            // Late against the clock's DECLARED period where it has one, and against its own median
            // only where it does not. The excess is measured against the same yardstick, so
            // "hitchMs" is time the pilot actually lost rather than time a rule invented.
            double beat = nominalMs > 0 ? nominalMs : medGap;
            int hitches = 0;
            double hitchMs = 0.0;
            for (int i = 0; i < gapMs.length; i++) {
                if (beat > 0 && gapMs[i] > 2.0 * beat) {
                    hitches++;
                    hitchMs += gapMs[i] - beat;
                }
            }

            StringBuilder sb = new StringBuilder();
            sb.append("{\"n\":").append(n)
                    .append(",\"seen\":").append(seen)
                    .append(",\"spanMs\":").append(round((time[idx[n - 1]] - time[idx[0]]) / 1.0e6))
                    .append(",\"hz\":").append(round(medGap > 0 ? 1000.0 / medGap : 0.0))
                    .append(",\"beatMs\":").append(round(beat))
                    .append(",\"gapMs\":{\"min\":").append(round(sortedGap[0]))
                    .append(",\"p50\":").append(round(medGap))
                    .append(",\"p95\":").append(round(pct(sortedGap, 0.95)))
                    .append(",\"max\":").append(round(sortedGap[sortedGap.length - 1]))
                    .append("},\"hitches\":").append(hitches)
                    .append(",\"hitchMs\":").append(round(hitchMs));

            if (positional) {
                double[] step = new double[n - 1];
                double sumDx = 0, sumDy = 0, sumDz = 0;
                for (int i = 1; i < n; i++) {
                    int a = idx[i - 1] * COLUMNS;
                    int b = idx[i] * COLUMNS;
                    double dx = cols[b + 1] - cols[a + 1];
                    double dy = cols[b + 2] - cols[a + 2];
                    double dz = cols[b + 3] - cols[a + 3];
                    step[i - 1] = Math.sqrt(dx * dx + dy * dy + dz * dz);
                    sumDx += dx;
                    sumDy += dy;
                    sumDz += dz;
                }
                double[] sortedStep = step.clone();
                java.util.Arrays.sort(sortedStep);
                double medStep = median(sortedStep);
                int stalls = 0;
                for (int i = 0; i < step.length; i++) {
                    if (medStep > 1.0e-4 && step[i] < 0.1 * medStep) {
                        stalls++;
                    }
                }
                // The largest CHANGE between two consecutive steps — the surge, and the one number
                // here that is bounded by something production declares: a craft tracking a
                // setpoint cannot change speed faster than the setpoint itself can ramp. A maximum
                // step says how fast it went; only this says how abruptly.
                double maxStepChange = 0.0;
                for (int i = 1; i < step.length; i++) {
                    maxStepChange = Math.max(maxStepChange, Math.abs(step[i] - step[i - 1]));
                }
                sb.append(",\"stepChangeBlocks\":{\"max\":").append(round(maxStepChange)).append('}')
                        .append(",\"stepBlocks\":{\"p50\":").append(round(medStep))
                        .append(",\"p95\":").append(round(pct(sortedStep, 0.95)))
                        .append(",\"max\":").append(round(sortedStep[sortedStep.length - 1]))
                        .append("},\"stalls\":").append(stalls)
                        // The SHAPE of the roughness, bounded to the tail of the window. A p95/p50
                        // ratio says a channel is uneven and can never say HOW: a periodic spike
                        // every N beats names a periodic writer, a random scatter names load, and a
                        // run of equal steps followed by one long one names a correction being
                        // applied in arrears. Those three want different fixes and the percentiles
                        // do not distinguish them.
                        .append(",\"lastSteps\":").append(tail(step, STEP_TAIL))
                        .append(",\"netMove\":[").append(round(sumDx)).append(',')
                        .append(round(sumDy)).append(',').append(round(sumDz)).append(']');
            }
            if (cmdSpeedCol >= 0) {
                double maxCmd = 0.0;
                for (int i = 0; i < n; i++) {
                    maxCmd = Math.max(maxCmd, cols[idx[i] * COLUMNS + cmdSpeedCol]);
                }
                sb.append(",\"cmdSpeedMax\":").append(round(maxCmd));
            }
            if (positional && secondPoseCol >= 0) {
                appendSecondPose(sb, idx, n);
            }
            appendPerTick(sb, idx, n, positional);

            // How many DISTINCT producers wrote into this window. One is the only healthy answer on
            // a channel whose clock has a single owner; more says the ring is not measuring one
            // thing, and says it in a way a doubled rate alone never could.
            sb.append(",\"writers\":").append(distinct(idx, n, COL_WRITER))
                    .append(",\"writerShips\":").append(distinct(idx, n, COL_WRITER2));

            // Both ends of the window, so any cumulative column (chunk arrivals, above all) can be
            // DIFFERENCED across it. A cumulative counter read only at the end can support a
            // correlation between legs and never an attribution inside one.
            appendRow(sb, ",\"first\":", idx[0]);
            appendRow(sb, ",\"last\":", idx[n - 1]);
            sb.append('}');
            return sb.toString();
        }

        /**
         * The SECOND pose's step series, under {@code mount} — the same numbers as the channel's
         * own, over the columns at {@link #secondPoseCol}.
         *
         * <p>Its whole value is that it is the SAME SAMPLE. A tick where the pilot's step is 0 and
         * the mount's is 2 says the mount moved and the pilot was not carried; 0 and 0 says the
         * freeze arrived already made, from the ship's pose or from the entity that tracks it. Two
         * separate runs could not tell those apart at all, and a second channel could not tell them
         * apart on the same TICK.</p>
         */
        private void appendSecondPose(StringBuilder sb, int[] idx, int n) {
            double[] step = new double[n - 1];
            for (int i = 1; i < n; i++) {
                int a = idx[i - 1] * COLUMNS + secondPoseCol;
                int b = idx[i] * COLUMNS + secondPoseCol;
                double dx = cols[b] - cols[a];
                double dy = cols[b + 1] - cols[a + 1];
                double dz = cols[b + 2] - cols[a + 2];
                step[i - 1] = Math.sqrt(dx * dx + dy * dy + dz * dz);
            }
            double[] sorted = step.clone();
            java.util.Arrays.sort(sorted);
            double maxChange = 0.0;
            for (int i = 1; i < step.length; i++) {
                maxChange = Math.max(maxChange, Math.abs(step[i] - step[i - 1]));
            }
            sb.append(",\"mount\":{\"stepBlocks\":{\"p50\":").append(round(median(sorted)))
                    .append(",\"p95\":").append(round(pct(sorted, 0.95)))
                    .append(",\"max\":").append(round(sorted[sorted.length - 1]))
                    .append("},\"stepChangeBlocks\":{\"max\":").append(round(maxChange))
                    .append("},\"lastSteps\":").append(tail(step, STEP_TAIL)).append('}');
        }

        /**
         * The window's account of itself IN TICKS — the half of this summary a verdict may rest on.
         *
         * <p>Reports, under {@code perTick}:</p>
         * <ul>
         *   <li>{@code ticks} / {@code span} — distinct ticks seen, and how many the window covers.
         *       </li>
         *   <li>{@code gaps} = {@code span - ticks}: ticks inside the window for which this channel
         *       produced NO sample. On a channel whose writer runs once per tick, that is the ship
         *       standing still for a tick and then catching up — the jerk itself, named.</li>
         *   <li>{@code maxPerTick} / {@code minPerTick}: how many samples one tick carried. A channel
         *       sampled once per tick contracts to 1 and 1; two samples in one tick is a lurch — or,
         *       measured 2026-09-21, a sampler taking both phases of a tick event and running at
         *       twice its clock, which no wall-clock summary could name. On the physics channel,
         *       which runs at its own rate against the world tick, the healthy answer is the RATIO
         *       of the two clocks and the number worth reading is {@code gaps}.</li>
         * </ul>
         *
         * <p><b>The step series is NOT here, deliberately.</b> It is reported per SAMPLE, above,
         * because a sample is the natural beat of each channel — and a per-TICK displacement built
         * out of a channel that samples faster than the tick is an artefact: with two to four
         * physics steps landing in one world tick, last-sample-to-last-sample alternates between two
         * and four steps' worth of ground and reports a metronomic craft as surging by a factor of
         * two. Measured on the first calibration run, where the per-sample series read
         * p50 = p95 = max = 0.667 blocks — perfectly even — while the per-tick one read a change of
         * 1.33. A caller wanting a per-tick bound scales the per-sample one by the ratio of the
         * declared rates, which it can do because both are declared.</p>
         *
         * <p>Emitted only where the samples carry ticks. A channel driven by something that is not a
         * tick — the render loop — says so by their absence rather than by a zero.</p>
         */
        private void appendPerTick(StringBuilder sb, int[] idx, int n, boolean positional) {
            long first = tick[idx[0]];
            long last = tick[idx[n - 1]];
            if (first < 0 || last < first) {
                return;
            }
            // One pass, ticks arriving in order: the newest sample of each tick is the pose that
            // tick ENDED at, which is what the next tick's displacement is measured from.
            long[] tickAt = new long[n];
            int[] lastSampleOf = new int[n];
            int[] samplesIn = new int[n];
            int ticks = 0;
            for (int i = 0; i < n; i++) {
                long t = tick[idx[i]];
                if (ticks > 0 && tickAt[ticks - 1] == t) {
                    lastSampleOf[ticks - 1] = idx[i];
                    samplesIn[ticks - 1]++;
                    continue;
                }
                tickAt[ticks] = t;
                lastSampleOf[ticks] = idx[i];
                samplesIn[ticks] = 1;
                ticks++;
            }
            int maxPerTick = 0;
            int minPerTick = Integer.MAX_VALUE;
            for (int i = 0; i < ticks; i++) {
                maxPerTick = Math.max(maxPerTick, samplesIn[i]);
                minPerTick = Math.min(minPerTick, samplesIn[i]);
            }
            long span = last - first + 1;
            sb.append(",\"perTick\":{\"ticks\":").append(ticks)
                    .append(",\"span\":").append(span)
                    .append(",\"gaps\":").append(Math.max(0L, span - ticks))
                    .append(",\"maxPerTick\":").append(maxPerTick)
                    .append(",\"minPerTick\":").append(ticks == 0 ? 0 : minPerTick)
                    .append('}');
        }

        /** How many beats of {@link #summary}'s per-beat step series are reported verbatim. */
        private static final int STEP_TAIL = 40;

        /** The last {@code max} entries of {@code values}, as a JSON array. Bounded on purpose: a
         *  full window is thousands of numbers and nobody reads those; forty beats is two seconds
         *  at tick rate, which is long enough to show a period and short enough to print. */
        private static String tail(double[] values, int max) {
            StringBuilder out = new StringBuilder("[");
            for (int i = Math.max(0, values.length - max); i < values.length; i++) {
                if (out.length() > 1) {
                    out.append(',');
                }
                out.append(round(values[i]));
            }
            return out.append(']').toString();
        }

        /** How many distinct values one column takes across the window. */
        private int distinct(int[] idx, int n, int column) {
            double[] seenTags = new double[n];
            int count = 0;
            for (int i = 0; i < n; i++) {
                double tag = cols[idx[i] * COLUMNS + column];
                boolean fresh = true;
                for (int j = 0; j < count; j++) {
                    if (seenTags[j] == tag) {
                        fresh = false;
                        break;
                    }
                }
                if (fresh) {
                    seenTags[count++] = tag;
                }
            }
            return count;
        }

        /** One sample's whole column row, under {@code name}. */
        private void appendRow(StringBuilder sb, String name, int sampleIndex) {
            int base = sampleIndex * COLUMNS;
            sb.append(name).append('[');
            for (int c = 0; c < COLUMNS; c++) {
                if (c > 0) {
                    sb.append(',');
                }
                sb.append(round(cols[base + c]));
            }
            sb.append(']');
        }

        private static double median(double[] sorted) {
            if (sorted.length == 0) {
                return 0.0;
            }
            return sorted[sorted.length / 2];
        }

        private static double pct(double[] sorted, double q) {
            if (sorted.length == 0) {
                return 0.0;
            }
            int i = (int) Math.floor(q * (sorted.length - 1));
            return sorted[Math.max(0, Math.min(sorted.length - 1, i))];
        }

        private static double round(double v) {
            if (Double.isNaN(v) || Double.isInfinite(v)) {
                return 0.0;
            }
            return Math.round(v * 1000.0) / 1000.0;
        }
    }
}
