package zmaster587.advancedRocketry.test.unit;

import java.util.UUID;

import org.junit.Test;

import net.minecraft.util.math.BlockPos;

import zmaster587.advancedRocketry.space.VSShipCrosser;

import static org.junit.Assert.assertEquals;

/**
 * A jump leg cuts the ship the jump NAMES, or it cuts nothing — on the way out and on the way back
 * alike.
 *
 * <p>Both legs park in, and cut out of, a world that holds every ship in flight at once, so "the
 * craft at this anchor" is a question with more than one answer by construction. And the anchor does
 * not even answer it by containment: the lookup behind it keeps the smallest distance over every
 * REGISTERED ship, with no distance bound and no claim test, so it reaches unloaded craft and the
 * blockless remnants a crossing deliberately leaves behind.</p>
 *
 * <h2>The rule, and the polarity flip in its history</h2>
 *
 * <p>The rule under test: a leg that names a real ship is resolved by its durable id or it is
 * REFUSED. There is no third answer. Three scenarios below previously pinned the opposite — an
 * unresolvable leg falling back to the anchor — on the argument that a check which cannot judge a
 * case must not block it. That argument is what kept a jump able to deliver a stranger, and it was
 * overruled: every crossing is anchored on a craft with a flight computer, which is what mints the
 * durable name, so "this jump names a ship and the world cannot find it" is a defect to surface
 * rather than a case to accommodate.</p>
 *
 * <p>The one surviving positional path is a leg that makes no identity claim at all — an id that is
 * not a uuid, which is a synthetic fixture and never a jump the game started.</p>
 *
 * <p><b>Read the failures accordingly.</b> A red here after a production change means the resolution
 * changed, not that a jump "stopped working"; a jump refusing to move a craft it cannot name is this
 * contract holding.</p>
 */
public class JumpCutsTheShipItNamesTest {

    private static final BlockPos ANCHOR = new BlockPos(0, 128, 0);
    private static final int DIM = 7;

    private static final UUID OURS = UUID.fromString("00000000-0000-0000-0000-0000000000A1");
    private static final UUID STRANGER = UUID.fromString("00000000-0000-0000-0000-0000000000B2");
    private static final UUID AT_ANCHOR = UUID.fromString("00000000-0000-0000-0000-0000000000C3");

    /** {@code REFUSED} is a value of the same type as an answer; recognise it the way production does. */
    private static final UUID REFUSED = UUID.fromString("00000000-0000-0000-0000-000000000000");

    private static UUID cut(UUID byDurableId, UUID byPosition, UUID afcNames) {
        return VSShipCrosser.identifyShipToCut("test", ANCHOR, OURS.toString(), DIM,
                byDurableId, byPosition, afcNames);
    }

    @Test
    public void theShipTheJumpNamesWinsOverWhateverTheAnchorReaches() {
        // The whole defect in one line: the anchor reaches a stranger, the jump's own id names our
        // hull, and the cut must take ours.
        assertEquals("the craft the jump's durable id names is the one cut",
                OURS, cut(OURS, STRANGER, null));
    }

    @Test
    public void anAnchorThatPositivelyNamesAnotherShipRefusesTheCut() {
        assertEquals("a computer at the anchor that calls itself another ship refuses the cut",
                REFUSED, cut(null, STRANGER, STRANGER));
    }

    @Test
    public void anAnchorThatAgreesWithUsStillCannotAimTheCut() {
        // Agreement is not resolution. `afcNames` is read by scanning the shipyard box the SAME
        // positional lookup produced, so on a wrong pick it is the STRANGER's computer agreeing with
        // whatever it was asked about. It can convict and it can never aim, so a leg whose index
        // lookup missed is refused even when the anchor's computer says our own name.
        assertEquals("an anchor that agrees is still an anchor, and an anchor cannot aim",
                REFUSED, cut(null, AT_ANCHOR, OURS));
    }

    @Test
    public void aNamedShipTheIndexCannotFindIsRefusedRatherThanApproximated() {
        // The case the old contract called "nothing establishable, fall back to the anchor". The
        // fallback is the defect: the leg names a real ship, and the only other candidate reaches by
        // distance with no bound.
        assertEquals("a jump that cannot find its own hull must not move a different one",
                REFUSED, cut(null, AT_ANCHOR, null));
    }

    @Test
    public void aSyntheticJumpIdIsRefusedTooRatherThanCrossingByPosition() {
        // A leg driven under a non-uuid key is a test probe moving a transit for a fixture that
        // assembled no ship. It has no hull of its own, so letting it cross "whatever the anchor
        // reaches" was not a weaker answer to a weaker question — it was the one case guaranteed to
        // cut a neighbour. Production's single caller passes a uuid.
        assertEquals("a leg with no hull of its own must not cut somebody else's",
                REFUSED, VSShipCrosser.identifyShipToCut("test", ANCHOR, "fixture-ship-1",
                        DIM, null, AT_ANCHOR, STRANGER));
    }

    @Test
    public void aNamedShipWithNothingAtTheAnchorIsAlsoRefusedRatherThanNull() {
        // Previously null, meaning "nothing to cut" — which the caller then passed to crossShip,
        // where a null uuid means "resolve by position". So the old "honest nothing" reached the
        // very fallback this contract removes, one layer down.
        assertEquals("no identity and no craft at the anchor is a refusal, not a null that becomes"
                + " a positional resolve one layer down", REFUSED, cut(null, null, null));
    }
}
