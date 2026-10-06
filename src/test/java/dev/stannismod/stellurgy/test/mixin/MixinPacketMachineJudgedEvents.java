package dev.stannismod.stellurgy.test.mixin;

import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.util.math.BlockPos;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

import dev.stannismod.stellurgy.libvulpes.network.PacketMachine;
import dev.stannismod.stellurgy.libvulpes.util.INetworkMachine;
import dev.stannismod.stellurgy.test.trace.TestTrace;

/**
 * The server judging whether a client's machine packet may be used, as an event:
 * {@code machine_packet_judged}.
 *
 * <p>Wraps the one {@code INetworkMachine#canBeUsedBy} call in {@code PacketMachine#executeServer} —
 * the question every client press is asked before a byte of its payload reaches the machine — and
 * records production's answer, passed through untouched: {@code machine} (the tile's simple class
 * name), {@code pos}, {@code dim}, {@code packet} (the machine packet id), {@code player} (who sent
 * it) and {@code usable}. Server log, routed by the machine's world.</p>
 *
 * <p>SILENT about a packet whose machine could not be resolved (no world, block not loaded, no
 * machine there) — those return before the question is asked — and about what the press then did,
 * which is the machine's own state. Read by {@code MachineGuiClientGroupTest}'s weapon-console and
 * fire-control-sensor reach methods.</p>
 */
@Mixin(value = PacketMachine.class, remap = false)
public abstract class MixinPacketMachineJudgedEvents {

    private static final String INSTRUMENT = "machine_packet_events";

    @Shadow
    byte packetId;

    @Shadow
    private int dimId;

    @Shadow
    private BlockPos pos;

    @Redirect(method = "executeServer", at = @At(value = "INVOKE",
            target = "Ldev/stannismod/stellurgy/libvulpes/util/INetworkMachine;canBeUsedBy(Lnet/minecraft/entity/player/EntityPlayer;)Z"),
            require = 1)
    private boolean stellurgyTest$judged(INetworkMachine machine, EntityPlayer player) {
        boolean usable = machine.canBeUsedBy(player);
        if (machine instanceof TileEntity && ((TileEntity) machine).getWorld() != null) {
            TileEntity tile = (TileEntity) machine;
            TestTrace.instrument(tile.getWorld(), INSTRUMENT);
            TestTrace.record(tile.getWorld(), "machine_packet_judged",
                    "\"machine\":\"" + TestTrace.json(machine.getClass().getSimpleName()) + "\""
                            + ",\"pos\":\"" + pos.getX() + "," + pos.getY() + "," + pos.getZ() + "\""
                            + ",\"dim\":" + dimId
                            + ",\"packet\":" + packetId
                            + ",\"player\":\"" + (player == null ? "" : TestTrace.json(player.getName())) + "\""
                            + ",\"usable\":" + usable);
        }
        return usable;
    }
}
