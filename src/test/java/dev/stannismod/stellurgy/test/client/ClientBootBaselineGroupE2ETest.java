package dev.stannismod.stellurgy.test.client;

import dev.stannismod.stellurgy.test.Events;
import dev.stannismod.stellurgy.test.Reply;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import org.junit.FixMethodOrder;
import org.junit.Test;
import org.junit.runners.MethodSorters;


import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * Everything that is true of a booted client before any feature is exercised: the bridge answers,
 * the mod list agrees with itself, and the harness client is silent.
 *
 * <h2>Why these three share one harness</h2>
 *
 * <p>Each was a single-method class, and each spent better than 95 % of its wall clock booting a
 * server and a client in order to ask one question of them. Measured 2026-08-07 on the maintainer's
 * box at 8 forks, from the result XML: {@code ClientConnectSmokeTest} 115.2 s,
 * {@code ModCountParityE2ETest} 107.9 s, {@code TestClientSoundMutedE2ETest} 76.1 s — three boots,
 * three client JVMs, ~5 minutes of machine time for three assertions that between them take
 * seconds.</p>
 *
 * <p>They also share a property none of the other groups has: <b>not one of them mutates anything.</b>
 * No block is placed, no item given, no dimension entered, no global flipped. That makes this the
 * safest possible group and the natural seed for a smoke lane — if these three are red, nothing else
 * in the tier can mean anything, and the rest of the run is wasted machine time.</p>
 *
 * <p>Source classes, merged verbatim (method names preserved so CI history greps):
 * {@code ClientConnectSmokeTest}, {@code ModCountParityE2ETest},
 * {@code TestClientSoundMutedE2ETest}.</p>
 */
@FixMethodOrder(MethodSorters.NAME_ASCENDING)
public class ClientBootBaselineGroupE2ETest extends AbstractSharedClientE2ETest {

    /** A deadline for the mute's own client tick, which is one of the first the client runs. Kept at
     *  the 200 ticks the readback poll it replaces allowed. */
    private static final int MUTE_BUDGET_TICKS = 200;

    private static final String MASTER = "master";

    @Override
    protected String subsystem() {
        return "client-boot";
    }

    /**
     * From {@code ModCountParityE2ETest}: e2e regression guard for the dummy-mod-container removal
     * (dercodeKoenig/AdvancedRocketry#71).
     *
     * <p>The ASM coremod used to register a {@code DummyModContainer}
     * ({@code stellurgycore}) with empty lifecycle handlers. Its single observable effect was
     * the vanilla main-menu line "N mods loaded, M mods active" disagreeing by one: the phantom
     * container counted as loaded but never became active. The {@code report_mods} probe reads the
     * exact two lists that menu line renders ({@code FMLCommonHandler.getBrandings} &rarr;
     * {@code Loader.getModList()} / {@code getActiveModList()}), on the real client — so this is the
     * player-visible layer of the report.</p>
     */
    @Test
    public void everyLoadedModIsActiveAndTheDummyContainerIsGone() throws Exception {
        scenario().asserting("the client's own mod lists agree, and carry no phantom container");
        bot().waitForWorld();

        JsonObject mods = bot().reportMods();
        int loaded = mods.get("loadedCount").getAsInt();
        int active = mods.get("activeCount").getAsInt();
        JsonArray ids = mods.getAsJsonArray("loadedModIds");
        scenario().record("loadedCount", loaded).record("activeCount", active);

        StringBuilder idList = new StringBuilder();
        boolean hasAr = false;
        boolean hasDummy = false;
        for (int i = 0; i < ids.size(); i++) {
            String id = ids.get(i).getAsString();
            idList.append(id).append(' ');
            hasAr |= "stellurgy".equals(id);
            hasDummy |= "stellurgycore".equals(id);
        }

        assertTrue("stellurgy must be among loaded mods: " + idList, hasAr);
        assertFalse("the vestigial dummy container stellurgycore must be gone "
                + "(issue dercodeKoenig/AdvancedRocketry#71): " + idList, hasDummy);
        // The actual user-visible symptom: the title-screen counts must agree.
        // A loaded-but-never-active container makes loadedCount = activeCount + 1.
        assertEquals("every loaded mod must be active (title-screen 'loaded' vs 'active' "
                + "mismatch — phantom container?): " + idList, loaded, active);
    }

}
