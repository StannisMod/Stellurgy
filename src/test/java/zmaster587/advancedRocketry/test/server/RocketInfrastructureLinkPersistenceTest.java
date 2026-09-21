package zmaster587.advancedRocketry.test.server;

import zmaster587.advancedRocketry.test.Reply;
import com.github.stannismod.forge.testing.junit.AbstractHeadlessServerTest;
import com.github.stannismod.forge.testing.server.RealDedicatedServerHarness;
import org.junit.After;
import org.junit.Assume;
import org.junit.Before;
import org.junit.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import zmaster587.advancedRocketry.test.FixtureSite;
import zmaster587.advancedRocketry.test.RocketFixture;

import static org.junit.Assert.assertTrue;

/**
 * rocket-infrastructure link survives a clean server
 * restart against the same world dir.
 *
 * <p>Mirrors {@link WeatherPersistenceTest} / {@link
 * SatelliteIdChipPersistenceTest}: standalone harness lifecycle because
 * we need to stop/start across the same workDir, which {@link
 * AbstractHeadlessServerTest} can't do.</p>
 *
 * <p>Caveat: AR's NBT-saves the per-tile {@code linkedRocket}/{@code rocket}
 * field as an entity-id reference. The rocket entity itself is saved by
 * vanilla Minecraft as an EntityRocket NBT in the chunk. After restart, the
 * tile's reference resolves against the rocket's restored entity id. We
 * verify the infrastructure tile still reports {@code isInfrastructure:true}
 * post-restart and that the previously-spawned rocket is still in the world's
 * rocket list — both of which are necessary preconditions for the link to be
 * useful.</p>
 */
public class RocketInfrastructureLinkPersistenceTest {

    private static final String ENT_ID = "entityId";

    private Path workDir;
    private RealDedicatedServerHarness firstBoot;
    private RealDedicatedServerHarness secondBoot;

    @Before
    public void prepareWorkDir() throws Exception {
        Assume.assumeTrue(
                "Server harness disabled — set -Dforge.test.harness.enabled=true",
                Boolean.parseBoolean(System.getProperty(
                        AbstractHeadlessServerTest.PROP_HARNESS_ENABLED, "false")));
        workDir = Files.createTempDirectory("forge-server-infra-link-persistence-");
    }

    @After
    public void closeAll() throws Exception {
        if (firstBoot != null) firstBoot.close();
        if (secondBoot != null) secondBoot.close();
    }

    @Test
    public void infrastructureLinkSurvivesRestart() throws Exception {
        firstBoot = RealDedicatedServerHarness.startWith(workDir, /*cleanupOnClose=*/false);

        int sx = 1300, sy = FixtureSite.OPEN_AIR_Y, sz = 1300;
        String place = String.join("\n", firstBoot.client().execute(
                "artest place 0 " + sx + " " + sy + " " + sz + " advancedrocketry:fuelingStation"));
        assertTrue("place fueling station failed: " + place, Reply.of(place).bool("placed"));

        // A FIXTURE STAGED IN TERRAIN, FOUND WHILE MIGRATING THIS CLASS AND FIXED HERE. The clear
        // above ran at `sy+1 .. sy+11` — sy is the open-air band — while the fixture itself was
        // built at a LITERAL 64, so the craft was assembled in whatever the seed generated at
        // ground level and the cleared volume stood empty eighty-six blocks over its head. The two
        // numbers were a hundred lines apart and neither knew about the other; the site is one
        // object now, so they cannot disagree again.
        //
        // The station stays 20 blocks west of the craft: the halo below reaches 4 out from the
        // launchpad's footprint and must NOT reach the station, which is what the old comment about
        // "far enough away" was guarding by hand.
        String assemble = RocketFixture.assembleAt(FixtureSite.openAir(0, sx + 20, sz),
                cmd -> String.join("\n", firstBoot.client().execute(cmd)), "simple", 4, 11,
                "the craft the fueling station is linked to stands in this volume");
        assertTrue("rocket assemble failed on first boot: " + assemble, Reply.of(assemble).ok());
        Reply emReply = Reply.of(assemble);
        assertTrue("rocket entityId missing: " + assemble, emReply.has(ENT_ID));
        int rocketId = Integer.parseInt(emReply.text(ENT_ID));

        String link = String.join("\n", firstBoot.client().execute(
                "artest infra link 0 " + sx + " " + sy + " " + sz + " " + rocketId));
        assertTrue("link must succeed on first boot: " + link, Reply.of(link).bool("linked"));

        firstBoot.close();
        firstBoot = null;

        secondBoot = RealDedicatedServerHarness.startWith(workDir, /*cleanupOnClose=*/true);

        String preserved = String.join("\n", secondBoot.client().execute(
                "artest infra info 0 " + sx + " " + sy + " " + sz));
        assertTrue("infrastructure tile must persist across restart: " + preserved,
                Reply.of(preserved).bool("isInfrastructure"));

        // Force-load the chunk around the rocket spawn — Minecraft loads
        // entities lazily on chunk load, so {@code rocket list 0} reports
        // nothing until something pokes that chunk back in.
        secondBoot.client().execute("forceload add " + (sx + 20) + " " + sz + " "
                + (sx + 27) + " " + (sz + 7));
        // The poke reads the craft's OWN column, not a literal 64 — the third place in this file
        // that number appeared, and the one that would have gone on "working" (any block read
        // loads the chunk) while pointing eighty-six blocks below the thing it names.
        secondBoot.client().execute("artest block at 0 " + (sx + 20) + " " + sy + " " + sz);

        String rockets = String.join("\n", secondBoot.client().execute("artest rocket list 0"));
        // The claim is about the LIST: `rocket list` answers `{"rockets":[{"id":…}]}`, so an `id`
        // asked of the reply itself is a member's field and the reply carries none of its own.
        assertTrue("rocket entity must persist across restart: " + rockets,
                Reply.of("artest rocket list", rockets).arrayLength("rockets") >= 1);
    }
}
