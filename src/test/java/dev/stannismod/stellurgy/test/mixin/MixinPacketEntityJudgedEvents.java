package dev.stannismod.stellurgy.test.mixin;

import net.minecraft.entity.Entity;
import net.minecraft.entity.player.EntityPlayer;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

import dev.stannismod.stellurgy.libvulpes.interfaces.INetworkEntity;
import dev.stannismod.stellurgy.libvulpes.network.PacketEntity;
import dev.stannismod.stellurgy.test.trace.TestTrace;

/**
 * The server judging whether a client's entity packet may be used, as an event:
 * {@code entity_packet_judged}.
 *
 * <p>Wraps the one {@code INetworkMachine#canBeUsedBy} call in {@code PacketEntity#executeServer} —
 * the question every client packet to an entity is asked before a byte of its payload reaches the
 * entity — and records production's answer, passed through untouched: {@code e} (the entity id the
 * packet named), {@code entity} (its simple class name), {@code dim}, {@code packet}, {@code player}
 * and {@code usable}. Server log, routed by the entity. Read by
 * {@code MachineGuiClientGroupTest#aRocketRefusesAPacketFromBeyondReach}.</p>
 *
 * <p>SILENT about a packet whose entity could not be resolved (no world, no such id, not a network
 * entity) — those return before the question is asked — and about what the packet then did.</p>
 */
@Mixin(value = PacketEntity.class, remap = false)
public abstract class MixinPacketEntityJudgedEvents {

    private static final String INSTRUMENT = "entity_packet_events";

    @Shadow
    byte packetId;

    @Shadow
    private int dimId;

    @Shadow
    private int entityId;

    @Redirect(method = "executeServer", at = @At(value = "INVOKE",
            target = "Ldev/stannismod/stellurgy/libvulpes/interfaces/INetworkEntity;canBeUsedBy(Lnet/minecraft/entity/player/EntityPlayer;)Z"),
            require = 1)
    private boolean stellurgyTest$judged(INetworkEntity target, EntityPlayer player) {
        boolean usable = target.canBeUsedBy(player);
        if (target instanceof Entity) {
            Entity entity = (Entity) target;
            TestTrace.instrument(entity, INSTRUMENT);
            TestTrace.record(entity, "entity_packet_judged",
                    "\"e\":" + entityId
                            + ",\"entity\":\"" + TestTrace.json(target.getClass().getSimpleName()) + "\""
                            + ",\"dim\":" + dimId
                            + ",\"packet\":" + packetId
                            + ",\"player\":\"" + (player == null ? "" : TestTrace.json(player.getName())) + "\""
                            + ",\"usable\":" + usable);
        }
        return usable;
    }
}
