package zmaster587.advancedRocketry.test.mixin;

import java.util.List;
import java.util.Map;

import net.minecraft.entity.player.EntityPlayer;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import zmaster587.advancedRocketry.network.PacketSystemBodiesSync;
import zmaster587.advancedRocketry.test.trace.TestTrace;

/**
 * The sky feed ARRIVED on this client as an event: one record per applied
 * {@link PacketSystemBodiesSync}, taken at the return of its {@code executeClient}.
 *
 * <p>What it observes is the production fact that the client's render store now holds exactly this
 * packet's payload — {@code executeClient} clears both client-side maps and overwrites them with the
 * decoded halves, so at its RETURN the store IS the payload. The tests used to poll the store's size
 * through reflection on a tick budget; a size cannot say which broadcast filled it, cannot tell an
 * empty feed (a deliberate "draw nothing" packet) from a feed that never came, and cannot count how
 * many broadcasts the cell change produced. Each arrival is now its own record.</p>
 *
 * <p>Payload: {@code dims} is the number of slot dims the body half carries an ENTRY for,
 * {@code bodies} and {@code nebulae} the totals across every slot dim, and {@code slotDims} those
 * same entry ids as a comma-separated string, so a wait can name the cell it is waiting for
 * ({@code Events.lastField(reply, "slotDims")}). {@code dims} counts entries and not bodies:
 * {@code forDims} copies a caller's empty list through verbatim, so a dim can appear in
 * {@code slotDims} and contribute nothing to {@code bodies} — the packet's own {@code isEmpty()}
 * draws that same line ({@code byDim.isEmpty()}), and a test that means "this cell has bodies to
 * draw" reads {@code bodies}, never {@code dims}. A dim that carries clouds but no body entry is
 * counted in {@code nebulae} alone. Recorded through {@code recordHere}: the handler runs on the
 * client thread, so the record lands in the client log.</p>
 *
 * <p>Applied on the CLIENT only — the target method is {@code @SideOnly(Side.CLIENT)} and does not
 * exist in a dedicated server, so the class belongs in the {@code client} list, never {@code mixins}.
 * The target's single return makes RETURN and TAIL the same point here.</p>
 *
 * <p>SILENT about: whether the sky renderer ever READ the store (that is {@code sky_frame_drawn}'s
 * business), what the server decided to send and why, the wire bytes themselves, the
 * {@code descendTarget} highlight per body, and a packet that failed to decode — a decode error
 * throws before {@code executeClient} and nothing here fires for it.</p>
 */
@Mixin(PacketSystemBodiesSync.class)
public abstract class MixinPacketSystemBodiesSyncEvents {

    private static final String INSTRUMENT = "system_bodies_sync_events";

    @Shadow
    private Map<Integer, List<PacketSystemBodiesSync.RenderBody>> byDim;

    @Shadow
    private Map<Integer, List<PacketSystemBodiesSync.RenderNebula>> nebulaeByDim;

    @Inject(method = "executeClient", at = @At("RETURN"))
    private void arTest$received(EntityPlayer player, CallbackInfo ci) {
        TestTrace.instrumentHere(INSTRUMENT);
        int bodies = 0;
        StringBuilder slotDims = new StringBuilder();
        if (byDim != null) {
            for (Map.Entry<Integer, List<PacketSystemBodiesSync.RenderBody>> e : byDim.entrySet()) {
                if (slotDims.length() > 0) {
                    slotDims.append(',');
                }
                slotDims.append(e.getKey());
                if (e.getValue() != null) {
                    bodies += e.getValue().size();
                }
            }
        }
        int nebulae = 0;
        if (nebulaeByDim != null) {
            for (List<PacketSystemBodiesSync.RenderNebula> clouds : nebulaeByDim.values()) {
                if (clouds != null) {
                    nebulae += clouds.size();
                }
            }
        }
        TestTrace.recordHere("system_bodies_received", "\"dims\":" + (byDim == null ? 0 : byDim.size())
                + ",\"bodies\":" + bodies + ",\"nebulae\":" + nebulae
                + ",\"slotDims\":\"" + slotDims + "\"");
    }
}
