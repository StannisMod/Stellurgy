package zmaster587.advancedRocketry.test.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import zmaster587.advancedRocketry.entity.EntityRocket;
import zmaster587.advancedRocketry.test.trace.TestTrace;

/**
 * A tier-1 rocket's engine state as events, on whichever side the rocket lives.
 *
 * <p>{@code rocket_flight_set} IS the production fact that a rocket's in-flight flag was written:
 * {@code EntityRocket.setInFlight(boolean)} is the one mutator every path goes through — the launch
 * ({@code startFreeFlight}, the descent timer), the touchdown, the orbit arrival, and the four
 * "make the player confirm the deorbit" branches. Every caller is already transition-guarded
 * ({@code isInFlight() && landed}, {@code !isInFlight()}, a {@code landedLatched} flag), so the seam
 * fires on an EDGE rather than per tick, and no filter is kept on the mixin: the pinned fact is that
 * the flag was WRITTEN, and a filter here would hide a redundant write, which is exactly the kind of
 * thing a test would want to see. The one bound on that: the descent-timer caller sits in
 * {@code onUpdate} and its guard reads {@code isInFlight()}, which ON THE CLIENT answers from the
 * data watcher — a value the server owns. While a server correction is in flight the client can
 * re-arm and re-record the same write on consecutive ticks, so a client-log chain reads the ring's
 * tail after a contested arrival, not a single edge. Recorded at HEAD, before the data-watcher
 * write, routed by the rocket's own world: a
 * server rocket writes the server log, a client-side call (the descent timer runs in
 * {@code onUpdate} on both sides) the client log. The record carries the entity id, the value being
 * set, and the rocket's height and vertical motion at that moment — the two numbers a "did it
 * lift / did it land" chain reads next.</p>
 *
 * <p>{@code rocket_ff_traced} IS a line of the free-flight lifecycle log production keeps for the
 * harness — {@code EntityRocket.ffTrace(String)}, the {@code [FF-TRACE/…]} logger the tests used to
 * grep the child JVM's log for. This injection is a BRIDGE over that production test-mode trace:
 * the trace call sites are a telemetry surface production should not carry, and they are scheduled
 * to be retired; when they go, this event goes with them and the facts they narrate (launch
 * accepted / rejected, liftoff, first free-flight tick, flight-assist re-engaged) must each be
 * recorded at their own seam. Until then the event carries the message verbatim, recorded at HEAD —
 * so it is taken BEFORE production's own {@code isTestMode()} gate and does not depend on the log
 * line ever being written. Eight of the nine call sites are one-shot lifecycle lines; the ninth,
 * {@code applyFreeFlightInput}, traces once per input and runs on BOTH sides — the key handler
 * applies it locally and then sends it, and the server applies the packet. Production already
 * de-duplicates upstream of this seam ({@code KeyBindings} sends only when the input DIFFERS from
 * the last one), so a steady held key traces once; what actually churns is a moving mouse, whose
 * cursor changes every client tick and can turn the 256-record ring — each side's own — in about
 * thirteen seconds. No further edge filter is kept here on purpose: the pinned fact is the trace
 * LINE, and a mixin-side filter would drop repeats production deliberately let through. A chain
 * that awaits a late trace after a long stick-waggle reads the ring's tail, not its head.</p>
 *
 * <p>SILENT about: WHICH caller set the flag (a touchdown and a deorbit confirm both read
 * {@code inFlight:false}); whether the write CHANGED anything (the callers guard for that, this
 * mixin does not re-check); the flight mode, the orbit flag, the fuel state, and the landing reason
 * — none of which cross this seam. It records nothing on a rocket whose world is not yet set, which
 * is exactly the NBT load path: the flag restored from a save
 * ({@code setInFlight(nbt.getBoolean("flight"))}) produces no event, so a chain must take a loaded
 * rocket's flight state from the world and not from this log.</p>
 *
 * <p>{@code rocket_ff_traced} is silent about every free-flight diagnostic production does NOT route
 * through {@code ffTrace} — the landing-reason dump is written straight to the logger under the same
 * test-mode gate and never reaches this seam, so an auto-land shows here only as the plain
 * {@code rocket_flight_set} that follows it, with no thrust or climb-authority numbers. And because
 * the message is carried verbatim, a chain that matches on its text is pinned to a production string
 * that the retirement of these call sites will delete; match on the seam's existence and on the
 * neighbouring events, not on the wording.</p>
 *
 * <p>Both seams cover {@code EntityStationDeployedRocket}, which extends the target and overrides
 * neither method — its own launch writes go through the woven {@code setInFlight}. They do not cover
 * a write to the {@code isInFlight} field or the {@code INFLIGHT} data-watcher entry that bypasses
 * the mutator: the two constructors clear the field directly, so a fresh rocket's {@code false} is
 * an initial condition this log never states, and any later bypass added to the target would be
 * invisible here with no complaint from this mixin.</p>
 */
@Mixin(EntityRocket.class)
public abstract class MixinEntityRocketEvents {

    private static final String INSTRUMENT = "rocket_events";

    @Inject(method = "setInFlight", at = @At("HEAD"))
    private void arTest$flightSet(boolean inFlight, CallbackInfo ci) {
        EntityRocket self = (EntityRocket) (Object) this;
        TestTrace.instrument(self, INSTRUMENT);
        if (self.world == null) {
            return;
        }
        TestTrace.record(self, "rocket_flight_set", "\"e\":" + self.getEntityId()
                + ",\"inFlight\":" + inFlight
                + ",\"y\":" + TestTrace.fmt(self.posY)
                + ",\"motionY\":" + TestTrace.fmt(self.motionY));
    }

    @Inject(method = "ffTrace", at = @At("HEAD"))
    private void arTest$ffTraced(String msg, CallbackInfo ci) {
        EntityRocket self = (EntityRocket) (Object) this;
        TestTrace.instrument(self, INSTRUMENT);
        if (self.world == null) {
            return;
        }
        TestTrace.record(self, "rocket_ff_traced", "\"e\":" + self.getEntityId()
                + ",\"msg\":\"" + TestTrace.json(msg == null ? "null" : msg) + "\"");
    }
}
