package zmaster587.advancedRocketry.test.mixin;

import java.lang.ref.WeakReference;
import java.util.WeakHashMap;

import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.inventory.Container;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import zmaster587.advancedRocketry.test.trace.TestTrace;
import zmaster587.advancedRocketry.util.RocketInventoryHelper;

/**
 * The rocket-inventory distance gate's decision as an event: whether vanilla's per-tick
 * {@code openContainer.canInteractWith(player)} check was answered "keep it open" or "close it",
 * and by which path.
 *
 * <p>{@code container_interact_checked} IS the return of
 * {@link RocketInventoryHelper#shouldAllowContainerInteract(Container, EntityPlayer)} — the single
 * helper both {@code MixinEntityPlayer(MP)InventoryAccess} {@code @Redirect}s route vanilla's check
 * through. {@code allowed} is the value the redirect handed back to {@code onUpdate}; {@code false}
 * means vanilla closes the screen on that same tick and puts {@code ContainerPlayer} back as the
 * open container. {@code bypass} says WHY a {@code true} was true: the AR bypass set, or the
 * container's own range check.</p>
 *
 * <p><b>Seam and side.</b> RETURN of the helper, read off {@code cir.getReturnValue()}; the record is
 * routed by the player's world. Both vanilla call sites — {@code EntityPlayer.onUpdate} and
 * {@code EntityPlayerMP.onUpdate} — sit behind {@code !this.world.isRemote}, so in practice every
 * record lands in the SERVER log; a client player never reaches the seam.</p>
 *
 * <p><b>Edge-only.</b> The check runs every server tick for every player, twice (the {@code MP}
 * override and the {@code super.onUpdate()} it calls), and {@code openContainer} is never null — it
 * is the inventory container when no GUI is open. A per-tick record would turn the 256-ring over in
 * seconds. So a record is taken only when, for that player, the container object or the decision
 * CHANGED against the last one seen: opening a GUI is an edge (new container), the gate refusing it
 * is an edge ({@code allowed} flips), and vanilla's resulting close is an edge too (the container
 * becomes {@code ContainerPlayer} again). The first check ever seen for a player is an edge.</p>
 *
 * <p><b>Silent about</b>: the client side entirely (see above); a check whose answer did not change
 * (a GUI held open across a hundred ticks yields ONE record, not a hundred); the reason a container
 * refused (that is the container's {@code canInteractWith}, opaque here); and the bypass set's
 * membership changes themselves — an add or remove only becomes visible on the next check whose
 * decision it moved. The instrument reports itself on every call, edge or not.</p>
 */
@Mixin(RocketInventoryHelper.class)
public abstract class MixinRocketInventoryHelperEvents {

    private static final String INSTRUMENT = "container_interact_events";

    // What was last recorded for a player: the container identity and the decision. Two parallel
    // maps rather than a nested value class, so the mixin carries no inner class for the
    // transformer to relocate. Weak on the player so a logout does not pin the entry; the
    // container is held weakly too, because a container references its player and a strong value
    // would keep its own key alive. Private statics are permitted on a mixin (the refusal is for
    // NON-private ones).
    private static final WeakHashMap<EntityPlayer, WeakReference<Container>> LAST_CONTAINER =
            new WeakHashMap<EntityPlayer, WeakReference<Container>>();
    private static final WeakHashMap<EntityPlayer, Boolean> LAST_ALLOWED =
            new WeakHashMap<EntityPlayer, Boolean>();

    @Inject(method = "shouldAllowContainerInteract", at = @At("RETURN"))
    private static void arTest$checked(Container container, EntityPlayer player,
                                       CallbackInfoReturnable<Boolean> cir) {
        TestTrace.instrument(player, INSTRUMENT);
        if (player == null || player.world == null) {
            return;
        }
        boolean allowed = cir.getReturnValueZ();
        synchronized (LAST_CONTAINER) {
            WeakReference<Container> lastContainer = LAST_CONTAINER.get(player);
            Boolean lastAllowed = LAST_ALLOWED.get(player);
            if (lastContainer != null && lastAllowed != null
                    && lastContainer.get() == container && lastAllowed.booleanValue() == allowed) {
                return;
            }
            LAST_CONTAINER.put(player, new WeakReference<Container>(container));
            LAST_ALLOWED.put(player, Boolean.valueOf(allowed));
        }
        TestTrace.record(player, "container_interact_checked", "\"e\":" + player.getEntityId()
                + ",\"who\":\"" + TestTrace.json(player.getName())
                + "\",\"container\":\"" + TestTrace.json(containerName(container))
                + "\",\"allowed\":" + allowed
                + ",\"bypass\":" + RocketInventoryHelper.canPlayerBypassInvChecks(player));
    }

    private static String containerName(Container container) {
        if (container == null) {
            return "null";
        }
        String simple = container.getClass().getSimpleName();
        // An anonymous container has no simple name; fall back to the binary name's last segment.
        if (simple.isEmpty()) {
            String name = container.getClass().getName();
            return name.substring(name.lastIndexOf('.') + 1);
        }
        return simple;
    }
}
