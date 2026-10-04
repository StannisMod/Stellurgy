package dev.stannismod.stellurgy.client.render;

import net.minecraft.client.Minecraft;
import net.minecraft.world.World;
import net.minecraftforge.client.event.RenderWorldLastEvent;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;
import net.minecraftforge.fml.common.gameevent.TickEvent;
import dev.stannismod.stellurgy.entity.fx.InverseTrailFx;
import dev.stannismod.stellurgy.entity.fx.RocketFx;
import dev.stannismod.stellurgy.world.WorldRuntime;

import java.util.ArrayList;
import java.util.List;

public class DelayedParticleRenderingEventHandler {

    /** The delayed-render particles living in one client world, as that world's part
     *  ({@link WorldRuntime}): they are dropped with the world they were spawned in. */
    public static final class Particles {
        public final List<RocketFx> rocket = new ArrayList<>();
        public final List<InverseTrailFx> trail = new ArrayList<>();
    }

    /** The delayed-render particles of {@code world}, which a particle joins when it is built. */
    public static Particles in(World world) {
        return WorldRuntime.of(world, Particles.class, Particles::new);
    }

    @SubscribeEvent
    public void onRenderWorldLast(RenderWorldLastEvent event) {
        World world = Minecraft.getMinecraft().world;
        if (world == null) {
            return;
        }
        Particles particles = in(world);
        InverseTrailFx.renderAll(particles.trail);
        RocketFx.renderAll(particles.rocket);
    }

    @SubscribeEvent
    public void onTick(TickEvent.ClientTickEvent event) {
        World world = Minecraft.getMinecraft().world;
        if (world == null) {
            return;
        }
        Particles particles = in(world);
        for (RocketFx p : particles.rocket) {
            p.onUpdate2();
        }
        for (InverseTrailFx p : particles.trail) {
            p.onUpdate2();
        }

        particles.rocket.removeIf(particle -> !particle.isAlive());
        particles.trail.removeIf(particle -> !particle.isAlive());
    }
}
