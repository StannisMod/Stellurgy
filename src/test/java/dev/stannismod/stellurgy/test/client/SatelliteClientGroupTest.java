package dev.stannismod.stellurgy.test.client;

import dev.stannismod.stellurgy.test.Events;
import dev.stannismod.stellurgy.test.Reply;
import com.google.gson.JsonObject;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/**
 * Satellites as a real client meets them when their type cannot be resolved: a sync packet naming a
 * type the client's registry does not hold, and a deploy whose chassis no longer resolves.
 *
 * <p>NEW-GROUP: the satellite's client-side contracts. No existing client group holds them —
 * {@code ItemRightClickClientGroupTest} is the right-click of a held item, and
 * {@code SpaceSubsystemClientSyncGroupTest} is the space cell, slot and clock; both scenarios here
 * were single-scenario classes, each paying its own client boot.</p>
 *
 * <p>What each scenario leaves in the shared world: the deploy scenario spawns a rocket at the player
 * and seats him in it, and dismounts him before it ends; the rocket stays on its plot, which nothing
 * else visits. Every observation is scoped by a mark taken before its stimulus.</p>
 */
@org.junit.FixMethodOrder(org.junit.runners.MethodSorters.NAME_ASCENDING)
public class SatelliteClientGroupTest extends AbstractSharedClientE2ETest {

    @Override
    protected String subsystem() {
        return "satellite-client";
    }

    /**
     * A satellite sync packet whose type the client cannot resolve is dropped, and the client stays
     * in the world.
     *
     * <p>{@code PacketSatellite.readClient} calls {@code SatelliteRegistry.createFromNBT}; for a type
     * the client's registry does not know (a join with a different mod set) {@code getNewSatellite}
     * answers null, and the NPE used to escape the packet handler and disconnect the client. Now
     * {@code createFromNBT} answers null and {@code readClient} skips it.</p>
     *
     * <p>The absence is only worth as much as the presence beside it: the client's own registry lookup
     * of the bogus type, coming back unresolved, is asserted FIRST — "the client did not disconnect" is
     * also satisfied by a packet that never arrived. Then the disconnects are COUNTED over the window,
     * because a disconnect and a reconnect between two samples read as a client that never left.</p>
     */
    @Test
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
        String announce = exec("stellurgytest satellite announce-unknown 0");
        assertTrue("announce-unknown probe must succeed: " + announce, Reply.of(announce).ok());

        // BOTH fields on ONE record: the type alone is satisfied by a lookup that RESOLVED, the
        // outcome alone by any other type failing to resolve.
        clientEvents().awaitMatching(clientMark, "satellite_nbt_resolved",
                reply -> Events.anyRecordHasAll(reply, "dataType", bogusType, "resolved", "false"),
                "carrying dataType = " + bogusType + " and resolved = false",
                "an unknown satellite type must be LOOKED UP on the client and come back"
                        + " unresolved — that is the branch readClient must then drop",
                decodeBudgetTicks);

        // WINDOW: from the mark to the read below, the disconnect count over `settleTicks` after the
        // decode. A disconnect is the event that must NOT happen, so there is no record to link on;
        // the pre-fix NPE disconnected a tick or two after the decode, well inside this window.
        bot().waitTicks(settleTicks);

        // SILENCE-IS-THE-ANSWER: the disconnect seam runs only on a disconnect, so no instrument check
        // can apply to it; the log is proved listening by the mark (it refuses an unarmed log) and by
        // the mixin-recorded lookup above landing in this same log inside this same window.
        String disconnects = clientEvents().since(clientMark, "client_disconnected");
        assertEquals("an unknown satellite type on the wire must NOT disconnect the client:"
                        + " PacketSatellite.readClient skips the null createFromNBT answers for it. The"
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
        Events events = events();
        long serverMark = events.markInstrumented();
        long clientMark = clientEvents().mark();
        try {
            String resp = exec("stellurgytest satellite deploy-unresolved");
            assertTrue("deploy-unresolved probe must succeed: " + resp, Reply.of(resp).ok());
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
            exec("stellurgytest player dismount");
        }
    }
}
