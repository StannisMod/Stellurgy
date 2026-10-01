package dev.stannismod.stellurgy.test.mixin;

import net.minecraft.util.math.BlockPos;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import dev.stannismod.stellurgy.api.StellurgyConfiguration;
import dev.stannismod.stellurgy.api.weapon.GunSpec;
import dev.stannismod.stellurgy.test.trace.TestTrace;
import dev.stannismod.stellurgy.tile.weapon.TileTurret;

/**
 * A gun's decisions as events: what it counted itself as, where it is pointing, whether it may fire
 * now, that a round left, and whether its beam is lit.
 *
 * <p>Production emits nothing for any of these; each is read at the gun's own seam. Every record is
 * filed against the world the gun stands in, through that world's side, so a server gun writes the
 * SERVER log and the client's copy of the same tile writes the CLIENT log. Only {@code turret_aim}
 * is written on both sides — the client runs the same traverse on the command it was sent — and the
 * rest return before their seam on a remote world. Read through {@code dev.stannismod.stellurgy.test.Weapons}.</p>
 *
 * <h2>The events</h2>
 *
 * <ul>
 *   <li><b>{@code turret_assembled}</b> — the HEAD of {@code resizeBufferFor}, which the tick calls
 *       exactly once after every re-walk of the build and nowhere else: {@code operable},
 *       {@code parts} and {@code beam} are the spec the walk just produced. One record per re-walk,
 *       so a gun whose parts land one probe command at a time records a partial build before the
 *       whole one; a reader waits for the part count it placed. SILENT about a gun that is never
 *       re-walked — a controller aboard a ship nobody has named returns before the walk.</li>
 *   <li><b>{@code turret_aim}</b> — the RETURN of {@code update}, on either side, EDGE-ONLY per tile
 *       on {@code (onTarget, manual, commanded, drive)}: the mount's own answer to "am I pointing where
 *       I was told", with the bearing it reached, the one it was told, and the drive state it is in
 *       (which the gun re-reads from its own condition every tick). SILENT about a slew that has not
 *       yet reached its command — the record is the arrival, not the travel.</li>
 *   <li><b>{@code turret_fire_decided}</b> — the RETURN of {@code canFireNow}, the conjunction of
 *       everything that must hold for a round to leave other than pointing the right way. The
 *       automatic path asks it only after the mount reports itself on target and the network is not
 *       holding fire, so a record from {@code caller = "auto"} is also the witness that the gun WAS
 *       on target in that tick. {@code permitted} is production's own answer, never re-derived;
 *       {@code drive}, {@code operable}, {@code energy}, {@code weapons} (the war switch) and
 *       {@code friendly} (production's own friend-or-foe answer), {@code locked} (production's own
 *       answer to "is the contact held well enough to shoot at", which is TRUE for any engagement
 *       that is not an acquisition), and the raw {@code cooldown} and {@code heat} are the inputs a
 *       reader needs to know which gun state the answer was given for — and, with every other input
 *       shown satisfied, which one refused. The heat and cooldown are the fields as they stand, not
 *       re-derived conjuncts: a reader states its own arrangement off them. EDGE-ONLY per gun on the
 *       whole payload, so a hot gun cooling on target writes one record a tick. SILENT
 *       about a gun that is NOT on target and about the beam family, which never asks it.</li>
 *   <li><b>{@code turret_fired}</b> — the RETURN of {@code launch} when it answered true: a round was
 *       admitted to the world. Carries the round's id. SILENT about a launch the substrate refused
 *       and about the beam family.</li>
 *   <li><b>{@code turret_launch_refused}</b> — the same RETURN when it answered false: the gun was
 *       permitted and the round was NOT admitted (no clear line of fire, or the substrate refused).
 *       EDGE-ONLY: once per run of refusals, reset by the next launch that succeeds.</li>
 *   <li><b>{@code turret_beam}</b> — the RETURN of {@code burnOneTick}, EDGE-ONLY per gun on
 *       {@code (lit, wanted, weapons, recharging)}: whether the beam burned this tick, whether the
 *       gun wanted it to, whether the war was on, and whether it has gone dark to save up. SILENT
 *       about a thrower, and about a beam gun under a hand (the manual path never burns).</li>
 *   <li><b>{@code turret_hold_decided}</b> — the RETURN of {@code isHoldingFire}, EDGE-ONLY per gun
 *       on {@code held}: whether the network told this gun to hold. The automatic path asks it only
 *       once the mount is on target (both families), so a {@code held:true} record is a gun on
 *       target that returned before its fire question. SILENT about a gun off target.</li>
 * </ul>
 *
 * <p>Every seam is required to match, so a renamed method fails the boot instead of going quiet. The
 * instrument names are one per seam, because {@code assertInstrumentRan} proves only that SOME hook
 * under a name ran.</p>
 */
@Mixin(TileTurret.class)
public abstract class MixinTileTurretEvents {

    private static final String FIRE_INSTRUMENT = "turret_fire_events";
    private static final String ASSEMBLY_INSTRUMENT = "turret_assembly_events";
    private static final String AIM_INSTRUMENT = "turret_aim_events";
    private static final String BEAM_INSTRUMENT = "turret_beam_events";
    private static final String HOLD_INSTRUMENT = "turret_hold_events";

    /** The last {@code turret_fire_decided} payload this gun recorded; null before its first. */
    @Unique
    private String stellurgyTest$lastDecision = null;

    /** The last {@code turret_aim} edge key this tile recorded; null before its first. */
    @Unique
    private String stellurgyTest$lastAim = null;

    /** The last {@code turret_beam} edge key this gun recorded; null before its first. */
    @Unique
    private String stellurgyTest$lastBeam = null;

    /** Whether this gun's last launch was refused and already said so. */
    @Unique
    private boolean stellurgyTest$launchRefused = false;

    /** The last {@code turret_hold_decided} answer this gun recorded; null before its first. */
    @Unique
    private String stellurgyTest$lastHold = null;

    @Shadow
    private int fireCooldown;

    @Shadow
    private boolean targetIsFriendly() {
        throw new AssertionError("shadowed");
    }

    @Shadow
    private boolean isLockedWellEnoughToFire() {
        throw new AssertionError("shadowed");
    }

    @Inject(method = "resizeBufferFor", at = @At("HEAD"), require = 1)
    private void stellurgyTest$assembled(GunSpec newSpec, CallbackInfo ci) {
        TileTurret self = (TileTurret) (Object) this;
        if (self.getWorld() == null || self.getWorld().isRemote) {
            return;
        }
        TestTrace.instrument(self.getWorld(), ASSEMBLY_INSTRUMENT);
        TestTrace.record(self.getWorld(), "turret_assembled", stellurgyTest$pos(self.getPos())
                + ",\"operable\":" + newSpec.isOperable()
                + ",\"parts\":" + newSpec.getPartCount()
                + ",\"beam\":" + newSpec.isBeam());
    }

    @Inject(method = "update", at = @At("RETURN"), require = 1)
    private void stellurgyTest$aimed(CallbackInfo ci) {
        TileTurret self = (TileTurret) (Object) this;
        if (self.getWorld() == null) {
            return;
        }
        TestTrace.instrument(self.getWorld(), AIM_INSTRUMENT);
        boolean onTarget = self.getMechanism().isOnTarget();
        String key = onTarget + "/" + self.isManuallyControlled() + "/" + self.getMechanism().hasCommand()
                + "/" + self.getMechanism().getDriveState();
        if (key.equals(stellurgyTest$lastAim)) {
            return;
        }
        stellurgyTest$lastAim = key;
        TestTrace.record(self.getWorld(), "turret_aim", stellurgyTest$pos(self.getPos())
                + ",\"onTarget\":" + onTarget
                + ",\"commanded\":" + self.getMechanism().hasCommand()
                + ",\"manual\":" + self.isManuallyControlled()
                + ",\"yaw\":" + self.getMechanism().getYaw()
                + ",\"pitch\":" + self.getMechanism().getPitch()
                + ",\"commandedYaw\":" + self.getMechanism().getCommandedYaw()
                + ",\"commandedPitch\":" + self.getMechanism().getCommandedPitch()
                + ",\"drive\":\"" + self.getMechanism().getDriveState().name() + "\""
                + ",\"remote\":" + self.getWorld().isRemote);
    }

    @Inject(method = "canFireNow", at = @At("RETURN"), require = 1)
    private void stellurgyTest$fireDecided(CallbackInfoReturnable<Boolean> cir) {
        TileTurret self = (TileTurret) (Object) this;
        if (self.getWorld() == null) {
            return;
        }
        TestTrace.instrument(self.getWorld(), FIRE_INSTRUMENT);
        // The manual trigger asks the same question from a probe or a seat; which caller asked is
        // part of what the answer means, because only the automatic path implies "on target".
        String caller = self.isManuallyControlled() ? "manual" : "auto";
        String payload = stellurgyTest$pos(self.getPos())
                + ",\"permitted\":" + cir.getReturnValue()
                + ",\"drive\":\"" + self.getMechanism().getDriveState().name() + "\""
                + ",\"operable\":" + self.getSpec().isOperable()
                + ",\"energy\":" + self.getEnergyStored()
                + ",\"weapons\":" + StellurgyConfiguration.getCurrentConfig().enableWeapons
                + ",\"friendly\":" + targetIsFriendly()
                + ",\"locked\":" + isLockedWellEnoughToFire()
                + ",\"cooldown\":" + fireCooldown
                + ",\"heat\":" + self.getHeat()
                + ",\"caller\":\"" + caller + "\"";
        if (payload.equals(stellurgyTest$lastDecision)) {
            return;
        }
        stellurgyTest$lastDecision = payload;
        TestTrace.record(self.getWorld(), "turret_fire_decided", payload);
    }

    @Inject(method = "launch", at = @At("RETURN"), require = 1)
    private void stellurgyTest$fired(String shipId, CallbackInfoReturnable<Boolean> cir) {
        TileTurret self = (TileTurret) (Object) this;
        if (self.getWorld() == null) {
            return;
        }
        TestTrace.instrument(self.getWorld(), FIRE_INSTRUMENT);
        if (!Boolean.TRUE.equals(cir.getReturnValue())) {
            // Refused after the gun was permitted: the round was never admitted — no clear line of
            // fire, or the substrate full. Edge-only until the next launch that succeeds.
            if (!stellurgyTest$launchRefused) {
                stellurgyTest$launchRefused = true;
                TestTrace.record(self.getWorld(), "turret_launch_refused", stellurgyTest$pos(self.getPos()));
            }
            return;
        }
        stellurgyTest$launchRefused = false;
        TestTrace.record(self.getWorld(), "turret_fired", stellurgyTest$pos(self.getPos())
                + ",\"shot\":" + self.getLastShotId()
                + ",\"drive\":\"" + self.getMechanism().getDriveState().name() + "\""
                + ",\"acquired\":" + (self.acquiredTrack() != null)
                + ",\"manual\":" + self.isManuallyControlled());
    }

    @Inject(method = "burnOneTick", at = @At("RETURN"), require = 1)
    private void stellurgyTest$beam(boolean wantsToFire, String shipId,
                                    CallbackInfoReturnable<Boolean> cir) {
        TileTurret self = (TileTurret) (Object) this;
        if (self.getWorld() == null) {
            return;
        }
        TestTrace.instrument(self.getWorld(), BEAM_INSTRUMENT);
        boolean weapons = StellurgyConfiguration.getCurrentConfig().enableWeapons;
        String key = cir.getReturnValue() + "/" + wantsToFire + "/" + weapons + "/"
                + self.isBeamRecharging();
        if (key.equals(stellurgyTest$lastBeam)) {
            return;
        }
        stellurgyTest$lastBeam = key;
        TestTrace.record(self.getWorld(), "turret_beam", stellurgyTest$pos(self.getPos())
                + ",\"lit\":" + cir.getReturnValue()
                + ",\"wanted\":" + wantsToFire
                + ",\"weapons\":" + weapons
                + ",\"recharging\":" + self.isBeamRecharging()
                + ",\"energy\":" + self.getEnergyStored());
    }

    @Inject(method = "isHoldingFire", at = @At("RETURN"), require = 1)
    private void stellurgyTest$holdDecided(CallbackInfoReturnable<Boolean> cir) {
        TileTurret self = (TileTurret) (Object) this;
        if (self.getWorld() == null || self.getWorld().isRemote) {
            return;
        }
        TestTrace.instrument(self.getWorld(), HOLD_INSTRUMENT);
        String key = String.valueOf(cir.getReturnValue());
        if (key.equals(stellurgyTest$lastHold)) {
            return;
        }
        stellurgyTest$lastHold = key;
        TestTrace.record(self.getWorld(), "turret_hold_decided", stellurgyTest$pos(self.getPos())
                + ",\"held\":" + cir.getReturnValue());
    }

    @Unique
    private static String stellurgyTest$pos(BlockPos pos) {
        return "\"pos\":\"" + (pos == null ? "null" : pos.getX() + "," + pos.getY() + "," + pos.getZ())
                + "\"";
    }
}
