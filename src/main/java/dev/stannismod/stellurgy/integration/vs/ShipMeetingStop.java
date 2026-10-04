package dev.stannismod.stellurgy.integration.vs;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import net.minecraft.util.math.AxisAlignedBB;
import net.minecraft.world.World;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;
import net.minecraftforge.fml.common.gameevent.TickEvent;

import dev.stannismod.stellurgy.api.StellurgyConfiguration;
import dev.stannismod.stellurgy.api.event.ShipCollisionEvent;
import dev.stannismod.stellurgy.world.WorldRuntime;

/**
 * Two craft may not pass through one another: while their world boxes overlap, both are held at
 * rest, and the meeting is announced once.
 *
 * <h2>TODO — THIS IS THE CRUDE VERSION, ON PURPOSE</h2>
 *
 * <p><b>It conserves nothing.</b> There is no momentum transfer, no restitution, no separation and
 * no contact point: two boxes overlap and both craft stop. A glancing pass and a head-on meeting
 * produce the same outcome, and a craft that is mostly air collides by the box that encloses it
 * rather than by its blocks. That is a deliberate first move — before this, two hulls simply
 * occupied the same space and nothing happened at all — and it is meant to be DELETED whole when a
 * collision that conserves something replaces it, not extended. The class is one file plus its
 * event for that reason: removing it restores the previous behaviour exactly.</p>
 *
 * <h2>Why "held at rest" and not "stopped"</h2>
 *
 * <p>Writing a velocity does not stop a ship here. The substrate recomputes velocity from forces on
 * every physics step and overwrites the write — measured, and recorded on {@code pushShipById}: 25
 * setpoints a tick apart moved a craft by −0.6 blocks. So a single zeroing would be a gesture. What
 * this does instead is keep zeroing, every tick, for as long as the overlap lasts; a craft whose
 * flight computer is still commanding force therefore does not accelerate away, but it is being
 * held rather than braked, and it resumes the moment the boxes part.</p>
 *
 * <h2>What it is silent about</h2>
 *
 * <ul>
 *   <li><b>A craft nobody is flying still never stops.</b> This module only has an opinion about two
 *       craft that MEET. A lone hull climbing with nothing to hit is a different gap.</li>
 *   <li><b>Bodies aboard are not consulted.</b> A crew member on a deck that stops dead feels
 *       whatever the deck resolver gives him, and this class does not tell it anything.</li>
 *   <li><b>Boxes, not blocks.</b> Two craft whose boxes touch while their hulls do not are stopped
 *       anyway, and the event fires. On the fixture craft this suite uses the box is nearly the
 *       hull; on a long thin craft it is not.</li>
 * </ul>
 */
public final class ShipMeetingStop {

    /**
     * Which pairs were overlapping on a world's previous tick, so the event is posted on the EDGE —
     * the tick a meeting begins — while the holding happens on every tick of it. That world's own part
     * ({@link WorldRuntime}): this handler is one object on the bus for the whole process, and a memory
     * kept on it would outlive every world, so a meeting from a stopped server could silence the same
     * pair's first meeting in the next one.
     *
     * <p>A pair is keyed by its two identities in a fixed order, because the overlap is symmetric
     * and "a met b" and "b met a" are one meeting.</p>
     */
    private static final class Meetings {
        Set<String> lastTick = new HashSet<>();
    }

    /** Subscribe the one instance the bus owns. Called from {@link VSIntegration}'s init beside the
     *  other watchers. */
    static void register() {
        MinecraftForge.EVENT_BUS.register(new ShipMeetingStop());
    }

    @SubscribeEvent
    public void onWorldTick(TickEvent.WorldTickEvent event) {
        if (event.phase != TickEvent.Phase.END || event.world == null || event.world.isRemote) {
            return;
        }
        final World world = event.world;
        final Meetings meetings = WorldRuntime.of(world, Meetings.class, Meetings::new);
        if (!StellurgyConfiguration.getCurrentConfig().shipsCollide) {
            // Disabled means disabled: no detection, no event, no cost beyond this read.
            meetings.lastTick.clear();
            return;
        }
        final Map<String, AxisAlignedBB> boxes = VSIntegration.loadedShipBoxes(world);
        if (boxes.size() < 2) {
            meetings.lastTick.clear();
            return;
        }

        final List<String> ids = new ArrayList<>(boxes.keySet());
        final Set<String> meetingNow = new HashSet<>();
        final Set<String> held = new HashSet<>();
        for (int i = 0; i < ids.size(); i++) {
            for (int j = i + 1; j < ids.size(); j++) {
                final String a = ids.get(i), b = ids.get(j);
                if (!boxes.get(a).intersects(boxes.get(b))) {
                    continue;
                }
                meetingNow.add(pairKey(a, b));
                // Held every tick of the overlap, for the reason in the class javadoc: a single
                // write is overwritten by the next physics step.
                if (held.add(a)) {
                    VSIntegration.haltShip(world, a);
                }
                if (held.add(b)) {
                    VSIntegration.haltShip(world, b);
                }
            }
        }

        final Set<String> before = meetings.lastTick;
        for (String pair : meetingNow) {
            if (!before.contains(pair)) {
                final int split = pair.indexOf('|');
                MinecraftForge.EVENT_BUS.post(new ShipCollisionEvent(world,
                        pair.substring(0, split), pair.substring(split + 1)));
            }
        }
        meetings.lastTick = meetingNow;
    }

    /** The two identities in a fixed order, so one meeting has one key whichever way it is found. */
    private static String pairKey(String a, String b) {
        return a.compareTo(b) <= 0 ? a + "|" + b : b + "|" + a;
    }
}
