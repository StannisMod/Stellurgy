package dev.stannismod.stellurgy.test.mixin;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

import net.minecraft.entity.Entity;
import net.minecraft.entity.player.EntityPlayer;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

import dev.stannismod.stellurgy.api.atmosphere.Atmosphere;
import dev.stannismod.stellurgy.atmosphere.AtmosphereHandler;
import dev.stannismod.stellurgy.test.trace.TestTrace;

/**
 * The moment production decides a player's atmosphere has CHANGED: {@code player_atmosphere_changed}.
 *
 * <h2>The fact it observes</h2>
 *
 * <p>{@link AtmosphereHandler}'s tick resolves an atmosphere for every entity in its dimension. That
 * resolution is what everything downstream of a player moving between atmospheres hangs off: the
 * suit gate, the HUD readout, the damage paths. The seam is the resolution itself — the call this
 * redirect stands in front of — and the original is performed unchanged and its result returned, so
 * nothing about the game's behaviour or timing moves.</p>
 *
 * <h2>The instrument keeps its own memory, and that is the design</h2>
 *
 * <p>This used to redirect a per-player CACHE WRITE, because production compared each resolution
 * against the previous one to decide whether to send a sync packet, and the cache was where that
 * previous value lived. Both are gone: the readout is sent periodically now — a composition slides
 * and has no edge to fire on — so nothing in production computes "changed" and nothing remembers
 * the last answer.</p>
 *
 * <p>So the instrument remembers it. A test may hold state the game has no reason to hold, and this
 * is the case for it: the FACT is still real and still worth recording, only its evidence stopped
 * being a side effect of something production needed for itself. What must not happen is the
 * reverse — production keeping a field alive so a test has something to read.</p>
 *
 * <h2>Payload</h2>
 *
 * <p>{@code e} — the player's entity id; {@code who} — his name; {@code dim} — the dimension whose
 * handler resolved him, which is not always the dimension he is standing in as a reader might assume
 * (a handler only ticks entities in its own dim, so this names the resolver); {@code atmosphere} —
 * the unlocalized name of what he was resolved TO; {@code from} — what he was resolved to on the
 * previous tick, or {@code "none"} for the first resolution this instrument has seen for him.</p>
 *
 * <h2>What it is SILENT about</h2>
 *
 * <ul>
 * <li><b>A resolution that did not change anything.</b> This is an EDGE by construction. "Which
 *     atmosphere is he in right now" is a state, not an event, and the probe answers it
 *     ({@code /stellurgytest atmosphere for-player}, which asks the live gate).</li>
 * <li><b>Non-player entities.</b> Every entity is resolved, but only players are named in the
 *     record, because only a player has a client to be told and a suit to be gated.</li>
 * <li><b>Whether the packet arrived.</b> The send is periodic and separate; a client that was never
 *     told is indistinguishable here.</li>
 * </ul>
 *
 * <p><b>The instrument mark is taken on EVERY resolution, not only on a change</b> — that is what
 * lets a reader tell "it was listening and nothing happened" from "the mixin never wove", which is
 * the distinction the spike's absence-is-an-answer leg rests on. The old shape marked only when it
 * recorded, so silence proved nothing.</p>
 */
@Mixin(AtmosphereHandler.class)
public abstract class MixinAtmosphereChangeEvents {

    private static final String INSTRUMENT = "atmosphere_change_events";

    /** What this instrument last saw each player resolved to. Test-side state; see the class doc. */
    private static final Map<UUID, String> stellurgyTest$lastSeen = new HashMap<>();

    /**
     * Stands in front of the per-entity resolution, performs it, and records the edge.
     *
     * <p>A redirect rather than an inject because the resolution's RESULT is the fact, and a redirect
     * is handed it without having to guess where in the method it lands or to call the gate a second
     * time — a second call could disagree with the one production made.</p>
     */
    @Redirect(method = "onTick",
            at = @At(value = "INVOKE",
                    target = "Ldev/stannismod/stellurgy/atmosphere/AtmosphereHandler;"
                            + "getAtmosphereType(Lnet/minecraft/entity/Entity;)"
                            + "Ldev/stannismod/stellurgy/api/atmosphere/Atmosphere;"),
            require = 1)
    private Atmosphere stellurgyTest$atmosphereResolved(AtmosphereHandler handler, Entity entity) {
        Atmosphere resolved = handler.getAtmosphereType(entity);
        if (!(entity instanceof EntityPlayer)) {
            return resolved;
        }
        EntityPlayer who = (EntityPlayer) entity;
        TestTrace.instrument(who, INSTRUMENT);

        String now = resolved == null ? "none" : resolved.getUnlocalizedName();
        String previous = stellurgyTest$lastSeen.put(who.getUniqueID(), now);
        if (now.equals(previous)) {
            return resolved;
        }
        TestTrace.record(who, "player_atmosphere_changed",
                "\"e\":" + who.getEntityId()
                        + ",\"who\":\"" + TestTrace.json(who.getName()) + "\""
                        + ",\"dim\":" + who.world.provider.getDimension()
                        + ",\"atmosphere\":\"" + TestTrace.json(now) + "\""
                        + ",\"from\":\"" + (previous == null ? "none" : TestTrace.json(previous))
                        + "\"");
        return resolved;
    }
}
