package org.valkyrienskies.mod.common.network;

import net.minecraft.client.Minecraft;
import net.minecraft.client.entity.EntityPlayerSP;
import net.minecraft.network.play.client.CPacketPlayer;
import org.joml.Vector3d;
import org.valkyrienskies.mod.common.entity.EntityShipMovementData;
import org.valkyrienskies.mod.common.ships.QueryableShipData;
import org.valkyrienskies.mod.common.ships.ShipData;
import org.valkyrienskies.mod.common.ships.entity_interaction.IDraggable;
import org.valkyrienskies.mod.common.ships.ship_transform.ShipTransform;
import org.valkyrienskies.mod.common.util.JOML;
import org.valkyrienskies.mod.common.util.ValkyrienUtils;
import valkyrienwarfare.api.TransformType;

import java.util.UUID;

public class PlayerMovementDataGenerator {

    /**
     * Only works on the client.
     */
    public static PlayerMovementData generatePlayerMovementDataForClient() {
        final EntityPlayerSP entityPlayer = Minecraft.getMinecraft().player;
        final EntityShipMovementData entityShipMovementData = ValkyrienUtils.getEntityShipMovementDataFor(entityPlayer);

        // A point declared in a ship's frame this tick is sent as it is: the server maps it through
        // its own pose, so no difference between this side's pose and the server's can move the
        // player on the deck he stands on.
        final IDraggable draggable = IDraggable.class.cast(entityPlayer);
        final UUID claimShipId = draggable.getMovementClaimShip();
        final ShipData claimShip = claimShipId == null
                ? null : QueryableShipData.get(entityPlayer.world).getShip(claimShipId).orElse(null);
        if (claimShip != null) {
            final Vector3d lookInShip = JOML.convert(entityPlayer.getLook(1));
            claimShip.getShipTransform().transformDirection(lookInShip, TransformType.GLOBAL_TO_SUBSPACE);
            return new PlayerMovementData(
                    claimShipId,
                    0,
                    entityShipMovementData.getTicksPartOfGround(),
                    new Vector3d(draggable.getMovementClaimInShip()),
                    lookInShip,
                    entityPlayer.onGround
            );
        }

        final ShipData lastTouchedShip = entityShipMovementData.getLastTouchedShip();
        final UUID lastTouchedShipId = lastTouchedShip != null ? lastTouchedShip.getUuid() : null;
        final Vector3d playerPosInLocal = new Vector3d(entityPlayer.posX, entityPlayer.posY, entityPlayer.posZ);
        final Vector3d playerLookInLocal = JOML.convert(entityPlayer.getLook(1));
        final boolean onGround = entityPlayer.onGround;

        if (lastTouchedShip != null) {
            final ShipTransform shipTransform = lastTouchedShip.getShipTransform();
            shipTransform.transformPosition(playerPosInLocal, TransformType.GLOBAL_TO_SUBSPACE);
            shipTransform.transformDirection(playerLookInLocal, TransformType.GLOBAL_TO_SUBSPACE);
        }

        return new PlayerMovementData(
                lastTouchedShipId,
                entityShipMovementData.getTicksSinceTouchedShip(),
                entityShipMovementData.getTicksPartOfGround(),
                playerPosInLocal,
                playerLookInLocal,
                onGround
        );
    }
}
