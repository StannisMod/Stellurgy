package zmaster587.advancedRocketry.test.client;

import com.google.gson.JsonObject;
import org.junit.FixMethodOrder;
import org.junit.Test;
import org.junit.runners.MethodSorters;

import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import zmaster587.advancedRocketry.test.Events;

import zmaster587.advancedRocketry.test.Plot;

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
 *   <li><b>Chat.</b> {@link #rightClickInVanillaDimComposesTheAirReadout} proved "the player was
 *       told X" by searching the last N chat lines, which is why it had to re-arm the channel
 *       against the harness's own completion sentinel, one broadcast per server command. It no
 *       longer looks at chat at all: what the analyser does is COMPOSE a reading, that reading is a
 *       record with the atmosphere in it, and the sentence the player sees is a rendering of it.</li>
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
     * The client is holding {@code itemId}, waited for as the PACKET that puts it there.
     *
     * <p>A server-side equip reaches the client as a set-slot write, and the harness records it
     * where the client applies it — so this waits for the arrival rather than asking a rendered
     * field how it looks every five ticks. What that changes is what an expiry MEANS: the poll it
     * replaces could only ever report "still not, after 200 ticks", which is a sentence about this
     * machine, while an absent record with the recorder proven live says the equip never reached
     * the client at all.</p>
     *
     * <p><b>Read once first, and that branch is not an optimisation.</b> The seam change-gates per
     * (window, slot) on item and count, so re-equipping what the hand already holds is recorded
     * NOWHERE — and a scenario that inherits the right item from its predecessor would then wait out
     * the whole budget for a packet nobody was going to send.</p>
     *
     * <p>Still an ARRANGEMENT gate, and typed as one: the contract under test is what the
     * right-click does, and a hand that never filled means the click below would have dispatched an
     * empty one.</p>
     *
     * @param equipMark the CLIENT's own mark, taken BEFORE the command that equips the item
     */
    private void awaitHeld(long equipMark, String itemId) throws Exception {
        String held = heldOnClient();
        if (!itemId.equals(held)) {
            try {
                clientEvents().awaitCarrying(equipMark, "client_slot_set",
                        "\"item\":\"" + itemId + "\"",
                        "the equip must REACH the client — the right-click below is dispatched from"
                                + " the hand the client renders, so an item that never arrived makes"
                                + " it a click with an empty hand", HELD_LINK_BUDGET_TICKS);
            } catch (AssertionError never) {
                // Which silence it was. An empty log from a recorder that never wove says nothing
                // about the equip, and must not be read as one that failed.
                Events.assertInstrumentRan(clientEvents().since(equipMark, "client_slot_set"),
                        "client_slot_set", "the client's own slot writes must be observed at all"
                                + " before an absent one can be read as an equip that never landed");
                scenario().arrangementFailed("the client was never told it holds " + itemId
                        + "; it renders " + held + " — " + never.getMessage());
            }
            held = heldOnClient();
        }
        // READ ONCE, after the link: the record says the packet arrived, this says what the hand
        // renders now, and a disagreement between the two is worth seeing in the failure text.
        if (!itemId.equals(held)) {
            scenario().arrangementFailed("the client APPLIED a slot write carrying " + itemId
                    + " and still renders " + held + " in hand — the item reached the client and"
                    + " something else holds the main hand");
        }
        scenario().record("clientHeld", held);
    }

    /** What the client renders in the main hand, right now. */
    private String heldOnClient() throws Exception {
        return bot().reportPlayerItems().getAsJsonObject("held").get("id").getAsString();
    }

    /**
     * How long the client is given to be TOLD about an equip, in ticks.
     *
     * <p>A deadline for one round trip that has already happened on the server, not a guess at how
     * long equipping takes. It keeps the old poll's own ceiling so that nothing that used to pass on
     * a slow box starts failing here.</p>
     */
    private static final int HELD_LINK_BUDGET_TICKS = 200;

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
        return Events.recordsWithAllIgnoringCase(sinceReply, first, second).size();
    }

    // ── atmosphere analyser: the answer is two lines of chat ──────────────────

    /**
     * From {@code ItemAtmosphereAnalzerReadoutE2ETest}. What
     * {@code ItemAtmosphereAnalzer#onItemRightClick} DOES: it reads the atmosphere the player is
     * standing in and composes a reading of it.
     *
     * <p>Dim 0 has no AtmosphereHandler &rarr; production falls back to {@code AtmosphereType.AIR},
     * and the contract is that the analyser answers for AIR there rather than refusing or reading a
     * neighbour's.</p>
     *
     * <p>Two links, one contract, and the failure names which one broke: the click reached the
     * server ({@code right_click_item}) and the item composed its readout
     * ({@code atmosphere_readout_composed}), whose own payload carries the atmosphere it composed
     * for. Two further links stood here — the server SENDING the lines and the client's HUD being
     * handed them — with the resolved English asserted on the client's records. They are gone: a
     * chat line is a rendering of this reading, and pinning the rendered words tied the test to the
     * language file.</p>
     */
    @Test
    public void rightClickInVanillaDimComposesTheAirReadout() throws Exception {
        scenario().arranging("give the atmosphere analyser and wait for the client to render it");
        bot().waitForWorld();
        long equipMark = clientEvents().mark();
        String give = exec("artest player give-held advancedrocketry:atmanalyser");
        scenario().requireArranged("give-held atmanalyser must succeed: " + give,
                give.contains("\"ok\":true"));
        awaitHeld(equipMark, "advancedrocketry:atmanalyser");

        // Both marks BEFORE the click, so nothing that happens afterwards can be missed between two
        // reads and nothing that happened before it can be mistaken for the answer. This is what
        // replaced armChatObservation(): whatever else is in the chat channel is simply another
        // record with other text. The harness no longer adds to it — the completion sentinel that
        // used to be broadcast per server command is gone with the server's control socket.
        scenario().measuring("mark the event log immediately before the right-click");
        Events events = events();
        long mark = events.markInstrumented();

        scenario().asserting("the analyser composes a reading for the atmosphere it is standing in");
        bot().useItem();

        // A third link stood on this chain — `chat_message_sent` — and two assertions after it read
        // the resolved lines out of the client's own chat records. Both were about the READOUT
        // BEING RENDERED; what the analyser DOES is compose a reading for the atmosphere it is
        // standing in, and that is the link below and the value asserted from it.
        events.assertChain(mark, "a right-click with the atmosphere analyser must reach the server"
                + " and compose its readout", LINK_BUDGET_TICKS,
                "right_click_item", "atmosphere_readout_composed");

        // WHICH atmosphere production composed for. Dim 0 has no handler, and the contract is that
        // the analyser answers for AIR there rather than refusing or reading a neighbour's.
        String composed = events.since(mark, "atmosphere_readout_composed");
        assertEquals("a dimension with no atmosphere handler must be read out as AIR: " + composed,
                "air", Events.firstField(composed, "atmosphere"));
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

        long equipMark = clientEvents().mark();
        String equip = exec("artest player equip-biomechanger " + plot().dim);
        scenario().requireArranged("equip-biomechanger must succeed: " + equip,
                equip.contains("\"ok\":true"));
        Matcher satM = SAT_ID.matcher(equip);
        scenario().requireArranged("equip response must carry satId: " + equip, satM.find());
        long satId = Long.parseLong(satM.group(1));
        scenario().record("satId", satId)
                .describeOnFailureWith("artest satellite poslist-size " + plot().dim + " " + satId);

        awaitHeld(equipMark, "advancedrocketry:biomechanger");

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
        long equipMark = clientEvents().mark();
        String equip = exec("artest player equip-orescanner none");
        scenario().requireArranged("equip-orescanner must succeed: " + equip,
                equip.contains("\"ok\":true"));
        scenario().requireArranged("empty branch must report hadSatelliteId:false: " + equip,
                equip.contains("\"hadSatelliteId\":false"));
        awaitHeld(equipMark, "advancedrocketry:orescanner");

        scenario().asserting("no screen opens on the client");
        Events events = events();
        long mark = events.markInstrumented();
        long clientMark = clientEvents().mark();
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
        String opened = clientEvents().since(clientMark, "client_gui_opened");
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
        long equipMark = clientEvents().mark();
        String equip = exec("artest player equip-orescanner " + plot().dim);
        scenario().requireArranged("equip-orescanner must succeed: " + equip,
                equip.contains("\"ok\":true"));
        scenario().requireArranged("resolved branch must report hadSatelliteId:true: " + equip,
                equip.contains("\"hadSatelliteId\":true"));
        awaitHeld(equipMark, "advancedrocketry:orescanner");

        scenario().asserting("the OreMapping GUI opens on the client");
        Events events = events();
        long mark = events.markInstrumented();
        long clientMark = clientEvents().mark();
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
                () -> clientEvents().since(clientMark, "client_gui_opened"),
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
        long equipMark = clientEvents().mark();
        String give = exec("artest player give-held advancedrocketry:hovercraft");
        scenario().requireArranged("give-held must succeed: " + give, give.contains("\"ok\":true"));
        exec("tp @a " + (x + 0.5) + " " + (Y + 2) + " " + (z + 0.5));
        // The click below is ray-traced from where the player stands and is dispatched by the
        // CLIENT, so the placement has to have reached the client — which is a link, where the ten
        // ticks that stood here were a guess at a round trip.
        awaitClientPlacedNear(equipMark, x + 0.5, z + 0.5,
                "the survival player must be standing over the block before he right-clicks at it");
        awaitHeld(equipMark, "advancedrocketry:hovercraft");

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
        long clientMark = clientEvents().mark();
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
                () -> clientEvents().since(clientMark, "entity_joined_world"),
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
        long equipMark = clientEvents().mark();
        String give = exec("artest player give-held advancedrocketry:hovercraft");
        scenario().requireArranged("give-held must succeed: " + give, give.contains("\"ok\":true"));
        // 200 is 50 blocks above the plot's own fixture level, and nothing is ever placed there —
        // so the upward ray has nothing to hit that belongs to this scenario or any other.
        exec("tp @a " + (x + 0.5) + " 200 " + (z + 0.5));
        awaitClientPlacedNear(equipMark, x + 0.5, z + 0.5,
                "the player must be up in the empty air ON THE CLIENT before he clicks: the ray"
                        + " this scenario needs to hit nothing is cast from where the client stands");
        awaitHeld(equipMark, "advancedrocketry:hovercraft");

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
