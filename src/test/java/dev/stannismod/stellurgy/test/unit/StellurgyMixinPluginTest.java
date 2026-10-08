package dev.stannismod.stellurgy.test.unit;

import org.junit.Test;
import dev.stannismod.stellurgy.mixin.StellurgyMixinPlugin;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * Disableability contract for the per-dimension WorldInfo MIXINS.
 *
 * <p>{@link StellurgyMixinPlugin#shouldApply} is the pure decision the mixin runtime
 * consults per target class: the three WorldInfo mixins — {@code
 * MixinWorldServerMulti} (wrapper install), {@code MixinWorldServer} (per-dim
 * time / sleep) and {@code MixinPlayerList} (weather sync) — are woven in iff
 * the {@code perDimWorldInfo} master flag is enabled; every other Stellurgy mixin
 * always applies. This pins the promise that "perDimWorldInfo off in the config"
 * means those mixins aren't even woven (not merely no-ops at runtime), AND that
 * the per-dim TIME mixin rides the SAME master flag — so turning the weather
 * sub-toggle off can never accidentally un-weave per-dim time.</p>
 *
 * <p><b>Why no end-to-end weave test.</b> Whether a mixin is actually woven is
 * decided once, at target-class load, from the config snapshot taken when the
 * coremod constructs the plugin — before any test can intervene, and frozen for
 * the JVM's life. A single test JVM can't load the same target class twice
 * under two different configs to observe weave-vs-no-weave. The runtime
 * <i>effect</i> of the wrapper is already covered by {@code WeatherBaselineTest}
 * (wrapping on) and {@code WeatherCycleDisableTest} (cycle off), so the residual
 * value of an end-to-end weave assertion is low. This unit test pins the gating
 * decision itself, which is the part this fix introduced.</p>
 */
public class StellurgyMixinPluginTest {

    private static final String WORLD_SERVER_MULTI =
            "dev.stannismod.stellurgy.mixin.MixinWorldServerMulti";
    private static final String PLAYER_LIST =
            "dev.stannismod.stellurgy.mixin.MixinPlayerList";
    private static final String WORLD_SERVER =
            "dev.stannismod.stellurgy.mixin.MixinWorldServer";
    private static final String GRAVITY =
            "dev.stannismod.stellurgy.mixin.MixinEntityGravity";
    private static final String BLOCK_PLACE =
            "dev.stannismod.stellurgy.mixin.MixinWorldSetBlockState";

    /** Pins INV-MIX-01 (the WorldInfo mixins weave iff perDimWorldInfo is on and all other mixins always weave). */
    @Test
    public void worldInfoMixinsApplyWhenPerDimWorldInfoEnabled() {
        assertTrue(StellurgyMixinPlugin.shouldApply(true, WORLD_SERVER_MULTI));
        assertTrue(StellurgyMixinPlugin.shouldApply(true, PLAYER_LIST));
        assertTrue(StellurgyMixinPlugin.shouldApply(true, WORLD_SERVER));
    }

    /** Pins INV-MIX-01 (the WorldInfo mixins weave iff perDimWorldInfo is on and all other mixins always weave). */
    @Test
    public void worldInfoMixinsSkippedWhenPerDimWorldInfoDisabled() {
        assertFalse(StellurgyMixinPlugin.shouldApply(false, WORLD_SERVER_MULTI));
        assertFalse(StellurgyMixinPlugin.shouldApply(false, PLAYER_LIST));
        // The per-dim TIME mixin is gated by the SAME master flag, so disabling
        // the subsystem un-weaves it too — no weather/time leak between flags.
        assertFalse(StellurgyMixinPlugin.shouldApply(false, WORLD_SERVER));
    }

    /** Pins INV-MIX-01 (the WorldInfo mixins weave iff perDimWorldInfo is on and all other mixins always weave). */
    @Test
    public void nonWorldInfoMixinsAlwaysApplyRegardlessOfFlag() {
        // Gravity / atmosphere block-place are unrelated to the WorldInfo
        // subsystem: they must weave whether perDimWorldInfo is on or off.
        assertTrue(StellurgyMixinPlugin.shouldApply(true, GRAVITY));
        assertTrue(StellurgyMixinPlugin.shouldApply(false, GRAVITY));
        assertTrue(StellurgyMixinPlugin.shouldApply(true, BLOCK_PLACE));
        assertTrue(StellurgyMixinPlugin.shouldApply(false, BLOCK_PLACE));
    }

    /**
     * The per-dim TIME mixin carries a SECOND mechanic — the refusal to skip time at all — and that
     * one does not need a per-dimension clock. So it is woven whenever either mechanic is wanted.
     *
     * <p>Without this, a pack that runs {@code perDimWorldInfo=false} and turns a time-skip flag off
     * would get a flag that does nothing: the class carrying its only seam would never be woven, and
     * nothing anywhere would say so. That is the exact shape of a config option that lies.</p>
     */
    @Test
    public void theTimeMixinIsWovenWheneverEitherMechanicNeedsIt() {
        // The old reason, unchanged: per-dimension time.
        assertTrue(StellurgyMixinPlugin.shouldApply(true, true, true, WORLD_SERVER));
        // The new reason: a locked skip, with no per-dim clock in the picture at all.
        assertTrue("a locked PLANET skip needs the seam even with the master off",
                StellurgyMixinPlugin.shouldApply(false, false, true, WORLD_SERVER));
        assertTrue("...and so does a locked OVERWORLD skip",
                StellurgyMixinPlugin.shouldApply(false, true, false, WORLD_SERVER));
        // And when nothing needs it, it stays un-woven — or the gate is not a gate.
        assertFalse("nothing wants it: master off and both skips allowed",
                StellurgyMixinPlugin.shouldApply(false, true, true, WORLD_SERVER));
        // Its two neighbours are NOT dragged along: they only ever served the per-dim clock.
        assertFalse(StellurgyMixinPlugin.shouldApply(false, false, false, WORLD_SERVER_MULTI));
        assertFalse(StellurgyMixinPlugin.shouldApply(false, false, false, PLAYER_LIST));
    }
}
