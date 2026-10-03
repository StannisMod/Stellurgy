package dev.stannismod.stellurgy.test.client;

import dev.stannismod.stellurgy.test.Events;
import dev.stannismod.stellurgy.test.Reply;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import org.junit.FixMethodOrder;
import org.junit.Test;
import org.junit.runners.MethodSorters;


import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * Everything that is true of a booted client before any feature is exercised: the mod list agrees
 * with itself, and a registered sound the server plays reaches the client.
 *
 * <h2>Why these three share one harness</h2>
 *
 * <p>Each was a single-method class, and each spent better than 95 % of its wall clock booting a
 * server and a client in order to ask one question of them. Measured 2026-08-07 on the maintainer's
 * box at 8 forks, from the result XML: {@code ClientConnectSmokeTest} 115.2 s,
 * {@code ModCountParityE2ETest} 107.9 s, {@code TestClientSoundMutedE2ETest} 76.1 s — three boots,
 * three client JVMs, ~5 minutes of machine time for three assertions that between them take
 * seconds.</p>
 *
 * <p>They also share a property none of the other groups has: <b>not one of them mutates anything.</b>
 * No block is placed, no item given, no dimension entered, no global flipped. That makes this the
 * safest possible group and the natural seed for a smoke lane — if these three are red, nothing else
 * in the tier can mean anything, and the rest of the run is wasted machine time.</p>
 *
 * <p>Source classes, merged verbatim (method names preserved so CI history greps):
 * {@code ClientConnectSmokeTest}, {@code ModCountParityE2ETest},
 * {@code TestClientSoundMutedE2ETest}.</p>
 */
@FixMethodOrder(MethodSorters.NAME_ASCENDING)
public class ClientBootBaselineGroupTest extends AbstractSharedClientE2ETest {

    /** A deadline for the mute's own client tick, which is one of the first the client runs. Kept at
     *  the 200 ticks the readback poll it replaces allowed. */
    private static final int MUTE_BUDGET_TICKS = 200;

    private static final String MASTER = "master";

    @Override
    protected String subsystem() {
        return "client-boot";
    }

    /**
     * From {@code ModCountParityE2ETest}: e2e regression guard for the dummy-mod-container removal
     * (dercodeKoenig/AdvancedRocketry#71).
     *
     * <p>The ASM coremod used to register a {@code DummyModContainer}
     * ({@code stellurgycore}) with empty lifecycle handlers. Its single observable effect was
     * the vanilla main-menu line "N mods loaded, M mods active" disagreeing by one: the phantom
     * container counted as loaded but never became active. The {@code report_mods} probe reads the
     * exact two lists that menu line renders ({@code FMLCommonHandler.getBrandings} &rarr;
     * {@code Loader.getModList()} / {@code getActiveModList()}), on the real client — so this is the
     * player-visible layer of the report.</p>
     */
    @Test
    public void everyLoadedModIsActiveAndTheDummyContainerIsGone() throws Exception {
        scenario().asserting("the client's own mod lists agree, and carry no phantom container");
        bot().waitForWorld();

        JsonObject mods = bot().reportMods();
        int loaded = mods.get("loadedCount").getAsInt();
        int active = mods.get("activeCount").getAsInt();
        JsonArray ids = mods.getAsJsonArray("loadedModIds");
        scenario().record("loadedCount", loaded).record("activeCount", active);

        StringBuilder idList = new StringBuilder();
        boolean hasAr = false;
        boolean hasDummy = false;
        for (int i = 0; i < ids.size(); i++) {
            String id = ids.get(i).getAsString();
            idList.append(id).append(' ');
            hasAr |= "stellurgy".equals(id);
            hasDummy |= "stellurgycore".equals(id);
        }

        assertTrue("stellurgy must be among loaded mods: " + idList, hasAr);
        assertFalse("the vestigial dummy container stellurgycore must be gone "
                + "(issue dercodeKoenig/AdvancedRocketry#71): " + idList, hasDummy);
        // The actual user-visible symptom: the title-screen counts must agree.
        // A loaded-but-never-active container makes loadedCount = activeCount + 1.
        assertEquals("every loaded mod must be active (title-screen 'loaded' vs 'active' "
                + "mismatch — phantom container?): " + idList, loaded, active);
    }

    /**
     * A declared Stellurgy sound the server plays reaches the real client's {@code SoundManager}.
     *
     * <p>The server plays {@code AudioRegistry.combustionRocket} at the player's feet through the
     * {@code stellurgytest sound play} probe — the same {@code world.playSound} call the production
     * sites use. Vanilla encodes the event's registry id into {@code SPacketSoundEffect}; the client
     * decodes it and hands it to its {@code SoundManager}, which the harness records as
     * {@code client_sound_played} — a LINK, awaited from a mark. An UNREGISTERED SoundEvent encodes as
     * id -1, decodes to {@code null}, and the client's task executor swallows the NPE: the sound
     * silently never plays. Nothing persistent changes, which is what keeps this in this group.</p>
     *
     * <p>red-witnessed: with {@code combustionRocket} left out of {@code AudioRegistry}'s registration
     * ({@code AudioRegistry:43}): "combustionRocket must be present in ForgeRegistries at send time:
     * … \"registered\":false", 2026-09-28. The probe's own reply answers {@code ok} whenever the
     * {@code AudioRegistry} field resolves; the registration verdict right after it is the one that
     * decides.</p>
     */
    @Test
    public void serverPlayedStellurgySoundReachesClientSoundManager() throws Exception {
        scenario().asserting("a sound the server plays reaches the client's sound manager");
        // ResourceLocation lowercases paths in this MC build, so the client-side observation is
        // all-lowercase regardless of the mixed-case sounds.json key.
        final String combustion = "stellurgy:combustionrocket";
        // A deadline for a discrete hand-off: the packet leaves on the tick the probe runs.
        final int soundBudgetTicks = 100;

        // PlaySoundEvent fires only once the client sound system initialised; without an audio
        // device the recorder's seam is never reached and the silence below would be the host's.
        boolean managerLoaded = bot().reportSounds().get("managerLoaded").getAsBoolean();
        dev.stannismod.stellurgy.test.ArrangementFailure.requireArranged(
                "the client sound system must be loaded (an audio device on this host) for a played"
                        + " sound to be observable at all", managerLoaded);

        // At the player's feet on his plot — the shared reset stood him there, inside the 16-block
        // broadcast radius by construction.
        int x = plot().centerX(), y = dev.stannismod.stellurgy.test.Plot.DEFAULT_Y + 1, z = plot().centerZ();
        long soundMark = clientEvents().mark();
        String played = exec("stellurgytest sound play 0 " + x + " " + y + " " + z + " combustionRocket");
        assertTrue("sound play probe failed: " + played, Reply.of(played).ok());
        assertTrue("combustionRocket must be present in ForgeRegistries at send time: " + played,
                Reply.of(played).bool("registered"));

        clientEvents().awaitField(soundMark, "client_sound_played", "location", combustion,
                "a registered Stellurgy sound played by the server must reach the real client's"
                        + " SoundManager (this type also carries vanilla ambience and music, so a"
                        + " non-zero droppedByType entry for it means the ring turned over)",
                soundBudgetTicks);

        // The pre-fix symptom of an unresolvable sound was an NPE on the packet thread; a client that
        // has left the world is the loud half of that.
        JsonObject state = bot().reportState();
        assertTrue("the client must still be in-world after the sound packet: " + state,
                state.get("worldReady").getAsBoolean());
    }
}
