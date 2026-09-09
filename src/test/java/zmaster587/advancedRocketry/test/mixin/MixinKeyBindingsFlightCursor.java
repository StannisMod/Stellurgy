package zmaster587.advancedRocketry.test.mixin;

import net.minecraft.client.Minecraft;
import net.minecraft.client.entity.EntityPlayerSP;
import net.minecraft.entity.Entity;
import net.minecraftforge.fml.common.gameevent.TickEvent;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import zmaster587.advancedRocketry.client.KeyBindings;
import zmaster587.advancedRocketry.test.trace.TestTrace;

/**
 * The flight cursor, recorded where production moves it.
 *
 * <h2>What this replaces</h2>
 *
 * <p>Eight assertions read {@code clientDouble("…client.KeyBindings", "flightCursorX")} — the harness
 * reaching through the socket into a private static by name. The value is real production state (the
 * Elite-style virtual cursor whose POSITION is the command), so unlike the seven fields cut from
 * {@code ShipFrameTravel} this morning it is not diagnostics that leaked into production: what is
 * wrong is only the reading of it.</p>
 *
 * <p>And the reading is wrong in a way a poll cannot fix. {@code flightCursorX} keeps its last value
 * when the input path stops running — a disengaged mode, a screen open, a tick that returned early —
 * so a test that polls it cannot tell "the cursor is where I left it" from "nothing has updated it
 * since". Two of the readers are centring LOOPS that nudge the mouse and re-read: against a dead
 * input path they nudge two hundred times and then proceed with a stale number that looks like a
 * measurement. A record exists only when production actually ran, so an empty window says so.</p>
 *
 * <h2>The two paths, and why both</h2>
 *
 * <p>Production updates the cursor in two places and they are different commands: {@code
 * onClientTick} drives a free-flying ROCKET, {@code handleShipPilotInput} drives a tier-2 SHIP from
 * its seat. The {@code path} field says which, so a reader is not left inferring it from context —
 * and a scenario that expected one and got the other sees that rather than a plausible number.</p>
 *
 * <p>{@code onClientTick} is injected at TAIL — its last return, which only the free-flight path
 * reaches; the five early {@code return}s above it are separate instructions and are not touched, so
 * a tick that never looked at the cursor writes no record. {@code handleShipPilotInput} is injected
 * at RETURN and records only when it returned {@code true}, which is production's own statement that
 * this tick was a piloting one.</p>
 */
@Mixin(KeyBindings.class)
public abstract class MixinKeyBindingsFlightCursor {

    /** Production's own answers. Shadowed accessors rather than the fields: what a test wants is
     *  what production would tell a caller, and the interpolating overloads are a different question
     *  (a per-FRAME value) that no reader here is asking. */
    @Shadow
    public static float flightCursorX() {
        throw new AssertionError();
    }

    @Shadow
    public static float flightCursorY() {
        throw new AssertionError();
    }

    @Inject(method = "onClientTick", at = @At("TAIL"))
    private void arTest$rocketCursor(TickEvent.ClientTickEvent event, CallbackInfo ci) {
        arTest$cursor(Minecraft.getMinecraft().player, "rocket");
    }

    @Inject(method = "handleShipPilotInput", at = @At("RETURN"))
    private void arTest$shipCursor(Minecraft mc, EntityPlayerSP player,
                                   CallbackInfoReturnable<Boolean> cir) {
        if (Boolean.TRUE.equals(cir.getReturnValue())) {
            arTest$cursor(player, "ship");
        }
    }

    private static void arTest$cursor(Entity player, String path) {
        if (player == null || player.world == null) {
            return;
        }
        TestTrace.instrument(player, "flight_cursor_events");
        TestTrace.record(player, "flight_cursor",
                "\"e\":" + player.getEntityId()
                        + ",\"path\":\"" + path + "\""
                        + ",\"x\":" + flightCursorX()
                        + ",\"y\":" + flightCursorY());
    }
}
