package dev.stannismod.stellurgy.test.unit;

import dev.stannismod.stellurgy.test.contract.RedWitness;
import dev.stannismod.stellurgy.test.contract.TestBindingsCheck;
import org.junit.Test;

import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * A red-witness record is exactly one of its two forms, and the build says which part a malformed one
 * lacks: a replayable record needs every part a tool must have to apply the break and compare the
 * failure, and a prose record carries nothing else.
 */
public class RedWitnessRecordFormTest {

    private static final String UNSET = RedWitness.NOT_GIVEN;

    @Test
    @RedWitness(verdict = "a complete replayable record", member = "TestBindingsCheck#recordProblem",
            at = "if (!DATE.matcher(on).matches()) {", replacedBy = "if (DATE.matcher(on).matches()) {",
            fails = "a complete replayable record was refused", on = "2026-10-08")
    @RedWitness(verdict = "a dropped fragment", member = "TestBindingsCheck#recordProblem",
            at = "if (RedWitness.NOT_GIVEN.equals(replacedBy)) {",
            replacedBy = "if (replacedBy.isEmpty() || RedWitness.NOT_GIVEN.equals(replacedBy)) {",
            fails = "a dropped fragment (replaced by nothing) was refused", on = "2026-10-08")
    @RedWitness(verdict = "a prose record", member = "TestBindingsCheck#recordProblem", at = "return structured ?",
            replacedBy = "return true ?", fails = "a prose record was refused", on = "2026-10-08")
    public void aCompleteReplayableRecordAndAProseRecordAreBothAccepted() {
        assertNull("a complete replayable record was refused",
                TestBindingsCheck.recordProblem("", "TileX#update", "if (a)", "if (!a)", "the gate held", "2026-10-08", ""));
        assertNull("a dropped fragment (replaced by nothing) was refused",
                TestBindingsCheck.recordProblem("", "TileX#update", "a();", "", "the gate held", "2026-10-08", ""));
        assertNull("a prose record was refused",
                TestBindingsCheck.recordProblem("", "", "", UNSET, "", "", "with {@code X#y} made true, fails \"q\""));
    }

    @Test
    @RedWitness(verdict = "no replacement", member = "TestBindingsCheck#recordProblem",
            at = "if (RedWitness.NOT_GIVEN.equals(replacedBy)) {", replacedBy = "if (false) {",
            fails = "a record with no replacement was accepted", on = "2026-10-08")
    @RedWitness(verdict = "no full date", member = "TestBindingsCheck#recordProblem",
            at = "if (!DATE.matcher(on).matches()) {", replacedBy = "if (false) {",
            fails = "a record with no full date was accepted", on = "2026-10-08")
    @RedWitness(verdict = "a line, not a member", member = "TestBindingsCheck#recordProblem",
            at = "if (!MEMBER.matcher(member).matches()) {", replacedBy = "if (false) {",
            fails = "a record naming a line, not a member, was accepted", on = "2026-10-08")
    @RedWitness(verdict = "both forms", member = "TestBindingsCheck#recordProblem", at = "return structured ?",
            replacedBy = "return false ?", fails = "a record in both forms was accepted", on = "2026-10-08")
    @RedWitness(verdict = "an empty record", member = "TestBindingsCheck#recordProblem", at = "if (!structured) {",
            replacedBy = "if (false) {", fails = "an empty record was accepted", on = "2026-10-08")
    public void aRecordInNeitherFormSaysWhatItLacks() {
        String noReplacement = TestBindingsCheck.recordProblem("", "TileX#update", "if (a)", UNSET, "held", "2026-10-08", "");
        assertTrue("a record with no replacement was accepted, or not told so: " + noReplacement,
                noReplacement != null && noReplacement.contains("replacedBy"));
        String badDate = TestBindingsCheck.recordProblem("", "TileX#update", "if (a)", "x", "held", "2026-10", "");
        assertTrue("a record with no full date was accepted, or not told so: " + badDate,
                badDate != null && badDate.contains("yyyy-mm-dd"));
        String noSymbol = TestBindingsCheck.recordProblem("", "TileX.java:12", "if (a)", "x", "held", "2026-10-08", "");
        assertTrue("a record naming a line, not a member, was accepted: " + noSymbol,
                noSymbol != null && noSymbol.contains("member"));
        String both = TestBindingsCheck.recordProblem("", "TileX#update", "", UNSET, "", "", "prose too");
        assertTrue("a record in both forms was accepted: " + both, both != null && both.contains("one form"));
        String empty = TestBindingsCheck.recordProblem("", "", "", UNSET, "", "", " ");
        assertTrue("an empty record was accepted: " + empty, empty != null && empty.contains("empty"));
    }
}
