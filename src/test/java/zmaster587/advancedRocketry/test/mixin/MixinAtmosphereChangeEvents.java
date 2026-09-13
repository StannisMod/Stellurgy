package zmaster587.advancedRocketry.test.mixin;

import java.util.HashMap;

import net.minecraft.entity.player.EntityPlayer;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

import zmaster587.advancedRocketry.api.IAtmosphere;
import zmaster587.advancedRocketry.atmosphere.AtmosphereHandler;
import zmaster587.advancedRocketry.test.trace.TestTrace;

/**
 * The moment production decides a player's atmosphere has CHANGED: {@code player_atmosphere_changed}.
 *
 * <h2>The fact it observes</h2>
 *
 * <p>{@link AtmosphereHandler}'s tick resolves an atmosphere for every entity in its dimension and,
 * for a player, compares it against what that player was last resolved to. When the two differ it
 * does two things in one breath — it sends the player a sync packet and it writes the new value into
 * its own per-player cache — and that pair IS the decision. Everything downstream of a player moving
 * between atmospheres hangs off it: the suit gate, the HUD readout, the damage paths.</p>
 *
 * <p>The seam is the cache WRITE rather than the packet, and the record is taken AFTER the write
 * completes, so a reader that sees this event can also read the cache back and find the new value
 * there. The previous value is the write's own return, which is the only place it still exists.</p>
 *
 * <h2>Why it exists</h2>
 *
 * <p>Because the alternative in use was a probe poll. The cache is a private static map and tests
 * reached it by reflection through {@code /artest atmosphere cached-for-player}, asked in a loop
 * until the answer stopped being empty. That cannot say WHEN the change happened, cannot tell a
 * change that was made and undone from one that never happened, and — since the cache is only
 * written on a CHANGE — spends its whole budget in silence whenever the resolved atmosphere is the
 * one already cached.</p>
 *
 * <h2>Payload</h2>
 *
 * <p>{@code e} — the player's entity id; {@code who} — his name; {@code dim} — the dimension whose
 * handler resolved him, which is not always the dimension he is standing in as a reader might assume
 * (a handler only ticks entities in its own dim, so this names the resolver); {@code atmosphere} —
 * the unlocalized name of what he was resolved TO; {@code from} — the unlocalized name of what he
 * was resolved to last, or {@code "none"} when this is the first resolution since the cache was
 * cleared.</p>
 *
 * <h2>What it is SILENT about</h2>
 *
 * <ul>
 * <li><b>A resolution that did not change anything.</b> This is an EDGE by construction: production
 *     reaches the write only when the type differs. "Which atmosphere is he in right now" is not a
 *     question this event answers — it is a state, and the probe still answers it.</li>
 * <li><b>Non-player entities.</b> Production caches only players; everything else is re-resolved
 *     every tick and compared against nothing.</li>
 * <li><b>The cache being CLEARED.</b> A logout, a dimension change and a handler teardown all remove
 *     the player's entry, and the next resolution then records {@code from:"none"} whether or not
 *     the atmosphere really changed for him. Read {@code from} as "what the cache held", never as
 *     "where he was".</li>
 * <li><b>Whether the packet arrived.</b> The send happens a line earlier and this records the write;
 *     a client that was never told is indistinguishable here.</li>
 * </ul>
 */
@Mixin(AtmosphereHandler.class)
public abstract class MixinAtmosphereChangeEvents {

    private static final String INSTRUMENT = "atmosphere_change_events";

    /**
     * Redirects the cache write so the record can carry BOTH values.
     *
     * <p>A redirect rather than an inject, because the previous atmosphere exists nowhere else: the
     * map is overwritten in the same call that would have to be read to learn it, and an inject
     * placed before the write would have to re-read the map — a second lookup that could disagree
     * with the one production made. The original operation is performed unchanged and its result
     * returned, so nothing about the game's behaviour or timing moves.</p>
     */
    @Redirect(method = "onTick",
            at = @At(value = "INVOKE",
                    target = "Ljava/util/HashMap;put(Ljava/lang/Object;Ljava/lang/Object;)"
                            + "Ljava/lang/Object;"),
            require = 1)
    private Object arTest$atmosphereCommitted(HashMap<EntityPlayer, IAtmosphere> cache,
                                              Object player, Object atmosphere) {
        Object previous = cache.put((EntityPlayer) player, (IAtmosphere) atmosphere);
        EntityPlayer who = (EntityPlayer) player;
        TestTrace.instrument(who, INSTRUMENT);
        TestTrace.record(who, "player_atmosphere_changed",
                "\"e\":" + who.getEntityId()
                        + ",\"who\":\"" + TestTrace.json(who.getName()) + "\""
                        + ",\"dim\":" + who.world.provider.getDimension()
                        + ",\"atmosphere\":\"" + TestTrace.json(
                                ((IAtmosphere) atmosphere).getUnlocalizedName()) + "\""
                        + ",\"from\":\"" + (previous == null ? "none"
                                : TestTrace.json(((IAtmosphere) previous).getUnlocalizedName()))
                        + "\"");
        return previous;
    }
}
