package dev.stannismod.stellurgy.test.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import net.minecraft.world.World;

import org.valkyrienskies.mod.common.ships.ShipData;
import org.valkyrienskies.mod.common.ships.ship_world.PhysicsObject;
import org.valkyrienskies.mod.common.ships.ship_transform.ShipTransform;

import dev.stannismod.stellurgy.test.trace.TestTrace;

/**
 * A server physics object ADOPTING a pose that was written over it — the tick on which a teleport
 * stops being a write and becomes where the ship is.
 *
 * <p>A teleport writes the ship's {@code ShipData} transform and raises
 * {@code forceToUseShipDataTransform}; nothing moves the loaded physics pipeline until
 * {@code PhysicsObject#onTick} consumes that flag, copies the written transform into all four of its
 * own transforms and then copies the tick transform back into {@code ShipData}. Before that tick a
 * reader of the pose is reading the write; after it, the object's own state. This records the
 * second.</p>
 *
 * <ul>
 *   <li>{@code ship_teleport_adopted} — the RETURN of a server {@code onTick} whose HEAD saw the flag
 *       raised. Payload: {@code vsShip} (the physics uuid), {@code dim}, and {@code posX}/{@code posY}/
 *       {@code posZ} — the {@code ShipData} pose as that tick left it, i.e. what was adopted.</li>
 * </ul>
 *
 * <p>The flag is read at HEAD because {@code onTick} clears it in its first statements, and it is
 * carried to RETURN on the physics object itself (a {@link Unique} field): the object that owns the
 * flag owns the memory of it, so nothing here outlives the ship it describes. The instrument is
 * declared at every server HEAD, whether or not a teleport is pending, so an absent record can be
 * told from an absent observer.</p>
 *
 * <p>SILENT about: the client's physics object (a client {@code onTick} never reads the flag); a
 * ship whose physics object is not loaded when it is teleported (the write then goes to
 * {@code ShipData} alone and no object ever adopts it); and whether a physics-thread pass that began
 * before the teleport writes its own transform afterwards — that pass consumes the physics side's
 * own force flag, which this does not watch.</p>
 *
 * <p>The target is a vendored class the project compiles, with no vanilla names, so nothing here
 * may be SRG-remapped.</p>
 */
@Mixin(value = PhysicsObject.class, remap = false)
public abstract class MixinPhysicsObjectTeleportAdoption {

    private static final String INSTRUMENT = "physics_object_teleport_adoption";

    @Shadow
    private boolean forceToUseShipDataTransform;

    @Shadow
    public abstract World getWorld();

    @Shadow
    public abstract ShipData getShipData();

    /** Whether THIS object's current {@code onTick} began with a pose to adopt. */
    @Unique
    private boolean stellurgyTest$adoptingThisTick;

    @Inject(method = "onTick", at = @At("HEAD"), remap = false, require = 1)
    private void stellurgyTest$teleportAdoptionHead(CallbackInfo ci) {
        World world = getWorld();
        if (world == null || world.isRemote) {
            return;
        }
        TestTrace.instrument(world, INSTRUMENT);
        stellurgyTest$adoptingThisTick = forceToUseShipDataTransform;
    }

    @Inject(method = "onTick", at = @At("RETURN"), remap = false, require = 1)
    private void stellurgyTest$teleportAdoptionReturn(CallbackInfo ci) {
        if (!stellurgyTest$adoptingThisTick) {
            return;
        }
        stellurgyTest$adoptingThisTick = false;
        World world = getWorld();
        ShipData ship = getShipData();
        ShipTransform pose = ship.getShipTransform();
        TestTrace.record(world, "ship_teleport_adopted", "\"vsShip\":\"" + ship.getUuid() + "\",\"dim\":"
                + world.provider.getDimension() + ",\"posX\":" + pose.getPosX() + ",\"posY\":"
                + pose.getPosY() + ",\"posZ\":" + pose.getPosZ());
    }
}
