package dev.stannismod.stellurgy.test.unit;

import dev.stannismod.stellurgy.space.GalacticCoord;
import dev.stannismod.stellurgy.space.ShipLedger;
import dev.stannismod.stellurgy.test.contract.Pins;
import dev.stannismod.stellurgy.test.contract.RedWitness;
import org.junit.Test;

import java.util.UUID;

import static org.junit.Assert.*;

/**
 * Contract tests for the ship ledger — the authoritative shipId&rarr;(coordinate, state) record.
 * Pins the lifecycle semantics (settle / transit / position self-report / removal), in particular
 * that a live pose report can never overwrite a parked ship's logically-integrated coordinate.
 */
public class ShipLedgerTest {

    /** A constant: a {@code UUID} is an immutable value: two final longs. */
    private static final UUID SHIP = UUID.fromString("00000000-0000-0000-0000-000000000001");

    private static GalacticCoord coord(long sector, long local) {
        return GalacticCoord.ofSectorLocal(sector, 0L, 0L, local, 0L, 0L);
    }

    @Test
    public void settleRecordsCoordinateAndState() {
        ShipLedger ledger = new ShipLedger();
        ledger.settle(SHIP, coord(3, 100));

        ShipLedger.Entry e = ledger.get(SHIP);
        assertNotNull(e);
        assertEquals(coord(3, 100), e.coord);
        assertEquals(ShipLedger.State.SETTLED, e.state);
        assertEquals(coord(3, 0).cellKey(), e.cellKey());
    }

    @Test
    public void positionReportRefreshesASettledShip() {
        ShipLedger ledger = new ShipLedger();
        ledger.settle(SHIP, coord(3, 100));

        ledger.updatePosition(SHIP, coord(3, 2500), Double.NaN);

        assertEquals("the self-reported pose is the ship's coordinate now",
                coord(3, 2500), ledger.get(SHIP).coord);
        assertEquals("a position report does not change the lifecycle state",
                ShipLedger.State.SETTLED, ledger.get(SHIP).state);
    }

    @Test
    public void positionReportNeverTouchesAShipInTransit() {
        ShipLedger ledger = new ShipLedger();
        ledger.beginTransit(SHIP, coord(9, 0));

        ledger.updatePosition(SHIP, coord(1, 1), Double.NaN); // a stale pose from a parked hull

        assertEquals("a parked ship's coordinate is owned by the transit integrator",
                coord(9, 0), ledger.get(SHIP).coord);
        assertEquals(ShipLedger.State.IN_TRANSIT, ledger.get(SHIP).state);
    }

    @Test
    public void arrivalAfterTransitSettlesAtTheTarget() {
        // The transit-arrival amnesia fix: after a jump the ship's coordinate must survive in the
        // ledger instead of vanishing with the finished transit record.
        ShipLedger ledger = new ShipLedger();
        ledger.settle(SHIP, coord(1, 0));
        ledger.beginTransit(SHIP, coord(2, 0));
        ledger.settle(SHIP, coord(2, 0));

        ShipLedger.Entry e = ledger.get(SHIP);
        assertEquals(coord(2, 0), e.coord);
        assertEquals(ShipLedger.State.SETTLED, e.state);
    }

    @Test
    public void removalAndUnknownShipsAnswerNull() {
        ShipLedger ledger = new ShipLedger();
        assertNull(ledger.get(SHIP));
        ledger.updatePosition(SHIP, coord(1, 1), Double.NaN); // reporting an unknown ship is a safe no-op
        assertNull(ledger.get(SHIP));

        ledger.settle(SHIP, coord(1, 0));
        ledger.remove(SHIP);
        assertNull(ledger.get(SHIP));
        assertEquals(0, ledger.size());
    }

    /**
     * INV-SPC-20: the hull radius the ledger holds for a ship is the last one the ship measured. A ship that
     * came into space (settled with no row before it) holds none; its first self-report with a measurement
     * gives it one; a report that measured nothing keeps it; a second measurement replaces it; a departure
     * into transit, a report while in transit, and the arrival's settle all carry it unchanged. The
     * radii are inputs, any two distinct finite numbers.
     */
    @Test
    @Pins("INV-SPC-20")
    @RedWitness(verdict = "measured", member = "ShipLedger#updatePosition",
            at = "Double.isNaN(hullRadius) ? e.hullRadius : hullRadius", replacedBy = "e.hullRadius",
            fails = "INV-SPC-20 measured", on = "2026-10-25")
    @RedWitness(verdict = "kept", member = "ShipLedger#updatePosition",
            at = "Double.isNaN(hullRadius) ? e.hullRadius : hullRadius", replacedBy = "hullRadius",
            fails = "INV-SPC-20 kept", on = "2026-10-25")
    @RedWitness(verdict = "carried into transit", member = "ShipLedger#beginTransit",
            at = "hullRadiusOf(shipId)", replacedBy = "Double.NaN",
            fails = "INV-SPC-20 carried into transit", on = "2026-10-25")
    @RedWitness(verdict = "none yet", member = "ShipLedger#settle",
            at = "hullRadiusOf(shipId)", replacedBy = "0.0D",
            fails = "INV-SPC-20 none yet", on = "2026-10-25")
    @RedWitness(verdict = "carried by the arrival", member = "ShipLedger#settle",
            at = "hullRadiusOf(shipId)", replacedBy = "Double.NaN",
            fails = "INV-SPC-20 carried by the arrival", on = "2026-10-25")
    public void theHullAShipMeasuredIsTheLedgersUntilItMeasuresAnother() {
        double first = 7.25D;
        double second = 9.5D;
        ShipLedger ledger = new ShipLedger();
        ledger.settle(SHIP, coord(3, 100));
        assertTrue("INV-SPC-20 none yet: a ship that has not measured itself holds no radius",
                Double.isNaN(ledger.get(SHIP).hullRadius));

        ledger.updatePosition(SHIP, coord(3, 110), first);
        assertEquals("INV-SPC-20 measured: the self-report's measurement", first, ledger.get(SHIP).hullRadius, 0.0D);
        ledger.updatePosition(SHIP, coord(3, 120), Double.NaN);
        assertEquals("INV-SPC-20 kept: a report that measured nothing", first, ledger.get(SHIP).hullRadius, 0.0D);
        ledger.updatePosition(SHIP, coord(3, 130), second);
        assertEquals("INV-SPC-20 measured: a second measurement replaces the first", second,
                ledger.get(SHIP).hullRadius, 0.0D);

        ledger.beginTransit(SHIP, coord(5, 0));
        assertEquals("INV-SPC-20 carried into transit", second, ledger.get(SHIP).hullRadius, 0.0D);
        ledger.updatePosition(SHIP, coord(1, 1), first);
        assertEquals("INV-SPC-20 carried into transit: a report while parked changes nothing", second,
                ledger.get(SHIP).hullRadius, 0.0D);
        ledger.settle(SHIP, coord(5, 0));
        assertEquals("INV-SPC-20 carried by the arrival", second, ledger.get(SHIP).hullRadius, 0.0D);
    }
}
