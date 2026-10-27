package dev.stannismod.stellurgy.test.client;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import dev.stannismod.stellurgy.test.Events;
import dev.stannismod.stellurgy.test.GameTicks;
import dev.stannismod.stellurgy.test.PlayerState;
import dev.stannismod.stellurgy.test.Reply;
import dev.stannismod.stellurgy.test.Window;
import dev.stannismod.stellurgy.test.contract.LegacyWitness;
import dev.stannismod.stellurgy.test.contract.Pins;
import dev.stannismod.stellurgy.test.contract.RedWitness;
import dev.stannismod.stellurgy.test.contract.Wait;
import org.junit.FixMethodOrder;
import org.junit.Test;
import org.junit.runners.MethodSorters;

import java.util.HashSet;
import java.util.Locale;
import java.util.Set;

import static org.junit.Assert.*;

/**
 * The player types an {@code /ar} command into the real client chat, and reads the answer there.
 * Seven scenarios, one client.
 *
 * <p>Every member drives the same production entry: {@code ClientBot.sendChat} &rarr;
 * {@code CPacketChatMessage} &rarr; the server's command handler &rarr;
 * {@code dev.stannismod.stellurgy.command.WorldCommand}. The outcome is observed from the CLIENT
 * side — the dimension it renders ({@code reportWeather}), the inventory it draws
 * ({@code reportPlayerItems}), the reply on its chat overlay ({@code reportChat}) — with server
 * probes kept as cross-side oracles.</p>
 *
 * <h2>Why these seven share one harness</h2>
 *
 * <p>Measured 2026-08-07 at 8 forks, from the result XML:
 * {@code WorldCommandPlayerEquippedE2ETest} 341.2 s over five client boots and
 * {@code WorldCommandFetchTest} 146.1 s over two — <b>8.1 minutes to type seven commands</b>.</p>
 *
 * <h2>The two channels this group has to be careful with</h2>
 *
 * <ul>
 *   <li><b>Chat is both the stimulus channel and the observation channel here.</b> Four scenarios
 *       prove "the command answered the player" by reading the last N lines of a channel a SHARED
 *       client leaves dirty. They used
 *       to drain that backlog and prove it empty before typing ({@code armChatObservation}), because
 *       searching the last N lines searches a window the test does not control. They now take a MARK
 *       on the client's event log instead and await a {@code client_chat_received} carrying the
 *       reply: everything before the mark is invisible by construction, so a marker line can no
 *       longer be mistaken for an answer and no drain is needed.</li>
 *   <li><b>Two scenarios leave the player in another dimension.</b> That is the shared base's job
 *       now: the reset returns him to his plot's world and asserts the world the client renders,
 *       rather than trusting a teleport that would have moved him to the right coordinates in the
 *       wrong world.</li>
 * </ul>
 *
 * <p>The bot is opped per scenario rather than in a {@code @Before}: the ops are part of each
 * scenario's arrangement and show up in its journal, and the de-op that the source classes did in
 * {@code @After} would have run before the failure watcher could print anything.</p>
 *
 * <p>Source classes, merged verbatim (method names preserved so CI history greps):
 * {@code WorldCommandPlayerEquippedE2ETest}, {@code WorldCommandFetchTest}.</p>
 */
@FixMethodOrder(MethodSorters.NAME_ASCENDING)
public class WorldCommandClientGroupTest extends AbstractSharedClientTest {

    private static final String STATION_ID = "id";
    private static final String POS_X = "posX";

    /** The space dim, where {@code /ar goto station} lands the player. */
    private static final int SPACE_DIM = -2;

    /** The registered name of Stellurgy's planet world type (WorldTypePlanetGen). */
    private static final String AR_PLANET_WORLD_TYPE = "PlanetGen";

    @Override
    protected String subsystem() {
        return "world-command";
    }

    // ── shared arrangement ────────────────────────────────────────────────────

    /** Ops the bot: every verb below is op-gated, and an un-opped run fails as a permission error
     *  that reads nothing like the contract under test. */
    private void opTheBot() throws Exception {
        String op = arrange("stellurgytest player op-self").raw();
        scenario().requireArranged("op-self must succeed: " + op, Reply.of(op).bool("opped"));
    }

    private String botName() throws Exception {
        return PlayerState.botName(c -> read(c).raw());
    }

    /**
     * The one dimension present in {@code after} and not in {@code before}, or {@code -1}.
     *
     * <p>Both are {@code stellurgytest dim list} replies now, not {@code ar planet list} renderings. The
     * scenario's subject is the dimension a generate ADDED, never the caption a planet line carries,
     * and the two lists come from the same place — {@code PlanetListCommand:28} iterates the very
     * registry {@code stellurgyDimensions} reports.</p>
     *
     * <p>The {@code -1} stays and is checked by both callers: "no new dimension appeared" is a real
     * outcome of the command under test, not a reader that failed.</p>
     */
    private static int newDimFromDiff(String before, String after) {
        Set<Integer> beforeIds = new HashSet<>();
        for (int dim : Reply.of("stellurgytest dim list", before).intArray("stellurgyDimensions")) {
            beforeIds.add(dim);
        }
        for (int dim : Reply.of("stellurgytest dim list", after).intArray("stellurgyDimensions")) {
            if (!beforeIds.contains(dim)) {
                return dim;
            }
        }
        return -1;
    }

    // ── the CLIENT's own event log ────────────────────────────────────────────
    //
    // Every scenario here types a command and then waits for its outcome to reach the CLIENT — a
    // reply on the chat overlay, a slot in the inventory it draws, a world it is respawned into — so
    // they read the base's {@link #clientEvents()}, not {@link #serverEvents()}, which is the server's log.

    /**
     * How long the far side of a typed command may take to reach the client — a deadline for a
     * discrete event, in the same ballpark as the 100/200-tick polls it replaces.
     */
    private static final int REPLY_BUDGET_TICKS = 200;

    /**
     * Wait for a chat line carrying {@code needle} to reach the client's HUD, from a mark taken
     * before the command was typed.
     *
     * <p>This is what makes {@code armChatObservation} unnecessary for these scenarios. The old form
     * searched the last N lines of the overlay, a window it did not control — this tier shares one
     * client, and the harness used to add a broadcast completion sentinel per server command on top
     * of that, so the backlog had to be drained and proved empty before the stimulus. The sentinel
     * is gone with the server's control socket; a mark handles the rest by construction, since
     * everything before it is invisible.</p>
     */
    private void awaitChatContaining(Events events, long mark, String needle) throws Exception {
        String lowered = needle.toLowerCase(Locale.ROOT);
        // A chat line is PROSE, so the match stays a substring — there is no field here whose value
        // is the sentence. What the link buys over the loop it replaces is the failure: the four
        // causes of an empty log are told apart, which a bare "nothing arrived" cannot do.
        events.awaitMatching(mark, "client_chat_received",
                reply -> reply.toLowerCase(Locale.ROOT).contains(lowered),
                "carrying \"" + needle + "\"",
                "the command's reply must reach the player's chat", REPLY_BUDGET_TICKS);
    }

    /** Counts stacks of {@code itemId} in the CLIENT-rendered main inventory. */
    private int countClientItems(String itemId) throws Exception {
        JsonObject items = bot().reportPlayerItems();
        int count = 0;
        JsonArray main = items.getAsJsonArray("main");
        for (int i = 0; i < main.size(); i++) {
            if (itemId.equals(main.get(i).getAsJsonObject().get("id").getAsString())) {
                count += main.get(i).getAsJsonObject().get("count").getAsInt();
            }
        }
        return count;
    }

    private static double extractDouble(String src, String field) {
        double value = Reply.of(src).number(field);
        return value;
    }

    // ── /ar station give ──────────────────────────────────────────────────────

    /** From {@code WorldCommandPlayerEquippedE2ETest}: the chip must appear in the inventory the
     *  CLIENT draws — that is what the player sees when he opens his inventory screen. */
    @Test
    public void stellurgyGiveStationAddsChipToPlayerInventory() throws Exception {
        scenario().arranging("op the bot and create a station for the chip to bind to");
        opTheBot();
        String create = arrange("stellurgytest station create " + plot().dim).raw();
        Reply created = Reply.of("stellurgytest station create", create);
        scenario().requireArranged("station create response must include id: " + create,
                created.has(STATION_ID));
        int stationId = created.integer(STATION_ID);
        scenario().record("stationId", stationId);

        // The shared reset clears the inventory, so this is a control rather than a hope: a chip
        // already in hand would make the poll below pass with no command behind it.
        scenario().measuring("the client-rendered inventory holds no station chip yet");
        int baseline = countClientItems("stellurgy:spacestationchip");
        scenario().requireArranged("baseline: the client inventory must hold no station chip, "
                + "or the verdict below is satisfiable without the command; saw " + baseline,
                baseline == 0);

        scenario().asserting("the chip appears in the inventory the client draws");
        Events clientLog = clientEvents();
        long mark = clientLog.mark();
        bot().sendChat("/ar station give " + stationId);

        // THE LINK: the server wrote the chip into a slot and the client APPLIED that write. The old
        // form re-counted the rendered inventory on a tick budget and reported "client count=0" for
        // a command that was refused, a slot packet that never came and a slow round trip alike.
        String slotWrites = clientLog.awaitField(mark, "client_slot_set",
                "item", "stellurgy:spacestationchip",
                "/ar station give must put a station chip into a slot the client draws",
                REPLY_BUDGET_TICKS);
        scenario().record("slotWrite", slotWrites);

        int count = countClientItems("stellurgy:spacestationchip");
        assertTrue("/ar station give must add a station chip to the bot's client-rendered "
                + "inventory; client count=" + count + " (the slot write was recorded: "
                + slotWrites + ")", count >= 1);

        String post = read("stellurgytest player inventory-contains stellurgy:spacestationchip").raw();
        assertTrue("server inventory must also contain the chip: " + post,
                !(Reply.of(post).integer("count") == 0));
    }

    // ── /ar goto dimension ────────────────────────────────────────────────────

    /** From {@code WorldCommandPlayerEquippedE2ETest}: the client must end up rendering the target
     *  dim. The base class's reset brings the player home afterwards. */
    @Test
    public void stellurgyGotoTransfersPlayerToTargetDim() throws Exception {
        scenario().arranging("op the bot and generate a planet to travel to");
        opTheBot();
        String before = read("stellurgytest dim list").raw();
        arrange("ar planet generate 0 GotoTarget");
        String after = read("stellurgytest dim list").raw();
        int targetDim = newDimFromDiff(before, after);
        scenario().record("targetDim", targetDim);
        scenario().requireArranged("planet generate must yield a new dim id; before=" + before
                + " after=" + after, targetDim != -1);
        try {
            arrange("stellurgytest dim load " + targetDim);

            scenario().asserting("the client renders the target dim after the command");
            Events clientLog = clientEvents();
            long mark = clientLog.mark();
            bot().sendChat("/ar goto dimension " + targetDim);
            awaitClientDim(mark, targetDim,
                    "the server half is read below and would agree about a world the client never"
                            + " reached");

            String health = read("stellurgytest player health").raw();
            assertTrue("server must agree the player is in dim " + targetDim + ": " + health,
                    String.valueOf(targetDim).equals(Reply.of(health).text("dim")));
        } finally {
            // The dimension itself must go: a generated planet outlives the scenario and the next
            // one's `ar planet list` diff would see it. The player's own return is the reset's.
            arrange("stellurgytest tp " + plot().dim).raw();
            arrange("ar planet delete " + targetDim);
        }
    }

    /**
     * A planet the server deletes leaves no Forge dimension behind on a client connected to it
     * remotely. That client registered the dimension itself when it first heard of the planet, so
     * nobody else will withdraw it, and a body later made on the same id would keep the deleted one's
     * dimension type.
     *
     * <p>The client's own record of the deletion ({@code client_dim_forgotten}) carries whether
     * Forge's registry in the client JVM still holds the id once the client is done with it. The
     * harness client is always remote (a separate server JVM), so this does not see the single-player
     * branch, where the registration is the integrated server's and must be left alone.</p>
     */
    @LegacyWitness(prose = "with {@code PacketDimInfo#executeClient} at"
            + " {@code if (dev.stannismod.stellurgy.client.ServerView.current().remote())} never taken — every"
            + " client only forgetting, the shape it shipped with — this fails with \"the client forgot the"
            + " deleted planet but its Forge registry still holds dimension 14: {...client_dim_forgotten,dim:14,"
            + " withdraw:false,forgeRegistered:true}\" (2026-10-06).")
    @LegacyWitness(prose = "with {@code PacketDimInfo#executeClient} at"
            + " {@code DimensionManager.getInstance().registerDimNoUpdate(dimProperties, true);} removed, this fails"
            + " at \"the client never learned of the generated planet — no `client_dim_registered` carrying dim = 14\";"
            + " with its {@code if (deleteDim)} branch never taken, at \"the client was never told the planet was"
            + " deleted — no `client_dim_forgotten` carrying dim = 14\" (2026-10-06).")
    @Test
    public void aDeletedPlanetLeavesNoForgeDimensionOnARemoteClient() throws Exception {
        scenario().arranging("op the bot and generate a planet the client learns of");
        opTheBot();
        Events clientLog = clientEvents();
        long heard = clientLog.mark();
        String before = read("stellurgytest dim list").raw();
        arrange("ar planet generate 0 WithdrawTarget");
        String after = read("stellurgytest dim list").raw();
        int targetDim = newDimFromDiff(before, after);
        scenario().record("targetDim", targetDim);
        scenario().requireArranged("planet generate must yield a new dim id; before=" + before
                + " after=" + after, targetDim != -1);
        clientLog.awaitRecordWithFields(heard, "client_dim_registered",
                "the client never learned of the generated planet", REPLY_BUDGET_TICKS,
                "dim", String.valueOf(targetDim), "registered", "true");
        scenario().requireArranged("the client must have registered the planet with Forge itself, or"
                        + " there is nothing for the deletion to withdraw (a planet with no surface is"
                        + " never registered)",
                ClientDimensions.forgeRegistered(bot(), targetDim));

        scenario().asserting("after the deletion the client's Forge registry no longer holds the id");
        long deleted = clientLog.mark();
        arrange("ar planet delete " + targetDim);
        String forgotten = clientLog.awaitRecordWithFields(deleted, "client_dim_forgotten",
                "the client was never told the planet was deleted", REPLY_BUDGET_TICKS,
                "dim", String.valueOf(targetDim));
        assertEquals("the client forgot the deleted planet but its Forge registry still holds dimension "
                + targetDim + ": " + forgotten, "false", Events.text(forgotten, "forgeRegistered"));
    }

    /**
     * The planet's own world type has to reach the CLIENT, because client-side terrain code
     * identifies a world by it — and a secondary world's {@code WorldInfo} used to answer with the
     * SAVE's world type, so every planet a player entered claimed to be the overworld's kind.
     *
     * <p>The overworld's value is read first, in this same scenario, and the assertion is that the
     * value CHANGED on crossing. Asserting the planet's name alone would also pass on a build that
     * hard-codes one world type everywhere, which is the failure this is about.</p>
     */
    @Test
    public void stellurgyGotoMakesTheClientRenderThePlanetsOwnWorldType() throws Exception {
        scenario().arranging("op the bot and generate a planet to travel to");
        opTheBot();
        String home = clientWorldType();
        scenario().record("homeWorldType", home);
        scenario().requireArranged("the client must name the world type it starts in, else the"
                + " comparison below has nothing to change FROM; got '" + home + "'", !home.isEmpty());

        String before = read("stellurgytest dim list").raw();
        arrange("ar planet generate 0 WorldTypeTarget");
        String after = read("stellurgytest dim list").raw();
        int targetDim = newDimFromDiff(before, after);
        scenario().record("targetDim", targetDim);
        scenario().requireArranged("planet generate must yield a new dim id; before=" + before
                + " after=" + after, targetDim != -1);
        try {
            arrange("stellurgytest dim load " + targetDim);

            scenario().asserting("the client renders the planet's own world type after arriving");
            Events clientLog = clientEvents();
            long mark = clientLog.mark();
            bot().sendChat("/ar goto dimension " + targetDim);
            awaitClientDim(mark, targetDim,
                    "the world type read next is the CLIENT's, so until he has arrived it is the"
                            + " world he LEFT");

            String onPlanet = clientWorldType();
            scenario().record("planetWorldType", onPlanet);
            assertEquals("the client must learn the planet's own world type on arrival, not the"
                    + " one the save was created with", AR_PLANET_WORLD_TYPE, onPlanet);
            assertNotEquals("the world type the client renders must differ between the overworld"
                    + " and a planet, or it is not per-dimension at all", home, onPlanet);
        } finally {
            arrange("stellurgytest tp " + plot().dim);
            arrange("ar planet delete " + targetDim);
        }
    }

    /** The world type the CLIENT believes it is in, by name. */
    private String clientWorldType() throws Exception {
        JsonObject state = bot().reportState();
        return state != null && state.has("worldType") ? state.get("worldType").getAsString() : "";
    }

    // ── /ar goto station ──────────────────────────────────────────────────────

    /** From {@code WorldCommandPlayerEquippedE2ETest}: a station's spawn is in the space dim, and
     *  the client must end up rendering it. */
    @Test
    public void stellurgyGotoStationTeleportsToStationSpawnInSpaceDim() throws Exception {
        scenario().arranging("op the bot and create a station to travel to");
        opTheBot();
        String create = arrange("stellurgytest station create " + plot().dim).raw();
        Reply created = Reply.of("stellurgytest station create", create);
        scenario().requireArranged("station create must succeed: " + create,
                created.has(STATION_ID));
        int stationId = created.integer(STATION_ID);
        scenario().record("stationId", stationId);

        arrange("stellurgytest dim load " + SPACE_DIM);

        scenario().asserting("the client renders the space dim after the command");
        Events clientLog = clientEvents();
        long mark = clientLog.mark();
        bot().sendChat("/ar goto station " + stationId);
        awaitClientDim(mark, SPACE_DIM,
                "the command's whole claim is that it renders the station's world for him");
    }

    // ── /ar fetch ─────────────────────────────────────────────────────────────

    /**
     * From {@code WorldCommandFetchTest}. {@code /ar fetch <unknown-name>} must surface vanilla's
     * "player cannot be found" error ON THE PLAYER'S CHAT OVERLAY (i18n resolved) without crashing.
     * Pins the {@code getPlayer -> PlayerNotFoundException} branch at the layer the player reads it.
     */
    @Test
    public void fetchUnknownNameReportsInvalidPlayerName() throws Exception {
        scenario().arranging("op the bot");
        opTheBot();
        String bogus = "_no_such_player_xyz_";

        scenario().measuring("mark the client's chat log immediately before typing");
        Events clientLog = clientEvents();
        long mark = clientLog.mark();

        scenario().asserting("the player-not-found error reaches the player's chat");
        bot().sendChat("/ar fetch " + bogus);
        awaitChatContaining(clientLog, mark, "cannot be found");
    }

    /**
     * From {@code WorldCommandFetchTest}. Self-fetch: with sender == target the command still runs
     * the WHOLE transfer — {@code changeDimension} with a {@code BasicTeleporter} bound for the
     * dimension the player is already in — and the teleporter puts him on the centre of his own
     * block, so the verb must complete and leave him where he was to within that half-block. (It is
     * not the "same-dim no-op" this javadoc used to claim; the recorded placement below says so.)
     * Positive coverage of the whole resolve &rarr; transfer &rarr; placement path without a second
     * bot (the harness is single-client; fetching a DIFFERENT connected player stays out of scope).
     */
    @LegacyWitness(prose = "with {@code FetchCommand#execute} at {@code new"
            + " BasicTeleporter(destPlayer.getPosition())} aimed 20 blocks east, the wait for the client's"
            + " placement at the teleporter's target passes and this fails on the position assertion with"
            + " \"preX=4096.5 … postX=4116.5\" (2026-09-30).")
    @Test
    public void selfFetchCompletesAndPreservesPosition() throws Exception {
        scenario().arranging("op the bot and read its own username back");
        opTheBot();
        String botName = botName();
        assertNotEquals("bot name must be non-empty", "", botName);
        scenario().record("botName", botName);

        scenario().measuring("the CLIENT-observed position before the command");
        JsonObject pre = bot().reportState();
        double preX = pre.get("playerX").getAsDouble();
        double preZ = pre.get("playerZ").getAsDouble();
        scenario().record("beforeXZ", preX + "," + preZ);

        scenario().asserting("a self-fetch leaves the client where it was");
        // A self-fetch is NOT a no-op, whatever the method javadoc above says: the command calls
        // changeDimension with a BasicTeleporter even when the destination is the dimension the
        // player is already in, so the whole transfer runs — a teleporter placement on the server
        // and a respawn on the client. Both marks go before the command; the twenty ticks that used
        // to stand here were budgeting exactly that round trip.
        Events serverLog = serverEvents();
        long serverMark = serverLog.markInstrumented();
        Events clientLog = clientEvents();
        long clientMark = clientLog.mark();
        bot().sendChat("/ar fetch " + botName);

        String placed = serverLog.awaitField(serverMark, "teleporter_placed", "who", botName,
                "a self-fetch must still run the transfer: the teleporter places the body",
                REPLY_BUDGET_TICKS);
        scenario().record("selfFetchPlacement", placed);
        awaitClientDim(clientMark, plot().dim,
                "the rendered position read below belongs to the world he ends in");
        // The respawn is not the arrival. Vanilla's handleRespawn builds a NEW client player and
        // stands it at the world spawn (Minecraft.setDimensionAndSpawnPlayer ->
        // preparePlayerToSpawn); the position the server chose comes in the NEXT packet. Read
        // between the two, the client reports 8.5, 8.5 — which is what this assertion used to
        // measure under load. So the read waits for the client to APPLY a placement at the
        // teleporter's own target, and the verdict below still compares against where he stood
        // before: a fetch that aimed elsewhere passes this wait and fails that assertion.
        String target = Events.firstField(placed, "target");
        assertNotNull("teleporter_placed must name its target: " + placed, target);
        String[] t = target.split(",");
        awaitClientPlacedNear(clientMark, Double.parseDouble(t[0]) + 0.5, Double.parseDouble(t[2]) + 0.5,
                "the position read below is the one the server chose, not the respawn placeholder");

        JsonObject post = bot().reportState();
        double postX = post.get("playerX").getAsDouble();
        double postZ = post.get("playerZ").getAsDouble();
        // The placement record carries the teleporter's own `from` and `target`, which is what
        // separates "the command aimed somewhere else" from "it aimed here and something moved him
        // afterwards" — the two stories this assertion could not tell apart while `placed` sat in
        // the scenario journal and not in the message.
        String where = " preX=" + preX + " preZ=" + preZ + " postX=" + postX + " postZ=" + postZ
                + "\n  teleporter_placed: " + placed;
        assertTrue("self-fetch must leave bot within 1 block of its prior position:" + where,
                Math.abs(postX - preX) < 1.0);
        assertTrue("self-fetch must leave bot within 1 block of its prior position:" + where,
                Math.abs(postZ - preZ) < 1.0);

        String postServer = read("stellurgytest player health").raw();
        assertTrue("server-side X must agree with the client view: " + postServer,
                Math.abs(extractDouble(postServer, POS_X) - postX) < 1.0);
    }

    // ── Ship 1 ─────────────────────────────────────────────────────────────────

    /**
     * The deadline of every link on a record Ship 1's build publishes. The assembly is synchronous in the
     * command, so these records come within a few ticks; this is a link's deadline, never a verdict.
     */
    private static final int SHIP_ONE_LINK_TICKS = 200;

    /**
     * The window over which a drained store must be seen to refill from the ship's own source. Any rise
     * is the verdict, so the span only has to hold some ticks of a creative source's push.
     */
    private static final int SHIP_ONE_REFILL_TICKS = 20;

    /** What one build of Ship 1 left: the ship as both of its names, its flight computer aboard, its world. */
    private static final class ShipOne {
        final int dim;
        final String physicsId;
        final String durable;
        final int afcX;
        final int afcY;
        final int afcZ;
        /** Whether the ship's box holds the point the command put its operator at. */
        final boolean holdsItsOperator;
        /** The server's own seq of the build's naming: what every later link in the scenario is read from. */
        final long mark;

        ShipOne(int dim, String physicsId, String durable, int afcX, int afcY, int afcZ, boolean holdsItsOperator,
                long mark) {
            this.dim = dim;
            this.physicsId = physicsId;
            this.durable = durable;
            this.afcX = afcX;
            this.afcY = afcY;
            this.afcZ = afcZ;
            this.holdsItsOperator = holdsItsOperator;
            this.mark = mark;
        }

        /** A part aboard, by the name the fixture publishes, as a probe addresses a block: {@code x y z}. */
        String part(String name) {
            net.minecraft.util.math.Vec3i d =
                    dev.stannismod.stellurgy.command.sub.FixtureCommand.Ship1.partsFromComputer().get(name);
            return (afcX + d.getX()) + " " + (afcY + d.getY()) + " " + (afcZ + d.getZ());
        }

        String afc() {
            return afcX + " " + afcY + " " + afcZ;
        }
    }

    /**
     * Stand the opped player on a floor in his plot facing south, have him type {@code /stellurgy fixture
     * ship-1}, and resolve the ship it built: the craft named after the command (its {@code ship_lifecycle}
     * record), its flight computer aboard (the first {@code flight_model_changed} under its durable name),
     * and whether its box holds the point the server has the player at once it is built.
     *
     * <p>The build lands inside the plot: the command clears a box of about 13 by 15 blocks ahead of the
     * player, and the site stands {@code Plot.FIXTURE_INSET} in from every edge of a {@code Plot.SIZE} plot.</p>
     */
    private ShipOne buildShipOne(String what) throws Exception {
        opTheBot();
        dev.stannismod.stellurgy.test.FixtureSite site = site();
        Reply floor = arrange("stellurgytest place " + site.dim + " " + site.x + " " + (site.y - 1) + " "
                + site.z + " minecraft:stone");
        scenario().requireArranged(what + " — a floor must be laid under the operator: " + floor,
                floor.ok() && floor.bool("placed"));
        standOnFloorTheClientHolds(site.x + 0.5D, site.y, site.z + 0.5D, 0F, 0F, what);

        long mark = serverEvents().markInstrumented();
        bot().sendChat("/stellurgy fixture ship-1");
        String named = serverEvents().awaitRecordWithFields(mark, "ship_lifecycle",
                what + " — the command must name the ship it built", SHIP_ONE_LINK_TICKS, "edge", "named");
        String physicsId = Events.text(named, "ship");
        String durable = Events.text(named, "durable");
        disposeOfShipAtEnd(physicsId);
        String model = serverEvents().awaitRecordWithFields(mark, "flight_model_changed",
                what + " — the ship's flight computer must build its first flight model", SHIP_ONE_LINK_TICKS,
                "ship", durable);
        PlayerState operator = PlayerState.read(c -> read(c).raw());
        Reply contains = read("stellurgytest vs ship-contains " + operator.dim + " " + physicsId + " " + operator.x
                + " " + operator.y + " " + operator.z);
        return new ShipOne(operator.dim, physicsId, durable, (int) Events.number(model, "afcX"),
                (int) Events.number(model, "afcY"), (int) Events.number(model, "afcZ"), contains.bool("contains"),
                (long) Events.number(named, "seq"));
    }

    /**
     * Ship 1 is built around its operator and can make every move (commands-gameplay INV-CMD-09, -10).
     *
     * <p>Every verdict reads the ship the command built, found by the point it put the player at. The
     * readout is the flight computer's live model; the drive is production's own drive survey and window.
     * The bank's refill is a WINDOW: the probe drains it first, so a rise across the window can only come
     * from what the ship carries — its own creative plug.</p>
     *
     * <p>What it does not see: whether the ship flies as its readout says (the flight model's own clauses),
     * a jump actually taken (no destination is set), and anything the player is shown.</p>
     */
    @Test
    @Pins({"INV-CMD-09", "INV-CMD-10"})
    @Wait(shape = Wait.Shape.WINDOW, at = "Window<Reply> bank = Window.over(",
            why = "the bank's charge converges and publishes no record of filling; the verdict is that it rose,"
                    + " judged over both reads")
    @RedWitness(verdict = "aboard", member = "FixtureCommand#execute",
            at = "player.connection.setPlayerLocation(cabin.getX() + 0.5D, cabin.getY(), cabin.getZ() + 0.5D, 180F, 0F);",
            replacedBy = "", fails = "INV-CMD-09", on = "2026-10-27")
    @RedWitness(verdict = "moves", member = "Ship1#propulsion",
            at = "set(world, o, x == 2 ? 1 : 7, 3, z, thruster(x == 2 ? EnumFacing.EAST : EnumFacing.WEST));",
            replacedBy = "", fails = "INV-CMD-10: Ship 1 must be able to move", on = "2026-10-27")
    @RedWitness(verdict = "refill", member = "Ship1#jumpBay",
            at = "world.setBlockState(capacitor.up(), LibVulpesBlocks.blockCreativeInputPlug.getDefaultState());",
            replacedBy = "", fails = "INV-CMD-10: Ship 1's own source must refill its bank", on = "2026-10-27")
    public void shipOneIsBuiltAroundItsOperatorAndCanMakeEveryMove() throws Exception {
        String what = "Ship 1, typed by its operator";
        ShipOne ship = buildShipOne(what);
        assertTrue("INV-CMD-09: the ship the command built holds the point it put its operator at — ship "
                + ship.physicsId, ship.holdsItsOperator);

        Reply found = read("stellurgytest vs flight-model-by-id " + ship.dim + " " + ship.physicsId);
        scenario().requireArranged(what + " — its flight computer must hold a model: " + found,
                found.bool("found") && found.object("model") != null);
        Reply model = Reply.of(found.object("model"));
        Reply directions = Reply.of(model.object("directions"));
        for (dev.stannismod.stellurgy.ship.control.ControlDirection d
                : dev.stannismod.stellurgy.ship.control.ControlDirection.values()) {
            Reply live = Reply.of(Reply.of(directions.object(d.name())).object("live"));
            assertTrue("INV-CMD-10: Ship 1 must be able to move " + d + " — " + live, live.number("burst") > 0D);
        }
        assertTrue("INV-CMD-10: Ship 1 must be able to move — hover where it was built: " + model,
                model.bool("canHoverLIVE"));

        // The drive family is filed as ARRANGE as a whole, its `info` read included.
        Reply drive = arrange("stellurgytest drive info " + ship.dim + " " + ship.afc());
        assertTrue("INV-CMD-10: Ship 1's drive must have a bank and a hull emitter aboard, and no hull outside"
                        + " its window — " + drive,
                drive.bool("afc") && drive.number("capacitors") >= 1D && drive.number("emitters") >= 1D
                        && drive.bool("hullMeasured") && drive.number("hullOutsideWindow") == 0D);
        Reply drained = arrange("stellurgytest drive charge " + ship.dim + " " + ship.afc() + " empty");
        scenario().requireArranged(what + " — the bank must be drained before its refill is watched: " + drained,
                drained.ok() && drained.number("charge") == 0D);
        Window<Reply> bank = Window.over(() -> arrange("stellurgytest drive info " + ship.dim + " " + ship.afc()),
                serverClient(), GameTicks.world(ship.dim), worldAndClient(ship.dim), SHIP_ONE_REFILL_TICKS);
        assertTrue("INV-CMD-10: Ship 1's own source must refill its bank — " + bank,
                bank.change(r -> r.number("charge")) > 0D);
    }

    /**
     * Ship 1 is armed and shielded (commands-gameplay INV-CMD-11).
     *
     * <p>Each gun's verdict is its own walk of its build, read off {@code turret_assembled} at the gun's
     * address aboard; the console's count is read once after the weapon network seeded its orders for the
     * component holding this console, from a mark no earlier than the later gun's walk, so both guns had
     * registered. Each shield emitter is drained by the probe and must then be seen to refill across a
     * WINDOW from the ship's own generator and source.</p>
     *
     * <p>What it does not see: a gun firing (nothing is targeted), the shield's field lighting (a threshold
     * of the coil, which a refill only approaches), and what the player is shown.</p>
     */
    @Test
    @Pins("INV-CMD-11")
    @Wait(shape = Wait.Shape.WINDOW, at = "Window<Reply> foreCoil = coilRefill(",
            why = "an emitter's coil converges and publishes no record of filling; the verdict is that it rose,"
                    + " judged over both reads")
    @Wait(shape = Wait.Shape.WINDOW, at = "Window<Reply> starboardCoil = coilRefill(",
            why = "an emitter's coil converges and publishes no record of filling; the verdict is that it rose,"
                    + " judged over both reads")
    @RedWitness(verdict = "armed", member = "Ship1#roof",
            at = "world.setBlockState(at.up(), StellurgyBlocks.blockGunBeamEmitter.getDefaultState());",
            replacedBy = "", fails = "INV-CMD-11: each of Ship 1's guns must be operable", on = "2026-10-27")
    @RedWitness(verdict = "shielded", member = "Ship1#roof",
            at = "world.setBlockState(generator.up(), AdvancedForceFieldSystem.BLOCK_ADMIN_ENERGY_SOURCE.getDefaultState());",
            replacedBy = "", fails = "INV-CMD-11: Ship 1's own source must feed", on = "2026-10-27")
    public void shipOneIsArmedAndShielded() throws Exception {
        String what = "Ship 1, typed by its operator";
        ShipOne ship = buildShipOne(what);

        Reply port = gunWalked(ship, "turretPort", what);
        assertTrue("INV-CMD-11: each of Ship 1's guns must be operable — turretPort " + port, port.bool("operable"));
        Reply starboard = gunWalked(ship, "turretStarboard", what);
        assertTrue("INV-CMD-11: each of Ship 1's guns must be operable — turretStarboard " + starboard,
                starboard.bool("operable"));

        String console = ship.part("weaponConsole");
        long laterGun = (long) Math.max(port.number("seq"), starboard.number("seq"));
        serverEvents().awaitRecordWithFields(laterGun, "weapon_orders_seeded",
                what + " — the weapon network must seed its orders for the component holding its console",
                SHIP_ONE_LINK_TICKS, "consoles", console.replace(' ', ','));
        Reply network = read("stellurgytest weaponconsole read " + ship.dim + " " + console);
        assertEquals("INV-CMD-11: Ship 1's weapon console must command both its guns — " + network,
                2D, network.number("guns"), 0D);

        Window<Reply> foreCoil = coilRefill(ship, "shieldEmitterFore", what);
        assertTrue("INV-CMD-11: Ship 1's own source must feed its shieldEmitterFore — " + foreCoil,
                foreCoil.change(r -> r.number("shieldStored")) > 0D);
        Window<Reply> starboardCoil = coilRefill(ship, "shieldEmitterStarboard", what);
        assertTrue("INV-CMD-11: Ship 1's own source must feed its shieldEmitterStarboard — " + starboardCoil,
                starboardCoil.change(r -> r.number("shieldStored")) > 0D);
    }

    /** The {@code turret_assembled} record of the gun named {@code gun} aboard {@code ship}: a link from the build's naming. */
    private Reply gunWalked(ShipOne ship, String gun, String what) throws Exception {
        return Reply.of(serverEvents().awaitRecordWithFields(ship.mark, "turret_assembled",
                what + " — its " + gun + " must walk its build aboard", SHIP_ONE_LINK_TICKS,
                "pos", ship.part(gun).replace(' ', ',')));
    }

    /**
     * Drain the coil of the emitter named {@code emitter} aboard {@code ship}, then read it across a window
     * of {@link #SHIP_ONE_REFILL_TICKS}: whatever it gained came from what the ship carries.
     */
    @Wait(shape = Wait.Shape.WINDOW, at = "return Window.over(",
            why = "an emitter's coil converges and publishes no record of filling; its caller judges both reads")
    private Window<Reply> coilRefill(ShipOne ship, String emitter, String what) throws Exception {
        String at = ship.part(emitter);
        Reply drained = arrange("stellurgytest shield charge " + ship.dim + " " + at + " 0");
        scenario().requireArranged(what + " — the " + emitter + " coil must be drained before its refill is"
                + " watched: " + drained, drained.ok());
        return Window.over(() -> read("stellurgytest shield read " + ship.dim + " " + at),
                serverClient(), GameTicks.world(ship.dim), worldAndClient(ship.dim), SHIP_ONE_REFILL_TICKS);
    }
}
