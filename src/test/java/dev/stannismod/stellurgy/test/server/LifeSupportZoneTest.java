package dev.stannismod.stellurgy.test.server;

import dev.stannismod.stellurgy.test.FixtureSite;
import dev.stannismod.stellurgy.test.GameTicks;
import dev.stannismod.stellurgy.test.Reply;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static dev.stannismod.stellurgy.test.ArrangementFailure.requireArranged;
import static dev.stannismod.stellurgy.test.StellurgyTestConstants.ppm;

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
    /** The world the helpers build in: the overworld, unless a scenario says otherwise. */
    private int dim = 0;

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
     *  <p>red-witnessed: with {@code TileOxygenVent#keepZone} at {@code if (zone.tick(atmhandler, pos, isTurnedOn() && hasEnoughEnergy(getPowerPerOperation())))}
     *  holding the zone whenever the vent is switched on, power or not: "an unpowered vent must not
     *  be maintaining a zone: … \"airSource\":\"zone\"", 2026-10-05 (re-taken after the zone moved
     *  into the shared zone keeper).</p> */
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
     *  separator could have moved it. STAYS BREATHABLE — {@code AirState#oxygenRung} at {@code if (oxidiser > config.lifeSupportMaxPartialO2)} calling the ceiling
     *  itself oxygen-rich (taken on the pre-move form, when this statement stood verbatim in
     *  {@code deriveAtmosphere}): "and the room must stay breathable rather than turn oxygen-toxic: …
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

    /**
     * A sealed room whose vent has no oxygen to give is still breathed down by whoever is in it.
     * The vent's tank is the room's SUPPLY; the room's air is what the crew consume, and a supply
     * that is empty does not stop the consuming — that is the whole danger of a supply failure.
     *
     * <p>Observed as a WINDOW on the zone's carbon dioxide: respiration turns oxygen into CO2 one
     * breath at a time, and nothing else in this room touches CO2 (no plant, no recirculator, no
     * scrubber; a vent adds oxygen only). One breath is
     * {@code lifeSupportRespirationRate / zone volume}, the same division {@code respire} makes, so
     * "rose by at least one breath" separates "breathed" from "frozen" exactly. The breathing
     * entity is the probe's player, driven by {@code player tick-living}: the same
     * {@code LivingUpdateEvent} a ticking player raises, on whose arrival production decides
     * whether to respire.</p>
     *
     * <p>The CONTROL is the same window once the tank holds oxygen, read AFTER the subject's and
     * asserted before it: if the subject leg reads zero, the control says whether the probe's player
     * breathes in this room at all, and an instrument that never fired is not reported as the
     * defect.</p>
     *
     * <p>red-witnessed: with {@code TileOxygenVent#isMaintainingAtmosphere} at {@code return
     * zone.isSealed();} also requiring {@code hasFluid} — the gate as it stood before 2026-10-05 —
     * "a room whose vent has no oxygen must still be breathed down: CO2 0 -&gt; 0, one breath is
     * 105263", the control green, 2026-10-05.</p>
     */
    @Test
    public void aRoomWhoseVentHasNoOxygenIsStillBreathedDown() throws Exception {
        int cx = stand("a sealed room whose vent has no oxygen to give");
        buildSealableRoom(cx);
        placeVent(cx);
        injectEnergy(cx, 1_000_000);
        forceTickAndReseal(cx);

        Reply sealed = ventInfo(cx);
        requireArranged("the room must be a sealed zone whose vent holds no oxygen: " + sealed,
                sealed.bool("isSealed") && sealed.integer("fluidAmount") == 0
                        && sealed.integer("blobSize") > 0 && "zone".equals(sealed.text("airSource")));
        long breath = configValue("lifeSupportRespirationRate") / sealed.integer("blobSize");
        requireArranged("one breath must move a measurable amount of gas in this room: " + breath,
                breath > 0L);

        // The breathing player, standing in the room.
        arrange("stellurgytest player ensure-fake 0 " + cx + " " + (cyBase + 1) + " " + czBase);

        long before = ventInfo(cx).longInteger("airCO2");
        breathe(40);
        long after = ventInfo(cx).longInteger("airCO2");

        // CONTROL: the same room with oxygen in the tank, where the vent supplies it.
        injectOxygen(cx, 1000);
        forceTick(cx, 1);
        long controlBefore = ventInfo(cx).longInteger("airCO2");
        breathe(40);
        long controlAfter = ventInfo(cx).longInteger("airCO2");
        requireArranged("the probe's player must breathe in this room once the vent is supplied"
                        + " (CO2 " + controlBefore + " -> " + controlAfter + ", one breath " + breath + ")",
                controlAfter - controlBefore >= breath);

        assertTrue("a room whose vent has no oxygen must still be breathed down: CO2 " + before
                + " -> " + after + ", one breath is " + breath, after - before >= breath);
    }

    /**
     * A vent pays its tank only for oxygen it actually lets into the room. A room already at sea
     * level lacks nothing, so a vent standing in one spends nothing, however long it runs.
     *
     * <p>Fresh air here is the world's own: this room stands in the overworld, whose outdoor air is
     * sea-level Earth air, and a new zone holds what was in its place — which the reading at the head
     * confirms rather than assumes.</p>
     *
     * <p>red-witnessed: with {@code TileOxygenVent#supplyOxygen} draining
     * {@code ceil(volume * getGasUsageMultiplier())} every tick before {@code if (missing <= 0L)} —
     * the running cost the vent paid before 2026-10-05 — "a vent must spend nothing on a room that
     * lacks nothing: … expected:&lt;993&gt; but was:&lt;891&gt;", 2026-10-05. PAYS WHEN LACKING —
     * {@code TileOxygenVent#supplyOxygen} at {@code if (missing <= 0L)} returning whatever the room
     * lacks: "and the same vent must pay out of its tank once the room lacks oxygen: …
     * \"fluidAmount\":1000", 2026-10-05.</p>
     */
    @Test
    public void aVentSpendsNothingOnARoomThatLacksNothing() throws Exception {
        int cx = stand("a sealed room already at sea level, with a full vent");
        buildSealableRoom(cx);
        placeVent(cx);
        injectEnergy(cx, 1_000_000);
        injectOxygen(cx, 1000);
        forceTickAndReseal(cx);

        Reply full = ventInfo(cx);
        requireArranged("the room must be a sealed zone at sea-level oxygen: " + full,
                full.bool("isSealed") && full.longInteger("airO2") == ppm(210_000));
        int tankBefore = full.integer("fluidAmount");

        forceTick(cx, 100);

        Reply after = ventInfo(cx);
        assertEquals("a vent must spend nothing on a room that lacks nothing: " + after,
                tankBefore, after.integer("fluidAmount"));

        // The same tank, read the same way, once the room DOES lack oxygen: it pays. Without this
        // half "nothing was spent" would also be what a vent that never spends looks like.
        arrange("stellurgytest vent setair " + dim + " " + cx + " " + cyBase + " " + czBase
                + " " + ppm(790_000) + " " + ppm(150_000) + " 0");
        forceTick(cx, 20);
        Reply lacking = ventInfo(cx);
        assertTrue("and the same vent must pay out of its tank once the room lacks oxygen: " + lacking,
                lacking.integer("fluidAmount") < tankBefore
                        && lacking.longInteger("airO2") > ppm(150_000));
    }

    /**
     * A room sealed where there is no air holds no air. Sealing a volume closes it; it does not fill
     * it, so on an airless world a new zone starts as vacuum and has only what a supply puts in.
     *
     * <p>Built on Luna, the shared server's airless moon, found by name. The site is this scenario's
     * own plot, read in Luna's world: the allocator's horizontal non-overlap does not depend on the
     * dimension.</p>
     *
     * <p>red-witnessed: with {@code SealedZone#airAround} at {@code return around == null ?
     * AirState.vacuum() : around.copy();} answering {@code AirState.earthLike()} — a new zone full of
     * sea-level air, as before 2026-10-05 — "a room sealed on an airless world must hold no oxygen: …
     * \"airO2\":210000000", 2026-10-05. NO AIR AT ALL — the same method answering 79 % nitrogen and no
     * oxygen: "and no air at all: … expected:&lt;0&gt; but was:&lt;79&gt;", 2026-10-05.
     * SUPPLIED — {@code TileOxygenVent#supplyOxygen} at {@code if (missing <= 0L)} returning whatever
     * the room lacks: "oxygen must reach the room once its vent's tank pays for it: …
     * \"fluidAmount\":1000", 2026-10-05.</p>
     */
    @Test
    public void aRoomSealedOnAnAirlessWorldStartsAsVacuum() throws Exception {
        Reply luna = ask("stellurgytest planet named Luna");
        requireArranged("the shared server must have a world named Luna: " + luna,
                luna.arrayLength("dims") == 1);
        int lunaDim = (int) luna.arrayNumber("dims", 0);
        int outdoors = planetIntField(lunaDim, "atmosphereDensity");
        requireArranged("Luna must keep no air (atmosphereDensity " + outdoors + ")", outdoors == 0);

        FixtureSite plot = site();
        // Keeps the DIMENSION loaded, putting the probe's player beside the room; nobody breathes,
        // because nothing drives his living update here. It does not keep the room's CHUNKS: the
        // player is never added to the world's chunk map, and a world nobody watches that cannot be
        // respawned in queues every chunk for unloading each tick (`PlayerChunkMap#tick`). A room
        // unloaded between two probe calls comes back as a fresh vent that has not sealed yet.
        arrange("stellurgytest player ensure-fake " + lunaDim + " " + plot.x + " " + (plot.y + 8) + " "
                + plot.z);
        java.util.List<String> heldChunks = new java.util.ArrayList<>();
        for (int chunkX = plot.x >> 4; chunkX <= (plot.x + 4) >> 4; chunkX++) {
            for (int chunkZ = plot.z >> 4; chunkZ <= (plot.z + 4) >> 4; chunkZ++) {
                String chunk = lunaDim + " " + chunkX + " " + chunkZ;
                arrange("stellurgytest chunk forceload " + chunk);
                heldChunks.add(chunk);
            }
        }
        try {
            roomOnLunaStartsAsVacuum(plot, lunaDim);
        } finally {
            for (String chunk : heldChunks) {
                arrange("stellurgytest chunk release " + chunk);
            }
        }
    }

    private void roomOnLunaStartsAsVacuum(FixtureSite plot, int lunaDim) throws Exception {
        FixtureSite lunar = FixtureSite.openAir(lunaDim, plot.x, plot.z);
        lunar.requireClear(this::exec, 4, 8, "a sealed room on Luna");
        dim = lunaDim;
        cyBase = plot.y + 2;
        czBase = plot.z + 2;
        int cx = plot.x + 2;
        buildSealableRoom(cx);
        placeVent(cx);
        injectEnergy(cx, 1_000_000);
        forceTickAndReseal(cx);

        Reply sealed = ventInfo(cx);
        requireArranged("the room must be a sealed zone: " + sealed,
                sealed.bool("isSealed") && "zone".equals(sealed.text("airSource")));
        assertEquals("a room sealed on an airless world must hold no oxygen: " + sealed,
                0L, sealed.longInteger("airO2"));
        assertEquals("and no air at all: " + sealed, 0L, sealed.longInteger("airPressure"));

        // The same reading once a supply pays: oxygen arrives, and only what the tank gave. Without
        // this half a zero would also be what a probe that cannot see this zone's air reads.
        int given = 1000;
        injectOxygen(cx, given);
        forceTick(cx, 20);
        Reply supplied = ventInfo(cx);
        assertTrue("oxygen must reach the room once its vent's tank pays for it: " + supplied,
                supplied.longInteger("airO2") > 0L && supplied.integer("fluidAmount") < given);
    }

    /** Raise the probe player's living update for {@code ticks} server ticks, and wait them out. */
    private void breathe(int ticks) throws Exception {
        arrange("stellurgytest player tick-living " + ticks);
        GameTicks.advance(client(), GameTicks.server(), ticks + 2);
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
        arrange("stellurgytest fill " + dim + " " + (cx - 2) + " " + (by - 1) + " " + (bz - 2)
                + " " + (cx + 2) + " " + by + " " + (bz + 2) + " minecraft:stone");
        for (int yy = by + 1; yy <= by + 2; yy++) {
            arrange("stellurgytest fill " + dim + " " + (cx - 2) + " " + yy + " " + (bz - 2)
                    + " " + (cx + 2) + " " + yy + " " + (bz + 2) + " minecraft:stone");
            arrange("stellurgytest fill " + dim + " " + (cx - 1) + " " + yy + " " + (bz - 1)
                    + " " + (cx + 1) + " " + yy + " " + (bz + 1) + " minecraft:air");
        }
        arrange("stellurgytest fill " + dim + " " + (cx - 2) + " " + (by + 3) + " " + (bz - 2)
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
        Reply resp = arrange("stellurgytest place " + dim + " " + x + " " + cyBase + " " + czBase + " " + block);
        assertTrue(block + " place failed: " + resp, resp.bool("placed"));
    }

    private void injectEnergy(int cx, int amount) throws Exception {
        injectEnergyAt(cx, amount);
    }

    private void injectEnergyAt(int x, int amount) throws Exception {
        arrange("stellurgytest energy inject " + dim + " " + x + " " + cyBase + " " + czBase + " " + amount);
    }

    private void injectOxygen(int cx, int amount) throws Exception {
        arrange("stellurgytest fluid inject " + dim + " " + cx + " " + cyBase + " " + czBase
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
        arrange("stellurgytest tile force-tick " + dim + " " + x + " " + cyBase + " " + czBase + " " + ticks);
    }

    private void forceTickAndReseal(int cx) throws Exception {
        forceTick(cx, 1);
        arrange("stellurgytest vent reseal " + dim + " " + cx + " " + cyBase + " " + czBase);
        forceTick(cx, 5);
    }

    private Reply ventInfo(int cx) throws Exception {
        return ask("stellurgytest vent info " + dim + " " + cx + " " + cyBase + " " + czBase);
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

    // ---- tier 2: scrubbers beside an oxygen vent -------------------------------------------------
    //
    // The room these scenarios stand in: a stone shell on a two-layer floor, its upper floor layer
    // holding the vent with a scrubber on either side of it. The second layer is there so the room
    // seals whatever the seal check makes of a scrubber block: a scrubber it counts as open air is a
    // pocket closed by the layer below, not a hole.

    /** A position in a T2 room, relative to its site, in the probe's "dim x y z" form. */
    private static String t2At(FixtureSite site, int dx, int dy, int dz) {
        return site.dim + " " + (site.x + dx) + " " + (site.y + dy) + " " + (site.z + dz);
    }

    private static String t2Vent(FixtureSite site) {
        return t2At(site, 2, 2, 2);
    }

    private static String[] t2Scrubbers(FixtureSite site) {
        return new String[]{t2At(site, 1, 2, 2), t2At(site, 3, 2, 2)};
    }

    /**
     * Build the room with its vent and both scrubbers, give the vent power and a tank of oxygen, tick
     * it once so it holds a zone, and seal it.
     *
     * @return the zone's volume in blocks, as the seal check measured it
     */
    private int buildSealedT2Room(FixtureSite site) throws Exception {
        arrange("stellurgytest fill " + t2At(site, 0, 1, 0) + " " + (site.x + 4) + " " + (site.y + 6)
                + " " + (site.z + 4) + " minecraft:stone");
        arrange("stellurgytest fill " + t2At(site, 1, 3, 1) + " " + (site.x + 3) + " " + (site.y + 5)
                + " " + (site.z + 3) + " minecraft:air");
        String vent = t2Vent(site);
        arrange("stellurgytest fill " + vent + " " + vent.substring(vent.indexOf(' ') + 1)
                + " stellurgy:oxygenvent");
        for (String scrubber : t2Scrubbers(site)) {
            arrange("stellurgytest fill " + scrubber + " " + scrubber.substring(scrubber.indexOf(' ') + 1)
                    + " stellurgy:oxygenscrubber");
        }
        arrange("stellurgytest energy inject " + vent + " 1000");
        arrange("stellurgytest fluid inject " + vent + " oxygen 1000");
        arrange("stellurgytest tile force-tick " + vent + " 1");
        Reply sealed = arrange("stellurgytest vent reseal " + vent);
        requireArranged("the T2 room must seal before its scrubbers are asked anything: " + sealed,
                sealed.bool("sealed") && sealed.integer("blobSize") > 0);
        return sealed.integer("blobSize");
    }

    private void chargeT2Cartridges(FixtureSite site) throws Exception {
        for (String scrubber : t2Scrubbers(site)) {
            arrange("stellurgytest hatch fill " + scrubber + " 0 stellurgy:carbonscrubbercartridge 1 0");
        }
    }

    /**
     * Run the vent for whole seconds of world, topping its power buffer up before each. A working
     * scrubber costs the vent power every tick, and a buffer charged once would brown the room out
     * part-way and drop the zone — which would end the experiment, not answer it.
     */
    private void runT2Seconds(FixtureSite site, int seconds) throws Exception {
        String vent = t2Vent(site);
        // EXPERIMENT: the dose is whole seconds of the vent running; the reads after it compare
        // amounts, and no record marks "a scrubber drew" for a link to close on.
        for (int i = 0; i < seconds; i++) {
            arrange("stellurgytest energy inject " + vent + " 1000");
            arrange("stellurgytest tile force-tick " + vent + " 20");
        }
    }

    /** The cartridge damage of the scrubber at this position: the charges it has spent. */
    private int spentCharges(String scrubber) throws Exception {
        return ask("stellurgytest hatch read " + scrubber).element("slots", "slot", "0").integer("meta");
    }

    /**
     * Tier 2: a scrubber beside a sealed vent takes its room's carbon dioxide out, and its cartridge
     * pays exactly the charges that CO2 is worth; with no cartridge it takes nothing.
     *
     * <p>The CO2 is put in by the probe rather than breathed in: the subject is what the scrubber
     * decides about the room's CO2, and with nothing breathing in the room every change in it is the
     * scrubber's. The empty-cartridge half is the control that the same room, vent and scrubbers draw
     * nothing until a cartridge is there to pay.</p>
     *
     * <p>red-witnessed: one inversion per verdict, 2026-10-05. NO CARTRIDGE — {@code
     * TileCO2Scrubber#absorb} at {@code wanted = Math.min(wanted, remainingCharges() * perCharge -
     * absorbedUnpaid);} removed with its {@code if}: "a scrubber with no cartridge must take no CO2 out
     * of its room expected:&lt;5000000&gt; but was:&lt;4714288&gt;". TAKES CO2 — {@code
     * TileOxygenVent#scrub} at {@code if (scrubber.absorb(air, volume))} never calling it, which is
     * HEAD's behaviour: "a charged scrubber must take CO2 out of its sealed room: 5000000 -&gt; 5000000
     * over 21 s". PAYS — {@code TileCO2Scrubber#absorb} at {@code while (absorbedUnpaid >= perCharge
     * && useCharge())} never entered: "the cartridges must pay for the CO2 they took, 20000000 a
     * charge: between 91999264 and 91999264 drawn, but 0 + 0 charges spent". EACH — {@code
     * TileOxygenVent#scrub} breaking out of its loop after the first scrubber that absorbed: "and each
     * cartridge must have spent a charge for its share (0, 2)".</p>
     */
    @Test
    public void aScrubberTakesItsRoomsCarbonDioxideAndPaysForItInCharges() throws Exception {
        FixtureSite site = clearedSite(0, 6, "a sealed T2 room: a vent with a scrubber either side");
        int volume = buildSealedT2Room(site);
        String vent = t2Vent(site);
        arrange("stellurgytest vent setair " + vent + " " + ppm(785_000) + " " + ppm(210_000)
                + " " + ppm(5_000));

        long beforeEmpty = ask("stellurgytest vent info " + vent).longInteger("airCO2");
        runT2Seconds(site, 2);
        long afterEmpty = ask("stellurgytest vent info " + vent).longInteger("airCO2");
        assertEquals("a scrubber with no cartridge must take no CO2 out of its room", beforeEmpty, afterEmpty);

        long rate = arrange("stellurgytest config get lifeSupportScrubberRate").longInteger("value");
        long perCharge = arrange("stellurgytest config get lifeSupportScrubberCo2PerCharge").longInteger("value");
        // Each second a scrubber takes rate/volume of partial pressure, a little under `rate` of gas,
        // so twice perCharge/rate seconds is worth at least one charge to each cartridge.
        int seconds = (int) (2 * perCharge / rate) + 1;
        System.out.println("[T2] volume=" + volume + " rate=" + rate + " perCharge=" + perCharge
                + " seconds=" + seconds + " co2AtCharge=" + afterEmpty);
        // The room held afterEmpty when the cartridges went in — nothing is drawn without one, as the
        // half above just showed — so every draw from here on is measured from it.
        chargeT2Cartridges(site);
        runT2Seconds(site, seconds);
        // WINDOW: the vent may also tick between probe calls, so the charges are read between two
        // readings of the room and each bound below names the reading it rests on.
        long co2BeforeCharges = ask("stellurgytest vent info " + vent).longInteger("airCO2");
        int spentA = spentCharges(t2Scrubbers(site)[0]);
        int spentB = spentCharges(t2Scrubbers(site)[1]);
        long co2AfterCharges = ask("stellurgytest vent info " + vent).longInteger("airCO2");

        assertTrue("a charged scrubber must take CO2 out of its sealed room: " + afterEmpty + " -> "
                + co2BeforeCharges + " over " + seconds + " s", co2BeforeCharges < afterEmpty);
        // The gas the two drew by each reading; each cartridge carries its own unpaid remainder below
        // one charge, so together they have spent the whole charges of the total, or one fewer.
        long drawnAtLeast = (afterEmpty - co2BeforeCharges) * volume;
        long drawnAtMost = (afterEmpty - co2AfterCharges) * volume;
        int spent = spentA + spentB;
        assertTrue("the cartridges must pay for the CO2 they took, " + perCharge + " a charge: between "
                        + drawnAtLeast + " and " + drawnAtMost + " drawn, but " + spentA + " + " + spentB
                        + " charges spent",
                spent >= drawnAtLeast / perCharge - 1 && spent <= drawnAtMost / perCharge);
        assertTrue("and each cartridge must have spent a charge for its share (" + spentA + ", " + spentB
                + ")", spentA >= 1 && spentB >= 1);
    }

    /**
     * A sealed room whose chunk is saved, dropped and read back keeps its air: the reloaded vent
     * seals its room again and nothing is let out while it does.
     *
     * <p>The air is a mix no world has outdoors, so a zone that came back as the air around it rather
     * than as its saved air could not pass either. Nitrogen and CO2 are what is read because the vent
     * supplies neither: a loss of them can only be air let out, while oxygen would be topped up from
     * the tank and hide one.</p>
     *
     * <p>red-witnessed: with {@code SealedZone#checkSeal} at {@code if (!ok && handler.isFilling(owner))}
     * never taken — the off-thread fill's "not yet" read as "open", which is what HEAD does: "a
     * reloaded sealed room must keep its nitrogen and CO2: … \"airN2\":700000000 … -&gt; …
     * \"airN2\":450000000,\"airO2\":1178562,\"airCO2\":0,\"airPressure\":45", 2026-10-05.</p>
     */
    @Test
    public void aSealedRoomKeepsItsAirAcrossAChunkReload() throws Exception {
        FixtureSite site = clearedSite(0, 6, "a sealed room whose chunk is reloaded");
        buildSealedT2Room(site);
        String vent = t2Vent(site);
        arrange("stellurgytest vent setair " + vent + " " + ppm(700_000) + " " + ppm(210_000)
                + " " + ppm(90_000));
        Reply before = ask("stellurgytest vent info " + vent);

        int ventX = site.x + 2, ventZ = site.z + 2;
        Reply cycled = arrange("stellurgytest chunk cycle " + site.dim + " " + (ventX >> 4) + " " + (ventZ >> 4));
        requireArranged("the vent's chunk must really leave memory and come back from disk: " + cycled,
                cycled.bool("dropped") && cycled.bool("reloaded") && !cycled.bool("sameInstance"));
        // EXPERIMENT: six seconds of the reloaded vent running, longer than one whole seal-check
        // interval, so a room that comes back unsealed has had time to be found sealed again.
        runT2Seconds(site, 6);
        Reply after = ask("stellurgytest vent info " + vent);

        requireArranged("the reloaded vent must have sealed its room again: " + after, after.bool("isSealed"));
        assertTrue("a reloaded sealed room must keep its nitrogen and CO2: " + before + " -> " + after,
                before.longInteger("airN2") == after.longInteger("airN2")
                        && before.longInteger("airCO2") == after.longInteger("airCO2"));
    }

    /**
     * Scrubbers work on a room's CO2 and leave the vent's oxygen alone: a vent with two charged
     * scrubbers still tops up a room that lacks oxygen, and pays its tank for it.
     *
     * <p>red-witnessed: one inversion per verdict, 2026-10-05. SUPPLIES — {@code
     * TileOxygenVent#supplyOxygen} at {@code long rate = (long) Math.ceil(volume * FLOW_PER_BLOCK}
     * computed from {@code runningCostPerBlock()} instead, the scrubber-cut rate HEAD used: "a vent with
     * two scrubbers must still give oxygen to a room that lacks it: 100000000 -&gt; 100000000". PAYS —
     * {@code TileOxygenVent#supplyOxygen} at {@code FluidStack paid = this.drain((int)
     * Math.min(Integer.MAX_VALUE, cost), true);} draining with {@code false}: "and pay its tank for it:
     * 1000 -&gt; 1000".</p>
     */
    @Test
    public void aVentWithTwoScrubbersStillSuppliesOxygen() throws Exception {
        FixtureSite site = clearedSite(0, 6, "a sealed T2 room short of oxygen");
        buildSealedT2Room(site);
        chargeT2Cartridges(site);
        String vent = t2Vent(site);
        arrange("stellurgytest vent setair " + vent + " " + ppm(790_000) + " " + ppm(100_000) + " 0");

        Reply before = ask("stellurgytest vent info " + vent);
        runT2Seconds(site, 1);
        Reply after = ask("stellurgytest vent info " + vent);

        assertTrue("a vent with two scrubbers must still give oxygen to a room that lacks it: "
                        + before.longInteger("airO2") + " -> " + after.longInteger("airO2"),
                after.longInteger("airO2") > before.longInteger("airO2"));
        assertTrue("and pay its tank for it: " + before.integer("fluidAmount") + " -> "
                        + after.integer("fluidAmount"),
                after.integer("fluidAmount") < before.integer("fluidAmount"));
    }

    /**
     * With {@code lifeSupportZones} off the vent is the classic one: charged scrubbers cut its running
     * cost, and the same scrubbers with their cartridges gone stop cutting it.
     *
     * <p>red-witnessed: one inversion per verdict, 2026-10-05. CUT — {@code
     * TileOxygenVent#runningCostPerBlock} at {@code Math.max(FLOW_PER_BLOCK - numScrubbers *
     * FLOW_SAVED_PER_SCRUBBER, 0)} with no scrubbers counted: "two charged scrubbers must cut a classic
     * vent's running cost to nothing expected:&lt;997&gt; but was:&lt;974&gt;". UNCUT WHEN EMPTY —
     * {@code TileOxygenVent#chargeScrubbers} at {@code numScrubbers = scrubber.useCharge() ? numScrubbers
     * + 1 : numScrubbers;} spending the charge without recounting: "and once their cartridges are gone
     * the vent must pay its running cost again: 1000 -&gt; 1000".</p>
     */
    @Test
    public void withZonesOffChargedScrubbersCutTheVentsRunningCost() throws Exception {
        FixtureSite site = clearedSite(0, 6, "a sealed T2 room run the classic way");
        arrange("stellurgytest config set lifeSupportZones false");
        try {
            buildSealedT2Room(site);
            chargeT2Cartridges(site);
            String vent = t2Vent(site);

            int beforeCharged = ask("stellurgytest vent info " + vent).integer("fluidAmount");
            runT2Seconds(site, 1);
            int afterCharged = ask("stellurgytest vent info " + vent).integer("fluidAmount");
            assertEquals("two charged scrubbers must cut a classic vent's running cost to nothing",
                    beforeCharged, afterCharged);

            for (String scrubber : t2Scrubbers(site)) {
                arrange("stellurgytest hatch fill " + scrubber + " 0 minecraft:air 1 0");
            }
            // The vent counts its charged scrubbers once per charge interval; ten seconds covers one.
            runT2Seconds(site, 10);
            int beforeEmpty = ask("stellurgytest vent info " + vent).integer("fluidAmount");
            runT2Seconds(site, 1);
            int afterEmpty = ask("stellurgytest vent info " + vent).integer("fluidAmount");
            assertTrue("and once their cartridges are gone the vent must pay its running cost again: "
                    + beforeEmpty + " -> " + afterEmpty, afterEmpty < beforeEmpty);
        } finally {
            arrange("stellurgytest config set lifeSupportZones true");
        }
    }
}
