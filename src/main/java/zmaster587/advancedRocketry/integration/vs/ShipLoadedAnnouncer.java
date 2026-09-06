package zmaster587.advancedRocketry.integration.vs;

import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.UUID;

import net.minecraft.world.World;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;
import net.minecraftforge.fml.common.gameevent.TickEvent;

import zmaster587.advancedRocketry.api.event.ShipEvent;

/**
 * Posts {@link ShipEvent.ShipLoadedEvent} on the tick a ship first becomes ready to be flown.
 *
 * <h2>Why this is an edge detector and not an injection</h2>
 *
 * <p>There is no single moment in the substrate to hook. "Ready to be flown" is a conjunction and
 * each half becomes true on a tick of its own: physics is deliberately withheld for the first ticks
 * after a load so a freshly placed hull does not fall through the floor, and the surrounding-chunk
 * cache is filled during the ship's own tick. So the fact this event reports is a PREDICATE that
 * turns true, and the honest way to publish it is to watch the predicate.</p>
 *
 * <p>The watch is cheap and the publication is rare: the check is two reads per loaded ship per
 * tick, and a ship posts once per load rather than once per tick. That asymmetry is what keeps a bus
 * event off a hot path.</p>
 *
 * <h2>What "again" means</h2>
 *
 * <p>A ship that stops being ready — unloaded — is forgotten here, so it posts again when it comes
 * back. That is deliberate: a consumer's question is "can I use this ship now", and the answer
 * genuinely became false and then true again. A ship that merely moves, crosses a cell or changes
 * hands never leaves the set and never re-posts.</p>
 *
 * <h2>What "ready" does NOT include, and why it cost a gate to learn</h2>
 *
 * <p>The substrate also carries {@code isPhysicsEnabled}, and the first version of this class
 * required it. That was wrong: enabling physics is an operational act somebody performs, so a parked
 * craft nobody has commanded is fully loaded with it false. A caller waiting to BEGIN flying then
 * waits for a state its own next action causes — a deadlock by construction, and it turned 1 red
 * into 42 on the first gate that used it. Readiness here is about the ship being LOADED and past its
 * settling delay; whether anything is flying it is a different question with a different answer.</p>
 *
 * <h2>What it is silent about</h2>
 *
 * <p>Ships in a world that stops ticking are not reported as gone — nothing here observes a world
 * unloading, and the per-world memory below is dropped only when that world ticks again with the
 * ship absent. A consumer that must know about disappearance cannot learn it here; that is a
 * separate event and deliberately not invented alongside this one.</p>
 */
public final class ShipLoadedAnnouncer {

    /**
     * Per world dimension, the substrate ids that were loaded-and-settled on the previous tick. Keyed by
     * dimension rather than by {@code World} so a world object replaced between ticks does not
     * resurrect an entry, and cleared per world as it ticks.
     */
    private final Map<Integer, Map<String, UUID>> readyLastTick = new HashMap<>();

    @SubscribeEvent
    public void onWorldTick(TickEvent.WorldTickEvent event) {
        if (event.phase != TickEvent.Phase.END) {
            return;
        }
        World world = event.world;
        if (world == null || world.isRemote || !VSIntegration.isAvailable()) {
            return;
        }
        Map<String, UUID> now = VSIntegration.shipsReadyForPhysics(world);
        int dim = world.provider.getDimension();
        Map<String, UUID> before = readyLastTick.get(dim);
        if (before == null) {
            before = new HashMap<>();
            readyLastTick.put(dim, before);
        }
        for (Map.Entry<String, UUID> entry : now.entrySet()) {
            if (!before.containsKey(entry.getKey())) {
                UUID durable = entry.getValue();
                MinecraftForge.EVENT_BUS.post(new ShipEvent.ShipLoadedEvent(
                        world, durable == null ? null : durable.toString(), entry.getKey()));
            }
        }
        // Forget what is no longer ready, so a reload is a new edge. Iterated rather than
        // replaced wholesale to keep the map's identity stable for the entry above.
        for (Iterator<String> it = before.keySet().iterator(); it.hasNext();) {
            if (!now.containsKey(it.next())) {
                it.remove();
            }
        }
        before.putAll(now);
    }
}
