package zmaster587.advancedRocketry.test.client;

import com.google.gson.JsonObject;
import org.junit.FixMethodOrder;
import org.junit.Test;
import org.junit.runners.MethodSorters;

import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import zmaster587.advancedRocketry.test.Events;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/**
 * The player holds an AR item and right-clicks it. Six scenarios, one client.
 *
 * <p>Every member drives the SAME production entry point — {@code Item#onItemRightClick} reached
 * through {@code ClientBot.useItem()} &rarr; {@code CPacketPlayerTryUseItem} — and differs only in
 * which item is in the hand and where the answer shows up: the player's chat, an opened screen, a
 * spawned entity, or a satellite's queue. That shared shape is why they group: the arrangement is
 * "give the item, wait for the CLIENT to render it in hand, click", six times over.</p>
 *
 * <h2>What the sharing costs, and what it bought</h2>
 *
 * <p>Measured 2026-08-07 at 8 forks, from the result XML: these six scenarios lived in four classes
 * costing 110.3 + 115.3 + 164.1 + 229.0 s = <b>10.3 minutes across six client boots</b>, of which
 * the six clicks themselves are seconds.</p>
 *
 * <p>Two channels the sharing makes dangerous. Both used to be handled by clearing a backlog and by
 * geometry; both are now scoped by a MARK taken on each side's event log immediately before the
 * click, which is a stronger guarantee than either — a record read after a mark cannot have come
 * from before it, whatever else is in the world:</p>
 * <ul>
 *   <li><b>Chat.</b> {@link #rightClickInVanillaDimDispatchesAirReadoutToPlayerChat} proved "the
 *       player was told X" by searching the last N chat lines, which is why it had to re-arm the
 *       channel against the harness's own {@code FORGE_TEST_DONE} markers, one per server command.
 *       It now reads {@code client_chat_received} since a client mark, so a marker in the backlog is
 *       simply another record with different text and the arming is no longer needed.</li>
 *   <li><b>Entities.</b> {@code reportEntities} counts what the CLIENT can see within a radius, and
 *       a craft spawned by one scenario is still in the world when the next one asks. Both
 *       hovercraft scenarios therefore work at the SAME offset inside their own plots, so the
 *       nearest foreign craft is a full plot stride away and outside the 32-block query. That
 *       geometry stays, and the verdict is now taken from {@code entity_joined_world} since the
 *       mark, which counts what SPAWNED in this scenario rather than what stands in range of it.</li>
 * </ul>
 *
 * <h2>What each scenario waits for</h2>
 *
 * <p>The subject is a LINK — a click that reaches the server and produces its item's answer — so
 * every wait here is an event wait, and the two absence scenarios open by asserting the click
 * arrived ({@code right_click_item}) before concluding anything from a silence. What stays a poll is
 * the arrangement's own read of the client's held item: that is a persistent VALUE, and the
 * harness's {@code client_slot_set} record is edge-gated per (window, slot), so a scenario handed
 * the same stack in the same slot as the one before it would wait for a record production
 * deliberately suppresses.</p>
 *
 * <p>Source classes, merged verbatim (method names preserved so CI history greps):
 * {@code ItemAtmosphereAnalzerReadoutE2ETest}, {@code ItemBiomeChangerSatelliteActionE2ETest},
 * {@code OreScannerRightClickClientE2ETest}, {@code ItemHovercraftSpawnE2ETest}.</p>
 */
@FixMethodOrder(MethodSorters.NAME_ASCENDING)
public class ItemRightClickClientGroupE2ETest extends AbstractSharedClientE2ETest {

    private static final int Y = Plot.DEFAULT_Y;

    /**
     * Where a scenario's own fixture block stands inside its plot. Deliberately NOT the centre: the
     * shared reset parks the player on the centre column, and a block placed under his feet is a
     * different arrangement from a block he walks up to.
     *
     * <p>Both hovercraft scenarios use this same offset on purpose — see the class javadoc: it is
     * what keeps a neighbouring scenario's craft outside the 32-block entity query.</p>
     */
    private static final int FIXTURE_DX = 20;
    private static final int FIXTURE_DZ = 20;

    private static final Pattern SAT_ID = Pattern.compile("\"satId\":(-?\\d+)");
    private static final Pattern POSLIST_SIZE = Pattern.compile("\"posListSize\":(-?\\d+)");

    /**
     * How long one link of a right-click's answer may take. The old per-scenario budgets were 100
     * ticks for the chat and the satellite queue, 200 for the client's view of a spawned entity;
     * this is the larger of them for every link, because a link that is late is a link that
     * happened, and the failure now names WHICH one did not.
     */
    private static final int LINK_BUDGET_TICKS = 200;

    @Override
    protected String subsystem() {
        return "item-use";
    }

    // ── shared arrangement ────────────────────────────────────────────────────

    private void forceLoadAround(int x, int z) throws Exception {
        int cx = x >> 4;
        int cz = z >> 4;
        for (int dx = -1; dx <= 1; dx++) {
            for (int dz = -1; dz <= 1; dz++) {
                exec("artest chunk forceload " + plot().dim + " " + (cx + dx) + " " + (cz + dz));
            }
        }
    }

    /**
     * Polls until the CLIENT renders {@code itemId} in the main hand (~10 s cap). A server-side
     * equip needs a sync round-trip, and clicking before it lands drives the click with an empty
     * hand — which is a different production path and reads as a contract failure.
     */
    private void waitForHeld(String itemId) throws Exception {
        String held = "";
        for (int waited = 0; waited < 200; waited += 5) {
            bot().waitTicks(5);
            held = bot().reportPlayerItems().getAsJsonObject("held").get("id").getAsString();
            if (itemId.equals(held)) {
                scenario().record("clientHeld", held);
                return;
            }
        }
        scenario().arrangementFailed("the client never rendered " + itemId + " in hand within 200"
                + " ticks; held=" + held + " — the item was never in the player's hand, so the"
                + " right-click below could not have dispatched it");
    }

    private static int extractInt(String src, Pattern pattern) {
        Matcher m = pattern.matcher(src);
        assertTrue("pattern " + pattern.pattern() + " not found in: " + src, m.find());
        return Integer.parseInt(m.group(1));
    }

    // ── waiting on a log, either side ─────────────────────────────────────────

    /** One read of one side's event log — the server probe's, or the client bridge's. */
    private interface LogReader {
        String read() throws Exception;
    }

    /**
     * Wait until {@code reader}'s reply carries a record matching {@code needle}, or the budget ends;
     * the reply is returned either way so the CALLER asserts and owns the failure message.
     *
     * <p>{@link Events#await} covers the server log and is used wherever a bare type is enough. This
     * exists for the two cases it cannot express: a wait on the CLIENT's own log (a different
     * transport — {@code bot().eventsSince}, no probe command behind it), and a wait for a record of
     * a type that is recorded for OTHER subjects too ({@code entity_joined_world} fires for anything
     * that joins), where the type alone would be satisfied by the wrong record. Matching is
     * case-insensitive so a needle can be written the way the payload reads.</p>
     *
     * <p>Local to this class on purpose: the shared base offers {@link Events} over the server probe
     * only, and this class is not that base's owner.</p>
     */
    private String awaitRecord(LogReader reader, String needle, int tickBudget) throws Exception {
        String reply = "";
        String wanted = needle.toLowerCase(Locale.ROOT);
        for (int waited = 0; waited <= tickBudget; waited += 5) {
            reply = String.valueOf(reader.read());
            if (reply.toLowerCase(Locale.ROOT).contains(wanted)) {
                return reply;
            }
            bot().waitTicks(5);
        }
        return reply;
    }

    /** How many records of a {@code since} reply carry BOTH needles — case-insensitively, because a
     *  chat line is prose and its capitalisation is the translation's business, not the contract's. */
    private static int recordsWithBoth(String sinceReply, String first, String second) {
        int n = 0;
        String lower = String.valueOf(sinceReply).toLowerCase(Locale.ROOT);
        for (String record : lower.split("\\{\"seq\":")) {
            if (record.contains(first.toLowerCase(Locale.ROOT))
                    && record.contains(second.toLowerCase(Locale.ROOT))) {
                n++;
            }
        }
        return n;
    }

    // ── atmosphere analyser: the answer is two lines of chat ──────────────────

    /**
     * From {@code ItemAtmosphereAnalzerReadoutE2ETest}. Player-visible side of
     * {@code ItemAtmosphereAnalzer#onItemRightClick}, observed on the REAL client chat overlay —
     * i18n already resolved, exactly the two lines the player reads.
     *
     * <p>Dim 0 has no AtmosphereHandler &rarr; production falls back to {@code AtmosphereType.AIR}.
     * Both lines must reach the player's screen: "Atmosphere Type: …air…" and "Breathable: yes".</p>
     *
     * <p>Four links, one contract, and the failure now names which one broke: the click reached the
     * server ({@code right_click_item}), the item composed its two lines
     * ({@code atmosphere_readout_composed}), the server sent them ({@code chat_message_sent}) and the
     * client's HUD was handed them, i18n resolved ({@code client_chat_received}). The RESOLVED words
     * are asserted on the client's record and nowhere else: on the server a
     * {@code TextComponentTranslation} still carries its key, so a server-side text match would pin
     * the key rather than what the player reads.</p>
     */
    @Test
    public void rightClickInVanillaDimDispatchesAirReadoutToPlayerChat() throws Exception {
        scenario().arranging("give the atmosphere analyser and wait for the client to render it");
        bot().waitForWorld();
        String give = exec("artest player give-held advancedrocketry:atmanalyser");
        scenario().requireArranged("give-held atmanalyser must succeed: " + give,
                give.contains("\"ok\":true"));
        waitForHeld("advancedrocketry:atmanalyser");

        // Both marks BEFORE the click, so nothing that happens afterwards can be missed between two
        // reads and nothing that happened before it can be mistaken for the answer. This is what
        // replaced armChatObservation(): the harness's own FORGE_TEST_DONE markers still land in the
        // chat channel, and they are now simply other records with other text.
        scenario().measuring("mark both event logs immediately before the right-click");
        Events events = events();
        long mark = events.markInstrumented();
        long clientMark = bot().eventMark().get("seq").getAsLong();

        scenario().asserting("the player reads both readout lines on his own chat");
        bot().useItem();

        events.assertChain(mark, "a right-click with the atmosphere analyser must reach the server,"
                + " compose its readout and send it to the player", LINK_BUDGET_TICKS,
                "right_click_item", "atmosphere_readout_composed", "chat_message_sent");

        // WHICH atmosphere production composed for. Dim 0 has no handler, and the contract is that
        // the analyser answers for AIR there rather than refusing or reading a neighbour's.
        String composed = events.since(mark, "atmosphere_readout_composed");
        assertEquals("a dimension with no atmosphere handler must be read out as AIR: " + composed,
                "air", Events.firstField(composed, "atmosphere"));

        // The client's own record of what its HUD was told — the two lines the player reads, with
        // the translation already applied.
        String chat = awaitRecord(
                () -> String.valueOf(bot().eventsSince(clientMark, "client_chat_received")),
                "breathable", LINK_BUDGET_TICKS);
        Events.assertInstrumentRan(chat, "client_chat_received",
                "the player was shown the analyser's readout");
        assertTrue("the client must be shown the resolved 'Atmosphere Type: …air' line; the server"
                        + " composed " + composed + " and the client's chat records since the click"
                        + " are: " + chat,
                recordsWithBoth(chat, "atmosphere type", "air") >= 1);
        assertTrue("the client must be shown the resolved 'Breathable: yes' line for AIR; the server"
                        + " composed " + composed + " and the client's chat records since the click"
                        + " are: " + chat,
                recordsWithBoth(chat, "breathable", "yes") >= 1);
    }

    // ── biome changer: the answer is a queue on a satellite ───────────────────

    /**
     * From {@code ItemBiomeChangerSatelliteActionE2ETest}. Player-visible side of
     * {@code ItemBiomeChanger#onItemRightClick}: arrange with the arrange-only
     * {@code equip-biomechanger} probe (register the satellite + equip the NBT-bound chip, no
     * click), CLIENT performs the click, and the satellite's queued-position list is the oracle —
     * server state is the contract here (save-format posList).
     *
     * <p>Two links before the oracle: the click reached the server ({@code right_click_item}) and the
     * satellite was ASKED to terraform ({@code biome_change_queued}). Between them they separate the
     * three silences a growing-list poll cannot: a click that never arrived, an item that declined
     * before ever calling the satellite (a dimension mismatch returns without a word), and a
     * satellite that was asked and queued nothing. The posList size stays as the END-STATE assertion
     * because it is read out of the satellite's own NBT — the save format, which is the contract.</p>
     */
    @Test
    public void rightClickQueuesPositionsIntoSatellitePosList() throws Exception {
        scenario().arranging("register a biome-changer satellite and equip its chip");
        bot().waitForWorld();

        String equip = exec("artest player equip-biomechanger " + plot().dim);
        scenario().requireArranged("equip-biomechanger must succeed: " + equip,
                equip.contains("\"ok\":true"));
        Matcher satM = SAT_ID.matcher(equip);
        scenario().requireArranged("equip response must carry satId: " + equip, satM.find());
        long satId = Long.parseLong(satM.group(1));
        scenario().record("satId", satId)
                .describeOnFailureWith("artest satellite poslist-size " + plot().dim + " " + satId);

        waitForHeld("advancedrocketry:biomechanger");

        scenario().measuring("the satellite's queue before the click");
        int posBefore = extractInt(
                exec("artest satellite poslist-size " + plot().dim + " " + satId), POSLIST_SIZE);
        scenario().record("posListBefore", posBefore);

        scenario().asserting("the right-click queues positions into the satellite's posList");
        Events events = events();
        long mark = events.markInstrumented();
        bot().useItem();

        events.assertChain(mark, "a right-click with the biome changer must reach the server and ask"
                + " the bound satellite to terraform", LINK_BUDGET_TICKS,
                "right_click_item", "biome_change_queued");
        String queued = events.since(mark, "biome_change_queued");
        // The request is a SERVER-side one: production guards the whole body on !world.isRemote, and
        // the recorder writes remote:true for the early exit that guard takes. A remote:true here
        // would mean the queue below was filled by nobody.
        assertTrue("the satellite must have been asked on the SERVER, not on the client's copy: "
                + queued, queued.contains("\"remote\":false"));

        int posAfter = extractInt(
                exec("artest satellite poslist-size " + plot().dim + " " + satId), POSLIST_SIZE);
        assertTrue("right-click must queue positions into the satellite's posList "
                        + "(before=" + posBefore + ", after=" + posAfter + "); the satellite was"
                        + " asked: " + queued,
                posAfter > posBefore);
        assertEquals("posList stores (x,y,z) triples — length must be divisible by 3, got "
                + posAfter, 0, posAfter % 3);
    }

    // ── ore scanner: the answer is a screen, or the absence of one ────────────

    /**
     * From {@code OreScannerRightClickClientE2ETest}. Empty satellite-ID branch: the held
     * OreScanner has no NBT &rarr; early-out, no GUI opens on the client, no crash.
     *
     * <p><b>An absence, and what makes it mean something.</b> "No screen opened" is satisfied by a
     * click that never arrived — a hand that emptied between the arrangement and the stimulus, a
     * spectator gate, a reach check — so the scenario first asserts the POSITIVE half from the
     * server's own log: the right-click reached the server, carrying the ore scanner. Only then is
     * the silence about the item's decision. The two honesty flags come from
     * {@link Events#markInstrumented} (the bus recorder is subscribed, the test-only mixins were
     * queued); {@code assertInstrumentRan} deliberately is NOT used for the two silent seams, because
     * both announce themselves from INSIDE the method that is not supposed to run, and a correct
     * absence would fail it. The live control for {@code gui_container_served} is the sibling
     * scenario below, which asserts the same seam recording a served container.</p>
     */
    @Test
    public void rightClickWithEmptySatelliteIdOpensNoGuiAndDoesNotCrash() throws Exception {
        scenario().arranging("equip an ore scanner with no satellite bound");
        bot().waitForWorld();
        String equip = exec("artest player equip-orescanner none");
        scenario().requireArranged("equip-orescanner must succeed: " + equip,
                equip.contains("\"ok\":true"));
        scenario().requireArranged("empty branch must report hadSatelliteId:false: " + equip,
                equip.contains("\"hadSatelliteId\":false"));
        waitForHeld("advancedrocketry:orescanner");

        scenario().asserting("no screen opens on the client");
        Events events = events();
        long mark = events.markInstrumented();
        long clientMark = bot().eventMark().get("seq").getAsLong();
        bot().useItem();
        // A window for the absence: nothing is being waited FOR, so its expiry is not the failure.
        bot().waitTicks(20);

        String clicks = events.await(mark, "right_click_item", "the click must reach the server"
                + " before its silence can be read as the item declining to open a GUI",
                LINK_BUDGET_TICKS);
        assertTrue("the click that reached the server must be the ORE SCANNER's — an empty hand"
                + " would produce the same silence below: " + clicks,
                clicks.contains("\"item\":\"advancedrocketry:orescanner\""));

        String served = events.since(mark, "gui_container_served");
        assertEquals("an unbound ore scanner must never ask the server for a GUI; containers served"
                + " since the click: " + served, 0, Events.typesOf(served).size());
        String opened = String.valueOf(bot().eventsSince(clientMark, "client_gui_opened"));
        assertEquals("empty-satellite right-click must open no screen on the client; screens the"
                        + " client was asked to display since the click: " + opened,
                0, Events.countRecords(opened, "OreMapping"));
        assertEquals("empty-satellite right-click must not leave a screen open",
                "", bot().reportState().get("screen").getAsString());
    }

    /**
     * From {@code OreScannerRightClickClientE2ETest}. Resolved satellite-ID branch: a registered
     * SatelliteOreMapping &rarr; the OreMapping GUI must actually OPEN on the client. (The old
     * probe-driven test only pinned "no crash" — it could not see whether the GUI opened.)
     */
    @Test
    public void rightClickWithRegisteredSatelliteIdOpensOreMappingGui() throws Exception {
        scenario().arranging("register an ore-mapping satellite and equip a scanner bound to it");
        bot().waitForWorld();
        String equip = exec("artest player equip-orescanner " + plot().dim);
        scenario().requireArranged("equip-orescanner must succeed: " + equip,
                equip.contains("\"ok\":true"));
        scenario().requireArranged("resolved branch must report hadSatelliteId:true: " + equip,
                equip.contains("\"hadSatelliteId\":true"));
        waitForHeld("advancedrocketry:orescanner");

        scenario().asserting("the OreMapping GUI opens on the client");
        Events events = events();
        long mark = events.markInstrumented();
        long clientMark = bot().eventMark().get("seq").getAsLong();
        bot().useItem();

        // Three links: the click arrived, AR's gui handler served a container for it, and the client
        // was asked to display the matching screen. The handler's record is taken at its RETURN and
        // carries "null" for a refusal, so a red here says whether the server declined to open
        // anything or opened something the client never showed.
        events.assertChain(mark, "a right-click with a bound ore scanner must reach the server and"
                + " be served the ore-mapping container", LINK_BUDGET_TICKS,
                "right_click_item", "gui_container_served");
        String served = events.since(mark, "gui_container_served");
        assertTrue("the container AR's gui handler served must be the ore-mapping one; served: "
                + served, served.contains("OreMapping"));

        String opened = awaitRecord(
                () -> String.valueOf(bot().eventsSince(clientMark, "client_gui_opened")),
                "oremapping", LINK_BUDGET_TICKS);
        Events.assertInstrumentRan(opened, "client_gui_events",
                "the client was asked to display the ore-mapping screen");
        assertTrue("the client must be asked to display the OreMapping screen; server served "
                + served + " and the client's screen requests since the click are: " + opened,
                opened.toLowerCase(Locale.ROOT).contains("oremapping"));
        assertEquals("the ore-mapping screen must not be cancelled on the way to the player: "
                + opened, 0, recordsWithBoth(opened, "oremapping", "\"cancelled\":true"));

        String screen = bot().reportState().get("screen").getAsString();
        assertTrue("right-click with a resolved SatelliteOreMapping must leave the "
                + "OreMapping GUI open on the client; screen='" + screen + "' opens=" + opened,
                screen.contains("OreMapping"));

        // Left open, the next scenario would start on a screen — which the shared reset closes and
        // then asserts about. Closing it here keeps that assertion about the RESET rather than
        // about this scenario's manners.
        bot().closeScreen();
    }

    // ── hovercraft item: the answer is an entity the client can see ───────────

    /**
     * From {@code ItemHovercraftSpawnE2ETest}. Right-click looking down at a stone block must spawn
     * exactly one EntityHoverCraft the CLIENT can see, and consume the held stack (survival).
     *
     * <p>The player is dropped to survival for the consumption half of the contract; the shared
     * reset puts the mode back for whoever runs next.</p>
     */
    @Test
    public void rightClickAtTargetBlockSpawnsHovercraftAndConsumesStack() throws Exception {
        int dim = plot().dim;
        int x = plot().x(FIXTURE_DX);
        int z = plot().z(FIXTURE_DZ);

        scenario().arranging("place the stone target at " + x + "," + Y + "," + z);
        bot().waitForWorld();
        forceLoadAround(x, z);
        String placeResp = exec("artest place " + dim + " " + x + " " + Y + " " + z + " minecraft:stone");
        scenario().requireArranged("place must not error; resp=" + placeResp,
                !placeResp.contains("\"error\""));

        scenario().arranging("stand the survival player two blocks above it, holding the item");
        exec("gamemode survival @a");
        String give = exec("artest player give-held advancedrocketry:hovercraft");
        scenario().requireArranged("give-held must succeed: " + give, give.contains("\"ok\":true"));
        exec("tp @a " + (x + 0.5) + " " + (Y + 2) + " " + (z + 0.5));
        bot().waitTicks(10);
        waitForHeld("advancedrocketry:hovercraft");

        // The stimulus is ray-traced SERVER-side from the player's look, so the aim has to have
        // arrived before the click. Aiming and clicking in the same breath leaves the ray pointing
        // wherever the player was already facing — horizontally, into 5 blocks of empty air, where
        // production correctly returns PASS and spawns nothing. That is a failure of the
        // arrangement wearing the contract's clothes, so the aim is MEASURED on the client itself
        // before the click rather than assumed from having asked for it.
        scenario().measuring("aim straight down and confirm the CLIENT is holding that look");
        bot().setLook(0f, 90f);
        double pitch = Double.NaN;
        for (int i = 0; i < 20; i++) {
            bot().waitTicks(2);
            pitch = bot().reportState().get("playerPitch").getAsDouble();
            if (pitch > 89.0) break;
        }
        scenario().record("clientPitch", pitch);
        scenario().requireArranged("the client must be looking straight down before the click, or"
                + " the item's 5-block ray traces into empty air; client pitch=" + pitch,
                pitch > 89.0);

        scenario().asserting("the client sees exactly one spawned hovercraft, and loses the stack");
        Events events = events();
        long mark = events.markInstrumented();
        long clientMark = bot().eventMark().get("seq").getAsLong();
        bot().useItem();

        // The craft's own arrival on each side, since the mark — not a radius query. A count taken
        // from the log is a count of what SPAWNED in this scenario, so a neighbouring scenario's
        // craft standing in range can no longer be mistaken for this one's (the plot geometry above
        // stays anyway; it costs nothing and keeps the reading honest for a reader who queries).
        events.assertChain(mark, "a right-click at a block must reach the server and spawn the"
                + " hovercraft into the world", LINK_BUDGET_TICKS,
                "right_click_item", "entity_joined_world");
        String spawnedOnServer = events.since(mark, "entity_joined_world");
        assertEquals("the server must spawn exactly one EntityHoverCraft for one click; entities"
                        + " that joined the server world since it: " + spawnedOnServer,
                1, Events.countRecords(spawnedOnServer, "EntityHoverCraft"));

        String spawnedOnClient = awaitRecord(
                () -> String.valueOf(bot().eventsSince(clientMark, "entity_joined_world")),
                "entityhovercraft", LINK_BUDGET_TICKS);
        Events.assertInstrumentRan(spawnedOnClient, "client_entity_join_events",
                "the client received the spawned hovercraft");
        int seen = Events.countRecords(spawnedOnClient, "\"cls\":\"EntityHoverCraft\"");
        // Read the stack whatever the entity count said: together the two separate "production
        // returned PASS/FAIL and spawned nothing" (stack intact) from "it spawned and the client
        // never saw it" (stack consumed). One of those is a client-sync bug and the other is not.
        JsonObject held = bot().reportPlayerItems().getAsJsonObject("held");
        scenario().record("heldAfterClick", held).record("clientHovercraftSeen", seen);

        assertEquals("client must see exactly one spawned EntityHoverCraft; the held stack after"
                + " the click was " + held + " (a stack still holding 1 means production returned"
                + " PASS or FAIL and never spawned anything), the server spawned "
                + spawnedOnServer + " and the client received " + spawnedOnClient, 1, seen);
        assertEquals("survival right-click must consume the held hovercraft item; held="
                + held, 0, held.get("count").getAsInt());
    }

    /**
     * From {@code ItemHovercraftSpawnE2ETest}. Right-click into open air (no block within 5 blocks
     * of the eye) must pass: no entity spawned, stack preserved. Pins the empty-ray-trace branch.
     *
     * <p>The absence's positive half is the click itself: {@code right_click_item} carrying the
     * hovercraft proves the item was in hand and the click was not dropped, so the silence that
     * follows is the ray trace's answer rather than the arrangement's.</p>
     */
    @Test
    public void rightClickIntoEmptyAirReturnsPassWithoutSpawn() throws Exception {
        int x = plot().x(FIXTURE_DX);
        int z = plot().z(FIXTURE_DZ);

        scenario().arranging("stand the survival player in open sky with the item in hand");
        bot().waitForWorld();
        forceLoadAround(x, z);
        exec("gamemode survival @a");
        String give = exec("artest player give-held advancedrocketry:hovercraft");
        scenario().requireArranged("give-held must succeed: " + give, give.contains("\"ok\":true"));
        // 200 is 50 blocks above the plot's own fixture level, and nothing is ever placed there —
        // so the upward ray has nothing to hit that belongs to this scenario or any other.
        exec("tp @a " + (x + 0.5) + " 200 " + (z + 0.5));
        bot().waitTicks(10);
        waitForHeld("advancedrocketry:hovercraft");

        // The control this scenario turns on: it must be able to tell "nothing spawned" from
        // "something else's craft is in range". Read the count BEFORE the click, in the same
        // radius the verdict uses.
        scenario().measuring("hovercraft the client can already see, before any click");
        int before = bot().reportEntities("EntityHoverCraft", 32).get("count").getAsInt();
        scenario().record("hovercraftInRangeBefore", before);
        scenario().requireArranged("no hovercraft may be within the query radius before the click,"
                + " or a PASS is indistinguishable from a spawn; saw " + before, before == 0);

        scenario().asserting("an empty ray-trace spawns nothing and keeps the stack");
        Events events = events();
        long mark = events.markInstrumented();
        bot().setLook(0f, -90f);
        bot().useItem();
        // A window for the absence: expiry is not the failure here.
        bot().waitTicks(20);

        String clicks = events.await(mark, "right_click_item", "the click must reach the server"
                + " before its silence can be read as an empty ray trace", LINK_BUDGET_TICKS);
        assertTrue("the click that reached the server must be the HOVERCRAFT's — an empty hand"
                + " would produce the same absence below: " + clicks,
                clicks.contains("\"item\":\"advancedrocketry:hovercraft\""));

        String joined = events.since(mark, "entity_joined_world");
        assertEquals("no hovercraft must be spawned on an empty ray-trace; entities that joined the"
                        + " server world since the click: " + joined,
                0, Events.countRecords(joined, "EntityHoverCraft"));
        assertEquals("no hovercraft must spawn on an empty ray-trace",
                0, bot().reportEntities("EntityHoverCraft", 32).get("count").getAsInt());
        JsonObject held = bot().reportPlayerItems().getAsJsonObject("held");
        assertEquals("stack must NOT be consumed on PASS; held=" + held,
                1, held.get("count").getAsInt());
    }
}
