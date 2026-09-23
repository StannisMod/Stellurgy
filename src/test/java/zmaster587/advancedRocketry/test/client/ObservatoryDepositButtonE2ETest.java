package zmaster587.advancedRocketry.test.client;

import org.junit.FixMethodOrder;
import org.junit.Test;
import org.junit.runners.MethodSorters;

import zmaster587.advancedRocketry.test.FixtureSite;
import zmaster587.advancedRocketry.test.RealizedBody;
import zmaster587.advancedRocketry.test.Reply;
import zmaster587.advancedRocketry.test.TelescopeReading;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static zmaster587.advancedRocketry.test.client.ClientGuiTestSupport.openGuiByRightClick;

/**
 * The one human act in the tier-1/tier-2 knowledge loop: a player standing at an observatory presses
 * <b>Deposit</b>, and the world he is standing on learns the addresses on the crystal in the machine.
 *
 * <p>The server tier already pins the path from the button's handler onwards. What it structurally
 * cannot pin is the CLICK: that the control exists on the survey tab, that it is enabled, and that
 * pressing it reaches the tile. That gap is a client e2e and not a playtest, so it lives here.</p>
 *
 * <p>The assertion is deliberately made on the SERVER's answer afterwards - `planet knowledge` asks
 * the production gate a rocket asks - rather than on anything the GUI says about itself. A button
 * that lights up and does nothing would satisfy a screen-scraping test and fail this one.</p>
 */
@FixMethodOrder(MethodSorters.NAME_ASCENDING)
public class ObservatoryDepositButtonE2ETest extends AbstractSharedClientE2ETest {

    /** The survey tab of the observatory GUI: data, asteroid, region-scan. */
    private static final int TAB_REGION_SCAN = 2;
    /** The Deposit control's own id on that tab. */
    private static final int BUTTON_DEPOSIT = 10;

    /** How long a GUI round trip may take: a deadline for a discrete record, never a settle. */
    private static final int GUI_LINK_BUDGET_TICKS = 200;

    private static final int X = 5200;
    private static final int Y = FixtureSite.OPEN_AIR_Y;
    private static final int Z = 5200;

    @Override
    protected String subsystem() {
        return "knowledge-deposit";
    }

    @Test
    public void pressingDepositTeachesTheWorldTheCrystalsAddresses() throws Exception {
        String where = "0 " + X + " " + Y + " " + Z;

        // The address on the crystal names a world MINTED for this test, and deliberately not one a
        // survey found: a survey teaches the world it is made from as it goes, so a crystal filled
        // by sweeping here would hold only things this world already knows - and the click could
        // then teach nothing and still look successful.
        exec("artest config set planetsMustBeDiscovered true");
        int fresh;
        try {
            String installed = exec("artest space gen-install 0.9 2000000 987654321");
            assertTrue("the procedural generator must install: " + installed,
                    Reply.of(installed).ok());
            String found = exec("artest space find-procedural 4");
            assertTrue("a dense procedural galaxy must offer a landable body: " + found,
                    Reply.of(found).ok());
            fresh = RealizedBody.atSectorLocal(this::exec,
                    intOf(found, "sx"), intOf(found, "sy"), intOf(found, "sz")).dim;
        } finally {
            exec("artest space gen-reset");
        }

        String before = exec("artest planet knowledge 0 " + fresh);
        assertTrue("arrangement: a just-minted world must be unknown here: " + before,
                (!Reply.of(before).bool("local")));
        assertTrue("arrangement: and unknown to the pack: " + before,
                (!Reply.of(before).bool("global")));
        // The COMPLETE multiblock, not a lone block: the survey tab is a machine's GUI, and a test
        // that opened a half-built one would be measuring the incomplete panel.
        // The COMPLETE multiblock, not a lone block: the survey tab is a machine's GUI, and a test
        // that opened a half-built one would be measuring the incomplete panel.
        String built = exec("artest fixture multiblock observatory 0 " + X + " " + Y + " " + Z);
        assertTrue("could not build an observatory: " + built, Reply.of(built).ok());
        String crystal = exec("artest telescope crystal " + where + " " + fresh);
        assertEquals("the machine must hold a crystal naming exactly that world: " + crystal,
                1, Reply.of("artest telescope crystal", crystal).integer("addresses"));
        TelescopeReading info = TelescopeReading.at(this::exec, where);
        assertArrayEquals("and the probe must see it there without depositing anything: "
                + info.raw(), new int[]{fresh}, info.crystalDims());

        // Stand at the machine and open its GUI the way a player does. The right-click below is
        // dispatched by the CLIENT and reach-checked against where it stands, so the placement is
        // waited for as the packet that applies it.
        long standMark = clientEvents().mark();
        exec("tp @a " + (X + 0.5) + " " + (Y + 2) + " " + (Z + 2.5) + " 0 30");
        awaitClientPlacedNear(standMark, X + 0.5, Z + 2.5,
                "the player must be at the machine before he right-clicks it");
        String screen = openGuiByRightClick(bot(), clientEvents(), X, Y, Z);
        assertTrue("right-clicking the observatory must open a GUI, got: " + screen,
                screen.contains("Gui"));

        // A tab press is a round trip: the client asks, the server re-opens the GUI on the chosen
        // tab, and only that re-opened screen carries the Deposit control.
        long tabMark = clientEvents().mark();
        bot().clickButtonById(TAB_REGION_SCAN);
        clientEvents().awaitField(tabMark, "client_gui_opened", "gui", "GuiModular",
                "the survey tab must be re-opened by the server before its Deposit control exists",
                GUI_LINK_BUDGET_TICKS);
        long depositMark = events().markInstrumented();
        bot().clickButtonById(BUTTON_DEPOSIT);
        String deposited = events().awaitField(depositMark, "crystal_deposited",
                "pos", X + "," + Y + "," + Z,
                "pressing Deposit must make THIS observatory read its crystal into the world's"
                        + " knowledge", GUI_LINK_BUDGET_TICKS);

        // The button's promise, asked of the server: the address the machine held is now something a
        // tier-1 pad standing on this world may be aimed at, and it is known LOCALLY - a deposit may
        // not touch the pack's global floor.
        String after = exec("artest planet knowledge 0 " + fresh);
        assertTrue("after the click a pad here must be offered that world: " + after
                        + " | the deposit's own record: " + deposited,
                Reply.of(after).bool("known"));
        assertTrue("and it must be known LOCALLY, not announced to the whole game: " + after,
                Reply.of(after).bool("local"));
        assertTrue("the pack's own floor must be untouched: " + after,
                (!Reply.of(after).bool("global")));
    }

    /** A numeric field of a probe reply, refusing when the reply does not carry it. */
    private static int intOf(String json, String name) {
        return Reply.of(json).integer(name);
    }
}
