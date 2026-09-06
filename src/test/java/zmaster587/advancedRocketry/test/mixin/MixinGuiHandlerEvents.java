package zmaster587.advancedRocketry.test.mixin;

import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.world.World;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import zmaster587.advancedRocketry.inventory.GuiHandler;
import zmaster587.advancedRocketry.test.trace.TestTrace;

/**
 * The server's answer to "open a GUI" as an event: which container (if any) AR's gui handler served
 * for a request, and to whom.
 *
 * <h2>What {@code gui_container_served} IS</h2>
 *
 * <p>Every {@code openGui} on the AR mod instance ends in Forge asking the registered handler for a
 * server-side {@code Container}. AR's {@code inventory.GuiHandler} is the fallback of the
 * {@code AffsGuiRouter} chain, so every non-AFFS id (AR's own {@code guiId} enum and, by delegation,
 * every libVulpes {@code MODULAR*} id) passes through
 * {@code getServerGuiElement(int, EntityPlayer, World, int, int, int)}. The record is taken at its
 * RETURN — every return, which is three: the {@code null} for a held-stack desync (the request said
 * "item gui" but the main hand holds no modular item), the {@code ContainerOreMappingSatellite}, and
 * whatever the libVulpes handler answered for a delegated id (a container, or {@code null} for an id
 * outside its enum). The payload carries the return value as its simple class name, or the string
 * {@code "null"} — a refused request is an event too, and a chain waiting for a GUI needs to see
 * the refusal rather than a silence.</p>
 *
 * <p>Payload: {@code id} (the mod gui id as Forge handed it over, AFFS offset already removed by the
 * router), {@code who} (the requesting player's name), {@code container} (simple class name or
 * {@code "null"}), {@code at} (the raw {@code x,y,z} triple as a string — a block position for a tile
 * gui; for an entity gui {@code x} is the entity id; for an item gui {@code x == -1} and
 * {@code y < -1}, per the handler's own convention). The record is routed by the player's world,
 * which on this seam is the server's, so it lands in the server log stamped with that world's clock;
 * a request with no player falls back to the overworld clock.</p>
 *
 * <h2>What this mixin is SILENT about</h2>
 *
 * <p>The CLIENT half: {@code getClientGuiElement} is not observed here — the client's screen
 * opening is the harness recorder's {@code client_gui_opened}, off {@code GuiOpenEvent}. An AFFS gui
 * ({@code id >= AffsGuiRouter.AFFS_GUI_BASE}) is served by the AFFS handler and never reaches this
 * method, so it never records. A gui opened on any OTHER mod instance (libVulpes' own, a third
 * mod's) goes to that mod's handler, not this one. And the seam says nothing about whether the
 * container was then actually opened for the player — Forge's {@code openGui} does that after this
 * call returns, and a {@code null} answer here is exactly the case where it will not.</p>
 */
@Mixin(GuiHandler.class)
public abstract class MixinGuiHandlerEvents {

    private static final String INSTRUMENT = "gui_handler_events";

    // RETURN, not TAIL: the method has three exits (the desync null, the ore-mapping container, the
    // delegated libVulpes answer) and a chain must see all of them. No local is captured, so the
    // early exit's shorter LVT is not a problem. CallbackInfoReturnable because the target returns
    // a value.
    @Inject(method = "getServerGuiElement", at = @At("RETURN"))
    private void arTest$containerServed(int id, EntityPlayer player, World world, int x, int y, int z,
                                        CallbackInfoReturnable<Object> cir) {
        if (player != null) {
            TestTrace.instrument(player, INSTRUMENT);
        } else {
            TestTrace.instrumentHere(INSTRUMENT);
        }
        Object served = cir.getReturnValue();
        String payload = "\"id\":" + id
                + ",\"who\":\"" + TestTrace.json(player == null ? "?" : player.getName()) + "\""
                + ",\"container\":\"" + (served == null ? "null" : served.getClass().getSimpleName()) + "\""
                + ",\"at\":\"" + x + "," + y + "," + z + "\"";
        if (player != null && player.world != null) {
            TestTrace.record(player, "gui_container_served", payload);
        } else {
            TestTrace.recordServer("gui_container_served", payload);
        }
    }
}
