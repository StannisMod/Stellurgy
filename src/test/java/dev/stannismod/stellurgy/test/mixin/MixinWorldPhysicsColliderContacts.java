package dev.stannismod.stellurgy.test.mixin;

import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.valkyrienskies.mod.common.collision.WorldPhysicsCollider;
import org.valkyrienskies.mod.common.ships.ship_world.PhysicsObject;

import dev.stannismod.stellurgy.test.trace.PhysicsStepWindow;
import dev.stannismod.stellurgy.test.trace.TestTrace;

/**
 * Tells {@link PhysicsStepWindow} whether a craft was touched: the physics mod's world collider is
 * where a contact between a ship and the world is decided, so it is read there.
 *
 * <p>{@code tickUpdatingTheCollisionCache} runs once per physics step for every ship with physics —
 * the collider was LISTENING for that ship; {@code calculateCollisionImpulseForce} runs once per
 * collision impulse it applies to that ship's velocity — the ship was TOUCHED. Both feed the windows
 * watching that ship, so a window's zero contacts can be told from a collider that never ran.</p>
 *
 * <p>What it is silent about: contacts between two ships (another collider path), and a ship no window
 * has bound yet.</p>
 */
@Mixin(value = WorldPhysicsCollider.class, remap = false)
public abstract class MixinWorldPhysicsColliderContacts {

    @Shadow
    @Final
    private PhysicsObject parent;

    @Inject(method = "tickUpdatingTheCollisionCache", at = @At("HEAD"), remap = false)
    private void stellurgyTest$colliderListened(CallbackInfo ci) {
        TestTrace.instrument(parent.getWorld(), "server_ship_world_collider");
        PhysicsStepWindow.colliderTick(parent.getWorld(), parent);
    }

    @Inject(method = "calculateCollisionImpulseForce", at = @At("HEAD"), remap = false)
    private void stellurgyTest$contact(CallbackInfo ci) {
        PhysicsStepWindow.contact(parent.getWorld(), parent);
    }
}
