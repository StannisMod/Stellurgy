package zmaster587.advancedRocketry.test.server;

import zmaster587.advancedRocketry.test.Reply;
import org.junit.Test;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

import zmaster587.advancedRocketry.test.FixtureSite;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * EntityElevatorCapsule motion-state and
 * NBT save/load contracts.
 *
 * <p>Two contract families are pinned here at the testServer tier
 * (no client harness needed because none of these contracts depend on
 * player input):</p>
 *
 * <ol>
 *   <li><b>Motion-state getters reflect {@code setCapsuleMotion}.</b>
 *       The four public methods {@code isAscending() / isDescending() /
 *       isInMotion() / getStandTime()} are consumed by
 *       {@code RenderElevatorCapsule} (client-side render) and
 *       {@code TileSpaceElevator} (controller gating). The contract is
 *       that the boolean flags are consistent with the byte stored via
 *       {@code setCapsuleMotion}: {@code +1 -> ascending},
 *       {@code -1 -> descending}, {@code 0 -> none}.</li>
 *   <li><b>NBT round-trip preserves motionDir, dst, src.</b> The save
 *       format pins keys {@code motionDir}, {@code dstDimid + dstLoc},
 *       {@code srcDimid + srcLoc}. Both populated and empty
 *       (null dst / src) paths must survive a write/read cycle without
 *       NPE.</li>
 * </ol>
 *
 * <p>Position-isolated at x=7000 (each method picks a unique offset).
 * Uses {@link AbstractSharedServerTest} so the harness JVM cold-starts
 * once per class.</p>
 */
public class ElevatorCapsuleStateAndNbtTest extends AbstractSharedServerTest {

    private static final int BASE_X = 7000;
    /**
     * The open-air band, not terrain. A hard-coded 80 until 2026-09-14, and no scenario here wants
     * ground: the subjects are the capsule's motion FLAGS and its NBT round-trip, both read out of
     * the entity the tick after it spawns. 80 bought whatever the pinned seed rolled at x=7000 —
     * unsurveyed either way — so the capsule may have been spawned inside the landscape, which for
     * a 3x3 entity is a collision the scenario never asks about and would not report. In the band
     * there is nothing to be inside of.
     */
    private static final int BASE_Y = FixtureSite.OPEN_AIR_Y;
    private static final int BASE_Z = 7000;

    private static final String ENTITY_ID = "entityId";
    private static final String IS_ASCENDING = "isAscending";
    private static final String IS_DESCENDING = "isDescending";
    private static final String IS_IN_MOTION = "isInMotion";
    private static final String PEER_IS_ASCENDING = "peerIsAscending";
    private static final String PEER_IS_DESCENDING = "peerIsDescending";
    private static final String PEER_IS_IN_MOTION = "peerIsInMotion";
    private static final String HAS_DST_KEY = "hasDstKey";
    private static final String HAS_SRC_KEY = "hasSrcKey";
    private static final String MOTION_DIR_NBT = "motionDirNbt";
    private static final String DST_DIM = "dstDim";
    private static final String DST_X = "dstX";
    private static final String DST_Y = "dstY";
    private static final String DST_Z = "dstZ";
    private static final String SRC_DIM = "srcDim";

    private static String join(java.util.List<String> resp) {
        return String.join("\n", resp);
    }

    private static int spawnCapsule(int offsetX) throws Exception {
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
        String warmup = join(client().execute(
                "artest chunk warmup 0 " + cx1 + " " + cz1 + " " + cx2 + " " + cz2));
        assertTrue("chunk warmup failed: " + warmup,
                Reply.of(warmup).ok());
        String spawn = join(client().execute(
                "artest entity spawn 0 " + x + ".5 " + BASE_Y + " " + BASE_Z + ".5"
                        + " advancedrocketry:ARSpaceElevatorCapsule"));
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

    private static int extractInt(String src, String field) {
        Reply reply = Reply.of(src);
        assertTrue("field `" + field + "` not found in: " + src, reply.has(field));
        return reply.integer(field);
    }

    // ── Phase 2: motion-state contracts ──────────────────────────────────

    @Test
    public void setCapsuleMotionAscendingFlipsAscendingAndInMotion() throws Exception {
        int id = spawnCapsule(0);

        String setResp = join(client().execute(
                "artest entity capsule-set-motion 0 " + id + " 1"));
        assertTrue("capsule-set-motion(1) must succeed: " + setResp,
                Reply.of(setResp).ok());

        String state = join(client().execute(
                "artest entity capsule-state 0 " + id));
        assertTrue("motion=+1 must set isAscending=true: " + state,
                extractBool(state, IS_ASCENDING));
        assertFalse("motion=+1 must NOT set isDescending=true: " + state,
                extractBool(state, IS_DESCENDING));
        assertTrue("motion=+1 must set isInMotion=true: " + state,
                extractBool(state, IS_IN_MOTION));
    }

    @Test
    public void setCapsuleMotionDescendingFlipsDescendingAndInMotion() throws Exception {
        int id = spawnCapsule(20);

        String setResp = join(client().execute(
                "artest entity capsule-set-motion 0 " + id + " -1"));
        assertTrue("capsule-set-motion(-1) must succeed: " + setResp,
                Reply.of(setResp).ok());

        String state = join(client().execute(
                "artest entity capsule-state 0 " + id));
        assertFalse("motion=-1 must NOT set isAscending=true: " + state,
                extractBool(state, IS_ASCENDING));
        assertTrue("motion=-1 must set isDescending=true: " + state,
                extractBool(state, IS_DESCENDING));
        assertTrue("motion=-1 must set isInMotion=true: " + state,
                extractBool(state, IS_IN_MOTION));
    }

    @Test
    public void freshlySpawnedCapsuleIsNotInMotion() throws Exception {
        // Default state pin — a capsule that has never received a
        // setCapsuleMotion call reports !isAscending && !isDescending
        // && !isInMotion. This is the player-visible "idle on pad"
        // state that RenderElevatorCapsule paints as stationary.
        int id = spawnCapsule(40);

        String state = join(client().execute(
                "artest entity capsule-state 0 " + id));
        assertFalse("freshly-spawned capsule must NOT be ascending: " + state,
                extractBool(state, IS_ASCENDING));
        assertFalse("freshly-spawned capsule must NOT be descending: " + state,
                extractBool(state, IS_DESCENDING));
        assertFalse("freshly-spawned capsule must NOT be in motion: " + state,
                extractBool(state, IS_IN_MOTION));
    }

    // ── Phase 3: NBT round-trip ──────────────────────────────────────────

    @Test
    public void nbtRoundTripPreservesMotionDirAndDstAndSrc() throws Exception {
        int id = spawnCapsule(60);

        // Populate motion + dst + src — the three pieces the save
        // format must round-trip per writeEntityToNBT (lines 130-141).
        assertTrue(Reply.of(join(client().execute(
                "artest entity capsule-set-motion 0 " + id + " 1"))
                ).ok());
        assertTrue(Reply.of(join(client().execute(
                "artest entity capsule-set-dst 0 " + id + " 1 100 64 200"))
                ).ok());
        assertTrue(Reply.of(join(client().execute(
                "artest entity capsule-set-src 0 " + id + " 0 -50 70 -25"))
                ).ok());

        String rt = join(client().execute(
                "artest entity capsule-nbt-roundtrip 0 " + id));
        assertTrue("roundtrip probe must succeed: " + rt,
                Reply.of(rt).ok());

        // The save-format contract: the three NBT keys are present.
        assertTrue("populated capsule must serialize a dstDimid key: " + rt,
                extractBool(rt, HAS_DST_KEY));
        assertTrue("populated capsule must serialize a srcDimid key: " + rt,
                extractBool(rt, HAS_SRC_KEY));
        assertEquals("motionDir byte must round-trip as +1: " + rt,
                1, extractInt(rt, MOTION_DIR_NBT));

        // After readEntityFromNBT on a peer, the motion flags must
        // reflect the byte loaded from NBT.
        assertTrue("peer must report ascending after readEntityFromNBT: " + rt,
                extractBool(rt, PEER_IS_ASCENDING));
        assertFalse("peer must NOT report descending: " + rt,
                extractBool(rt, PEER_IS_DESCENDING));
        assertTrue("peer must report in-motion: " + rt,
                extractBool(rt, PEER_IS_IN_MOTION));

        // Position fields round-trip — these go into the
        // DimensionBlockPosition save-compat contract.
        assertEquals("dstDim must round-trip: " + rt,
                1, extractInt(rt, DST_DIM));
        assertEquals("dstX must round-trip: " + rt,
                100, extractInt(rt, DST_X));
        assertEquals("dstY must round-trip: " + rt,
                64, extractInt(rt, DST_Y));
        assertEquals("dstZ must round-trip: " + rt,
                200, extractInt(rt, DST_Z));
        assertEquals("srcDim must round-trip: " + rt,
                0, extractInt(rt, SRC_DIM));
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

        String rt = join(client().execute(
                "artest entity capsule-nbt-roundtrip 0 " + id));
        assertTrue("roundtrip must succeed on un-linked capsule: " + rt,
                Reply.of(rt).ok());
        assertFalse("un-linked capsule must NOT write dstDimid key: " + rt,
                extractBool(rt, HAS_DST_KEY));
        assertFalse("un-linked capsule must NOT write srcDimid key: " + rt,
                extractBool(rt, HAS_SRC_KEY));
        assertEquals("default motionDir must round-trip as 0: " + rt,
                0, extractInt(rt, MOTION_DIR_NBT));
        // Peer must also report idle after reading the partial NBT.
        assertFalse("peer must report not-in-motion after empty NBT load: " + rt,
                extractBool(rt, PEER_IS_IN_MOTION));
    }
}
