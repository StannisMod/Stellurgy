package dev.stannismod.stellurgy.test.client;

import com.google.gson.JsonObject;
import dev.stannismod.stellurgy.test.Events;
import dev.stannismod.stellurgy.test.PlayerState;
import dev.stannismod.stellurgy.test.Reply;
import dev.stannismod.stellurgy.test.contract.Pins;
import dev.stannismod.stellurgy.test.contract.RedWitness;
import dev.stannismod.stellurgy.test.contract.Wait;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/**
 * Satellites as a real client meets them: a satellite the server adds reaching the client's galaxy, a
 * sync packet naming a type the client's registry does not hold, and a deploy whose chassis no longer
 * resolves.
 *
 * <p>NEW-GROUP: the satellite's client-side contracts. No existing client group holds them —
 * {@code ItemRightClickClientGroupTest} is the right-click of a held item, and
 * {@code SpaceSubsystemClientSyncGroupTest} is the space cell, slot and clock; both scenarios here
 * were single-scenario classes, each paying its own client boot.</p>
 *
 * <p>What each scenario leaves in the shared world: the deploy scenario spawns a rocket at the player
 * and seats him in it, and dismounts him before it ends; the rocket, named by the probe's reply, is
 * disposed of by that id when the scenario ends; the satellite the reaching scenario adds is marked dead
 * when it ends, which the server's dimension tick removes. Every observation is scoped by a mark taken
 * before its stimulus.</p>
 */
@org.junit.FixMethodOrder(org.junit.runners.MethodSorters.NAME_ASCENDING)
public class SatelliteClientGroupTest extends AbstractSharedClientTest {

    @Override
    protected String subsystem() {
        return "satellite-client";
    }

    /**
     * A satellite the server adds to the dimension the client stands in is in the client's copy of that
     * dimension, under its own id, once the client has applied the server's {@code PacketSatellite}
     * (INV-NW-11).
     *
     * <p>Contract: this fails if production stops putting the satellite a {@code PacketSatellite} carries
     * into the client's galaxy, or puts it under another dimension.</p>
     *
     * <p>The server's own {@code DimensionProperties#addSatellite} broadcasts the packet; a probe only
     * builds the satellite it is handed ({@code satellite create}). The verdict is read where the client
     * applied the packet ({@code client_satellite_packet_applied}, written as
     * {@code PacketSatellite#executeClient} returns), not by a later read of the galaxy: a satellite the
     * client lacks also arrives with the dimension's periodic update, and a later read would answer for
     * whichever of the two came first.</p>
     *
     * <p>What it does not see: the periodic update's own path ({@code PacketSatellitesUpdate}); a satellite
     * of a dimension the client's galaxy does not hold; and a packet decoded before the client has a galaxy
     * at all, which no arrangement of this harness can time.</p>
     *
     * <p>Leaves nothing: the satellite is marked dead when the scenario ends, and the server's tick of its
     * dimension removes a dead ticking satellite — an optical one ticks.</p>
     */
    @Pins("INV-NW-11")
    @RedWitness(member = "PacketSatellite#executeClient", at = "prop.addSatellite(satellite);", replacedBy = "",
            fails = "the dimension the server added it to, and only that one, must hold it in the client's galaxy",
            on = "2026-10-17")
    @Test
    public void aSatelliteTheServerAddsIsInTheClientsCopyOfItsWorld() throws Exception {
        // A type production registers (Stellurgy's registerSatellite("optical", SatelliteOptical.class)), and
        // one that ticks (SatelliteBase#canTick), so marking it dead is enough for the server to remove it.
        final String type = "optical";
        // The deadline of one server-to-client packet being applied on the client's game thread. Expiry means
        // the packet was never applied, which the failure says; it is no verdict.
        final int appliedBudgetTicks = 200;
        final int dim = plot().dim;

        scenario().arranging("the server adds an optical satellite to the dimension the client stands in");
        long mark = clientEvents().mark();
        Reply created = arrange("stellurgytest satellite create " + dim + " " + type);
        scenario().requireArranged("the server must add the satellite: " + created,
                created.has("id") && created.integer("dim") == dim);
        String id = created.text("id");
        try {
            scenario().asserting("the client's galaxy holds the satellite, in its dimension, once the packet ran");
            String applied = clientEvents().awaitRecordWithField(mark, "client_satellite_packet_applied",
                    "satellite", id, "the client must apply the satellite packet the server broadcast",
                    appliedBudgetTicks);
            assertEquals("once the client applied the packet carrying satellite " + id + ", the dimension the"
                            + " server added it to, and only that one, must hold it in the client's galaxy; the"
                            + " record of that moment: " + applied,
                    java.util.Arrays.toString(new int[]{dim}),
                    java.util.Arrays.toString(Reply.of(applied).intArray("heldIn")));
        } finally {
            Reply dead = arrange("stellurgytest satellite set-dead " + dim + " " + id);
            scenario().requireArranged("the scenario's satellite must be marked dead: " + dead, dead.ok());
        }
    }

    /**
     * INV-SAT-17: a player's right-click on a satellite standing in its live cell opens the builder's part
     * panel on it, and a power part put into that panel raises the satellite's generation by the part's own.
     *
     * <p>The satellite is released by the production deploy from a rocket a probe arranged, its cell is held
     * live, and the player is put beside its point on a block laid for him — a cell is a void. The click is
     * the real use key, aimed by the client's own look at the satellite's centre; the part is moved by the
     * container's own slot clicks. The generations are the parts' registered ones — the small panel 4, the
     * large 40 — inputs of the experiment, so the swap must leave 44.</p>
     *
     * <p>What it does not see: the render (how the parts face), which is appearance; the primary function's
     * slot, which the panel shows and refuses; the attitude the client is sent.</p>
     */
    @Pins("INV-SAT-17")
    @RedWitness(verdict = "opens", member = "EntitySatellite#processInitialInteract",
            at = "player.openGui(Stellurgy.instance, GuiHandler.guiId.MODULAR.ordinal(), world, getEntityId(), -1, 0);",
            replacedBy = "", fails = "the right-click must open the satellite's panel", on = "2026-10-21")
    @RedWitness(verdict = "takes effect", member = "OrbitalPartsInventory#store",
            at = "satellite.setProperties(chassis);", replacedBy = "",
            fails = "INV-SAT-17: the swapped part did not take effect", on = "2026-10-21")
    @Wait(shape = Wait.Shape.EXPERIMENT, at = "bot().waitWorldTicks(1);",
            why = "one client tick is the dose by construction: the client re-picks its crosshair at the start"
                    + " of each tick, so the look set before it is the one picked after it; no record names a pick")
    @Wait(shape = Wait.Shape.STIMULUS, at = "advanceServerAndClient(useKeyHeldTicks);",
            why = "the use key is held for a few ticks so the client's keybind poll sees its press edge; the"
                    + " verdict is linked on the server's container record after it")
    @Test
    public void aClickOpensTheSatellitesPanelAndASwappedPartTakesEffect() throws Exception {
        // itemSatellitePrimaryFunction 1 (composition) with itemSatellitePowerSource 0 (the small panel).
        final int composition = 1;
        final int smallPanel = 0;
        // The parts' registered generations (Stellurgy's registerSatelliteProperty): 4 and 40 RF/t.
        final int smallPanelGeneration = 4;
        final int largePanelGeneration = 40;
        // Deadlines for one discrete hand-off each: a respawn, a chunk's satellite standing up, a container
        // opening, a packet applied. Expiry says the thing never happened.
        final int linkBudgetTicks = 200;
        // How long the use key is held: a press edge must be seen by the client's keybind poll.
        final int useKeyHeldTicks = 4;
        // The player stands two blocks along +Z from the satellite, which stands with its feet at its point.
        final double standOff = 2d;
        final double eyeHeight = 1.62d;

        scenario().arranging("a satellite released and standing in its live cell, the player beside it");
        dev.stannismod.stellurgy.test.FixtureSite site = site();
        site.requireClear(c -> arrange(c).raw(), 1, 3, "a rocket holding a guidance computer and one satellite hatch");
        Reply rocket = arrange("stellurgytest satellite rocket-with " + site.dim + " " + site.x + " " + site.y + " "
                + site.z + " line 51.6 " + composition + " " + smallPanel).requireOk("arranging the rocket");
        long satId = rocket.longInteger("satId");
        act("stellurgytest satellite deploy " + rocket.integer("rocketId")).requireOk("deploying the hatch");
        // Removed now, while its chunk is still loaded: the player is about to leave this world, and an
        // unloaded rocket can no longer be named by its entity id.
        dev.stannismod.stellurgy.test.Disposal.rocket(c -> arrange(c).raw(), rocket.integer("rocketId"));
        Reply placed = read("stellurgytest satellite place " + satId);
        scenario().requireArranged("the satellite must be released with a point: " + placed,
                placed.bool("present") && placed.has("point"));
        Reply point = Reply.of(placed.object("point"));
        scenario().requireArranged("its cell must be galactic to be held by its sectors: " + point,
                point.text("cell").indexOf('.') < 0);
        int x = point.integer("x");
        int y = point.integer("y");
        int z = point.integer("z");
        Reply occupied = arrange("stellurgytest space occupy " + point.longInteger("sx") + " "
                + point.longInteger("sy") + " " + point.longInteger("sz")).requireOk("holding the cell live");
        int slotDim = occupied.integer("slotDim");
        try {
            arrange("stellurgytest fill " + slotDim + " " + (x - 1) + " " + (y - 1) + " " + (z + 1) + " " + x + " "
                    + (y - 1) + " " + (z + 2) + " minecraft:stone").requireOk("a block for the player to stand on");
            emptyTheHandOnClient("the hand must hold only the part about to be given");
            String bot = PlayerState.botName(c -> read(c).raw());
            Events server = serverEvents();
            long serverMark = server.mark();
            long clientMark = clientEvents().mark();
            arrange("stellurgytest space enter " + bot + " " + slotDim + " " + x + " " + y + " " + (z + standOff))
                    .requireOk("putting the player beside the satellite");
            awaitClientDim(clientMark, slotDim, "the player must arrive in the satellite's cell");
            // The placement packet sets the look as well as the position: aiming before the client has
            // applied it lets it overwrite the aim.
            awaitClientPlacedNear(clientMark, x, z + standOff, "the player must stand beside the satellite");
            server.awaitRecordWithFields(serverMark, "entity_joined_world",
                    "the satellite must stand up once the player's arrival loads its chunk", linkBudgetTicks,
                    "cls", "EntitySatellite", "dim", String.valueOf(slotDim));
            // The crosshair can only meet what the client holds: the server's entity reaches it with the tracker.
            clientEvents().awaitRecordWithFields(clientMark, "entity_joined_world",
                    "the client must be sent the satellite before it can be aimed at", linkBudgetTicks,
                    "cls", "EntitySatellite", "dim", String.valueOf(slotDim));
            arrange("give " + bot + " stellurgy:satellitePowerSource 1 1");

            // Aimed by the server's own position-look write: a correction the server sends later carries the
            // rotation it holds, which is this one, where a look set on the client alone would be overwritten.
            float pitch = (float) Math.toDegrees(Math.atan2(eyeHeight - 0.5d, standOff));
            long beforeAim = clientEvents().mark();
            arrange("tp " + bot + " " + x + " " + y + " " + (z + standOff) + " 180 " + pitch);
            awaitClientLookApplied(beforeAim, "the client must apply the aim at the satellite");
            // The client picks what its crosshair is on at the start of each of its own ticks, so the
            // look applied just now is picked one client tick later and not before.
            bot().waitWorldTicks(1);
            JsonObject aim = bot().reportMouseOver();
            scenario().requireArranged("the crosshair must be on the satellite before the click: " + aim,
                    aim.has("typeOfHit") && "ENTITY".equals(aim.get("typeOfHit").getAsString()));

            scenario().asserting("the right-click opens the satellite's panel");
            long beforeClick = server.mark();
            bot().input().setKey(-99, true);
            advanceServerAndClient(useKeyHeldTicks);
            bot().input().setKey(-99, false);
            server.awaitRecordWithFields(beforeClick, "container_opened",
                    "the right-click must open the satellite's panel", linkBudgetTicks,
                    "who", bot, "container", "ContainerModular");

            scenario().asserting("a large panel put into the panel raises the satellite's generation");
            JsonObject slots = bot().reportSlots();
            int from = -1;
            int to = -1;
            for (com.google.gson.JsonElement e : slots.getAsJsonArray("slots")) {
                JsonObject slot = e.getAsJsonObject();
                boolean ofPlayer = slot.get("playerSlot").getAsBoolean();
                boolean filled = slot.get("hasStack").getAsBoolean();
                if (ofPlayer && filled && slot.get("item").getAsString().equalsIgnoreCase("stellurgy:satellitePowerSource")) {
                    from = slot.get("slot").getAsInt();
                } else if (!ofPlayer && !filled && to < 0 && slot.get("slot").getAsInt() > 0) {
                    to = slot.get("slot").getAsInt();
                }
            }
            scenario().requireArranged("the panel must show an empty part slot and the player's large panel: "
                    + slots, from >= 0 && to >= 0);
            long beforeSwap = clientEvents().mark();
            bot().clickSlot(from, 0, "PICKUP");
            bot().clickSlot(to, 0, "PICKUP");
            clientEvents().awaitRecordWithField(beforeSwap, "client_satellite_packet_applied", "satellite",
                    String.valueOf(satId), "the swapped part must be sent to the client as the satellite's new make-up",
                    linkBudgetTicks);
            Reply after = read("stellurgytest satellite place " + satId);
            assertEquals("INV-SAT-17: the swapped part did not take effect: " + after,
                    smallPanelGeneration + largePanelGeneration, after.integer("powerGeneration"));
        } finally {
            bot().closeScreen();
            arrange("stellurgytest space release " + point.longInteger("sx") + " " + point.longInteger("sy") + " "
                    + point.longInteger("sz"));
            Reply left = read("stellurgytest satellite place " + satId);
            if (left.bool("present")) {
                arrange("stellurgytest satellite set-dead " + left.integer("dim") + " " + satId);
            }
        }
    }

    /**
     * A satellite sync packet whose type the client cannot resolve is dropped, and the client stays
     * in the world.
     *
     * <p>{@code PacketSatellite.executeClient} calls {@code SatelliteRegistry.createFromNBT}; for a type
     * the client's registry does not know (a join with a different mod set) {@code getNewSatellite}
     * answers null, and the NPE used to escape the packet handler and disconnect the client. Now
     * {@code createFromNBT} answers null and {@code executeClient} skips it.</p>
     *
     * <p>The absence is only worth as much as the presence beside it: the client's own registry lookup
     * of the bogus type, coming back unresolved, is asserted FIRST — "the client did not disconnect" is
     * also satisfied by a packet that never arrived. Then the disconnects are COUNTED over the window,
     * because a disconnect and a reconnect between two samples read as a client that never left.</p>
     */
    @Test
    @Wait(shape = Wait.Shape.WINDOW, at = "advanceServerAndAllClientTicks(settleTicks);",
            why = "a disconnect is the event that must NOT happen, so there is no record to link on; the pre-fix"
                    + " NPE disconnected a tick or two after the decode, well inside this window. The client"
                    + " half counts every tick, world or not: a disconnect is exactly what would take the world"
                    + " away.")
    public void unknownSatelliteTypeOnWireDoesNotCrashClient() throws Exception {
        // The type the probe puts on the wire when none is given — nothing is registered under it.
        final String bogusType = "stellurgy:unregistered.satellite.type.repro";
        // A deadline for one packet's round trip and decode.
        final int decodeBudgetTicks = 120;
        // How long the connection is watched AFTER the decode: the pre-fix NPE escaped on the netty
        // thread and the disconnect followed the decode by a tick or two. A window, not a search.
        final int settleTicks = 60;

        scenario().asserting("an unresolvable satellite type on the wire is dropped by the client");
        long clientMark = clientEvents().mark();
        String announce = act("stellurgytest satellite announce-unknown 0").raw();
        assertTrue("announce-unknown probe must succeed: " + announce, Reply.of(announce).ok());

        // BOTH fields on ONE record: the type alone is satisfied by a lookup that RESOLVED, the
        // outcome alone by any other type failing to resolve.
        clientEvents().awaitMatching(clientMark, "satellite_nbt_resolved",
                reply -> Events.anyRecordHasAll(reply, "dataType", bogusType, "resolved", "false"),
                "carrying dataType = " + bogusType + " and resolved = false",
                "an unknown satellite type must be LOOKED UP on the client and come back"
                        + " unresolved — that is the branch readClient must then drop",
                decodeBudgetTicks);

        advanceServerAndAllClientTicks(settleTicks);

        // SILENCE-IS-THE-ANSWER: the disconnect seam runs only on a disconnect, so no instrument check
        // can apply to it; the log is proved listening by the mark (it refuses an unarmed log) and by
        // the mixin-recorded lookup above landing in this same log inside this same window.
        String disconnects = clientEvents().since(clientMark, "client_disconnected");
        assertEquals("an unknown satellite type on the wire must NOT disconnect the client:"
                        + " PacketSatellite.executeClient skips the null createFromNBT answers for it. The"
                        + " client recorded a disconnection: " + disconnects,
                0, Events.records(disconnects).size());

        JsonObject end = bot().reportState();
        String screen = end.has("screen") ? end.get("screen").getAsString() : "";
        assertTrue("the client left the world after the unknown-type packet: " + end,
                end.get("worldReady").getAsBoolean() && !screen.toLowerCase().contains("disconnect"));
    }

    /**
     * A satellite whose chassis no longer resolves is not deployed silently: the pilot is told.
     *
     * <p>On orbit reach {@code EntityRocket.unpackSatellites} deploys each hatch; a chassis whose
     * {@code getSatellite()} is null and is not a station fell through every branch with no feedback.
     * A rocket reaching orbit with such a chassis is not producible in one config, so the
     * {@code satellite deploy-unresolved} probe seats THIS client on a rocket and calls the public
     * {@code EntityRocket.deploySatelliteFromHatch} with a bare chassis.</p>
     *
     * <p>Two links, one per side, and they fail differently: production choosing to send the notice
     * ({@code chat_message_sent}, carrying the key it chose) and the pilot's client being handed the
     * resolved line ({@code client_chat_received}). A notice composed and never delivered, and one
     * never composed, are different defects.</p>
     */
    @Test
    public void unresolvedSatelliteDeployNotifiesPilot() throws Exception {
        // The message production commits to when a hatch's type no longer resolves.
        final String deployFailedKey = "msg.rocket.satelliteDeployFailed";
        // What the pilot reads once the client resolved the key (en_us: "A satellite could not be
        // deployed: its type is no longer available…").
        final String deployFailedText = "could not be deployed";
        // A deadline for two discrete hand-offs — compose, send, decode, display.
        final int messageBudgetTicks = 100;

        scenario().asserting("a deploy that cannot resolve its satellite tells the pilot");
        // markInstrumented: the server link is recorded by a test-only MIXIN, so an empty log has a
        // second silent cause — the mixin never woven — and both must be ruled out.
        Events events = serverEvents();
        long serverMark = events.markInstrumented();
        long clientMark = clientEvents().mark();
        try {
            String resp = act("stellurgytest satellite deploy-unresolved").raw();
            assertTrue("deploy-unresolved probe must succeed: " + resp, Reply.of(resp).ok());
            disposeOfRocketAtEnd(Reply.of("stellurgytest satellite deploy-unresolved", resp).integer("rocketId"));
            assertTrue("the probe must have mounted the pilot: " + resp, Reply.of(resp).bool("mounted"));

            // Carrying the key, not the type alone: any other message in the window would satisfy a
            // type-only wait.
            events.awaitField(serverMark, "chat_message_sent", "key", deployFailedKey,
                    "an unresolvable satellite chassis must make the rocket TELL its pilot rather than"
                            + " fail silently", messageBudgetTicks);
            // A substring of the `text` FIELD: the line the player sees is assembled by the game, so
            // pinning the whole string would pin the formatting.
            clientEvents().awaitMatching(clientMark, "client_chat_received",
                    reply -> Events.anyRecordFieldContains(reply, "text", deployFailedText),
                    "whose text carries \"" + deployFailedText + "\"",
                    "the notice production sent must reach the pilot's chat as readable text",
                    messageBudgetTicks);
        } finally {
            arrange("stellurgytest player dismount");
        }
    }
}
