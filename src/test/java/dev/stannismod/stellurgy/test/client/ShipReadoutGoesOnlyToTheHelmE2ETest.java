package dev.stannismod.stellurgy.test.client;

import java.util.List;

import org.junit.FixMethodOrder;
import org.junit.Test;
import org.junit.runners.MethodSorters;

import dev.stannismod.stellurgy.test.ArrangementFailure;
import dev.stannismod.stellurgy.test.Events;
import dev.stannismod.stellurgy.test.FixtureSite;
import dev.stannismod.stellurgy.test.Reply;

import static dev.stannismod.stellurgy.test.ArrangementFailure.requireArranged;
import static org.junit.Assert.assertEquals;

/**
 * A ship's readout goes to the pilot at its helm, and to nobody else aboard.
 *
 * <p>The flight computer sends its readout to exactly two kinds of player — the occupant of the
 * ship's pilot seat, and anyone with its console open — and nobody else is sent a byte. Who RECEIVES
 * is the client's fact, so this is observed on the client: a test-only recorder at the readout
 * packet's own client handler writes one record per readout that arrives, naming the flight computer
 * it is for.</p>
 *
 * <p>One client, two roles in sequence, because the tier has one: the same player at the helm and
 * then standing on the deck. The order is the control's: the helm leg proves the recorder fires and
 * that this server does send readouts to someone on this ship, so the silence in the deck leg that
 * follows is a silence about HIM, not about an instrument or a ship that sends nothing. The two legs
 * last the same number of ticks.</p>
 *
 * <p>What this does NOT see: the console viewer (the other half of the audience), and what the pilot
 * is SHOWN — the HUD lines drawn from what arrived.</p>
 */
@FixMethodOrder(MethodSorters.NAME_ASCENDING)
public class ShipReadoutGoesOnlyToTheHelmE2ETest extends AbstractSharedVsClientTest {

    private static final int DIM = 0;

    /** How far past the launchpad's footprint the working volume reaches, in blocks. */
    private static final int HALO = 2;

    /** How far above the site the subject reaches: the hull, the seat, and a body standing on the deck. */
    private static final int HEIGHT = 12;

    /**
     * The deadline of a LINK on a record either side writes — a naming, a flight model, a mount, a
     * readout's arrival. Expiry means it never came.
     */
    private static final int LINK_BUDGET_TICKS = 200;

    /**
     * EXPERIMENT dose: the ticks each role is held for while its readouts are counted — the same for
     * both, so the helm's count is the rate the deck's silence is compared against. Measured
     * 2026-09-30: 5 and 4 readouts arrived at the helm in forty ticks, on two runs.
     */
    private static final int ROLE_DOSE_TICKS = 40;

    @Override
    protected String subsystem() {
        return "ship-readout";
    }

    /**
     * The pilot receives the readout; the same player, off the helm and standing on the deck, does
     * not.
     *
     * <p>Chain: the craft is built and named (server log), its flight computer announces its first
     * model (server log — the address the readouts will name); the player is seated in the pilot seat
     * and the client mounts (client log); a readout for this flight computer arrives (client log, the
     * LINK); over the next {@link #ROLE_DOSE_TICKS} more arrive (counted). He is dismounted, the client
     * applies it (client log), the server's deck capture says he stands aboard this craft, and over
     * the same number of ticks NONE arrives.</p>
     *
     * <p>Contract: this fails if production breaks the contract that a ship's readout is sent to its
     * pilot and to no passenger.</p>
     *
     * <p>red-witnessed: {@code TileAdvancedFlightComputer#isReadoutAudience} at {@code return seat != null && seat.getFlightComputer() == this;} (the pilot-seat occupant left out of the
     * audience) fails "seated at the helm, the pilot must receive his ship's readout" with no readout
     * inside 200 ticks, 2026-09-30</p>
     * <p>red-witnessed: {@code TileAdvancedFlightComputer#isReadoutAudience} at {@code return seat != null && seat.getFlightComputer() == this;} (every player in the world made the
     * audience) fails "standing on the deck, off the helm and with no console open, a passenger must
     * receive none of his ship's readouts" with 4 received against the pilot's 4, 2026-09-30</p>
     */
    @Test
    public void aShipsReadoutReachesItsPilotAndNotAPassenger() throws Exception {
        FixtureSite site = site();
        long serverMark = events().mark();
        site.makeRoom(this::exec, HALO, HEIGHT, "a decked craft with a pilot seat");
        Reply fixture = Reply.of(exec("stellurgytest fixture rocket " + site.dim + " " + site.x + " "
                + site.y + " " + site.z + " with-pilot-deck"));
        requireArranged("the decked craft must be laid: " + fixture,
                fixture.ok() && fixture.blockPos("builderPos") != null);
        int[] builder = fixture.blockPos("builderPos");
        Reply press = Reply.of(exec("stellurgytest rocket assemble " + site.dim + " " + builder[0] + " "
                + builder[1] + " " + builder[2]));
        requireArranged("the decked craft can hover and must be built on the first press: " + press,
                press.ok() && press.bool("shipCut"));
        String durable = press.text("shipId");
        // ARRANGEMENT links, typed as such: the naming and the first model are the premise that
        // gives this scenario its addresses, not the audience it is about.
        String named = ArrangementFailure.arranged(() -> events().awaitRecordWithFields(serverMark,
                "ship_lifecycle", "the craft must be named once it is assembled", LINK_BUDGET_TICKS,
                "durable", durable, "edge", "named"));
        String physicsId = Events.text(named, "ship");
        String model = ArrangementFailure.arranged(() -> events().awaitRecordWithFields(serverMark,
                "flight_model_changed", "the craft's flight computer must build its first flight model",
                LINK_BUDGET_TICKS, "ship", durable));
        String afcX = Events.text(model, "afcX");
        String afcY = Events.text(model, "afcY");
        String afcZ = Events.text(model, "afcZ");

        // THE HELM.
        Reply seat = Reply.of(exec("stellurgytest vs seat-mount " + DIM + " id " + physicsId));
        requireArranged("the craft's own pilot seat must be found: " + seat, seat.bool("seatFound"));
        long helmMark = clientEvents().mark();
        Reply mounted = Reply.of(exec("stellurgytest player mount-entity " + seat.integer("dummyId")));
        requireArranged("the player must be seated at the helm: " + mounted,
                mounted.ok() && mounted.bool("mounted"));
        // The client seating him is the helm leg's premise, not its subject: an arrangement.
        ArrangementFailure.arranged(() -> awaitClientMount(helmMark, "the client must seat him at the helm",
                LINK_BUDGET_TICKS, ""));
        clientEvents().awaitMatching(helmMark, "client_ship_readout_received",
                reply -> Events.anyRecordHasAll(reply, "afcX", afcX, "afcY", afcY, "afcZ", afcZ),
                "for this craft's flight computer at " + afcX + "," + afcY + "," + afcZ,
                "seated at the helm, the pilot must receive his ship's readout", LINK_BUDGET_TICKS);
        long helmWindow = clientEvents().mark();
        bot().waitTicks(ROLE_DOSE_TICKS);
        int atTheHelm = readoutsFor(clientEvents().since(helmWindow, "client_ship_readout_received"),
                afcX, afcY, afcZ);
        requireArranged("held at the helm for " + ROLE_DOSE_TICKS + " ticks the pilot must go on"
                + " receiving readouts, or the deck's silence below has no rate to be compared with",
                atTheHelm > 0);

        // THE DECK.
        long deckMark = clientEvents().mark();
        Reply off = Reply.of(exec("stellurgytest player dismount"));
        requireArranged("the player must come off the helm: " + off, off.ok());
        // Likewise the deck leg's premise: he is off the helm on the client.
        ArrangementFailure.arranged(() -> awaitClientDismount(deckMark, "the client must take him off the helm",
                LINK_BUDGET_TICKS));
        deckCaptureOfThisShip(physicsId, "off the helm, the player must be standing aboard this"
                + " craft — a passenger, not a bystander");
        long deckWindow = clientEvents().mark();
        bot().waitTicks(ROLE_DOSE_TICKS);
        String onDeck = clientEvents().since(deckWindow, "client_ship_readout_received");
        Events.assertInstrumentRan(onDeck, "client_ship_readout_received",
                "a passenger received no readout — the recorder must be one that fires, as it did"
                        + " at the helm");
        List<String> strays = Events.recordsWhereAll(onDeck, "afcX", afcX, "afcY", afcY, "afcZ", afcZ);
        System.out.println("[measured] readouts in " + ROLE_DOSE_TICKS + " ticks: at the helm "
                + atTheHelm + ", on the deck " + strays.size());
        assertEquals("standing on the deck, off the helm and with no console open, a passenger must"
                        + " receive none of his ship's readouts; the pilot received " + atTheHelm
                        + " in the same " + ROLE_DOSE_TICKS + " ticks. Received: " + strays,
                0, strays.size());
    }

    /** How many readouts in a {@code since} reply are for the flight computer at this address. */
    private static int readoutsFor(String reply, String x, String y, String z) {
        return Events.recordsWhereAll(reply, "afcX", x, "afcY", y, "afcZ", z).size();
    }
}
