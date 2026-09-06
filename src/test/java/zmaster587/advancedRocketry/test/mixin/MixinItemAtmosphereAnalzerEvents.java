package zmaster587.advancedRocketry.test.mixin;

import java.util.List;

import net.minecraft.item.ItemStack;
import net.minecraft.util.text.ITextComponent;
import net.minecraft.world.World;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import zmaster587.advancedRocketry.atmosphere.AtmosphereType;
import zmaster587.advancedRocketry.item.ItemAtmosphereAnalzer;
import zmaster587.advancedRocketry.test.trace.TestTrace;

/**
 * The atmosphere analyser COMPOSED its two-line readout, as an event — the one fact both of the
 * item's faces (the right-click chat and the helmet HUD) draw from.
 *
 * <h2>What the event is</h2>
 *
 * <p>{@code atmosphere_readout_composed}: production built the readout for an atmosphere in a
 * world — line 0 is the type-and-pressure line ("Atmosphere Type: … N atm"), line 1 the breathable
 * line ("Breathable: yes/no"). Both are taken off the method's own return value, rendered with
 * {@code getUnformattedText()} so the record carries what the translation resolved to and not the
 * key, and the atmosphere is named by its unlocalized name. Payload: {@code atmosphere, line0,
 * line1}.</p>
 *
 * <h2>The seam, and the side</h2>
 *
 * <p>The private {@code getAtmosphereReadout(ItemStack, AtmosphereType, World)} has a single return,
 * so {@code RETURN} is its tail. It is called from two places, and the record lands on the side of
 * whichever called it, through {@code recordHere}: the server, once per right-click (the handler
 * composes only when {@code !worldIn.isRemote}, then sends each line as chat); the client, once per
 * HUD FRAME while the analyser sits in a helmet slot ({@code renderScreen}). The client caller is
 * chatty — a per-frame record would turn the 256-ring over in seconds — so on a remote world only a
 * CHANGE of the composed readout is recorded (the last one kept in a private static on this mixin,
 * which is sound because the item is a singleton and the render thread is one thread); on the server
 * every composition is recorded, because there each one IS a right-click. The memo is keyed on the
 * client {@code World} instance as well as on the text, so that a relog or a dimension change into
 * an identical readout still records its first frame instead of being filtered against a session
 * that has ended.</p>
 *
 * <h2>What it is silent about</h2>
 *
 * <ul>
 *   <li>Whether the lines REACHED the player. The chat delivery is the next link
 *       ({@code chat_message_sent}, recorded at {@code EntityPlayerMP.sendMessage}), and whether the
 *       HUD actually drew a frame is a render fact this mixin does not see.</li>
 *   <li>The {@code null → AIR} fallback at the method's head. The handler receives the parameter
 *       slot AS IT IS at the injection point, so a readout composed for "no atmosphere handler"
 *       reads {@code atmosphere:"air"} exactly like one composed for a real AIR atmosphere. A test
 *       that needs to tell them apart needs a HEAD record, which this wave did not pin.</li>
 *   <li>Where the pressure figure came from — the handler's live pressure, the dimension's density,
 *       or the {@code 1} default. Only the composed figure is in {@code line0}.</li>
 *   <li>Who asked. The stack is a player's hand or a HUD frame, and neither is in the record.</li>
 * </ul>
 */
@Mixin(ItemAtmosphereAnalzer.class)
public abstract class MixinItemAtmosphereAnalzerEvents {

    private static final String INSTRUMENT = "atmosphere_readout_events";

    // The last readout recorded from a REMOTE world — the HUD recomposes it every frame, and a
    // steady sky must not fill the ring. Never consulted for a server composition.
    private static String arTest$lastClientReadout;

    // The client world that memo belongs to, weakly held so a disconnected world is still collected.
    // Without it the memo outlives its world: a relog or a dimension change into an IDENTICAL
    // readout would be filtered as "unchanged" against a session that has ended, and a test awaiting
    // the first readout after the reconnect would wait for a record that was suppressed.
    private static java.lang.ref.WeakReference<World> arTest$lastClientWorld;

    @Inject(method = "getAtmosphereReadout", at = @At("RETURN"))
    private void arTest$readoutComposed(ItemStack stack, AtmosphereType atm, World world,
                                        CallbackInfoReturnable<List<ITextComponent>> cir) {
        TestTrace.instrumentHere(INSTRUMENT);
        List<ITextComponent> lines = cir.getReturnValue();
        String line0 = lines != null && lines.size() > 0 && lines.get(0) != null
                ? lines.get(0).getUnformattedText() : "";
        String line1 = lines != null && lines.size() > 1 && lines.get(1) != null
                ? lines.get(1).getUnformattedText() : "";
        String payload = "\"atmosphere\":\"" + (atm == null ? "null" : TestTrace.json(atm.getUnlocalizedName()))
                + "\",\"line0\":\"" + TestTrace.json(line0)
                + "\",\"line1\":\"" + TestTrace.json(line1) + "\"";
        // A null world can only be a client caller: the server's caller is an item right-click,
        // whose world argument production declares @Nonnull. Filtering it as client-side is the
        // safe way round — the alternative floods the ring one record per rendered frame.
        if (world == null || world.isRemote) {
            World memoWorld = arTest$lastClientWorld == null ? null : arTest$lastClientWorld.get();
            if (memoWorld != world) {
                // A world this memo has never seen (first frame, a dimension change, a relog): its
                // first readout is an edge whatever the previous world's last one happened to be.
                arTest$lastClientWorld = new java.lang.ref.WeakReference<World>(world);
            } else if (payload.equals(arTest$lastClientReadout)) {
                return; // the HUD's per-frame recomposition of an unchanged readout: not an edge
            }
            arTest$lastClientReadout = payload;
        }
        TestTrace.recordHere("atmosphere_readout_composed", payload);
    }
}
