package com.github.stannismod.forge.testing.mixin;

import net.minecraft.client.Minecraft;
import net.minecraft.client.entity.EntityPlayerSP;
import net.minecraft.client.network.NetHandlerPlayClient;
import net.minecraft.item.ItemStack;
import net.minecraft.network.play.server.SPacketChunkData;
import net.minecraft.network.play.server.SPacketJoinGame;
import net.minecraft.network.play.server.SPacketPlayerPosLook;
import net.minecraft.network.play.server.SPacketRespawn;
import net.minecraft.network.play.server.SPacketSetSlot;
import net.minecraft.network.play.server.SPacketUpdateHealth;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import com.github.stannismod.forge.testing.client.bridge.ForgeTestClientBootstrap;

/**
 * Records the moments the client actually HAS a fact the server sent it — a chunk's blocks, its
 * dimension, an inventory slot, its health, its own position.
 *
 * <h2>Why this is a mixin and not a Forge event</h2>
 *
 * <p>{@code ChunkEvent.Load} is the obvious candidate and it is WRONG here.
 * {@code ChunkProviderClient.loadChunk} constructs an EMPTY chunk, puts it in the map, posts that
 * event, and only afterwards does {@code handleChunkData} call {@code chunk.read(…)} to fill it. A
 * recorder on the event would therefore report "chunk received" about a chunk containing nothing —
 * a witness that is confidently wrong, which is worse than no witness.</p>
 *
 * <p>There is no event after the fill. The TAIL of {@code handleChunkData} is the first instant at
 * which "the client can see these blocks" is true, so that is where this injects. The same holds
 * for the other packets below: Forge posts no client-side event for a slot, a health value or a
 * server-driven teleport, and the TAIL of the handler is the first instant the client's own state
 * reflects the packet.</p>
 *
 * <h2>Why a mixin against code we compile is allowed HERE</h2>
 *
 * <p>The distinction is DIRECTION. A production mixin patches a tree into itself, which is
 * indirection where an edit would do. This one reaches from the HARNESS into the product and ships
 * with the harness: it lives in the test source set, so it is absent from a released jar entirely
 * and a shipped game pays nothing for it. That is the whole point — an observation a test wants must
 * cost production zero, and a log line written into production costs it forever.</p>
 *
 * <h2>The events, all recorded on the CLIENT log, all at a TAIL</h2>
 *
 * <p>Every handler here opens with {@code PacketThreadUtil.checkThreadAndEnqueue}, which on the
 * netty thread re-queues the packet and THROWS {@code ThreadQuickExitException}. That is an
 * exception exit, not a return, so a TAIL injection never fires on the netty thread: each record
 * below is taken exactly once, on the client thread, after the body has run to its single final
 * return. All six targets were read at {@code build/rfg/minecraft-src/…/NetHandlerPlayClient.java}
 * on 2026-09-05: each is declared exactly once (no overload for a name selector to match twice) and
 * none contains an early {@code return}, so "TAIL" is that one final return in every case.</p>
 *
 * <p><b>Ring pressure.</b> The ring holds 256 records per type, so a seam that fires on a per-tick
 * path erases its own history in 13 s. Two of these are genuinely per-change and one is not:</p>
 *
 * <ul>
 *   <li>{@code handleUpdateHealth} is edge-gated on the SERVER ({@code EntityPlayerMP.onUpdate}
 *       sends only when health, food level or saturation-is-zero changed), so no gate is needed
 *       here. It can still record twice with an identical payload, because a saturation-only
 *       change resends the same health and food.</li>
 *   <li>{@code handlePlayerPosLook} fires per server-driven teleport, and every one of them is a
 *       distinct fact that must not be collapsed — so it is NOT gated. A sustained movement
 *       rejection ("moved wrongly", a vehicle-move refusal aboard a moving ship) can therefore
 *       resend it every tick and turn the ring over; the reply's {@code dropped} map is what says
 *       so, and a reader chasing an old teleport must check it.</li>
 *   <li>{@code handleSetSlot} IS chatty and IS gated — see its own note.</li>
 * </ul>
 *
 * <p><b>Instruments.</b> Each seam notes the file-wide {@link #INSTRUMENT} <i>and</i> its own event
 * name, because this mixin config declares no {@code injectors.defaultRequire}: a single injector
 * whose target drifted is a warning, not a fatal, and the class still applies. With one shared
 * instrument name, {@code handleChunkData} — which always runs — would report "the instrument ran"
 * on behalf of five seams that may never have woven. So {@code assertInstrumentRan("client_slot_set")}
 * asks about THAT seam. The instrument names are deliberately the event names; they live in the
 * {@code instruments} array of an {@code event_since} reply, not among its event types.</p>
 *
 * <ul>
 *   <li>{@code chunk_data_applied} — the client can see a chunk's blocks ({@code handleChunkData}).</li>
 *   <li>{@code client_dimension_changed} — the client is IN a dimension ({@code handleRespawn} /
 *       {@code handleJoinGame}).</li>
 *   <li>{@code client_slot_set} — the client's copy of one slot now holds what the server put there
 *       ({@code handleSetSlot}). Payload: {@code window} (−1 = the cursor stack, −2 = the player
 *       inventory by index, else a container window id), {@code slot}, {@code item} (registry name,
 *       or {@code "empty"}), {@code stackSize}. SILENT about a whole-window refresh
 *       ({@code SPacketWindowItems}) and about a slot the handler REJECTED — the vanilla body drops
 *       a set-slot for a window that is not the open container, and this record cannot tell a
 *       stored stack from a dropped one; it says only that the packet was handled. Also SILENT
 *       about a REPEAT: see the change-gate on the seam below.</li>
 *   <li>{@code client_health_updated} — the client player's health and food are what the server
 *       says ({@code handleUpdateHealth}). Payload: {@code health}, {@code food}. SILENT about
 *       saturation (in the packet, not in the pin) and about damage the client PREDICTED locally.</li>
 *   <li>{@code client_pos_look_applied} — the client player has been MOVED by the server
 *       ({@code handlePlayerPosLook}). Payload: {@code x, y, z} are the ABSOLUTE position the player
 *       holds after {@code setPositionAndRotation} — the packet's own numbers are relative for any
 *       axis named in {@code flags}, so the applied value is the one a test can compare;
 *       {@code flags} is the packet's relative-axis set as a JSON array of enum names;
 *       {@code teleportId} is the id the client confirms back, for pairing against a server-side
 *       teleport. SILENT about rotation (yaw/pitch are applied but not recorded) and about the
 *       {@code CPacketConfirmTeleport} reply, which is sent before this TAIL but not observed. A
 *       coordinate that is not finite is written as JSON {@code null} — see {@link #jsonNumber}.</li>
 * </ul>
 *
 * <p>Registered from {@code ForgeTestClientBootstrap}, which itself runs only under
 * {@code -Dforge.test.client=true}. Every seam notes {@link #INSTRUMENT} and its own event name, so
 * a reader of the log can tell "the code ran" from "the log was listening" — per seam, not per
 * file.</p>
 */
@Mixin(NetHandlerPlayClient.class)
public class MixinNetHandlerPlayClient {

    /** One observation point for the file; a seam that ran says so under this name. */
    private static final String INSTRUMENT = "client_play_packet_events";

    @Inject(method = "handleChunkData", at = @At("TAIL"))
    private void forgeTest$recordChunkApplied(SPacketChunkData packet, CallbackInfo ci) {
        ForgeTestClientBootstrap.noteInstrumentEntered(INSTRUMENT);
        ForgeTestClientBootstrap.noteInstrumentEntered("chunk_data_applied");
        ForgeTestClientBootstrap.recordChunkApplied(
                packet.getChunkX(), packet.getChunkZ(), packet.isFullChunk());
    }

    /**
     * The client is now IN a dimension — the far side of every dimension change a test drives.
     *
     * <p>{@code handleRespawn} is what the server's dimension transfer arrives as: the old world is
     * torn down, a new {@code WorldClient} is built for the packet's dimension and the player is put
     * into it. Its TAIL is the first instant "the client's own dimension is N" is true. Sampling
     * {@code mc.world.provider.getDimension()} on a tick budget sees the same fact later, if at all —
     * a client that is torn down and rebuilt twice between two samples shows one change or none.</p>
     *
     * <p>{@code handleJoinGame} carries the same fact for the FIRST world, so a chain that starts
     * from a fresh login reads one record shape throughout.</p>
     */
    @Inject(method = "handleRespawn", at = @At("TAIL"))
    private void forgeTest$recordRespawn(SPacketRespawn packet, CallbackInfo ci) {
        ForgeTestClientBootstrap.noteInstrumentEntered(INSTRUMENT);
        ForgeTestClientBootstrap.noteInstrumentEntered("client_dimension_changed");
        ForgeTestClientBootstrap.recordEvent("client_dimension_changed",
                "\"dim\":" + packet.getDimensionID() + ",\"via\":\"respawn\"");
    }

    @Inject(method = "handleJoinGame", at = @At("TAIL"))
    private void forgeTest$recordJoin(SPacketJoinGame packet, CallbackInfo ci) {
        ForgeTestClientBootstrap.noteInstrumentEntered(INSTRUMENT);
        ForgeTestClientBootstrap.noteInstrumentEntered("client_dimension_changed");
        ForgeTestClientBootstrap.recordEvent("client_dimension_changed",
                "\"dim\":" + packet.getDimension() + ",\"via\":\"join\"");
    }

    /**
     * Last {@code item + stackSize} recorded per {@code (window, slot)} — the change-gate below.
     * An instance field, so it dies with the connection it describes and a re-login starts clean;
     * no initialiser, so nothing is merged into the target's constructors. Touched only from
     * {@code handleSetSlot}, which {@code checkThreadAndEnqueue} guarantees is the client thread,
     * so a plain map needs no lock.
     */
    private java.util.HashMap<Long, String> forgeTest$lastSlotRecorded;

    /**
     * The client's copy of one slot has been written from the server's packet.
     *
     * <p>{@code handleSetSlot} routes on the window id: −1 sets the cursor stack, −2 writes the
     * player inventory by index, 0 with a hotbar slot goes to {@code inventoryContainer}, and any
     * other id is stored only when it matches the OPEN container's window. All branches fall through
     * to the one final return, so TAIL fires once per packet. The record carries the packet's
     * numbers, not the destination's — see the class javadoc for what that leaves unsaid.</p>
     *
     * <p><b>Why this seam is gated.</b> A set-slot is not per-change in the way the packet's name
     * suggests: the server's {@code Container.detectAndSendChanges} compares stacks with
     * {@code areItemStacksEqual}, which includes NBT, so any per-tick NBT churn on a slot resends
     * the packet. In this mod that is the normal case, not a corner: a worn space suit's air is
     * decremented every 10–20 ticks while the atmosphere needs one
     * ({@code AtmosphereNeedsSuit} → {@code ItemSpaceChest.decrementAir}), and a tool loses
     * durability per swing. Ungated, that is one to two records a second and the 256-record ring
     * for this type is gone in two to four minutes of ordinary suited play — taking with it the
     * slot write a test was actually waiting for.</p>
     *
     * <p>The gate suppresses a record whose recorded payload is IDENTICAL to the last one for the
     * same {@code (window, slot)}. Nothing this event SAYS is lost, because the four recorded
     * fields are exactly what is compared. What is lost is REPETITION, and that is the blind spot:
     * a server that re-asserts a slot after a client-side prediction moved it — the same stack
     * arriving a second time to correct the client — records nothing, so this event cannot be used
     * to count set-slot packets or to witness a correction back to a value already seen. A test
     * that needs the correction itself must observe the container, not this.</p>
     */
    @Inject(method = "handleSetSlot", at = @At("TAIL"))
    private void forgeTest$recordSlotSet(SPacketSetSlot packet, CallbackInfo ci) {
        ForgeTestClientBootstrap.noteInstrumentEntered(INSTRUMENT);
        ForgeTestClientBootstrap.noteInstrumentEntered("client_slot_set");
        ItemStack stack = packet.getStack();
        String item = "empty";
        int stackSize = 0;
        if (stack != null && !stack.isEmpty()) {
            stackSize = stack.getCount();
            if (stack.getItem().getRegistryName() != null) {
                item = stack.getItem().getRegistryName().toString();
            }
        }
        if (forgeTest$lastSlotRecorded == null) {
            forgeTest$lastSlotRecorded = new java.util.HashMap<Long, String>();
        }
        long key = ((long) packet.getWindowId() << 32) | (packet.getSlot() & 0xFFFFFFFFL);
        // '#' cannot occur in a registry name, so the join is unambiguous.
        String now = item + '#' + stackSize;
        if (now.equals(forgeTest$lastSlotRecorded.put(key, now))) {
            return;
        }
        ForgeTestClientBootstrap.recordEvent("client_slot_set",
                "\"window\":" + packet.getWindowId()
                + ",\"slot\":" + packet.getSlot()
                + ",\"item\":\"" + jsonSafe(item) + "\""
                + ",\"stackSize\":" + stackSize);
    }

    /**
     * The client player's health and food level are now what the server sent.
     *
     * <p>{@code handleUpdateHealth} writes health, food level and saturation straight into the
     * client player and returns; there is no branch. The packet's values are recorded rather than
     * re-read from the player, so the record says what the server SENT even if a later local
     * prediction has already moved the displayed value.</p>
     *
     * <p>Ungated on purpose: the server already sends this only on a change. The one consequence is
     * that two records can carry the same {@code health} and {@code food}, because a change in
     * SATURATION alone also resends the packet and saturation is not recorded.</p>
     */
    @Inject(method = "handleUpdateHealth", at = @At("TAIL"))
    private void forgeTest$recordHealthUpdated(SPacketUpdateHealth packet, CallbackInfo ci) {
        ForgeTestClientBootstrap.noteInstrumentEntered(INSTRUMENT);
        ForgeTestClientBootstrap.noteInstrumentEntered("client_health_updated");
        ForgeTestClientBootstrap.recordEvent("client_health_updated",
                "\"health\":" + packet.getHealth()
                + ",\"food\":" + packet.getFoodLevel());
    }

    /**
     * The client player has been MOVED by the server — a teleport, a dimension arrival, a
     * server-side position correction — and its position is now the applied one.
     *
     * <p>{@code handlePlayerPosLook} resolves each axis (absolute, or relative to the current
     * position when its flag is set), calls {@code setPositionAndRotation}, confirms the teleport,
     * and on the first arrival after a world load also dismisses the download-terrain screen. That
     * last block is conditional but falls through; the method has one final return, so TAIL fires
     * once. The recorded {@code x, y, z} are read from the player AFTER the write — the only
     * numbers a test can compare against a target, because the packet's own are relative for any
     * flagged axis. The player was dereferenced by the body already, so it is non-null here.</p>
     *
     * <p>Not gated, and the class javadoc says what that costs when the server is correcting a
     * client every tick.</p>
     */
    @Inject(method = "handlePlayerPosLook", at = @At("TAIL"))
    private void forgeTest$recordPosLookApplied(SPacketPlayerPosLook packet, CallbackInfo ci) {
        ForgeTestClientBootstrap.noteInstrumentEntered(INSTRUMENT);
        ForgeTestClientBootstrap.noteInstrumentEntered("client_pos_look_applied");
        EntityPlayerSP player = Minecraft.getMinecraft().player;
        StringBuilder flags = new StringBuilder("[");
        for (SPacketPlayerPosLook.EnumFlags flag : packet.getFlags()) {
            if (flags.length() > 1) {
                flags.append(',');
            }
            flags.append('"').append(flag.name()).append('"');
        }
        flags.append(']');
        ForgeTestClientBootstrap.recordEvent("client_pos_look_applied",
                "\"x\":" + jsonNumber(player.posX)
                + ",\"y\":" + jsonNumber(player.posY)
                + ",\"z\":" + jsonNumber(player.posZ)
                + ",\"flags\":" + flags
                + ",\"teleportId\":" + packet.getTeleportId());
    }

    /**
     * A coordinate as a JSON number, at FULL precision on purpose.
     *
     * <p>Deliberately not the harness's own six-significant-figure {@code %.6g}: a coordinate in
     * this mod runs out to ~1.5e6 blocks, where six figures quantise to about ten blocks and a
     * position assertion would be reading the formatter rather than the player. {@code Double}'s
     * own rendering is locale-independent, so there is no comma to fear either.</p>
     *
     * <p>A non-finite value — reachable only from a transform that has already broken, which is
     * exactly the bug this event exists to catch — is written as JSON {@code null}. The bare
     * {@code NaN} it would otherwise print is not JSON, and it would take the whole client event
     * log down with it rather than one record.</p>
     */
    private static String jsonNumber(double v) {
        return (Double.isNaN(v) || Double.isInfinite(v)) ? "null" : Double.toString(v);
    }

    /**
     * JSON-safe for a registry name: the harness has no escaper of its own, and a payload must not
     * lie. Mirrors the consumer's {@code TestTrace.json} — backslashes become slashes, double quotes
     * become single — so the two sides render one shape.
     */
    private static String jsonSafe(String raw) {
        return raw == null ? "" : raw.replace('\\', '/').replace('"', '\'');
    }
}
