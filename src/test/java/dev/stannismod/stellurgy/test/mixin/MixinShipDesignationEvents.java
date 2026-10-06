package dev.stannismod.stellurgy.test.mixin;

import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import dev.stannismod.stellurgy.test.trace.TestTrace;
import dev.stannismod.stellurgy.weapon.ShipDesignation;

/**
 * A pilot's designation, as an event: {@code ship_designated}.
 *
 * <p>The RETURN of {@code ShipDesignation#designate}: {@code pilot} (his name), the {@code seat} he
 * pressed it from ({@code x,y,z}) and {@code ship} — the substrate's id of the ship his batteries were
 * handed, production's own answer, or empty when nothing was designated (no ship ahead, no battery
 * aboard, a seat on no ship). Server log. Read by {@code HelmControlsClientGroupTest}.</p>
 */
@Mixin(value = ShipDesignation.class, remap = false)
public abstract class MixinShipDesignationEvents {

    private static final String INSTRUMENT = "ship_designation_events";

    @Inject(method = "designate", at = @At("RETURN"), require = 1)
    private static void stellurgyTest$designated(World world, BlockPos seatPos, EntityPlayer pilot,
                                                 CallbackInfoReturnable<String> cir) {
        if (world == null || world.isRemote) {
            return;
        }
        String ship = cir.getReturnValue();
        TestTrace.instrument(world, INSTRUMENT);
        TestTrace.record(world, "ship_designated", "\"pilot\":\"" + TestTrace.json(pilot.getName()) + "\""
                + ",\"seat\":\"" + seatPos.getX() + "," + seatPos.getY() + "," + seatPos.getZ() + "\""
                + ",\"ship\":\"" + (ship == null ? "" : TestTrace.json(ship)) + "\"");
    }
}
