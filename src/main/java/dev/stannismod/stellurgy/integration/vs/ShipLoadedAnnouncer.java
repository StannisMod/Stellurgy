package dev.stannismod.stellurgy.integration.vs;

import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.UUID;

import net.minecraft.world.World;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;
import net.minecraftforge.fml.common.gameevent.TickEvent;

import dev.stannismod.stellurgy.api.event.ShipEvent;

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
 * <h2>Disappearance, and why it is a SECOND watch</h2>
 *
 * <p>{@link ShipEvent.ShipGoneEvent} is posted here too, and it is deliberately NOT the falling edge
 * of the set above. "Ready for physics" goes false whenever a ship stops being steppable — an
 * unloaded chunk region, a quiet world — and such a ship is coming back; publishing that as "gone"
 * would tell every consumer the opposite of the truth on the most ordinary event there is. Gone is
 * the REGISTRY dropping the craft, which is what a destroy pass does, so it is watched separately
 * and against a different set.</p>
 *
 * <p>The two edges are independent and a ship normally crosses both, in that order: it stops being
 * steppable and then leaves the registry. A consumer wanting "can I use it now" reads the first; one
 * wanting "will it ever come" reads the second.</p>
 *
 * <h2>What it is silent about</h2>
 *
 * <p>Ships in a world that stops ticking are not reported as gone — nothing here observes a world
 * unloading: the per-world memory below goes with the world object and announces nothing.
 * <b>So the absence of a {@code ShipGoneEvent} is not proof a craft still exists</b>,
 * and a consumer that must ultimately answer for a craft in a world nobody ticks still needs an end
 * of its own. That limit is why {@code DeckHold} keeps a give-up at all, and it is stated on both
 * sides so neither reads as an oversight.</p>
 */
public final class ShipLoadedAnnouncer {

    /**
     * What one server WORLD remembers between its ticks ({@link dev.stannismod.stellurgy.world.WorldRuntime}),
     * so it goes with the world object and a later world reusing the dimension number starts clean.
     */
    private static final class WorldWatch {
        /** The substrate ids that were loaded-and-settled on the previous tick. */
        final Map<String, UUID> readyLastTick = new HashMap<>();

        /**
         * The substrate ids the REGISTRY carried on the previous tick, with each craft's durable name
         * — the set {@link ShipEvent.ShipGoneEvent} is the falling edge of.
         *
         * <p>Kept separately from {@link #readyLastTick} because it answers a different question and
         * has a different membership: the registry carries ships that are not loaded, and (during a
         * crossing) the source hull between its cut and the substrate's own destroy pass. The durable
         * name is remembered HERE rather than looked up when the craft leaves, because by then there
         * is nothing left to ask — that is the whole difficulty with publishing a disappearance.</p>
         */
        final Map<String, UUID> registeredLastTick = new HashMap<>();

        /**
         * Craft a crossing has told us it is about to cut out of this world, and where to — substrate
         * id to destination dimension. Consumed by the next falling edge for that id, which turns it
         * into {@link ShipEvent.ShipLeftWorldEvent} instead of {@link ShipEvent.ShipGoneEvent}.
         *
         * <p><b>Clock-free on purpose.</b> A mark lives until the edge it predicts arrives — the cut
         * is what causes that edge, so it does arrive — rather than for some number of ticks chosen
         * to be "long enough". A crossing that decides not to cut after all takes its own mark back
         * ({@link #abandonDeparture}); nothing else may.</p>
         */
        final Map<String, Integer> departing = new HashMap<>();
    }

    private static WorldWatch watchOf(World world) {
        return dev.stannismod.stellurgy.world.WorldRuntime.of(world, WorldWatch.class, WorldWatch::new);
    }

    /**
     * A crossing is about to cut {@code substrate} out of {@code world} into {@code destinationDim}.
     * Declared BEFORE the cut, so the registry edge it causes is classified by the code that knows
     * rather than guessed at by the code that watches.
     */
    public static void declareDeparture(World world, UUID substrate, int destinationDim) {
        if (substrate != null) {
            watchOf(world).departing.put(substrate.toString(), destinationDim);
        }
    }

    /** The declared departure did not happen: the craft is still here, and a later disappearance of
     *  it would be a real one. */
    public static void abandonDeparture(World world, UUID substrate) {
        if (substrate != null) {
            watchOf(world).departing.remove(substrate.toString());
        }
    }

    @SubscribeEvent
    public void onWorldTick(TickEvent.WorldTickEvent event) {
        if (event.phase != TickEvent.Phase.END) {
            return;
        }
        World world = event.world;
        if (world == null || world.isRemote) {
            return;
        }
        Map<String, UUID> now = VSIntegration.shipsReadyForPhysics(world);
        WorldWatch watch = watchOf(world);
        Map<String, UUID> before = watch.readyLastTick;
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
        announceGone(world, watch);
    }

    /**
     * The registry's own falling edge: every craft this world carried last tick and does not carry
     * now has been destroyed, and is announced as {@link ShipEvent.ShipGoneEvent}.
     *
     * <p>The durable name is read ONCE, on the tick a craft enters the registry, and remembered —
     * not looked up when it leaves. By then the record it would be read off is exactly what has been
     * removed, so a disappearance published from a live lookup could only ever say "some ship, name
     * unknown", which is the shape of answer this whole family exists to stop giving.</p>
     */
    private void announceGone(World world, WorldWatch watch) {
        Map<String, UUID> remembered = watch.registeredLastTick;
        java.util.Map<UUID, double[]> registered = VSIntegration.registeredShipPoses(world);
        for (UUID substrate : registered.keySet()) {
            String key = substrate.toString();
            if (!remembered.containsKey(key)) {
                remembered.put(key, VSIntegration.durableIdOfShip(world, substrate));
            }
        }
        for (Iterator<Map.Entry<String, UUID>> it = remembered.entrySet().iterator(); it.hasNext();) {
            Map.Entry<String, UUID> was = it.next();
            if (registered.containsKey(UUID.fromString(was.getKey()))) {
                continue;
            }
            UUID durable = was.getValue();
            String name = durable == null ? null : durable.toString();
            Integer departedTo = watch.departing.remove(was.getKey());
            // DEPARTED or DESTROYED — the registry edge is identical for both, and only the crossing
            // knows which. Reported as "gone" they are the same sentence, and it is false for exactly
            // the case that happens all the time: a craft carrying its crew to the next cell.
            MinecraftForge.EVENT_BUS.post(departedTo != null
                    ? new ShipEvent.ShipLeftWorldEvent(world, name, was.getKey(), departedTo)
                    : new ShipEvent.ShipGoneEvent(world, name, was.getKey()));
            it.remove();
        }
    }
}
