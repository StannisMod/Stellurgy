package dev.stannismod.stellurgy.util;

import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.inventory.Container;

import dev.stannismod.stellurgy.Stellurgy;

import java.util.Collections;
import java.util.Set;
import java.util.WeakHashMap;

/**
 * The players of one server ({@code ServerState}) whose rocket GUI may stay open past the vanilla
 * container-interaction range: the rocket moves, and the GUIs a player opens from it address tiles
 * inside the rocket, which a distance check against the world would close at once.
 */
public class RocketInventoryHelper {

    //TODO: more robust way of inv checking
    // Weak keys: a player who dies or logs out leaves nothing behind.
    private final Set<EntityPlayerMP> bypass = Collections.newSetFromMap(new WeakHashMap<>());

    /**
     * Decides whether the vanilla {@code openContainer.canInteractWith}
     * check inside {@code EntityPlayer(MP).onUpdate} should be force-skipped
     * for a given player. Returns {@code true} (i.e. "behave as if the
     * container is in interaction range") when the player is currently in
     * the rocket-inventory bypass set; otherwise delegates to vanilla's
     * own check.
     *
     * <p>Extracted so the
     * {@code MixinEntityPlayer(MP)InventoryAccess @Redirect}
     * bodies stay one line and the redirect's semantics are unit-testable
     * without running the full Mixin pipeline. The mixin redirects to this
     * helper; this helper is the single source of truth for "should Stellurgy
     * keep the rocket inventory GUI open past the vanilla distance gate".
     * </p>
     *
     * @param container the container vanilla was about to {@code
     *                  canInteractWith}-check (never {@code null} on the
     *                  vanilla call site — the {@code openContainer != null}
     *                  guard fires first).
     * @param player    the player whose {@code onUpdate} tick is running.
     * @return {@code true} when Stellurgy's bypass set says yes (skips
     *         close-screen path); otherwise the container's own
     *         {@code canInteractWith} result.
     */
    public static boolean shouldAllowContainerInteract(Container container, EntityPlayer player) {
        if (canPlayerBypassInvChecks(player)) {
            return true;
        }
        return container.canInteractWith(player);
    }

    /**
     * Whether {@code player} is in his server's bypass set. Only a server player can be: the set is
     * written on the server alone, so a client player is answered without looking at any server.
     */
    public static boolean canPlayerBypassInvChecks(EntityPlayer player) {
        return player instanceof EntityPlayerMP
                && Stellurgy.serverState().rocketInventory.bypass.contains(player);
    }

    public void removePlayerFromInventoryBypass(EntityPlayerMP player) {
        bypass.remove(player);
    }

    public void addPlayerToInventoryBypass(EntityPlayerMP player) {
        bypass.add(player);
    }
}
