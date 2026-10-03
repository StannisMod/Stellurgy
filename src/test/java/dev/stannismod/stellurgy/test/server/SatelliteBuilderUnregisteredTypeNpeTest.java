package dev.stannismod.stellurgy.test.server;

import dev.stannismod.stellurgy.test.Reply;
import org.junit.Test;

import dev.stannismod.stellurgy.test.FixtureSite;

import static org.junit.Assert.assertTrue;

/**
 * Regression guard (finding L3) for the null-type guard in
 * {@code TileSatelliteBuilder.canAssembleSatellite} (~line 91: formerly
 * {@code getNewSatellite(satType).isAcceptableControllerItemStack(...)}).
 *
 * <p>Reasonable use (add-on / registration-order gap): a mod registers a primary
 * satellite part via the public {@code SatelliteRegistry.registerSatelliteProperty(
 * stack, props.setSatelliteType("x"))} but its paired {@code registerSatellite(
 * "x", class)} is missing or runs later. A player puts that part in the Satellite
 * Builder core slot, adds a power source + chassis + id chip, and clicks Build.
 * {@code canAssembleSatellite()} calls {@code getNewSatellite("x")} → null; the fix
 * returns false there (build silently rejected) instead of dereferencing null.
 * Every sibling caller of {@code getNewSatellite} (createFromNBT, ItemSatellite)
 * null-guards; the builder now matches them.</p>
 *
 * <p>The {@code press-build-unregistered} probe registers exactly such an
 * orphaned property through the real public API (no reflection, no production
 * edit), loads the real builder slots, and presses the real Build button. This
 * pins the corrected contract: pressing Build with an unregistered core type is a
 * no-op (no throw, no satellite created). Reachability: add-on/API-misuse only
 * (every shipped core type is class-registered before its property) — honest but
 * not vanilla-reachable.</p>
 */
public class SatelliteBuilderUnregisteredTypeNpeTest extends AbstractSharedServerTest {

    @Test
    public void buildWithUnregisteredCoreTypeIsRejected() throws Exception {
        int x = 10900, y = FixtureSite.OPEN_AIR_Y, z = 9700; // isolated column, distinct from other builder tests

        exec("stellurgytest chunk warmup 0 " + (x >> 4) + " " + (z >> 4) + " " + (x >> 4) + " " + (z >> 4));
        String place = exec("stellurgytest place 0 " + x + " " + y + " " + z + " stellurgy:satelliteBuilder");
        assertTrue("satellite builder must place: " + place, Reply.of(place).bool("placed"));

        String resp = exec("stellurgytest satellite-builder press-build-unregistered 0 " + x + " " + y + " " + z);
        assertTrue("probe setup must succeed: " + resp, Reply.of(resp).ok());
        assertTrue("the bogus type must be absent from the class registry (else not a valid L3 repro): " + resp,
                Reply.of(resp).bool("getNewSatelliteNull"));
        assertTrue("the bogus part must actually load into core slot 0: " + resp,
                Reply.of(resp).bool("slot0Loaded"));
        assertTrue("L3 null-type guard: pressing Build with an unregistered core type must NOT throw — "
                        + "canAssembleSatellite returns false (build silently rejected) when getNewSatellite is "
                        + "null, so onInventoryButtonPressed(0) skips assembleSatellite. Got: " + resp,
                "no-throw".equals(Reply.of(resp).text("outcome")));
    }
}
