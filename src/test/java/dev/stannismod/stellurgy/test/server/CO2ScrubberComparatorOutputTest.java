package dev.stannismod.stellurgy.test.server;

import dev.stannismod.stellurgy.test.Reply;
import org.junit.Test;


import dev.stannismod.stellurgy.test.FixtureSite;

import static org.junit.Assert.assertTrue;

/**
 * TileCO2Scrubber's comparator output
 * reflects the cartridge's remaining charge.
 *
 * <p>Production:
 * {@link dev.stannismod.stellurgy.tile.atmosphere.TileCO2Scrubber#getComparatorOverride}
 * returns {@code (32766 - stack.getItemDamage() + 2184) / 2185} when
 * slot 0 holds any (non-empty) cartridge, and 0 when empty.
 * Player-visible: a redstone comparator placed adjacent reports
 * remaining cartridge charge as a 0..15 level.</p>
 *
 * <p>Pinned (loose-bound, contract-not-formula):</p>
 * <ul>
 *   <li>Empty slot &rarr; comparator output = 0.</li>
 *   <li>Fresh cartridge (damage = 0) &rarr; comparator output &gt; 0
 *       (the player-visible "scrubber has fuel" signal).</li>
 * </ul>
 *
 * <p>NOT pinned: the exact 32766 / 2184 / 2185 formula.
 * The constants are tuning, not contract.</p>
 */
public class CO2ScrubberComparatorOutputTest extends AbstractSharedServerTest {

    private static final int PX = 6400;
    private static final int PY = FixtureSite.OPEN_AIR_Y;
    private static final int PZ = 6400;

    private static final String VALUE_PAT = "value";

    /** Pins INV-ATM-10 (an empty scrubber reads comparator 0 and a fresh cartridge reads above 0). */
    @Test
    public void emptyScrubberReportsZeroComparatorOutput() throws Exception {
        int x = PX, y = PY, z = PZ;
        ok("stellurgytest place 0 " + x + " " + y + " " + z
                + " stellurgy:oxygenScrubber");
        String resp = exec("stellurgytest infra comparator-override 0 "
                + x + " " + y + " " + z);
        assertTrue("comparator-override must succeed: " + resp,
                Reply.of(resp).ok());
        int value = extract(resp);
        assertTrue("empty CO2 scrubber must report comparator = 0; "
                        + "actual=" + value + " resp=" + resp,
                value == 0);
    }

    /** Pins INV-ATM-10 (an empty scrubber reads comparator 0 and a fresh cartridge reads above 0). */
    @Test
    public void freshCartridgeReportsNonZeroComparatorOutput() throws Exception {
        int x = PX + 30, y = PY, z = PZ;
        ok("stellurgytest place 0 " + x + " " + y + " " + z
                + " stellurgy:oxygenScrubber");
        // Drop a fresh (damage=0) cartridge into slot 0 — production
        // damage starts at 0; max is Short.MAX_VALUE-1.
        ok("stellurgytest hatch fill 0 " + x + " " + y + " " + z
                + " 0 stellurgy:carbonScrubberCartridge 1 0");
        String resp = exec("stellurgytest infra comparator-override 0 "
                + x + " " + y + " " + z);
        assertTrue("comparator-override must succeed: " + resp,
                Reply.of(resp).ok());
        int value = extract(resp);
        assertTrue("CO2 scrubber with fresh cartridge must report "
                        + "comparator > 0 (the player-visible 'has "
                        + "fuel' redstone signal); actual=" + value
                        + " resp=" + resp,
                value > 0);
    }

    private void ok(String cmd) throws Exception {
        String resp = exec(cmd);
        assertTrue("probe must succeed: cmd='" + cmd + "' resp=" + resp,
                Reply.of(resp).ok());
    }

    private static int extract(String src) {
        Reply mReply = Reply.of(src);
        assertTrue("value missing in: " + src, mReply.has(VALUE_PAT));
        return Integer.parseInt(mReply.text(VALUE_PAT));
    }
}
