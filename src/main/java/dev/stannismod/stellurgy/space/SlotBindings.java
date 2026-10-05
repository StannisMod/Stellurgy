package dev.stannismod.stellurgy.space;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Which store each pool slot reads and writes — server state, held by the running server's
 * {@link SpaceSubsystem}.
 *
 * <p>A binding names a cell of ONE save. The slot dimensions themselves are JVM-global (a Forge
 * registration cannot be withdrawn, see {@link SpaceSlotPool#slotType}), but what a slot is bound to
 * is not: kept past the server that made it, a binding would hand the next world a single-player
 * client opens the previous world's cell folder for any slot that initialises before it is rebound.
 * So it lives and dies with the subsystem, and {@link SpaceSlotPool} reaches it through
 * {@code Stellurgy.spaceSubsystem()}.</p>
 */
public final class SlotBindings {

    /**
     * What a slot is bound to: the STORE it reads and writes, and — when the binding names a real
     * cell — the coordinate that cell is.
     *
     * <p><b>Both, in one record, because they are one fact and they used to be half a fact.</b> The
     * pool identified a binding by its key alone, and a key cannot carry a zoned lattice's width: a
     * moon's cell {@code 19_0_0.213_0_0} recovered from its own name comes back
     * {@code WIDTH_UNKNOWN}. Every reader that needed arithmetic then rebuilt a coordinate from the
     * key and got a width-less one — which is how a settled ship's ledger row lost its width one
     * tick after arriving and took the dedicated server down on the next.</p>
     *
     * <p><b>And the width cannot be re-derived, not merely inconveniently.</b> A zone's cell size is
     * {@code ZoneScale.cellBlocks(body, primary, tick)} — a function of the sphere of influence AT A
     * TICK. Re-attaching it later attaches the width of a DIFFERENT moment, which
     * {@link GalacticCoord#inLattice} names for what it is: a way to say something false. The
     * coordinate must travel whole or not at all.</p>
     *
     * <p>{@code coord} is {@code null} for a SCRATCH binding — a store named by a caller that has no
     * cell in mind ({@code "deep"}, a probe's {@code "A"}). That is an absence a reader can act on,
     * not a stand-in it cannot tell from a real address.</p>
     */
    private static final class BoundCell {
        /** The store folder's name. Never null: a binding always names a store. */
        final String store;
        /** The cell this binding IS, or {@code null} when the store names no cell. */
        final GalacticCoord coord;

        BoundCell(String store, GalacticCoord coord) {
            this.store = store;
            this.coord = coord;
        }
    }

    /** dimId &rarr; what that slot is bound to ({@code null} = unbound). */
    private final Map<Integer, BoundCell> byDim = new ConcurrentHashMap<>();

    String storeFor(int dimId) {
        BoundCell bound = byDim.get(dimId);
        return bound == null ? null : bound.store;
    }

    GalacticCoord cellFor(int dimId) {
        BoundCell bound = byDim.get(dimId);
        return bound == null ? null : bound.coord;
    }

    void bindCell(int dimId, GalacticCoord cell) {
        if (cell == null) {
            byDim.remove(dimId);
        } else {
            byDim.put(dimId, new BoundCell(cell.cellKey(), cell));
        }
    }

    void bindScratch(int dimId, String storeName) {
        if (storeName == null) {
            byDim.remove(dimId);
        } else {
            byDim.put(dimId, new BoundCell(storeName, null));
        }
    }
}
