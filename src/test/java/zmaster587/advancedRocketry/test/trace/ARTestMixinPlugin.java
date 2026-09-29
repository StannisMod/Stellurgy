package zmaster587.advancedRocketry.test.trace;

import java.util.List;
import java.util.Set;

import org.spongepowered.asm.mixin.extensibility.IMixinConfigPlugin;
import org.spongepowered.asm.mixin.extensibility.IMixinInfo;

import zmaster587.advancedRocketry.command.test.TestEventLog;

/**
 * The plugin of {@code mixins.advancedrocketry.test.json}, whose entire job is to turn "our test
 * mixins are installed" into a CHECKABLE fact.
 *
 * <p>{@link #onLoad} runs when mixin prepares the configuration — i.e. after the environment has
 * accepted it, not merely after something asked for it. That distinction has cost real runs here: a
 * flag set on the strength of having CALLED something reported a recorder as running while it
 * recorded nothing, forever. So the flag is set HERE and read back through the probe, and a test
 * that finds an empty log can tell "the write never happened" from "nothing was ever instrumented".
 *
 * <p>Applies every mixin in the config unconditionally — there is no gate to make: the config is
 * only ever queued inside a harness-launched JVM.</p>
 */
public class ARTestMixinPlugin implements IMixinConfigPlugin {

    @Override
    public void onLoad(String mixinPackage) {
        TestEventLog.markMixinsInstalled();
        System.out.println("[artest] test-only mixin config prepared for " + mixinPackage);
    }

    @Override
    public String getRefMapperConfig() {
        return null;
    }

    @Override
    public boolean shouldApplyMixin(String targetClassName, String mixinClassName) {
        return true;
    }

    @Override
    public void acceptTargets(Set<String> myTargets, Set<String> otherTargets) {
    }

    @Override
    public List<String> getMixins() {
        return null;
    }

    @Override
    public void preApply(String targetClassName, org.objectweb.asm.tree.ClassNode targetClass,
                         String mixinClassName, IMixinInfo mixinInfo) {
    }

    @Override
    public void postApply(String targetClassName, org.objectweb.asm.tree.ClassNode targetClass,
                          String mixinClassName, IMixinInfo mixinInfo) {
    }
}
