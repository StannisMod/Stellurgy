package zmaster587.advancedRocketry.space;

import java.util.ArrayList;
import java.util.List;

import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityList;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.util.math.AxisAlignedBB;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.WorldServer;

import zmaster587.advancedRocketry.entity.EntityDummy;
import zmaster587.advancedRocketry.integration.vs.ShipFrameTravel;
import zmaster587.advancedRocketry.integration.vs.VSIntegration;

/**
 * Carries the bodies aboard a ship that are NOT its crew — a mob on the deck, a dropped item, a
 * minecart — across a crossing, by the same ship-relative point the crew is carried by.
 *
 * <h2>Why these are stowed rather than held</h2>
 *
 * A crew member's movement is client-authoritative, so a crossing has to negotiate with his client:
 * it places him and then pins him until that client takes the deck capture over. Nothing here is
 * negotiating with anyone. The server owns these bodies outright, so the honest treatment is the one
 * the ship's own blocks get — write them down, take them out of the world, and put them back on the
 * far side. That also removes the window a held body would have to survive: there is no moment in
 * which one of these is standing in a world whose ship has not been rebuilt yet, so there is nothing
 * for gravity to do to it.
 *
 * <h2>What counts as aboard</h2>
 *
 * The ship's own stay region, in its subspace — the same volume the hyperspace void judges a crew
 * member by, so a mob and a player standing side by side on a deck are aboard by one definition
 * rather than two. A body whose position cannot be mapped into that frame is not aboard anything and
 * is left exactly where it is.
 *
 * <p>Server main thread only; a safe no-op when the physics mod is absent or the ship is not loaded.</p>
 */
public final class AboardBodies {

    private static final org.apache.logging.log4j.Logger LOGGER =
            org.apache.logging.log4j.LogManager.getLogger("advancedrocketry/space");

    /**
     * How far outside the hull's own box a body still counts as aboard, in blocks. One block: a mob
     * standing on a deck has its feet on the surface and its box above it, and a dropped item rests
     * fractionally proud of the block it landed on.
     */
    private static final double ABOARD_MARGIN = 1.0D;

    /** One stowed body: what it was, and where on the ship it was. */
    public static final class Stowed {
        final NBTTagCompound nbt;
        final double dx, dy, dz;

        Stowed(NBTTagCompound nbt, double dx, double dy, double dz) {
            this.nbt = nbt;
            this.dx = dx;
            this.dy = dy;
            this.dz = dz;
        }
    }

    private AboardBodies() { }

    /**
     * Take every non-crew body aboard the ship whose flight computer sits at subspace {@code afcPos}
     * out of {@code world}, recording each against that computer. Call BEFORE the crossing cuts the
     * ship's blocks; the bodies are removed from the world, so a caller that does not go on to
     * {@link #release} them has destroyed them.
     *
     * <p>Players and seat dummies are never stowed: the crew transfer owns the first and the seat
     * binding owns the second. A body that is RIDING something is left to its vehicle — stowing a
     * passenger without its mount would put it back on the far side sitting on nothing.</p>
     */
    public static List<Stowed> capture(WorldServer world, BlockPos afcPos) {
        List<Stowed> stowed = new ArrayList<>();
        if (world == null || afcPos == null) {
            return stowed;
        }
        String vsShipId = VSIntegration.shipIdManagingBlock(world, afcPos);
        AxisAlignedBB stay = vsShipId == null
                ? null : VSIntegration.subspaceStayRegion(world, vsShipId, ABOARD_MARGIN);
        if (stay == null) {
            // Say so. Carrying nothing is the right answer when there is no ship to be aboard of, and
            // it is indistinguishable from "nothing was aboard" — which is exactly the silence that
            // makes a body quietly left behind unattributable.
            LOGGER.info("[SPACE] no loaded ship at {} to stow bodies from (ship id {}); carrying none",
                    afcPos, vsShipId);
            return stowed;
        }
        int scanned = 0;
        int considered = 0;
        for (Entity body : new ArrayList<>(world.loadedEntityList)) {
            scanned++;
            if (body.isDead || body instanceof EntityPlayer || body instanceof EntityDummy
                    || body.isRiding()) {
                continue;
            }
            considered++;
            double[] local = VSIntegration.toShipFrameFor(
                    world, vsShipId, body.posX, body.posY, body.posZ);
            if (local == null || !stay.contains(new Vec3d(local[0], local[1], local[2]))) {
                continue;
            }
            NBTTagCompound nbt = new NBTTagCompound();
            // An entity that refuses to be written down is one vanilla itself would not save across
            // a world unload — leave it alone rather than delete it for the sake of a carry.
            if (!body.writeToNBTOptional(nbt)) {
                continue;
            }
            double[] offset = ShipRelativePoint.offsetOfSubspacePoint(
                    afcPos, local[0], local[1], local[2]);
            stowed.add(new Stowed(nbt, offset[0], offset[1], offset[2]));
            body.setDead();
        }
        if (!stowed.isEmpty()) {
            LOGGER.info("[SPACE] stowed {} body(ies) aboard the ship at {} for its crossing",
                    stowed.size(), afcPos);
        } else {
            // CARRYING NOTHING IS ALSO AN ANSWER, and it used to be the one case here that said
            // nothing at all. "there was nobody aboard" and "somebody was aboard and this did not
            // see him" are the same silence otherwise, and only the second is a defect — so the
            // SCAN is reported: how many entities this world offered, how many survived the
            // filter, and which ship they were measured against. A crossing that quietly loses
            // cargo is then a line in the log rather than an absence a player notices later.
            LOGGER.info("[SPACE] the ship at {} (physics id {}) carries no loose body across its "
                    + "crossing: {} entity(ies) in this world, {} of them eligible, none inside its "
                    + "stay region {}", afcPos, vsShipId, scanned, considered, stay);
        }
        return stowed;
    }

    /** One decimal place, dot-separated whatever the machine's locale is. */
    private static double round1(double v) {
        return Math.round(v * 10.0) / 10.0;
    }

    /**
     * Put every stowed body back on the re-assembled ship whose flight computer sits at subspace
     * {@code afcPos} in {@code dstWorld}, at the point it was taken from, at rest RELATIVE TO THE
     * DECK, and HELD there. Returns how many were placed; {@code 0} with a non-empty list means the
     * ship is not up yet and the caller should retry, which is the same contract the crew placement
     * has.
     *
     * <p><b>Held, not merely placed.</b> "At rest" is a statement about the DECK's frame, not the
     * world's: a craft keeps its cruise across a crossing, so the ship a carry delivers to is
     * typically moving, and a body put down in the right place with no hold is left behind on the
     * next tick. So the placement is followed by the same declaration the crew placement makes,
     * and the per-tick deck pass carries the body from then on.</p>
     *
     * <p><b>All or nothing.</b> Whether the ship can say where a point on it is does not vary from
     * body to body — it is one question about one ship — so it is asked ONCE, before anything is
     * placed. A partial release would be re-run by the caller's retry and put the bodies it already
     * placed into the world a second time, which is how a carry turns into duplication.</p>
     */
    public static int release(WorldServer dstWorld, BlockPos afcPos, List<Stowed> bodies) {
        if (dstWorld == null || afcPos == null || bodies == null || bodies.isEmpty()) {
            return 0;
        }
        // Registry-keyed like the crew placement, and for the same reason: an arriving ship has
        // nobody near it, so a question only a LOADED ship can answer would never be answered.
        double[] afcWorld = VSIntegration.getRegisteredSubspacePointWorldPosition(dstWorld, afcPos,
                afcPos.getX(), afcPos.getY(), afcPos.getZ());
        if (afcWorld == null) {
            return 0; // the ship is not rebuilt here yet; nothing is lost, the caller retries
        }
        // The ship's IDENTITY, asked once and part of the same "is it up yet" question. A body is
        // not merely put down on a deck, it is HELD to it (below), and a hold names its ship. The
        // registry answered a point on this craft one line ago, so a null here is an inconsistency
        // rather than a "not yet" — but it is treated as "not yet" deliberately: retrying is what
        // the caller already does, while placing an UNHELD body would be the very defect this
        // method exists to avoid, dressed as success.
        String shipId = VSIntegration.shipIdManagingBlock(dstWorld, afcPos);
        if (shipId == null) {
            LOGGER.warn("[SPACE] the ship at {} answers for its own points but has no id to hold a "
                    + "carried body to; releasing nothing this pass", afcPos);
            return 0;
        }
        int placed = 0, unmappable = 0, unbuildable = 0, refused = 0;
        StringBuilder where = new StringBuilder();
        for (Stowed body : bodies) {
            double[] sub = ShipRelativePoint.subspacePointOf(afcPos, body.dx, body.dy, body.dz);
            double[] world = sub == null ? null
                    : VSIntegration.getRegisteredSubspacePointWorldPosition(
                            dstWorld, afcPos, sub[0], sub[1], sub[2]);
            if (world == null) {
                unmappable++;
                continue;
            }
            Entity restored = EntityList.createEntityFromNBT(body.nbt, dstWorld);
            if (restored == null) {
                unbuildable++;
                continue; // an entity type this world cannot build; its record is dropped, not retried
            }
            restored.motionX = 0.0D;
            restored.motionY = 0.0D;
            restored.motionZ = 0.0D;
            restored.fallDistance = 0.0f;
            // Through the shared arrival spawn: it loads the chunk the body lands in, which
            // `spawnEntity` needs and does not do, and it hands back what the WORLD said. This
            // counted a placement either way before, so a carry that put its cargo nowhere reported
            // the same number as one that put it down.
            boolean accepted = ArrivalSpawn.at(dstWorld, restored, world[0], world[1], world[2]);
            // The identity is logged because the body is followed by uuid afterwards, and a re-spawn
            // that minted a new one would be invisible from every later reading.
            LOGGER.info("[SPACE] released a carried body into dim {} at ({},{},{}): accepted={} "
                            + "uuid={}", dstWorld.provider.getDimension(), world[0], world[1],
                    world[2], accepted, restored.getUniqueID());
            if (!accepted) {
                refused++;
                // WHERE it was refused, in the same breath. The refusal branch used to print counts
                // only, so the one question it raises - which chunk is missing - could not be answered
                // from it, and the reader had to guess from a passing run's coordinates.
                where.append(where.length() == 0 ? "" : " | ")
                        .append("refused@").append(round1(world[0])).append(' ')
                        .append(round1(world[1])).append(' ').append(round1(world[2]))
                        .append(" chunk ").append(net.minecraft.util.math.MathHelper.floor(world[0] / 16.0))
                        .append(',').append(net.minecraft.util.math.MathHelper.floor(world[2] / 16.0));
                continue;
            }
            // Plain concatenation, never String.format("%.1f"): the default locale on a Russian
            // Windows prints a DECIMAL COMMA, which turns three coordinates into six numbers and
            // nobody reading the line can tell where one of them ends. A double prints with a dot
            // whatever the locale is.
            where.append(where.length() == 0 ? "" : " | ")
                    .append(round1(world[0])).append(' ')
                    .append(round1(world[1])).append(' ')
                    .append(round1(world[2]));
            // HELD, not merely placed — and this is the half the crew path always had and this one
            // did not. `CrewTransfer` puts a player on his deck point and then DECLARES the hold
            // (`DeckHold.holdOnDeck`), so the per-tick deck pass carries him when the craft moves.
            // A carried body got the placement and no declaration, so it was correct for exactly as
            // long as the ship stood still — and a ship crossing a seam is typically UNDER WAY,
            // because a craft keeps its cruise across a crossing by design. The deck then climbed
            // out from under the cargo, which reads afterwards as "the crossing dropped it".
            //
            // The contact capture cannot cover this: it is keyed on deck SUPPORT and takes an
            // EntityLivingBase, so an item — the commonest thing to be carrying — is never a
            // candidate for it. A declared seed is the only path that reaches a non-living body,
            // and it is the same one a dismount and an arrival already use.
            boolean held = ShipFrameTravel.seedShipFrameCapture(
                    restored, shipId, sub[0], sub[1], sub[2]);
            if (!held) {
                // Placed but not held: say so rather than counting it as a clean carry. The body is
                // where it should be THIS tick and will be left behind the moment the craft moves,
                // which is a different outcome from the one this method promises.
                LOGGER.warn("[SPACE] a carried body was released onto the ship at {} but could not "
                        + "be held to its deck (uuid={}); it will not follow the craft", afcPos,
                        restored.getUniqueID());
            }
            placed++;
        }
        // Say what happened, in both directions. A carry that delivers everything and a carry that
        // drops half of it used to be the same silence, and the count alone cannot be read without
        // the two reasons beside it: a body whose point would not map is one the ship could not place
        // yet, a body this world could not build is one whose record has just been thrown away for
        // good. They are different losses and only one of them is retried.
        if (unmappable > 0 || unbuildable > 0 || refused > 0) {
            LOGGER.warn("[SPACE] released {} of {} stowed body(ies) onto the ship at {}: {} could not "
                            + "be mapped onto it, {} could not be rebuilt in this world (dropped for "
                            + "good), {} were REFUSED by the world itself even with the chunk they "
                            + "would land in loaded. Bodies: {}",
                    placed, bodies.size(), afcPos, unmappable, unbuildable, refused, where);
        } else {
            // WHERE, not just how many. A body that came back and a body that came back to the wrong
            // place are the same count, and the second one is what "the jump lost my things" actually
            // looks like from inside the game. The computer's own mapped position is printed beside
            // them because it is the frame every one of these coordinates was derived from: if the
            // bodies sit around it and the ship reports itself somewhere else, the disagreement is
            // between two answers about the ship, not between the ship and its cargo.
            LOGGER.info("[SPACE] released {} body(ies) back onto the ship at {}, whose computer maps "
                            + "to {} {} {} in the world; bodies at {}",
                    placed, afcPos, round1(afcWorld[0]), round1(afcWorld[1]), round1(afcWorld[2]),
                    where);
        }
        return placed;
    }
}
