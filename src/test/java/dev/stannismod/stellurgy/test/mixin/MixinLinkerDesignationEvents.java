package dev.stannismod.stellurgy.test.mixin;

import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.item.ItemStack;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.RayTraceResult;
import net.minecraft.world.World;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import dev.stannismod.stellurgy.libvulpes.items.ItemLinker;
import dev.stannismod.stellurgy.test.trace.TestTrace;
import dev.stannismod.stellurgy.tile.weapon.TileTurret;
import dev.stannismod.stellurgy.tile.weapon.TileWeaponConsole;

/**
 * A linker naming a target for a weapon, as events: the linker being bound to a gun or a console,
 * and the designation that tile then took. Production announces neither; both are read at the
 * tile's own seam, on the server only. Read by {@code ALinkerNamesTheBatteryItsTargetTest}.
 *
 * <ul>
 *   <li><b>{@code weapon_linker_bound}</b> — the RETURN of {@code onLinkStart} and
 *       {@code onLinkComplete}: {@code pos} is the tile clicked, and {@code linkedX/Y/Z} and
 *       {@code linkedDim} are what the LINKER now carries, read off the stack — the binding as
 *       written, not as intended. SILENT about a click that never reached the item: a plain
 *       right-click on a console opens its screen instead.</li>
 *   <li><b>{@code weapon_designated}</b> — the RETURN of {@code onLinkAimed}: {@code taken} is the
 *       tile's own answer, {@code hit} is {@code BLOCK} or {@code ENTITY}, {@code hitX/Y/Z} the point
 *       the line of sight landed on (unrounded), and {@code entity} the creature's uuid ({@code ""} for a block).
 *       SILENT about a right-click the linker did not hand to a tile — nothing in sight, or a
 *       binding it could not reach — which the player is told in chat instead.</li>
 * </ul>
 *
 * <p>Every seam is required to match, so a renamed method fails the boot instead of going quiet.</p>
 */
@Mixin({TileWeaponConsole.class, TileTurret.class})
public abstract class MixinLinkerDesignationEvents {

    private static final String INSTRUMENT = "weapon_linker_events";

    @Inject(method = "onLinkStart", at = @At("RETURN"), require = 1)
    private void stellurgyTest$boundOnStart(ItemStack item, TileEntity entity, EntityPlayer player,
                                            World world, CallbackInfoReturnable<Boolean> cir) {
        stellurgyTest$bound(item);
    }

    @Inject(method = "onLinkComplete", at = @At("RETURN"), require = 1)
    private void stellurgyTest$boundOnComplete(ItemStack item, TileEntity entity, EntityPlayer player,
                                               World world, CallbackInfoReturnable<Boolean> cir) {
        stellurgyTest$bound(item);
    }

    @Inject(method = "onLinkAimed", at = @At("RETURN"), require = 1)
    private void stellurgyTest$designated(ItemStack linker, RayTraceResult aimedAt, EntityPlayer player,
                                          CallbackInfoReturnable<Boolean> cir) {
        TileEntity self = (TileEntity) (Object) this;
        if (self.getWorld() == null || self.getWorld().isRemote) {
            return;
        }
        TestTrace.instrument(self.getWorld(), INSTRUMENT);
        boolean entity = aimedAt.typeOfHit == RayTraceResult.Type.ENTITY;
        TestTrace.record(self.getWorld(), "weapon_designated", stellurgyTest$linkerPos(self.getPos())
                + ",\"taken\":" + cir.getReturnValue()
                + ",\"hit\":\"" + aimedAt.typeOfHit.name() + "\""
                // Full precision, not TestTrace.fmt's six figures: at world coordinates in the
                // thousands those are a hundredth of a block, and the point is compared to the gun's.
                + ",\"hitX\":" + aimedAt.hitVec.x
                + ",\"hitY\":" + aimedAt.hitVec.y
                + ",\"hitZ\":" + aimedAt.hitVec.z
                + ",\"entity\":\"" + (entity ? aimedAt.entityHit.getUniqueID().toString() : "") + "\"");
    }

    private void stellurgyTest$bound(ItemStack item) {
        TileEntity self = (TileEntity) (Object) this;
        if (self.getWorld() == null || self.getWorld().isRemote) {
            return;
        }
        TestTrace.instrument(self.getWorld(), INSTRUMENT);
        BlockPos linked = ItemLinker.getMasterCoords(item);
        TestTrace.record(self.getWorld(), "weapon_linker_bound", stellurgyTest$linkerPos(self.getPos())
                + ",\"linkedX\":" + linked.getX()
                + ",\"linkedY\":" + linked.getY()
                + ",\"linkedZ\":" + linked.getZ()
                + ",\"linkedDim\":" + ItemLinker.getDimId(item));
    }

    private static String stellurgyTest$linkerPos(BlockPos pos) {
        return "\"pos\":\"" + pos.getX() + "," + pos.getY() + "," + pos.getZ() + "\"";
    }
}
