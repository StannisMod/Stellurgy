package dev.stannismod.stellurgy.test;

import dev.stannismod.stellurgy.test.contract.LegacyWitness;

import static org.junit.Assert.assertTrue;

/**
 * One tier-2 craft a weapon scenario builds, addressed by IDENTITY for its whole life.
 *
 * <p>The four ship scenarios of the weapons family each grew a private copy of "build, poll until a
 * ship is managed near the site, teleport by position, unpark by position" — every step a positional
 * lookup, and every poll a wait on wall clock. Those verbs answer "whichever craft is nearest", which
 * on a shared server is a sibling's as often as it is this one's. This builds through production's own
 * assembly, waits on the substrate's own records that THIS craft registered ({@code ship_spawned},
 * keyed by its durable name) and loaded ({@code ship_loaded}, keyed by its physics id), and from then
 * on names the craft by that id in every call.</p>
 *
 * <p>A scenario that uses this disposes of what it built, by this id, before it ends —
 * {@link #dispose}, or {@link Disposal#ship} with the id a relocation re-registered it under.</p>
 */
public final class WarShip {

    /** How much WORLD the registration and load links are given — the budget the tier uses for them. */
    private static final int LINK_TICKS = 200;

    /** The name production gave the craft at assembly; survives every re-assembly. */
    public final String durable;
    /** The physics mod's id for the hull; the {@code vs} verbs are keyed on it. */
    public final String vsShip;

    private final Events log;
    private final ServerCommands commands;

    private WarShip(Events log, ServerCommands commands, String durable, String vsShip) {
        this.log = log;
        this.commands = commands;
        this.durable = durable;
        this.vsShip = vsShip;
    }

    /** Work done between laying the craft's blocks and assembling them. */
    public interface BeforeAssembly {
        void run(int[] builderPos) throws Exception;
    }

    /**
     * Lay the with-pilot-seat fixture at {@code site}, run {@code before}, assemble, and wait for THIS
     * craft to be registered and loaded.
     */
    public static WarShip build(Events log, ServerCommands commands, FixtureSite site, BeforeAssembly before,
                                String what) throws Exception {
        long mark = log.mark();
        int[] builder = RocketFixture.placeAt(site, c -> commands.arrange(c).raw(), "with-pilot-seat", 4, 12, what);
        if (before != null) {
            before.run(builder);
        }
        String asm = RocketFixture.assembleBuilt(site, c -> commands.act(c).raw(), builder);
        java.util.List<String> rockets = RocketFixture.rocketsBornAt(log, mark, builder);
        assertTrue("an AFC-bearing build must become a ship, not a rocket — its assembler made " + rockets
                + ": " + asm, rockets.isEmpty());
        String durable = ShipIdentity.nameFromAssembly(asm);
        String spawned = log.awaitRecordWithField(mark, "ship_spawned", "stellurgyShip", durable,
                what + " — no hull was ever registered for this craft", LINK_TICKS);
        String vsShip = Events.text(spawned, "vsShip");
        log.awaitField(mark, "ship_loaded", "vsShip", vsShip,
                what + " — hull " + vsShip + " reached the registry and never became a loaded ship",
                LINK_TICKS);
        return new WarShip(log, commands, durable, vsShip);
    }

    /** Dispose of this hull by its id ({@link Disposal#ship}); for a hull that was not relocated. */
    public void dispose() throws Exception {
        Disposal.ship(c -> commands.arrange(c).raw(), log, 0, vsShip);
    }

    /** The substrate's report on this hull, by id; refuses one it no longer manages. */
    public Reply info() throws Exception {
        Reply info = commands.read("stellurgytest vs ship-info 0 id " + vsShip);
        assertTrue("hull " + vsShip + " is not managed any more: " + info, info.bool("managed"));
        return info;
    }

    /** Where this craft's pilot seat is, in SUBSPACE — a block of the ship whose address is known. */
    public int[] seat() throws Exception {
        Reply seat = commands.read("stellurgytest vs find-seat 0 id " + vsShip);
        assertTrue("could not locate the seat of hull " + vsShip + ": " + seat, seat.bool("seatFound"));
        return new int[]{seat.integer("seatX"), seat.integer("seatY"), seat.integer("seatZ")};
    }

    /** A subspace block of this craft, mapped into the WORLD through this hull's own transform. */
    public double[] toWorld(int sx, int sy, int sz) throws Exception {
        Reply mapped = commands.read("stellurgytest vs to-world 0 id "
                + vsShip + " " + sx + " " + sy + " " + sz).requireOk("map a subspace block to the world");
        return new double[]{mapped.number("worldX"), mapped.number("worldY"), mapped.number("worldZ")};
    }

    /**
     * Rigid-teleport this hull to {@code (x,y,z)} and leave it PARKED there — physics off, so nothing
     * moves it between this call and the scenario's own readings.
     *
     * <p>The teleport only WRITES the pose and raises the physics object's force-adopt flag; the
     * physics object consumes that flag on its own next tick ({@code PhysicsObject#onTick}), and
     * until then the loaded pipeline still holds the old pose. So the link is that consumption —
     * {@code ship_teleport_adopted}, written by a test mixin at that tick for THIS hull's id, from a
     * mark taken before the teleport — and only then is the standing pose read, once, and required
     * to be at the destination.</p>
     */
    @LegacyWitness(prose = "with {@code VSBridge#teleportShip} at"
            + " {@code physo.setForceToUseShipDataTransform(true);} removed, {@code TurretOnAShipTest} fails"
            + " at the link with \"hull … was teleported to 6800,150,9200 and its physics object never adopted"
            + " the written pose — no `ship_teleport_adopted` carrying vsShip = … was recorded within 200 ticks\""
            + " (2026-09-30).")
    @LegacyWitness(prose = "with {@code VSBridge#teleportShip} at {@code ship.setShipTransform(moved);}"
            + " and {@code ship.setPrevTickShipTransform(moved);} removed (the flag raised, the pose never"
            + " written), {@code TurretOnAShipTest} fails at the pose requirement with an ArrangementFailure"
            + " \"… stands 2397.006049220569 blocks from it after adopting {…\"posX\":6802.0,\"posY\":155.0,…}\""
            + " (2026-09-30).")
    public void parkAt(int x, int y, int z, double tolerance) throws Exception {
        long mark = log.markInstrumented();
        Reply tp = commands.arrange(
                "stellurgytest vs teleport-ship-by-id 0 " + vsShip + " " + x + " " + y + " " + z);
        tp.requireOk("teleport hull " + vsShip);
        String adopted = log.awaitRecordWithField(mark, "ship_teleport_adopted", "vsShip", vsShip,
                "hull " + vsShip + " was teleported to " + x + "," + y + "," + z
                        + " and its physics object never adopted the written pose", LINK_TICKS);
        Reply at = info();
        double away = Math.sqrt(sq(at.number("posX") - x) + sq(at.number("posY") - y) + sq(at.number("posZ") - z));
        ArrangementFailure.requireArranged("hull " + vsShip + " was teleported to " + x + "," + y + ","
                + z + " and stands " + away + " blocks from it after adopting " + adopted + ": " + at,
                away < tolerance);
    }

    private static double sq(double v) {
        return v * v;
    }
}
