package dev.stannismod.stellurgy.test.server;

import dev.stannismod.stellurgy.test.FixtureSite;
import dev.stannismod.stellurgy.test.Reply;
import org.junit.Test;

import static org.junit.Assert.*;

/**
 * EntityElevatorCapsule NBT save contract: the optional {@code dstDimid + dstLoc} and
 * {@code srcDimid + srcLoc} blocks are omitted for a capsule that has none, and the reader accepts
 * their absence without NPE.
 *
 * <p>Position-isolated at x=7000. Uses {@link AbstractSharedServerTest} so the harness JVM
 * cold-starts once per class.</p>
 */
public class ElevatorCapsuleStateAndNbtTest extends AbstractSharedServerTest {

    private static final int BASE_X = 7000;
    /**
     * The open-air band, not terrain. A hard-coded 80 until 2026-09-14, and no scenario here wants
     * ground: the subject is the capsule's NBT, read out of the entity the tick after it spawns. 80
     * bought whatever the pinned seed rolled at x=7000 — unsurveyed either way — so the capsule may
     * have been spawned inside the landscape, which for a 3x3 entity is a collision the scenario never
     * asks about and would not report. In the band there is nothing to be inside of.
     */
    private static final int BASE_Y = FixtureSite.OPEN_AIR_Y;
    private static final int BASE_Z = 7000;

    private static final String ENTITY_ID = "entityId";
    private static final String HAS_DST_KEY = "hasDstKey";
    private static final String HAS_SRC_KEY = "hasSrcKey";

    private static String join(java.util.List<String> resp) {
        return String.join("\n", resp);
    }

    private int spawnCapsule(int offsetX) throws Exception {
        int x = BASE_X + offsetX;
        // Force-load the chunk grid covering the capsule's 3x3 AABB
        // (setSize(3, 3) in EntityElevatorCapsule ctor) so
        // world.spawnEntity passes its isChunkLoaded gate. Without
        // this, spawn silently fails with "spawned":false at far-from-
        // origin coordinates.
        int cx1 = (x - 4) >> 4;
        int cz1 = (BASE_Z - 4) >> 4;
        int cx2 = (x + 4) >> 4;
        int cz2 = (BASE_Z + 4) >> 4;
        String warmup = join(client().arrange(
                "stellurgytest chunk warmup 0 " + cx1 + " " + cz1 + " " + cx2 + " " + cz2));
        assertTrue("chunk warmup failed: " + warmup,
                Reply.of(warmup).ok());
        String spawn = join(client().arrange(
                "stellurgytest entity spawn 0 " + x + ".5 " + BASE_Y + " " + BASE_Z + ".5"
                        + " stellurgy:StellurgySpaceElevatorCapsule"));
        assertTrue("capsule spawn failed: " + spawn,
                Reply.of(spawn).ok() && Reply.of(spawn).bool("spawned"));
        Reply mReply = Reply.of(spawn);
        assertTrue("spawn response must carry entityId: " + spawn, mReply.has(ENTITY_ID));
        return Integer.parseInt(mReply.text(ENTITY_ID));
    }

    private static boolean extractBool(String src, String field) {
        Reply reply = Reply.of(src);
        assertTrue("field `" + field + "` not found in: " + src, reply.has(field));
        return reply.bool(field);
    }

    @Test
    public void nbtRoundTripWithNullDstAndSrcSurvivesWithoutNpe() throws Exception {
        // A capsule that has NEVER been linked (no setDst, no
        // setSourceTile) is exactly the state a freshly-summoned
        // capsule sits in before TileSpaceElevator.notifyLanded
        // populates dst/src. The save format must omit the optional
        // keys (writeEntityToNBT gates both blocks on != null), and
        // readEntityFromNBT must accept the absent keys without NPE.
        int id = spawnCapsule(80);

        String rt = join(client().read(
                "stellurgytest entity capsule-nbt-roundtrip 0 " + id));
        assertTrue("roundtrip must succeed on un-linked capsule: " + rt,
                Reply.of(rt).ok());
        assertFalse("un-linked capsule must NOT write dstDimid key: " + rt,
                extractBool(rt, HAS_DST_KEY));
        assertFalse("un-linked capsule must NOT write srcDimid key: " + rt,
                extractBool(rt, HAS_SRC_KEY));
    }
}
