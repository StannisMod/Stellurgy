package zmaster587.advancedRocketry.test.server;

import zmaster587.advancedRocketry.test.RocketList;
import zmaster587.advancedRocketry.test.RocketInfo;
import zmaster587.advancedRocketry.test.Reply;
import org.junit.Test;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

import zmaster587.advancedRocketry.test.FixtureSite;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/**
 * nuclear-engine rocket assembly thrust aggregation.
 *
 * <p>The nuclear engine family — {@link
 * zmaster587.advancedRocketry.block.BlockNuclearRocketMotor},
 * {@link zmaster587.advancedRocketry.block.BlockNuclearCore},
 * {@link zmaster587.advancedRocketry.block.BlockNuclearFuelTank},
 * and the {@link zmaster587.advancedRocketry.api.IRocketNuclearCore}
 * interface — is wired into rocket assembly via two distinct scan paths:
 *
 * <ul>
 *   <li>{@link zmaster587.advancedRocketry.tile.TileRocketAssemblingMachine}
 *       lines 386-395 — initial scan during the player's "Build" click.</li>
 *   <li>{@link zmaster587.advancedRocketry.util.StorageChunk#recalculateStats}
 *       lines 222-224 — re-scan from the storage chunk's own snapshot.</li>
 * </ul>
 *
 * <p>Both paths apply a cohesion check: a placed {@code IRocketNuclearCore}
 * contributes its {@code getMaxThrust()} to {@code thrustNuclearReactorLimit}
 * <em>only if</em> the block directly below is either another
 * {@code IRocketNuclearCore} OR an {@code IRocketEngine}. The final rocket
 * thrust is {@code max(monopropellant, bipropellant, nuclearTotal)} where
 * {@code nuclearTotal = min(nozzleLimit, reactorLimit)} — so a nuclear
 * motor with NO contributing core gates the nuclear branch to zero, and
 * with no chemical engines either the final {@code stats.thrust} stays at
 * 0 (the player-visible "rocket cannot launch" state).</p>
 *
 * <p>Contracts pinned (two paired tests share one fixture chassis so the
 * delta isolates the cohesion check):</p>
 *
 * <ul>
 *   <li><b>Core stacked above nuclear motor &rarr; thrust &gt; 0.</b> The
 *       {@code with-nuclear-stack} fixture places 2 nuclear motors with
 *       cores directly above; the assembled rocket reports a positive
 *       {@code stats.thrust}, proving the nuclear chain energises the
 *       launch-readiness gate.</li>
 *   <li><b>Core misplaced (no engine/core below) &rarr; thrust = 0.</b> The
 *       {@code with-nuclear-misplaced} fixture places the same 2 nuclear
 *       motors but the core sits at the center column where below is air;
 *       {@code reactorLimit} stays 0 &rarr; {@code nuclearTotal=min(N,0)=0} &rarr;
 *       {@code thrust=max(0,0,0)=0}. Pins that the cohesion check is the
 *       difference, not just "presence of any nuclear block".</li>
 * </ul>
 *
 * <p>Rejected sub-pins: exact thrust magnitude (= 35 per motor × ratio)
 * is an impl detail — the player-visible contract is "rocket has thrust"
 * vs "rocket has none", not the specific numbers. The
 * {@code nuclearCoreThrustRatio} config flows through {@link
 * zmaster587.advancedRocketry.test.unit.ARConfigurationTest} and is
 * impl on this surface.</p>
 */
public class NuclearEngineRocketAssemblyTest extends AbstractSharedServerTest {

    private static final String BUILDER_POS = "builderPos";
    private static final String ROCKET_LIST_ID = "id";

    @Test
    public void nuclearCoreAboveMotorContributesNuclearThrust() throws Exception {
        int entityId = buildAndAssemble(FixtureSite.openAir(0, 1700, 500), "with-nuclear-stack");
        RocketInfo info = RocketInfo.byId(
                cmd -> String.join("\n", client().execute(cmd)), entityId);
        // Both nuclear motors must register in engineCount via the
        // IRocketEngine + air-below scan branch (BlockNuclearRocketMotor
        // extends BlockRocketMotor; with-nuclear-stack overrides BOTH
        // engine positions with nuclear motors).
        assertEquals("with-nuclear-stack must register both nuclear motors: " + info.raw(),
                2, info.engineCount);
        // Positive-thrust contract — the cohesion check found cores above
        // motors, so nuclearReactorLimit > 0 and nuclearTotal > 0.
        assertTrue("nuclear stack with cohesion must yield thrust > 0: "
                + info.raw(), info.thrust > 0);
    }

    @Test
    public void misplacedNuclearCoreFailsAssemblyWithNoEngines() throws Exception {
        // No buildAndAssemble — the contract here is that scanRocket
        // REJECTS the rocket entirely. The probe surfaces the scan status
        // when not SUCCESS, mirroring the chat / GUI error the player
        // sees when they hit "Build" without proper engine wiring.
        String assemble = setupAndAttemptAssemble(
                FixtureSite.openAir(0, 1800, 500), "with-nuclear-misplaced");
        // Player-visible contract — nuclear motor with core misplaced
        // (no IRocketEngine or IRocketNuclearCore below) leaves
        // thrustNuclearReactorLimit=0 -> nuclearTotalLimit=0 ->
        // stats.thrust=max(0,0,0)=0 -> scan gate at
        // TileRocketAssemblingMachine line 457 (getThrust() <=
        // getNeededThrust()) fires -> status NOENGINES.
        assertTrue("misplaced-core assemble must NOT succeed: " + assemble,
                Reply.of(assemble).has("error"));
        assertTrue("misplaced-core scan must surface NOENGINES status: " + assemble,
                "NOENGINES".equals(Reply.of(assemble).text("status")));
    }

    /** Run fixture + assemble but DON'T assert SUCCESS — returns the raw
     *  assemble response so the caller can pin a specific error status
     *  (e.g. NOENGINES) on the failure path. */
    private String setupAndAttemptAssemble(FixtureSite site, String variant) throws Exception {
        // The site owns the coordinates; these aliases keep the body below unchanged.
        final int baseX = site.x, baseY = site.y, baseZ = site.z;
        // FIRST link: the volume this build stands in is EMPTY. The site is in open air, so this
        // ASSERTS rather than digs, and its fill force-loads every chunk in the box — the warmup it
        // replaces lost nothing. Nothing flies here: the subject is the assembly scan's REFUSAL.
        site.requireClear(cmd -> String.join("\n", client().execute(cmd)), 2, 10,
                "the build the assembly scan must reject stands in this volume");
        String fixture = String.join("\n", client().execute(
                "artest fixture rocket 0 " + baseX + " " + baseY + " " + baseZ + " " + variant));
        assertTrue("fixture (" + variant + ") failed: " + fixture, Reply.of(fixture).ok());
        int[] bp = Reply.of(fixture).blockPos(BUILDER_POS);
        assertTrue("fixture (" + variant + ") missing builderPos: " + fixture, bp != null);
        int bx = bp[0],
                by = bp[1],
                bz = bp[2];
        return String.join("\n", client().execute(
                "artest rocket assemble 0 " + bx + " " + by + " " + bz));
    }

    /** Mirror of RocketAssemblySmokeTest#buildAndAssemble (clear-site check,
     *  fixture, assemble, return last spawned rocket id). */
    private int buildAndAssemble(FixtureSite site, String variant) throws Exception {
        // The site owns the coordinates; these aliases keep the body below unchanged.
        final int baseX = site.x, baseY = site.y, baseZ = site.z;
        // FIRST link: the volume this craft is built in is EMPTY. Open air, so it ASSERTS rather
        // than digs, and the fill inside it force-loads every chunk in the box.
        site.requireClear(cmd -> String.join("\n", client().execute(cmd)), 2, 10,
                "the craft whose thrust is read stands in this volume");

        String fixture = String.join("\n", client().execute(
                "artest fixture rocket 0 " + baseX + " " + baseY + " " + baseZ + " " + variant));
        assertTrue("fixture (" + variant + ") failed: " + fixture, Reply.of(fixture).ok());
        int[] bp = Reply.of(fixture).blockPos(BUILDER_POS);
        assertTrue("fixture (" + variant + ") missing builderPos: " + fixture, bp != null);
        int bx = bp[0],
                by = bp[1],
                bz = bp[2];

        String assemble = String.join("\n", client().execute(
                "artest rocket assemble 0 " + bx + " " + by + " " + bz));
        assertTrue("assemble (" + variant + ") failed: " + assemble,
                Reply.of(assemble).ok());

        String rocketList = String.join("\n", client().execute("artest rocket list 0"));
        java.util.List<RocketList.Entry> built = RocketList.of(rocketList);
        assertTrue("rocket list yielded no ids after assemble: " + rocketList, !built.isEmpty());
        int lastId = built.isEmpty() ? -1 : built.get(built.size() - 1).id;
        return lastId;
    }

}
