package dev.stannismod.stellurgy.util;

import dev.stannismod.stellurgy.Stellurgy;
import dev.stannismod.stellurgy.api.IGravityManager;
import dev.stannismod.stellurgy.api.IPlanetaryProvider;
import dev.stannismod.stellurgy.api.StellurgyAPI;
import dev.stannismod.stellurgy.dimension.DimensionManager;
import dev.stannismod.stellurgy.integration.vs.VSIntegration;
import dev.stannismod.stellurgy.world.provider.WorldProviderSpace;
import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityFlying;
import net.minecraft.entity.EntityLivingBase;
import net.minecraft.entity.item.*;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.entity.projectile.EntityArrow;
import net.minecraft.entity.projectile.EntityThrowable;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.OptionalDouble;
import java.util.WeakHashMap;

/**
 * Every static field of this type is effectively final, process lifetime: built once at class initialisation.
 */
public class GravityHandler implements IGravityManager {

    public static final float LIVING_OFFSET = 0.0755f;
    public static final float FLUID_LIVING_OFFSET = 0.02f;
    public static final float THROWABLE_OFFSET = 0.03f;
    public static final float OTHER_OFFSET = 0.04f;
    public static final float ARROW_OFFSET = 0.05f;

    static Class gcWorldProvider;
    static Method gcGetGravity;
    /**
     * The per-entity overrides this SERVICE holds.
     *
     * <p><b>An instance field, and that is the point.</b> It was a mutable static while the object
     * around it is the mod's owned gravity service — so every {@code new GravityHandler()} shared
     * one map, and the state of a service did not live with the service. The mod object's own note
     * on the four API services says what the arrangement is meant to be: one owner, one writer, and
     * a loud error on a second install. A static map quietly exempted itself from all three.</p>
     *
     * <p>OWNER: this handler, which the mod object installs once. LIFETIME: that handler's — and a
     * {@link WeakHashMap} additionally lets an entry go when the entity it keys does, so a world
     * that unloads does not leave its entities pinned here.</p>
     */
    private final WeakHashMap<Entity, Double> entityMap = new WeakHashMap<>();

    static {
        try {
            gcWorldProvider = Class.forName("micdoodle8.mods.galacticraft.api.world.IGalacticraftWorldProvider");
            Stellurgy.logger.info("GC IGalacticraftWorldProvider  found");
            gcGetGravity = gcWorldProvider.getMethod("getGravity");
        } catch (ClassNotFoundException | NoSuchMethodException e) {
            gcWorldProvider = null;
            Stellurgy.logger.info("GC IGalacticraftWorldProvider not found");
        }
    }

    /**
     * The mixin's entry point, kept STATIC so the per-tick call site does not change shape, and
     * routed through the installed service so the per-entity overrides can live on it.
     *
     * <p>This runs inside {@code Entity.onUpdate}, and the handler is installed during
     * {@code FMLInitializationEvent}, before any world exists to tick an entity in — so a missing
     * service is a broken mod, and it throws.</p>
     */
    public static void applyGravity(Entity entity) {
        IGravityManager service = StellurgyAPI.gravityManager();
        if (!(service instanceof GravityHandler)) {
            throw new IllegalStateException("no gravity service is installed while entities are ticking"
                    + " (the API answered " + service + ")");
        }
        ((GravityHandler) service).apply(entity);
    }

    private void apply(Entity entity) {
        if (entity.hasNoGravity()) return;
        //Because working gravity on elytra-flying players can cause..... severe problems at lower gravity, it is my utter delight to announce to you elytra are now magic!
        //This totally isn't because Mojang decided for some godforsaken @#@#@#% reason to make ALL WAYS TO SET ELYTRA FLIGHT _protected_
        //With no set methods
        //So I cannot, without much more effort than it's worth, set elytra flight. Therefore, they're magic.
        if ((!(entity instanceof EntityPlayer) && !(entity instanceof EntityFlying)) || (!(entity instanceof EntityFlying) && !(((EntityPlayer) entity).capabilities.isFlying || ((EntityLivingBase) entity).isElytraFlying()))) {

            // A body a deck holds is being updated in that deck's own frame right now (this hook runs
            // inside its update), where -Y IS deck-down and vanilla's own pull for its class is
            // exactly the craft's ~1G. The dimension's gravity is not the craft's: scaling that pull
            // by it left an item let go above a deck in a world without gravity hanging where it was.
            if (dev.stannismod.stellurgy.integration.vs.DeckFrameTick.holds(entity)) {
                return;
            }

            // No craft gravity for a body no deck holds. A deck's pull exists only while it holds the
            // body (above); anything else near a craft falls the dimension's way and is collided by the
            // physics substrate. Pulling it toward the craft's down by world-box containment instead
            // lifted an item off the outer hull of an upside-down craft and left it hanging above it.

            Double d;
            if (entityMap.containsKey(entity) && (d = entityMap.get(entity)) != null) {

                double multiplier = (isOtherEntity(entity) || entity instanceof EntityItem) ? OTHER_OFFSET * d : (entity instanceof EntityArrow) ? ARROW_OFFSET * d : (entity instanceof EntityThrowable) ? THROWABLE_OFFSET * d : LIVING_OFFSET * d;

                entity.motionY += multiplier;

            } else if (DimensionManager.getInstance().isDimensionCreated(entity.world.provider.getDimension()) || entity.world.provider instanceof WorldProviderSpace) {
                double gravMult;

                if (entity.world.provider instanceof IPlanetaryProvider)
                    gravMult = ((IPlanetaryProvider) entity.world.provider).getGravitationalMultiplier(entity.getPosition());
                else
                    gravMult = DimensionManager.getInstance().getDimensionProperties(entity.world.provider.getDimension()).gravitationalMultiplier;

                if (entity instanceof EntityItem)
                    entity.motionY -= (gravMult * OTHER_OFFSET - OTHER_OFFSET);
                else if (isOtherEntity(entity))
                    entity.motionY -= (gravMult * OTHER_OFFSET - OTHER_OFFSET);
                else if (entity instanceof EntityThrowable)
                    entity.motionY -= (gravMult * THROWABLE_OFFSET - THROWABLE_OFFSET);
                else if (entity instanceof EntityArrow)
                    entity.motionY -= (gravMult * ARROW_OFFSET - ARROW_OFFSET);
                else if (entity instanceof EntityLivingBase && entity.isInWater() || entity.isInLava())
                    entity.motionY -= (gravMult * FLUID_LIVING_OFFSET - FLUID_LIVING_OFFSET);
                else if (entity instanceof EntityLivingBase)
                    entity.motionY -= (gravMult * LIVING_OFFSET - LIVING_OFFSET);

            } else {
                //GC handling
                if (gcWorldProvider != null && gcWorldProvider.isAssignableFrom(entity.world.provider.getClass())) {
                    try {
                        entity.motionY -= LIVING_OFFSET - (float) gcGetGravity.invoke(entity.world.provider);
                    } catch (IllegalAccessException | IllegalArgumentException
                             | InvocationTargetException e) {
                        e.printStackTrace();
                    }
                }
            }
        }
    }

    public static boolean isOtherEntity(Entity entity) {
        return entity instanceof EntityBoat || entity instanceof EntityMinecart || entity instanceof EntityFallingBlock || entity instanceof EntityTNTPrimed;
    }

    @Override
    public void setGravityMultiplier(Entity entity, double multiplier) {
        //TODO: packet handling
        entityMap.put(entity, multiplier);
    }

    @Override
    public void clearGravityEffect(Entity entity) {
        entityMap.remove(entity);
    }

    @Override
    public OptionalDouble gravityMultiplier(Entity entity) {
        if (entity == null) {
            return OptionalDouble.empty();
        }
        Double stored = entityMap.get(entity);
        return stored == null ? OptionalDouble.empty() : OptionalDouble.of(stored);
    }
}
