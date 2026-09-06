package zmaster587.advancedRocketry.test.mixin;

import java.util.UUID;

import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.util.math.BlockPos;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import zmaster587.advancedRocketry.api.FreeFlightInput;
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

    @Inject(method = "setPilotInput", at = @At("HEAD"))
    private void arTest$pilotInputSet(FreeFlightInput input, CallbackInfo ci) {
        TestTrace.instrumentHere(INSTRUMENT);
        TestTrace.recordHere("pilot_input_set", arTest$posAndShip()
                + ",\"input\":\"" + (input == null ? "null" : "set") + "\"");
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
