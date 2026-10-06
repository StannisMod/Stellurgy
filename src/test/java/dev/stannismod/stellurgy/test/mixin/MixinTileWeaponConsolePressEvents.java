package dev.stannismod.stellurgy.test.mixin;

import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.util.math.BlockPos;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

import dev.stannismod.stellurgy.test.trace.TestTrace;
import dev.stannismod.stellurgy.tile.weapon.TileWeaponConsole;

/**
 * A weapon console judging whether a press may act, as an event: {@code weapon_console_press_judged}.
 *
 * <p>Wraps the {@code canInteractWithContainer} call inside the SERVER branch of
 * {@code useNetworkData} — the one question a press arriving as a packet is asked before it may
 * change the network — and records production's own answer: {@code pos} (the console),
 * {@code player} (who sent it) and {@code reachable} (what {@code canInteractWithContainer}
 * returned). The answer is passed through untouched. Server log. Read by
 * {@code MachineGuiClientGroupTest#aWeaponConsolePressFromBeyondReachChangesNothing}.</p>
 *
 * <p>SILENT about the client branch (a readout arriving), which asks nothing, and about what the
 * press then did — that is the network's state, read by the test through the console.</p>
 */
@Mixin(TileWeaponConsole.class)
public abstract class MixinTileWeaponConsolePressEvents {

    private static final String INSTRUMENT = "weapon_console_press_events";

    @Redirect(method = "useNetworkData", at = @At(value = "INVOKE",
            target = "Ldev/stannismod/stellurgy/tile/weapon/TileWeaponConsole;canInteractWithContainer(Lnet/minecraft/entity/player/EntityPlayer;)Z"),
            require = 1)
    private boolean stellurgyTest$judged(TileWeaponConsole console, EntityPlayer player) {
        boolean reachable = console.canInteractWithContainer(player);
        if (console.getWorld() != null && !console.getWorld().isRemote) {
            BlockPos pos = console.getPos();
            TestTrace.instrument(console.getWorld(), INSTRUMENT);
            TestTrace.record(console.getWorld(), "weapon_console_press_judged",
                    "\"pos\":\"" + pos.getX() + "," + pos.getY() + "," + pos.getZ() + "\""
                            + ",\"player\":\"" + (player == null ? "" : player.getName()) + "\""
                            + ",\"reachable\":" + reachable);
        }
        return reachable;
    }
}
