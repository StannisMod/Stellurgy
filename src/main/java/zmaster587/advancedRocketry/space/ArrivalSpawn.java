package zmaster587.advancedRocketry.space;

import net.minecraft.entity.Entity;
import net.minecraft.world.WorldServer;

/**
 * Putting a body into a world nobody is standing in — the arrival side of every crossing.
 *
 * <h2>Why this is not just {@code world.spawnEntity}</h2>
 *
 * <p>{@code WorldServer.spawnEntity} refuses a body whose destination chunk is not loaded, and it
 * does not load one: it answers {@code false} and drops the entity on the floor of the caller's
 * assumptions. That refusal is the ORDINARY case here rather than an edge one. An arriving ship's
 * world has nobody in it — the crew who would keep it loaded are the ones the same crossing is
 * carrying — so the chunk a body is about to land in is exactly the chunk nothing has asked for.</p>
 *
 * <p>Retrying does not help, and that is the trap this class exists to close: no attempt leaves the
 * world any more loaded than the one before it, so a caller that spawns, sees a refusal and comes
 * back next tick spins until it gives up. Measured 2026-09-06 on the cell-seam carry, where the
 * refusal was not even read — the placement was counted either way, the stash was dropped, and the
 * cargo ended up in NO world at all.</p>
 *
 * <h2>Why the position and the chunk are one argument</h2>
 *
 * <p>The chunk to load is a function of the position the body will occupy. Computed apart, the two
 * drift — a caller that sets the position later, or loads the chunk of the anchor rather than of the
 * body, gets a load that protects the wrong square. So this sets the position and loads its chunk in
 * one place, and the caller cannot hold one without the other.</p>
 *
 * <p>Nothing is PINNED. The body only has to exist long enough to be written down with the chunk it
 * landed in; keeping the chunk alive afterwards is the job of whoever is meant to be there.</p>
 *
 * <p>Server main thread only.</p>
 */
public final class ArrivalSpawn {

    private static final org.apache.logging.log4j.Logger LOGGER =
            org.apache.logging.log4j.LogManager.getLogger("advancedrocketry/space");

    private ArrivalSpawn() {
    }

    /**
     * Place {@code body} at {@code (x,y,z)} in {@code world} and put it there, loading the chunk it
     * lands in first.
     *
     * @return whether the WORLD took it. {@code false} is the world's own answer and never a guess —
     *         a caller must treat it as "this body is not in any world" and say so, because the body
     *         it is holding has usually already been removed from wherever it came from.
     */
    public static boolean at(WorldServer world, Entity body, double x, double y, double z) {
        if (world == null || body == null) {
            return false;
        }
        body.setPosition(x, y, z);
        world.getChunkProvider().provideChunk(((int) x) >> 4, ((int) z) >> 4);
        boolean accepted = world.spawnEntity(body);
        if (!accepted) {
            // The one case this class cannot fix, said out loud: the chunk was asked for and the
            // world still would not take the body. Silence here is how a crossing loses cargo.
            LOGGER.error("[SPACE] dim {} REFUSED a body at ({},{},{}) even with its chunk loaded - "
                            + "{} is in no world now", world.provider.getDimension(), x, y, z,
                    body.getClass().getSimpleName());
        }
        return accepted;
    }
}
