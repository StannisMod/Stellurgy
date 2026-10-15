package dev.stannismod.stellurgy.space;

import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.server.MinecraftServer;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.WorldServer;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.UUID;

/**
 * Carries a pre-assembly boarding across the ship assembly. The launch-pad assembler only QUEUES the
 * relocation of a tier-2 craft's blocks into the ship's subspace; the physics mod drains that queue
 * at the end of a later world tick, so at the moment {@code assembleRocket} returns there is nothing
 * to rebind a seated pilot to yet. The assembler enqueues each seated pilot here.
 *
 * <p><b>An entry waits for the craft, not for a clock.</b> The fact it needs — the craft named by the
 * entry's durable id is assembled in this world, with its tiles live — is published as
 * {@code FlightComputerLiveEvent}, posted from the relocated flight computer's first tick
 * ({@link #onFlightComputerLive}). Only from then on does the per-tick pass try
 * {@link CrewTransfer#rebindAcrossAssembly} for that entry; before it there is nothing the attempt
 * could find. The pass keeps trying after the event because the event can arrive from the craft's
 * computer while it still stands on the pad (the assembly's own paste re-creates it there, under the
 * name it was just given) and because a pilot read off his mount once is debounced over consecutive
 * ticks. The give-up budget still counts every tick an entry exists: a refused assembly (over-size,
 * bedrock) is dropped by the physics mod and announces nothing, so for that case only a clock can
 * end the wait - after which the pilot is left on his stale mount and the failure is logged, never
 * held in limbo and never silently dropped.</p>
 *
 * <p>The queue is server state: it belongs to the {@link SpaceSubsystem} of the running server,
 * which builds one at server start and drops it at stop, and {@link SpaceSubsystemEvents} drives it.
 * A pending rebind names a player, a dimension and an entity id of ONE server, so it must not
 * outlive that server into the next world a single-player client opens.</p>
 */
public final class AssemblyCrewRebind {

    /** Effectively final, process lifetime: built once at class initialisation. */
    private static final Logger LOGGER = LogManager.getLogger("stellurgy.assemblycrewrebind");

    /** Give-up budget in server ticks (~60 s at 20 TPS), counted from the enqueue. Giving up strands
     *  the pilot on a mount bound to vacated coordinates - a dead cockpit - so the budget errs long.
     *  A 15 s budget was measured expiring under an 8-fork test load, at a time when the wait it
     *  bounds was a per-tick poll rather than an event. */
    private static final int MAX_ATTEMPTS = 1200;

    /** How many CONSECUTIVE ticks the pilot must be observed off his stale mount before the entry
     *  is cancelled. A single observation is not trustworthy - a transient read during entity
     *  churn once cancelled a rebind whose pilot never stood up, stranding him. */
    private static final int NOT_ON_MOUNT_DEBOUNCE = 3;

    private final List<Pending> queue = new ArrayList<>();

    /**
     * Seam: this queue took an entry, or let one go, and why.
     *
     * <p>A SEAM, and nothing else. Four counters and the text of the most recent outcome used to be
     * published here for a probe to read, and their defect was that <b>they named no entry</b> — no
     * player, no stale mount, no anchor of their own. On a shared server every scenario writes them,
     * so a delta across one stimulus says only "the queue gave up on somebody", and a post-mortem
     * string cannot separate two entries whose anchor is the same fixture built twice. Measured on
     * the 2026-09-06 gate: a hard assertion red on {@code expired anchor=BlockPos{2802,69,2803}}
     * belonging to an earlier scenario's pilot, about a minute after that scenario assembled, while
     * the entry the assertion was about had not been queued long enough to expire at all.</p>
     *
     * <p>Every parameter here is the part the counters lacked. The queue runs on the server tick and
     * nowhere else, so a watcher records against the server's clock.</p>
     *
     * @param outcome {@code queued}, {@code rebound}, {@code expired},
     *                {@code cancelled(playerGone)} or {@code cancelled(offMount)}
     * @param attempts ticks this entry had been retried when it ended; {@code 0} at {@code queued}
     */
    private static void noteRebindQueue(String outcome, UUID playerId, int staleDummyId,
                                        BlockPos anchor, int attempts) {
    }

    /** One seated pilot owed a rebind: who, off which stale mount, onto which ship's seat. */
    private static final class Pending {
        final int dimension;
        final UUID playerId;
        final int staleDummyId;
        final BlockPos anchor;
        final int afcDx, afcDy, afcDz;
        /** The assembling ship's durable id, so the rebind can never grab an equal-offset seat of
         *  a neighbouring craft ({@code null} when the assembler had none to give). */
        final UUID shipId;
        int attempts;
        /** Consecutive ticks the pilot was observed NOT riding the stale mount (see debounce). */
        int notOnMountStreak;
        /** Whether the craft {@link #shipId} names has announced its flight computer live in
         *  {@link #dimension}; until it has, there is no relocated seat to rebind to. */
        boolean craftLive;

        Pending(int dimension, UUID playerId, int staleDummyId, BlockPos anchor,
                int afcDx, int afcDy, int afcDz, UUID shipId) {
            this.dimension = dimension;
            this.playerId = playerId;
            this.staleDummyId = staleDummyId;
            this.anchor = anchor;
            this.afcDx = afcDx;
            this.afcDy = afcDy;
            this.afcDz = afcDz;
            this.shipId = shipId;
        }
    }

    /**
     * Queue {@code player} - currently riding the stale mount {@code staleDummyId} bound to the
     * seat's pre-assembly position - for a rebind onto the ship being assembled at {@code anchor},
     * whose seat is re-identified by the given AFC-link offset on the ship with durable id
     * {@code shipId}. Server main thread only.
     */
    public void enqueue(WorldServer world, EntityPlayerMP player, int staleDummyId,
            BlockPos anchor, int afcDx, int afcDy, int afcDz, UUID shipId) {
        noteRebindQueue("queued", player.getUniqueID(), staleDummyId, anchor, 0);
        queue.add(new Pending(world.provider.getDimension(), player.getUniqueID(),
                staleDummyId, anchor, afcDx, afcDy, afcDz, shipId));
    }

    /**
     * The craft named {@code durableShipId} has its flight computer live in {@code dimension}: every
     * entry waiting for that craft there may now try its rebind. Server main thread — the event is
     * posted from the computer's own tick.
     */
    void onFlightComputerLive(int dimension, String durableShipId) {
        if (durableShipId == null) {
            return;
        }
        for (Pending pending : queue) {
            if (pending.dimension == dimension && pending.shipId != null
                    && durableShipId.equals(pending.shipId.toString())) {
                pending.craftLive = true;
            }
        }
    }

    /** One pass over every queued pilot. Server main thread, once per server tick. */
    void tick(MinecraftServer server) {
        if (queue.isEmpty()) {
            return;
        }
        Iterator<Pending> it = queue.iterator();
        while (it.hasNext()) {
            Pending pending = it.next();
            EntityPlayerMP player = server.getPlayerList().getPlayerByUUID(pending.playerId);
            WorldServer world = player == null ? null : server.getWorld(pending.dimension);
            if (player == null || world == null) {
                // Debounced like the mount check: a transient lookup miss must not strand a
                // still-connected pilot on a dead binding.
                if (++pending.notOnMountStreak >= NOT_ON_MOUNT_DEBOUNCE) {
                    noteRebindQueue("cancelled(playerGone)", pending.playerId,
                            pending.staleDummyId, pending.anchor, pending.attempts);
                    it.remove(); // logged out mid-assembly; the login-restore path owns him now
                }
                continue;
            }
            if (!pending.craftLive) {
                // The craft has not announced itself here yet, so there is no relocated seat to
                // find; only the give-up clock runs.
                if (++pending.attempts > MAX_ATTEMPTS) {
                    expire(pending, player);
                    it.remove();
                }
                continue;
            }
            CrewTransfer.RebindOutcome outcome = CrewTransfer.rebindAcrossAssembly(world,
                    pending.anchor, player, pending.staleDummyId,
                    pending.afcDx, pending.afcDy, pending.afcDz, pending.shipId);
            if (outcome == CrewTransfer.RebindOutcome.REBOUND) {
                noteRebindQueue("rebound", pending.playerId, pending.staleDummyId,
                        pending.anchor, pending.attempts);
                it.remove();
                continue;
            }
            if (outcome == CrewTransfer.RebindOutcome.NOT_ON_STALE_MOUNT) {
                if (++pending.notOnMountStreak >= NOT_ON_MOUNT_DEBOUNCE) {
                    noteRebindQueue("cancelled(offMount)", pending.playerId,
                            pending.staleDummyId, pending.anchor, pending.attempts);
                    it.remove(); // genuinely stood up / re-seated - never force him back
                }
                continue;
            }
            pending.notOnMountStreak = 0; // still on the stale mount, the seat not resolvable yet
            if (++pending.attempts > MAX_ATTEMPTS) {
                expire(pending, player);
                it.remove();
            }
        }
    }

    /** Say that an entry ran out of budget; the caller removes it. */
    private static void expire(Pending pending, EntityPlayerMP player) {
        noteRebindQueue("expired", pending.playerId, pending.staleDummyId,
                pending.anchor, pending.attempts);
        LOGGER.warn("gave up rebinding {} onto the ship assembled at {} after {} ticks - {}; he keeps"
                        + " the stale mount", player.getName(), pending.anchor, MAX_ATTEMPTS,
                pending.craftLive ? "the relocated seat never resolved"
                        : "the craft never announced its flight computer live in this world");
    }
}
