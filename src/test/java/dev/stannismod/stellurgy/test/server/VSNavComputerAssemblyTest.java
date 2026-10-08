package dev.stannismod.stellurgy.test.server;


import org.junit.Test;

import dev.stannismod.stellurgy.test.ArrangementFailure;
import dev.stannismod.stellurgy.test.Events;
import dev.stannismod.stellurgy.test.GameTicks;
import dev.stannismod.stellurgy.test.NavStatus;
import dev.stannismod.stellurgy.test.Reply;
import dev.stannismod.stellurgy.test.RocketFixture;
import dev.stannismod.stellurgy.test.ShipIdentity;
import dev.stannismod.stellurgy.test.FixtureSite;

import static org.junit.Assert.assertTrue;
import static dev.stannismod.stellurgy.test.ArrangementFailure.requireArranged;

/**
 * The assembler links a built navigation computer to the ship it is part of.
 *
 * <p>The link is what makes the computer THIS ship's — the jump gate finds a ship's navigation through
 * its flight computer, so a computer welded into the hull but never linked would leave the finished
 * ship unable to jump for no reason the player can see.</p>
 *
 * <p>Needs the physics mod: the link is set on the tier-2 assembly path, which only runs when a real
 * ship is being made.</p>
 */
public class VSNavComputerAssemblyTest extends AbstractSharedServerTest {

    // IN THE OPEN-AIR BAND SINCE 2026-09-30, and the reason it was not before is gone.
    //
    // This class stood inside a hill at y=80 because lifting it turned it red: at y=150 the craft
    // became a real ship, its blocks left the world, and the link was being read off a WORLD
    // position — so only a build that did NOT become a ship could pass. The link is now read on the
    // ship, found by the name its assembler minted, which is where it lives.
    //
    // And the hill had stopped being an experiment anyway. Measured 2026-09-30 at (7200,80,7200):
    // `rocket assemble` answered status SUCCESS with a shipId, yet no ship was queued, no hull
    // resolves that name, the flight computer still stands at its UN-lifted y=84 and dirt at y=85.
    // A second press on the same build then lifted it (the computer at y=85) and still no hull
    // resolved the name. So the first press had only WARNED — a tier-2 build under thrust-to-weight
    // 1 is warned about once and assembled on the next press, and the pad's scan box there is full of
    // terrain, which that gate weighs — and the assemble reply does not say it warned.
    /** A constant: every field of a {@code FixtureSite} is final. */
    private static final FixtureSite SITE = FixtureSite.openAir(0, 7200, 7200);

    /**
     * The assembler links the navigation computer it found in the build, read on the ship it built.
     *
     * <p>red-witnessed: {@code TileRocketAssemblingMachine#assembleRocket} at {@code ((TileNavigationComputer) navTe).linkToFlightComputer(shipAnchor);} (the navigation computer's
     * {@code linkToFlightComputer}) skipped fails "the assembler must link the navigation computer it
     * found in the build" with {@code "linked":false}, 2026-09-30.</p>
     */
    @Test
    public void aBuiltNavigationComputerIsLinkedToItsShipByTheAssembler() throws Exception {

        int[] bp = RocketFixture.placeAt(SITE, this::exec, "with-nav-computer", 2, 10,
                "the craft whose navigation computer is linked stands in this volume");

        // The fixture builds its craft at (siteX+3, siteY+1, siteZ+3); the flight computer sits one
        // west and three up from that origin, and the navigation computer one further along Z.
        int navX = SITE.x + 3 - 1, navY = SITE.y + 1 + 3, navZ = SITE.z + 3 + 1;
        String before = exec("stellurgytest nav status 0 " + navX + " " + navY + " " + navZ);
        requireArranged("the fixture must actually contain a navigation computer: " + before,
                Reply.of(before).ok());
        requireArranged("control: a freshly built computer is NOT yet linked - without this"
                        + " the test could not tell assembly apart from doing nothing: " + before,
                (!Reply.of(before).bool("linked")));

        String asm = exec("stellurgytest rocket assemble 0 " + bp[0] + " " + bp[1] + " " + bp[2]);
        assertTrue("with the physics mod an AFC-bearing build must become a ship, not a rocket: " + asm,
                (Reply.of(asm).integer("rocketCount") == 0));

        // THE LINK IS READ ON THE SHIP, found by the name its assembler minted. The craft's blocks
        // leave the world for the ship's subspace yard, so the computer is no longer at any world
        // position; the ship's own flight computer is resolved by identity and the navigation computer
        // stands where the fixture put it relative to that one — one block along +Z — which the rigid
        // relocation preserves.
        String physicsId = ArrangementFailure.arranged(() -> ShipIdentity.awaitPhysicsIdOf(this::exec,
                events, 0, ShipIdentity.nameFromAssembly(asm), SHIP_LINK_TICKS));
        String model = exec("stellurgytest vs flight-model-by-id 0 " + physicsId);
        requireArranged("the ship this build became must resolve its own flight computer, which is"
                + " where its navigation computer is found from: " + model, Reply.of(model).bool("found"));
        Reply afc = Reply.of(model);
        NavStatus after = NavStatus.at(this::exec, 0, afc.integer("afcX"), afc.integer("afcY"),
                afc.integer("afcZ") + 1);
        assertTrue("the assembler must link the navigation computer it found in the build: " + after.raw()
                        + " (pre-assembly state was " + before + ")",
                after.linked);
    }

    /**
     * Server ticks for this build's hull to become addressable by its name — a LINK budget: the
     * assembly queues the spawn and the ship manager serves it on its next tick and announces it.
     */
    private static final int SHIP_LINK_TICKS = 200;

    private final Events events =
            new Events(this::exec, ticks -> GameTicks.advance(client(), GameTicks.server(), ticks), evictionReports());
}
