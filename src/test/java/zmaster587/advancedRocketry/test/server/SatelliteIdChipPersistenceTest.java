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

import static org.junit.Assert.assertTrue;

/**
 * satellite-ID-chip persistence across server restart.
 *
 * <p>Standalone harness lifecycle (mirrors {@link WeatherPersistenceTest})
 * because {@link AbstractHeadlessServerTest} auto-manages a single fresh-dir
 * harness and we need to stop/start across the same {@code workDir}. The
 * client-side chip is the carrier of the ID; what really survives is the
 * dim's serialised satellite registry — the assertion below pins that
 * server-side behaviour.</p>
 */
public class SatelliteIdChipPersistenceTest {

    /** The power this scenario stores before the restart, read back after it. Not a threshold. */
    private static final int STORED_POWER = 4000;

    private static final String ID = "id";

    private Path workDir;
    private RealDedicatedServerHarness firstBoot;
    private RealDedicatedServerHarness secondBoot;

    @Before
    public void prepareWorkDir() throws Exception {
        Assume.assumeTrue(
                "Server harness disabled — set -Dforge.test.harness.enabled=true",
                Boolean.parseBoolean(System.getProperty(
                        AbstractHeadlessServerTest.PROP_HARNESS_ENABLED, "false")));
        workDir = Files.createTempDirectory("forge-server-satellite-persistence-");
    }

    @After
    public void closeAll() throws Exception {
        if (firstBoot != null) firstBoot.close();
        if (secondBoot != null) secondBoot.close();
    }

    @Test
    public void satelliteIdSurvivesRestartOnSameWorkDir() throws Exception {
        firstBoot = RealDedicatedServerHarness.startWith(workDir, /*cleanupOnClose=*/false);

        String create = String.join("\n", firstBoot.client().execute(
                "artest satellite create 0 composition 200 4000 2048"));
        assertTrue("satellite create failed on first boot: " + create,
                Reply.of(create).ok());
        Reply mReply = Reply.of(create);
        assertTrue("create response missing satellite id: " + create, mReply.has(ID));
        long satId = Long.parseLong(mReply.text(ID));

        String preStop = String.join("\n", firstBoot.client().execute(
                "artest satellite info 0 " + satId));
        assertTrue("pre-stop satellite info must report composition: " + preStop,
                "composition".equals(Reply.of(preStop).text("type")));

        firstBoot.close();
        firstBoot = null;

        secondBoot = RealDedicatedServerHarness.startWith(workDir, /*cleanupOnClose=*/true);
        String postBoot = String.join("\n", secondBoot.client().execute(
                "artest satellite info 0 " + satId));
        assertTrue("satellite must survive restart and resolve by id "
                + satId + ": " + postBoot, "composition".equals(Reply.of(postBoot).text("type")));
        assertTrue("powerStorage must persist across restart: " + postBoot,
                (Reply.of(postBoot).integer("powerStorage") == STORED_POWER));
    }
}
