package dev.stannismod.stellurgy.test.server;

import dev.stannismod.stellurgy.test.Reply;
import org.junit.Test;

import static org.junit.Assert.assertTrue;

/**
 * Regression guard for the standalone-repair null-deref invariant (PR #23
 * review note #5).
 *
 * <p>{@code performFunction()} works the linked rocket's storage only inside its
 * {@code if (linkedRocket instanceof EntityRocket)} branch, so with no rocket
 * linked it must do nothing. (Until 2026-10-06 the branch also held the
 * station's standalone repair, which dereferenced
 * {@code ((EntityRocket) linkedRocket).storage}; that repair now runs from the
 * station's own tick behind its own {@code instanceof} check, and an unlinked
 * station that ticks here exercises it too.)</p>
 *
 * <p>This pins that invariant directly: driving {@code performFunction} on a
 * powered but UNLINKED service station must be a safe no-op and must not
 * throw. The {@code service-perform-function} probe wraps the call in
 * try/catch and reports {@code "performFunction threw"} on any
 * {@link RuntimeException}, so a regression (the {@code instanceof} guard
 * removed) surfaces here as a failed {@code "ok":true} assertion rather than a
 * silent NPE in production.</p>
 */
public class ServiceStationUnlinkedPerformFunctionTest extends AbstractSharedServerTest {

    // Isolated lane, clear of the other service-station fixtures.
    private static final int X = 16400;
    /** The open-air band. A hard-coded 70 until 2026-09-14; this station is placed and asked to
     *  perform a function with nothing linked to it, and never looks down. */
    private static final int Y = dev.stannismod.stellurgy.test.FixtureSite.OPEN_AIR_Y;
    private static final int Z = 15900;

    @Test
    public void performFunctionOnUnlinkedPoweredStationIsSafeNoOp() throws Exception {
        int cx = X >> 4, cz = Z >> 4;
        exec("stellurgytest chunk warmup 0 " + cx + " " + cz + " " + cx + " " + cz);
        exec("stellurgytest fill 0 " + X + " " + Y + " " + Z + " " + X + " " + (Y + 1) + " "
                + Z + " minecraft:air");

        String place = exec("stellurgytest place 0 " + X + " " + Y + " " + Z
                + " stellurgy:serviceStation");
        assertTrue("service station place failed: " + place,
                Reply.of(place).bool("placed"));

        // Power it (performFunction's getEquivalentPower gate) but DO NOT link a
        // rocket — linkedRocket stays null.
        exec("stellurgytest place 0 " + X + " " + (Y + 1) + " " + Z + " minecraft:redstone_block");

        // Sanity: truly unlinked, empty repair queue.
        String pre = exec("stellurgytest infra service-state 0 " + X + " " + Y + " " + Z);
        assertTrue("station must be unlinked: " + pre, (Reply.of(pre).integer("linkedRocketId") == -1));
        assertTrue("repair queue must be empty: " + pre, (Reply.of(pre).integer("partsToRepairCount") == 0));

        // The concern: performFunction must NOT reach ((EntityRocket) linkedRocket).storage
        // with a null linkedRocket.
        String pf = exec("stellurgytest infra service-perform-function 0 " + X + " " + Y + " " + Z);
        assertTrue("performFunction on an unlinked powered station must be a safe "
                + "no-op (no NPE/CCE reaching the rocket's storage): " + pf,
                Reply.of(pf).ok());

        // State still sane after the no-op.
        String post = exec("stellurgytest infra service-state 0 " + X + " " + Y + " " + Z);
        assertTrue("repair queue still empty after no-op performFunction: " + post,
                (Reply.of(post).integer("partsToRepairCount") == 0));
    }
}
