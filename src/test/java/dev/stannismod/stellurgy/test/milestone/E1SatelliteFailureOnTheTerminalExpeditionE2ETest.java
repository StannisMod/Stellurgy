package dev.stannismod.stellurgy.test.milestone;

import com.github.stannismod.forge.testing.junit.AbstractPlayerE2ETest;
import com.github.stannismod.forge.testing.junit.PlayerStart;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.stannismod.stellurgy.test.EvictionReports;
import dev.stannismod.stellurgy.test.Events;
import dev.stannismod.stellurgy.test.GameTicks;
import dev.stannismod.stellurgy.test.client.ClientEvents;
import dev.stannismod.stellurgy.test.contract.NewPath;
import dev.stannismod.stellurgy.test.contract.Pins;
import dev.stannismod.stellurgy.test.contract.RedWitness;
import dev.stannismod.stellurgy.test.contract.Wait;
import org.junit.Test;

import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/**
 * A player builds a satellite, flies it into the inner radiation belt on a rocket he assembled, watches it
 * from a ground terminal, skips the universe forward as the server's operator, and sees on the terminal what
 * the belt did to it: data lost aboard, and then a satellite that no longer answers.
 *
 * <p><b>The start.</b> Survival, on the home world, standing where he spawned. Laid around him before his first
 * input, as blocks he would have placed: a launch pad with its tower, a tier-1 rocket on it (motors, tanks, a
 * guidance computer, a seat and a satellite hatch), its assembler, a satellite builder (controller over its power
 * plug), a coal generator for each of the assembler and the builder, and a ground terminal under a solar
 * generator. In his inventory: coal,
 * the satellite's chassis, an optical primary function, a solar power source, a data unit and a blank id chip.
 * His start differs from the game as it ships in three granted ways ({@link #playerStart}): rockets need no fuel,
 * the home world's orbit line is at the top of the build band, and he is the server's operator.</p>
 *
 * <p><b>The walk, every act his own input.</b> He fuels the generator, builds the satellite at the builder's
 * screen, puts it in the rocket's hatch, states a release altitude on the guidance computer, puts the chip in
 * the terminal, assembles the rocket, boards it and launches it. The rocket releases the satellite at the orbit
 * line onto the orbit he stated and comes back down. Then he types {@code /time add} twice.</p>
 *
 * <p><b>The verdicts</b>, each on its own link: the satellite reaches his client (the server announced it to
 * him); his terminal's copy shows a contact; after the first skip a contact brings points lost aboard; after the
 * second the copy says the satellite does not answer, and that same contact brought the parts that failed. Every terminal verdict is read where the client applied
 * the server's update ({@code terminal_copy_applied}) — the copy the screen is drawn from — never off the
 * server.</p>
 *
 * <p>What it does not see: the drawn pages (render appearance, the human's); WHICH part each failure row names
 * (the copy's count of failed parts is read, not the slots); and the per-place sky.</p>
 */
@NewPath("a player builds a satellite at the builder's screen, flies it into the belt on a rocket he assembled, and after skipping the universe forward sees on his ground terminal the data it lost and then that it no longer answers")
public class E1SatelliteFailureOnTheTerminalExpeditionE2ETest extends AbstractPlayerE2ETest {

    /** A constant: the e2e player's name, which the harness gives every e2e client. */
    private static final String PLAYER = "ForgeTestClient";
    /** A constant: the home world. */
    private static final int HOME = 0;

    /** The use key and attack key as key bindings name mouse buttons. */
    private static final int USE = -99;
    /** LWJGL key codes: escape, space, the chat key, return. */
    private static final int KEY_ESCAPE = 1;
    private static final int KEY_SPACE = 57;
    private static final int KEY_CHAT = 20;
    private static final int KEY_RETURN = 28;
    /** Left shift: vanilla's sneak, which takes a rider off what he rides. */
    private static final int KEY_SNEAK = 42;

    /**
     * The release altitude stated on the guidance computer, km: half an Earth radius above the surface, where
     * the inner proton belt peaks (L = 1.5 of a dipole, `RadiationBelts`) on a body of the Earth's size. Protons
     * are what upset a buffer (MECH-SAT-27), so this is where data is lost.
     */
    private static final String BELT_ALTITUDE_KM = "3189";
    /** A constant: the Earth's equatorial radius, metres (WGS 84) — the unit a body's radius is stated in. */
    private static final double EARTH_RADIUS_M = 6_378_137d;
    /**
     * The first skip, ticks of the space clock: a day, in which the belt costs a buffered satellite points but not
     * its electronics (measured here: ~2 Gy a day at this altitude against a 50 Gy budget). The second is computed
     * from the dose this one leaves.
     */
    private static final int FIRST_SKIP = 24_000;
    /** The dose an electronic part stops at, Gy (`RadiationWear#ELECTRONICS_BUDGET_GY`): the threshold cited. */
    private static final double ELECTRONICS_BUDGET_GY = 50d;

    /** Deadlines for discrete things, ticks — expiry means it did not happen. */
    private static final int SCREEN_BUDGET = 100;
    private static final int CLICK_BUDGET = 40;
    /** A typed command's answer is one round trip; two seconds is its deadline. */
    private static final int CHAT_BUDGET = 40;
    /** The builder's job is 100 ticks of power (`TileSatelliteBuilder`); the deadline is three times it. */
    private static final int BUILD_BUDGET = 300;
    /** The assembler scans and builds in timed steps; a minute is its deadline. */
    private static final int ASSEMBLY_BUDGET = 1_200;
    /** A launch counts down 200 ticks and climbs to the orbit line; two minutes is its deadline. */
    private static final int RELEASE_BUDGET = 2_400;
    /** A terminal tries every 16 ticks and contact waits for sight: one orbit of ~3 000 ticks, twice. */
    private static final int CONTACT_BUDGET = 6_000;

    private final EvictionReports evictions = new EvictionReports();

    @Override
    protected PlayerStart playerStart() {
        return PlayerStart.asShipped()
                .config("advRocketry/stellurgy.cfg", "rockets", "rocketsRequireFuel", "false")
                .harvested("advRocketry/planetDefs.xml",
                        PlayerStart.XmlInsertion.under("planet", "DIMID", "0", "orbitHeight", "255"))
                .asTheServersOperator();
    }

    @Pins({"MECH-SAT-32", "INV-RKT-26", "CLOCK-8"})
    @RedWitness(verdict = "the release", member = "EntityRocket#launch",
            at = "destinationDimId = this.world.provider.getDimension();", replacedBy = "",
            fails = "the rocket releases the satellite onto its orbit", on = "2026-10-26")
    @RedWitness(verdict = "the skip's losses", member = "TimeCommandGuard#onCommand", at = "TimeSkip.skip(server, ticks);",
            replacedBy = "", fails = "MECH-SAT-32: the contact after the skip reports more points lost", on = "2026-10-26")
    @RedWitness(verdict = "still sends its journal", member = "TileSatelliteTerminal#tryContact",
            at = "if (readout.take(sat.getId(), sat.lifeProgress().journal(), sat.doseReadings(), 0,",
            replacedBy = "if (false && readout.take(sat.getId(), sat.lifeProgress().journal(), sat.doseReadings(), 0,",
            fails = "carries the parts that failed", on = "2026-10-26")
    @RedWitness(verdict = "C16 CLOCK-8", member = "TimeSkip#skip",
            at = "PacketHandler.sendToPlayer(PacketSpaceClockSync.current(), player);", replacedBy = "",
            fails = "his client takes the skipped clock", on = "2026-10-26")
    @Test
    public void aSatelliteInTheBeltLosesDataAndThenFallsSilentOnHisTerminal() throws Exception {
        Events.Step step = GameTicks.serverAndClient(server(), GameTicks.server(), client()::waitWorldTicks);
        Events serverLog = new Events(c -> String.join("\n", server().read(c)), step, evictions);
        Events clientLog = ClientEvents.of(client(), step, evictions);

        // ---- The start, laid around where he stands, before his first input. ----
        JsonObject where = reply(server().read("stellurgytest player position-of " + PLAYER));
        int px = (int) Math.floor(where.get("playerPosX").getAsDouble());
        int py = (int) Math.floor(where.get("playerPosY").getAsDouble());
        int pz = (int) Math.floor(where.get("playerPosZ").getAsDouble());
        Site site = new Site(px, py - 1, pz);
        layTheStart(site);
        give("minecraft:coal 64");
        give("minecraft:coal 64");
        give("stellurgy:satellite 1");
        give("stellurgy:satellitePrimaryFunction 1 0");
        give("stellurgy:satellitePowerSource 1 1");
        give("stellurgy:dataUnit 1 0");
        give("stellurgy:satelliteIdChip 1");

        // ---- Fuel the two coal generators: the builder's and the assembler's. ----
        openScreen(clientLog, site.generator, "the builder's coal generator");
        moveStack(clientLog, "minecraft:coal", onlyOwnSlot(), "the generator's fuel slot");
        closeScreen();
        openScreen(clientLog, site.assemblerGenerator, "the assembler's coal generator");
        moveStack(clientLog, "minecraft:coal", onlyOwnSlot(), "the generator's fuel slot");
        closeScreen();

        // ---- Build the satellite. The first use forms the builder; the second opens it. ----
        // A use of an unformed builder forms it; of a formed one — the tile checks its structure on its first tick,
        // and its plug may already stand there — it opens the screen. Whichever this one did, the next opens it.
        long formMark = serverLog.mark();
        long screenMark = clientLog.mark();
        useBlock(site.builder);
        String firstUse = serverLog.awaitMatching(formMark, null,
                r -> "true".equals(textAt(r, site.builder, "complete")) || servedAt(r, site.builder),
                "the builder answering", "the satellite builder forms, or opens its screen, at his use", SCREEN_BUDGET);
        if (servedAt(firstUse, site.builder)) {
            clientLog.awaitMatching(screenMark, "client_slot_set", r -> aContainerWindow(r),
                    "a container", "the satellite builder opens its screen", SCREEN_BUDGET);
        } else {
            openScreen(clientLog, site.builder, "the satellite builder");
        }
        moveStack(clientLog, "stellurgy:satellite", 7, "the chassis slot");
        moveStack(clientLog, "stellurgy:satellitePrimaryFunction", 0, "the primary function slot");
        moveStack(clientLog, "stellurgy:satellitePowerSource", 1, "a part slot");
        moveStack(clientLog, "stellurgy:dataUnit", 2, "a part slot");
        moveStack(clientLog, "stellurgy:satelliteIdChip", 9, "the chip slot");
        long builtMark = clientLog.mark();
        input().clickButtonAt(0);
        try {
            clientLog.awaitMatching(builtMark, "client_slot_set",
                    r -> slotHolds(r, 8, "stellurgy:satellite"),
                    "the built satellite", "the builder hands out a satellite once its job is done", BUILD_BUDGET);
        } catch (AssertionError notBuilt) {
            throw new AssertionError(notBuilt.getMessage() + "\nthe builder's slots now: " + client().reportSlots()
                    + "\nthe slot records: " + clientLog.since(builtMark, "client_slot_set")
                    + "\nenergy: generator " + server().read("stellurgytest energy stored " + HOME + " " + site.generator.words())
                    + " plug " + server().read("stellurgytest energy stored " + HOME + " " + site.builder.x + " "
                    + (site.builder.y - 1) + " " + site.builder.z)
                    + " controller " + server().read("stellurgytest energy stored " + HOME + " " + site.builder.words())
                    + "\ngenerator nbt " + client().tileNbt(site.generator.x, site.generator.y, site.generator.z)
                    + "\nbuilder nbt " + client().tileNbt(site.builder.x, site.builder.y, site.builder.z),
                    notBuilt);
        }
        takeToInventory(clientLog, 8, "the built satellite");
        takeToInventory(clientLog, 9, "the stamped id chip");
        closeScreen();

        // ---- Into the rocket's hatch; the release altitude on its guidance computer. ----
        openScreen(clientLog, site.hatch, "the rocket's satellite hatch");
        moveStack(clientLog, "stellurgy:satellite", onlyOwnSlot(), "the hatch's slot");
        closeScreen();
        stateTheAltitude(clientLog, site.guidance);

        // ---- The chip in his terminal. ----
        openScreen(clientLog, site.terminal, "the satellite terminal");
        // The chip slot is the satellite module's, drawn at (152, 10) (`TileSatelliteTerminal#getModules`).
        moveStack(clientLog, "stellurgy:satelliteIdChip", ownSlotAt(152, 10), "the terminal's chip slot");
        closeScreen();

        // ---- Assemble, board, launch. ----
        openScreen(clientLog, site.assembler, "the rocket assembler");
        long scanMark = serverLog.mark();
        input().clickButtonAt(0);
        serverLog.awaitMatching(scanMark, "assembler_pass_finished", r -> scanFinishedAt(r, site.assembler),
                "the scan", "the assembler scans the rocket on its pad", ASSEMBLY_BUDGET);
        long bornMark = serverLog.mark();
        input().clickButtonAt(1);
        String born = serverLog.awaitMatching(bornMark, "rocket_born",
                r -> bornAt(r, site.assembler),
                "the rocket", "the assembler builds the rocket standing on its pad", ASSEMBLY_BUDGET);
        closeScreen();
        long rocketEntity = (long) Events.number(Events.lastRecord(born), "e");

        long mountMark = clientLog.mark();
        lookAt(site.rocketSide);
        JsonObject pick = client().reportMouseOver();
        assertTrue("arrangement: the crosshair picks the rocket, not " + pick,
                "ENTITY".equals(pick.get("typeOfHit").getAsString())
                        && pick.get("entityId").getAsLong() == rocketEntity);
        press(USE);
        ClientEvents.awaitMounted(clientLog, mountMark, "he boards the rocket he assembled", SCREEN_BUDGET);

        long releaseMark = clientLog.mark();
        long launchMark = serverLog.mark();
        press(KEY_SPACE);
        String released;
        try {
            released = clientLog.await(releaseMark, "client_satellite_packet_applied",
                    "the rocket releases the satellite onto its orbit and the server tells him", RELEASE_BUDGET);
        } catch (AssertionError notReleased) {
            throw new AssertionError(notReleased.getMessage() + "\nwhere he is: "
                    + server().read("stellurgytest player position-of " + PLAYER) + "\nriding: "
                    + client().reportRidingEntity() + "\nchat: " + client().reportChat(10)
                    + "\nrocket: " + server().read("stellurgytest rocket info " + rocketEntity)
                    + "\nserver flight: " + serverLog.since(launchMark, "rocket_ff_traced")
                    + "\n" + serverLog.since(launchMark, "rocket_flight_set")
                    + "\n" + serverLog.since(launchMark, "rocket_launch_gate")
                    + "\nclient flight: " + clientLog.since(releaseMark, "rocket_ff_traced")
                    + "\n" + clientLog.since(releaseMark, "rocket_flight_set"), notReleased);
        }
        String satellite = Events.text(Events.lastRecord(released), "satellite");
        assertTrue("MECH-SAT-32: the released satellite is held in his home world's galaxy: " + released,
                heldIn(Events.lastRecord(released), HOME));
        JsonObject placed = reply(server().read("stellurgytest satellite place " + satellite));
        double altitudeKm = (placed.get("radiusM").getAsDouble()
                - placed.get("bodyRadiusEarths").getAsDouble() * EARTH_RADIUS_M) / 1000d;
        assertEquals("arrangement: the satellite was released at the altitude he typed: " + placed,
                Double.parseDouble(BELT_ALTITUDE_KM), altitudeKm, 1d);

        // ---- He brings the rocket down: a crewed rocket holds over its world after the release until its pilot
        // confirms the descent with the launch key (`EntityRocket#prepareLaunch`, in orbit). ----
        long landMark = serverLog.mark();
        press(KEY_SPACE);
        serverLog.awaitMatching(landMark, "rocket_landed", r -> {
            for (String record : Events.records(r)) {
                if (Events.number(record, "e") == rocketEntity && "server".equals(Events.text(record, "side"))) {
                    return true;
                }
            }
            return false;
        }, "the landing", "his rocket comes back down onto its world", RELEASE_BUDGET);

        // ---- He leaves the rocket: while he sits in it T designates a target instead of opening his chat. ----
        long leaveMark = clientLog.mark();
        input().holdKey(KEY_SNEAK);
        clientLog.await(leaveMark, "dismount", "he steps out of his rocket", SCREEN_BUDGET);
        input().releaseKey(KEY_SNEAK);

        // ---- His terminal sees it. ----
        long firstContactMark = clientLog.mark();
        String firstContact = clientLog.awaitMatching(firstContactMark, "terminal_copy_applied",
                r -> !firstAt(r, site.terminal, "outcome", "RECEIVED").isEmpty(),
                "a contact", "his terminal reaches the satellite and his copy says so", CONTACT_BUDGET);
        // The first contact may already bring losses: it counts everything since the release, and the belt costs a
        // buffer within minutes. The skip's own verdict is the contact AFTER it, which counts only since this one.
        assertTrue("MECH-SAT-32: his copy shows the contact: " + firstContact,
                !firstAt(firstContact, site.terminal, "outcome", "RECEIVED").isEmpty());

        // ---- A day skipped: what the belt cost while nobody watched. ----
        // The belt costs a few points every pass on its own, so the skip's verdict is a comparison: the contact after
        // a day skipped brings more lost points than any contact he has watched so far, each of which spans one pass.
        double mostLostWatched = 0d;
        for (String r : Events.records(clientLog.since(firstContactMark, "terminal_copy_applied"))) {
            if (Events.number(r, "x") == site.terminal.x && Events.number(r, "z") == site.terminal.z) {
                mostLostWatched = Math.max(mostLostWatched, Events.number(r, "lostAboard"));
            }
        }
        long lossMark = clientLog.mark();
        skipTheUniverse(clientLog, FIRST_SKIP);
        String losses = clientLog.awaitMatching(lossMark, "terminal_copy_applied",
                r -> !firstPositiveAt(r, site.terminal, "lostAboard").isEmpty(),
                "points lost aboard", "after a day in the belt, a contact brings word of data lost aboard",
                CONTACT_BUDGET);
        String lossContact = firstPositiveAt(losses, site.terminal, "lostAboard");
        assertTrue("MECH-SAT-32: the contact after the skip reports more points lost aboard satellite " + satellite
                        + " than any watched pass did (" + mostLostWatched + "): " + lossContact,
                Events.number(lossContact, "lostAboard") > mostLostWatched);

        // ---- Long enough more that its electronics have taken twice their budget: it no longer answers. ----
        // The span is measured, not assumed: the dose its parts hold over the time it has lived so far, read off
        // the satellite (`satellite life`), against the budget production stops electronics at.
        JsonObject life = reply(server().read("stellurgytest satellite life " + satellite));
        double dose = 0d;
        for (JsonElement part : life.getAsJsonArray("parts")) {
            dose = Math.max(dose, part.getAsJsonObject().get("dose").getAsDouble());
        }
        double lived = life.get("segments").getAsDouble() * life.get("segmentTicks").getAsDouble();
        assertTrue("arrangement: the satellite has taken a dose to measure a rate from: " + life, dose > 0d && lived > 0d);
        long secondSkip = (long) Math.ceil((2d * ELECTRONICS_BUDGET_GY - dose) / (dose / lived));
        assertTrue("arrangement: the skip that kills it fits one /time add (" + secondSkip + " ticks)",
                secondSkip > 0 && secondSkip <= Integer.MAX_VALUE);
        long silentMark = clientLog.mark();
        skipTheUniverse(clientLog, (int) secondSkip);
        String silent;
        try {
            silent = clientLog.awaitMatching(silentMark, "terminal_copy_applied",
                    r -> !firstAt(r, site.terminal, "outcome", "SILENT").isEmpty(),
                    "no answer", "after its electronics took their budget twice over, his terminal is in contact and the satellite does not answer",
                    CONTACT_BUDGET);
        } catch (AssertionError notSilent) {
            StringBuilder outcomes = new StringBuilder();
            for (String r : Events.records(clientLog.since(silentMark, "terminal_copy_applied"))) {
                outcomes.append(Events.text(r, "outcome")).append("/lost ").append(Events.text(r, "lostAboard"))
                        .append("/parts ").append(Events.text(r, "partsFailed")).append("; ");
            }
            throw new AssertionError(notSilent.getMessage() + "\noutcomes: " + outcomes + "\nsatellite: "
                    + server().read("stellurgytest satellite life " + satellite), notSilent);
        }
        String silentCopy = firstAt(silent, site.terminal, "outcome", "SILENT");
        assertEquals("MECH-SAT-32: the copy says the satellite is in contact and silent: " + silent, "SILENT",
                Events.text(silentCopy, "outcome"));
        // The same copy: the contact that found it dead still brought its journal, the failure that silenced it
        // among the rows — what the expedition's player reads to place the loss on the orbit.
        assertTrue("MECH-SAT-10: a satellite whose primary stopped still sends its journal, so the copy that says"
                        + " it is silent carries the parts that failed: " + silentCopy,
                Integer.parseInt(Events.text(silentCopy, "partsFailed")) > 0);
    }

    // ---- The start ----

    /** Where each thing of the start stands, from the block he stands on. */
    private static final class Site {
        final int x;
        final int floor;
        final int z;
        final Block assembler;
        final Block assemblerGenerator;
        final Block generator;
        final Block builder;
        final Block terminal;
        final Block guidance;
        final Block hatch;
        /** A fuel tank of the rocket, the side he is closest to: what he aims at to board. */
        final Block rocketSide;

        Site(int x, int floor, int z) {
            this.x = x;
            this.floor = floor;
            this.z = z;
            // West of where he stands: the assembler at the pad's west edge with its own coal generator north of
            // it; further south the builder's coal generator, touching only the builder's plug, the controller
            // over that plug. A generator hands its power to its neighbours in a fixed order, and the assembler's
            // 100 000 RF buffer would take all of one it touched. South-east: the terminal under its solar
            // generator. Between his eyes and each of them there is only air, wherever in his cell he spawned.
            assembler = new Block(x - 2, floor + 1, z);
            assemblerGenerator = new Block(x - 2, floor + 1, z - 1);
            generator = new Block(x - 2, floor + 1, z + 2);
            builder = new Block(x - 2, floor + 2, z + 3);
            terminal = new Block(x + 1, floor + 1, z + 2);
            guidance = new Block(x + 2, floor + 4, z - 2);
            // On his side of the guidance computer, so neither stands in front of the other.
            hatch = new Block(x + 1, floor + 4, z - 2);
            rocketSide = new Block(x + 1, floor + 2, z - 2);
        }
    }

    /** A block position. */
    private static final class Block {
        final int x;
        final int y;
        final int z;

        Block(int x, int y, int z) {
            this.x = x;
            this.y = y;
            this.z = z;
        }

        String key() {
            return x + "," + y + "," + z;
        }

        String words() {
            return x + " " + y + " " + z;
        }
    }

    /**
     * The pad (6×6, the cell he stands on in its southern row), the tower beside it, the rocket on it, and
     * the machines south of it — every block what a player would have placed, laid before his first input.
     */
    private void layTheStart(Site s) throws Exception {
        int f = s.floor;
        build("fill " + HOME + " " + (s.x - 3) + " " + (f + 1) + " " + (s.z - 7) + " " + (s.x + 6) + " " + (f + 12) + " "
                + (s.z + 4) + " minecraft:air");
        build("fill " + HOME + " " + (s.x - 3) + " " + f + " " + (s.z - 7) + " " + (s.x + 6) + " " + f + " " + (s.z + 4)
                + " minecraft:stone");
        build("fill " + HOME + " " + (s.x - 1) + " " + f + " " + (s.z - 5) + " " + (s.x + 4) + " " + f + " " + s.z
                + " stellurgy:launchpad");
        build("fill " + HOME + " " + (s.x - 2) + " " + f + " " + (s.z - 4) + " " + (s.x - 2) + " " + (f + 6) + " "
                + (s.z - 4) + " stellurgy:structureTower");
        int rx = s.x + 2;
        int rz = s.z - 2;
        place(rx - 1, f + 1, rz, "stellurgy:advRocketmotor", 0);
        place(rx + 1, f + 1, rz, "stellurgy:advRocketmotor", 0);
        build("fill " + HOME + " " + (rx - 1) + " " + (f + 2) + " " + rz + " " + (rx + 1) + " " + (f + 3) + " " + rz
                + " stellurgy:fuelTank");
        place(s.guidance.x, s.guidance.y, s.guidance.z, "stellurgy:guidanceComputer", 0);
        place(s.hatch.x, s.hatch.y, s.hatch.z, "stellurgy:loader", 1);
        place(rx, f + 5, rz, "stellurgy:seat", 0);
        // The assembler faces west, away from the pad it reads (`TileRocketAssemblingMachine#getRocketPadBounds`
        // looks opposite its front): meta 4 is west.
        place(s.assembler.x, s.assembler.y, s.assembler.z, "stellurgy:rocketBuilder", 4);
        place(s.assemblerGenerator.x, s.assemblerGenerator.y, s.assemblerGenerator.z, "libvulpes:coalGenerator", 0);
        place(s.generator.x, s.generator.y, s.generator.z, "libvulpes:coalGenerator", 0);
        place(s.builder.x, s.builder.y - 1, s.builder.z, "libvulpes:forgePowerInput", 0);
        place(s.builder.x, s.builder.y, s.builder.z, "stellurgy:satelliteBuilder", 0);
        place(s.terminal.x, s.terminal.y, s.terminal.z, "stellurgy:satelliteControlCenter", 0);
        place(s.terminal.x, s.terminal.y + 1, s.terminal.z, "stellurgy:solarGenerator", 0);
    }

    private void place(int x, int y, int z, String block, int meta) throws Exception {
        build("place " + HOME + " " + x + " " + y + " " + z + " " + block + " " + meta);
    }

    private void build(String verb) throws Exception {
        JsonObject r = reply(server().arrange("stellurgytest " + verb));
        assertTrue("arrangement: the start's " + verb + " -> " + r, !r.has("error"));
    }

    private void give(String what) throws Exception {
        server().arrange("give " + PLAYER + " " + what);
    }

    // ---- His acts ----

    /**
     * Turn his head to {@code b}'s centre, and let two client ticks pass: vanilla re-picks what the crosshair is on
     * once a tick ({@code Minecraft#runTick} → {@code EntityRenderer#getMouseOver}), along the look interpolated
     * between the last two ticks' rotations ({@code Entity#getLook(partialTicks)}) — so the first pick after a turn
     * is half way there, and the second is where he looks. Measured: one tick picked a block 35° short of the turn.
     */
    @Wait(shape = Wait.Shape.EXPERIMENT, at = "client().waitWorldTicks(2);",
            why = "the pick is refreshed once per client tick along the look between two ticks' rotations; two ticks are"
                    + " the pick's own latency, and no record of a refreshed pick exists to link on")
    private void lookAt(Block b) throws Exception {
        JsonObject where = reply(server().read("stellurgytest player position-of " + PLAYER));
        double eyeX = where.get("playerPosX").getAsDouble();
        double eyeY = where.get("playerPosY").getAsDouble() + 1.62d;
        double eyeZ = where.get("playerPosZ").getAsDouble();
        double dx = b.x + 0.5d - eyeX;
        double dy = b.y + 0.5d - eyeY;
        double dz = b.z + 0.5d - eyeZ;
        // Vanilla's yaw: 0 faces +z (south), 90 faces -x (west); pitch is positive downward.
        float yaw = (float) Math.toDegrees(Math.atan2(-dx, dz));
        float pitch = (float) -Math.toDegrees(Math.atan2(dy, Math.sqrt(dx * dx + dz * dz)));
        lastLook = "asked yaw " + yaw + " pitch " + pitch + " from eye " + eyeX + "," + eyeY + "," + eyeZ + ", got "
                + input().setLook(yaw, pitch);
        client().waitWorldTicks(2);
    }

    /** What the last turn of his head asked for and got, for the message of a pick that missed. */
    private String lastLook = "";

    /** Look at {@code b}, confirm the crosshair is on it, and press the use key. */
    private void useBlock(Block b) throws Exception {
        lookAt(b);
        JsonObject pick = client().reportMouseOver();
        assertTrue("arrangement: the crosshair picks " + b.key() + ", not " + pick + " (" + lastLook + ")",
                "BLOCK".equals(pick.get("typeOfHit").getAsString())
                        && pick.get("blockX").getAsInt() == b.x && pick.get("blockY").getAsInt() == b.y
                        && pick.get("blockZ").getAsInt() == b.z);
        press(USE);
    }

    private void press(int key) throws Exception {
        input().setKey(key, true);
        input().setKey(key, false);
    }

    /** Use {@code b} and wait for its container's contents to reach his client. */
    private void openScreen(Events clientLog, Block b, String what) throws Exception {
        long mark = clientLog.mark();
        useBlock(b);
        clientLog.awaitMatching(mark, "client_slot_set", r -> aContainerWindow(r),
                "a container", what + " opens its screen", SCREEN_BUDGET);
    }

    private void closeScreen() throws Exception {
        input().pressScreenKey(KEY_ESCAPE);
    }

    /** Pick up the stack of {@code item} in his inventory and put it down in container slot {@code to}. */
    private void moveStack(Events clientLog, String item, int to, String what) throws Exception {
        int from = -1;
        for (JsonElement e : client().reportSlots().getAsJsonArray("slots")) {
            JsonObject slot = e.getAsJsonObject();
            // A 1.12 registry name is held in lower case, whatever case it was registered in.
            if (slot.get("playerSlot").getAsBoolean() && item.equalsIgnoreCase(slot.get("item").getAsString())) {
                from = slot.get("slot").getAsInt();
            }
        }
        assertTrue("arrangement: he carries " + item + " into " + what, from >= 0);
        click(clientLog, from, "picking up " + item);
        click(clientLog, to, "putting " + item + " in " + what);
    }

    /** The open container's one slot of its own (not his inventory's); refused unless there is exactly one. */
    private int onlyOwnSlot() throws Exception {
        int found = -1;
        int count = 0;
        for (JsonElement e : client().reportSlots().getAsJsonArray("slots")) {
            JsonObject slot = e.getAsJsonObject();
            if (!slot.get("playerSlot").getAsBoolean()) {
                found = slot.get("slot").getAsInt();
                count++;
            }
        }
        assertEquals("arrangement: the open screen has one slot of its own", 1, count);
        return found;
    }

    /** The open container's own slot drawn at {@code (x, y)} of its screen. */
    private int ownSlotAt(int x, int y) throws Exception {
        int found = -1;
        for (JsonElement e : client().reportSlots().getAsJsonArray("slots")) {
            JsonObject slot = e.getAsJsonObject();
            if (!slot.get("playerSlot").getAsBoolean() && slot.get("x").getAsInt() == x
                    && slot.get("y").getAsInt() == y) {
                found = slot.get("slot").getAsInt();
            }
        }
        assertTrue("arrangement: the open screen has a slot of its own at " + x + "," + y, found >= 0);
        return found;
    }

    /** Pick up container slot {@code from} and put it down in the first empty slot of his inventory. */
    private void takeToInventory(Events clientLog, int from, String what) throws Exception {
        int to = -1;
        for (JsonElement e : client().reportSlots().getAsJsonArray("slots")) {
            JsonObject slot = e.getAsJsonObject();
            if (to < 0 && slot.get("playerSlot").getAsBoolean() && !slot.get("hasStack").getAsBoolean()) {
                to = slot.get("slot").getAsInt();
            }
        }
        assertTrue("arrangement: he has room for " + what, to >= 0);
        click(clientLog, from, "taking " + what);
        click(clientLog, to, "putting " + what + " away");
    }

    /** One click on container slot {@code slot}, waited for the server's word that it took it. */
    private void click(Events clientLog, int slot, String what) throws Exception {
        long mark = clientLog.mark();
        input().clickSlotAt(slot, 0);
        String confirmed = clientLog.await(mark, "client_click_confirmed", what, CLICK_BUDGET);
        assertTrue("arrangement: the server took the click " + what + ": " + confirmed,
                "true".equals(Events.text(Events.lastRecord(confirmed), "accepted")));
    }

    /** The release altitude typed into the guidance computer's altitude box, digit by digit. */
    private void stateTheAltitude(Events clientLog, Block guidance) throws Exception {
        long mark = clientLog.mark();
        useBlock(guidance);
        serverOrClientScreen(clientLog, mark);
        JsonObject state = client().reportState();
        // The altitude box stands at (120, 16), 48 by 12, in the screen's own frame (`TileGuidanceComputer`).
        input().click(state.get("guiLeft").getAsInt() + 120 + 24, state.get("guiTop").getAsInt() + 16 + 6, 0);
        // One key a call, as a hand types; what the box took is read off the orbit the satellite is released onto.
        for (int i = 0; i < BELT_ALTITUDE_KM.length(); i++) {
            input().typeText(String.valueOf(BELT_ALTITUDE_KM.charAt(i)));
        }
        closeScreen();
    }

    /** The guidance computer's screen holds no slots of its own; its opening reaches him as his inventory's. */
    private void serverOrClientScreen(Events clientLog, long mark) throws Exception {
        clientLog.awaitMatching(mark, "client_slot_set", r -> aContainerWindow(r),
                "a container", "the guidance computer opens its screen", SCREEN_BUDGET);
    }

    /**
     * He types {@code /time add <ticks>} and the server tells him the universe moved — the line
     * `msg.timeskip.command.universe` puts in his chat, with the number. A skip that did not happen would leave
     * every later verdict reading the belt's ordinary losses, so it is a link of its own.
     */
    private void skipTheUniverse(Events clientLog, int ticks) throws Exception {
        long before = reply(server().read("stellurgytest space clock")).get("spaceClock").getAsLong();
        long mark = clientLog.mark();
        typeInChat(clientLog, "/time add " + ticks);
        String told = "The universe moved " + ticks + " tick(s) forward";
        String chat;
        try {
            chat = clientLog.awaitMatching(mark, "client_chat_received", r -> {
                for (String record : Events.records(r)) {
                    String text = Events.text(record, "text");
                    if (text != null && text.contains(told)) {
                        return true;
                    }
                }
                return false;
            }, "the skip", "his /time add moves the universe and he is told so", CHAT_BUDGET);
        } catch (AssertionError notTold) {
            throw new AssertionError(notTold.getMessage() + "\nhis chat: "
                    + clientLog.since(mark, "client_chat_received"), notTold);
        }
        // His client is told the new clock AT the skip: the server sends it before the chat line, over the same
        // connection, so the baseline carrying the jumped clock is applied before the line arrives. A client left
        // to its periodic sync would take it only after.
        double toldAt = Double.NaN;
        for (String record : Events.records(chat)) {
            String text = Events.text(record, "text");
            if (text != null && text.contains(told)) {
                toldAt = Events.number(record, "seq");
                break;
            }
        }
        String synced = clientLog.since(mark, "space_clock_synced");
        boolean clockFirst = false;
        for (String record : Events.records(synced)) {
            if (Events.number(record, "serverTick") >= before + ticks && Events.number(record, "seq") < toldAt) {
                clockFirst = true;
                break;
            }
        }
        assertTrue("C16 CLOCK-8: his client takes the skipped clock (at least " + (before + ticks) + ") before the"
                + " chat line telling him of the skip arrives; synced: " + synced + "\nchat: " + chat, clockFirst);
    }

    /**
     * Open the chat, type {@code line}, and send it — an operator's command, typed by him. One key a call: a call is
     * read in one client tick, and a hand does not press one key three times in a tick ("24000").
     */
    private void typeInChat(Events clientLog, String line) throws Exception {
        long mark = clientLog.mark();
        press(KEY_CHAT);
        try {
            clientLog.awaitMatching(mark, "client_gui_opened", r -> {
                for (String record : Events.records(r)) {
                    if ("GuiChat".equals(Events.text(record, "gui")) && "false".equals(Events.text(record, "cancelled"))) {
                        return true;
                    }
                }
                return false;
            }, "his chat", "the chat key opens his chat", SCREEN_BUDGET);
        } catch (AssertionError noChat) {
            throw new AssertionError(noChat.getMessage() + "\nhis screen: " + client().reportState(), noChat);
        }
        for (int i = 0; i < line.length(); i++) {
            input().typeText(String.valueOf(line.charAt(i)));
        }
        input().pressScreenKey(KEY_RETURN);
    }

    // ---- Reading ----

    private static JsonObject reply(List<String> lines) {
        String all = String.join("\n", lines);
        int open = all.indexOf('{');
        int close = all.lastIndexOf('}');
        assertTrue("a reply carries a JSON object: " + all, open >= 0 && close > open);
        return new JsonParser().parse(all.substring(open, close + 1)).getAsJsonObject();
    }

    /** The last record of {@code reply} at block {@code b} (its {@code x}, {@code y}, {@code z}), or empty. */
    private static String lastAt(String reply, Block b) {
        String out = "";
        for (String r : Events.records(reply)) {
            if (Events.number(r, "x") == b.x && Events.number(r, "y") == b.y && Events.number(r, "z") == b.z) {
                out = r;
            }
        }
        return out;
    }

    /** Whether the last record of {@code reply} for container slot {@code slot} holds {@code item}. */
    private static boolean slotHolds(String reply, int slot, String item) {
        String last = "";
        for (String r : Events.records(reply)) {
            if (Events.number(r, "slot") == slot) {
                last = r;
            }
        }
        return !last.isEmpty() && item.equalsIgnoreCase(Events.text(last, "item"));
    }

    /** Whether any record of {@code reply} is about a container window other than his own inventory (0). */
    private static boolean aContainerWindow(String reply) {
        for (String r : Events.records(reply)) {
            if (Events.number(r, "window") > 0) {
                return true;
            }
        }
        return false;
    }

    /**
     * The first record of {@code reply} at {@code b} whose {@code field} is {@code value}, or empty. A terminal goes
     * on trying after a contact, and its next try may be out of sight: a contact is a record that HAPPENED.
     */
    private static String firstAt(String reply, Block b, String field, String value) {
        for (String r : Events.records(reply)) {
            if (Events.number(r, "x") == b.x && Events.number(r, "y") == b.y && Events.number(r, "z") == b.z
                    && value.equals(Events.text(r, field))) {
                return r;
            }
        }
        return "";
    }

    /** The first record of {@code reply} at {@code b} whose {@code field} is above zero, or empty. */
    private static String firstPositiveAt(String reply, Block b, String field) {
        for (String r : Events.records(reply)) {
            if (Events.number(r, "x") == b.x && Events.number(r, "y") == b.y && Events.number(r, "z") == b.z
                    && Events.number(r, field) > 0) {
                return r;
            }
        }
        return "";
    }

    /** The text {@code field} of {@code reply}'s last record at {@code b}; {@code null} when no record is at it. */
    private static String textAt(String reply, Block b, String field) {
        String r = lastAt(reply, b);
        return r.isEmpty() ? null : Events.text(r, field);
    }

    /** Whether a record of {@code reply} is the server serving the container of the block at {@code b}. */
    private static boolean servedAt(String reply, Block b) {
        for (String r : Events.records(reply)) {
            if ("gui_container_served".equals(Events.text(r, "type")) && b.key().equals(Events.text(r, "at"))
                    && !"null".equals(Events.text(r, "container"))) {
                return true;
            }
        }
        return false;
    }

    /** Whether a record of {@code reply} is the server's scan (not a build) finishing at {@code assembler}. */
    private static boolean scanFinishedAt(String reply, Block assembler) {
        for (String r : Events.records(reply)) {
            if (assembler.key().equals(Events.text(r, "pos")) && "false".equals(Events.text(r, "building"))
                    && "false".equals(Events.text(r, "remote"))) {
                return true;
            }
        }
        return false;
    }

    /** Whether a record of {@code reply} names the rocket born at {@code assembler} ({@code pos}). */
    private static boolean bornAt(String reply, Block assembler) {
        for (String r : Events.records(reply)) {
            if (assembler.key().equals(Events.text(r, "pos"))) {
                return true;
            }
        }
        return false;
    }

    /** Whether the satellite record's {@code heldIn} names {@code dim}. */
    private static boolean heldIn(String record, int dim) {
        for (JsonElement e : new JsonParser().parse(record).getAsJsonObject().getAsJsonArray("heldIn")) {
            if (e.getAsInt() == dim) {
                return true;
            }
        }
        return false;
    }
}
