package zmaster587.advancedRocketry.api.event;

import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.world.World;
import net.minecraftforge.fml.common.eventhandler.Event;
import zmaster587.advancedRocketry.space.GalacticCoord;

import java.util.Collections;
import java.util.List;

/**
 * A tier-2 ship changing WHERE IT IS, published from the crossing that moves it.
 *
 * <p>Three mechanics move a ship between places, and each is its own pair — one event when the craft
 * leaves, one when it has arrived:</p>
 *
 * <ul>
 *   <li><b>Crossing a cell boundary</b> ({@link LeftCell} / {@link EnteredCell}) — nothing was
 *       commanded; the craft flew past a cell face and is carried into the neighbour.</li>
 *   <li><b>A transit</b> ({@link TransitBegan} / {@link TransitEnded}) — a commanded jump. Whether it
 *       goes through hyperspace or crosses directly is a {@link Route}, not a second mechanic: the
 *       transit manager picks the route from distance and speed, and a short jump is a transit that
 *       skipped the parking.</li>
 *   <li><b>Planet and space</b> ({@link LeftPlanet} / {@link EnteredPlanet}) — the launch up and the
 *       descent down.</li>
 * </ul>
 *
 * <h2>Why this is not a subclass of {@link ShipEvent.ShipLeftWorldEvent}</h2>
 *
 * <p>That event is already posted for every departure, from the substrate REGISTRY's falling edge, by
 * the announcer that watches it. It answers a coarser question — "left here alive, as opposed to
 * destroyed" — for a consumer that never sees a crossing. Making these its subclasses would post two
 * events for one departure to anyone listening on the base. They are a different observer of the same
 * moment: richer, and from inside the crossing rather than beside it.</p>
 *
 * <h2>Every payload is total</h2>
 *
 * <p>No field here is null-when-inapplicable. Where a mechanic has only one cell — a launch comes
 * FROM a planet dimension, a descent goes TO one — the event carries the one coordinate that exists
 * and names the planet by the dimension id its {@link Departure} or {@link Arrival} half already
 * carries. A coordinate invented for the planet side would be a well-formed answer indistinguishable
 * from a real one, which is how a navigation crystal once addressed planets by their star.</p>
 *

 * <h2>The bases are concrete, and Forge requires that</h2>
 *
 * <p>{@link ShipCrossingEvent}, {@link Departure}, {@link Arrival} and {@link PlanetTrip} are not
 * abstract, and it is not a modelling choice: 1.12.2's {@code EventBus.register} INSTANTIATES the
 * event class to reach its listener list, so a subscription to an abstract event throws
 * {@code InstantiationException} and takes the whole registering object's handlers down with it —
 * measured, on the run that first tried it. Their constructors are package-private, so nothing
 * outside this package can post a bare base; subscribing to one is the point.</p>
 *
 * <h2>Server side, and not cancellable</h2>
 *
 * <p>Both by the family's existing doctrine. These report something that has already happened: an
 * arrival that could be vetoed would leave a listener holding a ship whose blocks are already cut,
 * and where it should go instead is a mechanic in its own right rather than a return value.</p>
 */
public class ShipCrossingEvent extends Event {

    /** The world the event is ABOUT: the one being left, or the one arrived in. */
    public final World world;
    /** The ship's durable AR identity, or {@code null} for a craft that has none.
     *
     *  <p>The substrate's own id is deliberately NOT carried. {@link ShipEvent} offers it as a
     *  logging convenience; here it would have to be plumbed from the crossing service into three
     *  controllers that do not otherwise hold it, to supply a value no subscriber can resolve
     *  anything with. The durable id is the one AR's ledger and navigation are keyed on.</p> */
    public final String shipId;
    /** Who was aboard when the crossing captured the crew. Empty, never null; never modified. */
    public final List<EntityPlayerMP> crew;

    ShipCrossingEvent(World world, String shipId, List<EntityPlayerMP> crew) {
        this.world = world;
        this.shipId = shipId;
        this.crew = crew == null
                ? Collections.<EntityPlayerMP>emptyList()
                : Collections.unmodifiableList(new java.util.ArrayList<>(crew));
    }

    /**
     * The craft is LEAVING. Posted before its blocks are cut, so a subscriber can still read the
     * world it is in.
     *
     * <p>Like {@link Arrival}, it carries no destination DIMENSION. Where a craft is going is its
     * destination coordinate, which each departure below carries; the slot world that will host that
     * cell is often not even allocated yet when the departure is announced — a hyperspace transit
     * materializes its target only on arrival.</p>
     */
    public static class Departure extends ShipCrossingEvent {
        Departure(World world, String shipId, List<EntityPlayerMP> crew) {
            super(world, shipId, crew);
        }
    }

    /**
     * The craft has ARRIVED: assembled, posed, re-crewed and settled where it was sent. Posted after
     * the crossing's own finalizer, so the ledger already answers for it.
     *
     * <p><b>There is deliberately no "dimension it came from".</b> Where a craft came from is its
     * origin COORDINATE, which each arrival below carries; the slot dimension is only which world
     * happens to host that cell right now, and for a transit restored across a restart the departure
     * slot no longer exists at all. A field that would have to carry a sentinel for a real case is
     * the same defect as one that carries null, and this class says elsewhere that it has none.</p>
     */
    public static class Arrival extends ShipCrossingEvent {
        Arrival(World world, String shipId, List<EntityPlayerMP> crew) {
            super(world, shipId, crew);
        }
    }

    /** How a commanded transit was flown. Not two mechanics — one, with two routes. */
    public enum Route {
        /**
         * Short enough that the parking step is skipped: the craft is cut and pasted straight into
         * the target cell. The transit manager decides this from distance and speed, not the caller.
         */
        DIRECT,
        /** Cut into hyperspace, flown for a real duration, then cut into the target cell. */
        HYPERSPACE
    }

    // ---------------------------------------------------------------- crossing a cell boundary

    /** The craft flew past a cell face and is being carried into the neighbour. */
    public static class LeftCell extends Departure {
        public final GalacticCoord origin;
        public final GalacticCoord destination;

        public LeftCell(World world, String shipId, List<EntityPlayerMP> crew,
                        GalacticCoord origin, GalacticCoord destination) {
            super(world, shipId, crew);
            this.origin = origin;
            this.destination = destination;
        }
    }

    /** The carried craft has settled in the neighbouring cell. */
    public static class EnteredCell extends Arrival {
        public final GalacticCoord origin;
        public final GalacticCoord destination;

        public EnteredCell(World world, String shipId, List<EntityPlayerMP> crew,
                           GalacticCoord origin, GalacticCoord destination) {
            super(world, shipId, crew);
            this.origin = origin;
            this.destination = destination;
        }
    }

    // ---------------------------------------------------------------- a commanded transit

    /**
     * A commanded jump has begun.
     *
     * <p>{@link #route} says whether it will be flown through hyperspace or crossed directly, which
     * is also how a subscriber knows whether the matching {@link TransitEnded} is imminent: a
     * {@link Route#DIRECT} transit ends within a few ticks, a {@link Route#HYPERSPACE} one after a
     * real journey. The expected arrival time is deliberately NOT carried — it is a live estimate the
     * transit manager owns and revises, and a copy in an event nothing re-publishes goes stale
     * silently.</p>
     */
    public static class TransitBegan extends Departure {
        public final GalacticCoord origin;
        public final GalacticCoord destination;
        public final Route route;

        public TransitBegan(World world, String shipId,
                            List<EntityPlayerMP> crew,
                            GalacticCoord origin, GalacticCoord destination, Route route) {
            super(world, shipId, crew);
            this.origin = origin;
            this.destination = destination;
            this.route = route;
        }
    }

    /** A commanded jump has completed: the craft is settled in the target cell. */
    public static class TransitEnded extends Arrival {
        public final GalacticCoord origin;
        public final GalacticCoord destination;
        public final Route route;

        public TransitEnded(World world, String shipId,
                            List<EntityPlayerMP> crew,
                            GalacticCoord origin, GalacticCoord destination, Route route) {
            super(world, shipId, crew);
            this.origin = origin;
            this.destination = destination;
            this.route = route;
        }
    }

    // ---------------------------------------------------------------- planet and space

    /**
     * A trip between a planet and space, published ONCE, when it has completed.
     *
     * <p><b>These two are directions, not the two ends of one trip</b>, and that is why they do not
     * extend {@link Departure} / {@link Arrival} like the symmetric mechanics above. A cell crossing
     * and a transit have two moments worth publishing because both ends are cells and a subscriber
     * may care about either; a planet trip is asymmetric — one end is a world with no coordinate at
     * all — so what a subscriber can act on is that the trip HAPPENED and in which direction. The
     * model is asymmetric because the domain is.</p>
     *
     * <p>{@link #world} is where the craft ENDED UP: the slot world for a launch, the planet for a
     * descent. The three fields below are the same for both directions, so a consumer that only
     * cares "which planet, which cell" reads them without knowing the direction.</p>
     */
    public static class PlanetTrip extends ShipCrossingEvent {
        /** The planet's dimension — the only handle a planet has, having no cell coordinate. */
        public final int planetDim;
        /** The slot world holding {@link #cell}. */
        public final int cellDim;
        /** The space side of the trip. */
        public final GalacticCoord cell;

        PlanetTrip(World world, String shipId, List<EntityPlayerMP> crew,
                   int planetDim, int cellDim, GalacticCoord cell) {
            super(world, shipId, crew);
            this.planetDim = planetDim;
            this.cellDim = cellDim;
            this.cell = cell;
        }
    }

    /**
     * The craft LEFT a planet and is now in space: it climbed past the launch dimension's ceiling and
     * has settled in {@link #cell}. {@link #world} is the slot world it arrived in.
     */
    public static class LeftPlanet extends PlanetTrip {
        public LeftPlanet(World world, String shipId, List<EntityPlayerMP> crew,
                          int planetDim, int cellDim, GalacticCoord cell) {
            super(world, shipId, crew, planetDim, cellDim, cell);
        }
    }

    /**
     * The craft ENTERED a planet's dimension from space. {@link #world} is the planet.
     *
     * <p><b>This is entry, not landing.</b> The descent puts the craft into the planet's world at its
     * arrival pose; whether and when it touches down is a later and separate question that this
     * moment does not witness, and the event is named for what it saw.</p>
     */
    public static class EnteredPlanet extends PlanetTrip {
        public EnteredPlanet(World world, String shipId, List<EntityPlayerMP> crew,
                             int planetDim, int cellDim, GalacticCoord cell) {
            super(world, shipId, crew, planetDim, cellDim, cell);
        }
    }
}
