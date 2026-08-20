package zmaster587.advancedRocketry.api.event;

import net.minecraft.entity.Entity;
import net.minecraft.world.World;
import net.minecraftforge.event.entity.EntityEvent;
import net.minecraftforge.fml.common.eventhandler.Cancelable;
import zmaster587.advancedRocketry.api.atmosphere.Atmosphere;

public class AtmosphereEvent extends EntityEvent {

    public final World world;
    public final Atmosphere atmosphere;

    public AtmosphereEvent(Entity entity, Atmosphere atmosphere) {
        super(entity);
        world = entity.world;
        this.atmosphere = atmosphere;
    }

    /**
     * Called what an entity is about to be ticked in an atmosphere, effects are not applied if canceled
     */
    @Cancelable
    public static class AtmosphereTickEvent extends AtmosphereEvent {
        public AtmosphereTickEvent(Entity entity, Atmosphere atmosphere) {
            super(entity, atmosphere);
        }
    }
}
