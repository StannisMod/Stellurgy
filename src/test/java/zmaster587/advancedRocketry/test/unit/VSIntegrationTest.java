package zmaster587.advancedRocketry.test.unit;

import org.junit.Test;
import zmaster587.advancedRocketry.integration.vs.VSIntegration;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/**
 * Presence contract for the Valkyrien Skies integration.
 *
 * <p>VS is <b>vendored into Advanced Rocketry</b> — its source is compiled into AR's own jar and its
 * libraries are ordinary runtime dependencies, so VS is a mandatory part of AR and is always on the
 * runtime classpath (the 3.0.0 "own the physics stack" decision). {@code isAvailable()} must
 * therefore report <b>present</b>, and {@link VSIntegration#init()} must resolve the VS API without
 * throwing.</p>
 *
 * <p><b>This is the smoke test that kept {@code isAvailable()} alive.</b> It is not a gate any more
 * and no production caller branches on it: this class asks whether the vendoring actually worked in
 * THIS build, which is the one question the answer can still be interesting for.</p>
 *
 * <p>Presence is detected by a classpath probe, not a modid — VS is no longer registered as its own
 * mod (it is hosted by AR's single mod container), so {@code Loader.isModLoaded("valkyrienskies")}
 * would be false.</p>
 */
public class VSIntegrationTest {

    // The availability assertion that used to stand here is gone with the method it called.
    // `isAvailable()` was a `Class.forName` for a class compiled into this jar: it answered a
    // constant, and the test asserted the constant. What remains is the question that can actually
    // fail — whether `init()` resolves the API — and a `NoClassDefFoundError` there is the same
    // catastrophe the old assertion was reaching for, reported where it happens.

    @Test
    public void initResolvesTheVsApiWhenPresent() {
        // With VS present this runs the real integration path (logs + touches a VS API type).
        // A NoClassDefFoundError here would mean the vendored VS classpath did not resolve.
        VSIntegration.init();
    }

    @Test
    public void modidIsStillTheVendoredModsOwnName() {
        // Still "valkyrienskies" — but NOT, as this test used to claim, "the registry DOMAIN for VS
        // blocks/items". Measured 2026-09-22: the vendored blocks and items reach the registry
        // through a bare `setRegistryName(name)`, which Forge resolves against the ACTIVE container
        // — the host, advancedrocketry — while only the tile entities pass this id explicitly. The
        // two halves disagree and the assets sit under a third path; see VendoredAssetDomainTest.
        // What this constant is, then, is the vendored mod's own name, used where a registration
        // asks for it by hand.
        assertEquals("valkyrienskies", VSIntegration.MODID);
    }
}
