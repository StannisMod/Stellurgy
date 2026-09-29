package zmaster587.advancedRocketry.test.unit;

import org.junit.Test;

import zmaster587.advancedRocketry.test.Reply;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/**
 * An ABSENT field and an EMPTY one are different answers, and every verb here says which it gave.
 *
 * <h2>The contract</h2>
 *
 * <p>{@code Reply} carries one rule: the bare verb REFUSES when the reply does not carry the field,
 * and the verb whose name says it defaults — {@code …Or} for a scalar, {@code …OrEmpty} for an
 * array — answers instead. That rule is what every probe read in the tier stands on, and it is
 * pinned here rather than in a comment.</p>
 *
 * <h2>Why it is written now</h2>
 *
 * <p>Measured 2026-09-20: 317 test files call {@code Reply.of(}, and the class had NO test of its
 * own in either harness-free tier. Two consequences were live at that moment. The five array verbs
 * answered an EMPTY array for a field the reply did not carry — indistinguishable, at any call
 * site, from an array the producer really sent empty — so a renamed field read as a finding about
 * the world: no station pads, no clouds in the sky, no dimension registered. And a comment in
 * {@code StationPads} justified a guard with the claim that {@code has} "answers false for an
 * array"; it does not, and nothing contradicted it because nothing asked.</p>
 *
 * <p>Unit tier on purpose: this is a property of a parser, and pinning it here means it cannot
 * regress behind a half-hour harness run.</p>
 */
public class ReplyContractTest {

    /** A reply of the shape the probe actually sends: scalars, an array, an empty array, a null. */
    private static final String REPLY =
            "{\"ok\":true,\"count\":2,\"name\":\"pad\",\"dims\":[9701,9702],\"empty\":[],"
                    + "\"pads\":[{\"x\":1},{\"x\":2}],\"ships\":[\"a\",\"b\"],"
                    + "\"corners\":[[1,2,3],[4,5,6]],\"fuel\":{\"RP\":{\"amount\":7}},"
                    + "\"nothing\":null}";

    private static Reply reply() {
        return Reply.of("artest probe", REPLY);
    }

    // ---- the array verbs REFUSE on absence ------------------------------------------------------

    @Test
    public void everyArrayVerbRefusesAFieldTheReplyDoesNotCarry() {
        refuses("intArray", () -> reply().intArray("absent"));
        refuses("textArray", () -> reply().textArray("absent"));
        refuses("objectArray", () -> reply().objectArray("absent"));
        refuses("blockPosArray", () -> reply().blockPosArray("absent"));
        refuses("objectValues", () -> reply().objectValues("absent"));
    }

    @Test
    public void aRefusalNamesTheCommandTheFieldAndTheReplyItRead() {
        // The refusal is caught OUTSIDE the assertions below: a `fail()` inside the catch's own try
        // is itself an AssertionError, so a verb that answered instead of refusing would be read as
        // the refusal under examination and the test would report on its own message.
        String said = String.valueOf(refusalOf("intArray", () -> reply().intArray("arDimensions")));
        assertTrue("the refusal must name the command that answered: " + said,
                said.contains("artest probe"));
        assertTrue("the refusal must name the field it looked for: " + said,
                said.contains("arDimensions"));
        assertTrue("the refusal must show the reply, which is what says whether the field was"
                + " renamed or the verb took another branch: " + said, said.contains("\"dims\""));
        assertTrue("and it must name the defaulting twin, so the caller can choose it on"
                + " purpose: " + said, said.contains("intArrayOrEmpty"));
    }

    /**
     * The other half of the rule, and the one that makes it a distinction rather than a refusal:
     * an array the producer really sent EMPTY is a reading, and the bare verb answers it.
     */
    @Test
    public void anArrayThatIsPresentAndEmptyIsAnAnswerRatherThanARefusal() {
        assertEquals(0, reply().intArray("empty").length);
        assertEquals(0, reply().textArray("empty").length);
        assertEquals(0, reply().objectArray("empty").length);
        assertEquals(0, reply().blockPosArray("empty").length);
    }

    // ---- the `…OrEmpty` twins default, and read the real contents when there are any ------------

    @Test
    public void theOrEmptyTwinAnswersEmptyForAnAbsentFieldAndTheContentsForAPresentOne() {
        assertEquals(0, reply().intArrayOrEmpty("absent").length);
        assertEquals(0, reply().textArrayOrEmpty("absent").length);
        assertEquals(0, reply().objectArrayOrEmpty("absent").length);
        assertEquals(0, reply().blockPosArrayOrEmpty("absent").length);
        assertEquals(0, reply().objectValuesOrEmpty("absent").length);

        assertArrayEquals(new int[]{9701, 9702}, reply().intArrayOrEmpty("dims"));
        assertArrayEquals(new String[]{"a", "b"}, reply().textArrayOrEmpty("ships"));
        assertEquals(2, reply().objectArrayOrEmpty("pads").length);
        assertEquals(2, reply().blockPosArrayOrEmpty("corners").length);
        assertEquals(1, reply().objectValuesOrEmpty("fuel").length);
    }

    @Test
    public void theBareVerbReadsTheSameValuesAsItsTwin() {
        assertArrayEquals(new int[]{9701, 9702}, reply().intArray("dims"));
        assertArrayEquals(new String[]{"a", "b"}, reply().textArray("ships"));
        assertArrayEquals(new int[]{4, 5, 6}, reply().blockPosArray("corners")[1]);
        assertEquals("{\"x\":2}", reply().objectArray("pads")[1]);
        assertEquals("{\"amount\":7}", reply().objectValues("fuel")[0]);
    }

    // ---- `has` and `arrayLength`: the two verbs a caller asks the absence question with ---------

    /**
     * {@code has} answers for ANY value, not only a primitive.
     *
     * <p>A comment in {@code StationPads} said the opposite — that it "answers false for an array,
     * so it can never answer this question" — and that reading, quoted rather than checked, is the
     * sort of thing that decides a design. It is false, and this is where it stops being arguable.
     * What {@code has} does NOT distinguish is an empty array from a full one, which is
     * {@link Reply#arrayLength}'s job below.</p>
     */
    @Test
    public void hasAnswersTrueForAnArrayAnObjectAndAnEmptyArray() {
        assertTrue("an array field is carried", reply().has("dims"));
        assertTrue("an EMPTY array is still a field the reply carries", reply().has("empty"));
        assertTrue("an object field is carried", reply().has("fuel"));
        assertTrue("a primitive is carried", reply().has("count"));
        assertFalse("a JSON null is not a value the reply carries", reply().has("nothing"));
        assertFalse("and an absent field is not carried", reply().has("absent"));
    }

    @Test
    public void arrayLengthTellsAnEmptyArrayFromAnAbsentOne() {
        assertEquals("two elements", 2, reply().arrayLength("dims"));
        assertEquals("the producer sent an empty list — a reading about the world",
                0, reply().arrayLength("empty"));
        assertEquals("the reply carries no such array at all — a reading about the reply",
                -1, reply().arrayLength("absent"));
    }

    // ---- the scalar half of the same rule -------------------------------------------------------

    @Test
    public void aScalarVerbRefusesOnAbsenceAndItsOrTwinDefaults() {
        refuses("text", () -> reply().text("absent"));
        refuses("integer", () -> reply().integer("absent"));
        refuses("number", () -> reply().number("absent"));
        refuses("bool", () -> reply().bool("absent"));

        assertEquals("fallback", reply().textOr("absent", "fallback"));
        assertEquals(-1, reply().integerOr("absent", -1));
        assertEquals(2, reply().integerOr("count", -1));
        assertTrue(reply().boolOr("absent", true));
        assertEquals("pad", reply().text("name"));
    }

    /**
     * A field that lives inside a MEMBER is refused by NAME rather than reported absent — asking
     * the reply for {@code amount} when it belongs to a fuel entry is a question aimed one level
     * too high, and only the reader can see that.
     */
    @Test
    public void aFieldThatOnlyExistsNestedIsRefusedWithWhereItActuallyLives() {
        String said = String.valueOf(refusalOf("integer", () -> reply().integer("amount")));
        assertTrue("the refusal must say where the field really sits: " + said,
                said.contains("fuel.RP.amount"));
    }

    private interface Read {
        void run();
    }

    private static void refuses(String verb, Read read) {
        refusalOf(verb, read);
    }

    /** The message {@code read} refused with, failing when it answered instead. */
    private static String refusalOf(String verb, Read read) {
        AssertionError refused = null;
        try {
            read.run();
        } catch (AssertionError caught) {
            refused = caught;
        }
        if (refused == null) {
            fail(verb + " answered a field the reply does not carry instead of refusing — an absent"
                    + " field and an empty one are opposite findings");
        }
        return refused.getMessage();
    }
}
