package dev.stannismod.stellurgy.command.test;

import net.minecraft.util.math.BlockPos;
import net.minecraftforge.event.world.BlockEvent;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;

import java.util.Collections;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * A stand-in for the protection mod nobody wants to install to run a test.
 *
 * <h3>What it exists to prove</h3>
 * <p>Weapon fire removes blocks by asking first — it posts a break event and honours a refusal. That
 * contract is only worth anything if something can actually refuse, and everything that would refuse
 * in production (a claim mod, a region plugin, an admin's listener) is a third party we do not ship.
 * So this is exactly what one of them registers: an ordinary subscriber to
 * {@code BlockEvent.BreakEvent} that cancels for the positions it was told to guard.</p>
 *
 * <p>It is a listener, not a seam: nothing in the damage engine knows this class exists, and the
 * path a test exercises through it is the same path a stranger's mod takes. Test-only, and reachable
 * only through the {@code /stellurgytest} command, which the mod refuses to register without its test
 * property.</p>
 *
 * <p><b>Owner and lifetime</b>: one instance per SERVER. {@link TestProbeCommandRegistration} makes
 * it when a server starts, puts it on the bus through {@link ServerScoped} and hands it to that
 * server's {@link TestProbeCommand}; it leaves the bus when that server's overworld unloads, and the
 * positions it guards go with it. A guarded position therefore never outlives the server it was
 * guarded on.</p>
 */
public final class WeaponFireVetoProbe {

    /** Guarded positions, per dimension. */
    private final Map<Integer, Set<Long>> guarded = new ConcurrentHashMap<>();

    /** Guard, or stop guarding, one position. Answers how many are guarded in that dimension now. */
    public int guard(int dimension, BlockPos pos, boolean guard) {
        Set<Long> set = guarded.computeIfAbsent(dimension,
                d -> Collections.newSetFromMap(new ConcurrentHashMap<Long, Boolean>()));
        if (guard) {
            set.add(pos.toLong());
        } else {
            set.remove(pos.toLong());
        }
        return set.size();
    }

    /** Forget every guarded position, in every dimension. */
    public void clear() {
        guarded.clear();
    }

    @SubscribeEvent
    public void onBreak(BlockEvent.BreakEvent event) {
        if (event.getWorld() == null || event.getWorld().isRemote || event.getPos() == null) {
            return;
        }
        Set<Long> set = guarded.get(event.getWorld().provider.getDimension());
        if (set != null && set.contains(event.getPos().toLong())) {
            event.setCanceled(true);
        }
    }
}
