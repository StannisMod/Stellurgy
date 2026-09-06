package zmaster587.advancedRocketry.test.client;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import org.junit.FixMethodOrder;
import org.junit.Test;
import org.junit.runners.MethodSorters;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

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

    private static final Pattern MASTER = Pattern.compile("\"master\":(-?[0-9.eE+-]+)");

    @Override
    protected String subsystem() {
        return "client-boot";
    }

    /** From {@code ClientConnectSmokeTest}: the client bridge handshake round-trips a player view. */
    @Test
    public void clientReportsStateOverBridge() throws Exception {
        scenario().asserting("the client answers report_state over the bridge");
        // ARRANGEMENT GATE (harness): the bridge answers before the world exists, so a report read
        // without this would describe a client that has not joined anything yet.
        bot().waitForWorld();
        JsonObject state = bot().reportState();
        assertTrue("client reportState missing 'ok' key: " + state, state.has("ok"));
        // The handshake must round-trip a PLAYER view, which is what the smoke test was named for.
        // The old assertion here was assertNotNull on the reply, which cannot fail: ClientBot
        // throws on a failed reply rather than returning null.
        assertTrue("the bridge answered, but not about a client that is in a world: " + state,
                state.has("worldReady") && state.get("worldReady").getAsBoolean());
    }

    /**
     * From {@code ModCountParityE2ETest}: e2e regression guard for the dummy-mod-container removal
     * (dercodeKoenig/AdvancedRocketry#71).
     *
     * <p>The ASM coremod used to register a {@code DummyModContainer}
     * ({@code advancedrocketrycore}) with empty lifecycle handlers. Its single observable effect was
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
            hasAr |= "advancedrocketry".equals(id);
            hasDummy |= "advancedrocketrycore".equals(id);
        }

        assertTrue("advancedrocketry must be among loaded mods: " + idList, hasAr);
        assertFalse("the vestigial dummy container advancedrocketrycore must be gone "
                + "(issue dercodeKoenig/AdvancedRocketry#71): " + idList, hasDummy);
        // The actual user-visible symptom: the title-screen counts must agree.
        // A loaded-but-never-active container makes loadedCount = activeCount + 1.
        assertEquals("every loaded mod must be active (title-screen 'loaded' vs 'active' "
                + "mismatch — phantom container?): " + idList, loaded, active);
    }

    /**
     * From {@code TestClientSoundMutedE2ETest}: a harness-spawned test client must run SILENT.
     *
     * <p>Automated client e2e boots a real client with real audio on the dev box;
     * {@code ClientProxy.muteTestClientSound} zeroes the master sound level on the first client tick
     * where the sound handler is up, gated on the {@code -Dforge.test.client=true} marker that every
     * {@code RealClientHarness} client carries (and a manual {@code runClient} does not).</p>
     *
     * <p>This observes the REAL client state as an EVENT: {@code test_client_muted} is recorded by a
     * test-only mixin at the one return production reaches only after it has written the level, and
     * its {@code master} payload is what {@code GameSettings} reports at that instant. So the mute
     * RUNNING and the level it LEFT are one record, and this fails if the mute is removed, mis-gated
     * or clamped — not merely if the code path is skipped. Production keeps no field for any of it.</p>
     */
    @Test
    public void harnessTestClientHasMasterSoundMuted() throws Exception {
        scenario().asserting("the harness client's master sound level is 0");
        bot().waitForWorld();

        // Read from sequence 0, NOT from a mark: the mute lands on one of the client's first END
        // ticks, before any scenario in this class can take a mark, so a since(mark) window would
        // be empty however long it waited. Nothing can have evicted the record — the ring is 256
        // deep PER TYPE and production reaches this seam at most once per client session.
        String muted = clientEvents().awaitCarrying(0L, "test_client_muted", "\"master\"",
                "a harness-spawned client must mute its master sound level on the first client tick"
                        + " with the sound handler up (instrument: client_proxy_events)",
                MUTE_BUDGET_TICKS);

        Matcher m = MASTER.matcher(muted);
        assertTrue("a test_client_muted record must carry the level the mute left behind: " + muted,
                m.find());
        float master = Float.parseFloat(m.group(1));
        scenario().record("testClientMasterVolume", master);
        assertEquals("a harness test client must have master sound muted to 0",
                0.0f, master, 1e-6f);
    }

}
