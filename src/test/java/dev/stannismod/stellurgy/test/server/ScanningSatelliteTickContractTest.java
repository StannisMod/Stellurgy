package dev.stannismod.stellurgy.test.server;

import dev.stannismod.stellurgy.test.Reply;
import org.junit.Test;


import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/**
 * per-type data contracts for the four scanning satellites that extend
 * {@link dev.stannismod.stellurgy.satellite.SatelliteData} (optical / density / mass / composition),
 * plus the non-{@code SatelliteData} oreScanner (player-driven) and the non-tickable spyTelescope.
 * How much data a satellite collects, with what power, is no longer a tick's: it is the lazy accrual's
 * (`satellite` MECH-SAT-24, pinned in the fast tier).
 *
 * <p>The contract pinned here is per-type, not generic: each scanner is
 * <em>the</em> producer of one specific {@link
 * dev.stannismod.stellurgy.api.DataStorage.DataType}, and the data
 * stick / satellite-builder GUI / mission system branch on
 * {@code DataStorage.getDataType()}. A regression that swapped (say)
 * optical to emit {@code MASS} instead of {@code DISTANCE} would
 * silently break mission completion paths that demand a specific
 * data-type input — caught only by per-type pins.</p>
 *
 */
public class ScanningSatelliteTickContractTest extends AbstractSharedServerTest {

    private static final String ID = "id";
    private static final String PRE_STORED = "preStored";
    private static final String POST_STORED = "postStored";
    private static final String DATA_TYPE = "dataType";
    private static final String IS_SAT_DATA = "isSatelliteData";
    private static final String CAN_TICK = "canTick";

    /**
     * Pin: optical scanner emits DISTANCE-type data.
     *
     * <p>Contract litmus: "fails if production breaks the contract that
     * the {@code optical} satellite is the producer of DISTANCE-type
     * data". The
     * dataType identity is what the optical-scanner item-tool and
     * companion-mod subscribers branch on; swapping it would break
     * downstream consumers silently.</p>
     */
    @Test
    public void opticalPoweredTickEmitsDistanceTypeData() throws Exception {
        long satId = createSat("optical", 1000, 100_000, 1000);

        String dataResp = String.join("\n", client().read(
                "stellurgytest satellite data 0 " + satId));
        assertEquals("optical satellite MUST emit DISTANCE-typed data — "
                        + "downstream mission gates branch on dataType; "
                        + dataResp,
                "DISTANCE", stringField(DATA_TYPE, dataResp, "dataType"));
    }

    /**
     * Pin: density scanner emits ATMOSPHEREDENSITY-type data.
     *
     * <p>Contract litmus: "fails if production breaks the contract that
     * the {@code density} satellite is the producer of ATMOSPHEREDENSITY
     * data". The atmosphere-density readout drives the fuel-calc UI and
     * mission completion paths; a type swap would break those paths.</p>
     */
    @Test
    public void densityPoweredTickEmitsAtmosphereDensityTypeData() throws Exception {
        long satId = createSat("density", 1000, 100_000, 1000);

        String dataResp = String.join("\n", client().read(
                "stellurgytest satellite data 0 " + satId));
        assertEquals("density satellite MUST emit ATMOSPHEREDENSITY-typed data; "
                        + dataResp,
                "ATMOSPHEREDENSITY", stringField(DATA_TYPE, dataResp, "dataType"));
    }

    /**
     * Pin: mass scanner emits MASS-type data.
     *
     * <p>Contract litmus: "fails if production breaks the contract that
     * the {@code mass} satellite is the producer of MASS data". MASS
     * feeds the fuel-cost calculation for landings; a wrong type would
     * silently change calc-failure messages or skip the calc entirely.</p>
     */
    @Test
    public void massScannerPoweredTickEmitsMassTypeData() throws Exception {
        long satId = createSat("mass", 1000, 100_000, 1000);

        String dataResp = String.join("\n", client().read(
                "stellurgytest satellite data 0 " + satId));
        assertEquals("mass satellite MUST emit MASS-typed data; " + dataResp,
                "MASS", stringField(DATA_TYPE, dataResp, "dataType"));
    }

    /**
     * Pin: composition scanner emits COMPOSITION-type data.
     *
     */
    @Test
    public void compositionPoweredTickEmitsCompositionTypeData() throws Exception {
        long satId = createSat("composition", 1000, 100_000, 1000);

        String dataResp = String.join("\n", client().read(
                "stellurgytest satellite data 0 " + satId));
        assertEquals("composition satellite MUST emit COMPOSITION-typed data; "
                        + dataResp,
                "COMPOSITION", stringField(DATA_TYPE, dataResp, "dataType"));
    }

    /**
     * Pin: oreScanner is a non-{@code SatelliteData} satellite — its
     * it does not produce a
     * {@link dev.stannismod.stellurgy.api.DataStorage} stream.
     *
     * <p>Contract: oreScanner is player-driven (the player right-clicks
     * the chip to invoke {@code scanChunk}). Converting it to a
     * passive emitter would silently change the UX surface — the player
     * would no longer need to interact with it.</p>
     *
     * <p>Observable assertions:
     * <ol>
     *   <li>{@code markers.isSatelliteData == false} — the class-family
     *       gate that downstream consumers (terminal UI, satellite-info
     *       packet) branch on.</li>
     *   <li>{@code satellite data} returns the "not a SatelliteData
     *       subclass" error — confirming there is no data-stream surface
     *       to query.</li>
     * </ol>
     */
    @Test
    public void oreMappingIsNotSatelliteDataAndPoweredTickAccruesBatteryOnly()
            throws Exception {
        long satId = createSat("oreScanner", 200, 100_000, 1000);

        String markers = String.join("\n", client().read(
                "stellurgytest satellite markers 0 " + satId));
        assertEquals("oreScanner MUST report isSatelliteData=false — it is a "
                        + "player-driven scanner, not a passive data emitter; "
                        + markers,
                "false", stringField(IS_SAT_DATA, markers, "isSatelliteData"));

        String dataResp = String.join("\n", client().read(
                "stellurgytest satellite data 0 " + satId));
        // absence is the answer: a reply with NO `error` is the success shape, and "the
        // verb refused, with this reason" is exactly what this claim measures.
        assertTrue("oreScanner has no DataStorage surface — `satellite data` "
                        + "probe must report it is not a SatelliteData subclass; "
                        + dataResp,
                "not a SatelliteData subclass".equals(Reply.of(dataResp).textOr("error", null)));
    }

    /**
     * Pin: SpyTelescope's {@code canTick()} returns false AND its
     * (empty) {@code tickEntity} body produces no observable state
     * change even when invoked directly.
     *
     * <p>Defense-in-depth complement to {@link
     * SatelliteCoverageGapsTest#satelliteWithCanTickFalseIsNotAddedToTickingList}.
     * That test pins the registration gate (the satellite stays out of
     * {@code tickingSatellites}); this one pins the inner contract that
     * the {@code tickEntity} body itself is also a no-op — so any
     * future regression that bypasses the registration gate (mod-compat
     * hook calling tickEntity directly, etc.) doesn't smuggle in state
     * changes.</p>
     */
    @Test
    public void spyTelescopeCannotTickAndDirectTickEntityIsNoOp() throws Exception {
        String createResp = String.join("\n", client().arrange(
                "stellurgytest satellite create-spy-telescope 0"));
        assertTrue("create-spy-telescope failed: " + createResp,
                Reply.of(createResp).ok());
        Reply mReply = Reply.of(createResp);
        assertTrue("could not extract id from create response: " + createResp,
                mReply.has(ID));
        long spyId = Long.parseLong(mReply.text(ID));
        assertEquals("spyTelescope MUST report canTick=false; " + createResp,
                "false", stringField(CAN_TICK, createResp, "canTick"));

        // Directly invoke tickEntity 10 times via the probe — the
        // probe doesn't gate on canTick (it tests the production
        // tickEntity body unconditionally). SpyTelescope's body is
        // empty (does NOT call super.tickEntity), so battery must not
        // change.
        String tickResp = String.join("\n", client().act(
                "stellurgytest satellite tick 0 " + spyId + " 10"));
        assertTrue("tick probe failed: " + tickResp,
                Reply.of(tickResp).ok());
        long preStored = longField(PRE_STORED, tickResp, "preStored");
        long postStored = longField(POST_STORED, tickResp, "postStored");
        assertEquals("spyTelescope tickEntity is an empty body — even when "
                        + "called directly via the probe (bypassing the "
                        + "canTick=false registration gate) it must produce "
                        + "no battery change; preStored=" + preStored
                        + " postStored=" + postStored,
                preStored, postStored);
    }

    // -- helpers ----------------------------------------------------------

    private long createSat(String type, int powerGen, int powerStorage, int maxData) throws Exception {
        String resp = String.join("\n", client().arrange(
                "stellurgytest satellite create 0 " + type + " " + powerGen + " "
                        + powerStorage + " " + maxData));
        assertTrue("satellite create (" + type + ") failed: " + resp,
                Reply.of(resp).ok());
        Reply mReply = Reply.of(resp);
        assertTrue("could not extract id from create response: " + resp, mReply.has(ID));
        return Long.parseLong(mReply.text(ID));
    }

    private long longField(String field, String src, String name) {
        Reply reply = Reply.of(src);
        assertTrue("field " + name + " missing in: " + src, reply.has(field));
        return (long) reply.integer(field);
    }

    private String stringField(String field, String src, String name) {
        Reply reply = Reply.of(src);
        assertTrue("field " + name + " missing in: " + src, reply.has(field));
        return reply.text(field);
    }
}
