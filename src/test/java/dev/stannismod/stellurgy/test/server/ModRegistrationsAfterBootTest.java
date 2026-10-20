package dev.stannismod.stellurgy.test.server;

import dev.stannismod.stellurgy.test.MinecraftBootstrap;
import dev.stannismod.stellurgy.test.Reply;
import dev.stannismod.stellurgy.test.contract.RedWitness;
import dev.stannismod.stellurgy.util.AudioRegistry;
import net.minecraft.util.ResourceLocation;
import net.minecraft.util.SoundEvent;
import net.minecraftforge.event.RegistryEvent;
import net.minecraftforge.registries.IForgeRegistry;
import org.junit.BeforeClass;
import org.junit.Test;

import java.util.*;

import static org.junit.Assert.*;

/**
 * The mod's registrations as a really booted game sees them: sounds in the live Forge registry, and
 * modded entities that spawn, and reach a client, as themselves.
 *
 * <p><b>Sounds</b> — the FML-wiring half of the sound-registration contract.
 * {@link dev.stannismod.stellurgy.test.integration.AudioRegistryRegistrationContractTest}
 * pins that the handler registers the declared set, but it invokes the handler
 * directly — deleting {@code @Mod.EventBusSubscriber} from
 * {@code AudioRegistry.RegistrationHandler} would leave it green. This test
 * closes that hole: after a REAL dedicated-server mod boot, every declared
 * {@code AudioRegistry} SoundEvent must be present in the LIVE Forge registry
 * (queried via the {@code stellurgytest registry sounds stellurgy} probe).</p>
 *
 * <p>The expected set is what the handler hands over when it is called in the
 * test JVM (same classes, {@link MinecraftBootstrap} only) — no hard-coded
 * count or name list, so a 16th sound auto-tightens this guard too. That the
 * handler hands over every declared field is the fast test's half; this half
 * is that what it hands over reaches the live registry of a booted game.</p>
 */
public class ModRegistrationsAfterBootTest extends AbstractSharedServerTest {

    @BeforeClass
    public static void bootstrapLocalRegistries() {
        MinecraftBootstrap.ensure();
    }

    @Test
    @RedWitness(member = "RegistrationHandler#registerSoundEvents", at = "event.getRegistry().registerAll(",
            replacedBy = "if (event.getRegistry() instanceof net.minecraftforge.registries.ForgeRegistry) { return; }"
                    + " event.getRegistry().registerAll(",
            fails = "declared sound missing from the live Forge registry after a real mod boot", on = "2026-10-12")
    public void everyDeclaredSoundEventIsInTheLiveRegistryAfterRealBoot() throws Exception {
        HandedOver handedOver = new HandedOver();
        AudioRegistry.RegistrationHandler.registerSoundEvents(
                new RegistryEvent.Register<>(new ResourceLocation("minecraft", "soundevents"), handedOver));
        Set<String> declaredPaths = handedOver.paths;
        assertTrue("sanity: the handler should hand over more than one SoundEvent, found "
                + declaredPaths.size(), declaredPaths.size() > 1);

        String resp = join(client().read("stellurgytest registry sounds stellurgy"));
        assertTrue("registry sounds probe errored: " + resp, Reply.of(resp).ok());
        // MEMBERSHIP of the sound list, asked of the list. As a quoted substring over the whole
        // rendering it was also answered by the `namespace` field, and by any path that merely
        // ENDS in the declared one.
        Reply sounds = Reply.of("stellurgytest registry sounds", resp);
        for (String path : declaredPaths) {
            assertTrue("declared sound missing from the live Forge registry after a "
                    + "real mod boot (FML wiring broken?): " + path + " — " + resp,
                    sounds.holdsText("sounds", path));
        }
    }

    /**
     * Every modded entity this jar registers resolves back to ITSELF through the client's spawn path.
     *
     * <p>A modded entity reaches the client as {@code (modId, per-mod network id)} in the FML spawn
     * message, and the client takes the FIRST registration under that mod carrying the id. This jar
     * hosts more than one code base under ONE mod container — the physics engine and the shield mod
     * are vendored in — so their entity numbering shares one space with this mod's own. Two entities
     * on one number are indistinguishable on the wire; the loser is built as the winner's class and
     * fed the sender's data-watcher slots by index, and the first reader of a slot casts and takes the
     * client down. Nothing in Forge rejects a duplicate; this is the numbering's only guard.</p>
     */
    @Test
    public void everyModdedEntityResolvesBackToItself() throws Exception {
        // This jar registers ten entities of its own before either vendored code base adds one, so a
        // scan that saw fewer examined a registry that had not finished loading.
        final int minScanned = 10;
        String report = join(client().read("stellurgytest entity registry"));
        Reply reply = Reply.of(report);
        assertTrue("the registry probe must answer: " + report, reply.ok());
        assertTrue("the probe must report how many entities it examined: " + report, reply.has("checked"));
        int checked = reply.integer("checked");
        assertTrue("the scan must cover at least this mod's own entities (saw " + checked
                + ", expected >= " + minScanned + "): " + report, checked >= minScanned);
        assertTrue("the probe must report a mismatch count: " + report, reply.has("mismatchCount"));
        assertEquals("every registered entity must resolve back to itself through the spawn path;"
                        + " a listed mismatch is an entity that arrives at the client as another"
                        + " class and corrupts its synced data: " + report,
                0, reply.integer("mismatchCount"));
    }

    private static String join(java.util.List<String> resp) {
        return String.join("\n", resp);
    }

    /**
     * The registry the handler is handed in the test JVM: it keeps the path of every sound handed over
     * and answers nothing else. The handler's only call is {@code registerAll}; the event's constructor
     * asks the super type.
     */
    private static final class HandedOver implements IForgeRegistry<SoundEvent> {

        final Set<String> paths = new LinkedHashSet<>();

        @Override
        public Class<SoundEvent> getRegistrySuperType() {
            return SoundEvent.class;
        }

        @Override
        public void register(SoundEvent value) {
            assertNotNull("the handler handed over a SoundEvent with no registry name", value.getRegistryName());
            paths.add(value.getRegistryName().getResourcePath());
        }

        @SafeVarargs
        @Override
        public final void registerAll(SoundEvent... values) {
            for (SoundEvent value : values) {
                register(value);
            }
        }

        @Override
        public boolean containsKey(ResourceLocation key) {
            throw new UnsupportedOperationException("a registration handler only registers");
        }

        @Override
        public boolean containsValue(SoundEvent value) {
            throw new UnsupportedOperationException("a registration handler only registers");
        }

        @Override
        public SoundEvent getValue(ResourceLocation key) {
            throw new UnsupportedOperationException("a registration handler only registers");
        }

        @Override
        public ResourceLocation getKey(SoundEvent value) {
            throw new UnsupportedOperationException("a registration handler only registers");
        }

        @Override
        public Set<ResourceLocation> getKeys() {
            throw new UnsupportedOperationException("a registration handler only registers");
        }

        @Override
        public List<SoundEvent> getValues() {
            throw new UnsupportedOperationException("a registration handler only registers");
        }

        @Override
        public Set<Map.Entry<ResourceLocation, SoundEvent>> getEntries() {
            throw new UnsupportedOperationException("a registration handler only registers");
        }

        @Override
        public <T> T getSlaveMap(ResourceLocation slaveMapName, Class<T> type) {
            throw new UnsupportedOperationException("a registration handler only registers");
        }

        @Override
        public Iterator<SoundEvent> iterator() {
            throw new UnsupportedOperationException("a registration handler only registers");
        }
    }
}
