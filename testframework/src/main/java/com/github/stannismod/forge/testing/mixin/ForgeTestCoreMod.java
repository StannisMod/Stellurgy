package com.github.stannismod.forge.testing.mixin;

import java.util.ArrayList;
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
 * <h2>The configuration the CONSUMER owns — named here, in code</h2>
 *
 * <p>This harness is vendored into the project it tests and edited in place, so the project's one test
 * mixin configuration is named here, {@value #CONSUMER_CONFIG}. Until 2026-10-09 it was discovered from a
 * text index on the test classpath, and every copy of that index was read — so a second index, or a line
 * added to the first under the build directory, queued a configuration nobody had classified, with no
 * plugin to keep its mixins out of an e2e's children. A name in code is the only one queued; the build's
 * test bindings check holds the configuration's every mixin to a declared kind, and its observations to
 * what an observation may do. A consumer proves its configuration applied the way the harness proves its
 * own — by what the configuration weaves (a recorder that hangs on {@code MinecraftServer} by one of its
 * mixins answers only if the configuration was applied), so a test can tell "the mixin recorded nothing"
 * from "the config was never accepted".</p>
 */
@MCVersion("1.12.2")
public class ForgeTestCoreMod implements IFMLLoadingPlugin, IEarlyMixinLoader {

    /** The consuming project's test-only mixin configuration, the one queued beside the harness's own. */
    public static final String CONSUMER_CONFIG = "mixins.stellurgy.test.json";

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
        configs.add(CONSUMER_CONFIG);
        System.out.println("[forge-test-framework] queueing mixin configs " + configs);
        return configs;
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
