package dev.stannismod.stellurgy.test.server;

import dev.stannismod.stellurgy.test.FixtureSite;
import dev.stannismod.stellurgy.test.GameTicks;
import dev.stannismod.stellurgy.test.Reply;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static dev.stannismod.stellurgy.test.ArrangementFailure.requireArranged;
import static dev.stannismod.stellurgy.test.StellurgyTestConstants.ppm;
import static dev.stannismod.stellurgy.test.server.WorldCommandFixtures.arrange;
import static dev.stannismod.stellurgy.test.server.WorldCommandFixtures.ask;

/**
 * Life support as a placed machine rather than as arithmetic.
 *
 * <p>{@code AirStateTest} already pins the gas maths in isolation. What it cannot see is whether a
 * zone exists at all, whether the vent stays the authority over it, and whether a machine standing
 * in a room actually finds that room, moves its air, and stops where it is told to. Those are the
 * things here — including the combiner's governor, which is a refusal and so can only be told from
 * a broken machine by watching it act first and then decline.</p>
 */
public class LifeSupportZoneTest extends AbstractSharedServerTest {

    /** The Y and Z every helper here builds on, taken from this scenario's own site by
     *  {@link #stand}. A fresh instance per test method, so one scenario's never reaches another. */
    private int cyBase;
    private int czBase;

    /**
     * Ask for this scenario's site, prove its volume empty, and answer the X the fixture is centred
     * on. The room spans two blocks either side of that X and five blocks up from one above the
     * site; the jettison pocket reaches four either side, and its floor stands on the site's own Y.
     */
    private int stand(String what) throws Exception {
        FixtureSite site = clearedSite(4, 8, what);
        cyBase = site.y + 2;
        czBase = site.z + 2;
        return site.x + 2;
    }

    /** A maintained zone starts as sea-level air, and reports the pressure the mod has always
     *  reported for a pressurised room. This is the probe's own grounding: if it lied, the two
     *  tests below would be measuring nothing.
     *
     *  <p>red-witnessed: one inversion per verdict, 2026-09-30. OXYGEN — {@code AirState#earthLike} at {@code return new AirState(790_000 * PER_PPM, 210_000 * PER_PPM, 0L);}
     *  ({@code earthLike}) at 200 000 ppm oxygen: "a fresh maintained zone must hold sea-level
     *  oxygen: … \"airO2\":200000000". NO CO2 — the same line with 1 000 ppm of carbon dioxide: "and
     *  no carbon dioxide at all: … \"airCO2\":1000000". ONE ATMOSPHERE — {@code AirState#getPressureCentiAtm} at {@code return (int) Math.min(Integer.MAX_VALUE, getTotalPressure() / (ONE_ATM / 100L));}
     *  dividing by a hundred-and-first of an atmosphere: "its pressure must read as one atmosphere:
     *  … \"airPressure\":101".</p> */
    @Test
    public void aSealedPoweredRoomHoldsBreathableAir() throws Exception {
        int cx = stand("a sealed room with a powered vent");
        buildSealableRoom(cx);
        placeVent(cx);
        injectEnergy(cx, 1_000_000);
        injectOxygen(cx, 16000);
        forceTickAndReseal(cx);

        Reply info = ventInfo(cx);
        assertEquals("a fresh maintained zone must hold sea-level oxygen: " + info,
                ppm(210_000), info.longInteger("airO2"));
        assertEquals("and no carbon dioxide at all: " + info, 0L, info.longInteger("airCO2"));
        assertEquals("its pressure must read as one atmosphere: " + info,
                100L, info.longInteger("airPressure"));
    }

    /** INV-ATM-19. Without power the vent never seals, so there is no zone — and therefore nothing
     *  for life support to act on. The probe reports -1 for "no zone", which is the distinction
     *  that matters: an unmaintained room is not a room full of stale air, it is a room the system
     *  has no opinion about.
     *
     *  <p>red-witnessed: with {@code TileOxygenVent#notEnoughEnergyForFunction} at {@code if (handler != null)} no longer clearing the zone when the vent
     *  has too little energy to run: "an unpowered vent must not be maintaining a zone: …
     *  \"airO2\":210000000", 2026-09-30.</p> */
    @Test
    public void anUnpoweredRoomHasNoZoneForLifeSupportToTouch() throws Exception {
        int cx = stand("a sealed room with an unpowered vent");
        buildSealableRoom(cx);
        placeVent(cx);
        injectOxygen(cx, 16000);
        // Deliberately no energy.
        forceTickAndReseal(cx);

        Reply info = ventInfo(cx);
        assertEquals("an unpowered vent must not be maintaining a zone: " + info,
                -1L, info.longInteger("airO2"));
    }

    /** MECH-ATM-20 end to end: a powered recirculator standing in a stale room turns that room's
     *  CO2 back into oxygen and leaves solid carbon in its own slot.
     *
     *  <p>red-witnessed: one inversion per verdict, 2026-09-30. CONSUMES — {@code TileAirRecirculator#performFunction} at {@code long regenerated = air.regenerate(StellurgyConfiguration.getCurrentConfig().lifeSupportRecirculatorRate);} regenerating nothing: "the recirculator must consume its room's CO2
     *  (before=150000 after=150000000)". PRESSURE — {@code AirState#regenerate} at {@code set(GasRegistry.OXYGEN, getOxygen() + converted);} returning twice the oxygen
     *  it took: "regeneration must not change the room's pressure: … \"airPressure\":112". BREATHABLE
     *  — {@code TileAirRecirculator#performFunction} at {@code handler.refreshDerivedAtmosphereAt(cell);} no longer refreshing the published atmosphere: "a
     *  regenerated room must read as breathable, not merely contain oxygen: … \"lowO2\"". DUST —
     *  {@code TileAirRecirculator#performFunction} at {@code emitDust();} no longer making dust: "`slots` holds no element whose `item`
     *  is stellurgy:carbondust". The stale-room premise is an arrangement and is not witnessed.</p>
     *
     *  <p>Not asserted here: that the oxygen comes back, because this room's vent tops it up toward
     *  sea level the whole time, so a rise in oxygen cannot be credited to the recirculator. That is
     *  {@link #aRecirculatorGivesBackOneOxygenForEachCarbonDioxideItTakes}, in a room the vent has
     *  nothing to add to.</p> */
    @Test
    public void aRecirculatorClearsItsRoomsCarbonDioxideAndDropsDust() throws Exception {
        int cx = stand("a stale room with a recirculator in it");
        buildSealableRoom(cx);
        placeVent(cx);
        injectEnergy(cx, 1_000_000);
        injectOxygen(cx, 16000);
        forceTickAndReseal(cx);

        // Make the room stale: most of its oxygen already breathed into CO2.
        arrange("stellurgytest vent setair 0 " + cx + " " + cyBase + " " + czBase
                + " " + ppm(790_000) + " " + ppm(60_000) + " " + ppm(150_000));

        Reply before = ventInfo(cx);
        assertEquals("premise: the room is stale before the machine runs: " + before,
                ppm(150_000), before.longInteger("airCO2"));

        placeRecirculator(cx);
        injectEnergyAt(cx + 1, 1_000_000);
        // World time advances one second per 20 ticks and the machine acts on that cadence.
        forceTick(cx + 1, 400);

        Reply after = ventInfo(cx);
        long co2After = after.longInteger("airCO2");
        assertTrue("the recirculator must consume its room's CO2 (before=150000 after="
                + co2After + "): " + after, co2After < ppm(150_000));
        assertEquals("regeneration must not change the room's pressure: " + after,
                100L, after.longInteger("airPressure"));
        // The gases are only half the story: what damages the crew is the atmosphere the zone
        // PUBLISHES, and a room that has been regenerated must publish a breathable one.
        assertEquals("a regenerated room must read as breathable, not merely contain oxygen: " + after,
                "PressurizedAir", after.text("blobAtmosphere"));

        // Forge lowercases registry paths, so the id Java passes as "carbonDust" is stored — and
        // reported — as "carbondust".
        Reply slot = ask("stellurgytest hatch read 0 " + (cx + 1) + " " + cyBase + " " + czBase);
        assertTrue("the carbon it removed from the air must appear as dust: " + slot,
                slot.element("slots", "item", "stellurgy:carbondust").integer("count") >= 1);
    }

    /** The half of regeneration the stale room above cannot see: the oxygen a recirculator puts back is
     *  the carbon dioxide it took, one for one.
     *
     *  <p>The room is arranged so that nothing ELSE can put oxygen in it. Its vent tops a room up
     *  toward sea level, and only while it is below that; this room starts with sea-level oxygen plus
     *  carbon dioxide on top, and regeneration only ever raises its oxygen, so the vent has nothing to
     *  add at any point. That is measured before the machine arrives, not assumed: a window with the
     *  vent alone must leave the oxygen where it was.</p>
     *
     *  <p>red-witnessed: with {@code AirState#regenerate} at {@code set(GasRegistry.OXYGEN, getOxygen() + converted);} ({@code regenerate}) taking the carbon dioxide and
     *  returning no oxygen: "the oxygen must come back, one for one with the carbon dioxide the
     *  recirculator took (took 60000000, oxygen rose 0)", 2026-09-30. The vent-alone premise is an
     *  arrangement and is not witnessed.</p> */
    @Test
    public void aRecirculatorGivesBackOneOxygenForEachCarbonDioxideItTakes() throws Exception {
        int cx = stand("a room at sea-level oxygen plus carbon dioxide, with a recirculator in it");
        buildSealableRoom(cx);
        placeVent(cx);
        injectEnergy(cx, 1_000_000);
        injectOxygen(cx, 16000);
        forceTickAndReseal(cx);

        arrange("stellurgytest vent setair 0 " + cx + " " + cyBase + " " + czBase
                + " " + ppm(730_000) + " " + ppm(210_000) + " " + ppm(60_000));

        Reply ventAloneBefore = ventInfo(cx);
        // WINDOW: two reads with the vent alone between them, ventAloneBefore and before. The vent's
        // top-up is the only other thing that puts oxygen into this room, it works per server tick,
        // and at this room's size it would add tens of ppm on each of them — so an unchanged reading
        // across twenty ticks says the vent is not adding, which no single read could say.
        GameTicks.advance(client(), GameTicks.server(), 20);
        Reply before = ventInfo(cx);
        requireArranged("premise: the vent alone must add no oxygen to this room, or the oxygen below"
                        + " could be the vent's: " + ventAloneBefore + " → " + before,
                before.longInteger("airO2") == ventAloneBefore.longInteger("airO2"));
        long o2Before = before.longInteger("airO2");
        long co2Before = before.longInteger("airCO2");

        placeRecirculator(cx);
        injectEnergyAt(cx + 1, 1_000_000);
        forceTick(cx + 1, 400);

        Reply after = ventInfo(cx);
        long taken = co2Before - after.longInteger("airCO2");
        long gained = after.longInteger("airO2") - o2Before;
        assertTrue("the oxygen must come back, one for one with the carbon dioxide the recirculator took"
                        + " (took " + taken + ", oxygen rose " + gained + "): " + before + " → " + after,
                gained > 0 && gained == taken);
    }

    /** MECH-ATM-21 split: a separator standing in a stale room draws its CO2 into its own tank.
     *  The unit tests prove the arithmetic; this proves the machine finds the room at all, which
     *  is precisely what the recirculator got wrong twice.
     *
     *  <p>red-witnessed: one inversion per verdict, 2026-09-30. PULLS CO2 — {@code TileGasSeparator#split} at {@code if (!moveToTank(air, cell, StellurgyFluids.fluidCarbonDioxide, rate))} going straight to nitrogen: "the separator must pull CO2 out of the room
     *  (before=150000 after=150000000)". SPARES OXYGEN — {@code TileGasSeparator#moveToTank} at {@code ? air.drawCarbonDioxide(take)} drawing oxygen
     *  alongside the carbon dioxide: "and must not touch the oxygen the crew are breathing: …
     *  \"airO2\":52631". INTO ITS TANK — {@code TileGasSeparator#moveToTank} at {@code fill(new FluidStack(gas, Math.min(accepted, volumeFor(taken, cell))), true);} never filling the tank: "`tanks`
     *  holds no element whose `fluid` is carbon_dioxide".</p> */
    @Test
    public void aSeparatorDrawsItsRoomsCarbonDioxideIntoItsTank() throws Exception {
        int cx = stand("a stale room with a separator in it");
        buildSealableRoom(cx);
        placeVent(cx);
        injectEnergy(cx, 1_000_000);
        injectOxygen(cx, 16000);
        forceTickAndReseal(cx);

        arrange("stellurgytest vent setair 0 " + cx + " " + cyBase + " " + czBase
                + " " + ppm(790_000) + " " + ppm(60_000) + " " + ppm(150_000));

        placeSeparator(cx);
        injectEnergyAt(cx + 1, 1_000_000);
        forceTick(cx + 1, 200);

        Reply after = ventInfo(cx);
        long co2After = after.longInteger("airCO2");
        assertTrue("the separator must pull CO2 out of the room (before=150000 after="
                + co2After + "): " + after, co2After < ppm(150_000));
        // A floor, not an equality. The vent maintaining this room restores oxygen while the
        // separator runs, and that is not the separator touching it; what the clause forbids is the
        // separator drawing the crew's oxygen, which a floor still catches.
        assertTrue("and must not touch the oxygen the crew are breathing: " + after,
                after.longInteger("airO2") >= ppm(60_000));

        Reply tank = ask("stellurgytest fluid stored 0 " + (cx + 1) + " " + cyBase + " " + czBase);
        assertTrue("the gas it removed must be in its tank as carbon dioxide: " + tank,
                tank.element("tanks", "fluid", "carbon_dioxide").longInteger("amount") > 0);
    }

    /** MECH-ATM-21 combine: the other direction. A separator flipped to combine puts the gas in
     *  its tank back into the room — which is what makes a stripped cabin habitable again, and is
     *  the half of the machine no test had ever driven in a world.
     *
     *  <p>red-witnessed: one inversion per verdict, 2026-09-30. BREATHABLE AGAIN — {@code TileGasSeparator#performFunction} at {@code handler.refreshDerivedAtmosphereAt(cell);} no longer refreshing the published atmosphere: "and the room must become
     *  breathable again: … \"lowO2\" … \"airO2\":260368417". LEFT ITS TANK — {@code TileGasSeparator#combine} at {@code drain(volumeFor(admitted, cell), true);} admitting oxygen without draining it: "the oxygen it gave the room must
     *  have left its tank: … \"tankAmount\":8000". The three premises are arrangements and are not
     *  witnessed.</p>
     *
     *  <p>Not asserted here: that the room's oxygen rises, because this room's vent tops it up toward
     *  sea level meanwhile, so a rise cannot be credited to the separator. The separator's own
     *  delivery is pinned by {@link #theCombinerRefusesToPushOxygenPastTheSafeCeiling}, whose room
     *  starts above what the vent tops up to.</p> */
    @Test
    public void aSeparatorInCombineModeGivesItsOxygenBackToTheRoom() throws Exception {
        int cx = stand("a stripped room with a combining separator in it");
        buildSealableRoom(cx);
        placeVent(cx);
        injectEnergy(cx, 1_000_000);
        injectOxygen(cx, 16000);
        forceTickAndReseal(cx);

        // A room whose oxygen has been stripped out: still pressurised by its nitrogen, but not
        // breathable. This is the state a split-mode separator leaves behind.
        arrange("stellurgytest vent setair 0 " + cx + " " + cyBase + " " + czBase
                + " " + ppm(790_000) + " " + ppm(60_000) + " 0");
        Reply before = ventInfo(cx);
        assertEquals("premise: the room must start un-breathable: " + before,
                "lowO2", before.text("blobAtmosphere"));

        placeSeparator(cx);
        injectEnergyAt(cx + 1, 1_000_000);
        arrange("stellurgytest fluid inject 0 " + (cx + 1) + " " + cyBase + " "
                + czBase + " oxygen 8000");

        flipMode(cx + 1);
        Reply mode = separatorInfo(cx + 1);
        assertTrue("premise: the sneak-click must have put it in combine mode: " + mode,
                mode.bool("combining"));
        assertTrue("premise: it must have found the room it stands in: " + mode,
                mode.bool("hasServedCell"));

        forceTick(cx + 1, 200);

        Reply after = ventInfo(cx);
        assertEquals("and the room must become breathable again: " + after,
                "PressurizedAir", after.text("blobAtmosphere"));

        Reply tank = separatorInfo(cx + 1);
        assertTrue("the oxygen it gave the room must have left its tank: " + tank,
                tank.longInteger("tankAmount") < 8000);
    }

    /** MECH-ATM-21 governor — the reason the combiner exists. Oxygen is admitted only up to the
     *  configured ceiling, so a cabin cannot be enriched into a fire hazard however much gas is
     *  piped at it. Pinned as "climbs, then stops exactly at the ceiling with gas to spare": a
     *  machine that simply did nothing would satisfy "never exceeds" without governing anything.
     *
     *  <p>red-witnessed: one inversion per verdict, 2026-09-30. AT THE CEILING — {@code TileGasSeparator#combine} at {@code long admitted = Math.min(available, air.oxygenHeadroom());} admitting without the headroom cap: "oxygen must stop exactly at the
     *  ceiling … \"airO2\":660000000"; and, the separator's delivery at all, {@code TileGasSeparator#combine} at {@code air.addOxygen(admitted, fromTheTank);} draining the tank without adding the oxygen to the room: "… climbing from
     *  260000000 and no further than 300000000: … expected:&lt;300000000&gt; but
     *  was:&lt;260000000&gt;" — the room starts above what its vent tops up to, so nothing but the
     *  separator could have moved it. STAYS BREATHABLE — {@code AirState#deriveAtmosphere} at {@code if (oxidiser > config.lifeSupportMaxPartialO2)} calling the ceiling
     *  itself oxygen-rich: "and the room must stay breathable rather than turn oxygen-toxic: …
     *  \"highO2\"". NOT DRY — {@code TileGasSeparator#combine} at {@code if (admitted <= 0L)} emptying the tank once it can admit
     *  nothing: "it must have stopped because of the ceiling, not because the tank ran dry: …
     *  \"tankAmount\":0".</p> */
    @Test
    public void theCombinerRefusesToPushOxygenPastTheSafeCeiling() throws Exception {
        long ceiling = configValue("lifeSupportMaxPartialO2");
        long start = ceiling - ppm(40_000);

        int cx = stand("a room just under the oxygen ceiling, with a combining separator");
        buildSealableRoom(cx);
        placeVent(cx);
        injectEnergy(cx, 1_000_000);
        injectOxygen(cx, 16000);
        forceTickAndReseal(cx);

        arrange("stellurgytest vent setair 0 " + cx + " " + cyBase + " " + czBase
                + " " + ppm(790_000) + " " + start + " 0");

        placeSeparator(cx);
        injectEnergyAt(cx + 1, 1_000_000);
        arrange("stellurgytest fluid inject 0 " + (cx + 1) + " " + cyBase + " "
                + czBase + " oxygen 8000");

        flipMode(cx + 1);
        // Far longer than the two operations the gap needs: the machine must stop by decision,
        // not by running out of time.
        forceTick(cx + 1, 400);

        Reply after = ventInfo(cx);
        long o2After = after.longInteger("airO2");
        assertEquals("oxygen must stop exactly at the ceiling — climbing from " + start
                + " and no further than " + ceiling + ": " + after, ceiling, o2After);
        assertEquals("and the room must stay breathable rather than turn oxygen-toxic: " + after,
                "PressurizedAir", after.text("blobAtmosphere"));

        Reply tank = separatorInfo(cx + 1);
        assertTrue("it must have stopped because of the ceiling, not because the tank ran dry: "
                + tank, tank.longInteger("tankAmount") > 0);
    }

    // ─── helpers ───────────────────────────────────────────────────────

    /**
     * The carbon has somewhere to go. A scrubber's output slot backs the machine up when it fills,
     * so a closed air loop is only closed if the dust can leave the ship — this is that exit.
     *
     * <p>The assertion is deliberately about the WORLD and not about the slot: an emptied slot is
     * equally consistent with a port that simply voided its cargo, and "the dust was deleted" is
     * not the contract. {@code ejected} counts loose item entities beside the port.</p>
     *
     * <p>red-witnessed: one inversion per verdict, 2026-09-30. NO OBSTRUCTION — {@code EjectionPort#obstructionDistance} at {@code return HullClearance.obstructionDistance(world, pos, facing, clearance);} never reporting a clear exit: "a port with a clear exit must report no
     * obstruction: … expected:&lt;0&gt; but was:&lt;1&gt;". SLOT EMPTY — {@code TileJettisonPort#update} at {@code setInventorySlotContents(0, ItemStack.EMPTY);}
     * no longer emptying the slot after firing: "and its slot must be empty afterwards: …
     * expected:&lt;0&gt; but was:&lt;1&gt;". IN THE WORLD — {@code EjectionPort#eject} at {@code return world.spawnEntity(item);} reporting a
     * launch without spawning anything: "the dust must exist in the world as a jettisoned item … \"ejected\":0".
     * The load premise is an arrangement and is not witnessed.</p>
     */
    @Test
    public void aJettisonPortThrowsItsCargoOverboard() throws Exception {
        int cx = stand("a jettison port with a clear exit");
        clearAirPocket(cx);
        placeJettisonPort(cx);

        Reply loaded = ask("stellurgytest jettison load 0 " + cx + " " + cyBase + " " + czBase
                + " stellurgy:carbonDust 1");
        assertTrue("the port must accept a stack: " + loaded, loaded.ok());

        forceTick(cx, 25);

        Reply info = ask("stellurgytest jettison info 0 " + cx + " " + cyBase + " " + czBase);
        assertEquals("a port with a clear exit must report no obstruction: " + info,
                0, info.integer("obstruction"));
        assertEquals("and its slot must be empty afterwards: " + info, 0, info.integer("heldCount"));
        assertTrue("the dust must exist in the world as a jettisoned item — an empty slot alone is"
                + " what voiding it would also look like: " + info, info.integer("ejected") >= 1);
    }

    /**
     * The counter-test, and the one that makes the port safe to build badly: a port whose exit is
     * blocked HOLDS its cargo instead of firing into the wall or quietly voiding it.
     *
     * <p>red-witnessed: one inversion per verdict, 2026-09-30. OBSTRUCTION — {@code EjectionPort#obstructionDistance} at {@code return HullClearance.obstructionDistance(world, pos, facing, clearance);}
     * reporting every exit clear: "a walled-in port must report where the obstruction is: …
     * \"obstruction\":0". HELD — {@code TileJettisonPort#update} at {@code if (obstruction != 0 || held.isEmpty())} firing whether or not the exit is
     * blocked: "and it must still be holding the dust: … expected:&lt;1&gt; but was:&lt;0&gt;".
     * NOTHING JETTISONED — the same line ejecting a copy of the cargo while still holding it: "with
     * nothing jettisoned: … expected:&lt;0&gt; but was:&lt;1&gt;".</p>
     */
    @Test
    public void aBlockedJettisonPortHoldsItsCargo() throws Exception {
        int cx = stand("a jettison port walled in on every side");
        clearAirPocket(cx);
        placeJettisonPort(cx);
        // Wall it in on every side, so the outcome does not depend on which way the port was placed.
        arrange("stellurgytest fill 0 " + (cx - 1) + " " + (cyBase - 1) + " " + (czBase - 1)
                + " " + (cx + 1) + " " + (cyBase + 1) + " " + (czBase + 1)
                + " minecraft:stone");
        placeJettisonPort(cx);

        arrange("stellurgytest jettison load 0 " + cx + " " + cyBase + " " + czBase
                + " stellurgy:carbonDust 1");
        forceTick(cx, 25);

        Reply info = ask("stellurgytest jettison info 0 " + cx + " " + cyBase + " "
                + czBase);
        assertTrue("a walled-in port must report where the obstruction is: " + info,
                info.integer("obstruction") > 0);
        assertEquals("and it must still be holding the dust: " + info, 1, info.integer("heldCount"));
        assertEquals("with nothing jettisoned: " + info, 0, info.integer("ejected"));
    }

    /** Open sky around the port, on a stone floor, so its exit is clear whichever way it faces. */
    private void clearAirPocket(int cx) throws Exception {
        arrange("stellurgytest fill 0 " + (cx - 4) + " " + (cyBase - 1) + " " + (czBase - 4)
                + " " + (cx + 4) + " " + (cyBase + 4) + " " + (czBase + 4) + " minecraft:air");
        arrange("stellurgytest fill 0 " + (cx - 4) + " " + (cyBase - 2) + " " + (czBase - 4)
                + " " + (cx + 4) + " " + (cyBase - 2) + " " + (czBase + 4) + " minecraft:stone");
    }

    private void placeJettisonPort(int cx) throws Exception {
        place(cx, "stellurgy:jettisonPort");
    }

    private void buildSealableRoom(int cx) throws Exception {
        int by = cyBase, bz = czBase;
        arrange("stellurgytest fill 0 " + (cx - 2) + " " + (by - 1) + " " + (bz - 2)
                + " " + (cx + 2) + " " + by + " " + (bz + 2) + " minecraft:stone");
        for (int yy = by + 1; yy <= by + 2; yy++) {
            arrange("stellurgytest fill 0 " + (cx - 2) + " " + yy + " " + (bz - 2)
                    + " " + (cx + 2) + " " + yy + " " + (bz + 2) + " minecraft:stone");
            arrange("stellurgytest fill 0 " + (cx - 1) + " " + yy + " " + (bz - 1)
                    + " " + (cx + 1) + " " + yy + " " + (bz + 1) + " minecraft:air");
        }
        arrange("stellurgytest fill 0 " + (cx - 2) + " " + (by + 3) + " " + (bz - 2)
                + " " + (cx + 2) + " " + (by + 3) + " " + (bz + 2) + " minecraft:stone");
    }

    private void placeVent(int cx) throws Exception {
        place(cx, "stellurgy:oxygenVent");
    }

    /** Placed one block along, inside the same sealed volume as the vent. */
    private void placeRecirculator(int cx) throws Exception {
        place(cx + 1, "stellurgy:airRecirculator");
    }

    private void placeSeparator(int cx) throws Exception {
        place(cx + 1, "stellurgy:gasSeparator");
    }

    private void place(int x, String block) throws Exception {
        Reply resp = arrange("stellurgytest place 0 " + x + " " + cyBase + " " + czBase + " " + block);
        assertTrue(block + " place failed: " + resp, resp.bool("placed"));
    }

    private void injectEnergy(int cx, int amount) throws Exception {
        injectEnergyAt(cx, amount);
    }

    private void injectEnergyAt(int x, int amount) throws Exception {
        arrange("stellurgytest energy inject 0 " + x + " " + cyBase + " " + czBase + " " + amount);
    }

    private void injectOxygen(int cx, int amount) throws Exception {
        arrange("stellurgytest fluid inject 0 " + cx + " " + cyBase + " " + czBase
                + " oxygen " + amount);
    }

    /**
     * Force-ticks a machine and CHECKS that it was there to tick.
     * <p>
     * The probe answers {@code {"error":"tile not ITickable","tile":"null"}} when the chunk has gone
     * — and an unchecked force-tick makes that indistinguishable from the machine declining to act,
     * which is exactly the shape several assertions here are testing for. Measured 2026-08-16: a
     * combiner scenario read as "the machine stopped at its ceiling" when in truth nothing had
     * ticked at all.
     */
    private void forceTick(int x, int ticks) throws Exception {
        arrange("stellurgytest tile force-tick 0 " + x + " " + cyBase + " " + czBase + " " + ticks);
    }

    private void forceTickAndReseal(int cx) throws Exception {
        forceTick(cx, 1);
        arrange("stellurgytest vent reseal 0 " + cx + " " + cyBase + " " + czBase);
        forceTick(cx, 5);
    }

    private Reply ventInfo(int cx) throws Exception {
        return ask("stellurgytest vent info 0 " + cx + " " + cyBase + " " + czBase);
    }

    private Reply separatorInfo(int x) throws Exception {
        return ask("stellurgytest separator info 0 " + x + " " + cyBase + " " + czBase);
    }

    /** The production toggle: a sneak-right-click on the block, through the block's own
     *  onBlockActivated. Calling toggleMode() on the tile would skip the dispatch that decides
     *  whether a click means "open me" or "flip me", which is the part a player uses. */
    private void flipMode(int x) throws Exception {
        Reply resp = ask("stellurgytest block activate 0 " + x + " " + cyBase + " " + czBase + " true");
        assertTrue("sneak-click failed: " + resp, resp.bool("handled"));
    }

    private long configValue(String key) throws Exception {
        return arrange("stellurgytest config get " + key).longInteger("value");
    }
}
