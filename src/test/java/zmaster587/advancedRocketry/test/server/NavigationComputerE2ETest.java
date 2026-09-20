package zmaster587.advancedRocketry.test.server;

import zmaster587.advancedRocketry.test.Reply;
import org.junit.Test;

import zmaster587.advancedRocketry.test.NavStatus;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/**
 * End-to-end contracts of the navigation computer in a real world: what a pilot can do with the block
 * he built, and what the jump gate tells him when he cannot jump.
 *
 * <p>Everything here drives production code through {@code /artest nav} — the probe only places the
 * block and stocks the crystals; the copy, the sync and the gate verdict are production's own.</p>
 */
public class NavigationComputerE2ETest extends AbstractSharedServerTest {

    /** Well away from the other fixtures' build sites, so nothing else in the shared world overlaps. */
    private static final String A = "0 2400 80 2400";
    private static final String B = "0 2410 80 2400";
    /** The flight-computer position the gate is asked about; nothing is built there. */
    private static final String AFC = "2400 82 2400";
    /**
     * A second flight computer that never gets a drive. It has to be a DIFFERENT site: the tests
     * share one world, so a drive another test stood up at {@link #AFC} is still standing there when
     * this one runs, and "no drive aboard" would quietly become "somebody else's drive aboard".
     */
    private static final String AFC_NO_DRIVE = "2440 82 2440";

    /** The gate's own refusal, as the lang key production hands the player. */
    private static final String MESSAGE = "message";

    @Test
    public void copyingACrystalAddsToTheShipWithoutTakingFromTheSource() throws Exception {
        placeComputer(A);
        stock(A, 0, 3, 100);
        stock(A, 1, 2, 200);

        String copied = exec("artest nav copy " + A);

        assertTrue("the copy must report the three new addresses: " + copied,
                (Reply.of(copied).integer("changed") == 3));
        assertTrue("the ship's crystal must hold everything it had plus everything copied: " + copied,
                (Reply.of(copied).integer("ship") == 5));
        assertTrue("a copy must never take an address off the source crystal: " + copied,
                (Reply.of(copied).integer("source") == 3));
    }

    @Test
    public void erasingTheSourceLeavesTheShipCrystalAlone() throws Exception {
        placeComputer(A);
        stock(A, 0, 3, 300);
        stock(A, 1, 2, 400);
        exec("artest nav copy " + A);

        String erased = exec("artest nav erase " + A);
        NavStatus status = NavStatus.of(exec("artest nav status " + A));

        assertTrue("the source must be blank after an erase: " + erased,
                (Reply.of(erased).integer("source") == 0));
        assertTrue("erasing the source must not touch what the ship knows: " + status.raw(),
                status.shipCrystals == 5);
    }

    @Test
    public void theJumpGateRefusesAShipWithNoNavigationComputer() throws Exception {
        String verdict = exec("artest nav gate 0 500 82 500");

        assertTrue("a ship with no navigation computer cannot jump: " + verdict,
                (!Reply.of(verdict).bool("allowed")));
        assertTrue("and must be told exactly that: " + verdict,
                "msg.jumpgate.nonavcomputer".equals(Reply.of(verdict).text(MESSAGE)));
    }

    @Test
    public void aLinkedComputerWithoutATargetStillRefuses() throws Exception {
        placeComputer(A);
        exec("artest nav link " + A + " " + AFC);
        exec("artest nav cleartarget " + A);

        String verdict = exec("artest nav gate 0 " + AFC);

        assertTrue("having a computer is not having a destination: " + verdict,
                (!Reply.of(verdict).bool("allowed")));
        assertEquals("msg.jumpgate.notarget", Reply.of(verdict).text(MESSAGE));
        assertTrue("the computer itself must have been found: " + verdict,
                Reply.of(verdict).bool("navComputer"));
    }

    @Test
    public void aComputerAndATargetAreNotEnoughWithoutADrive() throws Exception {
        // Knowing where you want to go is not a way of getting there. Before the hyperdrive family
        // existed this ship would have cleared the gate and then had nothing to jump WITH.
        placeComputer(A);
        exec("artest nav link " + A + " " + AFC_NO_DRIVE);
        exec("artest nav target " + A + " 7 0 0");

        String verdict = exec("artest nav gate 0 " + AFC_NO_DRIVE);

        assertTrue("a ship with no field generator cannot jump, however well it is aimed: " + verdict,
                (!Reply.of(verdict).bool("allowed")));
        assertEquals("msg.jumpgate.nodrive", Reply.of(verdict).text(MESSAGE));
    }

    @Test
    public void aComputerATargetAndAChargedDriveClearTheGate() throws Exception {
        placeComputer(A);
        exec("artest nav link " + A + " " + AFC);
        exec("artest nav target " + A + " 7 0 0");
        exec("artest drive build 0 " + AFC);
        exec("artest drive charge 0 " + AFC + " full");

        String verdict = exec("artest nav gate 0 " + AFC);

        assertTrue("computer aboard, position known, target set, a drive and the burst to open the "
                + "window - nothing refuses this: " + verdict, Reply.of(verdict).bool("allowed"));
        assertTrue("and nothing merely advises either: " + verdict,
                (!Reply.of(verdict).bool("confirm")));
    }

    @Test
    public void anEmptyCapacitorRefusesTheJumpUntilItHasCharged() throws Exception {
        placeComputer(A);
        exec("artest nav link " + A + " " + AFC);
        exec("artest nav target " + A + " 7 0 0");
        exec("artest drive build 0 " + AFC);

        String flat = exec("artest drive charge 0 " + AFC + " empty");
        String refused = exec("artest nav gate 0 " + AFC);
        exec("artest drive charge 0 " + AFC + " full");
        String allowed = exec("artest nav gate 0 " + AFC);

        assertTrue("precondition: the bank really is empty: " + flat, (Reply.of(flat).integer("charge") == 0));
        assertTrue("without the burst the window does not open at all: " + refused,
                (!Reply.of(refused).bool("allowed")));
        assertEquals("msg.jumpgate.capacitorlow", Reply.of(refused).text(MESSAGE));
        assertTrue("and the same ship, charged, may go: " + allowed,
                Reply.of(allowed).bool("allowed"));
    }

    @Test
    public void aHandTypedCoordinateIsAcceptedAsATarget() throws Exception {
        placeComputer(A);
        exec("artest nav link " + A + " " + AFC);

        exec("artest drive build 0 " + AFC);
        exec("artest drive charge 0 " + AFC + " full");

        // Nothing has ever surveyed sector 4242: aiming there is legal, and reckless, on purpose.
        String aimed = exec("artest nav target " + A + " 4242 0 0");
        String verdict = exec("artest nav gate 0 " + AFC);

        assertTrue("an unsurveyed coordinate is still a coordinate: " + aimed,
                "4242_0_0".equals(Reply.of(aimed).text("target")));
        assertTrue("and the gate lets the pilot take the risk: " + verdict,
                Reply.of(verdict).bool("allowed"));
    }

    @Test
    public void syncingOnAChannelLeavesBothComputersHoldingTheUnion() throws Exception {
        placeComputer(A);
        placeComputer(B);
        stock(A, 1, 3, 600);
        stock(B, 1, 2, 700);

        String synced = exec("artest nav sync " + A + " 42");
        NavStatus peerBefore = NavStatus.of(exec("artest nav status " + B));
        exec("artest nav sync " + B + " 42");
        NavStatus peer = NavStatus.of(exec("artest nav status " + B));
        NavStatus self = NavStatus.of(exec("artest nav status " + A));

        assertTrue("the sync must report moving addresses: " + synced,
                Reply.of(synced).has("changed"));
        // One assert per computer, because `self` and `peer` are two separate status fetches taken
        // at two different moments (peer first). Conjoined, "both hold five" was never a statement
        // about any one instant, and a red named neither computer.
        assertEquals("the computer that offered the channel must hold the UNION — its own three"
                        + " addresses plus the two the peer brought; A=" + self.raw(),
                5, self.shipCrystals);
        assertEquals("...and so must the computer that synced onto the same channel — a sync that"
                        + " only moves addresses one way is a copy, not a sync; B=" + peer.raw()
                        + " (B before its own sync: " + peerBefore.raw() + ")",
                5, peer.shipCrystals);
    }

    @Test
    public void aComputerOnNoChannelSyncsWithNobody() throws Exception {
        placeComputer(A);
        placeComputer(B);
        stock(A, 1, 3, 800);
        stock(B, 1, 2, 900);

        String synced = exec("artest nav sync " + A + " 0");
        NavStatus self = NavStatus.of(exec("artest nav status " + A));

        assertTrue("channel 0 must move nothing: " + synced, (Reply.of(synced).integer("changed") == 0));
        assertTrue("a computer nobody put on a channel must not pool its knowledge: " + self.raw(),
                self.shipCrystals == 3);
    }

    private void placeComputer(String at) throws Exception {
        String placed = exec("artest nav place " + at);
        assertTrue("the navigation computer must be placeable: " + placed, Reply.of(placed).ok());
        exec("artest nav sync " + at + " 0"); // a fresh block starts on no channel
        exec("artest nav cleartarget " + at);
    }

    private void stock(String at, int slot, int addresses, int firstSector) throws Exception {
        String stocked = exec("artest nav crystal " + at + " " + slot + " " + addresses
                + " " + firstSector + " 1");
        assertTrue("the probe must stock the crystal: " + stocked, Reply.of(stocked).ok());
    }

    private String exec(String cmd) throws Exception {
        return String.join("\n", client().execute(cmd));
    }
}
