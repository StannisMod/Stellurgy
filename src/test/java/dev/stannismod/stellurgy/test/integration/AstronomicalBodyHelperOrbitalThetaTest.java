package dev.stannismod.stellurgy.test.integration;

import org.junit.BeforeClass;
import org.junit.Test;
import dev.stannismod.stellurgy.test.MinecraftBootstrap;
import dev.stannismod.stellurgy.util.AstronomicalBodyHelper;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/**
 * {@code orbitalAngleWrapsCorrectly}.
 *
 * <p>Pins the orbit law through {@link AstronomicalBodyHelper#getOrbitalThetaAt}, which takes the
 * tick it is evaluated at — so the test states each tick instead of replacing the sided proxy that
 * {@link AstronomicalBodyHelper#getOrbitalTheta} reads the current one from. Lives in the
 * integration layer because the helper class references {@code Stellurgy}, whose loading needs the
 * Forge bootstrap {@link MinecraftBootstrap#ensure()} sets up.</p>
 *
 * <p>The production formula is</p>
 * <pre>
 * theta = ((worldTime % (24000 * period)) / (24000 * period)) * 2π
 * </pre>
 * <p>The modulo is the wrap. This test pins the wrap by probing the four
 * cardinal phases plus a multi-orbit, multi-cycle case that would catch a
 * missing modulo or a long-arithmetic overflow.</p>
 */
public class AstronomicalBodyHelperOrbitalThetaTest {

    @BeforeClass
    public static void bootstrap() {
        MinecraftBootstrap.ensure();
    }

    /**
     * red-witnessed: 2026-09-30, with {@code AstronomicalBodyHelper#getOrbitalThetaAt} at {@code return ((worldTick % periodTicks) / periodTicks) * (2d * Math.PI)} dropping the
     * modulo by the period, this fails at the one-orbit wrap with "expected:&lt;0.0&gt; but
     * was:&lt;6.283185307179586&gt;".
     */
    @Test
    public void orbitalAngleWrapsCorrectly() {
        // Earth-baseline orbit: distance=100, solarSize=1.0 -> period=48 (per
        // AstronomicalBodyHelper.getOrbitalPeriod docs). One full orbit takes
        // 24000 * 48 = 1_152_000 world ticks.
        // One AU. The comment above still describes the case; only the unit it is written in
        // has changed, from a hundredth of an AU to a length of 100 km.
        final int distance = AstronomicalBodyHelper.DISTANCE_UNITS_PER_AU;
        final float solarSize = 1.0f;
        final double period = AstronomicalBodyHelper.getOrbitalPeriod(distance, solarSize);
        final long oneOrbitTicks = (long) (24000d * period);

        // Phase 0: t=0 -> θ=0.
        assertEquals(0.0, AstronomicalBodyHelper.getOrbitalThetaAt(distance, solarSize, 0L), 1e-9);

        // Phase π/2: quarter orbit.
        assertEquals(Math.PI / 2,
                AstronomicalBodyHelper.getOrbitalThetaAt(distance, solarSize, oneOrbitTicks / 4), 1e-6);

        // Phase π: half orbit.
        assertEquals(Math.PI,
                AstronomicalBodyHelper.getOrbitalThetaAt(distance, solarSize, oneOrbitTicks / 2), 1e-6);

        // Wrap: a full orbit -> back to θ=0 (modulo collapses to 0).
        assertEquals(0.0,
                AstronomicalBodyHelper.getOrbitalThetaAt(distance, solarSize, oneOrbitTicks), 1e-6);

        // Wrap across many orbits: 7 full + a quarter -> θ should still be π/2.
        assertEquals("multiple wraps must collapse to the same cardinal phase",
                Math.PI / 2,
                AstronomicalBodyHelper.getOrbitalThetaAt(distance, solarSize,
                        oneOrbitTicks * 7L + oneOrbitTicks / 4L), 1e-6);

        // Stress: a world time near the long-arithmetic safe ceiling. The
        // result must still fit cleanly in [0, 2π) — no NaN, no Infinity.
        double huge = AstronomicalBodyHelper.getOrbitalThetaAt(distance, solarSize, Long.MAX_VALUE / 1024L);
        assertTrue("θ must be a real number even at huge world times: " + huge,
                !Double.isNaN(huge) && !Double.isInfinite(huge));
        assertTrue("θ must remain in [0, 2π): " + huge,
                huge >= 0.0 && huge < 2.0 * Math.PI);
    }
}
