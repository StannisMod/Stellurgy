package zmaster587.advancedRocketry.test.server;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static zmaster587.advancedRocketry.test.server.WorldCommandFixtures.exec;
import static zmaster587.advancedRocketry.test.server.WorldCommandFixtures.planetFloatField;
import static zmaster587.advancedRocketry.test.server.WorldCommandFixtures.planetIntField;

/**
 * {@code /ar planet set | get | list} contract pins.
 *
 * <p>Each test mutates one DimensionProperties field on the overworld
 * via {@code /ar planet set 0 <field> <val>}, asserts the change is
 * observable through the independent {@code /artest planet info 0}
 * JSON reader, then restores the pre-test value in a finally block.
 * Pinning the result (the field IS the new value) rather than the
 * dispatch chain (which reflective branch fired) keeps each test ≤ 6
 * lines of body.</p>
 *
 * <p>{@code planet set} has two write paths in production: a hardcoded
 * branch for {@code atmosphereDensity} (calls
 * {@code setAtmosphereDensityDirect}) and a generic reflective branch
 * for the rest. Both paths are exercised here.</p>
 */
public class WorldCommandPlanetSetGetContractTest extends AbstractSharedServerTest {

    private static final int DIM = 0;

    @Test
    public void planetSetAtmosphereDensityIsObservableViaProbe() throws Exception {
        int before = planetIntField(DIM, "atmosphereDensity");
        try {
            exec("ar planet set " + DIM + " atmosphereDensity 73");
            assertEquals(73, planetIntField(DIM, "atmosphereDensity"));
        } finally {
            exec("ar planet set " + DIM + " atmosphereDensity " + before);
        }
    }

    @Test
    public void planetSetGravitationalMultiplierIsObservableViaProbe() throws Exception {
        double before = planetFloatField(DIM, "gravity");
        try {
            exec("ar planet set " + DIM + " gravitationalMultiplier 0.42");
            assertEquals(0.42, planetFloatField(DIM, "gravity"), 1e-4);
        } finally {
            exec("ar planet set " + DIM + " gravitationalMultiplier " + before);
        }
    }

    // averageTemperature is intentionally NOT pinned via planet set: it is
    // a derived field — DimensionProperties.getAverageTemp() recomputes it
    // from star + orbital + atmosphereDensity on every read
    // (DimensionProperties.java:2002). Pinning a write to a derived field
    // would test impl detail (whether the write-then-immediate-read window
    // is observable) rather than contract. The three real settable ints
    // (atmosphereDensity, gravitationalMultiplier, rotationalPeriod) cover
    // the same reflective branch.

    @Test
    public void planetSetRotationalPeriodIsObservableViaProbe() throws Exception {
        int before = planetIntField(DIM, "rotationalPeriod");
        try {
            exec("ar planet set " + DIM + " rotationalPeriod 17000");
            assertEquals(17000, planetIntField(DIM, "rotationalPeriod"));
        } finally {
            exec("ar planet set " + DIM + " rotationalPeriod " + before);
        }
    }

    /** {@code /ar planet get <dim> <field>} echoes the field's current
     *  value via chat. Pinning that the value text matches the probe
     *  read — cross-checks the two read paths agree, which guards
     *  against the "set-and-get both buggy in the same way" scenario. */
    @Test
    public void planetGetEchoesCurrentAtmosphereDensity() throws Exception {
        int probeValue = planetIntField(DIM, "atmosphereDensity");
        String getResp = exec("ar planet get " + DIM + " atmosphereDensity");
        // The chat line IS the contract here — there is no data form for a player command — but
        // the claim is bounded at both ends instead of hunting for digits. The message is
        // `%s=%s` (en_US.lang), so the field name bounds it on the left and the end of the line
        // on the right: the bare needle `50` was answered by a coordinate, a tick count, or a
        // density of 500 printed anywhere in the same output.
        assertTrue("planet get response must echo atmosphereDensity=" + probeValue
                        + " — got: " + getResp,
                echoesField(getResp, "atmosphereDensity", String.valueOf(probeValue)));
    }

    /** {@code /ar planet list} prints one chat line per registered dim
     *  in {@code DimensionManager.getInstance()}. Overworld is always
     *  registered (AR adds it at boot — confirmed by every existing
     *  test that calls {@code /artest planet info 0}). Pin the presence
     *  of {@code DIM0} substring without pinning exact line wording. */
    @Test
    public void planetListIncludesOverworldDim() throws Exception {
        String resp = exec("ar planet list");
        assertTrue("planet list must include DIM0 — got: " + resp,
                resp.contains("DIM0"));
    }

    /**
     * Whether any line of a chat reply ENDS with {@code field=value} — the shape
     * {@code commands.advancedrocketry.planet.get.success} prints.
     *
     * <p>A chat line is the contract for a player command, so it is read as text; what this adds
     * is BOUNDS. The field name and the {@code =} bound it on the left, the end of the line on
     * the right, so neither a longer value nor the digits appearing elsewhere in the output can
     * answer it. The server's own log prefix sits on the left of the line and is why this is an
     * {@code endsWith} rather than an equality.</p>
     */
    private static boolean echoesField(String chatReply, String field, String value) {
        for (String line : chatReply.split("\\R")) {
            if (line.trim().endsWith(field + "=" + value)) {
                return true;
            }
        }
        return false;
    }
}
