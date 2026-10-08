package dev.stannismod.stellurgy.network;

import dev.stannismod.stellurgy.ship.control.ShipReadout;

/**
 * A tile that shows a ship readout on the client: a flight computer's console and HUD, or an
 * assembler's Scan. The readout packet is addressed to the tile's position and hands it over here.
 */
public interface IShipReadoutReceiver {

    /** A readout arrived for this tile, with the live flight slice it was sent with. */
    void acceptReadout(ShipReadout readout, boolean saturated, double wheelFill);
}
