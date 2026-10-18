package dev.stannismod.stellurgy.test.server;

import dev.stannismod.stellurgy.test.Events;
import dev.stannismod.stellurgy.test.GameTicks;
import dev.stannismod.stellurgy.test.Reply;
import dev.stannismod.stellurgy.test.Weapons;
import dev.stannismod.stellurgy.test.contract.RedWitness;
import dev.stannismod.stellurgy.weapon.TurretMechanism;
import org.junit.Test;
import org.valkyrienskies.mod.common.ships.chunk_claims.ShipChunkAllocator;

import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/**
 * A gun with nothing attached to it.
 *
 * <p>No cable, no console, no network — the configuration a player who has just built their first
 * turret is in, and the one a design that leans on a control network is most likely to leave broken.
 * Everything asserted here is about that gun alone: that its numbers come from what was built around
 * it, that it fires at what it was pointed at, and that it stops firing for reasons it states. If a
 * later wave makes any of this depend on a network being present, these go red — which is the point
 * of writing them before the network has a console at all.</p>
 *
 * <p>Every wait is on the gun's own records ({@code MixinTileTurretEvents}): the build it counted,
 * the mount's arrival, the fire decision, the round that left, the launch that was refused.</p>
 */
public class TurretStandaloneTest extends AbstractSharedServerTest {

    /** This class's own site, clear of the other server scenarios. */
    private static final int X = 9400, Y = 80, Z = 9400;

    /** The reference gun's part count: four barrels, two feeds, two cooling jackets. */
    private static final int PARTS = 8;

    /**
     * Inside the region Valkyrien Skies allocates ship blocks in, DERIVED from the allocator rather
     * than written down here.
     *
     * <p>It was a literal (block X 5 120 400) until the shipyard moved out to make room for the
     * universe's cell bound, and the literal then named ordinary world coordinates: the gun placed
     * there was not aboard anything, it assembled and ticked exactly as a gun on the ground should,
     * and the test reported that as production having stopped waiting. Where the shipyard IS belongs
     * to the allocator; what this test claims is only that a gun inside it does nothing.</p>
     * A constant: an int.
     */
    private static final int SHIPYARD_X =
            (ShipChunkAllocator.CHUNK_X_START << 4) + 400;

    /** This class's reader of the server's ordered event log. */
    private final Events events =
            new Events(c -> read(c).raw(), ticks -> GameTicks.advance(client(), GameTicks.server(), ticks), evictionReports());

    @RedWitness(verdict = "fires", member = "TileTurret#update",
            at = "if (!onTarget || isHoldingFire() || !canFireNow())",
            replacedBy = "if (!onTarget || isHoldingFire() || !canFireNow() || world != null)",
            fails = "a gun with a target, a charge and no network never fired", on = "2026-10-18")
    @RedWitness(verdict = "a muzzle speed", member = "GunSpec#getMuzzleSpeed",
            at = "return muzzleSpeed;",
            replacedBy = "return 0.0D;",
            fails = "a built gun must have a muzzle speed", on = "2026-10-18")
    @RedWitness(verdict = "names the round", member = "TileTurret#launch",
            at = "lastShotId = id;",
            replacedBy = "",
            fails = "a gun that fired must name the round it fired", on = "2026-10-18")
    @Test
    public void aGunWithNoNetworkFiresAtWhatItWasPointedAt() throws Exception {
        int bx = X;
        buildSite(bx);
        long built = events.markInstrumented();
        buildGun(bx);

        Weapons.awaitAssembled(events, built, bx, Y, Z, PARTS,
                "the gun never counted every part placed as one operable gun");
        Reply gun = read(bx);
        assertTrue("a built gun must have a muzzle speed: " + gun, gun.number("muzzleSpeed") > 0.0D);

        charge(bx);
        // A point 40 blocks away, level with the mount: reachable, and nothing of the gun's own is
        // in the way.
        long aimed = events.mark();
        target(bx, (bx + 40.5D) + " " + (Y + 0.5D) + " " + (Z + 0.5D));
        String fired = Weapons.awaitFired(events, aimed, bx, Y, Z,
                "a gun with a target, a charge and no network never fired");
        assertTrue("a gun that fired must name the round it fired: " + fired,
                Events.number(fired, "shot") > 0);
    }

    /**
     * The round is a real one: it exists in the substrate, it is going the way the gun is pointing,
     * and it is worth what the build says it is worth.
     *
     * <p>NOT witnessed: "a gun aimed straight up fired something that is not going up". The attempt —
     * {@code TurretFireControl.java:200} handing the world a direction with its y negated — sent the
     * bore into the gun's own barrel and the line of fire refused it, so the gun never fired and the
     * run ended at the wait for its round. And the two speed verdicts above it leave almost nothing
     * for it to catch alone: a round launched level or downward is FASTER than its muzzle after any
     * ticks of gravity and reds the first of them, so only an upward angle shallower than
     * gravity * age / muzzle speed reaches this line, which depends on the age at the read.</p>
     */
    @RedWitness(verdict = "worth", member = "TurretFireControl#fire",
            at = "spec.getProjectileMass(), spec.getLifetimeTicks(), spec.getImpactEnergy(),",
            replacedBy = "spec.getProjectileMass(), spec.getLifetimeTicks(), spec.getImpactEnergy() / 2,",
            fails = "the round is not worth what the build says it is worth", on = "2026-10-18")
    @RedWitness(verdict = "the round it reports", member = "TileTurret#launch",
            at = "lastShotId = id;",
            replacedBy = "",
            fails = "the gun reported a shot the substrate does not have", on = "2026-10-18")
    @Test
    public void theRoundItFiresIsTheRoundItsBuildDescribes() throws Exception {
        int bx = X + 100;
        buildSite(bx);
        long built = events.markInstrumented();
        buildGun(bx);
        Weapons.awaitAssembled(events, built, bx, Y, Z, PARTS, "the gun never assembled");
        charge(bx);
        Reply gun = read(bx);
        int declaredEnergy = gun.integer("impactEnergy");
        double declaredSpeed = gun.number("muzzleSpeed");

        // Straight up: nothing to hit, so the round is still in the air to be read.
        long aimed = events.mark();
        target(bx, (bx + 0.5D) + " " + (Y + 200.5D) + " " + (Z + 0.5D));
        long shotId = (long) Events.number(Weapons.awaitFired(events, aimed, bx, Y, Z,
                "the gun aimed straight up never fired"), "shot");

        Reply read = read("stellurgytest shot read 0 " + shotId).requireOk("read the round");
        assertTrue("the gun reported a shot the substrate does not have: " + read, read.bool("present"));
        Reply shot = Reply.of("the round", read.object("shot"));
        assertEquals("the round is not worth what the build says it is worth: " + shot,
                declaredEnergy, shot.integer("energy"));
        // Not an equality: by the time a test can read it, the round has been in the air for a few
        // ticks and the world's gravity has been acting on it — which is the substrate doing its
        // job. What is pinned is that it LEFT at the build's muzzle speed and that nothing other
        // than the environment the round itself carries has touched it since. The two small
        // tolerances are float arithmetic over a handful of ticks, not a physical allowance.
        int age = shot.integer("age");
        double speed = shot.number("speed");
        double gravityLoss = shot.number("gravity") * age;
        assertTrue("the round is faster than the build can fire (" + speed + " vs " + declaredSpeed
                + "): " + shot, speed <= declaredSpeed + 1.0E-6D);
        assertTrue("the round is slower than gravity alone can explain (" + speed + " after " + age
                + " ticks, muzzle " + declaredSpeed + "): something other than the declared"
                + " environment is acting on it: " + shot,
                speed >= declaredSpeed - gravityLoss - 1.0E-3D);
        assertTrue("a gun aimed straight up fired something that is not going up: " + shot,
                shot.number("vy") > 0.0D);
    }

    /**
     * A controller with nothing built around it is not a gun and does not fire — and the same
     * controller, given its parts, does, which is what makes its silence evidence.
     */
    @RedWitness(verdict = "not operable", member = "GunSpec#isOperable",
            at = "if (partCount <= 0) { return false; }",
            replacedBy = "if (partCount <= 0) { return true; }",
            fails = "a bare controller reports itself operable", on = "2026-10-18")
    @RedWitness(verdict = "not permitted", member = "TileTurret#canFireNow",
            at = "&& spec.isOperable()",
            replacedBy = "",
            fails = "a bare controller, on its target, was permitted to fire", on = "2026-10-18")
    @Test
    public void anUnbuiltControllerIsNotAGunAndFiresNothing() throws Exception {
        int bx = X + 200;
        buildSite(bx);
        long placed = events.markInstrumented();
        place("stellurgy:turret", bx, Y, Z);
        String bare = events.awaitRecordWithFields(placed, "turret_assembled",
                "the bare controller never walked its build at all", Weapons.ARRANGEMENT_TICKS,
                "pos", Weapons.at(bx, Y, Z));
        assertEquals("a bare controller reports itself operable: " + bare, "false",
                Events.text(bare, "operable"));

        long aimed = events.mark();
        charge(bx);
        target(bx, (bx + 40.5D) + " " + (Y + 0.5D) + " " + (Z + 0.5D));
        // The bare mount still turns (a spec with no parts keeps the builder's default traverse,
        // GunSpec.Builder), so it reaches its target and asks the fire question there — and that
        // answer is the decision this leg is about. A permitted gun launches in the same tick it is
        // permitted, so "no round up to this decision" is the whole of what it did with it.
        String refused = events.awaitRecordWithFields(aimed, "turret_fire_decided",
                "the bare controller never asked the fire question on its target — then its silence"
                        + " below would be about aiming, not about being unbuilt",
                Weapons.SUBJECT_TICKS, "pos", Weapons.at(bx, Y, Z), "caller", "auto", "operable", "false");
        assertEquals("a bare controller, on its target, was permitted to fire: " + refused,
                "false", Events.text(refused, "permitted"));
        long quiet = events.mark();

        buildParts(bx);
        Weapons.awaitAssembled(events, quiet, bx, Y, Z, PARTS, "the controller, given its parts, never"
                + " counted them");
        String fired = Weapons.awaitFired(events, quiet, bx, Y, Z,
                "the same controller given its parts never fired, so its silence above was not about"
                        + " being unbuilt");
        System.out.println("[control] built-to-fired took " + (long) (Events.number(fired, "tick")
                - Events.number(bare, "tick")) + " ticks from the bare walk");
        List<String> whileBare = Weapons.firedSince(events, aimed, bx, Y, Z);
        whileBare.removeIf(round -> Events.number(round, "seq") >= quiet);
        assertTrue("a bare controller fired something (marks " + aimed + ".." + quiet + "): " + whileBare,
                whileBare.isEmpty());
    }

    /**
     * A dead drive is the one failure that stops the shooting as well as the turning.
     *
     * <p>The gun must be ON TARGET when the drive dies, or this cannot fail: a dead mount does not
     * turn, the automatic path returns on "not on target" before it ever asks whether the drive
     * permits firing, and a gun killed while still pointing the wrong way is silent for that reason
     * alone. So the mount is slewed onto its target with a working drive and NO charge — it then asks
     * the fire question every tick and is refused for want of energy — and only then is the drive
     * killed and the gun charged. The fire decision taken after that, with a dead drive, on target,
     * assembled and charged, is the subject; the same gun given its drive back is the control, and
     * the only thing the two legs differ in is the drive.</p>
     */
    @RedWitness(member = "TurretDriveState#permitsFiring",
            at = "return this != DEAD;",
            replacedBy = "return true;",
            fails = "a gun with a dead drive fired, on target and charged", on = "2026-10-18")
    @Test
    public void aDeadDriveStopsTheGunFiring() throws Exception {
        int bx = X + 300;
        String at = bx + "," + Y + "," + Z;
        buildSite(bx);
        long built = events.markInstrumented();
        buildGun(bx);
        Reply.of(arrange("stellurgytest turret target 0 " + bx + " " + Y + " " + Z + " " + (bx + 40.5D) + " "
                + (Y + 0.5D) + " " + (Z + 0.5D)).raw()).requireOk("aim the gun at a point 40 blocks along +X");

        // The link that says the gun is assembled, on target and waiting: the automatic path only asks
        // the fire question once the mount answers "on target". The budget is a deadline for the
        // arrangement, not a verdict: a mount that never gets there fails here, loudly, as setup.
        String waiting = events.awaitRecordWithFields(built, "turret_fire_decided",
                "the gun never reached its target with a working drive, so nothing below would be about"
                        + " the drive", 600,
                "pos", at, "caller", "auto", "drive", "WORKING", "operable", "true", "permitted", "false");
        assertEquals("the gun was waiting for something other than energy: " + waiting, 0,
                (int) Events.number(waiting, "energy"));

        Reply killed = Reply.of(arrange("stellurgytest turret drive 0 " + bx + " " + Y + " " + Z + " DEAD").raw())
                .requireOk("kill the drive");
        assertEquals("the drive state was not the one that was set: " + killed, "DEAD",
                killed.text("drive"));

        long charged = events.mark();
        int energy = Reply.of(arrange("stellurgytest turret charge 0 " + bx + " " + Y + " " + Z).raw())
                .requireOk("charge the gun").integer("energy");
        assertTrue("a full charge left the gun empty, so nothing is being refused for the drive's sake",
                energy > 0);

        String decided = events.awaitRecordWithFields(charged, "turret_fire_decided",
                "the gun never took a fire decision with its dead drive and its charge", 200,
                "pos", at, "caller", "auto", "drive", "DEAD", "operable", "true",
                "energy", String.valueOf(energy));
        String fired = events.since(charged, "turret_fired");
        Events.assertInstrumentRan(fired, "turret_fire_events", "no round left a dead-drive gun");
        assertTrue("a gun with a dead drive fired, on target and charged: " + decided + " | " + fired,
                Events.recordsWhere(fired, "pos", at).isEmpty());
        assertEquals("the dead-drive decision was a permission: " + decided, "false",
                Events.text(decided, "permitted"));

        // The control: the same gun, the same target, the same charge, and a working drive.
        long restored = events.mark();
        Reply.of(arrange("stellurgytest turret drive 0 " + bx + " " + Y + " " + Z + " WORKING").raw())
                .requireOk("give the drive back");
        events.awaitRecordWithFields(restored, "turret_fired",
                "the same gun with its drive given back never fired, so the silence above was not the"
                        + " drive's", 200,
                "pos", at, "drive", "WORKING");
    }

    /**
     * A gun whose own hull is in front of the barrel holds fire instead of demolishing it.
     *
     * <p>Every other scenario in this class mounts the gun in open air, which is exactly the
     * arrangement that cannot exhibit the defect this pins: the muzzle sits a few blocks along the
     * aim and nothing asks what is there, so a turret recessed into a hull shells its own ship one
     * round at a time. The hold is read off the gun's own launch being REFUSED after it was permitted
     * — on target, charged — and the same gun with the wall taken away fires.</p>
     */
    @RedWitness(member = "TurretFireControl#muzzleOf",
            at = "if (StructureCrossing.isBlocked(world, worldMuzzle,",
            replacedBy = "if (worldMuzzle == null && StructureCrossing.isBlocked(world, worldMuzzle,",
            fails = "a gun fired into the structure it is built into", on = "2026-10-18")
    @Test
    public void aGunWithItsOwnHullInFrontOfTheBarrelHoldsFire() throws Exception {
        int bx = X + 400;
        buildSite(bx);
        long built = events.markInstrumented();
        buildGun(bx);
        Weapons.awaitAssembled(events, built, bx, Y, Z, PARTS, "the gun never assembled");
        charge(bx);

        // A wall across the line of fire, just past where the muzzle sits.
        arrange("stellurgytest fill 0 " + (bx + 6) + " " + (Y - 1) + " " + (Z - 2) + " " + (bx + 7) + " "
                + (Y + 2) + " " + (Z + 2) + " minecraft:stone").requireOk("build the wall");
        long walled = events.mark();
        target(bx, (bx + 40.5D) + " " + (Y + 0.5D) + " " + (Z + 0.5D));
        // The gun PERMITTED to fire — on target, charged — launches in that same tick, so once the
        // permission is recorded the launch's own answer is already in the log beside it.
        events.awaitRecordWithFields(walled, "turret_fire_decided",
                "the gun never got onto its target and was never permitted to fire, so its silence"
                        + " would prove nothing", Weapons.SUBJECT_TICKS,
                "pos", Weapons.at(bx, Y, Z), "caller", "auto", "permitted", "true");
        List<String> intoWall = Weapons.firedSince(events, walled, bx, Y, Z);
        assertTrue("a gun fired into the structure it is built into: " + intoWall, intoWall.isEmpty());
        String refusals = events.since(walled, "turret_launch_refused");
        assertTrue("the gun was permitted and neither fired nor had its launch refused: " + refusals,
                !Events.recordsWhere(refusals, "pos", Weapons.at(bx, Y, Z)).isEmpty());

        // The control: take the wall away and the same gun, same target, fires.
        long cleared = events.mark();
        arrange("stellurgytest fill 0 " + (bx + 6) + " " + (Y - 1) + " " + (Z - 2) + " " + (bx + 7) + " "
                + (Y + 2) + " " + (Z + 2) + " minecraft:air").requireOk("clear the wall");
        Weapons.awaitFired(events, cleared, bx, Y, Z, "with the obstruction gone the gun still refuses"
                + " to fire, so the hold was not about the wall");
    }

    /**
     * A gun standing in the shipyard that no ship claims does NOTHING — it does not even count its
     * own build.
     *
     * <p>Valkyrien Skies keeps ship blocks in a far-off region (block X past ~5.12 million), and a
     * ship's chunks load before its ship object exists. In that window every coordinate a machine
     * aboard holds is a shipyard address rather than a place in the world, so there is no partial
     * behaviour that is correct — only waiting. The control is built in the same method at ordinary
     * coordinates: the same eight blocks, placed the same way, assemble and fire — which is also what
     * proves the assembly and fire recorders were live for the silence at the shipyard.</p>
     */
    @RedWitness(member = "TileTurret#update",
            at = "if (VSIntegration.isOnUnnamedShip(world, pos))",
            replacedBy = "if (VSIntegration.isOnUnnamedShip(world, pos) && world == null)",
            fails = "a gun aboard an unnamed ship counted its build", on = "2026-10-18")
    @Test
    public void aGunAboardAnUnnamedShipDoesNothingAtAll() throws Exception {
        int bx = SHIPYARD_X;
        int controlX = X + 600;
        buildSite(bx);
        buildSite(controlX);
        long placed = events.markInstrumented();
        buildGun(bx);
        buildGun(controlX);
        charge(bx);
        charge(controlX);
        target(bx, (bx + 40.5D) + " " + (Y + 0.5D) + " " + (Z + 0.5D));
        target(controlX, (controlX + 40.5D) + " " + (Y + 0.5D) + " " + (Z + 0.5D));

        // The control, and the window's closing link: the ordinary gun assembles and fires.
        Weapons.awaitAssembled(events, placed, controlX, Y, Z, PARTS, "the control gun never assembled");
        Weapons.awaitFired(events, placed, controlX, Y, Z, "the control gun never fired, so the"
                + " silence at the shipyard below would prove nothing");

        // The shipyard gun ticked the whole time — its mount recorded its first tick — and did
        // nothing else.
        String aim = events.since(placed, "turret_aim");
        Events.assertInstrumentRan(aim, "turret_aim_events", "the shipyard gun ticked");
        assertTrue("the gun in the shipyard never ticked, so its silence proves nothing: " + aim,
                !Events.recordsWhere(aim, "pos", Weapons.at(bx, Y, Z)).isEmpty());
        String assembled = events.since(placed, "turret_assembled");
        Events.assertInstrumentRan(assembled, "turret_assembly_events", "the shipyard gun counted nothing");
        assertTrue("a gun aboard an unnamed ship counted its build: it is ticking when it should be"
                + " waiting: " + assembled,
                Events.recordsWhere(assembled, "pos", Weapons.at(bx, Y, Z)).isEmpty());
        assertTrue("a gun aboard an unnamed ship fired",
                Weapons.firedSince(events, placed, bx, Y, Z).isEmpty());
        Reply state = read(bx);
        assertEquals("a gun aboard an unnamed ship turned: " + state, 0.0D, state.number("yaw"), 0.0D);
    }

    /**
     * The manual seam: a gun under a hand chooses no target, obeys the bearing it is given, and fires
     * only when told — on exactly the same conditions the automatic path checks.
     *
     * <p>This is the half of the manned gun that has to exist for the seat and the first-person view
     * to be an addition rather than a rewrite. It is pinned now, while there is nothing driving it,
     * because a seam nobody exercises is a seam that quietly stops working.</p>
     */
    @RedWitness(verdict = "fires only when told", member = "TileTurret#update",
            at = "mechanism.tick(spec.getTraverseDegreesPerTick()); syncCommandIfChanged(); // Nothing",
            replacedBy = "boolean onBearing = mechanism.tick(spec.getTraverseDegreesPerTick()); syncCommandIfChanged(); if (onBearing && canFireNow()) { launch(TurretFireControl.shipIdAt(world, pos)); } // Nothing",
            fails = "the trigger did nothing", on = "2026-10-18")
    @RedWitness(verdict = "the bearing", member = "TileTurret#commandManualBearing",
            at = "mechanism.commandBearing(yaw, pitch);",
            replacedBy = "mechanism.commandBearing(yaw + 30.0D, pitch);",
            fails = "the mount ignored the bearing it was handed", on = "2026-10-18")
    @RedWitness(verdict = "the trigger's round", member = "TileTurret#fireOnce",
            at = "return launch(TurretFireControl.shipIdAt(world, pos));",
            replacedBy = "manualControl = false; boolean fired = launch(TurretFireControl.shipIdAt(world, pos)); manualControl = true; return fired;",
            fails = "the round that left was not the trigger's", on = "2026-10-18")
    @RedWitness(verdict = "back to automatic", member = "TileTurret#update",
            at = "launch(shipId);",
            replacedBy = "manualControl = true; launch(shipId); manualControl = false;",
            fails = "the round after the hand let go was still a manual one", on = "2026-10-18")
    @Test
    public void aGunUnderManualControlIgnoresItsTargetAndFiresOnlyWhenTold() throws Exception {
        int bx = X + 500;
        buildSite(bx);
        long built = events.markInstrumented();
        buildGun(bx);
        Weapons.awaitAssembled(events, built, bx, Y, Z, PARTS, "the gun never assembled");
        charge(bx);

        // A target it WOULD engage on its own, so "did not fire" is about the mode.
        long taken = events.mark();
        target(bx, (bx + 40.5D) + " " + (Y + 0.5D) + " " + (Z + 0.5D));
        Reply manual = arrange("stellurgytest turret manual 0 " + bx + " " + Y + " " + Z + " true")
                .requireOk("take manual control");
        assertTrue("the gun did not enter manual control: " + manual, manual.bool("manual"));

        // It obeys a hand-given bearing...
        long bearing = events.mark();
        arrange("stellurgytest turret bearing 0 " + bx + " " + Y + " " + Z + " -90 0").requireOk("hand it a bearing");
        String arrived = events.awaitRecordWithFields(bearing, "turret_aim",
                "the mount never arrived at the bearing it was handed", Weapons.SUBJECT_TICKS,
                "pos", Weapons.at(bx, Y, Z), "onTarget", "true", "manual", "true");
        assertEquals("the mount ignored the bearing it was handed: " + arrived, -90.0D,
                Events.number(arrived, "yaw"), TurretMechanism.AIM_TOLERANCE_DEGREES);

        // ...and fires when the trigger is pulled, once per pull.
        long pulled = events.mark();
        Reply shot = act("stellurgytest turret fire 0 " + bx + " " + Y + " " + Z).requireOk("pull the trigger");
        assertTrue("the trigger did nothing: " + shot + " state: " + read(bx), shot.bool("fired"));
        List<String> perPull = Weapons.firedSince(events, pulled, bx, Y, Z);
        assertEquals("one pull fired other than one round: " + perPull, 1, perPull.size());
        assertEquals("the round that left was not the trigger's: " + perPull, "true",
                Events.text(perPull.get(0), "manual"));

        // Nothing left the gun on its own while it was under the hand: judged now that the pull
        // above has proved the fire recorder live, over the window from the target to the pull.
        List<String> onItsOwn = Weapons.firedSince(events, taken, bx, Y, Z);
        onItsOwn.removeIf(round -> Events.number(round, "seq") >= pulled);
        assertTrue("a gun in manual control fired on an assigned target by itself: " + onItsOwn,
                onItsOwn.isEmpty());

        // Returning it to automatic re-engages the target it was given.
        long returned = events.mark();
        arrange("stellurgytest turret manual 0 " + bx + " " + Y + " " + Z + " false").requireOk("hand it back");
        charge(bx);
        String resumed = Weapons.awaitFired(events, returned, bx, Y, Z,
                "the gun never went back to firing on its own");
        assertEquals("the round after the hand let go was still a manual one: " + resumed, "false",
                Events.text(resumed, "manual"));
    }

    // ---- scenario construction

    /**
     * The reference gun: a controller with four barrel sections, two feeds and two cooling jackets
     * around it. Every one of them touches the run, which is all the assembly asks of a build.
     */
    private void buildGun(int bx) throws Exception {
        place("stellurgy:turret", bx, Y, Z);
        buildParts(bx);
    }

    private void buildParts(int bx) throws Exception {
        for (int i = 1; i <= 4; i++) {
            place("stellurgy:gunBarrel", bx, Y + i, Z);
        }
        place("stellurgy:gunAmmoFeed", bx + 1, Y, Z);
        place("stellurgy:gunAmmoFeed", bx - 1, Y, Z);
        place("stellurgy:gunCooling", bx, Y, Z + 1);
        place("stellurgy:gunCooling", bx, Y, Z - 1);
    }

    /** Air around the site, and a chunk that stays loaded so the gun's own tile actually ticks. */
    private void buildSite(int bx) throws Exception {
        arrange("stellurgytest chunk warmup 0 " + ((bx - 16) >> 4) + " " + ((Z - 16) >> 4) + " "
                + ((bx + 64) >> 4) + " " + ((Z + 16) >> 4)).requireOk("warm the site's chunks");
        arrange("stellurgytest fill 0 " + (bx - 4) + " " + (Y - 2) + " " + (Z - 4) + " " + (bx + 60) + " "
                + (Y + 12) + " " + (Z + 4) + " minecraft:air").requireOk("clear the site");
        arrange("stellurgytest chunk forceload 0 " + (bx >> 4) + " " + (Z >> 4)).requireOk("hold the chunk");
    }

    private void charge(int bx) throws Exception {
        arrange("stellurgytest turret charge 0 " + bx + " " + Y + " " + Z).requireOk("charge the gun");
    }

    private void target(int bx, String point) throws Exception {
        arrange("stellurgytest turret target 0 " + bx + " " + Y + " " + Z + " " + point).requireOk("aim the gun");
    }

    private Reply read(int bx) throws Exception {
        return read("stellurgytest turret read 0 " + bx + " " + Y + " " + Z).requireOk("read the gun");
    }

    private void place(String block, int x, int y, int z) throws Exception {
        Reply placed = arrange("stellurgytest place 0 " + x + " " + y + " " + z + " " + block);
        assertTrue("failed to place " + block + " at " + x + "," + y + "," + z + ": " + placed,
                placed.bool("placed"));
    }
}
