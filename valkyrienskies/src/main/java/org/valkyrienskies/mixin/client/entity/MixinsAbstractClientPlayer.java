package org.valkyrienskies.mixin.client.entity;

import net.minecraft.client.entity.AbstractClientPlayer;
import net.minecraft.util.math.BlockPos;
import org.spongepowered.asm.mixin.Mixin;
import org.valkyrienskies.mod.common.ValkyrienSkiesMod;
import org.valkyrienskies.mod.common.piloting.ControllerInputType;
import org.valkyrienskies.mod.common.piloting.IShipPilotClient;
import org.valkyrienskies.mod.common.piloting.PilotControlsMessage;
import org.valkyrienskies.mod.common.ships.ship_world.PhysicsObject;

/**
 * Todo: Replace this with a capability
 */
@Deprecated
@Mixin(AbstractClientPlayer.class)
public abstract class MixinsAbstractClientPlayer implements IShipPilotClient {

    /** Which pilot edge keys were down at this player's previous sample — this player's own input,
     *  gone with the player object. */
    @org.spongepowered.asm.mixin.Unique
    private final boolean[] vs$keysDownLastTick = new boolean[PilotControlsMessage.EDGE_KEYS];

    @Override
    public void onClientTick() {
        if (isPiloting()) {
            sendPilotKeysToServer(this.getControllerInputEnum(), getPilotedShip(),
                getPosBeingControlled());
        }
    }

    private void sendPilotKeysToServer(ControllerInputType type, PhysicsObject shipPiloting,
                                       BlockPos blockBeingControlled) {
        PilotControlsMessage keyMessage = new PilotControlsMessage();
        if (type == null) {
            System.out.println("This is totally wrong");
            type = ControllerInputType.CaptainsChair;
        }
        // System.out.println(blockBeingControlled);
        keyMessage.assignKeyBooleans(shipPiloting, type, vs$keysDownLastTick);
        keyMessage.controlBlockPos = blockBeingControlled;

        dev.stannismod.stellurgy.Stellurgy.instance.valkyrienSkies.controlNetwork.sendToServer(keyMessage);
    }

}
