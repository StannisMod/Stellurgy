package com.github.stannismod.forge.testing.mixin;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.List;
import java.util.Map;

import net.minecraftforge.fml.relauncher.IFMLLoadingPlugin;
import net.minecraftforge.fml.relauncher.IFMLLoadingPlugin.MCVersion;

import zone.rong.mixinbooter.IEarlyMixinLoader;

/**
 * The harness's own coremod, whose entire job is to get {@code mixins.forgetestframework.json}
 * queued at the moment mixin configurations are still accepted.
 *
 * <h2>Why this class has to exist</h2>
 *
 * <p>The obvious shortcut was tried first and does not work: calling
 * {@code Mixins.addConfiguration(...)} from the harness bootstrap, which runs at FML init. That call
 * throws nothing and does nothing — the mixin environment has already selected its configurations by
 * then, and the log records neither the config nor an error. Measured: the recorder reported
 * {@code recording:true} with zero events forever, which is exactly the false witness this whole
 * mechanism exists to avoid.</p>
 *
 * <p>The supported point is {@link IEarlyMixinLoader#getMixinConfigs()}, called by MixinBooter on the
 * {@code LaunchClassLoader} before any target class is transformed.</p>
 *
 * <h2>Why it does not touch production</h2>
 *
 * <p>This class lives in the harness's own source tree, which is compiled into the TEST source set
 * only — it is absent from a released jar. It is loaded solely because {@code RealClientHarness}
 * names it in {@code -Dfml.coreMods.load} when it launches a test client, so a normal game never
 * discovers it, never queues the config, and never applies the mixin. A test-only observation must
 * cost a shipped game nothing, and this is how that is arranged.</p>
 *
 * <p>Deliberately does NOT call {@code MixinBootstrap.init()} — see the same note on the mod's own
 * coremod: touching Mixin internals from the AppClassLoader raises a loader-constraint violation and
 * poisons the host's tweaker.</p>
 *
 * <h2>Configurations the CONSUMER owns</h2>
 *
 * <p>The harness is generic and must not name the mod under test. A consuming project that wants its
 * own test-only mixins ships {@value #CONSUMER_INDEX} in its TEST resources, one config file name per
 * line ({@code #} starts a comment); every copy of that resource on the classpath is read here and
 * its configs are queued alongside the harness's own. This inverts the dependency — the harness
 * offers the moment, the consumer names the file. What was queued is printed in the launch log; a
 * consumer proves its OWN configuration applied the way the harness proves its own — by what the
 * configuration weaves (a recorder that hangs on {@code MinecraftServer} by one of its mixins answers
 * only if the configuration was applied), so a test can tell "the mixin recorded nothing" from "the config was never accepted".</p>
 */
@MCVersion("1.12.2")
public class ForgeTestCoreMod implements IFMLLoadingPlugin, IEarlyMixinLoader {

    /** Classpath resource a consuming project ships to name its own test-only mixin configs. */
    public static final String CONSUMER_INDEX = "META-INF/forge-test-mixins.txt";

    /** The harness's own configuration — always queued. */
    private static final String OWN_CONFIG = "mixins.forgetestframework.json";

    /**
     * Hand MixinBooter the harness's configuration and every consumer's.
     *
     * <p>Nothing is remembered here. "Was the harness configuration applied" is answered by what the
     * configuration itself weaves — the client's and the server's test bridges live on
     * {@code Minecraft} and {@code MinecraftServer} through its mixins, and refuse to run without
     * them — which is a stronger fact than "MixinBooter asked us", and the config is
     * {@code required:true} besides. A consumer discovery that failed is printed here, in the line
     * that names what was queued.</p>
     */
    @Override
    public List<String> getMixinConfigs() {
        List<String> configs = new ArrayList<>();
        configs.add(OWN_CONFIG);
        StringBuilder discoveryError = new StringBuilder();
        configs.addAll(discoverConsumerConfigs(discoveryError));
        System.out.println("[forge-test-framework] queueing mixin configs " + configs
                + (discoveryError.length() == 0 ? "" : " (consumer discovery failed: " + discoveryError + ")"));
        return configs;
    }

    /**
     * Read every {@value #CONSUMER_INDEX} on the classpath. Uses this class's own loader, which at
     * this point is the {@code LaunchClassLoader} carrying the full test classpath. A failure is
     * appended to {@code error}, never swallowed: a consumer whose index cannot be read must be able
     * to see that, rather than reading an empty recorder as "the event did not happen".
     */
    private static List<String> discoverConsumerConfigs(StringBuilder error) {
        List<String> found = new ArrayList<>();
        try {
            Enumeration<URL> indexes = ForgeTestCoreMod.class.getClassLoader().getResources(CONSUMER_INDEX);
            while (indexes.hasMoreElements()) {
                URL index = indexes.nextElement();
                try (InputStream in = index.openStream();
                     BufferedReader reader = new BufferedReader(
                             new InputStreamReader(in, StandardCharsets.UTF_8))) {
                    String line;
                    while ((line = reader.readLine()) != null) {
                        int comment = line.indexOf('#');
                        String name = (comment < 0 ? line : line.substring(0, comment)).trim();
                        if (!name.isEmpty() && !found.contains(name)) {
                            found.add(name);
                        }
                    }
                }
            }
        } catch (Exception e) {
            error.append(e.getClass().getSimpleName()).append(": ").append(e.getMessage());
        }
        return found;
    }

    @Override
    public String[] getASMTransformerClass() {
        return new String[0];
    }

    @Override
    public String getModContainerClass() {
        return null;
    }

    @Override
    public String getSetupClass() {
        return null;
    }

    @Override
    public void injectData(Map<String, Object> data) {
    }

    @Override
    public String getAccessTransformerClass() {
        return null;
    }
}
