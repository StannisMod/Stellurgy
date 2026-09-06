package zmaster587.advancedRocketry.api.event;

import net.minecraft.world.World;
import net.minecraftforge.fml.common.eventhandler.Event;

/**
 * Events about a tier-2 SHIP — the assembled, flyable craft, as distinct from a rocket.
 *
 * <p>Tier 1 has had a lifecycle on the bus since the beginning
 * ({@link zmaster587.advancedRocketry.api.RocketEvent}); ships had nothing, so nothing outside the
 * subsystem could learn that one exists, has become flyable, or has gone. This is the first of that
 * family.</p>
 *
 * <h2>The identity is AR's, on purpose</h2>
 *
 * <p>A ship is carried by a physics substrate that AR treats as swappable, so no event here exposes
 * that substrate's types: a subscriber gets the world, the ship's durable AR id and the substrate's
 * opaque id as a STRING, and never learns what implements it. The whole point of the port is that a
 * consumer written against these events keeps working if the substrate is replaced.</p>
 *
 * <h2>Server side</h2>
 *
 * <p>Posted on the server only. The facts these events carry — whether a ship's physics will actually
 * be stepped, which chunks are cached for it — are server state; a client knows a ship by its
 * rendered transform and would answer a different question.</p>
 */
public class ShipEvent extends Event {

    /** The world the ship is in. */
    public final World world;
    /**
     * The ship's durable AR identity — the value AR's own ledger and navigation are keyed on.
     *
     * <p>It is carried ON the ship's own record, so reading it here costs a field access and no
     * lookup. It is {@code null} only for a craft that has not been given one: a bare hull with no
     * flight computer is a real ship, is loaded like any other, and is reported as one.</p>
     */
    public final String shipId;
    /** The substrate's own id for the same ship, opaque on purpose: useful to log, never to resolve. */
    public final String substrateId;

    public ShipEvent(World world, String shipId, String substrateId) {
        this.world = world;
        this.shipId = shipId;
        this.substrateId = substrateId;
    }

    /**
     * A ship has become USABLE in this world: its physics will be stepped from now on.
     *
     * <p><b>This is a stronger claim than "the ship exists".</b> A ship can be registered, and its
     * physics object constructed, several ticks before anything moves it: the substrate deliberately
     * withholds physics for the first ticks after a load so a freshly placed hull does not fall
     * through the floor, and the surrounding-chunk cache it needs is filled on a tick of its own.
     * A consumer that acts on mere existence acts on a ship that will not respond yet — which is
     * exactly the ambiguity this event exists to remove, and why it is not posted at construction.</p>
     *
     * <p>Posted ONCE per load, on the tick the ship first becomes steppable. A ship that is unloaded
     * and comes back posts again; a ship that merely moves does not.</p>
     *
     * <p>Not cancellable: it reports something that has already become true. There is nothing here to
     * veto, and a listener that throws does not un-load the ship.</p>
     *
     * <p><b>What it does not tell you</b>: whether the ship has a flight computer, a pilot, or any
     * controller registered. Those are separate questions with separate answers, and a ship is
     * steppable without any of them.</p>
     */
    public static class ShipLoadedEvent extends ShipEvent {
        public ShipLoadedEvent(World world, String shipId, String substrateId) {
            super(world, shipId, substrateId);
        }
    }
}
