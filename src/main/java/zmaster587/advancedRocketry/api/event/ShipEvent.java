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

    /**
     * A ship has LEFT this world alive: its blocks were cut out of here and re-assembled somewhere
     * else. It is not gone — {@link #destinationDim} is where it went.
     *
     * <p><b>This event exists because its sibling is otherwise a lie on the commonest path there
     * is.</b> Both a crossing and a destruction end with the substrate's registry dropping the craft
     * in this world, so a falling edge on that registry cannot tell them apart — and a consumer told
     * "gone" for a departure will conclude the craft no longer exists at the precise moment it is
     * being carried, with its crew aboard, to the next cell. The two are published separately for
     * that reason and a consumer must decide which of them it actually means.</p>
     *
     * <p>The crossing DECLARES the departure before it cuts, so the classification is made by the
     * code that knows, not inferred from timing by the code that watches.</p>
     */
    public static class ShipLeftWorldEvent extends ShipEvent {
        /** The dimension the craft was cut into. */
        public final int destinationDim;

        public ShipLeftWorldEvent(World world, String shipId, String substrateId, int destinationDim) {
            super(world, shipId, substrateId);
            this.destinationDim = destinationDim;
        }
    }

    /**
     * A ship is GONE from this world: the substrate no longer has a record of it at all.
     *
     * <p><b>This is the counterpart {@link ShipLoadedEvent} deliberately did not have, and the
     * absence had a cost.</b> Every consumer waiting for a craft could learn that it had arrived and
     * could never learn that it never would, so each one ended up with a clock standing in for the
     * answer — a duration guessing at a fact, on the branch where the fact is least like the typical
     * case. {@code DeckHold} is the one that made this concrete: a crew member pinned to a deck point
     * for a ship that had been destroyed was held for a fixed window and then handed to gravity, and
     * nothing anywhere said why.</p>
     *
     * <p><b>Gone is not unloaded.</b> A ship whose chunks stop being kept, or whose world stops
     * ticking, is still a ship and will come back; this is posted only when the substrate's REGISTRY
     * stops carrying it, which is what a destroy pass does. A consumer that wants "is it steppable
     * right now" is asking {@link ShipLoadedEvent}'s question, not this one.</p>
     *
     * <p><b>Gone is not departed either.</b> A craft cut out of this world by a crossing leaves the
     * registry exactly as a destroyed one does, and it is alive in another cell with its crew aboard.
     * That case is {@link ShipLeftWorldEvent} and is never published here.</p>
     *
     * <p><b>One honest limit</b>, and it follows from the same place: this is an edge detected on a
     * ticking world. If a world stops ticking altogether, nothing here observes its ships leaving —
     * they were not destroyed, the observer stopped. A consumer must not read the ABSENCE of this
     * event as proof a craft still exists.</p>
     *
     * <p>Not cancellable, for the same reason as its sibling: it reports something already true.</p>
     */
    public static class ShipGoneEvent extends ShipEvent {
        public ShipGoneEvent(World world, String shipId, String substrateId) {
            super(world, shipId, substrateId);
        }
    }
}
