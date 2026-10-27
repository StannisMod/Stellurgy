package dev.stannismod.stellurgy.test.mixin;

import dev.stannismod.stellurgy.probe.ProbeFall;
import dev.stannismod.stellurgy.test.trace.TestTrace;
import net.minecraft.block.state.IBlockState;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.world.World;
import net.minecraft.world.biome.Biome;
import net.minecraftforge.common.BiomeDictionary;
import net.minecraftforge.common.DimensionManager;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * The save's one fall deciding, as two server records stamped with the overworld's clock.
 *
 * <p>{@code probe_entry} — every call of {@code ProbeFall#enter}, at its return: {@code admitted}, what it
 * answered; the {@code phase} it leaves; and the fall it was offered — its world {@code dim}, the landing
 * block {@code x}/{@code y}/{@code z}, its {@code entryTick} and the {@code player} it fell around — and what
 * the landing was when it was drawn, and where that player stood (see {@code ground}). The offer is made in
 * the tick the driver decided it in, so the player's place here is his place at the entry. A refused call is
 * recorded too, so a second offer is visible whichever way it was decided.</p>
 *
 * <p>{@code probe_touchdown} — every call of {@code ProbeFall#touchDown}, at its tail: the {@code phase} it
 * leaves.</p>
 *
 * <p>SILENT about: the driver's ticks on which it offered nothing — while the fall is under way or down it
 * never calls {@code enter}, so those ticks leave no record; read the phase with {@code first-probe fall}.
 * Silent too about the crater being laid and about what any client is told.</p>
 */
@Mixin(ProbeFall.class)
public abstract class MixinProbeFallEvents {

    private static final String ENTRY = "probe_entry";
    private static final String TOUCHDOWN = "probe_touchdown";

    @Inject(method = "enter", at = @At("RETURN"), require = 1)
    private void stellurgyTest$entry(ProbeFall.Fall entering, CallbackInfoReturnable<Boolean> cir) {
        TestTrace.instrumentServer(ENTRY);
        ProbeFall self = (ProbeFall) (Object) this;
        TestTrace.recordServer(ENTRY, "\"admitted\":" + cir.getReturnValue()
                + ",\"phase\":\"" + self.phase().name() + "\""
                + ",\"dim\":" + entering.dimension
                + ",\"x\":" + entering.landing.getX() + ",\"y\":" + entering.landing.getY()
                + ",\"z\":" + entering.landing.getZ()
                + ",\"entryTick\":" + entering.entryTick
                + ",\"player\":\"" + TestTrace.json(entering.player) + "\""
                + ground(entering));
    }

    /**
     * What the landing is, read at the offer from the world the driver drew it in — before the crater
     * changes it: the landing block's name and whether its material is a liquid, the column's biome and its
     * dictionary types, and where the player the fall is around stands. Empty fields where the world or the
     * player is gone.
     */
    private static String ground(ProbeFall.Fall entering) {
        World world = DimensionManager.getWorld(entering.dimension);
        if (world == null) {
            return "";
        }
        IBlockState top = world.getBlockState(entering.landing);
        Biome biome = world.getBiome(entering.landing);
        StringBuilder types = new StringBuilder("[");
        for (BiomeDictionary.Type type : BiomeDictionary.getTypes(biome)) {
            types.append(types.length() > 1 ? "," : "").append('"').append(type.getName()).append('"');
        }
        String out = ",\"landingBlock\":\"" + top.getBlock().getRegistryName() + "\""
                + ",\"landingLiquid\":" + top.getMaterial().isLiquid()
                + ",\"biome\":\"" + biome.getRegistryName() + "\""
                + ",\"biomeTypes\":" + types.append(']');
        EntityPlayer player = world.getPlayerEntityByName(entering.player);
        return player == null ? out
                : out + ",\"playerX\":" + player.posX + ",\"playerZ\":" + player.posZ;
    }

    @Inject(method = "touchDown", at = @At("TAIL"), require = 1)
    private void stellurgyTest$touchDown(CallbackInfo ci) {
        TestTrace.instrumentServer(TOUCHDOWN);
        TestTrace.recordServer(TOUCHDOWN, "\"phase\":\"" + ((ProbeFall) (Object) this).phase().name() + "\"");
    }
}
