package dev.stannismod.stellurgy.test.server;

import org.junit.Test;

import dev.stannismod.stellurgy.test.ArrangementFailure;
import dev.stannismod.stellurgy.test.DimList;
import dev.stannismod.stellurgy.test.Reply;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/**
 * The navigation READOUT: what the navigation computer phrases about a body, before it decides how much
 * of it the pilot has earned.
 *
 * <p>NEW-GROUP: the readout cluster ({@code NavBodyView}, and the redaction applied to it). The block's
 * other cluster — crystals and the jump gate — lives in {@code NavigationComputerTest}, whose file was
 * being changed by the jump-gate work when this was written; these methods start here and are a
 * candidate to move into it.</p>
 *
 * <p><b>What this does not see.</b> The computer's tile asking for the readout, the redaction it applies,
 * and the packet that carries the result to the client. The decision is read where it is made, through
 * {@code stellurgytest space nav-body-view}, which calls {@code NavBodyView#of} for the body that is a
 * given dimension and reports that dimension's own mass and gravity beside it.</p>
 */
public class NavigationReadoutServerTest extends AbstractSharedServerTest {

    /**
     * The MASS line of a body's readout carries the body's mass, and a body whose gravity differs from
     * its mass shows the mass.
     *
     * <p>Fails if {@code NavBodyView#of} stops deciding that {@code PlanetInfoField.MASS} is the body's
     * mass in Earth masses (it put the surface-gravity multiplier there).</p>
     *
     * <p>Every dimension the mod registers is asked; one no body of the universe stands for has no
     * readout and is passed over. The comparison is exact: the probe prints both numbers through the
     * same {@code Double.toString} the readout uses. The arrangement must hold a body whose mass and
     * gravity differ, or a readout that printed the gravity could not be told from one that printed the
     * mass — the shipped Moon is one (0.0123 Earth masses against a stated 0.166 g).</p>
     * <p>red-witnessed: with {@code NavBodyView#of} at {@code view.put(PlanetInfoField.MASS, Double.toString(props.getMass()));} writing {@code getGravitationalMultiplier()}, fails: "dim 2's readout must carry its mass under MASS, not its gravity (0.166)" (2026-10-07).</p>
     */
    @Test
    public void theMassLineCarriesTheBodysMassNotItsGravity() throws Exception {
        int described = 0;
        int massDiffersFromGravity = 0;
        for (int dim : DimList.from(this::exec).registered()) {
            Reply view = ask("stellurgytest space nav-body-view " + dim);
            if (!view.ok()) {
                continue;
            }
            double mass = view.number("mass");
            if (!(mass > 0d)) {
                continue;
            }
            described++;
            double gravity = view.number("gravity");
            if (Double.compare(mass, gravity) != 0) {
                massDiffersFromGravity++;
            }
            assertTrue("dim " + dim + " states a mass of " + mass + " and its readout has no MASS line: "
                    + view, view.bool("viewHasMass"));
            assertEquals("dim " + dim + "'s readout must carry its mass under MASS, not its gravity ("
                    + gravity + "): " + view, mass, Double.parseDouble(view.text("viewMass")), 0d);
        }
        ArrangementFailure.requireArranged("the server must hold a body the readout can describe with "
                + "a stated mass", described > 0);
        ArrangementFailure.requireArranged("the server must hold a body whose mass and gravity differ, "
                + "or the MASS line cannot tell the two apart", massDiffersFromGravity > 0);
    }
}
