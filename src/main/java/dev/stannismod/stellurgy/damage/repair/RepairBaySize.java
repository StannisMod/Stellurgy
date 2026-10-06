package dev.stannismod.stellurgy.damage.repair;

import net.minecraft.util.EnumFacing;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;

import dev.stannismod.stellurgy.api.StellurgyConfiguration;
import dev.stannismod.stellurgy.api.damage.IRepairBayPart;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashSet;
import java.util.Set;

/**
 * How big a repair bay is, and how fast that size lets it work.
 *
 * <h3>Size is what was built, not a template</h3>
 * <p>A bay is its controller plus the connected run of {@link IRepairBayPart} blocks touching it.
 * There is no shape to match, so a frame shipped by somebody else joins a bay by being placed
 * against one, and size is a continuous number a player builds rather than a tier.</p>
 *
 * <h3>The law</h3>
 * <p>{@code rate(N) = max * N / (N + half)}: strictly increasing in N, each added size buying less
 * than the one before, and never reaching {@code max}. All three properties hold for every positive
 * {@code max} and {@code half}, so retuning the two numbers cannot break the shape.</p>
 */
public final class RepairBaySize {

    /**
     * The most frames one bay is credited with. A bound on the WORK of walking a bay, not a
     * balance number: without it a hull paved in frames is one bay walked block by block on every
     * look. A bay built past it still works — it is simply not credited for the excess.
     */
    public static final int MAX_PARTS = 256;

    private RepairBaySize() {
    }

    /**
     * The size of the bay whose controller stands at {@code controller}: the controller itself plus
     * every connected part, at most {@code 1 + MAX_PARTS}.
     *
     * <p>The controller counts so that a lone controller is a working bay of size 1 rather than a
     * machine that silently does nothing. A position in an unloaded chunk is not walked: it is an
     * unknown, and loading chunks to answer it would cost far more than under-counting a bay whose
     * far end nobody is near.</p>
     */
    public static int of(World world, BlockPos controller) {
        Set<BlockPos> visited = new HashSet<>();
        Deque<BlockPos> frontier = new ArrayDeque<>();
        visited.add(controller);
        for (EnumFacing facing : EnumFacing.VALUES) {
            frontier.add(controller.offset(facing));
        }
        int parts = 0;
        while (!frontier.isEmpty() && parts < MAX_PARTS) {
            BlockPos pos = frontier.poll();
            if (!visited.add(pos) || !world.isBlockLoaded(pos)
                    || !(world.getBlockState(pos).getBlock() instanceof IRepairBayPart)) {
                continue;
            }
            parts++;
            for (EnumFacing facing : EnumFacing.VALUES) {
                BlockPos next = pos.offset(facing);
                if (!visited.contains(next)) {
                    frontier.add(next);
                }
            }
        }
        return 1 + parts;
    }

    /** The Forge Energy per tick a bay of {@code size} puts into its work. */
    public static double powerPerTick(int size) {
        StellurgyConfiguration config = StellurgyConfiguration.getCurrentConfig();
        return ceiling() * size / (double) (size + config.repairBayHalfRateSize);
    }

    /** What no bay reaches however large it is built. */
    public static double ceiling() {
        return StellurgyConfiguration.getCurrentConfig().repairBayMaxPowerPerTick;
    }
}
