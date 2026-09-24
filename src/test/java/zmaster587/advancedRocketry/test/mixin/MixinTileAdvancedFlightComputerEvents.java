package zmaster587.advancedRocketry.test.mixin;

import java.util.UUID;

import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.util.math.BlockPos;

import org.objectweb.asm.Opcodes;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import zmaster587.advancedRocketry.api.FreeFlightInput;
import zmaster587.advancedRocketry.integration.vs.VSIntegration;
import zmaster587.advancedRocketry.test.trace.TestTrace;
import zmaster587.advancedRocketry.tile.TileAdvancedFlightComputer;

/**
 * The flight computer's five decisions as events — what a pilot's hands, a probe, a broken seat,
 * a reload and an empty seat each make the ship's computer DO.
 *
 * <p>All five are read at the computer's own seams, never re-derived; production emits nothing.
 * The computer only runs its control loop on the server (its {@code update} returns on a remote
 * world), so every record below lands in the SERVER log in practice; each is routed by the
 * calling thread's side ({@code recordHere}) rather than pinned to the server, so a call that
 * did happen on a client would say so by where it landed instead of being dropped.</p>
 *
 * <h2>The events</h2>
 *
 * <ul>
 *   <li><b>{@code pilot_input_set}</b> — the computer was HANDED an input, or had it taken away:
 *       the HEAD of {@code setPilotInput}. {@code input} is {@code "set"} or {@code "null"}. Every
 *       call is recorded, because every caller already sends only on a change: the seat forwards
 *       what the client sends, and the client sends only when the intent differs from the last
 *       one it sent; the riderless dummy clears once, gated on the input being non-null; the probe
 *       sends per command. SILENT about the input's content (throttles, rates) and about a write
 *       that bypasses the setter — the station-lost path and {@code invalidate} null the field
 *       directly, and those are theirs to announce ({@code control_station_lost} does).</li>
 *   <li><b>{@code cruise_setpoint_changed}</b> — the cruise the ship will hold when the pilot lets
 *       go changed. Two seams, one event: the pilot's ramp reaches {@code markCruiseDirty} only on
 *       a tick the ramped triple actually differs (the field is already assigned when its HEAD
 *       runs, so the record reads the field), {@code via = "pilot"}; a caller commanding one
 *       directly reaches {@code commandCruise}'s HEAD, {@code via = "probe"} — that name is
 *       production's only caller today, so {@code caller} is carried too and is what to read if
 *       another appears. Chatty while a throttle is held: a full ramp is 60 ticks of change, and
 *       the ring holds 256 per type. SILENT about a re-capture on Assist enable (it lands in the
 *       ramp's own comparison a tick later, or not at all if equal) and about the two zeroings
 *       ({@code onControlStationLost}, {@code invalidate}) that assign the field without marking
 *       it.</li>
 *   <li><b>{@code control_station_lost}</b> — the pilot's seat was destroyed under a surviving
 *       computer: the HEAD of {@code onControlStationLost}, with whether an input was live to drop
 *       and the caller trail, since the seat's block-break is the one caller today and a second one
 *       would be the finding.</li>
 *   <li><b>{@code station_keeping_restored}</b> — the persisted flight settings came back off NBT:
 *       the RETURN of {@code readFromNBT} (the method has a single exit). The tile has no world
 *       and possibly no position of its own yet, so {@code x,y,z} are read from the tag the way
 *       vanilla reads them. Recorded only when the tag carries production's own
 *       {@code stationKeeping} key — the tag {@code writeToNBT} produces always does, while the
 *       bare vanilla update tag the client applies on chunk load never does, and a client reading
 *       {@code false} out of a tag that said nothing is not a restore. SILENT, therefore, about a
 *       client-side read; and about whether the restored hold then HOLDS — that is the next
 *       event's.</li>
 *   <li><b>{@code unmanned_hold_decided}</b> — with nobody flying, the computer decided whether to
 *       hold station (physics on, hover at the reference attitude) or stay inert. The narrowest
 *       point that sees BOTH outcomes is the instruction just before the branch: the single
 *       {@code isTestMode()} call {@code update} makes for its own trace, which every non-returning
 *       path passes and every early return (remote, no attitude, hyperspace, a crossing that cut
 *       the tile, an autopilot tick that published) skips. The branch reads exactly two things —
 *       whether the pilot input it took at its head is null, and the persisted station-keeping
 *       flag — and both are read here from the same fields in the same tick. That the field read
 *       here equals the local production captured at the method's head is not an assumption about
 *       ordering: the input is only ever written from the seat's packet handler, and the packet
 *       layer schedules that handler onto the server's own tick thread before running it, so no
 *       other thread can move it under the tick. The two can diverge only through a write on THIS
 *       thread between the two reads — a tile cut out from under the computer mid-tick — which is a
 *       finding in its own right rather than noise. EDGE-ONLY: recorded when the decision differs
 *       from the last one this tile made, and a tick with a pilot aboard forgets the last one, so
 *       the first unmanned tick after a dismount records again. SILENT about an auto-takeoff tick
 *       (the autopilot is flying, not the hold) and about the hold's effect on the ship.</li>
 * </ul>
 *
 * <h2>The one fragile anchor, and how it fails</h2>
 *
 * <p>Four of these five seams are a method's HEAD or its single RETURN and cannot miss. The fifth,
 * {@code unmanned_hold_decided}, is an {@code INVOKE} descriptor: it rides the one call
 * {@code update} makes to the probe tree's test-mode check for its own playtest trace, and it is
 * confirmed unique in that method by reading it — no ordinal is pinned because there is nothing to
 * disambiguate. A wrong descriptor is loud (the target fails to load, and the harness echoes the
 * child JVM's fatal). A descriptor that becomes STALE — that trace block deleted, or its condition
 * hoisted into a helper — is not: this config declares no injector requirement, so the injection
 * simply matches nothing and the event stops, with no line anywhere saying so.</p>
 *
 * <p>Which is why that seam does not share the others' instrument name. Were it to, a run in which
 * it alone had died would still report the instrument as having executed — on the strength of the
 * four seams that cannot die — and its silence would read as "the ship was never unmanned". It
 * reports under its own name, so an empty {@code unmanned_hold_decided} can be told from a dead
 * one. If it ever does go quiet, the cheapest replacement anchor is the branch's own
 * {@code ensureShipPhysicsEnabled} call under a pinned ordinal, which is strictly more fragile,
 * not less.</p>
 */
@Mixin(TileAdvancedFlightComputer.class)
public abstract class MixinTileAdvancedFlightComputerEvents {

    private static final String INSTRUMENT = "flight_computer_events";

    /** The unmanned seam reports separately: it is the only anchor here that can go stale in
     *  silence, and a shared name would let the four safe seams vouch for it. */
    private static final String INSTRUMENT_UNMANNED = "flight_computer_unmanned_events";

    /** Production's own NBT key for the persisted hold flag — the witness that a tag was written
     *  by {@code writeToNBT} and not by vanilla's bare update-tag path. */
    private static final String NBT_STATION_KEEPING = "stationKeeping";

    @Shadow private UUID shipId;
    @Shadow private double[] velocitySetpoint;
    @Shadow private boolean stationKeeping;
    @Shadow private boolean flightAssistEnabled;
    @Shadow private boolean entryLatched;

    /** The last unmanned decision this tile recorded; {@code null} while a pilot is flying or
     *  before the first one, so the next unmanned tick is a fresh edge. */
    @Unique
    private Boolean arTest$lastHold = null;

    /**
     * The computer was handed an input — and WHAT it was handed, not merely that it was handed
     * something.
     *
     * <p>This used to write {@code "input":"null"|"set"}, and that could not answer the one question
     * a still-turning craft raises. The computer LATCHES: a {@code null} leaves the last command in
     * force, and the only thing that stops a rotation is an IDLE input, which the client sends ONCE
     * on the tick its cursor enters the dead-zone and never repeats. So "ten inputs were accepted"
     * is compatible with both "the stop arrived and was ignored" and "the stop never arrived", which
     * send a reader to opposite subsystems.</p>
     *
     * <p>So the record carries the discriminator — {@code idle} as its own value, distinct from
     * {@code set} — and the three rotation channels this craft's residual rate is about. Measured
     * 2026-09-14: a craft went on turning at 0.5-1.2 rad/s with its pilot's cursor provably inside
     * the dead-zone, in the pit and again in open air, and nothing here could say which half it was.</p>
     */
    @Inject(method = "setPilotInput", at = @At("HEAD"))
    private void arTest$pilotInputSet(FreeFlightInput input, CallbackInfo ci) {
        TestTrace.instrumentHere(INSTRUMENT);
        String what = input == null ? "null" : (input.isIdle() ? "idle" : "set");
        String channels = input == null ? ""
                : ",\"yaw\":" + input.yawInput
                        + ",\"pitch\":" + input.pitchInput
                        + ",\"roll\":" + input.rollInput;
        // WHICH craft, in the spellings a caller holds: the world it is ticked in, and the physics
        // mod's own id of the ship whose chunk claim holds this computer (an identity, not a
        // proximity). `ship` is the computer's durable id, minted lazily, so it cannot be the only one.
        TileAdvancedFlightComputer self = (TileAdvancedFlightComputer) (Object) this;
        String dim = self.getWorld() == null ? "null"
                : String.valueOf(self.getWorld().provider.getDimension());
        String vsShip = self.getWorld() == null || self.getPos() == null ? null
                : VSIntegration.shipIdOwningBlock(self.getWorld(), self.getPos());
        TestTrace.recordHere("pilot_input_set", arTest$posAndShip()
                + ",\"dim\":" + dim + ",\"vsShip\":\"" + vsShip + "\""
                + ",\"input\":\"" + what + "\"" + channels);
    }

    @Inject(method = "markCruiseDirty", at = @At("HEAD"))
    private void arTest$cruiseRamped(CallbackInfo ci) {
        TestTrace.instrumentHere(INSTRUMENT);
        // The ramp assigns the field and THEN marks it, so the field is this tick's new triple.
        double[] sp = velocitySetpoint;
        TestTrace.recordHere("cruise_setpoint_changed", arTest$posAndShip()
                + arTest$cruise(sp == null || sp.length < 3 ? 0.0 : sp[0],
                        sp == null || sp.length < 3 ? 0.0 : sp[1],
                        sp == null || sp.length < 3 ? 0.0 : sp[2])
                + ",\"via\":\"pilot\"");
    }

    @Inject(method = "commandCruise", at = @At("HEAD"))
    private void arTest$cruiseCommanded(double forward, double right, double up, CallbackInfo ci) {
        TestTrace.instrumentHere(INSTRUMENT);
        // HEAD: the field is not yet assigned, so the record reads the arguments production is
        // about to store.
        TestTrace.recordHere("cruise_setpoint_changed", arTest$posAndShip()
                + arTest$cruise(forward, right, up)
                + ",\"via\":\"probe\",\"caller\":\"" + TestTrace.json(TestTrace.callerTrail()) + "\"");
    }

    @Inject(method = "onControlStationLost", at = @At("HEAD"))
    private void arTest$controlStationLost(CallbackInfo ci) {
        TestTrace.instrumentHere(INSTRUMENT);
        TileAdvancedFlightComputer self = (TileAdvancedFlightComputer) (Object) this;
        TestTrace.recordHere("control_station_lost", arTest$pos()
                + ",\"hadPilotInput\":" + (self.pilotInput != null)
                + ",\"caller\":\"" + TestTrace.json(TestTrace.callerTrail()) + "\"");
    }

    @Inject(method = "readFromNBT", at = @At("RETURN"))
    private void arTest$restored(NBTTagCompound nbt, CallbackInfo ci) {
        TestTrace.instrumentHere(INSTRUMENT);
        if (nbt == null || !nbt.hasKey(NBT_STATION_KEEPING)) {
            return; // vanilla's bare update tag, or nothing: no settings were restored from it
        }
        TestTrace.recordHere("station_keeping_restored",
                "\"x\":" + nbt.getInteger("x") + ",\"y\":" + nbt.getInteger("y")
                + ",\"z\":" + nbt.getInteger("z")
                + ",\"ship\":\"" + shipId + "\""
                + ",\"stationKeeping\":" + stationKeeping
                + ",\"flightAssist\":" + flightAssistEnabled
                + ",\"entryLatched\":" + entryLatched);
    }

    @Inject(method = "update",
            at = @At(value = "INVOKE",
                    target = "Lzmaster587/advancedRocketry/command/test/TestProbeCommandRegistration;"
                            + "isTestMode()Z"))
    private void arTest$unmannedHoldDecided(CallbackInfo ci) {
        TestTrace.instrumentHere(INSTRUMENT_UNMANNED);
        TileAdvancedFlightComputer self = (TileAdvancedFlightComputer) (Object) this;
        if (self.pilotInput != null) {
            // A pilot is flying: not an unmanned decision, and the next unmanned tick is a fresh one.
            arTest$lastHold = null;
            return;
        }
        boolean held = stationKeeping;
        if (arTest$lastHold != null && arTest$lastHold == held) {
            return;
        }
        arTest$lastHold = held;
        TestTrace.recordHere("unmanned_hold_decided", arTest$pos()
                + ",\"held\":" + held
                + ",\"stationKeeping\":" + stationKeeping
                + ",\"flightAssist\":" + flightAssistEnabled);
    }

    /**
     * The first tick each computer INSTANCE is ever given, and the moment one is invalidated.
     *
     * <p>These answer a question none of the five above can: <b>is the object being ticked the same
     * object a probe reads?</b> A tile's identity is invisible from outside — a reply says
     * {@code tickCensus:"0/0"} whether the computer has never run or whether the caller is holding a
     * replacement that was created a moment ago — and vanilla re-creates a tile in place whenever the
     * chunk's map holds an invalidated one, WITHOUT reading NBT, so that replacement leaves no trace
     * in any of the other records here. The identity hash is carried on both, so two records with
     * different hashes at one position are a tile that was replaced, and no record at all is a
     * computer that genuinely never ran.</p>
     *
     * <p>Their own instrument name, for the reason the unmanned seam has one: these two are the
     * evidence that the OTHER events' silence means what it appears to mean, so they must not be
     * vouched for by seams that cannot fail.</p>
     */
    private static final String INSTRUMENT_LIFECYCLE = "flight_computer_lifecycle_events";

    /** Whether this instance has already recorded its first tick. */
    @Unique
    private boolean arTest$tickedOnce = false;

    @Inject(method = "update", at = @At("HEAD"))
    private void arTest$firstTick(CallbackInfo ci) {
        TestTrace.instrumentHere(INSTRUMENT_LIFECYCLE);
        if (arTest$tickedOnce) {
            return; // EDGE-ONLY: one record per instance, or this is every tile every tick
        }
        arTest$tickedOnce = true;
        TestTrace.recordHere("afc_first_tick", arTest$posAndShip() + arTest$identity());
    }

    @Inject(method = "invalidate", at = @At("HEAD"))
    private void arTest$invalidated(CallbackInfo ci) {
        TestTrace.instrumentHere(INSTRUMENT_LIFECYCLE);
        TestTrace.recordHere("afc_invalidated", arTest$posAndShip() + arTest$identity()
                + ",\"tickedAtLeastOnce\":" + arTest$tickedOnce
                + ",\"caller\":\"" + TestTrace.json(TestTrace.callerTrail()) + "\"");
    }

    /** This OBJECT, as distinct from the position it sits at — two tiles can share the second. */
    /**
     * The autopilot REFUSED a climb: the corridor overhead is blocked, so it disengaged and handed
     * the ship back to manual.
     *
     * <p>This is a surfaced decline — a normal outcome the pilot is told about — and it is the one
     * decision of the autopilot's that nothing else here can see. {@code unmanned_hold_decided} is
     * about a computer with nobody flying it, and the {@code afc_*} pair is lifecycle; a blocked
     * corridor changes neither, which is why the test waiting for it had nothing to link on and
     * polled {@code auto-takeoff … status} for {@code engaged} going false instead.</p>
     *
     * <p>The seam is {@code driveAutoTakeoff}'s own RETURN, and the verdict is the return value
     * rather than a re-derivation: it answers {@code false} on exactly the path that clears
     * {@code autoTakeoffEngaged} and messages the seated pilot. A method RETURN cannot go stale in
     * silence the way the {@code INVOKE} anchor above it can, so this shares the safe instrument
     * name.</p>
     *
     * <p>EDGE-ONLY in practice without needing a flag: the decline clears the engaged latch, and
     * {@code update} only calls this method while it is set, so a blocked corridor produces one
     * record and not one per tick. SILENT about the climb itself (a cleared corridor returns true
     * and says nothing) and about a disengage by any other route.</p>
     */
    @Inject(method = "driveAutoTakeoff", at = @At("RETURN"))
    private void arTest$autoTakeoffDecided(CallbackInfoReturnable<Boolean> cir) {
        TestTrace.instrumentHere(INSTRUMENT);
        if (Boolean.TRUE.equals(cir.getReturnValue())) {
            return; // a corridor that was clear is the climb, and the climb has its own records
        }
        TestTrace.recordHere("auto_takeoff_declined", arTest$posAndShip()
                + ",\"stationKeeping\":" + stationKeeping);
    }

    /**
     * The post-descent entry latch RELEASED: the ship has been at or below its entry line, and the
     * on-ramp is armed again.
     *
     * <p>Its arrival already leaves a record: the descent sets it on the source ship before the cut
     * and it rides the tile's NBT, so the destination's {@code station_keeping_restored} carries
     * {@code entryLatched}. (The setting itself does not — {@code descent_requested} is the
     * descent's verdict, and a cut that fails after the latch still answers false.) The release had
     * none, so a
     * test that needed "he has been below the line" sampled the pilot's altitude until it liked
     * it, under a budget that was really a claim about how high the previous leg had climbed.</p>
     *
     * <p>The seam is the one write {@code update} makes to the field, read AFTER it, so the record
     * is the release and nothing else ({@code latchEntryUntilBelowTheLine} and {@code readFromNBT}
     * are the other writers, in other methods). {@code shipY} and {@code ceiling} are read again
     * here from the same sources the branch compared, in the same tick. Its own instrument name, for
     * the unmanned seam's reason: a FIELD anchor that goes stale matches nothing and says nothing.
     * SILENT about a latch that never releases — that is the absence the caller's budget reports.</p>
     */
    @Inject(method = "update",
            at = @At(value = "FIELD",
                    target = "Lzmaster587/advancedRocketry/tile/TileAdvancedFlightComputer;entryLatched:Z",
                    opcode = Opcodes.PUTFIELD, shift = At.Shift.AFTER))
    private void arTest$entryLatchReleased(CallbackInfo ci) {
        TestTrace.instrumentHere(INSTRUMENT_LATCH);
        TileAdvancedFlightComputer self = (TileAdvancedFlightComputer) (Object) this;
        double[] shipPos = VSIntegration.getShipWorldPosition(self.getWorld(), self.getPos());
        TestTrace.recordHere("entry_latch_released", arTest$posAndShip()
                + ",\"entryLatched\":" + entryLatched
                + ",\"shipY\":" + (shipPos == null ? "null" : TestTrace.fmt(shipPos[1]))
                + ",\"ceiling\":" + self.entryCeiling());
    }

    private static final String INSTRUMENT_LATCH = "flight_computer_latch_events";

    @Unique
    private String arTest$identity() {
        return ",\"identity\":" + System.identityHashCode(this);
    }

    @Unique
    private String arTest$pos() {
        BlockPos pos = ((TileAdvancedFlightComputer) (Object) this).getPos();
        return "\"pos\":\"" + (pos == null ? "null"
                : pos.getX() + "," + pos.getY() + "," + pos.getZ()) + "\"";
    }

    @Unique
    private String arTest$posAndShip() {
        return arTest$pos() + ",\"ship\":\"" + shipId + "\"";
    }

    @Unique
    private static String arTest$cruise(double forward, double right, double up) {
        return ",\"forward\":" + TestTrace.fmt(forward) + ",\"right\":" + TestTrace.fmt(right)
                + ",\"up\":" + TestTrace.fmt(up);
    }
}
