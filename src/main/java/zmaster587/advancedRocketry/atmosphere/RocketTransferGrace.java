package zmaster587.advancedRocketry.atmosphere;

import net.minecraft.entity.Entity;
import net.minecraft.nbt.NBTTagCompound;

/**
 * The short window after a rocket transfer in which an entity is IMMUNE to the suit check.
 *
 * <p>A dimension transfer puts a passenger into the destination world a tick or two before the
 * things that keep him alive there are resolved, so without this a player arriving on an airless
 * world takes damage for the arrival itself. The window is 100 ticks from the moment of the move.</p>
 *
 * <h2>Why this class exists</h2>
 *
 * <p><b>It had no owner.</b> The key was a bare string literal written at two call sites, read at a
 * third, and never cleared anywhere: {@code "arRocketTransferGrace"} spelled out in full in each of
 * them, with the {@code + 100L} duplicated at both writers. Nothing named the concept, so nothing
 * could ask whether an entity is inside the window, and nothing could END the window — which is why
 * a player carrying one could hand it to whatever he did next.</p>
 *
 * <p>It is a BINDING in the sense {@link zmaster587.advancedRocketry.api.event.PlayerReleaseEvent}
 * uses: state stamped on a player by one subsystem that changes how another subsystem treats him.
 * Giving it an owner is what lets it be released.</p>
 */
public final class RocketTransferGrace {

    /** The NBT key on the entity's own forge data. Public because the test probe reports it. */
    public static final String KEY = "arRocketTransferGrace";

    /**
     * How long the window lasts, in ticks.
     *
     * <p>Five seconds. It has to outlast the delayed transition queue the transfer schedules, and
     * both writers used the same number before this class existed — which is the only evidence
     * behind it, so treat it as inherited rather than as measured.</p>
     */
    public static final long WINDOW_TICKS = 100L;

    private RocketTransferGrace() {
    }

    /** Open the window on {@code entity}, counted from the destination world's clock. */
    public static void stamp(Entity entity, long worldTime) {
        if (entity != null) {
            entity.getEntityData().setLong(KEY, worldTime + WINDOW_TICKS);
        }
    }

    /** Is {@code entity} still inside the window? */
    public static boolean isActive(Entity entity, long worldTime) {
        return entity != null && entity.getEntityData().getLong(KEY) > worldTime;
    }

    /**
     * End the window, and say whether there was one.
     *
     * <p>Returns {@code false} for an entity that was not inside it, so a caller can report what was
     * actually undone rather than what it asked for.</p>
     */
    public static boolean clear(Entity entity, long worldTime) {
        if (entity == null) {
            return false;
        }
        NBTTagCompound data = entity.getEntityData();
        boolean wasActive = data.getLong(KEY) > worldTime;
        data.removeTag(KEY);
        return wasActive;
    }
}
