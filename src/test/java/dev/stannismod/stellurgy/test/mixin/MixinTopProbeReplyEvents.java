package dev.stannismod.stellurgy.test.mixin;

import java.util.ArrayList;
import java.util.List;

import mcjty.theoneprobe.api.IElement;
import mcjty.theoneprobe.apiimpl.ProbeInfo;
import mcjty.theoneprobe.apiimpl.elements.AbstractElementPanel;
import mcjty.theoneprobe.apiimpl.elements.ElementText;
import mcjty.theoneprobe.network.PacketReturnInfo;
import net.minecraft.util.math.BlockPos;
import net.minecraftforge.fml.common.network.simpleimpl.IMessage;
import net.minecraftforge.fml.common.network.simpleimpl.MessageContext;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import dev.stannismod.stellurgy.test.trace.TestTrace;

/**
 * What TheOneProbe's SERVER told this client about one block — the probe lines as they arrived,
 * before the client draws them.
 *
 * <p>{@code top_block_info} carries {@code dim}, {@code x/y/z}, {@code info} (whether the reply
 * carried probe info at all) and {@code lines}: the text of every text element in the reply, in
 * order, nested panels walked depth first, exactly as sent — a key the client translates is still
 * wrapped in the probe's {@code {*...*}} markers. Recorded at the reply's handler, so a record is a
 * server's answer by construction: the stand-in the client draws while it waits is built on the
 * client and never passes here. Read by {@code BlockProbeLineClientGroupTest}.</p>
 *
 * <p>Silent about what is DRAWN: a line that arrives is recorded whether or not the client's own
 * settings then show it. Client JVM only (the {@code client} list); the handler runs on the
 * connection's thread, which is the client's side.</p>
 */
@Mixin(value = PacketReturnInfo.Handler.class, remap = false)
public abstract class MixinTopProbeReplyEvents {

    private static final String INSTRUMENT = "top_block_info_events";

    @Inject(method = "onMessage(Lmcjty/theoneprobe/network/PacketReturnInfo;"
            + "Lnet/minecraftforge/fml/common/network/simpleimpl/MessageContext;)"
            + "Lnet/minecraftforge/fml/common/network/simpleimpl/IMessage;",
            at = @At("HEAD"), require = 1, remap = false)
    private void stellurgyTest$replied(PacketReturnInfo message, MessageContext ctx,
                                       CallbackInfoReturnable<IMessage> cir) {
        TestTrace.instrumentHere(INSTRUMENT);
        TopProbeReplyAccessor reply = (TopProbeReplyAccessor) message;
        BlockPos pos = reply.stellurgyTest$pos();
        ProbeInfo info = reply.stellurgyTest$probeInfo();
        List<String> lines = new ArrayList<>();
        if (info != null) {
            collectText(info, lines);
        }
        StringBuilder array = new StringBuilder("[");
        for (int i = 0; i < lines.size(); i++) {
            if (i > 0) {
                array.append(',');
            }
            array.append('"').append(TestTrace.json(lines.get(i))).append('"');
        }
        array.append(']');
        TestTrace.recordHere("top_block_info", "\"dim\":" + reply.stellurgyTest$dim()
                + ",\"x\":" + (pos == null ? 0 : pos.getX())
                + ",\"y\":" + (pos == null ? 0 : pos.getY())
                + ",\"z\":" + (pos == null ? 0 : pos.getZ())
                + ",\"info\":" + (info != null)
                + ",\"lines\":" + array);
    }

    private static void collectText(IElement element, List<String> into) {
        if (element instanceof ElementText) {
            into.add(((TopElementTextAccessor) element).stellurgyTest$text());
        } else if (element instanceof AbstractElementPanel) {
            for (IElement child : ((TopElementPanelAccessor) element).stellurgyTest$children()) {
                collectText(child, into);
            }
        }
    }
}
