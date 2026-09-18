package zmaster587.advancedRocketry.test.unit;

import org.junit.Test;

import zmaster587.advancedRocketry.space.SpaceSlotPool;
import zmaster587.advancedRocketry.space.SpaceSubsystem;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * Contract pins for the space-subsystem registration hygiene: the enable-gate decision surface
 * ({@link SpaceSubsystem#shouldRegister}) and the ephemeral-hyperspace folder target
 * ({@link SpaceSlotPool#unboundSlotSubfolder}). Pure — no server, no world.
 *
 * <p>The gate contract (a mechanic behind a config flag must, when off, register NOTHING): each guard
 * — the config flag, Valkyrien Skies presence, and once-per-session idempotence — must independently
 * veto registration. The OFF-flag pin is the regression guard: it fails the moment the flag stops
 * fully disabling the subsystem. Note what is deliberately NOT a guard: the JVM's test property.
 * Space registers wherever the mod runs, so a diagnostic session cannot disable the subsystem it is
 * diagnosing.</p>
 *
 * <p>The folder pin fixes the on-disk path the hyperspace wipe deletes, so it can never target the
 * wrong directory, and so the path stays stable (it is also what {@code WorldProviderSpaceSlot}
 * reads/writes for an unbound slot).</p>
 */
public class SpaceRegistrationHygieneTest {

    // ---- the gate: shouldRegister(alreadyBuilt) ---------------------------------------------
    //
    // BOTH CONDITIONAL TESTS HERE WERE DELETED RATHER THAN RELAXED, on 2026-09-18, because each
    // pinned a contract about something that no longer exists:
    //
    //   * `theDisabledFlagFullyStandsDown` pinned `enableSpaceSubsystem=false` vetoing
    //     registration. The flag is gone (maintainer: "давай вообще уберём условие регистрации
    //     космоса, он слишком централен") — space is the mod's subject, not one of its features.
    //   * `noValkyrienSkiesMeansNothingToHost` pinned standing down when VS is absent. VS is
    //     VENDORED into this jar (`build.gradle`: "VS is a mandatory part of the mod"), so the
    //     condition could only fire for a stripped or repacked jar — a broken build, for which
    //     silently registering no space at all was the worst available answer.
    //
    // What is left is idempotence, which is not a gate on the environment at all.

    @Test
    public void registersOnEveryWorkingInstall() {
        assertTrue("not yet built -> register. Since the flag and the VS probe were both removed,"
                        + " this is the only outcome an operator or an environment can reach",
                SpaceSubsystem.shouldRegister(false));
    }

    @Test
    public void anAlreadyBuiltSessionDoesNotReRegister() {
        assertFalse("a single-player re-open reuses the JVM-global registration",
                SpaceSubsystem.shouldRegister(true));
    }

    // ---- ephemeral hyperspace: the wipe targets exactly the unbound-slot folder ------------

    @Test
    public void unboundSlotFolderHasTheStableOnDiskPath() {
        assertEquals("advRocketry/spacepool/slot7", SpaceSlotPool.unboundSlotSubfolder(7));
        assertEquals("advRocketry/spacepool/slot-2", SpaceSlotPool.unboundSlotSubfolder(-2));
    }
}
