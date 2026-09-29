package zmaster587.advancedRocketry.api.event;

import net.minecraft.world.World;
import net.minecraftforge.fml.common.eventhandler.Event;

/**
 * Two craft met. Posted on the server bus the tick their world boxes first overlap, and not again
 * until they have parted.
 *
 * <h2>Why an event and not a log line</h2>
 *
 * <p>A meeting is a thing the rest of the game has opinions about — damage, sound, a warning to a
 * pilot, a test that waits for it. A log line can be read by a human afterwards and by nothing else;
 * this can be subscribed to. Neither craft is "the one that hit": the overlap is symmetric and so is
 * this event, which is why the two identities are {@code a} and {@code b} rather than a subject and
 * an object. A subscriber that needs an order has to establish one from the craft themselves.</p>
 *
 * <h2>What it does NOT carry, said here rather than discovered</h2>
 *
 * <p>No point of contact, no normal, no relative velocity, no energy. The detector behind it
 * compares two boxes and knows none of those things; a field that would have to be invented to be
 * filled is worse than an absent one. When the collision response grows up, this event grows with
 * it or is replaced by one that can say more — and either way a subscriber written today against
 * two identities and a world keeps working or is told at compile time.</p>
 */
public class ShipCollisionEvent extends Event {

    /** The world both craft are in. They cannot be in different ones: boxes are compared per world. */
    public final World world;

    /** One craft's substrate identity. Which of the two is {@code a} is arbitrary and may differ
     *  between one meeting and the next — the pair is a SET, and the field names say only that
     *  there are two of them. */
    public final String a;

    /** The other craft's substrate identity. */
    public final String b;

    public ShipCollisionEvent(World world, String a, String b) {
        this.world = world;
        this.a = a;
        this.b = b;
    }
}
