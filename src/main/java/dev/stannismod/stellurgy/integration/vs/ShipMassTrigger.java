package dev.stannismod.stellurgy.integration.vs;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.valkyrienskies.mod.common.ships.ShipData;
import org.valkyrienskies.mod.common.ships.physics_data.ShipInertiaData;

import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;
import dev.stannismod.stellurgy.api.event.ShipLifecycleEvent;
import dev.stannismod.stellurgy.ship.mass.ShipMassFrame;

/**
 * Recomputes a ship's mass from its hull at the moments where the incremental path cannot be trusted
 * to have kept up, and reports it whenever the two disagree.
 *
 * <h2>Why these moments</h2>
 *
 * <p>The incremental path — a delta applied as each block changes — is the working answer and stays
 * so. A full pass over the hull is justified only where something happened that the deltas were never
 * shown:</p>
 *
 * <ul>
 *   <li><b>Assembled.</b> The craft was fed to the accumulator block by block moments ago. The
 *       recompute here is not a repair; it is the independent second opinion that makes a
 *       disagreement detectable at all, and this is the one case where the two really ought to
 *       agree.</li>
 *   <li><b>Pasted.</b> A craft cut out of one world and re-registered around blocks somewhere else.
 *       Same shape as an assembly and the same reason.</li>
 *   <li><b>Loaded.</b> The record came off disk carrying a number of unknown provenance — written by
 *       some earlier version, possibly by a build with a different table. This is the one transition
 *       where a disagreement is not a defect but the entire reason to recompute, so it is applied
 *       WITHOUT being reported.</li>
 * </ul>
 *
 * <p>These are the STRUCTURAL moments, and they are armed on the naming announcement rather than on a
 * positional probe: before the engine names a ship its blocks are already loaded and ticking at
 * shipyard addresses no player can reach, and a frame computed in that window is a frame about a craft
 * that does not exist yet. Content and crew change with no block changing, so they are the business of
 * {@link #backgroundRound}, which the flight computer runs on its own cadence.</p>
 *
 * <h2>Drift is reported, never corrected quietly, and never thrown</h2>
 *
 * <p>A disagreement on an assembly or a paste means a trigger is missing somewhere, and the repair is
 * that trigger. Substituting the right number silently would turn the safety net into normal
 * operation and destroy the only signal that anything is wrong — so the authoritative frame is
 * written AND the disagreement is logged, with its sign.</p>
 *
 * <p>It is logged rather than thrown because this runs inside the world tick, from an event whose
 * contract forbids a handler to throw: an exception here leaves the tick with nothing between it and
 * the server loop, and a dead server reports "the process exited", not "the mass drifted by 4%". A
 * failure nobody can read is not a louder failure.</p>
 */
public final class ShipMassTrigger {

    private ShipMassTrigger() {}

    private static final Logger LOG = LogManager.getLogger("stellurgy.mass");

    /** The Forge subscriber. Registered for the whole run; the work is gated by the event itself. */
    public static final class Hooks {
        @SubscribeEvent
        public void onShipNamed(ShipLifecycleEvent.ShipNamed event) {
            try {
                recompute(event);
            } catch (Throwable failure) {
                // The event contract forbids a handler to throw, and this one runs inside the ship
                // manager's own tick. A mass model that cannot compute is a degraded ship; a mass
                // model that kills the world tick is a degraded server.
                LOG.error("ship mass recompute failed for " + event.shipUuid, failure);
            }
        }
    }

    private static void recompute(ShipLifecycleEvent.ShipNamed event) {
        ShipMassFrame authority = ShipHullMass.frameOf(event.world, event.shipUuid);
        if (authority == null) {
            return;
        }
        ShipData ship = VSBridge.shipDataByUuid(event.world, event.shipUuid);
        if (ship == null) {
            return;
        }
        ShipInertiaData record = ship.getInertiaData();
        if (event.cause != ShipLifecycleEvent.Cause.LOADED) {
            // Compared BEFORE the write, or there is nothing left to compare against.
            ShipInertiaWriter.Drift drift =
                    ShipInertiaWriter.compare(record, authority, String.valueOf(event.shipUuid));
            if (drift != null) {
                LOG.warn(event.cause + ": " + drift);
            }
        }
        ShipInertiaWriter.applyTo(record, authority, String.valueOf(event.shipUuid));
    }

    /**
     * The slow background round: re-measure this ship and write what the measurement says.
     *
     * <p>The event triggers above cover the moments a hull's STRUCTURE changes wholesale. They cannot
     * cover content and crew, which change with no block ever changing — a tank empties, a crate is
     * filled, somebody steps aboard — and there is no event for any of it worth subscribing to. So the
     * flight computer calls this on its own cadence, and the ship's mass follows what it is actually
     * carrying instead of what it was carrying when it was built.</p>
     *
     * <p><b>No drift report here, deliberately.</b> Between two rounds the content legitimately
     * changed; reporting the difference would file a burned tank of fuel as a defect and bury the one
     * signal the report exists for — a block delta that went missing. A round that finds nothing to
     * weigh writes nothing: a craft mid-crossing owns no blocks for a moment, and zeroing its mass
     * would hand the solver a tensor it inverts every step.</p>
     */
    public static void backgroundRound(net.minecraft.world.World world, java.util.UUID shipUuid) {
        try {
            ShipMassFrame authority = ShipHullMass.frameOf(world, shipUuid);
            if (authority == null) {
                return;
            }
            ShipData ship = VSBridge.shipDataByUuid(world, shipUuid);
            if (ship == null) {
                return;
            }
            ShipInertiaWriter.applyTo(ship.getInertiaData(), authority, String.valueOf(shipUuid));
        } catch (Throwable failure) {
            // Same reasoning as the event handler: this runs inside the world tick, and a mass model
            // that kills the tick is worse than one that cannot compute.
            LOG.error("background mass round failed for " + shipUuid, failure);
        }
    }
}
