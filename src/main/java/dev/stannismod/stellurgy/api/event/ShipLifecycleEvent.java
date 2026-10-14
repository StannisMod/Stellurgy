package dev.stannismod.stellurgy.api.event;

import java.util.UUID;

import javax.annotation.Nullable;

import net.minecraft.world.World;
import net.minecraftforge.fml.common.eventhandler.Event;

/**
 * The life of a tier-2 craft as a ship: it is NAMED, it becomes USABLE, and it is UNNAMED — every
 * edge posted by the ship manager at the place the transition happens.
 *
 * <h2>One family, published at the source</h2>
 *
 * <p>This is the only ship-lifecycle announcement there is. Nothing works out a birth, a departure or
 * a death by comparing what the world holds now with what it held last tick: each edge is posted by
 * the code that performs the transition, so every consumer hears the same thing about the same craft
 * on the same tick, and the cause is said by the code that knows it rather than inferred by the code
 * that watches.</p>
 *
 * <h2>Why an event when a predicate already exists</h2>
 *
 * <p>Asking "is this craft named right now" is the right question for a machine deciding whether to
 * do its work this tick, and it is enough for every such gate. It is not enough for anything that has
 * to happen <b>once, at the moment naming happens</b>: an authoritative recompute of derived state, a
 * registration built at first contact and resolved by id thereafter, a durable record minted at
 * birth. Finding an edge by watching a level means polling, and a subsystem that polls ends up with
 * its own private idea of which tick the craft started existing on.</p>
 *
 * <h2>The three edges</h2>
 *
 * <ul>
 *   <li>{@link ShipNamed} — the engine holds a live ship object for this identity: the craft can be
 *       resolved by id, asked for its pose, and written to. A craft's blocks and its tiles arrive
 *       before this; until it, every coordinate they hold is a shipyard address.</li>
 *   <li>{@link ShipUsable} — the ship's physics will be STEPPED from now on. Later than naming: the
 *       engine withholds physics for the first ticks after a load so a freshly placed hull does not
 *       fall through the floor, and fills the surrounding-chunk cache on a tick of its own. A
 *       consumer that wants to fly the craft, or to put a body on its deck, wants this one.</li>
 *   <li>{@link ShipUnnamed} — the ship can no longer be resolved by id, and {@link ShipUnnamed#cause}
 *       says whether it will be back ({@link Cause#UNLOADED}), is alive elsewhere
 *       ({@link Cause#DEPARTED}, posted as {@link ShipDeparted} with its destination), or has ceased
 *       to exist ({@link Cause#DESTROYED}).</li>
 * </ul>
 *
 * <h2>The contract — read this before writing a handler</h2>
 *
 * <ul>
 *   <li><b>Server only.</b> Posted from the server's ship manager; nothing posts these on the client,
 *       so a client-side subscriber will simply never fire. A client learns about ships from the
 *       engine's own index packets.</li>
 *   <li><b>The server game thread, inside the world tick.</b> Never the physics thread. A handler may
 *       therefore touch the world, read tiles and send packets, exactly as in any other world-tick
 *       callback.</li>
 *   <li><b>After the fact, and not cancellable.</b> By the time a handler runs, the transition has
 *       happened. There is nothing to veto.</li>
 *   <li><b>A handler must not block, and must not throw.</b> It runs inside the tick that every other
 *       ship in the world is waiting on, and Forge's bus re-throws whatever a handler throws — so an
 *       exception here leaves the world tick with nothing between it and the server loop. A handler
 *       that can fail catches its own failure and logs it.</li>
 *   <li><b>A handler may queue ship spawns, loads and unloads.</b> Each edge is published after the
 *       pass that produced it has finished walking its collections, so a queue touched by a handler
 *       is acted on next tick rather than corrupting the walk in progress.</li>
 *   <li><b>Ordering within a tick is the order the transitions happened</b> — removals from the
 *       destroy pass and the registry sweep, then spawns, then loads, then unloads; and after the
 *       ships have ticked, the usable edges. A craft that is re-registered in one tick (a crossing
 *       that lands where its own blockless remnant still sat) therefore produces the
 *       {@link Cause#DEPARTED} before the {@link Cause#PASTED}, which is the truth of what
 *       happened.</li>
 *   <li><b>A world that stops ticking announces nothing.</b> Its ships were not destroyed — the
 *       publisher stopped. The ABSENCE of an unnaming is never proof that a craft still exists.</li>
 * </ul>
 *
 * <h2>Named is not the same as moving</h2>
 *
 * <p>A usable ship is simulated, but whether it MOVES is its flight computer's decision: with Flight
 * Assist on, an unpiloted craft holds station. A handler that arms on these events and then waits for
 * the craft to move may wait forever, and that is not a fault in the event.</p>
 */
public class ShipLifecycleEvent extends Event {

    /**
     * Why a craft was named or unnamed. Carried as a field rather than as a separate event type per
     * case because most consumers want several of them and would otherwise subscribe several times,
     * while the consumers that want exactly one (a durable record minted only for a genuinely new
     * build) compare one field.
     */
    public enum Cause {
        /** Named: a new craft, built here and assembled for the first time. Nothing of it existed before. */
        ASSEMBLED,
        /**
         * Named: a craft that already existed, re-registered around blocks that were cut out somewhere
         * else and pasted here — a crossing, a transit, a reposition. The vessel is the same one; only
         * its registration is new.
         */
        PASTED,
        /**
         * Named: a craft that was already registered and is now loaded again — the world came back, or
         * a player came close enough for the engine to want it live. Nothing about the craft changed.
         */
        LOADED,
        /**
         * Unnamed: the ship object is gone but the craft still exists — it is registered, its blocks
         * are on disk, and it will be back. Live state keyed on it must be dropped; anything durable
         * must not.
         */
        UNLOADED,
        /**
         * Unnamed: the craft was cut out of this world and is being re-assembled in
         * {@link ShipDeparted#destinationDim}. It is ALIVE — its crew are aboard it on the way there —
         * so nothing durable about the vessel may be dropped; only this world's registration ended.
         *
         * <p>A departure and a destruction remove the craft from this world's registry in exactly the
         * same way, so the two cannot be told apart by looking. The crossing DECLARES the departure to
         * the manager before it cuts, and the manager announces the removal as what it was.</p>
         */
        DEPARTED,
        /**
         * Unnamed: the craft ceased to exist as a ship — deconstructed back into the world, or a
         * blockless registration collected that nothing declared a departure for. Durable records keyed
         * on it are now about nothing.
         */
        DESTROYED
    }

    /** The world the ship belongs to. Always a server world. */
    public final World world;

    /** The physics engine's identity for this ship. Stable while the registration lives. */
    public final UUID shipUuid;

    /**
     * Stellurgy's DURABLE id for this craft, or {@code null} for a craft Stellurgy does not own (and
     * for a genuinely new build that has not yet been given one by its flight computer).
     *
     * <p>Unlike {@link #shipUuid} this one survives a re-assembly, so it is what names the same vessel
     * across a crossing, a restart and a re-registration. A consumer holding state that must follow
     * the VESSEL rather than the registration keys on this.</p>
     */
    @Nullable
    public final UUID durableId;

    protected ShipLifecycleEvent(World world, UUID shipUuid, @Nullable UUID durableId) {
        this.world = world;
        this.shipUuid = shipUuid;
        this.durableId = durableId;
    }

    /**
     * A ship exists and can be resolved by id from now on. {@link #cause} is one of
     * {@link Cause#ASSEMBLED}, {@link Cause#PASTED} or {@link Cause#LOADED}.
     */
    public static class ShipNamed extends ShipLifecycleEvent {
        /** How the ship came to be named; see {@link Cause}. */
        public final Cause cause;

        public ShipNamed(World world, UUID shipUuid, @Nullable UUID durableId, Cause cause) {
            super(world, shipUuid, durableId);
            this.cause = cause;
        }
    }

    /**
     * A named ship has become USABLE: its physics will be stepped from now on.
     *
     * <p>Posted ONCE per load, on the tick the engine first steps it — past its settling delay and with
     * its surrounding-chunk cache filled. A ship that is unloaded and comes back is a new ship object
     * and posts again; a ship that merely moves does not.</p>
     *
     * <p><b>What it does not tell you</b>: whether the ship has a flight computer, a pilot, or any
     * controller registered. Those are separate questions with separate answers, and a ship is
     * steppable without any of them.</p>
     */
    public static class ShipUsable extends ShipLifecycleEvent {
        public ShipUsable(World world, UUID shipUuid, @Nullable UUID durableId) {
            super(world, shipUuid, durableId);
        }
    }

    /**
     * A ship can no longer be resolved by id. {@link #cause} is {@link Cause#UNLOADED} (it will be
     * back), {@link Cause#DEPARTED} (it is alive elsewhere — always a {@link ShipDeparted}) or
     * {@link Cause#DESTROYED} (it will not be back).
     *
     * <p>Designed with its counterpart rather than after it, because every consumer that arms on the
     * naming edge holds something it then has to let go of, and the two halves must agree on what a
     * craft's identity is.</p>
     */
    public static class ShipUnnamed extends ShipLifecycleEvent {
        /** Why the ship stopped being resolvable here; see {@link Cause}. */
        public final Cause cause;

        /**
         * {@link Cause#UNLOADED} or {@link Cause#DESTROYED}. A departure is a {@link ShipDeparted},
         * which carries its destination; this refuses one, because a departure without a destination
         * would reach every subscriber as a well-formed event about nowhere.
         */
        public ShipUnnamed(World world, UUID shipUuid, @Nullable UUID durableId, Cause cause) {
            super(world, shipUuid, durableId);
            if (cause != Cause.UNLOADED && cause != Cause.DESTROYED) {
                throw new IllegalArgumentException("not an unnaming cause for ShipUnnamed: " + cause);
            }
            this.cause = cause;
        }

        /** The departure's own path: {@link ShipDeparted} is the only caller. */
        private ShipUnnamed(World world, UUID shipUuid, @Nullable UUID durableId) {
            super(world, shipUuid, durableId);
            this.cause = Cause.DEPARTED;
        }
    }

    /**
     * The {@link Cause#DEPARTED} unnaming: the craft was cut out of this world and is being
     * re-assembled in {@link #destinationDim}, alive, crew aboard.
     *
     * <p>A subclass rather than a field on {@link ShipUnnamed}, because only a departure HAS a
     * destination; a field the other two causes would have to fill with a stand-in is a value no
     * reader could tell from a real dimension. A subscriber to {@link ShipUnnamed} receives this too
     * and tells it apart by {@link #cause}.</p>
     */
    public static class ShipDeparted extends ShipUnnamed {
        /** The dimension the craft was cut into. */
        public final int destinationDim;

        public ShipDeparted(World world, UUID shipUuid, @Nullable UUID durableId, int destinationDim) {
            super(world, shipUuid, durableId);
            this.destinationDim = destinationDim;
        }
    }
}
