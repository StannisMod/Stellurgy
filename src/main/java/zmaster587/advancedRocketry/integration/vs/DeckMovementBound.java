package zmaster587.advancedRocketry.integration.vs;

import net.minecraft.entity.player.EntityPlayerMP;

/**
 * What the SERVER accepts from a client that is standing on a deck.
 *
 * <h2>Why anything is checked at all</h2>
 *
 * <p>Player movement is client-authoritative: the client computes where the player is and the server
 * takes it. Measured on a body launched off an inverted hull, the server accepted every declared
 * position of a thirty-blocks-per-tick climb without a murmur — {@code serverY == packetY} on each
 * tick — because vanilla's own speed check is written for a player on solid ground and a body on a
 * moving deck legitimately covers ground its own legs never did.</p>
 *
 * <p>So the deck's own motion is what makes the check possible: knowing how fast the craft is moving
 * AT the body's point, the server knows the region the body could have reached — its own maximum
 * speed plus the deck's carry — and anything outside that region is a movement no combination of
 * player input and craft motion could produce.</p>
 *
 * <h2>What it deliberately does NOT do</h2>
 *
 * <p>It never makes legitimate play impossible. It applies ONLY while AR itself holds a deck capture
 * for that player — a state that already excludes riding, elytra flight, creative flight and
 * levitation, i.e. every locomotion whose speed is not the walking one. Outside a deck capture this
 * class has no opinion and vanilla's own checks stand alone. And the bound it applies is generous by
 * construction: the sum of two speeds, each taken at its maximum rather than its typical value.</p>
 *
 * <h2>The numbers</h2>
 *
 * <p>{@link #PLAYER_MAX_OWN_BLOCKS_PER_TICK} is what a body can cover under its own power in one
 * tick, and it is MEASURED rather than reasoned about: {@link #maxOwnDisplacementSeen} records what
 * captured bodies actually produce across the crew suite — walking, sprinting, jumping, riding a
 * climbing deck — and the constant sits above the largest of them with room to spare. A body that
 * exceeds the region is not banned from moving: its position is refused and the last accepted one
 * stands, which is the same correction vanilla applies to a player who moved too quickly.</p>
 */
public final class DeckMovementBound {

    private DeckMovementBound() {}

    /**
     * The most a body can move under its OWN power in one tick, blocks, on a deck it is captured on.
     *
     * <p>Sprint-jumping is the fastest a walking body travels and lands near 0.4; a fall on the deck
     * accelerates to a terminal that a captured body does not reach, because a body that leaves the
     * deck loses its capture and this bound with it. The value is deliberately several times the
     * measured maximum: the purpose is to refuse movements that no input could produce — the launch
     * that motivated this covered THIRTY blocks in a tick — not to police the last centimetre.
     */
    public static final double PLAYER_MAX_OWN_BLOCKS_PER_TICK = 2.0;

    /** A floor under the region, so a single lagging tick or a rounding of the deck's own carry can
     *  never produce a refusal on its own. */
    public static final double REGION_FLOOR_BLOCKS = 1.0;

    /** The most ticks one packet may claim allowance for. A client that went quiet for a minute does
     *  not get a minute's worth of movement in the packet that ends the silence. */
    public static final double MAX_CLAIMED_TICKS = 10.0;

    /** When each player was last judged, so a step spanning several ticks is judged against several
     *  ticks of allowance. Only a TIMESTAMP: the position anchor is read from the server on either
     *  side of the packet, precisely so a teleport cannot leave a stale one behind. */
    private static final java.util.Map<java.util.UUID, Long> LAST_SEEN_TICK =
            java.util.Collections.synchronizedMap(new java.util.WeakHashMap<java.util.UUID, Long>());

    /** Observation only — nothing branches on these. The first two are what the bound is set from;
     *  the third is what it did. */
    public static volatile double maxOwnDisplacementSeen;
    public static volatile double lastRefusedExcessBlocks;
    public static volatile long positionsRefused;

    /**
     * Whether the step from {@code (fromX, fromY, fromZ)} to {@code (toX, toY, toZ)} is one this
     * player could have taken in the handling of a single movement packet. {@code true} for anyone
     * this class has no opinion about — a body not captured on a deck is vanilla's business.
     *
     * <p><b>The two positions are the caller's to supply, and that is deliberate.</b> An earlier
     * version kept its own record of the last position it had accepted, and it was wrong in a way
     * worth remembering: a SERVER-side move — a teleport, a dimension change, a plugin setting a
     * position — is not a client claim at all, and comparing the next packet against a record taken
     * before it makes every teleport look like a body crossing thousands of blocks in a tick.
     * Measured: 3925 blocks of "own displacement" in one tick, and four refusals, all of them
     * ordinary staging teleports. Reading the server's position immediately before and after the
     * packet is handled has no such state to go stale: a teleport moves BOTH readings.</p>
     */
    public static boolean accepts(EntityPlayerMP player,
                                  double fromX, double fromY, double fromZ,
                                  double toX, double toY, double toZ) {
        if (player == null || player.world == null || player.world.isRemote) {
            return true;
        }
        final String shipId = ShipFrameTravel.aboardShipId(player);
        if (shipId == null) {
            return true;
        }

        final double dx = toX - fromX, dy = toY - fromY, dz = toZ - fromZ;
        final double moved = Math.sqrt(dx * dx + dy * dy + dz * dz);

        // The deck's own contribution, at the point the body is standing on — the server's number,
        // the same one the client was told.
        final double[] shipVel = VSIntegration.shipVelocityAtPointFor(player.world, shipId, toX, toY, toZ);
        final double carryPerTick = shipVel == null ? 0.0
                : Math.sqrt(shipVel[0] * shipVel[0] + shipVel[1] * shipVel[1] + shipVel[2] * shipVel[2]) * 0.05;

        // A packet does not always cover exactly one tick. Under load a client sends fewer of them
        // and each carries more ground — measured on a fast climb, a step of 1.47 blocks against a
        // one-tick allowance, from a body doing nothing but standing still on a rising deck. So the
        // region is scaled by the ticks that actually passed, which is the same correction the rate
        // arithmetic on the other side of this contract needed: an interval is time, never a count
        // of anything. Bounded above, because a client nobody heard from for a minute does not get a
        // minute's worth of licence.
        final long now = player.world.getTotalWorldTime();
        final Long previous = LAST_SEEN_TICK.put(player.getUniqueID(), now);
        final double ticks = previous == null ? 1.0
                : Math.max(1.0, Math.min(MAX_CLAIMED_TICKS, now - previous));

        final double allowed = (PLAYER_MAX_OWN_BLOCKS_PER_TICK + carryPerTick) * ticks + REGION_FLOOR_BLOCKS;

        // What the body did under its OWN power, as far as this can tell: what it covered beyond
        // what the deck carried it. This is the quantity the bound above is set from.
        final double own = Math.max(0.0, moved - carryPerTick * ticks);
        if (own > maxOwnDisplacementSeen) {
            maxOwnDisplacementSeen = own;
        }

        if (moved > allowed) {
            lastRefusedExcessBlocks = moved - allowed;
            positionsRefused++;
            return false;
        }
        return true;
    }
}
