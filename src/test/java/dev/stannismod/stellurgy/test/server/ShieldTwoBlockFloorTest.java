package dev.stannismod.stellurgy.test.server;

import dev.stannismod.stellurgy.test.Reply;
import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import dev.stannismod.stellurgy.affs.world.shield.ShieldNetworkManager;
import dev.stannismod.stellurgy.test.Events;
import dev.stannismod.stellurgy.test.GameTicks;
import dev.stannismod.stellurgy.test.ShieldTile;
import dev.stannismod.stellurgy.test.FixtureSite;

import static dev.stannismod.stellurgy.test.ArrangementFailure.requireArranged;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/**
 * P1 floor for the vendored AFFS shield: a shield network needs no cable.
 *
 * <p>The trimmed topology ({@code ShieldNetworkManager}) forms a network from block-adjacency of ALL
 * shield nodes, and the max-flow links a source's supply port directly to an adjacent sink's demand
 * port. So a shield <em>generator</em> touching a field <em>emitter</em> — two blocks, no cable, no
 * console, no accumulator — is a working shield.</p>
 *
 * <p><b>What this pins.</b> The positive method charges a generator that is directly adjacent to an
 * emitter and asserts the emitter powers up (energy crossed the cable-less edge). The control places
 * the same two blocks with a one-block gap and no cable between them: they fall into two disconnected
 * components, no flow crosses the gap, and the emitter never powers — proving it is the adjacency edge,
 * not incidental ticking, that lights the positive case.</p>
 *
 * <p>The network solve lives in a {@code WorldTickEvent} handler, which a command (running inside a
 * server tick) cannot advance; the test drives it deterministically via {@code /stellurgytest shield tick}.</p>
 */
public class ShieldTwoBlockFloorTest extends AbstractSharedServerTest {

    private static final int DIM = 0;
    private static final int Y = FixtureSite.OPEN_AIR_Y;
    private static final int CHARGE_ITERATIONS = 60;
    private static final int FE_PER_ITERATION = 4000;

    @Test
    public void twoBlockShieldPowersWithoutCable() throws Exception {
        // Generator at G, emitter directly adjacent along +X. No cable, console or accumulator.
        int gx = 900, gz = 760;
        int ex = gx + 1, ez = gz;
        place("affs:shield_generator", gx, gz);
        place("affs:field_generator", ex, ez);

        chargeAndSolve(gx, gz);

        ShieldTile emitter = ShieldTile.at(cmd -> exec(cmd), DIM, ex, Y, ez);
        assertTrue("adjacent generator+emitter (no cable) failed to power — the cable-less edge did not "
                        + "carry shield energy:\n" + emitter.raw(),
                emitter.powered());
    }

    @Test
    public void nonAdjacentPairNeverPowers() throws Exception {
        // Same two blocks, but a one-block gap and no cable: two disconnected components.
        int gx = 920, gz = 760;
        int ex = gx + 2, ez = gz; // gap at gx+1
        place("affs:shield_generator", gx, gz);
        place("affs:field_generator", ex, ez);

        chargeAndSolve(gx, gz);

        ShieldTile emitter = ShieldTile.at(cmd -> exec(cmd), DIM, ex, Y, ez);
        assertTrue("disconnected emitter (one-block gap, no cable) powered anyway — a spurious edge is "
                        + "carrying energy across the gap:\n" + emitter.raw(),
                !emitter.powered());
    }

    /**
     * The emitters a world answers for are the ones loaded IN it: an emitter loaded in another
     * dimension is not among them, and an emitter whose chunk unloaded has left the answer.
     *
     * <p>"Which emitters are loaded here" is what every shell query, strike, explosion and sync
     * snapshot reads ({@code TileEntityFieldGenerator.loadedIn}), and its owner is the server's
     * network registry: a node joins on load and leaves on chunk unload, break or world unload. One
     * emitter in the overworld and one in the nether, two blocks apart in X, and each world's answer is
     * read for both positions — so an answer that is not filtered by world names the other world's
     * emitter, and one that answers for the wrong world names the wrong one. The chunk cycle unloads
     * the overworld emitter's chunk and loads it back, which builds a NEW tile at the same place: an
     * answer that kept the unloaded one names that position twice. Read through the
     * {@code shield emitters} probe, which lists {@code loadedIn} for the dimension asked.</p>
     *
     * <p>Silent about a server STOP: the registry belongs to one server session and goes with it,
     * which no single-server tier can watch.</p>
     *
     * <p>One inversion per verdict, 2026-10-03:</p>
     *
     * <p>red-witnessed: with {@code SubsystemNetworkRegistry#nodesIn} at
     * {@code if (type.isInstance(node) && node.getNodeWorld() == world)} dropping the world conjunct,
     * this fails at "the overworld does not answer for exactly its own emitter
     * expected:[4020,151,4020] but was:[4020,151,4020, 4022,151,4020]".</p>
     *
     * <p>red-witnessed: with {@code TileEntityFieldGenerator#loadedIn} at
     * {@code nodesIn(ShieldNetworkManager.DOMAIN, world,} asking for the overworld whatever world it is
     * given, this fails at "the nether does not answer for exactly its own emitter
     * expected:[4022,151,4020] but was:[4020,151,4020]".</p>
     *
     * <p>red-witnessed: with {@code TileEntityFieldGenerator#onChunkUnload} at
     * {@code SubsystemNetworkManager.of(world).unregister(this);} removed, this fails at "after its chunk
     * unloaded and loaded again ... the unloaded tile was never released expected:[4020,151,4020] but
     * was:[4020,151,4020, 4020,151,4020]".</p>
     */
    @Test
    public void aWorldAnswersForTheEmittersLoadedInItAndNoOthers() throws Exception {
        FixtureSite here = site();
        here.requireClear(this::exec, 0, 1, "the emitter's block");
        int y = here.y + 1, z = here.z;
        int overworldX = here.x, netherX = here.x + 2;
        String overworldEmitter = overworldX + "," + y + "," + z, netherEmitter = netherX + "," + y + "," + z;
        int cx = overworldX >> 4, cz = z >> 4;
        // The nether is held loaded for the whole scenario, so its answer below is about a loaded world
        // with an emitter in it rather than about a world that has gone away.
        Reply held = Reply.of(exec("stellurgytest chunk forceload " + NETHER + " " + (netherX >> 4) + " " + cz));
        requireArranged("the nether chunk could not be held loaded: " + held, held.ok());
        placeAt(DIM, "affs:field_generator", overworldX, y, z);
        placeAt(NETHER, "affs:field_generator", netherX, y, z);

        assertEquals("the overworld does not answer for exactly its own emitter",
                Collections.singletonList(overworldEmitter),
                emittersAmong(DIM, overworldEmitter, netherEmitter));
        assertEquals("the nether does not answer for exactly its own emitter",
                Collections.singletonList(netherEmitter),
                emittersAmong(NETHER, overworldEmitter, netherEmitter));

        Events events = new Events(this::exec,
                ticks -> GameTicks.advance(client(), GameTicks.server(), ticks), evictionReports());
        long cycled = events.markInstrumented();
        Reply cycle = Reply.of(exec("stellurgytest chunk cycle " + DIM + " " + cx + " " + cz));
        requireArranged("the emitter's chunk did not unload and come back as a new chunk, so there is no"
                        + " released tile to look for: " + cycle,
                cycle.bool("dropped") && cycle.bool("reloaded") && !cycle.bool("sameInstance"));
        // The unloaded tile leaves the registry on the world's next tile pass, and the network that
        // tile pass dirties is rebuilt at the end of that same tick — the record of the rebuild is the
        // record that the leaving has happened.
        events.awaitRecordWithFields(cycled, "subsystem_network_rebuilt",
                "the shield network was never rebuilt after the emitter's chunk unloaded", REBUILD_TICKS,
                "domain", ShieldNetworkManager.DOMAIN.getName(), "dim", String.valueOf(DIM));
        assertEquals("after its chunk unloaded and loaded again, the overworld does not answer for exactly"
                        + " the one emitter standing there — the unloaded tile was never released",
                Collections.singletonList(overworldEmitter),
                emittersAmong(DIM, overworldEmitter, netherEmitter));
    }

    /** The nether: a second world that every server loads, for a question about "this world only". */
    private static final int NETHER = -1;

    /**
     * The rebuild's deadline, from production: the unloaded tile leaves the registry in the next world
     * tick's tile pass ({@code World#updateEntities} runs {@code onChunkUnload} for every tile its chunk
     * unload queued) and that tick's END phase rebuilds the dirtied network
     * ({@code SubsystemNetworkEvents#onWorldTick}). So the record exists one world tick after the cycle;
     * the wait reads, advances its one step of 5 ticks ({@code Events#awaitMatching}), and reads again.
     */
    private static final int REBUILD_TICKS = 5;

    /**
     * The emitters the {@code shield emitters} probe lists for {@code dim} that stand at one of
     * {@code positions} ({@code "x,y,z"}), in the probe's order, once per listing — so a position
     * listed twice appears twice.
     */
    private List<String> emittersAmong(int dim, String... positions) throws Exception {
        Reply listed = Reply.of("stellurgytest shield emitters " + dim,
                exec("stellurgytest shield emitters " + dim));
        assertEquals("the emitters probe answered for another dimension: " + listed,
                dim, listed.integer("dim"));
        List<String> wanted = Arrays.asList(positions);
        List<String> found = new ArrayList<>();
        for (String one : listed.objectArray("emitters")) {
            Reply emitter = Reply.of("emitter", one);
            String at = emitter.integer("posX") + "," + emitter.integer("posY") + "," + emitter.integer("posZ");
            if (wanted.contains(at)) {
                found.add(at);
            }
        }
        System.out.println("FIXTURE emitters-per-world: dim " + dim + " lists " + found + " of " + wanted);
        return found;
    }

    private void placeAt(int dim, String block, int x, int y, int z) throws Exception {
        Reply placed = Reply.of(exec("stellurgytest place " + dim + " " + x + " " + y + " " + z + " " + block));
        assertTrue("failed to place " + block + " at " + x + "," + y + "," + z + " in dim " + dim + ": "
                + placed, placed.bool("placed"));
    }

    /** Feed the generator FE and run one network solve per iteration. */
    private void chargeAndSolve(int gx, int gz) throws Exception {
        for (int i = 0; i < CHARGE_ITERATIONS; i++) {
            exec("stellurgytest energy inject " + DIM + " " + gx + " " + Y + " " + gz + " " + FE_PER_ITERATION);
            exec("stellurgytest tile force-tick " + DIM + " " + gx + " " + Y + " " + gz + " 1");
            exec("stellurgytest shield tick " + DIM);
        }
    }

    private void place(String block, int x, int z) throws Exception {
        String resp = exec("stellurgytest place " + DIM + " " + x + " " + Y + " " + z + " " + block);
        assertTrue("failed to place " + block + " at " + x + "," + Y + "," + z + ": " + resp,
                Reply.of(resp).bool("placed"));
    }

    private static String join(List<String> resp) {
        return String.join("\n", resp);
    }
}
