package dev.stannismod.stellurgy.affs.client;

import dev.stannismod.stellurgy.affs.entity.EntityLaserBolt;
import net.minecraftforge.fml.client.registry.RenderingRegistry;

public final class ClientEntityRenderRegistry {

    private ClientEntityRenderRegistry() {
    }

    public static void init() {
        RenderingRegistry.registerEntityRenderingHandler(EntityLaserBolt.class, RenderLaserBolt::new);
    }
}
