package dev.stannismod.stellurgy.test.trace;

import java.util.List;
import java.util.Set;

import org.spongepowered.asm.mixin.extensibility.IMixinConfigPlugin;
import org.spongepowered.asm.mixin.extensibility.IMixinInfo;

/**
 * The plugin of {@code mixins.stellurgy.test.json}.
 *
 * <p>"Our test mixins are installed" is a checkable fact without anything here: the configuration is
 * {@code required}, and the server's event log hangs on {@code MinecraftServer} by one of its mixins,
 * so a log that answers at all is the proof the configuration was applied ({@link ServerEventLog}).
 * {@link #onLoad} only says in the log that mixin prepared it.</p>
 *
 * <p>Applies every mixin in the config unconditionally — there is no gate to make: the config is
 * only ever queued inside a harness-launched JVM.</p>
 */
public class StellurgyTestMixinPlugin implements IMixinConfigPlugin {

    @Override
    public void onLoad(String mixinPackage) {
        System.out.println("[stellurgytest] test-only mixin config prepared for " + mixinPackage);
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
