package dev.stannismod.stellurgy.api.event;

import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;
import net.minecraftforge.fml.common.eventhandler.Event;

/**
 * Events about what a tier-2 SHIP's own machinery did — its flight computer coming live, its flight
 * model being rebuilt.
 *
 * <p><b>Not the ship's lifecycle.</b> Whether a craft exists, has become usable, has left for another
 * cell or is gone is {@link ShipLifecycleEvent}, which the ship manager posts at the place each
 * transition happens. This family once carried its own copy of three of those edges, worked out by
 * comparing what the world held with what it held the tick before; the two copies disagreed on a
 * crossing, and only the one published at the source was kept.</p>
 *
 * <h2>The identity is Stellurgy's, on purpose</h2>
 *
 * <p>A ship is carried by a physics substrate that Stellurgy treats as swappable, so no event here exposes
 * that substrate's types: a subscriber gets the world and the ship's durable Stellurgy id, and never
 * learns what implements it. The whole point of the port is that a consumer written against these
 * events keeps working if the substrate is replaced.</p>
 *
 * <h2>Server side</h2>
 *
 * <p>Posted on the server only, from the flight computer's own tick.</p>
 */
public class ShipEvent extends Event {

    /** The world the ship is in. */
    public final World world;
    /**
     * The ship's durable Stellurgy identity — the value Stellurgy's own ledger and navigation are keyed on.
     *
     * <p>It is carried ON the ship's own record, so reading it here costs a field access and no
     * lookup. It is {@code null} only for a craft that has not been given one: a bare hull with no
     * flight computer is a real ship, is loaded like any other, and is reported as one.</p>
     */
    public final String shipId;

    public ShipEvent(World world, String shipId) {
        this.world = world;
        this.shipId = shipId;
    }

    /**
     * The FLIGHT COMPUTER of a ship is live in this world: its tile is loaded, is being ticked, and
     * knows the durable id it carries.
     *
     * <p><b>Why this is not {@link ShipLifecycleEvent.ShipUsable} said differently.</b> That event
     * reports that a craft's PHYSICS will be stepped. This one reports that the block a durable id can be resolved
     * THROUGH is present — and the two come apart exactly where it matters. A craft kept loaded
     * while nobody is aboard is already steppable, so its physics edge has long since passed; when a
     * returning player's login asks the world for that craft's computer, the tile is not in the
     * world's loaded-tile list yet, the load it triggers is queued rather than immediate, and the
     * arrival of the blocks moves no readiness flag. A consumer waiting for the physics edge
     * therefore waits for a transition that has already happened and will not happen again.</p>
     *
     * <p>Posted ONCE per computer instance, on the first tick it runs with a ship id — server side. A computer that is unloaded and comes back is a new instance and
     * posts again, which is the property a consumer holding a durable id needs.</p>
     *
     * <p><b>What it does not tell you</b>: whether the craft is steppable, who is aboard, or that the
     * hull is intact. It says one thing — ask this world for that id now and you will find it.</p>
     */
    public static class FlightComputerLiveEvent extends ShipEvent {

        /** Where the computer sits, so a consumer that needs the ship's frame can start from it. */
        public final net.minecraft.util.math.BlockPos pos;

        public FlightComputerLiveEvent(World world, String shipId, BlockPos pos) {
            super(world, shipId);
            this.pos = pos;
        }
    }

    /**
     * What a ship can DO has been re-derived: its flight model — mass, the twelve signed authorities,
     * thrust to weight — was rebuilt from the hull, because the hull changed or on the periodic
     * re-measure of its load, which may find the same figures as before. Carries the readout itself,
     * so a subscriber that shows or reacts to a ship's characteristics needs no second lookup.
     *
     * <p>This is where a system that owns a characteristic of its own (shields, a drive) joins the
     * ship's readout: it hears the ship change, and the readout is the one surface every consumer
     * reads.</p>
     *
     * <p>Posted on each rebuild, server side, from the flight computer. Not posted for a hull that
     * could not be surveyed; a consumer must not read silence as "unchanged".</p>
     */
    public static class FlightModelChangedEvent extends ShipEvent {

        /** Where the flight computer sits. */
        public final net.minecraft.util.math.BlockPos pos;
        /** The new readout; its revision increases with every rebuild of this computer's model. */
        public final dev.stannismod.stellurgy.ship.control.ShipReadout readout;

        public FlightModelChangedEvent(World world, String shipId, BlockPos pos,
                                       dev.stannismod.stellurgy.ship.control.ShipReadout readout) {
            super(world, shipId);
            this.pos = pos;
            this.readout = readout;
        }
    }
}
