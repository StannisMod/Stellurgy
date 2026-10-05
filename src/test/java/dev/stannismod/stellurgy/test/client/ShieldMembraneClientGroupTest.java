package dev.stannismod.stellurgy.test.client;

import org.junit.FixMethodOrder;
import org.junit.Test;
import org.junit.runners.MethodSorters;
import org.lwjgl.input.Keyboard;

import dev.stannismod.stellurgy.affs.world.FieldSurfaceMath;
import dev.stannismod.stellurgy.test.Events;
import dev.stannismod.stellurgy.test.FixtureSite;
import dev.stannismod.stellurgy.test.PlayerPosition;
import dev.stannismod.stellurgy.test.PlayerState;
import dev.stannismod.stellurgy.test.Reply;
import dev.stannismod.stellurgy.test.ShieldTile;

import static dev.stannismod.stellurgy.test.ArrangementFailure.requireArranged;
import static org.junit.Assert.assertTrue;

/**
 * A player meeting a shield's membrane: the shield holds him on the side of ITS shell he came from.
 *
 * <p>NEW-GROUP: a shield's membrane against a PLAYER — the per-player, per-shell barrier an emitter
 * decides in {@code TileEntityFieldGenerator#shouldRepelEntity}. Its subject is a real player entity
 * walking through the world, which only a connected client puts there: the server tier's stand-in
 * player is never spawned into a world, so an emitter's scan of its influence box cannot see it. No
 * client group holds shield mechanics.</p>
 *
 * <p>What a scenario here does NOT see: the membrane's ENERGY economy (the shields are fed from an
 * unlimited source, so a hold is never refused for want of charge), and anything about entities
 * other than players, which take the other branch of the decision.</p>
 */
@FixMethodOrder(MethodSorters.NAME_ASCENDING)
public class ShieldMembraneClientGroupTest extends AbstractSharedClientE2ETest {

    /**
     * Distance between the two emitters. A placed emitter holds {@code TileEntityFieldGenerator}'s
     * default radius of 4, so at 6 the spheres overlap and the near side of B's shell lies inside A's
     * volume; and B's influence box (radius + 1.5) stops short of a player standing a block from A's
     * centre, so B first sees him on his way over.
     */
    private static final int SPACING = 6;
    /**
     * A link's deadline, for both shields lighting and the walk to B's hold. Measured 2026-10-03 on the
     * server clock, at the waits' 5-tick read granularity: 14 ticks from placing to both lit, 14 ticks
     * from pressing forward to B's first hold. The deadline is about 14 times that: it bounds a
     * failure and never decides a pass.
     */
    private static final int LINK_TICKS = 200;

    @Override
    protected String subsystem() {
        return "shield-membrane";
    }

    /**
     * A shield holds out a player who came at it from outside — even while he stands inside another
     * shield whose volume overlaps it.
     *
     * <p><b>Why this arrangement, and why the order of placement is part of it.</b> Two emitters on one
     * line, overlapping: the player starts inside A, outside B and outside B's influence box, and walks
     * toward B. For the last block before B's membrane both emitters see him clear of every shell: A
     * notes him inside, B notes him outside. Each shell's side is its own. The decision this pins was
     * once taken from one record per PLAYER that every emitter wrote, so at B's membrane it read
     * whichever emitter wrote last — and an emitter writes when it ticks, in the order the tiles were
     * placed. B is placed FIRST, so A writes last: on that shape B reads A's "inside" and lets him in.
     * Placed the other way round the same shape happens to hold him, which is why the order is
     * deliberate.</p>
     *
     * <p><b>What it cannot see</b>: whether A would wrongly hold him. B ticks before A and its hold puts
     * him back clear of every membrane before A looks, so in this arrangement A never sees him touching
     * anything — measured 2026-10-03: with every player touching a membrane held regardless of side,
     * A wrote no hold. A verdict on A was written and removed for that reason.</p>
     *
     * <p>red-witnessed: with {@code TileEntityFieldGenerator#shouldRepelEntity} at
     * {@code Boolean outside = playerOutsideShell.get(entity.getUniqueID());} reading a map made
     * {@code static} again — one record per player for every emitter, the shape it replaced — this
     * fails at "shield B let in a player who walked at it from outside ... no `shield_player_held`
     * carrying pos = 4026,150,4020 was recorded within 200 ticks" (2026-10-03).</p>
     *
     * <p>red-witnessed: with {@code TileEntityFieldGenerator#pushEntityBack} at
     * {@code Vec3d targetCenter = fieldCenter.add(} replaced by the field's own centre — B holds him, but
     * puts him INSIDE — this fails at "shield B held the player but did not keep him OUT: after the hold
     * he stands 1.0584229873233446 from B's centre, inside its inner surface at 3.5" (2026-10-03).</p>
     */
    @Test
    public void aShieldHoldsOutAPlayerWhoCameFromOutsideEvenFromInsideAnother() throws Exception {
        FixtureSite here = site();
        // The working air: the walk, the player's height and the jolt of a hold, over both shells' rows.
        here.requireClear(this::exec, 7, 6, "the air the player walks through between two shields");
        int floorY = here.y, z0 = here.z;
        int ax = here.x, bx = here.x + SPACING;
        Reply floor = Reply.of(exec("stellurgytest fill " + plot().dim + " " + (ax - 7) + " " + floorY + " "
                + (z0 - 2) + " " + (ax + 12) + " " + floorY + " " + (z0 + 4) + " minecraft:stone"));
        requireArranged("the floor could not be laid: " + floor, floor.ok());

        Events server = serverEvents();
        long built = server.markInstrumented();
        // B FIRST: see the javadoc. Each shield is an emitter in the floor, fed by a generator beside
        // it, fed in turn by an unlimited FE source.
        buildShield(bx, floorY, z0);
        buildShield(ax, floorY, z0);
        server.awaitRecordWithFields(built, "field_power_changed", "shield B never lit", LINK_TICKS,
                "pos", at(bx, floorY, z0), "powered", "true");
        server.awaitRecordWithFields(built, "field_power_changed", "shield A never lit", LINK_TICKS,
                "pos", at(ax, floorY, z0), "powered", "true");
        // Inside A (a block from its centre), outside B, facing B along +X.
        double startX = ax - 0.5D, startZ = z0 + 0.5D;
        standOnFloorTheClientHolds(startX, floorY + 1, startZ, -90.0F, 0.0F,
                "the player must stand inside shield A before he walks at shield B");

        // The four placements the scenario rests on, measured against the shields as production holds
        // them (each emitter's world centre and projected radius) and the player as the server holds him.
        ShieldTile a = ShieldTile.at(this::exec, plot().dim, ax, floorY, z0);
        ShieldTile b = ShieldTile.at(this::exec, plot().dim, bx, floorY, z0);
        String name = PlayerState.botName(this::exec);
        PlayerPosition standing = PlayerPosition.of(this::exec, name);
        double half = FieldSurfaceMath.FIELD_HALF_THICKNESS;
        double toA = distanceToCentre(a, standing), toB = distanceToCentre(b, standing);
        // The same half-size FieldSurfaceMath#influenceBox gives the box an emitter scans.
        double influenceHalf = b.radius() + FieldSurfaceMath.FIELD_THICKNESS + 0.5D;
        double gapToBBox = (b.worldX() - influenceHalf) - (standing.x + PLAYER_HALF_WIDTH);
        double aToB = Math.sqrt(sq(a.worldX() - b.worldX()) + sq(a.worldY() - b.worldY())
                + sq(a.worldZ() - b.worldZ()));
        System.out.println("FIXTURE shield-membrane: A " + a.worldX() + "," + a.worldY() + "," + a.worldZ()
                + " r=" + a.radius() + "; B " + b.worldX() + "," + b.worldY() + "," + b.worldZ() + " r="
                + b.radius() + "; player " + name + " at " + standing.x + "," + standing.y + "," + standing.z
                + "; to A " + toA + ", to B " + toB + ", gap to B's influence box " + gapToBBox
                + ", A-B " + aToB);
        requireArranged("the player does not stand inside shield A's inner surface (" + toA + " from its"
                + " centre, inner surface at " + (a.radius() - half) + ")", toA < a.radius() - half);
        requireArranged("the player does not stand outside shield B's outer surface (" + toB + " from its"
                + " centre, outer surface at " + (b.radius() + half) + ")", toB > b.radius() + half);
        requireArranged("the player starts inside shield B's influence box, so B sees him before he walks"
                + " (gap " + gapToBBox + ")", standing.x + PLAYER_HALF_WIDTH < b.worldX() - influenceHalf);
        requireArranged("shield B's near membrane is not inside shield A's volume (A-B " + aToB + ", B's"
                + " outer " + (b.radius() + half) + ", A's inner " + (a.radius() - half) + ")",
                aToB - (b.radius() + half) < a.radius() - half);

        long walk = server.mark();
        String held;
        try {
            bot().holdKey(Keyboard.KEY_W);
            held = server.awaitRecordWithFields(walk, "shield_player_held",
                    "shield B let in a player who walked at it from outside — it decided his side from"
                            + " a record shield A wrote while he stood inside A",
                    LINK_TICKS, "pos", at(bx, floorY, z0));
        } finally {
            bot().releaseKey(Keyboard.KEY_W);
        }
        PlayerPosition after = PlayerPosition.of(this::exec, name);
        double afterToB = distanceToCentre(b, after);
        System.out.println("FIXTURE shield-membrane: held " + held + "; after, " + afterToB + " from B");
        assertTrue("shield B held the player but did not keep him OUT: after the hold he stands "
                + afterToB + " from B's centre, inside its inner surface at " + (b.radius() - half)
                + " (" + held + ")", afterToB >= b.radius() - half);
    }

    /** Half a player's width: his bounding box is 0.6 wide (vanilla {@code EntityPlayer#setSize}). */
    private static final double PLAYER_HALF_WIDTH = 0.3D;
    /** His box's centre above his feet: half his 1.8 height (vanilla {@code EntityPlayer#setSize}). */
    private static final double PLAYER_HALF_HEIGHT = 0.9D;

    /** Distance from an emitter's world centre to the centre of the player's box — what production compares. */
    private static double distanceToCentre(ShieldTile emitter, PlayerPosition player) {
        return Math.sqrt(sq(player.x - emitter.worldX()) + sq(player.y + PLAYER_HALF_HEIGHT - emitter.worldY())
                + sq(player.z - emitter.worldZ()));
    }

    private static double sq(double v) {
        return v * v;
    }

    /** An emitter at {@code (x, y, z)} in the floor, its generator and an unlimited FE source behind it. */
    private void buildShield(int x, int y, int z) throws Exception {
        place("affs:field_generator", x, y, z);
        place("affs:shield_generator", x, y, z + 1);
        place("affs:admin_energy_source", x, y, z + 2);
    }

    private void place(String block, int x, int y, int z) throws Exception {
        Reply placed = Reply.of(exec("stellurgytest place " + plot().dim + " " + x + " " + y + " " + z + " "
                + block));
        requireArranged("failed to place " + block + " at " + at(x, y, z) + ": " + placed,
                placed.bool("placed"));
    }

    private static String at(int x, int y, int z) {
        return x + "," + y + "," + z;
    }
}
