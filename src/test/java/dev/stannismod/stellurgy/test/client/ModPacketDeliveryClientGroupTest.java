package dev.stannismod.stellurgy.test.client;

import com.google.gson.JsonObject;
import org.junit.FixMethodOrder;
import org.junit.Test;
import org.junit.runners.MethodSorters;

import dev.stannismod.stellurgy.test.Events;
import dev.stannismod.stellurgy.test.Reply;

import static dev.stannismod.stellurgy.test.ArrangementFailure.requireArranged;
import static org.junit.Assert.assertEquals;

/**
 * How the mod's own network channel delivers a packet to the code that executes it, on a real client.
 *
 * <p>NEW-GROUP: the mod channel's delivery on the client — the step between a packet arriving on the
 * network thread and its {@code executeClient} running on the client thread, which every mod packet
 * passes through and no mechanic owns. No client group holds it: every other client class observes
 * what one particular packet DID, never how a packet was handed over.</p>
 */
@FixMethodOrder(MethodSorters.NAME_ASCENDING)
public class ModPacketDeliveryClientGroupTest extends AbstractSharedClientTest {

    private static final String ARMING = "dev.stannismod.stellurgy.test.trace.RespawnPacketArming";
    /** The dimension the respawn carries the player into: the nether, which every server has. */
    private static final int NETHER = -1;
    /**
     * A link's deadline for the respawn being queued and the packet behind it running. Measured
     * 2026-10-03 on the server clock, at the waits' 5-tick read granularity: 5 ticks from the transfer
     * command to the probe packet's record. The deadline is 20 times that, because a dimension change
     * loads a world on the client and that cost varies with the box; it bounds a failure and never
     * decides a pass.
     */
    private static final int LINK_TICKS = 100;

    @Override
    protected String subsystem() {
        return "mod-network";
    }

    /**
     * A mod packet that arrives while a respawn is still waiting to be applied is executed for the
     * player the respawn PRODUCES, in the world he is respawned into.
     *
     * <p><b>The window, and how it is opened on purpose.</b> A respawn packet is handed from the
     * network thread to the client thread's queue, and when it is applied it builds a NEW player in a
     * NEW world. A mod packet that arrives between those two moments is queued behind it. The client
     * handler used to read the player when the packet ARRIVED — on the network thread, while the old
     * player was still the client's — and the packet then ran, after the respawn, against that old
     * player and the world he had left. Nothing in the game holds that window open; a test mixin on
     * the step that queues the respawn ({@code MixinPacketThreadUtilRespawnWindow}) hands a
     * {@code PlayerProbePacket} to the channel's client handler in it, once per arming, and the probe
     * packet records the player it was executed for.</p>
     *
     * <p>What it does not see: the decode on the network thread (the packet is handed to the handler,
     * not sent), and the server's handler, which is the same shape on the other side and needs a
     * server-side respawn to exercise.</p>
     *
     * <p>red-witnessed: with {@code BasePacketHandlerClient#onMessage} at
     * {@code mc.addScheduledTask(() -> message.executeClient(mc.player));} put back to the shape it had
     * — the player read into a local on arrival and that local handed to the task — this fails at "a
     * mod packet queued behind a respawn ran for the player the client had when it ARRIVED, in the
     * world he was leaving ... expected:playerDim=-1 current=true but was:playerDim=0 current=false"
     * (2026-10-03).</p>
     */
    @Test
    public void aModPacketQueuedBehindARespawnRunsForTheNewPlayer() throws Exception {
        Events client = clientEvents();
        int handle = Integer.parseInt(bot().invokeStaticInt(ARMING, "open").get("returned").getAsString());
        long armed = client.mark();
        Reply moved = Reply.of(exec("stellurgytest tp " + NETHER));
        requireArranged("the server would not move the player to the nether: " + moved, moved.ok());

        String queued = client.awaitRecordWithFields(armed, "client_respawn_queued",
                "the client never queued the respawn into the nether, so no packet was handed into its"
                        + " window", LINK_TICKS, "toDim", String.valueOf(NETHER));
        requireArranged("the window was not open: when the respawn was queued the client's player was"
                        + " not still in the world he was leaving: " + queued,
                !String.valueOf(NETHER).equals(Events.text(queued, "playerDim")));

        String ran = client.await(armed, "client_mod_packet_ran",
                "the packet handed in behind the respawn never executed", LINK_TICKS);
        String record = Events.lastRecord(ran);
        System.out.println("FIXTURE mod-packet: respawn queued " + queued + "; packet ran " + record);
        // One verdict over both fields: the player it ran for is the client's current one AND lives in
        // the world the respawn produced. Either half failing is the same defect seen from one side.
        assertEquals("a mod packet queued behind a respawn ran for the player the client had when it"
                        + " ARRIVED, in the world he was leaving: " + record,
                "playerDim=" + NETHER + " current=true",
                "playerDim=" + Events.text(record, "playerDim") + " current=" + Events.text(record, "current"));

        JsonObject closed = bot().invokeStaticInt(ARMING, "close", handle);
        requireArranged("the arming was never spent, so the packet above was handed in by something"
                + " else: " + closed, "1".equals(closed.get("returned").getAsString()));
    }
}
