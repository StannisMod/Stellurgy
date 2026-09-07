package zmaster587.advancedRocketry.test.client;

import org.junit.FixMethodOrder;
import org.junit.Test;
import org.junit.runners.MethodSorters;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

import zmaster587.advancedRocketry.test.Events;

import static org.junit.Assert.assertTrue;

/**
 * P3 (§4.3): the shield must ride an assembled Valkyrien Skies ship. A ship's blocks live in a
 * distant SUBSPACE shipyard while the hull flies around the world, so a naive world-frame shield would
 * project its shell at the shipyard, thousands of blocks from where the ship is. The {@code FieldFrame}
 * seam maps each emitter's subspace centre out through the ship transform, so the shell tracks the
 * flying hull, and exposes the hull's own velocity so impacts are taken relative to it (a cruising ship
 * must not bill its own crew).
 *
 * <p>This is a CLIENT e2e because a VS ship only loads with an observer present (the headless server
 * cannot load one — {@code VSShipMotionServerTest} documents that limit). With the client near the ship
 * the hull loads on both sides, so the shield's frame is verified through precise server probes:
 * <ul>
 *   <li><b>Ship-framed.</b> The emitter's field is ship-framed and its world centre sits at the loaded
 *       ship's world position — far from its subspace block pos.</li>
 *   <li><b>Tracks the hull.</b> Pushing the ship moves the shell's world centre with it, and the shell's
 *       reported surface velocity becomes non-zero — the relative-velocity input the deflection uses.</li>
 *   <li><b>Deflects on board.</b> A charged shield on the ship deflects an inbound arrow off its shell
 *       (the same absorption math, now around the ship-transformed centre).</li>
 * </ul>
 * The frame-blind deflection math itself is pinned standalone by {@code ShieldImpactAbsorptionTest}; here
 * we verify the two frame-dependent quantities (moving world centre + live surface velocity) on a real
 * loaded ship, plus one on-hull deflection.</p>
 */
@FixMethodOrder(MethodSorters.NAME_ASCENDING)
public class VSShipFrameShieldE2ETest extends AbstractSharedVsClientE2ETest {

    @Override
    protected String subsystem() {
        return "vs-ship-frame-shield";
    }

    private static final Pattern BUILDER_POS =
            Pattern.compile("\"builderPos\":\\[(-?\\d+),(-?\\d+),(-?\\d+)]");
    private static final Pattern EMITTER_COUNT = Pattern.compile("\"count\":(-?\\d+)");
    private static final Pattern ENTITY_ID = Pattern.compile("\"entityId\":(-?\\d+)");

    private static final String VARIANT = "with-shield-emitter";
    private static final int BX = 5200, BY = 64, BZ = 5200;

    /** A deadline for the emitter's frame DECISION — the tile loads in the ship's subspace chunk,
     *  looks the managing ship up and its frame becomes ready once the hull is loaded on this side.
     *  A discrete decision production makes, not a value converging; the 200 ticks the two loops it
     *  replaces allowed between them are kept. */
    private static final int FRAME_BUDGET_TICKS = 200;

    @Test
    public void shieldRidesTheAssembledShipAndDeflectsOnBoard() throws Exception {

        exec("artest vs permaload true");
        exec("tp @a " + (BX + 40) + " 120 " + (BZ + 40) + " 0 0");
        bot().waitTicks(10);

        // The mark goes before the assembly, so every record read below belongs to THIS ship by
        // construction — where a count incremented on a shared world is answered by any neighbour
        // that ever assembled one. markInstrumented, because the frame decision further down is
        // recorded by a test-only mixin: an empty log would otherwise have a second silent cause.
        Events events = events();
        long spawnMark = events.markInstrumented();

        String assemble = assembleFixture(BX, BY, BZ);
        assertTrue("a with-shield-emitter build must route to a ship: " + assemble,
                assemble.contains("\"rocketCount\":0"));
        // The identity, from the registry's record of THIS assembly's own add.
        final String shipId = awaitShipSpawned(events, spawnMark,
                "a with-shield-emitter assembly must create a VS ship in the queryable registry");

        // Sit the client on the ship so the hull (and the emitter's chunk) loads server-side.
        exec("tp @a " + (BX + 0.5) + " " + (BY + 6) + " " + (BZ + 0.5) + " 0 0");
        bot().waitTicks(20);
        // ARRANGEMENT: the ship has a physics object at all — the thing a chunk read, a deck and a
        // frame lookup depend on, and which the registry record above does NOT imply. It is not a
        // proof that the client's approach loaded it: the same record is written when the spawn
        // queue constructs the object, which is before this tp. What NEEDS the loaded hull is the
        // frame's readiness below, and that is where the wait actually lands.
        events.await(spawnMark, "ship_loaded",
                "the assembled ship must get a physics object before anything can ride it",
                FRAME_BUDGET_TICKS);

        // The emitter's frame is a DECISION production makes and re-makes: it resolves whatever
        // FieldFrames.forBlock answers for its block, and that frame is only usable once it reports
        // itself ready. Awaited as the event that carries both, since the assembly. Polling the
        // emitters probe could not tell a WORLD frame (the VS lookup found no managing ship) from a
        // SHIP frame that is not ready (the hull is not loaded on this side) from a tile that never
        // entered the active set at all — and only the last of those three is a shield bug.
        String frames = "";
        String frame = null;
        for (int waited = 0; waited <= FRAME_BUDGET_TICKS && frame == null; waited += 5) {
            frames = events.since(spawnMark, "field_frame_resolved");
            frame = recordWithAll(frames, "\"shipFramed\":true", "\"ready\":true");
            if (frame == null) {
                bot().waitTicks(5);
            }
        }
        assertTrue("the ship's emitter must resolve a SHIP frame AND report it ready — a world-frame"
                + " shield on a ship would project its shell at the shipyard rather than the flying"
                + " hull, and a ship-framed emitter whose frame is not ready contributes no shell at"
                + " all (its centre falls back to the raw subspace block pos). Frames resolved since"
                + " the assembly: " + frames + "\nEverything the server recorded since the assembly: "
                + events.since(spawnMark), frame != null);

        // The emitter's own subspace position, off the record whose frame we just awaited — so every
        // read below is about THAT emitter and not about whichever one a list happens to lead with.
        String[] pos = String.valueOf(Events.firstField(frame, "pos")).split(",");
        assertTrue("a field_frame_resolved record must name the emitter's block pos: " + frame,
                pos.length == 3);
        int spX = Integer.parseInt(pos[0]), spY = Integer.parseInt(pos[1]), spZ = Integer.parseInt(pos[2]);

        // The probe's agreeing view, read ONCE now that the decision has been recorded. It is not
        // the link — it is what the geometry below is measured from, and this asserts the list it
        // leads with is the ship-framed emitter the event named.
        String emitters = exec("artest shield emitters 0");
        assertTrue("the frame log says a ship-framed emitter is ready, but the shield registry does"
                + " not list one:\n" + emitters,
                emitterCount(emitters) >= 1 && emitters.contains("\"shipFramed\":true"));
        double wx1 = f(emitters, "worldX"), wy1 = f(emitters, "worldY"), wz1 = f(emitters, "worldZ");

        // Check 1: the shell's world centre is at the loaded ship, FAR from the emitter's subspace pos
        // (VS relocates a ship's blocks thousands of blocks away into its shipyard).
        double subToWorld = dist(spX + 0.5, spY + 0.5, spZ + 0.5, wx1, wy1, wz1);
        assertTrue("the shell's world centre coincides with the raw subspace block pos (subToWorld="
                + subToWorld + ") — the frame did not map the centre out to the flying hull:\n" + emitters,
                subToWorld > 64.0);
        assertTrue("the shell's world centre is not near the ship's world position (worldXZ=" + wx1 + ","
                + wz1 + " ship=" + BX + "," + BZ + ") — the shell is not on the hull:\n" + emitters,
                Math.abs(wx1 - (BX + 0.5)) < 24.0 && Math.abs(wz1 - (BZ + 0.5)) < 24.0);

        // Check 3 (before pushing, while the ship is roughly settled): charge the emitter and deflect an
        // inbound arrow off the ship-framed shell. Re-read the world centre immediately so the arrow is
        // aimed at where the shell actually is this instant.
        String read = exec("artest shield read 0 " + spX + " " + spY + " " + spZ);
        int radius = (int) f(read, "radius");
        exec("artest shield charge 0 " + spX + " " + spY + " " + spZ + " 40000");
        String reRead = exec("artest shield read 0 " + spX + " " + spY + " " + spZ);
        assertTrue("charged emitter did not power:\n" + reRead, reRead.contains("\"powered\":true"));
        double cx = f(reRead, "worldX"), cy = f(reRead, "worldY"), cz = f(reRead, "worldZ");

        double sx = cx, sy = cy, sz = cz + radius; // on the +Z shell, aimed inward at the centre
        String spawn = exec("artest entity spawn 0 " + sx + " " + sy + " " + sz + " minecraft:arrow");
        int arrowId = entityId(spawn);
        exec("artest entity set-motion 0 " + arrowId + " 0 0 -0.4");
        exec("artest entity tick 0 " + arrowId + " 1");
        exec("artest tile force-tick 0 " + spX + " " + spY + " " + spZ + " 1");
        String arrow = exec("artest entity info 0 " + arrowId);
        // Re-read the centre once more; the deflection is measured against where the shell is now.
        double ncx = f(exec("artest shield read 0 " + spX + " " + spY + " " + spZ), "worldX");
        double dcx = ncx - cx; // how far the hull drifted while we set this up
        // Survival first, then geometry: an absorbed arrow is a shield interaction too, but the
        // kinetic path must REFLECT, and a dead arrow has no position to measure. (This assertion
        // used to sit below a guard that made the distance check conditional on the same fact.)
        assertTrue("the arrow was consumed, not deflected — the kinetic path should reflect it off the "
                + "ship's shell:\n" + arrow, arrow.contains("\"isAlive\":true"));
        double ax = f(arrow, "posX"), ay = f(arrow, "posY"), az = f(arrow, "posZ");
        double d = dist(ax, ay, az, cx + dcx, cy, cz);
        assertTrue("the arrow ended up inside the ship's shell (dist=" + d + " <= radius " + radius
                + ") — a charged shield on a VS ship did not deflect it off the hull:\n" + arrow,
                d > radius);

        // Check 2: the shell rides the hull as it MOVES, and its surface velocity is live (the
        // relative-velocity input). A just-assembled free hull drifts under its own physics; we perturb
        // it and confirm the shell's world centre stays LOCKED to the ship's world position across the
        // displacement — a direction- and rotation-agnostic "rides the hull" test (a raw +axis delta is
        // unreliable because a free hull also rotates, swinging an off-centre emitter, and push-ship
        // does not reliably impose a chosen direction here).
        double[] ship1 = shipPos(shipId);
        double[] shell1 = shellCenter();
        assertTrue("precondition: the shell must sit on the hull before it moves (shell=" + str(shell1)
                + " ship=" + str(ship1) + ")", dist(shell1, ship1) < 32.0);
        // A STIMULUS loop, deliberately left as one: it is not waiting for a link but accumulating a
        // physical displacement under repeated velocity writes the substrate keeps overwriting, and
        // "the hull has moved far enough to test tracking" is a measured quantity, not an event.
        for (int i = 0; i < 25; i++) {
            String push = exec("artest vs push-ship-by-id 0 " + shipId + " 0 14 0");
            scenario().requireArranged("the push must reach THIS ship: " + push,
                    push.contains("\"pushed\":true"));
            bot().waitTicks(2);
        }
        double[] ship2 = shipPos(shipId);
        String moved = exec("artest shield emitters 0");
        double[] shell2 = new double[]{f(moved, "worldX"), f(moved, "worldY"), f(moved, "worldZ")};
        double speed = Math.sqrt(sq(f(moved, "velX")) + sq(f(moved, "velY")) + sq(f(moved, "velZ")));
        double shipMoved = dist(ship1, ship2);
        double shellMoved = dist(shell1, shell2);
        scenario().requireArranged("the hull must actually move to test tracking (shipMoved=" + shipMoved
                + ") — perturb harder if VS pinned it", shipMoved > 1.5);
        assertTrue("the shell's world centre did not move with the hull (shellMoved=" + shellMoved
                + " shipMoved=" + shipMoved + ") — it is frozen, not tracking the flying hull:\n" + moved,
                shellMoved > 0.5);
        assertTrue("the shell detached from the hull after it moved (shell=" + str(shell2) + " ship="
                + str(ship2) + " dist=" + dist(shell2, ship2) + ") — the shell does not ride the hull:\n"
                + moved, dist(shell2, ship2) < 32.0);
        assertTrue("the shell's surface velocity stayed zero on a moving ship (speed=" + speed + ") — the "
                + "relative-velocity input is dead, so a cruising ship would bill its own crew:\n" + moved,
                speed > 0.0);
    }

    // ---- helpers -------------------------------------------------------------------------------

    /**
     * The first record in an {@code events since} reply that carries EVERY needle, or {@code null}.
     * Records are split on the envelope's own {@code {"seq":} prefix, exactly as
     * {@link Events#countRecords} does — needed here because the fact this class waits for is a
     * CONJUNCTION within one record ({@code shipFramed} and {@code ready} of the same resolution),
     * and a whole-reply {@code contains} would be satisfied by two different emitters, or by one
     * emitter's two different moments. Written locally: {@link Events} is not this class's to edit.
     */
    private static String recordWithAll(String sinceReply, String... needles) {
        for (String record : String.valueOf(sinceReply).split("\\{\"seq\":")) {
            boolean all = true;
            for (String needle : needles) {
                all &= record.contains(needle);
            }
            if (all) {
                return record;
            }
        }
        return null;
    }

    private int emitterCount(String json) {
        Matcher m = EMITTER_COUNT.matcher(json);
        return m.find() ? Integer.parseInt(m.group(1)) : 0;
    }

    private int entityId(String json) {
        Matcher m = ENTITY_ID.matcher(json);
        assertTrue("no entityId in: " + json, m.find());
        return Integer.parseInt(m.group(1));
    }

    /** Where THIS ship is — asked by identity, so it keeps answering about the same hull once the
     *  push has carried it away from the spot it was built on. */
    private double[] shipPos(String shipId) throws Exception {
        String si = exec("artest vs ship-info 0 id " + shipId);
        return new double[]{f(si, "posX"), f(si, "posY"), f(si, "posZ")};
    }

    private double[] shellCenter() throws Exception {
        String em = exec("artest shield emitters 0");
        return new double[]{f(em, "worldX"), f(em, "worldY"), f(em, "worldZ")};
    }

    private static double dist(double[] a, double[] b) {
        return dist(a[0], a[1], a[2], b[0], b[1], b[2]);
    }

    private static String str(double[] a) {
        return "(" + a[0] + "," + a[1] + "," + a[2] + ")";
    }

    private double f(String json, String key) {
        Matcher m = Pattern.compile("\"" + key + "\":(-?[0-9.E\\-]+)").matcher(json);
        assertTrue("expected key " + key + " in: " + json, m.find());
        return Double.parseDouble(m.group(1));
    }

    private static double dist(double x1, double y1, double z1, double x2, double y2, double z2) {
        return Math.sqrt(sq(x1 - x2) + sq(y1 - y2) + sq(z1 - z2));
    }

    private static double sq(double v) {
        return v * v;
    }

    private String assembleFixture(int baseX, int baseY, int baseZ) throws Exception {
        int cx1 = (baseX - 2) >> 4, cz1 = (baseZ - 2) >> 4;
        int cx2 = (baseX + 7) >> 4, cz2 = (baseZ + 7) >> 4;
        assertTrue("chunk warmup failed",
                exec("artest chunk warmup 0 " + cx1 + " " + cz1 + " " + cx2 + " " + cz2).contains("\"ok\":true"));
        assertTrue("pre-clear failed",
                exec("artest fill 0 " + (baseX - 2) + " " + (baseY + 1) + " " + (baseZ - 2)
                        + " " + (baseX + 7) + " " + (baseY + 10) + " " + (baseZ + 7) + " minecraft:air")
                        .contains("\"ok\":true"));
        String fixture = exec("artest fixture rocket 0 " + baseX + " " + baseY + " " + baseZ + " " + VARIANT);
        assertTrue("fixture (" + VARIANT + ") failed: " + fixture, fixture.contains("\"ok\":true"));
        Matcher bp = BUILDER_POS.matcher(fixture);
        assertTrue("fixture missing builderPos: " + fixture, bp.find());
        return exec("artest rocket assemble 0 " + bp.group(1) + " " + bp.group(2) + " " + bp.group(3));
    }
}
