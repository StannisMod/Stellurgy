package dev.stannismod.stellurgy.event;

import net.minecraft.entity.EntityLivingBase;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.inventory.EntityEquipmentSlot;
import net.minecraft.item.ItemStack;
import net.minecraft.util.DamageSource;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;
import net.minecraftforge.event.entity.living.LivingEvent.LivingUpdateEvent;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;
import dev.stannismod.stellurgy.api.StellurgyConfiguration;
import dev.stannismod.stellurgy.api.capability.CapabilitySpaceArmor;
import dev.stannismod.stellurgy.dimension.DimensionManager;
import dev.stannismod.stellurgy.dimension.DimensionProperties;
import dev.stannismod.stellurgy.integration.vs.DeckFrameTick;
import dev.stannismod.stellurgy.world.provider.WorldProviderPlanet;

/**
 * Applies damage to living things caught in acidic rain — planets flagged
 * {@code acidicRain=true} in their definition — while rain falls on them and they wear
 * no full protective space suit.
 *
 * <p>Every living body burns, not only players: the suit is a reason to spare a body that
 * wears one, never a reason to spare a body that cannot. Acid rain is independent of
 * breathability: a breathable acidic planet still burns. Protection is the same
 * {@code PROTECTIVEARMOR} capability the atmosphere system uses, required on all four armor
 * slots so a mask-only loadout does not shield bare skin.</p>
 *
 * <p>Peak load: every exposed body takes one damage (and its hurt packets go to whoever tracks
 * it) once per {@code acidRainDamageInterval} ticks, each on a phase of its own, so the bodies
 * in one rain are spread over the interval rather than stacked into one tick of it.</p>
 */
public class AcidRainHandler {

    public static final DamageSource ACID_RAIN =
            new DamageSource("acidRain").setDamageBypassesArmor();

    @SubscribeEvent
    public void livingTick(LivingUpdateEvent event) {
        EntityLivingBase body = event.getEntityLiving();
        World world = body.world;
        if (world.isRemote) return;

        int interval = StellurgyConfiguration.getCurrentConfig().acidRainDamageInterval;
        if (interval < 1) interval = 1;
        if ((world.getTotalWorldTime() + phaseOf(body, interval)) % interval != 0) return;

        float damage = StellurgyConfiguration.getCurrentConfig().acidRainDamage;
        if (damage <= 0f) return;

        if (isExposedToAcidRain(body)) {
            body.attackEntityFrom(ACID_RAIN, damage);
        }
    }

    /**
     * True when {@code body} is currently being harmed by acid rain: on a Stellurgy planet
     * whose rain is acidic, where rain actually reaches it, and not wearing a full
     * protective suit.
     *
     * <p>For a body a craft's deck holds, "where rain reaches it" is two facts read in two
     * places: whether rain falls there at all is the WORLD's — weather, biome, the world's
     * own blocks over it — asked at its world point; whether the craft covers it is the
     * craft's, asked at its deck point in the shipyard, where the craft's blocks are.
     * Neither place alone sees both: the world point never sees the craft's roof, and the
     * shipyard never sees the world's weather.</p>
     */
    public static boolean isExposedToAcidRain(EntityLivingBase body) {
        World world = body.world;
        if (!(world.provider instanceof WorldProviderPlanet)) return false;

        DimensionProperties props = DimensionManager.getInstance()
                .getDimensionProperties(world.provider.getDimension());
        if (props == null || !props.isAcidicRain()) return false;

        BlockPos here = body.getPosition();
        if (DeckFrameTick.inDeckFrame(body)) {
            BlockPos inWorld = DeckFrameTick.worldPositionOf(body);
            if (inWorld == null || !world.isRainingAt(inWorld)) return false;
            if (!world.canSeeSky(here)) return false;
        } else if (!world.isRainingAt(here)) {
            return false;
        }

        return !isProtected(body);
    }

    /** A full protective space suit (all four slots) shields from acid rain. */
    public static boolean isProtected(EntityLivingBase body) {
        if (body instanceof EntityPlayer) {
            EntityPlayer player = (EntityPlayer) body;
            if (player.capabilities.isCreativeMode || player.isSpectator()) return true;
        }
        return hasProtectiveArmor(body, EntityEquipmentSlot.HEAD)
                && hasProtectiveArmor(body, EntityEquipmentSlot.CHEST)
                && hasProtectiveArmor(body, EntityEquipmentSlot.LEGS)
                && hasProtectiveArmor(body, EntityEquipmentSlot.FEET);
    }

    private static boolean hasProtectiveArmor(EntityLivingBase body, EntityEquipmentSlot slot) {
        ItemStack stack = body.getItemStackFromSlot(slot);
        return !stack.isEmpty()
                && CapabilitySpaceArmor.PROTECTIVEARMOR != null
                && stack.hasCapability(CapabilitySpaceArmor.PROTECTIVEARMOR, null);
    }

    /** A stable phase in {@code [0, interval)} from the body's own identity. */
    private static int phaseOf(EntityLivingBase body, int interval) {
        return (int) Math.floorMod(body.getEntityId() * 2654435761L, (long) interval);
    }
}
