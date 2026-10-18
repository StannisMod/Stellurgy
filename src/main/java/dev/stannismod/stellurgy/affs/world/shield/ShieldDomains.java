package dev.stannismod.stellurgy.affs.world.shield;

import dev.stannismod.stellurgy.integration.vs.VSIntegration;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;

/**
 * Resolves a shield block's <b>domain</b> — D134-6 Layer 1 (identity). A domain <em>bounds</em> the
 * shield configuration; it is never a credential and is never "entered". One hull = one shield domain,
 * so every console on a ship edits the same configuration and destroying one console loses nothing.
 *
 * <p>Two kinds exist. A block a ship's registry record claims belongs to that ship's domain, which
 * follows the hull wherever it flies (the same {@code shipIdOfBlock} resolution the
 * field frame uses, so frame and domain can never disagree) — whether or not anybody is near enough
 * for the hull to be simulated. Everything else — a planet base, an asteroid outpost —
 * belongs to its dimension's domain. Base identity has no first-class concept in Stellurgy yet, so a dimension
 * is the coarsest defensible bound: two bases in one dimension share a <em>group namespace</em> (their
 * group names appear in each other's consoles), which costs nothing physically because a group only
 * pushes a priority into the member emitters it actually lists.</p>
 */
public final class ShieldDomains {

    private static final String SHIP_PREFIX = "ship:";
    private static final String WORLD_PREFIX = "world:";

    private ShieldDomains() {
    }

    /** Domain id of the shield block at {@code pos}, or {@code null} if it cannot be resolved. */
    public static String forBlock(World world, BlockPos pos) {
        if (world == null || pos == null) {
            return null;
        }
        String shipId = VSIntegration.shipIdOfBlock(world, pos);
        if (shipId != null) {
            return SHIP_PREFIX + shipId;
        }
        return WORLD_PREFIX + world.provider.getDimension();
    }

    /** True when this domain is a flying hull rather than a fixed installation. */
    public static boolean isShipDomain(String domainId) {
        return domainId != null && domainId.startsWith(SHIP_PREFIX);
    }
}
