package dev.stannismod.stellurgy.space;

import net.minecraft.server.MinecraftServer;
import net.minecraftforge.fml.common.FMLCommonHandler;

import dev.stannismod.stellurgy.Stellurgy;
import dev.stannismod.stellurgy.api.StellurgyConfiguration;
import dev.stannismod.stellurgy.integration.vs.VSIntegration;

/**
 * The movable-ship space subsystem's STATE OBJECT: the six services are its fields, wired by its one
 * constructor, and they live and die together. They used to be six separate mutable statics on this
 * class — a shape that let a test seam replace them with no way back, and let each probe wire a
 * different subset of what production wires.
 *
 * <p><b>It does not own its own lifetime, and deliberately cannot.</b> This class has a public
 * constructor, so it is not a singleton and no {@code static current} of it could ever mean anything.
 * Its owner is the mod object, {@link Stellurgy}: that holds the server's instance in a field,
 * drives the lifecycle steps below by handing them that field, and is the one route to it
 * ({@link Stellurgy#spaceSubsystem()}). The steps stay here because they are space's business,
 * but each one now TAKES the subsystem it acts on instead of looking one up.</p>
 *
 * <p>There is exactly ONE accessor, and it answers with the whole stack. The six per-service statics
 * this class used to publish ({@code space()}, {@code ledger()}, {@code transit()}, …) are gone
 * deliberately: with two instances alive — one built by a fixture, one held by the mod — which one a
 * caller reached depended on WHICH ACCESSOR it happened to use, and nothing could ask whose subsystem
 * it had. Callers now read the services off one object, so a swap landing between two reads can no
 * longer hand out half of each stack.</p>
 *
 * <p>The Forge subscriptions that drive it live beside it in {@link SpaceSubsystemEvents}, which is a
 * class of static handlers reaching into this object rather than an object pretending to be a
 * handler.</p>
 *
 * <p>GC cadence (maintainer-ratified): a periodic tick sweep ({@link #GC_TICK_INTERVAL}) plus a
 * pool-pressure trigger. A single WARN fires only when the pool is saturated and a live bubble slot is
 * force-evicted (the real overload signal); tier-2 store GC over idle cells stays quiet.</p>
 *
 * <p>Server main thread only.</p>
 */
public final class SpaceSubsystem {

    /** Periodic GC sweep interval, in server ticks (~30 s at 20 tps). Internal cadence, not a config knob. */
    private static final int GC_TICK_INTERVAL = 600;

    // ---- this subsystem's own state: five services that live and die together -----------------

    // Final and public: this is a state object, and the code that drives it — the Forge handlers
    // beside it in this package, and a probe holding a subsystem it built for itself — reads its
    // fields. They cannot be reassigned, so "public" costs nothing the rule cares about: the defect
    // was five INDEPENDENTLY WRITABLE statics with no lifecycle, not a readable field on an object
    // somebody already has in hand.
    public final SpaceManager manager;
    public final ShipLedger ledger;
    public final ShipTransitManager transit;
    /** The transit's hyperspace crosser; held here too for its census of the last cut, lane and re-seat. */
    public final VSShipCrosser crosser = new VSShipCrosser();
    public final ShipEntryController entry;
    public final DescentController descent;
    public final CellCrossingController cellCrossings;
    public final AssemblyCrewRebind crewRebind = new AssemblyCrewRebind();
    public final SlotBindings slotBindings = new SlotBindings();
    /** The sky producers' broadcast cadence and derived-content cache, for this server. */
    public final SystemBodiesProducer skyProducer = new SystemBodiesProducer();
    private int gcTickCounter;
    /** Set by the pool-pressure eviction listener; consumed on the next server tick to run an extra GC. */
    private boolean pressureGcRequested;
    /** Armed by {@link #armSaveFaultOnce()}; consumed by the next save point that reaches it. */
    private boolean saveFaultArmed;

    /**
     * Wire a subsystem. <b>This is the ONE construction site</b>, and every {@code null} argument
     * means "exactly what production uses" — which is the point of it. A probe needing its own slot
     * binder and its own clock says only that, and cannot end up without production's arrival standoff
     * or its offline-progress policy because nobody remembered to attach them.
     *
     * <p>Not hypothetical: the transit probe built its stack by hand and diverged from production on
     * four axes at once, while the entry probe, in the same file, re-attached two of them with a
     * comment explaining that forgetting them makes a whole suite "quietly measure a different game".
     *
     * @param binder {@code null} &rarr; the production pool binder
     * @param clock  {@code null} &rarr; the production space clock
     * @param config {@code null} &rarr; the manager config the current Stellurgy config asks for
     */
    public SpaceSubsystem(SlotBinder binder, java.util.function.LongSupplier clock,
                          SpaceManager.Config config) {
        // Read once, and tolerated as absent: a caller that supplies its own config is not asking
        // this constructor to consult the game's, and a unit test has no config at all. Every use of
        // cfg below is null-guarded for that reason, not by accident.
        StellurgyConfiguration cfg = StellurgyConfiguration.getCurrentConfig();
        SlotBinder useBinder = binder == null ? new PoolSlotBinder() : binder;
        java.util.function.LongSupplier useClock = clock == null ? SpaceSubsystem::spaceClock : clock;
        SpaceManager.Config useConfig = config != null ? config
                : new SpaceManager.Config(parseGcPolicy(cfg == null ? null : cfg.spaceCellGcPolicy),
                        cfg == null ? 0L : cfg.spaceCellMaxAgeTicks,
                        cfg == null ? 0 : cfg.spaceMaxStoredCells);
        // The pool-pressure signal comes back to THIS stack. It used to be a static callback that
        // looked up whatever was attached, so a probe-built subsystem's saturation asked the
        // PRODUCTION one for a sweep while its own pool stayed full.
        this.manager = new SpaceManager(useBinder, useClock, useConfig, (cellKey, wasDirty) -> {
            Stellurgy.logger.warn("[SPACE] pool pressure - force-evicted live cell {} ({}); "
                            + "raise spaceCellPoolSize if this recurs",
                    cellKey, wasDirty ? "flushed to store" : "discarded");
            this.requestPressureGc();
        });
        this.ledger = new ShipLedger();
        // A cell is protected from garbage collection while a ship is parked in it. That fact already
        // lives in the ledger, so the manager asks it rather than keeping a second flag of its own.
        this.manager.setClaimedCells(cellKey -> this.ledger.holdsShipIn(cellKey));
        // The WORLD's lane allocator, never a fresh one: lanes are a property of the single hyperspace
        // world every subsystem parks in, so a per-subsystem allocator hands out lanes another one is
        // already using.
        this.transit = new ShipTransitManager(this.manager, dev.stannismod.stellurgy.Stellurgy.serverState().hyperspace.lanes(),
                this.crosser, this.ledger, useClock);
        this.transit.setOfflineProgress(new OfflineProgress(
                OfflineProgress.parseMode(cfg == null ? null : cfg.spaceTransitOfflineProgress),
                SpaceSubsystem::isPlayerOnline));
        this.transit.setArrivalPlacement(SpaceSubsystem::arrivalStandoff);
        this.transit.setFrames(SpaceSubsystem::cellFrameOriginAt);
        this.entry = new ShipEntryController(this.manager, this.ledger, new VSShipCrossingOps(),
                SpaceSubsystem::launchBodyAddress, useClock);
        this.descent = new DescentController(this.manager, this.ledger, new VSShipCrossingOps(),
                new VSDescentPasteResolver(), useClock);
        this.cellCrossings = new CellCrossingController(this.manager, this.ledger, new VSShipCrossingOps(),
                useClock, SpaceSubsystem::zoneMembershipOf);
        // A jump too short to be worth a hyperspace leg is performed by the same machinery that carries
        // a ship across a cell face — one crossing, ledger straight to the destination, no lane and no
        // mid-flight. The transit manager decides WHICH jumps those are; this hands it the means.
        this.transit.setDirectCrosser((shipId, origin, originSlotDim, originAnchor, target) -> {
            // The transit manager keys ships by STRING, the ledger and the crossing by UUID. Not every
            // string is one: a fixture may depart under a synthetic name, and a crossing cannot look
            // that up. Refuse it here rather than throw out of a departure the pilot has paid for.
            java.util.UUID durableId;
            try {
                durableId = java.util.UUID.fromString(shipId);
            } catch (IllegalArgumentException notADurableId) {
                Stellurgy.logger.warn("[SPACE] direct crossing refused for ship '{}': it is not "
                        + "a durable id, so nothing can resolve it in the ledger", shipId);
                return false;
            }
            // Stood OFF the destination's bodies exactly as a hyperspace arrival is (the placement set
            // above): a short jump is the same jump, and without this it lands ON a body's address —
            // inside the descent radius, where the flight computer takes the ship down on its first
            // settled tick with nobody asking. A cell with no body is returned untouched.
            return this.cellCrossings.requestDirectJump(originSlotDim, originAnchor, durableId,
                    origin, arrivalStandoff(shipId, target, useClock.getAsLong()));
        });
    }

    /** One GC tick of this subsystem's cadence; {@code true} when a sweep ran. */
    boolean tickGc() {
        boolean run = false;
        if (pressureGcRequested) {
            pressureGcRequested = false;
            run = true;
        }
        if (++gcTickCounter >= GC_TICK_INTERVAL) {
            gcTickCounter = 0;
            run = true;
        }
        return run;
    }

    /** Ask for an extra GC sweep on the next tick (the pool-pressure trigger). */
    void requestPressureGc() {
        pressureGcRequested = true;
    }

    /**
     * Whether the production subsystem should register the space dimensions on server start. Pure decision
     * surface — factored out so the one remaining condition (once-per-session idempotence) is
     * unit-testable without booting a server.
     *
     * <p>The decision deliberately does NOT consider whether the JVM runs in test mode. Space is the
     * point of this mod, so it registers wherever the mod runs — an interactive session launched with
     * the probe property is a session that wants to fly, and a harness run that needs scratch cells
     * takes them from {@link SpaceSlotPool#registerAdditionalSlots(int)}, which APPENDS to the pool
     * and therefore cannot disturb what production already registered.</p>
     *
     * <p><b>There is no config flag here, and that is the decision.</b> {@code enableSpaceSubsystem}
     * was removed on 2026-09-18 (maintainer: <i>"давай вообще уберём условие регистрации космоса, он
     * слишком централен"</i>). Space is not a feature of this mod, it is its subject: the dimension
     * pool, hyperspace and tier-2 transit are what everything above them is built on, so a server
     * that boots without them is not a lighter server but a different, broken game. A toggle on
     * something that central buys a configuration nobody should run and costs every layer above it a
     * branch for a state it cannot handle.</p>
     *
     * <p><b>The Valkyrien Skies condition is gone too, for the same reason and one more.</b> It asked
     * {@code VSIntegration.isAvailable()}, which probes for a VS class on the classpath — and VS is
     * VENDORED into this jar: {@code build.gradle} compiles {@code valkyrienskies/src/main/java}
     * into the main source set and says in as many words that "VS is a mandatory part of the mod".
     * So the answer was always yes, and the {@code false} branch was reachable only by a stripped or
     * repacked jar, which is a broken build rather than a configuration. Standing the subsystem down
     * for it was not a graceful degradation either — it produced a server with no cells, no
     * hyperspace and no tier-2 transit, which is the very outcome this decision now refuses to keep
     * a path to. A repacked jar fails at class load instead, where the cause is legible.</p>
     *
     * <p>What is left is ONE condition, and it gates on nothing the operator or the environment can
     * say: {@code alreadyBuilt} — a single-player re-open reuses the JVM-global registration. It
     * stays a named function rather than an inlined {@code != null} so that the once-per-session
     * rule keeps a witness at the unit tier.</p>
     */
    public static boolean shouldRegister(boolean alreadyBuilt) {
        return !alreadyBuilt;
    }

    /** Extra headroom above the cells' topmost realizable pose, so a ship can maneuver at the very
     *  top of a cell without touching the physics clamp. {@code tunable}. */
    private static final double SHIP_CEILING_MARGIN = 2_000d;

    /**
     * The ship-altitude ceiling the slot cells require: the top of the realized pose band
     * ({@link GalacticCoord#HALF_CELL}, since the cell is centred on the world origin) plus a
     * maneuvering margin. Pure, so the "every realizable cell pose is inside the initialized range"
     * contract is directly checkable.
     */
    public static double requiredShipCeiling() {
        return (double) GalacticCoord.HALF_CELL + SHIP_CEILING_MARGIN;
    }

    /**
     * The ship-altitude FLOOR the slot cells require — the mirror of {@link #requiredShipCeiling}.
     *
     * <p>It exists because the pose band is centred on the world origin: half of every cell is at
     * negative world Y, which the old {@code +HALF_CELL} shift had made unreachable and therefore
     * unnecessary to ask about. The substrate keeps a lower limit beside its upper one
     * ({@code VSConfig.shipLowerLimit}), and a band that is not declared to it is a band a ship is
     * clamped out of on its next physics step — silently, and in the half of the cell nobody is
     * looking at.</p>
     */
    public static double requiredShipFloor() {
        return -((double) GalacticCoord.HALF_CELL + SHIP_CEILING_MARGIN);
    }

    /**
     * Server-start step: register the pool (once per JVM) and build this server's subsystem, unless
     * {@link #shouldRegister} says to stand down — which now happens for exactly one reason, a
     * subsystem already built in this JVM.
     *
     * <p>Returns what the OWNER should hold from here on — {@code existing} untouched when standing
     * down, a freshly wired subsystem otherwise. It takes the owner's current value and gives one
     * back rather than writing a field of its own: this class cannot be the thing that decides which
     * subsystem is the server's, because it is not a singleton and there may legitimately be another
     * instance in the same JVM (a fixture ticking its own isolated stack).</p>
     *
     * <p>Registration runs wherever the mod runs: it is NOT conditioned on the JVM's test property.
     * Space is the mod's subject, so a session that can fly is the only useful default — conditioning
     * it on a diagnostic property once disabled the very subsystem a playtest was diagnosing, with the
     * ship stopping dead at the physics clamp and no feedback. Probe-driven tests that want scratch
     * cells of their own take them from {@link SpaceSlotPool#registerAdditionalSlots(int)}, which
     * APPENDS fresh dimensions to the pool THIS subsystem binds from, while
     * {@link SpaceSlotPool#registerPool(int)} is idempotent — so the two cannot fight over slot ids.</p>
     */
    public static SpaceSubsystem buildForServer(SpaceSubsystem existing) {
        // The config is still read — for the pool SIZE, the cell GC policy and the home-system
        // anchor. What it no longer carries is an on/off switch for the subsystem itself.
        StellurgyConfiguration cfg = StellurgyConfiguration.getCurrentConfig();
        if (!shouldRegister(existing != null)) {
            // Nothing to log: the only way here is a single-player re-open reusing the JVM-global
            // registration, which is an internal, expected no-op and was always kept quiet.
            return existing;
        }
        // Register the physical slot dimensions once per JVM; a single-player world re-open reuses the
        // already-registered dims (DimensionManager registration is JVM-global and re-registering throws).
        if (dev.stannismod.stellurgy.Stellurgy.serverState().slots.slotDims().isEmpty()) {
            dev.stannismod.stellurgy.Stellurgy.serverState().slots.registerPool(Math.max(1, cfg.spaceCellPoolSize));
        }
        // Register the shared hyperspace dim UPFRONT here, exactly like the pool (cheap - a Forge map
        // entry, no world loaded until a ship first transits). Idempotent, so safe on a single-player
        // re-open. Consistent with the pool + gives a predictable id at a known point.
        dev.stannismod.stellurgy.Stellurgy.serverState().hyperspace.register();
        SpaceManager.Config mgrConfig = new SpaceManager.Config(
                parseGcPolicy(cfg.spaceCellGcPolicy),
                cfg.spaceCellMaxAgeTicks,
                cfg.spaceMaxStoredCells);
        // Through the same constructor every other caller uses, with no knob overridden: production
        // IS the default, so "the probe wired something production does not" and its mirror are both
        // off the table by construction.
        SpaceSubsystem built = new SpaceSubsystem(null, null, mgrConfig);
        Stellurgy.logger.info("[SPACE] subsystem online: pool={} gcPolicy={} maxStored={} maxAgeTicks={}",
                dev.stannismod.stellurgy.Stellurgy.serverState().slots.slotDims().size(), mgrConfig.gcPolicy, mgrConfig.maxStoredCells, mgrConfig.maxAgeTicks);
        return built;
    }

    /**
     * Server-STARTED hook (worlds are up, MapStorage reachable): restore the space clock, and then
     * the persisted ship ledger so the server's knowledge of every settled ship survives a restart.
     * Runs before any player login. The LEDGER half is a no-op when the subsystem stood down
     * ({@code live} null: disabled, or no VS); the CLOCK half is not — see below.
     */
    public static void onServerStarted(SpaceSubsystem live) {
        MinecraftServer server = FMLCommonHandler.instance().getMinecraftServerInstance();
        ShipLedgerData data = ShipLedgerData.get(server);
        // The clock FIRST, and BEFORE the stand-down check. Every value restored below is dated
        // against it, and a ledger age or a transit ETA read at tick zero while its stamp came from
        // last session is not merely stale, it is in the future.
        //
        // It is restored even with the subsystem down, because the clock is not the SUBSYSTEM's:
        // spaceClock() is public and is read by code that has no idea whether space registered - a
        // memory crystal stamps the freshness of every address it is seeded with, from any world,
        // with or without Valkyrien Skies - and such a stamp OUTLIVES the session in storage of its
        // own. A counter that restarted at zero would leave every one of them permanently in the
        // future, so the freshest observation could never win a merge again. A world with none
        // stored (a new save) starts at zero, which is where a new clock starts.
        if (data != null) {
            Stellurgy.serverState().setSpaceClock(data.clock());
        }
        if (live == null) {
            return;
        }
        if (data != null) {
            data.loadInto(live.ledger);
            Stellurgy.logger.info("[SPACE] restored {} settled ship(s) from disk", live.ledger.size());
            // Restore when each cell was last visited, or every stored cell looks freshly visited on
            // this boot and age-based collection can never reach an earlier session's leftovers.
            live.manager.importVisits(data.loadVisits());
            // Recreate any in-flight jump so a transit survives a restart: a record whose hull is still
            // standing in its lane resumes as that same ship, and one whose lane came back empty falls
            // back to the block snapshot it carries. The ledger is re-marked IN_TRANSIT inside
            // importTransit.
            //
            // LOAD hyperspace first, and this is load-bearing rather than tidy. Both readers below
            // ask what is standing in a lane, and both ask it of the world only IF IT IS LOADED -
            // an honest refusal to create a world as a side effect of inspecting one. Hyperspace is
            // otherwise loaded lazily by the first crossing, which happens long after this runs, so
            // without this every record would see an empty lane and take the snapshot path, and the
            // reconciliation below would find nothing to collect however many hulls were there.
            // Skipped entirely when the save has no hyperspace folder: then there is provably
            // nothing parked, and loading would pin an empty world on every boot of every save.
            if (SpaceSlotPool.hyperspaceStoreExists()) {
                dev.stannismod.stellurgy.Stellurgy.serverState().hyperspace.getOrCreate();
            }
            java.util.List<TransitRecord> records = data.loadTransits();
            for (TransitRecord r : records) {
                live.transit.importTransit(r);
            }
            if (!records.isEmpty()) {
                Stellurgy.logger.info("[SPACE] restored {} in-flight transit(s) from disk",
                        records.size());
            }
            // JUMP-10, and it belongs HERE - after the last record has been imported. Hyperspace
            // outlives the server, so a hull can outlive the record that put it there; every ship
            // found in it is matched against the transits that claim a lane and the rest are
            // disposed of. Run one record too early and a perfectly good ship looks unclaimed.
            int disposed = live.transit.reconcileParkedShips();
            if (disposed > 0) {
                Stellurgy.logger.warn("[SPACE] disposed of {} ship(s) parked in hyperspace "
                        + "that no transit record claims", disposed);
            }
        }
    }

    /**
     * The launch BODY's full galactic address for a planet dimension: its zone cell via the
     * universe registry (the C-1 lookup), refined to the body's own local offset when the zone
     * content lists it. {@code null} (no placement / registry unreachable) makes the entry fall
     * back to the configured home-system anchor. Public: the production resolver is also what a
     * probe-built entry stack wires, so tests exercise the real lookup chain.
     */
    public static GalacticCoord launchBodyAddress(int dimId) {
        MinecraftServer server = FMLCommonHandler.instance().getMinecraftServerInstance();
        dev.stannismod.stellurgy.universe.UniverseRegistry reg =
                dev.stannismod.stellurgy.universe.UniverseRegistry.get(server);
        if (reg != null) {
            java.util.Optional<GalacticCoord> cell = reg.coordForPlanet(dimId);
            if (cell.isPresent()) {
                for (dev.stannismod.stellurgy.universe.SystemBody body : reg.bodiesAt(cell.get())) {
                    if (body.dimId() == dimId) {
                        // The body's own offset inside its zone cell, as of now: a moon is a live
                        // point inside its parent's neighbourhood, and a ship leaving it has to be
                        // put beside where the moon IS, not beside where its cell is named.
                        return body.addressAt(spaceClock());
                    }
                }
                return cell.get();
            }
        }
        StellurgyConfiguration cfg = StellurgyConfiguration.getCurrentConfig();
        return cfg == null ? null
                : dev.stannismod.stellurgy.universe.UniverseRegistry.parseAnchor(cfg.spaceHomeSystemCoord);
    }

    /**
     * Where a jump aimed at {@code target} actually ends: standing the ship off every descend-target
     * body of the target's own cell, by the same ring an entry uses.
     *
     * <p>Without this an arrival lands ON its destination. A planet's address IS its cell centre, and
     * the arrival settles the ship exactly onto the coordinate it aimed at, so the ship comes out of
     * hyperspace at distance zero from the body — well inside the descent radius — and the pilot's
     * first control input drops him onto the surface he had just spent a jump reaching. The entry
     * path has said this for as long as it has existed ({@link ShipEntryController#ENTRY_RING_BLOCKS}
     * is twice the descent radius for exactly this reason); the arrival path never had a counterpart.
     *
     * <p>A cell with no descend-target body — deep space, a hand-typed coordinate — is returned
     * UNTOUCHED. There is nothing to stand off from, and displacing a destination the pilot chose
     * rather than derived would be its own kind of wrong. Public for the same reason
     * {@link #launchBodyAddress(int)} is: a probe-built stack wires the production resolver.
     */
    public static GalacticCoord arrivalStandoff(String shipId, GalacticCoord target, long worldTick) {
        if (target == null) {
            return null;
        }
        MinecraftServer server = FMLCommonHandler.instance().getMinecraftServerInstance();
        dev.stannismod.stellurgy.universe.UniverseRegistry reg =
                dev.stannismod.stellurgy.universe.UniverseRegistry.get(server);
        if (reg == null) {
            return target;
        }
        // EVERY body, not only the ones a ship can land on. The question here is "is something there",
        // not "could I descend to it": arriving on top of a gas giant trips no descent trigger, but it
        // does put the ship at zero distance from the body, and an observer→body vector of zero is
        // dropped by the sky renderer — so the pilot spends a jump and arrives at a destination his
        // own sky does not draw.
        // The ring and the clearance come from the BODIES, as the entry path's do: each body's descent
        // shell is where its atmosphere begins, so a flat ring sits inside any shell wider than itself —
        // a moon's, a planet's.
        java.util.List<GalacticCoord> occupied = new java.util.ArrayList<>();
        long ring = ShipEntryController.ENTRY_RING_BLOCKS;
        long clearance = ShipEntryController.DESCENT_RADIUS_BLOCKS;
        for (dev.stannismod.stellurgy.universe.SystemBody body : reg.bodiesAt(target)) {
            occupied.add(body.addressAt(worldTick));
            ring = Math.max(ring, ShipEntryController.entryRingAround(body));
            clearance = Math.max(clearance, DescentShell.radiusAround(body));
        }
        return StandoffRing.standoffFrom(target, occupied, ring, clearance,
                shipId == null ? 0 : shipId.hashCode());
    }

    /**
     * Where a craft BELONGS at {@code tick}, given the address it currently holds — the production
     * reading of the reference-frame clause, decided by SPHERES.
     *
     * <p>{@code null} means "leave it alone", and it is the answer for three different situations
     * that must not be told apart by the caller: the craft is in no sphere's reach (a galactic cell
     * with no zone body, or one whose body's children are all far off), it is between the two
     * thresholds (the hysteresis), or the universe cannot be asked. All three mean the cube goes on
     * deciding, which is what it always did. A craft in a galactic cell is asked only the inward
     * question — its outward boundary is that cell's cube.</p>
     *
     * <p>Order matters: a child is tested BEFORE the parent's own boundary. A craft deep inside a
     * moon's sphere is also inside its planet's, and the innermost containing sphere is the one that
     * governs — asking the outer question first would answer "still in the planet's zone" and never
     * reach the moon.</p>
     */
    public static GalacticCoord zoneMembershipOf(GalacticCoord craftCoord, long tick) {
        MinecraftServer server = FMLCommonHandler.instance().getMinecraftServerInstance();
        return zoneMembershipIn(
                dev.stannismod.stellurgy.universe.UniverseRegistry.get(server), craftCoord, tick);
    }

    /**
     * The same decision, against a stated universe — the form the production entry point above is a
     * one-line binding of.
     *
     * <p>Split out so the whole membership rule can be driven without a server standing behind it.
     * It is the only decision in the crossing that consults the universe, and it is where a craft's
     * NAME is chosen: an answer that is well-formed but points at the wrong cell is invisible at
     * every layer below (a cell key carries no lattice width, so a mismatch renames rather than
     * fails). A rule with that failure mode may not be reachable only through a booted server.</p>
     */
    public static GalacticCoord zoneMembershipIn(
            dev.stannismod.stellurgy.universe.UniverseRegistry reg,
            GalacticCoord craftCoord, long tick) {
        if (craftCoord == null || craftCoord.cellBlocks() <= 0L) {
            return null;
        }
        // A craft in a GALACTIC cell is in the zone of whatever body stands in that cell — a planet's
        // own cell IS that planet's zone, seen from the galactic lattice. Only the INWARD question is
        // asked for it: its outward boundary is the cube, which the caller already applies. Without
        // this a craft that entered space from a planet — whose address is the planet's galactic cell
        // — could never be taken into a moon's zone however close it flew; only one that arrived in
        // a zoned cell by jump could.
        boolean galactic = craftCoord.zone() == null;
        GalacticCoord zoneCell = galactic ? craftCoord.cellCentre()
                : GalacticCoord.fromCellKey(craftCoord.zone());
        if (reg == null || zoneCell == null) {
            return null;
        }
        dev.stannismod.stellurgy.universe.SystemBody zoneBody = frameBodyAt(reg, zoneCell);
        if (zoneBody == null) {
            return null;
        }
        AbsolutePos craftAt = reg.originAt(craftCoord.cellCentre(), tick)
                .plus(craftCoord.localX(), craftCoord.localY(), craftCoord.localZ());

        // INWARD first — the innermost containing sphere governs.
        for (dev.stannismod.stellurgy.universe.SystemBody child : reg.systemBodiesAt(zoneCell)) {
            if (child == null || child == zoneBody || child.equals(zoneBody)
                    || !child.definesFrame()) {
                continue;
            }
            if (!java.util.Objects.equals(child.name().zone(), zoneBody.name().cellKey())) {
                continue; // not a child of THIS zone
            }
            // The child's EXTENT, as on the way out: a zone is entered where it would be left, so no
            // band between its realized span and its sphere belongs to neither.
            double childRadius = ZoneScale.extentRadiusBlocks(child, zoneBody, tick);
            AbsolutePos childAt = child.absoluteAt(tick);
            if (!CellSeam.hasEnteredZone(craftAt.distanceTo(childAt), childRadius)) {
                continue;
            }
            return addressIn(reg, ZoneScale.addressOnLattice(child.name().cellKey(),
                    latticeOf(reg, child, zoneBody, tick), craftAt.minus(childAt)), craftAt, tick);
        }

        if (galactic) {
            return null; // in no child's sphere; the cube decides the rest, as for any galactic cell
        }

        // OUTWARD — past this zone's own sphere, so the parent's lattice takes it.
        double zoneRadius = sphereRadiusOf(reg, zoneBody, zoneCell, tick);
        if (!CellSeam.hasLeftZone(craftAt.distanceTo(zoneBody.absoluteAt(tick)), zoneRadius)) {
            return null;
        }
        if (zoneCell.zone() == null) {
            // The parent lattice is the GALACTIC one, which is addressed by absolute sectors rather
            // than by an offset from a body. The craft keeps its position; only its name changes.
            return addressIn(reg, zoneCell, craftAt, tick);
        }
        // Resolved STRICTLY, and deliberately not reused from `primary`: that one falls back to the
        // star when a parent cannot be found, which is the right reading for a RADIUS (a sphere has
        // to be measured against something) and the wrong one for an ADDRESS — it would name the
        // craft in the star's lattice, four levels away from where it is. A craft whose parent body
        // is missing is left where it is instead.
        dev.stannismod.stellurgy.universe.SystemBody grandparent =
                frameBodyAt(reg, GalacticCoord.fromCellKey(zoneCell.zone()));
        if (grandparent == null) {
            return null;
        }
        // The grandparent's lattice is the one THIS zone's body is itself named in — the craft is
        // moving out into the company of its own parent, so the width is already on the name it is
        // leaving. Read through the same helper anyway: one call site cannot be the place a width is
        // decided, and the inward branch has no such shortcut.
        return addressIn(reg, ZoneScale.addressOnLattice(grandparent.name().cellKey(),
                latticeOf(reg, grandparent, primaryOf(reg, grandparent.name()), tick),
                craftAt.minus(grandparent.absoluteAt(tick))), craftAt, tick);
    }

    /**
     * The address a craft at {@code craftAt} holds, given the CELL a lattice has just named for it —
     * with the in-cell offset measured from the origin that cell actually rides.
     *
     * <p>The lattice answers a cell and an offset from the zone body, and for an EMPTY cell those are
     * the same thing: an empty cell's origin is its zone body displaced by the lattice slot. A cell a
     * body STANDS in is different — its origin is that body ({@code UniverseRegistry.originAt} clause
     * one), which is what "a cell rides its primary" means — and the body does not sit at its slot's
     * centre, only inside it. Handing the lattice's own offset to such a cell measures it from the
     * wrong point.</p>
     *
     * <p>That is not a rounding: a craft leaving a moon's sphere lands in the moon's OWN cell by
     * construction (a cell contains the sphere of the body it names), so this is the ordinary case
     * for the outward crossing rather than an edge of it. Measured before this existed: the craft was
     * displaced <b>321 994 blocks</b> at the instant it crossed — the gap between Luna and its
     * lattice slot — which is a teleport out of a crossing that is supposed to preserve position.</p>
     */
    private static GalacticCoord addressIn(dev.stannismod.stellurgy.universe.UniverseRegistry reg,
                                           GalacticCoord latticeAddress, AbsolutePos craftAt,
                                           long tick) {
        if (reg == null || latticeAddress == null || craftAt == null) {
            return null;
        }
        GalacticCoord cell = latticeAddress.cellCentre();
        dev.stannismod.stellurgy.space.BlockDelta off = craftAt.minus(reg.originAt(cell, tick));
        return cell.plusLocal(off.dx(), off.dy(), off.dz());
    }

    /**
     * The body a zone's own sphere of influence is measured AGAINST — the body whose zone that zone
     * lives in, and only where it lives in the galactic lattice, the system's star.
     *
     * <p>A sphere of influence is a two-body quantity: {@code r = a·(m/M)^(2/5)} with {@code a} the
     * separation from the body being orbited. For a planet that body is the star; <b>for a moon it is
     * its planet</b>, and reading the star there does not fail — it answers with a plausible number
     * about the wrong pair. Measured on Luna: against Earth its sphere is <b>264 731</b> blocks
     * (66 183 km, the published value); against Sol the same call returns <b>638 428</b>, so a craft
     * is judged still inside the moon's influence 2.4 times further out than it is, and the outward
     * crossing never fires where a pilot actually leaves.</p>
     *
     * <p>The inward test never had this: it measures a child against the zone body it was found in,
     * which is its parent by construction. Only the outward one had to name the pair itself.</p>
     */
    private static dev.stannismod.stellurgy.universe.SystemBody primaryOf(
            dev.stannismod.stellurgy.universe.UniverseRegistry reg, GalacticCoord cellOfZoneBody) {
        if (reg == null || cellOfZoneBody == null) {
            return null;
        }
        GalacticCoord parentZone = cellOfZoneBody.zone() == null ? null
                : GalacticCoord.fromCellKey(cellOfZoneBody.zone());
        dev.stannismod.stellurgy.universe.SystemBody parent =
                parentZone == null ? null : frameBodyAt(reg, parentZone);
        return parent != null ? parent : starOf(reg, cellOfZoneBody);
    }

    /**
     * The cell width of {@code zoneBody}'s own lattice at {@code tick} — the width the NAMING pass
     * recorded, and only failing that the width an undivided zone has.
     *
     * <p>A crossing may not size a lattice. The size depends on the innermost child of the zone,
     * which only the naming pass sees in full, and a second derivation does not announce a
     * disagreement: a cell key carries no width, so two lattices produce two different names for one
     * place and every reader downstream answers correctly about the wrong cell. Measured before this
     * existed: on the reference solar system a craft standing exactly where Luna stands was
     * addressed on a lattice four times too coarse — 7 397 280 blocks against the 1 849 320 Luna is
     * named on — so it arrived in the cell holding its PLANET and the moon beside it was not in its
     * sky.
     *
     * <p>The fallback is the childless reading and it is an ANSWER, not a stand-in: a zone that names
     * no body has nothing to divide for, so one cell spanning the whole sphere is what the naming
     * pass would have produced too. The zero handed to {@code cellBlocks} here therefore states a
     * fact the registry was asked for, rather than a parameter nobody filled in.</p>
     */
    private static long latticeOf(dev.stannismod.stellurgy.universe.UniverseRegistry reg,
                                  dev.stannismod.stellurgy.universe.SystemBody zoneBody,
                                  dev.stannismod.stellurgy.universe.SystemBody primary,
                                  long tick) {
        long named = reg == null ? GalacticCoord.WIDTH_UNKNOWN
                : reg.zoneLatticeBlocks(zoneBody.name());
        return named > 0L ? named : ZoneScale.cellBlocks(zoneBody, primary, 0L, tick);
    }

    /**
     * The width of the lattice INSIDE the zone whose own cell is {@code zoneCell}, at {@code tick} —
     * the same answer {@link #zoneMembershipIn} addresses a craft on, reached by a caller that holds
     * a cell rather than a body.
     *
     * <p>{@link GalacticCoord#WIDTH_UNKNOWN} when no body stands at that cell, and the caller must
     * say what it does about that rather than substituting a width: a wrong one does not fail, it
     * renames the cell.</p>
     *
     * <p>Public because the alternative is a second derivation of one quantity, and two derivations
     * of a lattice width do not conflict when they disagree — they produce different well-formed
     * names for one place. That is the defect this whole area was fixed for.</p>
     */
    public static long latticeWidthOfZone(dev.stannismod.stellurgy.universe.UniverseRegistry reg,
                                          GalacticCoord zoneCell, long tick) {
        dev.stannismod.stellurgy.universe.SystemBody zoneBody = frameBodyAt(reg, zoneCell);
        return zoneBody == null ? GalacticCoord.WIDTH_UNKNOWN
                : latticeOf(reg, zoneBody, primaryOf(reg, zoneCell), tick);
    }

    /**
     * The radius of the sphere bounding the zone whose own cell is {@code zoneCell}, at {@code tick}
     * — the sphere {@link #zoneMembershipIn} carries a craft OUT of, reached by a caller that holds a
     * cell rather than a body.
     *
     * <p>Empty when no body stands at that cell. Not zero: a zero radius is a real answer (a body
     * with no mass has no sphere, and {@link CellSeam#hasLeftZone} reads it as such), so a missing
     * body answered with one would read as a massless body.</p>
     *
     * <p>Public for the reason {@link #latticeWidthOfZone} is: a test that places a craft against
     * this boundary must read production's radius, measured against production's choice of primary.
     * That choice is exactly where a second derivation goes wrong — see {@link #primaryOf}.</p>
     */
    public static java.util.OptionalLong zoneSphereRadiusOf(
            dev.stannismod.stellurgy.universe.UniverseRegistry reg, GalacticCoord zoneCell, long tick) {
        dev.stannismod.stellurgy.universe.SystemBody zoneBody = frameBodyAt(reg, zoneCell);
        return zoneBody == null ? java.util.OptionalLong.empty()
                : java.util.OptionalLong.of(sphereRadiusOf(reg, zoneBody, zoneCell, tick));
    }

    /**
     * The one reading of a zone's sphere, shared by the crossing and by {@link #zoneSphereRadiusOf}:
     * the zone's EXTENT, never its realized span — a craft between a big planet's one realized cell
     * and its sphere of influence is still keeping station with the planet.
     */
    private static long sphereRadiusOf(dev.stannismod.stellurgy.universe.UniverseRegistry reg,
                                       dev.stannismod.stellurgy.universe.SystemBody zoneBody,
                                       GalacticCoord zoneCell, long tick) {
        return ZoneScale.extentRadiusBlocks(zoneBody, primaryOf(reg, zoneCell), tick);
    }

    /** The body whose frame {@code cell} rides, or {@code null} when the cell is void. */
    private static dev.stannismod.stellurgy.universe.SystemBody frameBodyAt(
            dev.stannismod.stellurgy.universe.UniverseRegistry reg, GalacticCoord cell) {
        if (reg == null || cell == null) {
            return null;
        }
        for (dev.stannismod.stellurgy.universe.SystemBody b : reg.bodiesAt(cell)) {
            if (b.definesFrame()) {
                return b;
            }
        }
        return null;
    }

    /** The star of the system {@code cell} belongs to — what a sphere of influence is measured against. */
    private static dev.stannismod.stellurgy.universe.SystemBody starOf(
            dev.stannismod.stellurgy.universe.UniverseRegistry reg, GalacticCoord cell) {
        if (reg == null || cell == null) {
            return null;
        }
        for (dev.stannismod.stellurgy.universe.SystemBody b : reg.systemBodiesAt(cell)) {
            if (b.kind() == dev.stannismod.stellurgy.universe.SystemBodyKind.STAR) {
                return b;
            }
        }
        return null;
    }

    /**
     * Where the cell NAMED {@code name} is, absolutely, at {@code tick} — the production
     * {@link dev.stannismod.stellurgy.space.CellFrames} lookup, resolved against the live universe
     * registry. Falls back to the static reading ({@code sector * CELL}) with no registry, which is
     * what a void cell really does anyway.
     *
     * <p>Public and static for the same reason {@link #launchBodyAddress(int)} is: a probe-built
     * stack wires the production resolver rather than a second one that could disagree with it.</p>
     */
    public static AbsolutePos cellFrameOriginAt(GalacticCoord name, long tick) {
        MinecraftServer server = FMLCommonHandler.instance().getMinecraftServerInstance();
        dev.stannismod.stellurgy.universe.UniverseRegistry reg =
                dev.stannismod.stellurgy.universe.UniverseRegistry.get(server);
        return reg == null ? AbsolutePos.ofCellName(name) : reg.originAt(name, tick);
    }

    /** The production frame lookup as a {@link CellFrames}. Never {@code null}. */
    public static CellFrames frames() {
        return SpaceSubsystem::cellFrameOriginAt;
    }

    /**
     * Take a final cut of every parked ship before the server writes its last save. Called from the
     * server-STOPPING hook, which runs while the worlds are still up and before {@code stopServer} saves
     * them; the periodic re-cut alone would leave the shutdown snapshot up to one period out of date, and
     * the shutdown save is the one a returning player actually resumes from. A no-op while the subsystem
     * is down, and it never propagates: a stop must not be turned into a crash by a snapshot.
     */
    public static void onServerStopping(SpaceSubsystem live) {
        if (live == null) {
            return;
        }
        try {
            int refreshed = live.transit.refreshSnapshots();
            if (refreshed > 0) {
                Stellurgy.logger.info("[SPACE] re-cut {} in-flight ship(s) before the shutdown save",
                        refreshed);
            }
        } catch (Exception failed) {
            Stellurgy.logger.error("[SPACE] could not re-cut the in-flight ships before shutdown; "
                    + "each jump keeps the snapshot it already carries", failed);
        }
    }

    /**
     * Arm a one-shot failure inside the next ship-ledger save point. The subsystem promises that a save
     * which fails part-way leaves the previously persisted fleet intact and leaves the server running,
     * and that promise is only worth what a test can make fail — the gather it protects is otherwise
     * total, which is the whole point of it and also why nothing can be made to break from outside.
     * Fired and disarmed by the first save that reaches it.
     */
    public void armSaveFaultOnce() {
        saveFaultArmed = true;
    }

    /**
     * Whether an armed save fault is still waiting to fire. It going false is how an observer knows a
     * save point actually reached the fault — which matters because the save that can take the server
     * down is the world autosave, not one a command asked for.
     */
    public boolean isSaveFaultArmed() {
        return saveFaultArmed;
    }

    /**
     * The armed fault, thrown from the middle of a save point's gather — where a mistake in that gather
     * would land, which is the one failure the handler undertakes to survive.
     */
    void failSavePointIfArmed() {
        if (saveFaultArmed) {
            saveFaultArmed = false;
            throw new IllegalStateException("armed ship-ledger save fault");
        }
    }

    /** Parse the {@code spaceCellGcPolicy} config string, defaulting to {@code BOTH} on an unknown value. */
    private static SpaceManager.GcPolicy parseGcPolicy(String value) {
        if (value == null) {
            return SpaceManager.GcPolicy.BOTH;
        }
        try {
            return SpaceManager.GcPolicy.valueOf(value.trim().toUpperCase(java.util.Locale.ROOT));
        } catch (IllegalArgumentException bad) {
            Stellurgy.logger.warn("[SPACE] unknown spaceCellGcPolicy '{}' - defaulting to BOTH", value);
            return SpaceManager.GcPolicy.BOTH;
        }
    }

    /**
     * The one clock every space-side elapsed-time computation reads, on EITHER side. Public so
     * machines that carry a lazy resource — a capacitor that is charged by arithmetic rather than by
     * ticking — measure their elapsed time against exactly the same counter a transit does, and so a
     * ship parked in an unloaded cell is never quietly on a different clock from one in a loaded
     * chunk.
     *
     * <p><b>Side-agnostic on purpose.</b> On the server this is the running server's own counter
     * ({@link dev.stannismod.stellurgy.ServerState#spaceTick()}), written out with the subsystem's
     * durable state and read back on server start; on a client it is {@link SpaceClockSync}, the synced copy of that same counter. No
     * caller needs to know which side it is on, and none may reach for a world's own clock instead:
     * every dimension except the overworld carries a clock that advances only while it ticks, so "the
     * total time of whatever world I am in" is a DIFFERENT quantity that merely looks like this one.
     * A jump aim once read that other quantity and put arrivals thousands of blocks off their target.
     * There is now no world clock anywhere in this answer, so that class of mistake has nothing left
     * to be made out of.</p>
     */
    public static long spaceClock() {
        return FMLCommonHandler.instance().getEffectiveSide().isClient()
                ? dev.stannismod.stellurgy.Stellurgy.proxy.clientSpaceClock()
                : Stellurgy.serverState().spaceTick();
    }

    /** Whether {@code player} is currently connected — the offline-progress crew-online check. */
    private static boolean isPlayerOnline(java.util.UUID player) {
        MinecraftServer server = FMLCommonHandler.instance().getMinecraftServerInstance();
        return server != null && server.getPlayerList().getPlayerByUUID(player) != null;
    }

}
