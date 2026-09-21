package zmaster587.advancedRocketry.test.client;

import org.junit.FixMethodOrder;
import org.junit.Test;
import org.junit.runners.MethodSorters;


import zmaster587.advancedRocketry.test.Reply;
import zmaster587.advancedRocketry.test.PilotSeat;
import zmaster587.advancedRocketry.test.Events;
import zmaster587.advancedRocketry.test.FixtureSite;
import zmaster587.advancedRocketry.test.RocketFixture;
import zmaster587.advancedRocketry.test.Plot;
import zmaster587.advancedRocketry.test.ShipIdentity;

import static org.junit.Assert.assertTrue;

/**
 * Spike — in WHICH coordinate frame does an oxygen vent seal, once its blocks belong to an
 * assembled Valkyrien Skies ship, and does the crew's own breathe gate resolve against that seal?
 *
 * <p>Two identical sealed cabins are built and pressurised in the same run:</p>
 *
 * <ul>
 *   <li><b>Control — static world.</b> A cabin on the ground, far from any ship. It proves the
 *       instrument is sensitive: the vent seals, the position query reports
 *       {@code PressurizedAir}, and the player standing inside it has that atmosphere cached by
 *       the per-entity gate. Without this leg an "air" reading on the ship leg would be
 *       unfalsifiable — it could equally mean the vent, the probe or the cache never worked.</li>
 *   <li><b>Subject — aboard an assembled ship.</b> The same cabin built at the ship's SUBSPACE
 *       addresses, its vent sealed there, then queried twice: at the subspace cell, and at the
 *       WORLD cell the same cabin actually occupies (mapped through the live ship transform).
 *       The player is teleported into that world cell — physically inside the pressurised
 *       cabin — and his cached atmosphere is read back.</li>
 * </ul>
 *
 * <p>The subject can falsify the premise: if the blob were built (or re-keyed) in world
 * coordinates, the world-cell query and the aboard player would both report {@code PressurizedAir}
 * and no ship-frame conversion would be needed at the gate at all. The run prints every raw
 * payload, and each assertion carries them, so a red is a finished measurement rather than the
 * start of one.</p>
 *
 *
 * <p><b>What it measured (2026-07-26).</b> The vent seals identically in both frames
 * ({@code blobSize=28} on the ground and aboard), so a ship's own blocks seal normally; the blob's
 * member cells are the ship's SUBSPACE addresses ({@code 5120005,129,51200} &rarr;
 * {@code PressurizedAir}), while the WORLD cell the same cabin occupies ({@code 5207,70,5203})
 * reports the dimension default, and a player standing inside that pressurised cabin resolves
 * {@code air} — against {@code PressurizedAir} for the identical cabin on the ground. The
 * assertions therefore pin the CURRENT behaviour: when the atmosphere gate learns to resolve in
 * the ship frame this test goes red and must be rewritten to the new contract.</p>
 */
@FixMethodOrder(MethodSorters.NAME_ASCENDING)
public class VSShipAtmosphereFrameSpikeTest extends AbstractSharedVsClientE2ETest {

    /**
     * How far apart the subspace and world frames must be, in blocks, for their comparison to mean
     * anything.
     *
     * <p>The TEST'S OWN sensitivity bar: if the two frames nearly coincide the comparison is
     * vacuous, and a hundred blocks is far beyond any settle while far inside the distance the
     * craft is flown.</p>
     */
    private static final double FRAMES_GENUINELY_DIFFER_BLOCKS = 100.0;

    @Override
    protected String subsystem() {
        return "vs-ship-atmosphere-frame";
    }

    private static final String COUNT = "count";
    private static final String ATM_TYPE = "type";
    private static final String CACHED_ATM = "cachedAtmosphere";
    private static final String BLOB_SIZE = "blobSize";
    private static final String WORLD_X = "worldX";
    private static final String WORLD_Y = "worldY";
    private static final String WORLD_Z = "worldZ";

    private static final String VARIANT = "with-pilot-seat";

    /**
     * How far into the plot the static control cabin stands, measured from the plot's origin.
     *
     * <p><b>The separation is the number that matters, not the address.</b> The cabin is the
     * CONTROL leg — the same shell, in the plain world — so it has to be clear of the ship, of the
     * ship's drift while the cabin is built, and of the shipyard VS relocates hull blocks into. It
     * stood 400 blocks from the build site (5200 vs 5600) for as long as both were hand-picked, and
     * this offset preserves exactly that distance while letting the allocator choose where the pair
     * lives. {@link #lane()} widens the plot to hold both; a cabin outside the plot would be a
     * second hand-picked coordinate wearing a helper's clothes.</p>
     *
     * <p>Its Y is the open-air band and was a hard-coded 70 until 2026-09-14. The cabin is a stone
     * shell this class BUILDS, so it wants no terrain: what a fixed 70 bought was whatever the seed
     * left touching the shell, and a sealed volume whose walls may be continuous with the landscape
     * is the one thing this leg must not be unsure about.</p>
     */
    private static final int CABIN_INSET = 420;

    /**
     * How long the atmosphere gate is given to evaluate a moved player, in ticks.
     *
     * <p>The gate runs on the player's own {@code LivingUpdateEvent}, so one server tick after the
     * teleport is the whole of what is being waited for; the rest is slack for a loaded box. It is
     * the budget the twenty-read probe poll it replaces spent ({@code 20 * 5}), kept so the
     * conversion changes the FORM of the wait and not how long it is willing to wait.</p>
     */
    private static final int ATMOSPHERE_GATE_WINDOW_TICKS = 100;

    /**
     * Wide enough for the ship AND its control cabin, which stand {@value #CABIN_INSET} blocks
     * apart. This is the supported way to need more room: a scenario whose structures do not fit
     * declares a wider lane, rather than reaching past its plot into a neighbour's.
     */
    @Override
    protected Plot.Lane lane() {
        return new Plot.Lane(SHIP_LANE.originX, SHIP_LANE.originZ, 512, 512);
    }

    @Test
    public void aSealedShipCabinDoesNotReachItsOwnCrew_documentsKnownBug() throws Exception {

        // WHERE THIS SCENARIO STANDS IS ASKED FOR, NOT CHOSEN. Both structures come off the one
        // plot, so the 400-block separation below is the only spatial fact this class still states.
        final FixtureSite site = site();
        final int bx = site.x, by = site.y, bz = site.z;
        final int cx = plot().x(CABIN_INSET), cy = FixtureSite.OPEN_AIR_Y, cz = plot().z(CABIN_INSET);
        anchor = new int[]{bx, by + 5, bz};

        // ── Control leg: the same cabin, in the plain world ───────────────────────────────
        buildCabin(cx, cy, cz);
        String ctrlSeal = sealCabin(cx, cy, cz);
        int ctrlBlob = readInt(ctrlSeal, BLOB_SIZE);
        String ctrlAtm = atmosphereAt(cx, cy, cz);
        System.out.println("[S1/control] seal=" + ctrlSeal + " atm=" + ctrlAtm);
        assertTrue("CONTROL: a vent in a sealed cabin in the plain world must seal a non-empty "
                        + "blob — otherwise nothing measured on the ship means anything (seal="
                        + ctrlSeal + ")",
                ctrlBlob > 0);
        assertTrue("CONTROL: the sealed cabin's own cell must read PressurizedAir (got " + ctrlAtm
                        + ", raw=" + ctrlSeal + ")",
                "PressurizedAir".equalsIgnoreCase(ctrlAtm));

        String ctrlCached = cachedAtmosphereWithPlayerAt(cx + 0.5, cy, cz + 0.5);
        System.out.println("[S1/control] cachedForPlayer=" + ctrlCached);
        assertTrue("CONTROL: the per-entity gate must see the seal for a player standing INSIDE "
                        + "the static cabin — this is the instrument the ship leg reads (cached="
                        + ctrlCached + ")",
                "PressurizedAir".equalsIgnoreCase(ctrlCached));

        // ── Subject leg: the same cabin, aboard an assembled ship ─────────────────────────
        // Bring the client to the build site BEFORE assembling: a client near the ship is what
        // makes VS load it (a headless server alone never does), and one run that assembled with
        // the player still 400 blocks away at the control cabin left the registry empty.
        long approachMark = clientEvents().mark();
        exec("tp @a " + (bx + 0.5) + " " + (by + 8) + " " + (bz + 0.5) + " 0 0");
        // The comment above is the reason this is a LINK and not twenty ticks: what makes VS load
        // the ship is a CLIENT being near it, and a client still on its way is exactly the run that
        // left the registry empty.
        awaitClientPlacedNear(approachMark, bx + 0.5, bz + 0.5,
                "the client must be AT the build site before the assembly, because a client near the"
                        + " ship is what makes the physics mod load it");

        String assemble = assembleFixture(site);
        System.out.println("[S1/ship] assemble=" + assemble);
        assertTrue("a with-pilot-seat build must route to a VS ship (no rocket): " + assemble,
                (Reply.of(assemble).integer("rocketCount") == 0));
        int all = 0;
        for (int i = 0; i < 60 && all < 1; i++) {
            bot().waitTicks(5);
            all = count("ship-count-all");
        }
        scenario().requireArranged("assembly must create a VS ship (all=" + all + ", assemble="
                + assemble + ")", all >= 1);

        int loaded = 0;
        for (int i = 0; i < 40 && loaded < 1; i++) {
            bot().waitTicks(5);
            loaded = count("ship-count");
        }
        scenario().requireArranged("the ship must LOAD with the client present (loaded=" + loaded
                + ", all=" + all + ")", loaded >= 1);

        // The craft this spike built, by the name the assembler minted — so every lookup below is
        // about it and not about whichever hull is nearest the pad on a world the tier shares.
        scenarioShipId = ShipIdentity.awaitPhysicsIdOf(this::exec, 0,
                ShipIdentity.nameFromAssembly(assemble), 40, () -> bot().waitTicks(5));

        PilotSeat seat = PilotSeat.byId(this::exec, 0, scenarioShipId)
                .requireFound("find-seat must resolve the ship's subspace seat");
        int sx = seat.seatX;
        int sy = seat.seatY;
        int sz = seat.seatZ;
        System.out.println("[S1/ship] seatSubspace=" + sx + "," + sy + "," + sz);

        // The cabin goes beside the seat, in the ship's own (subspace) addresses.
        int vx = sx + 4, vy = sy, vz = sz;
        buildCabin(vx, vy, vz);
        String shipSeal = sealCabin(vx, vy, vz);
        int shipBlob = readInt(shipSeal, BLOB_SIZE);
        System.out.println("[S1/ship] seal=" + shipSeal);

        // RESULT 1 — does a vent whose blocks belong to a ship seal at all?
        assertTrue("RESULT-1: a vent in a sealed cabin built on an ASSEMBLED ship must still seal "
                        + "a blob (control blob=" + ctrlBlob + ", ship seal=" + shipSeal + ")",
                shipBlob > 0);

        // RESULT 2 — the frame the member cells actually live in.
        String subAtm = atmosphereAt(vx, vy, vz);
        double[] w = toWorld(vx, vy, vz);
        int wx = (int) Math.floor(w[0]), wy = (int) Math.ceil(w[1]), wz = (int) Math.floor(w[2]);
        String worldAtm = atmosphereAt(wx, wy, wz);
        System.out.println("[S1/ship] subspaceCell=" + vx + "," + vy + "," + vz + " -> " + subAtm
                + " | worldCell=" + wx + "," + wy + "," + wz + " -> " + worldAtm);

        double separation = Math.abs(w[0] - vx) + Math.abs(w[1] - vy) + Math.abs(w[2] - vz);
        scenario().requireArranged("the two frames must genuinely differ, else the comparison is "
                        + "vacuous (subspace=" + vx + "," + vy + "," + vz + " world=" + w[0] + ","
                        + w[1] + "," + w[2] + " separation=" + separation + ")",
                separation > FRAMES_GENUINELY_DIFFER_BLOCKS);

        assertTrue("RESULT-2: the sealed cabin's SUBSPACE cell reports " + subAtm
                        + " (expected PressurizedAir — the blob is keyed in ship-block addresses)",
                "PressurizedAir".equalsIgnoreCase(subAtm));
        assertTrue("RESULT-2: the WORLD cell the cabin actually occupies reports " + worldAtm
                        + " — with a subspace-keyed blob it must NOT be PressurizedAir; if it IS, "
                        + "the atmosphere already resolves in the world frame for ship blocks",
                !"PressurizedAir".equalsIgnoreCase(worldAtm));

        // RESULT 3 — the gate that actually matters: a body standing inside the sealed cabin.
        // Re-map first: the ship keeps drifting, so the cabin's world image is only valid now.
        double[] wNow = toWorld(vx, vy, vz);
        String aboardCached = cachedAtmosphereWithPlayerAt(wNow[0], wNow[1], wNow[2]);
        System.out.println("[S1/ship] cachedForPlayerAboard=" + aboardCached);
        assertTrue("RESULT-3: a player standing INSIDE the ship's pressurised cabin resolves "
                        + aboardCached + " — the per-entity gate keys his WORLD position against "
                        + "a SUBSPACE-keyed blob, so a sealed hull does not reach its own crew "
                        + "(control, same cabin on the ground: " + ctrlCached + ")",
                !"PressurizedAir".equalsIgnoreCase(aboardCached));
    }

    // ── cabin construction / sealing ──────────────────────────────────────────────────────

    /**
     * A 3×3×3 air cavity inside a solid stone shell, with the vent set into the floor directly
     * under the cavity — the same shape as the static-world vent tests, so the two legs differ
     * only in which frame the blocks live in.
     */
    private void buildCabin(int x, int y, int z) throws Exception {
        assertTrue("chunk warmup failed",
                Reply.of(exec("artest chunk warmup 0 " + ((x - 2) >> 4) + " " + ((z - 2) >> 4)
                        + " " + ((x + 4) >> 4) + " " + ((z + 4) >> 4))).ok());
        assertTrue("cabin shell fill failed",
                Reply.of(exec("artest fill 0 " + (x - 1) + " " + (y - 2) + " " + (z - 1)
                        + " " + (x + 3) + " " + (y + 3) + " " + (z + 3) + " minecraft:stone")
                        ).ok());
        assertTrue("cabin cavity fill failed",
                Reply.of(exec("artest fill 0 " + x + " " + y + " " + z
                        + " " + (x + 2) + " " + (y + 2) + " " + (z + 2) + " minecraft:air")
                        ).ok());
    }

    /** Place, fuel and force-seal the cabin's vent; returns the raw {@code vent reseal} payload. */
    private String sealCabin(int x, int y, int z) throws Exception {
        String place = exec("artest place 0 " + x + " " + (y - 1) + " " + z
                + " advancedrocketry:oxygenVent");
        assertTrue("vent place failed: " + place, Reply.of(place).bool("placed"));
        assertTrue("energy inject failed",
                Reply.of(exec("artest energy inject 0 " + x + " " + (y - 1) + " " + z + " 1000000")
                        ).ok());
        assertTrue("oxygen inject failed",
                Reply.of(exec("artest fluid inject 0 " + x + " " + (y - 1) + " " + z + " oxygen 16000")
                        ).ok());
        exec("artest tile force-tick 0 " + x + " " + (y - 1) + " " + z + " 1");
        String reseal = exec("artest vent reseal 0 " + x + " " + (y - 1) + " " + z);
        exec("artest tile force-tick 0 " + x + " " + (y - 1) + " " + z + " 5");
        return reseal;
    }

    // ── observation ───────────────────────────────────────────────────────────────────────

    private String atmosphereAt(int x, int y, int z) throws Exception {
        String info = exec("artest atmosphere get 0 " + x + " " + y + " " + z);
        Reply mReply = Reply.of(info);
        assertTrue("atmosphere type not found in: " + info, mReply.has(ATM_TYPE));
        return mReply.text(ATM_TYPE);
    }

    /**
     * Teleport the player to a point and answer what the per-entity gate then resolved him to.
     *
     * <p>The wait is production's own decision, not a re-read of its result. The gate caches a
     * player's atmosphere only when the resolved type CHANGES, and that change is a write it makes
     * in one breath with the sync packet — so it is recorded ({@code player_atmosphere_changed},
     * carrying what he was resolved TO and what the cache held before). The two legs are still run
     * in opposite directions, because no change means no record and nothing to observe: an
     * atmosphere the player is already in is not a thing that happens.</p>
     *
     * <p>What this replaced was a poll of the cache through a reflective probe, twenty reads of a
     * private static map until one came back non-empty. It could not say when the change happened,
     * could not tell a change made and undone from one never made, and answered the same silence
     * for "the gate resolved him to what he was already in" as for "the gate never ran".</p>
     */
    private String cachedAtmosphereWithPlayerAt(double x, double y, double z) throws Exception {
        long mark = events().markInstrumented();
        exec("tp @a " + x + " " + y + " " + z + " 0 0");
        // A WINDOW, and it is not a poll: there is nothing here to ask twice. The handler ticks
        // EVERY entity in its dimension on that entity's own living update, so it evaluates the
        // player at his new position on the next server tick — both legs of this test are in dim 0,
        // and `tp` has already moved the server's copy of him by the time the command answers. What
        // the window is for is load, not convergence.
        bot().waitTicks(ATMOSPHERE_GATE_WINDOW_TICKS);
        String changes = events().since(mark, "player_atmosphere_changed");

        // AN ABSENCE IS AN ANSWER HERE, and this is what makes it one. One leg of this test expects
        // the gate to resolve the player into a sealed cabin and the other expects it NOT to — that
        // second expectation IS the bug being documented — so a silent log is a finding rather than
        // a failure. It is only a finding while the log can prove it was listening: without this, a
        // mixin that never wove and a gate that never changed its mind produce the same silence.
        Events.assertInstrumentRan(changes, "atmosphere_change_events",
                "whether the per-entity atmosphere gate changed its mind about this player");

        // The RESOLUTION itself is a state, not an event, and is read as one. The record above says
        // a change HAPPENED and when; it cannot say what the player is resolved to now, because the
        // answer when nothing changed is the value already in the cache. Reading both is the point:
        // the pair separates "he was moved into a different atmosphere" from "he is in the same one
        // he was in", which is exactly the difference between this test's two legs.
        String resp = exec("artest atmosphere cached-for-player");
        Reply mReply = Reply.of(resp);
        String cached = mReply.has(CACHED_ATM) ? mReply.text(CACHED_ATM) : "";
        System.out.println("[S1/gate] at (" + x + "," + y + "," + z + ") cached=" + cached
                + " changes=" + changes);
        return cached;
    }

    /**
     * A world point currently INSIDE the ship, needed by every world-keyed VS lookup. The build
     * site is not one — the ship's blocks sit above the pad's base course, and the ship drifts
     * under gravity while the cabin is being built — so the anchor is re-derived from the seat's
     * own live world position (which is keyed off its SUBSPACE block and so never goes stale)
     * every time it is used.
     */
    private int[] anchor;

    /** The craft this spike assembled — the subject of every lookup, and never re-derived from a pose. */
    private String scenarioShipId;

    private int[] refreshAnchor() throws Exception {
        // ONE lookup, by name. This used to try four build-site coordinates in turn and take the
        // first that answered — a search whose success condition was "some ship's yard was reachable
        // from one of these points", which a neighbour's craft satisfies as readily as this one's.
        PilotSeat pose = PilotSeat.byId(this::exec, 0, scenarioShipId);
        if (!Double.isNaN(pose.shipWorldX)) {
            anchor = new int[]{(int) Math.floor(pose.shipWorldX),
                    (int) Math.floor(pose.shipWorldY),
                    (int) Math.floor(pose.shipWorldZ)};
            return anchor;
        }
        throw new AssertionError("ARRANGEMENT: the ship " + scenarioShipId + " reports no world "
                + "position for its seat — it is gone or was never loaded: " + pose.raw());
    }

    private double[] toWorld(int sx, int sy, int sz) throws Exception {
        // The anchor is still refreshed — it proves the craft is live and locatable — but the mapping
        // goes through the ship this spike NAMES. The positional form maps through the first hull
        // containing the anchor, and a point can be inside more than one.
        int[] a = refreshAnchor();
        String resp = exec("artest vs to-world 0 id " + scenarioShipId
                + " " + sx + " " + sy + " " + sz);
        Reply mapped = Reply.of("artest vs to-world", resp);
        scenario().requireArranged("subspace->world mapping failed (anchor=" + a[0] + "," + a[1] + ","
                + a[2] + "): " + resp, mapped.has(WORLD_X));
        return new double[]{mapped.number(WORLD_X), mapped.number(WORLD_Y), mapped.number(WORLD_Z)};
    }

    // ── plumbing ──────────────────────────────────────────────────────────────────────────

    private int readInt(String json, String field) {
        Reply reply = Reply.of(json);
        assertTrue("field `" + field + "` not found in: " + json, reply.has(field));
        return reply.integer(field);
    }

    private int count(String sub) throws Exception {
        Reply mReply = Reply.of(exec("artest vs " + sub + " 0"));
        return mReply.has(COUNT) ? Integer.parseInt(mReply.text(COUNT)) : -1;
    }

    private String assembleFixture(FixtureSite site) throws Exception {
        // The site owns the coordinates; these aliases keep the body below unchanged.
        final int baseX = site.x, baseY = site.y, baseZ = site.z;
        // FIRST link: the volume the hull and the cabin built on it occupy is EMPTY, measured by
        // the air fill's own `placed`. Open air, so this ASSERTS rather than digs. It matters more
        // here than in most: the subject is a SEALED cabin, and a shell whose stone is continuous
        // with the pit's wall is not the shape this leg believes it built.
        return RocketFixture.assembleAt(site, this::exec, VARIANT, 2, 16,
                "the hull, and the sealed cabin raised on it");
    }
}
