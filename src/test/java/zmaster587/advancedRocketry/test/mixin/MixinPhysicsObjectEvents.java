package zmaster587.advancedRocketry.test.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import net.minecraft.world.World;

import org.valkyrienskies.mod.common.ships.ShipData;
import org.valkyrienskies.mod.common.ships.ship_world.PhysicsObject;

import zmaster587.advancedRocketry.test.trace.TestTrace;

/**
 * A ship is LOADED in a world — has a live physics object, claimed chunks, a renderer on the
 * client — and is unloaded again, as events, on whichever side the world lives.
 *
 * <p>This is the second of two facts about a ship's existence, and it is not the registry's.
 * {@code ship_spawned} / {@code ship_removed} ({@link MixinQueryableShipDataEvents}) say that the
 * registry KNOWS a ship; a ship the registry knows may still have no physics object at all — after a
 * cross-dimension carry, before a client has received its data, or once its chunks have been let go.
 * What a body standing on a deck, a chunk read through the ship's cache, or a client render actually
 * depends on is the {@code PhysicsObject}, and that is what these two records witness.</p>
 *
 * <ul>
 *   <li>{@code ship_loaded} — the RETURN of the {@code PhysicsObject(World, ShipData)} constructor:
 *       the object is fully built (chunk caches, transformation manager, and on the client its
 *       renderer). Constructed at spawn ({@code WorldServerShipManager}, the spawn queue), at load
 *       (the same manager's load queue) and on the client ({@code WorldClientShipManager}'s load
 *       queue). Payload: {@code vsShip} (the physics uuid), {@code name}, {@code dim},
 *       {@code remote} (the world's own {@code isRemote}, not the calling thread's side).</li>
 *   <li>{@code ship_unloaded} — the HEAD of {@code unload()}: the chunk claim is about to be queued
 *       for unload (server) or dropped and its renderers killed (client). Recorded at HEAD so the
 *       ship's data is still readable. Payload: {@code vsShip}, {@code name}, {@code dim}.</li>
 * </ul>
 *
 * <p>Both are recorded through {@link TestTrace#recordHere}, so the side is the effective side of
 * the thread the manager runs on — the server thread for the server manager, the client thread for
 * the client's — and each record lands in that side's log.</p>
 *
 * <p>SILENT about: the registry (see the registry pair above); whether the ship's chunks have
 * ARRIVED on the client (a client physics object is deliberately built before all its chunks are
 * present — {@code updateChunk} fills them in later); whether physics is enabled (the object holds
 * physics off for its first ticks); and any ship that is removed from a manager without
 * {@code unload()} being called. The constructor has a single exit, so its RETURN fires exactly
 * once per object; there is no early return to filter.</p>
 *
 * <p>The target is a vendored class the project compiles, with no vanilla names, so nothing here
 * may be SRG-remapped.</p>
 */
@Mixin(value = PhysicsObject.class, remap = false)
public abstract class MixinPhysicsObjectEvents {

    private static final String INSTRUMENT = "physics_object_events";

    @Shadow
    public abstract World getWorld();

    @Shadow
    public abstract ShipData getShipData();

    @Inject(method = "<init>", at = @At("RETURN"), remap = false)
    private void arTest$loaded(World world, ShipData initial, CallbackInfo ci) {
        TestTrace.instrumentHere(INSTRUMENT);
        TestTrace.recordHere("ship_loaded", "\"vsShip\":\"" + initial.getUuid() + "\",\"name\":\""
                + TestTrace.json(initial.getName()) + "\",\"dim\":" + world.provider.getDimension()
                + ",\"remote\":" + world.isRemote);
    }

    @Inject(method = "unload", at = @At("HEAD"), remap = false)
    private void arTest$unloaded(CallbackInfo ci) {
        TestTrace.instrumentHere(INSTRUMENT);
        ShipData ship = getShipData();
        World world = getWorld();
        TestTrace.recordHere("ship_unloaded", "\"vsShip\":\"" + (ship == null ? "null" : ship.getUuid())
                + "\",\"name\":\"" + (ship == null ? "" : TestTrace.json(ship.getName()))
                + "\",\"dim\":" + (world == null ? "null" : String.valueOf(world.provider.getDimension())));
    }
}
