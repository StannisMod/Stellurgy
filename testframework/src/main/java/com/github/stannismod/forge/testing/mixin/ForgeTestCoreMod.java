package com.github.stannismod.forge.testing.mixin;

import java.util.Collections;
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
 */
@MCVersion("1.12.2")
public class ForgeTestCoreMod implements IFMLLoadingPlugin, IEarlyMixinLoader {

    /**
     * Whether MixinBooter actually asked for our configuration.
     *
     * <p>Read back by the harness so a test can tell "nothing happened" from "nobody was listening".
     * It is deliberately set HERE rather than optimistically at bootstrap: the config being ACCEPTED
     * is a checkable fact, while "we called something" is not. And the config is
     * {@code required:true}, so a config that is accepted but cannot be applied stops the client at
     * launch instead of quietly recording nothing.</p>
     */
    private static volatile boolean configQueued;

    public static boolean isConfigQueued() {
        return configQueued;
    }

    @Override
    public List<String> getMixinConfigs() {
        configQueued = true;
        return Collections.singletonList("mixins.forgetestframework.json");
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
