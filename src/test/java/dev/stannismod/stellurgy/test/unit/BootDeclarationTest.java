package dev.stannismod.stellurgy.test.unit;

import com.github.stannismod.forge.testing.junit.AbstractMechanicsTest;
import com.github.stannismod.forge.testing.junit.ClassScope;
import com.github.stannismod.forge.testing.junit.ClassScopeRunner;
import com.github.stannismod.forge.testing.junit.TestClassScope;
import dev.stannismod.stellurgy.test.contract.Pins;
import dev.stannismod.stellurgy.test.contract.RedWitness;
import dev.stannismod.stellurgy.test.contract.TestBindingsCheck;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/**
 * Which mechanics class owes a reason for the boot it takes. A class of an own-boot base boots a pair for
 * itself and names why on the closed list; one that shares a single pair across its scenarios through a
 * class scope of its own is a group, and declares nothing. The fixtures are abstract: they are shapes for
 * the rule to judge, never tests to run.
 */
public class BootDeclarationTest {

    /** A pair held for a class run. Never opened here: the rule reads the declaration, not the scope. */
    public abstract static class SharedPair extends TestClassScope {
    }

    /** Takes a boot of its own and names no reason. */
    public abstract static class OwnBootWithoutReason extends AbstractMechanicsTest {
    }

    /** Shares one pair across its scenarios through its own class scope. */
    @RunWith(ClassScopeRunner.class)
    @ClassScope(SharedPair.class)
    public abstract static class SharesItsOwnPair extends AbstractMechanicsTest {
    }

    @Test
    @Pins("MECH-TST-09")
    @RedWitness(verdict = "a class-scoped group declares nothing", member = "TestBindingsCheck#checkBootDeclaration",
            at = "ownBoot &= !classScopedGroup;", replacedBy = "",
            fails = "a class sharing its own pair across its scenarios was owed a reason", on = "2026-10-16")
    @RedWitness(verdict = "an own-boot class owes its reason", member = "TestBindingsCheck#checkBootDeclaration",
            at = "withoutReason.add(k.getName());", replacedBy = "",
            fails = "a class taking a boot of its own and naming no reason was let through", on = "2026-10-16")
    public void aClassThatSharesItsOwnPairIsAGroupAndOneThatDoesNotOwesAReason() {
        List<String> group = TestBindingsCheck.bootDeclarationVerdict(SharesItsOwnPair.class);
        assertTrue("a class sharing its own pair across its scenarios was owed a reason: " + group, group.isEmpty());

        List<String> own = TestBindingsCheck.bootDeclarationVerdict(OwnBootWithoutReason.class);
        assertEquals("a class taking a boot of its own and naming no reason was let through: " + own,
                1, own.size());
        assertTrue("a class taking a boot of its own and naming no reason was let through: " + own,
                own.contains("without a reason"));
    }
}
