package zmaster587.advancedRocketry.test.mixin;

import net.minecraft.entity.Entity;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraftforge.fml.common.gameevent.PlayerEvent.PlayerLoggedOutEvent;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import zmaster587.advancedRocketry.space.ShipAboardTag;
import zmaster587.advancedRocketry.space.SpaceEventHandler;
import zmaster587.advancedRocketry.test.trace.TestTrace;

/**
 * A player's LOGOUT, as the space subsystem finished handling it — with the aboard record his next
 * login will read back.
 *
 * <p>{@code player_logged_out} observes the RETURN of {@link SpaceEventHandler#onPlayerLoggedOut},
 * the subsystem's {@code PlayerLoggedOutEvent} subscriber. Forge fires that event at the top of
 * {@code PlayerList.playerLoggedOut}, BEFORE the player file is written and before he is removed
 * from his world, and the handler's body begins by reconciling his aboard record; so by the time
 * this record is taken the tag read off him through {@link ShipAboardTag#of} is the reconciled one
 * — the very bytes a relog test's next login resolves from. The record carries who left, the
 * dimension he was in, whether he was tagged aboard a tier-2 ship, which ship, his posture on it and
 * what he was riding. A relog scenario used to poll for the player's absence and then trust that the
 * tag "must have been" refreshed; this is the moment it was.</p>
 *
 * <p>Routed by the player's own world, which at this point is still his server world, so the record
 * lands in the SERVER log even under an integrated server. The target is a plain server-side
 * subscriber and exists on both sides, so the mixin is on the common list; on a pure client it wove
 * onto a class that never receives the event, and stays silent there.</p>
 *
 * <p>SILENT about: the {@code event.player == null} early return (the injection fires at every
 * return of the method and this one is filtered out — a null player is not a logout anyone can name);
 * whether the held cell was actually released or a pending seat dropped (both are private to the
 * handler and neither is observable from a return); and the ORDER of this record against the other
 * {@code PlayerLoggedOutEvent} subscribers, which is bus-registration order and is measured on a run,
 * never assumed. {@code riding} is the simple class name of the mount he was on (an
 * {@code EntityDummy} for an AR seat) or {@code "none"} — it says what he sat on, not whether the
 * tag agrees with it.</p>
 */
@Mixin(SpaceEventHandler.class)
public abstract class MixinSpaceEventHandlerEvents {

    private static final String INSTRUMENT = "space_logout_events";

    @Inject(method = "onPlayerLoggedOut", at = @At("RETURN"))
    private void arTest$playerLoggedOut(PlayerLoggedOutEvent event, CallbackInfo ci) {
        TestTrace.instrumentHere(INSTRUMENT);
        if (event == null || event.player == null) {
            // The handler's own null-player early return — nothing to name.
            return;
        }
        EntityPlayer player = event.player;
        ShipAboardTag.Aboard aboard = ShipAboardTag.of(player);
        Entity mount = player.getRidingEntity();
        String payload = "\"who\":\"" + TestTrace.json(player.getName())
                + "\",\"dim\":" + player.dimension
                + ",\"tagged\":" + (aboard != null)
                + ",\"ship\":\"" + (aboard == null ? "null" : String.valueOf(aboard.shipId))
                + "\",\"posture\":\"" + (aboard == null ? "none" : aboard.posture.name())
                + "\",\"riding\":\"" + (mount == null ? "none" : mount.getClass().getSimpleName())
                + "\"";
        if (player.world != null) {
            TestTrace.record(player, "player_logged_out", payload);
        } else {
            // Forge fires the event before the world lets go of him, so this branch is not expected;
            // it exists so a record is never lost to a routing null, and it names itself.
            TestTrace.recordServer("player_logged_out", payload + ",\"worldless\":true");
        }
    }
}
