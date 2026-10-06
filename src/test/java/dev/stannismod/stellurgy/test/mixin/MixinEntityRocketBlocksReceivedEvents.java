package dev.stannismod.stellurgy.test.mixin;

import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraftforge.fml.relauncher.Side;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import dev.stannismod.stellurgy.entity.EntityRocket;
import dev.stannismod.stellurgy.test.trace.TestTrace;

/**
 * A client being given a rocket's blocks, as an event: {@code rocket_blocks_received}.
 *
 * <p>At the RETURN of {@code EntityRocket#useNetworkData} for a {@code RECIEVENBT} packet on a CLIENT
 * world — the point at which the client's copy of the rocket has read the blocks and stats the server
 * sent and rebuilt itself from them. Fields: {@code e} (the rocket's entity id, the same on both
 * sides) and {@code blocks} (whether the client's copy now holds a block store). Client log, routed by
 * the rocket. Read by {@code MachineGuiClientGroupTest}'s rocket methods.</p>
 *
 * <p>SILENT about WHY the server sent it — a push when the player started seeing the rocket, or any
 * other sender of that packet — and about the server side, which never takes this branch.</p>
 */
@Mixin(value = EntityRocket.class, remap = false)
public abstract class MixinEntityRocketBlocksReceivedEvents {

    private static final String INSTRUMENT = "rocket_blocks_events";

    @Inject(method = "useNetworkData", at = @At("RETURN"), require = 1)
    private void stellurgyTest$blocksReceived(EntityPlayer player, Side side, byte id, NBTTagCompound nbt,
                                              CallbackInfo ci) {
        EntityRocket self = (EntityRocket) (Object) this;
        if (self.world == null || !self.world.isRemote) {
            return;
        }
        TestTrace.instrument(self, INSTRUMENT);
        if (id == EntityRocket.PacketType.RECIEVENBT.ordinal()) {
            TestTrace.record(self, "rocket_blocks_received",
                    "\"e\":" + self.getEntityId() + ",\"blocks\":" + (self.storage != null));
        }
    }
}
