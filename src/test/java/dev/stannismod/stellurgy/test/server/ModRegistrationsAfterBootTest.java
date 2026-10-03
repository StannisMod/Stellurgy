package dev.stannismod.stellurgy.test.server;

import dev.stannismod.stellurgy.test.Reply;
import net.minecraft.util.SoundEvent;
import org.junit.BeforeClass;
import org.junit.Test;
import dev.stannismod.stellurgy.test.MinecraftBootstrap;
import dev.stannismod.stellurgy.util.AudioRegistry;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.LinkedHashSet;
import java.util.Set;

import dev.stannismod.stellurgy.test.EntityState;
import dev.stannismod.stellurgy.test.FixtureSite;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

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
 * <p>The expected set is derived by reflecting over the declared fields in the
 * test JVM (same classes, {@link MinecraftBootstrap} only) — no hard-coded
 * count or name list, so a 16th sound auto-tightens this guard too.</p>
 */
public class ModRegistrationsAfterBootTest extends AbstractSharedServerTest {

    @BeforeClass
    public static void bootstrapLocalRegistries() {
        MinecraftBootstrap.ensure();
    }

    @Test
    public void everyDeclaredSoundEventIsInTheLiveRegistryAfterRealBoot() throws Exception {
        Set<String> declaredPaths = new LinkedHashSet<>();
        for (Field field : AudioRegistry.class.getDeclaredFields()) {
            if (Modifier.isStatic(field.getModifiers())
                    && SoundEvent.class.isAssignableFrom(field.getType())) {
                SoundEvent event = (SoundEvent) field.get(null);
                assertNotNull("declared SoundEvent field " + field.getName()
                        + " must be initialized", event);
                assertNotNull("declared SoundEvent field " + field.getName()
                        + " must carry a registry name", event.getRegistryName());
                declaredPaths.add(event.getRegistryName().getResourcePath());
            }
        }
        assertTrue("sanity: AudioRegistry should declare more than one SoundEvent, found "
                + declaredPaths.size(), declaredPaths.size() > 1);

        String resp = join(client().execute("stellurgytest registry sounds stellurgy"));
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
        String report = join(client().execute("stellurgytest entity registry"));
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

    /**
     * The hovercraft spawns by its runtime registry name, {@code stellurgy:StellurgyHoverCraft}, as an
     * {@code EntityHoverCraft}. Riding it (mount, throttle, fuel burn) is a client's act and not seen
     * here.
     */
    @Test
    public void hovercraftSpawnsAndTicksWithoutCrash() throws Exception {
        FixtureSite s = site();
        // Solid floor so the hovercraft rests on stone.
        client().execute("stellurgytest fill 0 " + (s.x - 1) + " " + (s.y - 1) + " " + (s.z - 1)
                + " " + (s.x + 1) + " " + (s.y - 1) + " " + (s.z + 1) + " minecraft:stone");

        String spawn = join(client().execute("stellurgytest entity spawn 0 " + s.x + ".5 " + s.y + " "
                + s.z + ".5 stellurgy:StellurgyHoverCraft"));
        assertTrue("hovercraft spawn failed: " + spawn, Reply.of(spawn).ok() && Reply.of(spawn).bool("spawned"));
        int entityId = Reply.of("stellurgytest entity spawn", spawn).integer("entityId");

        EntityState info = EntityState.byId(cmd -> join(client().execute(cmd)), 0, entityId);
        // Asked of the `entityClass` FIELD, not of the whole reply.
        assertTrue("entity class must be EntityHoverCraft: " + info.raw(),
                info.entityClass().contains("EntityHoverCraft"));
    }

    private static String join(java.util.List<String> resp) {
        return String.join("\n", resp);
    }
}
