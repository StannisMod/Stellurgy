package zmaster587.advancedRocketry.test.mixin;

import net.minecraft.entity.Entity;
import net.minecraft.entity.item.EntityFallingBlock;
import net.minecraft.entity.item.EntityItem;
import net.minecraft.entity.item.EntityXPOrb;
import net.minecraft.world.World;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import zmaster587.advancedRocketry.test.trace.TestTrace;

/**
 * An entity STOPPED EXISTING in a world — the counterpart of the bus's {@code entity_joined_world},
 * and the link every "and then it was gone" contract was missing.
 *
 * <h2>Why a mixin and not a bus subscriber</h2>
 *
 * <p>There is no leave-world event to subscribe to. Forge 1.12.2 posts {@code EntityJoinWorldEvent}
 * and has no counterpart (checked against the Forge sources jar for 14.23.5.2860: the entity event
 * package carries {@code EntityJoinWorldEvent}, {@code EntityMountEvent},
 * {@code EntityStruckByLightningEvent}, {@code EntityTravelToDimensionEvent},
 * {@code EntityMobGriefingEvent} — and no removal). So the seam is vanilla's own funnel.</p>
 *
 * <h2>Why {@code onEntityRemoved} is the right funnel</h2>
 *
 * <p>Every way an entity leaves a world passes through it, and it is production's own method — it
 * notifies the world's event listeners and calls {@code Entity.onRemovedFromWorld}. The four
 * callers, read in {@code build/rfg/minecraft-src}: the {@code isDead} sweep in
 * {@code World.updateEntities} (a killed or despawned entity, one tick after {@code setDead}), the
 * {@code unloadedEntityList} sweep in the same method (a chunk went away), the direct call in
 * {@code removeEntityDangerously} (a dimension transfer), and the player branch of
 * {@code removeEntity}. {@code WorldServer} and {@code WorldClient} both override it and both call
 * {@code super}, so one injection sees the removal on either side.</p>
 *
 * <p><b>{@code dead} is what separates a death from an unload</b>, and it is a plain read of the
 * argument: the {@code isDead} sweep arrives here with the flag set, while a chunk unload carries a
 * perfectly alive entity out of the world. A contract about a destroyed mount wants the first; a
 * contract about a ship leaving the client's view wants the second, and they are not the same event.</p>
 *
 * <h2>The filter, and the measurement that chose it</h2>
 *
 * <p>Three classes are skipped: {@code EntityItem}, {@code EntityXPOrb} and
 * {@code EntityFallingBlock}. The first two are the join recorder's own exclusions and the reason
 * transfers exactly — they arrive AND leave in dozens from one block break. <b>The third was
 * measured, after a guess about the same problem had already been wrong once.</b></p>
 *
 * <p>The first run that read this type's own eviction counter reported <b>2524 records evicted</b>
 * while every assertion on it passed, so the 256-record ring covered a fraction of one scenario. A
 * filter was written on the assumption that the flood was chunk UNLOAD (a crossing tears down a
 * thousand chunks and carries every entity in them out of the world, alive) — and it changed the
 * number by 3%. Dumping the ring's actual composition answered it in one line: <b>255 of the 256
 * records were {@code EntityFallingBlock}</b>. Every scenario here bulk-{@code fill}s its build
 * site, sand and gravel fall, and a landing falling-block is removed DEAD — which is why a
 * filter keyed on {@code dead} could not see it.</p>
 *
 * <p><b>No filter on the unload is kept</b>, because that one had no measurement behind it: a
 * removal is recorded whether the entity died or was carried out alive, and {@code dead} tells the
 * reader which. If a crossing's unload sweep ever turns out to fill this ring, the number will say
 * so and the filter can be written then, against it.</p>
 *
 * <h2>Side</h2>
 *
 * <p>Routed by the world the removal happens IN — deliberately BOTH sides, where
 * {@code entity_joined_world} is server-only. Its reason for excluding the client was that the two
 * copies of one entity join their worlds on their own clocks and would double the record; here that
 * is the point. The server's removal and the client's removal are separate facts, they land in
 * separate logs, and the second is the honest replacement for a bounded "how many are left within
 * 64 blocks" entity report — which reads a settled state at one instant and cannot see a removal
 * that happened and was followed by a respawn.</p>
 *
 * <h2>Silent about</h2>
 *
 * <p><b>WHY</b> the entity left: the four callers above are indistinguishable here beyond what
 * {@code dead} says, and nothing on this method names the killer. And the three skipped classes,
 * for which this event does not exist at all.</p>
 *
 * <p><b>The residual the filter leaves, with its number</b>: after it, the ring's remaining traffic
 * is passive mobs leaving loaded chunks — 67 evictions across two scenarios of one class, against
 * windows of a few ticks between each mark and its read. Deliberately not filtered further:
 * {@code MixinEntityPositionWriters} does keep a subject filter (players, dummies, anything ridden)
 * and its reason does NOT transfer, because it filters a rider re-positioned EVERY TICK by its
 * mount, while a removal happens once per entity. A reader who sees a gap checks
 * {@code droppedByType} for {@code entity_removed} before calling it an absence, and
 * {@code Events.droppedOf} is the verb for asking.</p>
 *
 * <p>{@code entity_joined_world} carries the same exposure on a chunk LOAD and its own filter also
 * omits {@code EntityFallingBlock}; it dropped ~2555 records in the same scenario, measured for the
 * first time here. That is its recorder's problem — and there are two of them, one per side.</p>
 */
@Mixin(World.class)
public abstract class MixinWorldEntityRemoval {

    private static final String INSTRUMENT = "world_entity_removal";

    // HEAD, not RETURN: the method has one exit but `@At("RETURN")` injects at every one of them,
    // and nothing here needs the world listeners to have been notified first.
    @Inject(method = "onEntityRemoved", at = @At("HEAD"))
    private void arTest$entityRemoved(Entity entity, CallbackInfo ci) {
        World self = (World) (Object) this;
        // Announced before the filter — a scenario reading a silence here needs to know the
        // observation point ran at all.
        TestTrace.instrument(self, INSTRUMENT);
        if (entity == null || entity instanceof EntityItem || entity instanceof EntityXPOrb
                || entity instanceof EntityFallingBlock) {
            return;
        }
        TestTrace.record(self, "entity_removed",
                "\"e\":" + entity.getEntityId()
                        + ",\"cls\":\"" + TestTrace.json(entity.getClass().getSimpleName()) + "\""
                        + ",\"dead\":" + entity.isDead
                        + ",\"x\":" + TestTrace.fmt(entity.posX)
                        + ",\"y\":" + TestTrace.fmt(entity.posY)
                        + ",\"z\":" + TestTrace.fmt(entity.posZ)
                        + ",\"dim\":" + (self.provider == null
                                ? Integer.MIN_VALUE : self.provider.getDimension()));
    }
}
