package dev.stannismod.stellurgy.client;

import net.minecraft.client.Minecraft;
import net.minecraft.util.SoundCategory;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;
import net.minecraftforge.fml.common.gameevent.TickEvent;

/**
 * Silences a harness-spawned test client. Automated client e2e boots a REAL client with REAL audio on
 * the dev box, marked by {@code -Dforge.test.client=true}; this mutes the master sound level so those
 * runs are quiet. The proxy registers one only on such a client, so a human playtest is never muted.
 *
 * <p>The mute waits for a client tick because {@code GameSettings.setSoundLevel} pushes to the sound
 * handler, which does not exist yet at pre-init. Once it has muted, the listener takes itself off the
 * bus, so "already done" is the bus no longer holding it rather than a flag somebody has to keep.</p>
 */
public final class TestClientMute {

    @SubscribeEvent
    public void muteTestClientSound(TickEvent.ClientTickEvent event) {
        if (event.phase != TickEvent.Phase.END) {
            return;
        }
        Minecraft mc = Minecraft.getMinecraft();
        if (mc.getSoundHandler() == null) {
            return;
        }
        mc.gameSettings.setSoundLevel(SoundCategory.MASTER, 0.0F);
        MinecraftForge.EVENT_BUS.unregister(this);
    }
}
