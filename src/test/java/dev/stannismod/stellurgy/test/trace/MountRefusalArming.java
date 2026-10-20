package dev.stannismod.stellurgy.test.trace;

import java.util.List;

import net.minecraft.world.World;

/**
 * A number of seat-mount spawns the SERVER's world is to refuse, armed by a scenario — see
 * {@code MixinArrivalSpawnMountRefusal}, which spends them at the one call where the world answers
 * whether it took a body.
 *
 * <p>A world refusing a fresh seat dummy is real (an arriving ship's world can answer {@code false}
 * for a body even with its chunk asked for) and rare: no scenario can provoke it on demand. Arming it
 * is fault injection, scoped to what the scenario opened: each refusal is spent once, and a window
 * left open is dropped by the shared base's per-scenario discard of the server's windows.</p>
 *
 * <p>Server thread only. Test source set: absent from a released jar.</p>
 */
public final class MountRefusalArming implements TraceWindow {

    private int pending;
    private int spent;

    private MountRefusalArming(int refusals) {
        this.pending = refusals;
    }

    /** Arm {@code refusals} refused mount spawns on this server; answers the window's handle. */
    public static int open(int refusals) {
        return SideTrace.here().open(new MountRefusalArming(refusals));
    }

    /** End the window; answers how many refusals it SPENT. */
    public static int close(int handle) {
        return SideTrace.here().close(handle, MountRefusalArming.class).spent;
    }

    /** Spend one armed refusal on {@code world}'s side; {@code false} when none is armed there. */
    public static boolean take(World world) {
        List<MountRefusalArming> open = SideTrace.of(world).windows(MountRefusalArming.class);
        for (int i = 0; i < open.size(); i++) {
            MountRefusalArming w = open.get(i);
            if (w.pending > 0) {
                w.pending--;
                w.spent++;
                return true;
            }
        }
        return false;
    }
}
