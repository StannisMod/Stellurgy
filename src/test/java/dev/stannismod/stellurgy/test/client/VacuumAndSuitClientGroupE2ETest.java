package dev.stannismod.stellurgy.test.client;

import com.google.gson.JsonObject;
import org.junit.FixMethodOrder;
import org.junit.Test;
import org.junit.runners.MethodSorters;


import dev.stannismod.stellurgy.test.Events;
import dev.stannismod.stellurgy.test.GameTicks;
import dev.stannismod.stellurgy.test.Reply;

import dev.stannismod.stellurgy.test.Plot;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static dev.stannismod.stellurgy.test.StellurgyTestConstants.ppm;
import dev.stannismod.stellurgy.api.atmosphere.Atmosphere;

/**
 * Vacuum, suits, and the air a player breathes. Nine scenarios, one client.
 *
 * <p>Every member works the same lever — flip the overworld's atmosphere density and watch what
 * happens to a player who is, or is not, wearing something that protects him — and every one of them
 * reads the outcome on the real client: health as the client renders it, the armour NBT the
 * inventory screen draws.</p>
 *
 * <h2>Why these share one harness</h2>
 *
 * <p>Measured 2026-08-07 at 8 forks, from the result XML: 117.6 + 349.4 + 351.3 + 120.2 s across
 * eight client boots — <b>15.6 minutes</b>.</p>
 *
 * <h2>Sharing a GLOBAL mutator, deliberately</h2>
 *
 * <p>Atmosphere density is per-dimension state with no owner, which is exactly the kind of thing the
 * shared-harness rules say does not group. It groups here because of a stronger property: <b>every
 * scenario SETS the density it needs rather than assuming it</b>, and measures that the dimension
 * actually reads that way before it starts its window. A leftover from the scenario before is then
 * overwritten rather than inherited, and a set that has not propagated yet is a ARRANGEMENT
 * failure instead of a silent change of subject. Each also restores the snapshot in a
 * {@code finally}, so a scenario that dies mid-window does not hand the next one a vacuum.</p>
 *
 * <p>The same argument covers the other two globals these scenarios touch: survival mode and
 * {@code naturalRegeneration}. Both are set per scenario; the shared reset additionally puts the
 * game mode and the player's health back, so "the player must start at full health" — a precondition
 * three of these open with — is true by construction rather than by luck.</p>
 *
 * <h2>The fixture geometry changed, and that is the point</h2>
 *
 * <p>All three source classes stood the player at (8.5, 79, 8.5), ordinary overworld terrain height,
 * and each carried an {@code stellurgytest fill … minecraft:air} pre-clear because on some world seeds a
 * hillside filled that volume and the player suffocated — damage that a "vacuum hurts an unsuited
 * player" assertion happily accepts for the wrong reason (the ledger entry for that false green is
 * why the pre-clear exists). Here each scenario builds its own stone platform in open air inside its
 * own plot, so there is no terrain to clear and no seed that can put a hill in it.</p>
 *
 * <h2>What these scenarios WAIT for, and the one thing none of them can see</h2>
 *
 * <p>Every subject here is a link in one chain the atmosphere tick walks: the suit gate decided
 * ({@code suit_immunity_decided}), the suit paid for the decision ({@code suit_air_drained}, carrying
 * the {@code route} — {@code component} for the Stellurgy chest's pressure tank, {@code enchanted} for the
 * vanilla-enchanted suit), the damage landed ({@code living_hurt} with source {@code Vacuum}), the
 * client was told ({@code client_health_updated}). So the waits are event waits, taken from a mark
 * set before the density is flipped. What stays a bounded poll is a persistent VALUE read back —
 * the dimension's density, the client's rendered armour NBT — because those are states, not links.</p>
 *
 * <p><b>The breathable counter-tests have no positive precondition, and cannot be given one.</b>
 * {@code AtmosphereType.AIR} is built with {@code canTick=false}, so in a breathable dimension the
 * atmosphere tick never runs, the suit gate is never asked and NOTHING is recorded — which is
 * precisely the contract, and also why no event can witness that the player was ticked and judged
 * breathable. Their silences rest on the two honesty flags of {@code markInstrumented} (the recorder
 * is subscribed, the test-only mixins were queued) and on the vacuum scenarios beside them, which
 * show the same seams recording when they do fire.</p>
 *
 * <p><b>One path these scenarios deliberately never take.</b> The suit gate is also asked, without a
 * side gate and every single tick, for any living entity that is IN WATER — so a suited swimmer
 * drains air twice as fast as the atmosphere tick alone would explain, and fills a 256-deep event
 * ring in about thirteen seconds. Every scenario here stands its player on a dry stone platform in
 * open air, so every drain in these windows belongs to the atmosphere tick.</p>
 *
 * <p>Source classes, merged verbatim (method names preserved so CI history greps):
 * {@code OxygenSuitClientStateE2ETest}, {@code ItemSpaceArmorUseFluidE2ETest},
 * {@code ItemSpaceChestSubInventoryDrainE2ETest}, {@code GasChargePadFillsPressureTankE2ETest}.</p>
 */
@FixMethodOrder(MethodSorters.NAME_ASCENDING)
public class VacuumAndSuitClientGroupE2ETest extends AbstractSharedClientE2ETest {



    private static final int PAD_Y = Plot.DEFAULT_Y;
    private static final int PAD_DX = 16;
    private static final int PAD_DZ = 16;
    private static final int PAD_EDGE = 8;
    private static final int ROOM_DX = 40;
    private static final int ROOM_DZ = 40;
    private static final int ROOM_Y = Plot.DEFAULT_Y;
    private static final int STAND_DX = PAD_DX + 4;
    private static final int STAND_DZ = PAD_DZ + 4;

    private static final String DENSITY = "atmosphereDensity";
    private static final String CHEST_AIR = "chestAir";
    private static final String CLIENT_HEALTH = "health";

    /**
     * How long one link of the atmosphere tick's chain may take. The vacuum damages on a shared
     * {@code % 10} clock, so eight ticks of it is 80 game ticks; 200 is the budget the health poll
     * this replaced already allowed, kept whole for every link.
     */
    /**
     * Vanilla's own full health, in half-hearts — what a player starts a scenario at.
     *
     * <p>PRODUCTION'S number in the sense that matters: {@code EntityPlayer}'s max health is 20,
     * and every "he must start unhurt" gate in this class is asking for exactly that rather than
     * for a tolerance. It is named once so the four gates cannot drift apart.</p>
     */
    private static final double FULL_HEALTH = 20.0;

    /**
     * A full oxygen tank, in the units {@code chestAir} reports.
     *
     * <p>PRODUCTION'S capacity, restated: the equip verb fills the tank and answers {@code 1000},
     * and every drain assertion below is "less than full". Naming it makes the pair — the equip
     * that fills and the drains that must reduce — one decision instead of five literals.</p>
     */
    private static final int FULL_TANK = 1000;

    private static final int LINK_BUDGET_TICKS = 200;

    /** The window an ABSENCE is asserted over: eight atmosphere ticks, the same 80 the three
     *  counter-tests always used. Its expiry is not a failure — nothing is being waited for. */
    private static final int ABSENCE_WINDOW_TICKS = 80;

    @Override
    protected String subsystem() {
        return "atmosphere-suit";
    }

    // ── shared arrangement ────────────────────────────────────────────────────

    /**
     * Builds this scenario's platform, stands the player on it, strips his armour and drops him to
     * survival — the harness server runs creative, under which {@code AtmosphereNeedsSuit.isImmune}
     * short-circuits regardless of suit and none of these contracts can be observed at all.
     *
     * <p>Survival comes LAST, after the player is measurably standing on the platform: dropping him
     * into survival while he is still falling from the reset's teleport would cost him fall damage
     * that three of these scenarios would read as the subject.</p>
     */
    private void standOnOwnPlatformInSurvival() throws Exception {
        int dim = plot().dim;
        scenario().arranging("build a platform in open air and stand on it");
        String fill = exec("stellurgytest fill " + dim + " " + plot().x(PAD_DX) + " " + PAD_Y + " "
                + plot().z(PAD_DZ) + " " + plot().x(PAD_DX + PAD_EDGE - 1) + " " + PAD_Y + " "
                + plot().z(PAD_DZ + PAD_EDGE - 1) + " minecraft:stone");
        scenario().requireArranged("platform fill must succeed: " + fill,
                Reply.of(fill).ok());
        long standMark = clientEvents().mark();
        exec("tp @a " + (plot().x(STAND_DX) + 0.5) + " " + (PAD_Y + 1) + " "
                + (plot().z(STAND_DZ) + 0.5));
        // Where he STANDS decides what atmosphere he is in, and the readings below are the client's.
        awaitClientPlacedNear(standMark, plot().x(STAND_DX) + 0.5, plot().z(STAND_DZ) + 0.5,
                "the vacuum this scenario is about is the one at the player's own position");

        arrangeProbe("stellurgytest player clear-armor");
        exec("gamerule naturalRegeneration false");
        exec("gamemode survival @a");
        // WINDOW: ten ticks of survival on the spot he was placed, watched for damage — he arrived
        // in creative, so full health is the start of the window by construction, and a spot that
        // hurts (inside a block, over nothing) shows as less at its end.
        bot().waitTicks(10);
        double health = health(bot().reportState());
        scenario().record("healthOnPlatform", health);
        scenario().requireArranged("the player must be standing unhurt on his platform before the"
                + " window opens — anything less means he arrived falling or inside a block, and"
                + " every damage assertion below would be measuring that instead of the vacuum;"
                + " client health=" + health, health >= FULL_HEALTH);
    }

    /** Reads the dim's baseline density so {@link #restoreDim} can put it back. */
    private int snapshotDensity() throws Exception {
        String planet = exec("stellurgytest planet info " + plot().dim);
        return Reply.of("stellurgytest planet info", planet).integer(DENSITY);
    }

    /**
     * Sets the dimension's density and reads it back, as a ARRANGEMENT check.
     *
     * <p>It is a check and not a wait, and this javadoc used to claim otherwise ("lands a tick or two
     * later … a scenario that starts measuring immediately measures the tail of the previous
     * setting"). The probe assigns the field on the command thread and {@code planet info} reads the
     * same field, with the atmosphere TYPE derived from it on every call — so the first read already
     * satisfies the predicate and nothing here can wait for anything. What a scenario actually needs
     * to know is when the new atmosphere first acted ON THIS PLAYER, and that is an event the
     * scenarios below wait for by name ({@code suit_air_drained}, {@code suit_immunity_decided},
     * {@code living_hurt}) after a mark taken before this call.</p>
     */
    private void setDensityAndConfirm(int density, boolean expectBreathable) throws Exception {
        String set = exec("stellurgytest atmosphere set-density " + plot().dim + " " + density);
        scenario().requireArranged("set-density " + density + " failed: " + set,
                Reply.of(set).ok());
        int readBack = snapshotDensity();
        scenario().record("densityReadBack", readBack);
        scenario().requireArranged("the dimension must READ " + (expectBreathable ? "breathable"
                        + " (>=1)" : "vacuum (0)") + " before the window opens; it reads " + readBack,
                expectBreathable ? readBack >= 1 : readBack == 0);
    }

    private void restoreDim(int originalDensity) {
        try {
            exec("stellurgytest atmosphere set-density " + plot().dim + " " + Math.max(originalDensity, 1));
        } catch (Exception ignored) {
            // Teardown only — the next scenario sets the density it needs and proves it took.
        }
        try {
            exec("gamerule naturalRegeneration true");
        } catch (Exception ignored) {
            // Same.
        }
    }

    /** Server-side chest air via the static "air" NBT route ({@code ItemAirUtils}). */
    private int readChestAir() throws Exception {
        String resp = exec("stellurgytest player held-air");
        Reply held = Reply.of("stellurgytest player held-air", resp);
        assertTrue("held-air response must include chestAir: " + resp, held.has(CHEST_AIR));
        return held.integer(CHEST_AIR);
    }

    /**
     * Server-side chest air via the COMPONENT route. {@code ItemSpaceChest} stores its O2 buffer
     * inside an embedded inventory's pressure-tank FluidStacks rather than as a top-level NBT key,
     * so the static-NBT probe reads 0 for it.
     */
    private int readChestAirComponentRoute() throws Exception {
        String resp = exec("stellurgytest player held-air-component-route");
        Reply held = Reply.of("stellurgytest player held-air-component-route", resp);
        assertTrue("held-air-component-route response must include chestAir: " + resp,
                held.has(CHEST_AIR));
        return held.integer(CHEST_AIR);
    }

    /** Vanilla's player container is window 0, and its chest armour slot is index 6 of it. */
    private static final String PLAYER_WINDOW = "0";
    private static final String CHEST_ARMOUR_SLOT = "6";

    /**
     * The client's chest air once its copy of the chest slot has caught up with {@code synced}.
     *
     * <p><b>A read first, then a link.</b> A slot that already reads right is answered at once — an
     * unchanged tag writes no record, so waiting for one there would wait out the budget on the
     * healthy path. Otherwise the wait is for the client's own record of that slot's tag CHANGING
     * ({@code client_slot_tag_set}) since {@code clientMark}, and the air is read once after it. The
     * armour's NBT reaches the client on its own set-slot packet, some ticks after the server-side
     * change a scenario's own link reports, and this is that packet arriving.</p>
     *
     * <p><b>The mark is the caller's to place, and where matters</b>: any earlier change to the slot
     * that is still in flight can close this wait instead of the one meant — the equip's own sync
     * closing a wait for a drain. So a scenario confirms the slot's previous state on the client
     * first, and takes the mark after that.</p>
     */
    private int clientChestAirOnceSynced(long clientMark, java.util.function.IntPredicate synced,
                                         String what) throws Exception {
        int now = clientChestAir();
        if (synced.test(now)) {
            return now;
        }
        clientEvents().awaitRecordWithFields(clientMark, "client_slot_tag_set", what,
                LINK_BUDGET_TICKS, "window", PLAYER_WINDOW, "slot", CHEST_ARMOUR_SLOT);
        return clientChestAir();
    }

    /**
     * CLIENT-rendered chest-slot air: parses the synced {@code armor[2]} NBT string
     * ({@code air:<n>} for the suit buffer, {@code Amount:<n>} for the fluid tank) — the state the
     * HUD and the inventory screen draw from. Returns -1 if absent.
     */
    private int clientChestAir() throws Exception {
        JsonObject chest = bot().reportPlayerItems().getAsJsonArray("armor").get(2).getAsJsonObject();
        // The tag as DATA, not its `toString()`. What stood here was `\bair:(\d+)` over Minecraft's
        // own display rendering of the compound — a format nothing promises to keep, and one where
        // `air` cannot be told from any other tag whose name ends in those three letters. The
        // harness now reports the compound itself beside the rendering (`stackJson`, added the same
        // day); `nbt` stays for a failure message to print.
        JsonObject tag = chest.getAsJsonObject("tag");
        Integer air = tagNamed(tag, SUIT_AIR_TAG);
        if (air != null) {
            return air;
        }
        Integer amount = tagNamed(tag, FLUID_AMOUNT_TAG);
        return amount == null ? -1 : amount;
    }

    /**
     * The first tag called {@code name} anywhere in the compound, depth-first, or {@code null}.
     *
     * <p>The search is by NAME and it descends, because a fluid tank writes {@code Amount} inside
     * its own sub-compound and the suit writes {@code air} at the top. That is the same reach the
     * regex had over the rendering — and the difference is that a name here is a key, so a tag
     * called {@code chair} can no longer answer for one called {@code air}.</p>
     */
    private static Integer tagNamed(JsonObject tag, String name) {
        if (tag.has(name) && tag.get(name).isJsonPrimitive()) {
            return (int) tag.get(name).getAsDouble();
        }
        for (java.util.Map.Entry<String, com.google.gson.JsonElement> entry : tag.entrySet()) {
            Integer nested = inElement(entry.getValue(), name);
            if (nested != null) {
                return nested;
            }
        }
        return null;
    }

    /**
     * The same search through one value, LISTS included.
     *
     * <p>Descending only into compounds was not enough and the first version did exactly that: this
     * chest piece keeps its tank in a sub-inventory, so the {@code Amount} sits inside a LIST of
     * stacks, and three tests went red reading {@code -1}. The regex this replaced searched the
     * whole flattened rendering, so depth and container kind never came up — which is the one thing
     * a text search is better at, and the reason to say out loud what the structured reader must
     * cover instead.</p>
     */
    private static Integer inElement(com.google.gson.JsonElement value, String name) {
        if (value.isJsonObject()) {
            return tagNamed(value.getAsJsonObject(), name);
        }
        if (value.isJsonArray()) {
            for (com.google.gson.JsonElement element : value.getAsJsonArray()) {
                Integer nested = inElement(element, name);
                if (nested != null) {
                    return nested;
                }
            }
        }
        return null;
    }

    /** The suit buffer's own tag, as the item writes it. */
    private static final String SUIT_AIR_TAG = "air";
    /** A fluid-tank stack's amount, for a chest piece whose buffer is a tank rather than a counter. */
    private static final String FLUID_AMOUNT_TAG = "Amount";

    private static double health(JsonObject state) {
        return state.has("health") ? state.get("health").getAsDouble() : -1.0;
    }

    // ── waiting on a log, either side ─────────────────────────────────────────

    // WHY EVERY SERVER-SIDE WAIT HERE NAMES A FIELD. `Events.await` waits on a bare TYPE, which is
    // not enough for any of the three types this class means: `living_hurt` is recorded for every
    // damage source a player can meet (fall, suffocation, the vacuum), and `suit_air_drained` is
    // written by BOTH suit routes under one name. A wait on the type alone would be satisfied by
    // the wrong record and read as the contract holding. So each one goes through
    // Events.awaitRecordWithField, naming `route` or `source`.
    //
    // The local wait this replaced returned its reply on EXPIRY, and each caller then asserted on
    // it with a sentence explaining what the record meant. Those assertions could not fail once the
    // wait had returned — they re-asked exactly what it had just established — so the sentences
    // moved into the wait's own `what`, where they are printed by the failure that means them.

    /**
     * Wait until the CLIENT has been told a health BELOW {@code threshold} since {@code mark}, and
     * answer the last health it was told ({@code NaN} when it was told none).
     *
     * <p>This is what the old {@code waitForHealthDrop} sampled. Two things change. The reading is
     * mark-scoped, so a drop that was healed back between two samples can no longer be missed; and
     * the number is what the SERVER SENT rather than what the client happens to render now, so a
     * local prediction cannot stand in for a damage packet that never arrived.</p>
     *
     * <p>The waiting itself belongs to {@code Events.awaitMatching}; only the predicate is this
     * scenario's. Running out of budget THROWS here, printing the chain the client did record and
     * the four reasons a log can be empty — where the private loop it replaced returned the last
     * sample (or {@code NaN}) and left each caller to assert on it. That also removes a hole those
     * asserts had: a drop healed back inside the same reply made the wait succeed and the caller's
     * {@code current < threshold} fail, which is the one case the mark-scoped read exists to catch.
     * The number answered is still the LAST health the client was told, for the record; the claim
     * this method now makes is that a health below {@code threshold} was among them.</p>
     *
     * @param what what the caller is really claiming, with its own cross-side context, for the
     *             failure sentence
     */
    private double awaitClientHealthBelow(long clientMark, double threshold, String what,
                                          int tickBudget) throws Exception {
        String reply = clientEvents().awaitMatching(clientMark, "client_health_updated",
                r -> healthsIn(r, threshold)[1] > 0, "below " + threshold, what, tickBudget);
        return healthsIn(reply, threshold)[0];
    }

    /** {@code {last health in the reply (NaN if none), how many of them were below threshold}}. */
    private static double[] healthsIn(String reply, double threshold) {
        double last = Double.NaN;
        int below = 0;
        for (String record : Events.records(reply)) {
            double health = Events.number(record, CLIENT_HEALTH);
            if (Double.isNaN(health)) {
                continue;
            }
            last = health;
            if (last < threshold) below++;
        }
        return new double[]{last, below};
    }

    // ── ItemSpaceChest (component route) ──────────────────────────────────────

    /**
     * From {@code ItemSpaceChestSubInventoryDrainE2ETest}. Counter-test: same suit and tank in a
     * breathable atmosphere. The breathable {@code Atmosphere.onTick} is a no-op, so
     * {@code protectsFrom} &rarr; {@code decrementAir} is never called and the tank's oxygen stays
     * at its initial value.
     *
     * <p>red-witnessed: with {@code AtmosphereHandler} asking {@code VACUUM.isImmune} of every body in
     * a breathable atmosphere every ten ticks (it spends a suit's air, hurts nobody), this fails with
     * "a breathable atmosphere must never reach the suit's tank at all; drains recorded" — 2026-09-28.</p>
     */
    @Test
    public void breathableAtmosphereDoesNotDrainChestTank() throws Exception {
        int originalDensity = snapshotDensity();
        try {
            standOnOwnPlatformInSurvival();
            setDensityAndConfirm(100, true);

            scenario().arranging("equip the suit chest with a full pressure tank");
            long equipMark = clientEvents().mark();
            String equip = exec("stellurgytest player equip-space-chest 1000");
            scenario().requireArranged("equip-space-chest must succeed: " + equip,
                    Reply.of(equip).ok());
            assertEquals("baseline chestAir", 1000, readChestAirComponentRoute());

            // The client's armor[2] reaches it on its own packet after the server-side equip.
            int baseline = clientChestAirOnceSynced(equipMark, v -> v == 1000,
                    "the equipped tank must reach the client's chest slot");
            scenario().requireArranged("client-rendered baseline must agree (1000); client="
                    + baseline, baseline == 1000);

            scenario().asserting("80 ticks of breathable atmosphere drain nothing");
            // An absence, and the class javadoc says what stands behind it: AIR cannot tick, so no
            // event can witness that the player was ticked and judged breathable. markInstrumented
            // is what rules out the two silences that are not about the subject — an unsubscribed
            // recorder and a mixin configuration that was never queued.
            Events events = events();
            long mark = events.markInstrumented();
            // WINDOW: from the mark to the log read below, counted on the player's OWN world clock —
            // the atmosphere judges him on that world's ticks, so client ticks would buy a busy box
            // fewer judgments and a quieter log.
            GameTicks.advanceWorld(serverClient(), plot().dim, ABSENCE_WINDOW_TICKS);

            String drains = events.since(mark, "suit_air_drained");
            assertEquals("a breathable atmosphere must never reach the suit's tank at all; drains"
                            + " recorded on the component route since the window opened: " + drains,
                    0, Events.countRecords(drains, "route", "component"));
            int chestAirAfter = readChestAirComponentRoute();
            assertEquals("chest air must hold steady when the atmosphere doesn't drain; before=1000"
                    + " after=" + chestAirAfter, 1000, chestAirAfter);
        } finally {
            restoreDim(originalDensity);
        }
    }

    /**
     * From {@code ItemSpaceChestSubInventoryDrainE2ETest}. A nearly-drained chest tank transitions
     * the player from suit-protected to suit-fails-{@code isImmune}: once the tank's last mB is
     * drained, {@code decrementAir(stack, 1)} returns 0 &rarr; {@code chest.protectsFromSubstance}
     * returns false &rarr; {@code isImmune} returns false &rarr; vacuum damage applies.
     *
     * <p>That transition IS the contract, and it is asserted as one: the tank pays
     * ({@code suit_air_drained} on the component route), the damage lands afterwards
     * ({@code living_hurt} from {@code Vacuum}), and somewhere between the two the gate flipped
     * ({@code suit_immunity_decided} with {@code immune:false}). The flip is asserted as a COUNT
     * rather than as a chain link because the gate's recorder is edge-only — a decision that repeats
     * is not recorded — and only the flip itself is guaranteed to be an edge: the tank starts with
     * oxygen, so the run of {@code true}s before it is what makes the {@code false} a change.</p>
     *
     * <p>red-witnessed: one inversion per verdict, 2026-09-30. DAMAGE — {@code HazardExposure:145}
     * no longer applying a row's damage: "vacuum damage must apply once the tank is drained … no
     * `living_hurt` carrying source = Vacuum". REFUSAL RECORDED — {@code Atmosphere:137} applying
     * the exposure even to a player the suit protects: "the suit gate must be recorded turning the
     * player DOWN — that flip is the contract …". Not witnessed: the drain link, the client's health
     * and the emptied tank.</p>
     */
    @Test
    public void drainedChestTankTransitionsToVacuumDamage() throws Exception {
        int originalDensity = snapshotDensity();
        try {
            standOnOwnPlatformInSurvival();

            scenario().arranging("equip the suit chest with only three millibuckets of oxygen");
            String equip = exec("stellurgytest player equip-space-chest 3");
            scenario().requireArranged("equip-space-chest with low oxygen must succeed: " + equip,
                    Reply.of(equip).ok());
            assertEquals("baseline chestAir = 3", 3, readChestAirComponentRoute());

            double healthStart = health(bot().reportState());
            scenario().measuring("health before the vacuum window").record("healthStart", healthStart);
            scenario().requireArranged("player must start at full health: " + healthStart,
                    healthStart >= FULL_HEALTH);

            // Both marks BEFORE the vacuum exists, or the first drain of a three-millibucket tank
            // happens between the flip and the mark and the chain starts mid-way.
            Events events = events();
            long mark = events.markInstrumented();
            long clientMark = clientEvents().mark();
            setDensityAndConfirm(0, false);

            scenario().asserting("the tank drains to nothing and the damage then starts");
            String drains = events.awaitRecordWithField(mark, "suit_air_drained", "route",
                    "component",
                    "the vacuum must reach the chest's pressure tank before anything else can be"
                            + " concluded — without a drain the transition below never starts",
                    LINK_BUDGET_TICKS);

            String hurts = events.awaitRecordWithField(mark, "living_hurt", "source", "Vacuum",
                    "vacuum damage must apply once the tank is drained; the drain that started it: "
                            + drains, LINK_BUDGET_TICKS);

            String decisions = events.since(mark, "suit_immunity_decided");
            assertTrue("the suit gate must be recorded turning the player DOWN — that flip is the"
                    + " contract, and a damage record without it would mean he was hurt for some"
                    + " other reason. Decisions since the flip: " + decisions,
                    Events.countRecords(decisions, "immune", "false") >= 1);

            double current = awaitClientHealthBelow(clientMark, healthStart,
                    "the client must be TOLD the damage, not only the server hold it (he started"
                            + " at " + healthStart + "); server damage records: " + hurts,
                    LINK_BUDGET_TICKS);
            int chestAirAfter = readChestAirComponentRoute();
            scenario().record("chestAirAfter", chestAirAfter).record("healthAfter", current);

            assertEquals("tank must be fully drained after the wait window; chestAir="
                    + chestAirAfter, 0, chestAirAfter);
        } finally {
            restoreDim(originalDensity);
        }
    }

    /**
     * From {@code GasChargePadFillsPressureTankE2ETest}. {@code TileGasChargePad.canPerformFunction}
     * scans the 1x2x1 AABB at the pad's position for a player, reads his CHEST slot, and — if the
     * pad's tank holds oxygen — drains it by the missing-air amount and calls
     * {@code fillable.increment}. Player-visible: the suit air meter rises.
     *
     * <p>Why testClient and not testServer: the pad's AABB scan needs a real {@code EntityPlayer} in
     * the world, and a server-side {@code FakePlayer} is forbidden by project policy. The
     * real-client bot IS a real {@code EntityPlayerMP} on the server side of the harness.</p>
     *
     * <p>Pins the END STATE (air rises over the window) rather than a per-tick mB rate.</p>
     *
     * <p>red-witnessed: with {@code TileGasChargePad} draining its tank but no longer calling
     * {@code fillable.increment}, this fails with "no `suit_air_filled` whose 'filled' is not 0" —
     * 2026-09-28.</p>
     */
    @Test
    public void standingOnPoweredPadRefillsSuitAir() throws Exception {
        int dim = plot().dim;
        int px = plot().x(PAD_DX + 1), py = PAD_Y, pz = plot().z(PAD_DZ + 1);

        scenario().arranging("place a charge pad, fill it with oxygen, and suit the player up");
        // The pad replaces one block of this scenario's own platform, so the player stands ON the
        // pad with solid ground either side of him.
        standOnOwnPlatformInSurvival();
        String place = exec("stellurgytest place " + dim + " " + px + " " + py + " " + pz
                + " stellurgy:oxygencharger");
        scenario().requireArranged("pad placement must succeed: " + place,
                Reply.of(place).ok());
        String inj = exec("stellurgytest fluid inject " + dim + " " + px + " " + py + " " + pz
                + " oxygen 8000");
        scenario().requireArranged("fluid inject must succeed: " + inj, Reply.of(inj).ok());

        // initialOxygen=500: half of the pressure tank's 1000 mB capacity, which leaves headroom for
        // the pad to actually add fluid. Equipping a full tank short-circuits the pad's
        // canPerformFunction body (amtFluid = 0) and the test would measure nothing.
        long equipMark = clientEvents().mark();
        String equip = exec("stellurgytest player equip-space-chest 500");
        scenario().requireArranged("equip-space-chest must succeed: " + equip,
                Reply.of(equip).ok());

        scenario().measuring("the suit's air before standing on the pad");
        int airBefore = readChestAirComponentRoute();
        scenario().record("chestAirBefore", airBefore);
        scenario().requireArranged("baseline chest air must be > 0 (the probe filled the pressure"
                + " tank); actual=" + airBefore + " equip=" + equip, airBefore > 0);
        // The CLIENT's copy too, before the fill is marked: the equip's own sync is a change to the
        // same slot, and one still in flight would otherwise close the refill wait below.
        int clientBefore = clientChestAirOnceSynced(equipMark, v -> v == airBefore,
                "the equipped tank must reach the client's chest slot before its refill is watched");
        scenario().requireArranged("the client must show the equipped tank (" + airBefore + ") before"
                + " the refill is watched; client=" + clientBefore, clientBefore == airBefore);

        scenario().asserting("standing on the powered pad raises the suit's air, on both sides");
        Events events = events();
        long mark = events.markInstrumented();
        long fillClientMark = clientEvents().mark();
        exec("tp @p " + (px + 0.5) + " " + (py + 1) + " " + (pz + 0.5));

        // The pad's own transfer, rather than a fixed window and a bigger number afterwards. A fill
        // that never happened used to be indistinguishable from three other things: a pad that never
        // found the player in its 1x2x1 box, one that found him with no deficit to fill, and one that
        // filled him while the client was never told.
        String fills = events.awaitMatching(mark, "suit_air_filled",
                reply -> Events.countRecords(reply, "type", "suit_air_filled")
                        - Events.countRecords(reply, "filled", "0") >= 1,
                "whose \"filled\" is not 0",
                "the pad must actually transfer oxygen into the suit — a request the chest"
                        + " answered with 0 is the pad finding nothing to fill, not a refill",
                LINK_BUDGET_TICKS);
        scenario().record("suitAirFills", fills);

        int airAfter = readChestAirComponentRoute();
        int clientAfter = clientChestAirOnceSynced(fillClientMark, v -> v > airBefore,
                "the refilled tank must reach the client's chest slot");
        scenario().record("chestAirAfter", airAfter).record("clientChestAir", clientAfter);
        assertTrue("client-rendered chest tank must show the refill; client=" + clientAfter
                + " serverBefore=" + airBefore + " serverAfter=" + airAfter
                + " fills=" + fills, clientAfter > airBefore);
        assertTrue("chest air must increase after standing on a powered, filled GasChargePad;"
                + " before=" + airBefore + " after=" + airAfter, airAfter > airBefore);
    }

    // ── enchanted-armour route ────────────────────────────────────────────────

    /**
     * From {@code ItemSpaceArmorUseFluidE2ETest}. Counter-test: the same enchanted suit in a
     * breathable atmosphere. The breathable type's {@code onTick} is a no-op, so the
     * {@code protectsFrom} branch is never evaluated and no decrement fires.
     *
     * <p>red-witnessed: with the same breathable-drain inversion as
     * {@link #breathableAtmosphereDoesNotDrainChestTank}, this fails with "a breathable atmosphere must
     * never reach the enchanted suit's buffer; drains recorded" — 2026-09-28.</p>
     */
    @Test
    public void suitedPlayerInBreathableDimDoesNotLoseChestAir() throws Exception {
        int originalDensity = snapshotDensity();
        try {
            standOnOwnPlatformInSurvival();
            setDensityAndConfirm(100, true);

            scenario().arranging("equip the enchanted air suit with a full buffer");
            String equip = exec("stellurgytest player equip-airsuit 1000");
            scenario().requireArranged("equip-airsuit must succeed: " + equip,
                    Reply.of(equip).ok());
            assertEquals("baseline chest air", 1000, readChestAir());

            scenario().asserting("80 ticks of breathable atmosphere drain nothing");
            // The same absence as the component-route counter-test, on the other route, and with the
            // same limit: a breathable atmosphere does not tick, so nothing can witness that the
            // player was judged. markInstrumented is what rules out an instrument that was not there.
            Events events = events();
            long mark = events.markInstrumented();
            // WINDOW: the same absence window, on the player's own world clock.
            GameTicks.advanceWorld(serverClient(), plot().dim, ABSENCE_WINDOW_TICKS);

            String drains = events.since(mark, "suit_air_drained");
            assertEquals("a breathable atmosphere must never reach the enchanted suit's buffer;"
                            + " drains recorded on that route since the window opened: " + drains,
                    0, Events.countRecords(drains, "route", "enchanted"));
            int chestAirAfter = readChestAir();
            scenario().record("chestAirAfter", chestAirAfter);
            assertEquals("client-rendered chest air must hold in breathable atmosphere",
                    1000, clientChestAir());
            assertEquals("chest air must be unchanged in breathable atmosphere; before=1000 after="
                    + chestAirAfter, 1000, chestAirAfter);
        } finally {
            restoreDim(originalDensity);
        }
    }

    /**
     * From {@code ItemSpaceArmorUseFluidE2ETest}. Vacuum plus a full enchanted suit: the atmosphere
     * {@code onTick} fires every 10 game ticks and each fire decrements the chest "air" NBT by 1 via
     * {@code ItemAirUtils.ItemAirWrapper}. Health holds, because the four enchanted slots make
     * {@code isImmune} return true and no {@code attackEntityFrom} ever runs.
     *
     * <p>"He was not hurt" is a negative, and the drain is its positive precondition: production
     * checks the chest LAST, after the legs, the boots and the helmet, so a recorded drain means the
     * whole suit was consulted and the chest was asked to pay. Only then does the silence in
     * {@code living_hurt} say the suit held rather than that the atmosphere never looked at him.</p>
     *
     * <p>red-witnessed: with {@code ItemAirUtils.decrementAir} reporting the air spent without
     * spending it (protection intact), this fails with "the drained buffer must reach the client's
     * chest slot — no `client_slot_tag_set`" — 2026-09-28.</p>
     */
    @Test
    public void suitedPlayerInVacuumLosesChestAirOverTime() throws Exception {
        int originalDensity = snapshotDensity();
        try {
            standOnOwnPlatformInSurvival();

            scenario().arranging("equip the enchanted air suit with a full buffer");
            long equipMark = clientEvents().mark();
            String equip = exec("stellurgytest player equip-airsuit 1000");
            scenario().requireArranged("equip-airsuit must succeed: " + equip,
                    Reply.of(equip).ok());
            assertEquals("baseline chest air before vacuum exposure", 1000, readChestAir());
            // The CLIENT's copy too, before the drain is marked: the equip's own sync is a change to
            // the same slot, and one still in flight would otherwise close the drain wait below.
            int clientBefore = clientChestAirOnceSynced(equipMark, v -> v == 1000,
                    "the equipped suit must reach the client's chest slot before its drain is watched");
            scenario().requireArranged("the client must show the full buffer before the drain is"
                    + " watched; client=" + clientBefore, clientBefore == 1000);

            double healthStart = health(bot().reportState());
            scenario().measuring("health before the vacuum window").record("healthStart", healthStart);
            Events events = events();
            long mark = events.markInstrumented();
            long drainClientMark = clientEvents().mark();
            setDensityAndConfirm(0, false);

            scenario().asserting("the suit's air drains and the suit keeps the player unhurt");
            String drains = events.awaitRecordWithField(mark, "suit_air_drained", "route",
                    "enchanted",
                    "the vacuum must reach the enchanted suit's buffer — the chest is the LAST"
                            + " piece production consults, so a drain is the proof the whole suit"
                            + " was asked", LINK_BUDGET_TICKS);

            // The suit HELD: the gate never recorded a refusal and no vacuum damage was applied.
            // The decision recorder is edge-only, so a run of unchanged `true`s leaves no record at
            // all — which is why the assertion is on the ABSENCE of `immune:false` rather than on
            // the presence of `immune:true`, a record that is only written when the answer changes.
            String decisions = events.since(mark, "suit_immunity_decided");
            assertEquals("a full enchanted suit must never be judged unprotected in vacuum;"
                    + " decisions since the flip: " + decisions,
                    0, Events.countRecords(decisions, "immune", "false"));
            String hurts = events.since(mark, "living_hurt");
            assertEquals("a suited player must take no vacuum damage; what hurt him since the flip: "
                    + hurts + " | suit-diag " + exec("stellurgytest player suit-diag"),
                    0, Events.countRecords(hurts, "source", "Vacuum"));

            int chestAirAfter = readChestAir();
            int clientAir = clientChestAirOnceSynced(drainClientMark, v -> v >= 0 && v < 1000,
                    "the drained buffer must reach the client's chest slot");
            double healthAfter = health(bot().reportState());
            scenario().record("chestAirAfter", chestAirAfter).record("clientChestAir", clientAir)
                    .record("healthAfter", healthAfter);

            assertTrue("chest air must decrease in vacuum with suit; before=1000 after="
                    + chestAirAfter + " drains=" + drains, chestAirAfter < FULL_TANK);
            assertTrue("client-rendered chest air must reflect the drain; client=" + clientAir
                    + " server=" + chestAirAfter, clientAir >= 0 && clientAir < FULL_TANK);
            // Health lost to anything other than vacuum (suffocation, fall, …) is a fixture failure
            // rather than a suit failure, and the message must say which — so the damage SOURCE
            // goes in the text beside the delta.
            assertTrue("suited player must not take vacuum damage; healthStart=" + healthStart
                    + " healthAfter=" + healthAfter + " diag=" + exec("stellurgytest player suit-diag"),
                    healthAfter >= healthStart);
        } finally {
            restoreDim(originalDensity);
        }
    }

    /**
     * From {@code ItemSpaceArmorUseFluidE2ETest}. Cross-check: a bare-skinned player in vacuum loses
     * HEALTH (the no-suit branch of {@code AtmosphereVacuum.onTick}) and the {@code chestAir} probe
     * reports -1 (no chest stack). Pins that drain is gated on having a chest with a valid air
     * container — no chest, no decrement, just damage.
     *
     * <p>Here the damage is the positive precondition for the absence beside it: production only
     * reaches {@code attackEntityFrom} when the suit gate has turned the player down, so a
     * {@code living_hurt} from {@code Vacuum} proves the atmosphere tick ran on THIS player, and the
     * empty drain log then says the missing chest never entered a decrement path.</p>
     *
     * <p>red-witnessed: with {@code HazardExposure:145} no longer applying a row's damage: "vacuum
     * damage must apply to a bare-skinned player — no `living_hurt`", 2026-09-30. Only that link is
     * witnessed; the empty drain log, the client's health and the chest reading are not.</p>
     */
    @Test
    public void unsuitedPlayerInVacuumLosesNoAirAndTakesDamage() throws Exception {
        int originalDensity = snapshotDensity();
        try {
            standOnOwnPlatformInSurvival();

            scenario().measuring("bare-skinned baseline: no chest, full health");
            assertEquals("bare-skinned baseline chest air must be -1", -1, readChestAir());
            double healthStart = health(bot().reportState());
            scenario().record("healthStart", healthStart);
            scenario().requireArranged("player must start at full health, got " + healthStart,
                    healthStart >= FULL_HEALTH);

            Events events = events();
            long mark = events.markInstrumented();
            long clientMark = clientEvents().mark();
            setDensityAndConfirm(0, false);

            scenario().asserting("vacuum damages the unprotected player, and drains no air");
            String hurts = events.awaitRecordWithField(mark, "living_hurt", "source", "Vacuum",
                    "vacuum damage must apply to a bare-skinned player", LINK_BUDGET_TICKS);

            String drains = events.since(mark, "suit_air_drained");
            assertEquals("a player with no chest must enter no decrement path at all — the damage"
                            + " above proves the atmosphere DID tick him, so this silence is about"
                            + " the missing chest. Drains since the flip: " + drains,
                    0, Events.typesOf(drains).size());

            double current = awaitClientHealthBelow(clientMark, healthStart,
                    "the client must be told the damage (he started at " + healthStart
                            + "); server damage: " + hurts,
                    LINK_BUDGET_TICKS);
            scenario().record("healthAfter", current);
            assertEquals("chestAir must remain -1 throughout — no chest = no decrement path",
                    -1, readChestAir());
        } finally {
            restoreDim(originalDensity);
        }
    }

    // ── the vacuum itself, on the client ──────────────────────────────────────

    /**
     * From {@code OxygenSuitClientStateE2ETest}. Flips the overworld to a vacuum and observes —
     * through the client bridge — that {@code reportState().health} DROPS. That confirms the
     * server-side {@code AtmosphereVacuum} damage tick ({@code attackEntityFrom}) reaches and is
     * visible on the real Minecraft client, end to end.
     *
     * <p>Kept alongside {@link #unsuitedPlayerInVacuumLosesNoAirAndTakesDamage()}, which asserts the
     * same damage plus the no-chest decrement contract: this one is the narrower, older pin and the
     * one the suit tests cross-check themselves against.</p>
     *
     * <p>red-witnessed: with {@code HazardExposure:145} no longer applying a row's damage: "the
     * vacuum must damage the player at all before the client can be shown it — no `living_hurt`",
     * 2026-09-30. The client-health link after it is not witnessed.</p>
     */
    @Test
    public void vacuumDamageReachesTheClient() throws Exception {
        int originalDensity = snapshotDensity();
        try {
            standOnOwnPlatformInSurvival();

            scenario().measuring("the client's own health before the vacuum");
            double healthStart = health(bot().reportState());
            scenario().record("healthStart", healthStart);
            scenario().requireArranged("player should start at full health, got " + healthStart,
                    healthStart >= FULL_HEALTH);

            Events events = events();
            long mark = events.markInstrumented();
            long clientMark = clientEvents().mark();
            setDensityAndConfirm(0, false);

            scenario().asserting("the damage tick reaches the client's rendered health");
            // The narrow pin, as its two links. The server applied vacuum damage, and the client was
            // TOLD a lower health: read off the client's own record of the health packet rather than
            // sampled from what it renders now, so a drop cannot be missed between two samples and a
            // local prediction cannot stand in for a packet that never came.
            String hurts = events.awaitRecordWithField(mark, "living_hurt", "source", "Vacuum",
                    "the vacuum must damage the player at all before the client can be shown it",
                    LINK_BUDGET_TICKS);

            double current = awaitClientHealthBelow(clientMark, healthStart,
                    "vacuum damage never reached the client (he started at " + healthStart
                            + "); server damage: " + hurts,
                    LINK_BUDGET_TICKS);
            scenario().record("healthAfter", current);
        } finally {
            restoreDim(originalDensity);
        }
    }

    /**
     * From {@code ItemSpaceChestSubInventoryDrainE2ETest}. Vacuum plus a full suit chest (an
     * oxygen-charged pressure tank in slot 0): the atmosphere {@code onTick} fires every 10 game
     * ticks and each fire drains 1 mB from the tank's FluidStack via
     * {@code ItemSpaceChest.decrementAir}. The player takes no damage — {@code isImmune} holds while
     * the chain does.
     *
     * <p>Same shape as the enchanted-route scenario: the drain is what proves the whole suit was
     * consulted (the chest is checked last), and only then does the silence in {@code living_hurt}
     * mean the suit held. The gate's own {@code immune:true} is not asserted, because that recorder
     * writes only on a CHANGE and an unbroken run of protection may produce no record at all.</p>
     *
     * <p>red-witnessed: with {@code ItemSpaceChest.decrementAir} reporting the air spent without
     * draining its tank (protection intact), this fails with "the drained tank must reach the client's
     * chest slot — no `client_slot_tag_set`" — 2026-09-28.</p>
     */
    @Test
    public void vacuumDrainsOxygenFromChestSubInventoryTank() throws Exception {
        int originalDensity = snapshotDensity();
        try {
            standOnOwnPlatformInSurvival();

            scenario().arranging("equip the suit chest with a full pressure tank");
            long equipMark = clientEvents().mark();
            String equip = exec("stellurgytest player equip-space-chest 1000");
            scenario().requireArranged("equip-space-chest must succeed: " + equip,
                    Reply.of(equip).ok());
            scenario().requireArranged("equip-space-chest must report oxygen filled in tank: "
                    + equip, (Reply.of(equip).integer("tankFilled") == FULL_TANK));
            assertEquals("baseline chestAir read via ItemAirUtils -> ItemSpaceChest.getAirRemaining"
                    + " -> sum of FluidStack amounts must equal 1000",
                    1000, readChestAirComponentRoute());
            // The CLIENT's copy too, before the drain is marked — see the enchanted-route scenario.
            int clientBefore = clientChestAirOnceSynced(equipMark, v -> v == 1000,
                    "the equipped tank must reach the client's chest slot before its drain is watched");
            scenario().requireArranged("the client must show the full tank before the drain is"
                    + " watched; client=" + clientBefore, clientBefore == 1000);

            double healthStart = health(bot().reportState());
            scenario().measuring("health before the vacuum window").record("healthStart", healthStart);
            Events events = events();
            long mark = events.markInstrumented();
            long drainClientMark = clientEvents().mark();
            setDensityAndConfirm(0, false);

            scenario().asserting("the tank drains through the component route and the suit holds");
            String drains = events.awaitRecordWithField(mark, "suit_air_drained", "route",
                    "component",
                    "the vacuum must drain the chest's pressure tank through the COMPONENT route —"
                            + " the chest is the last piece production consults, so this is also"
                            + " the proof the whole suit was asked", LINK_BUDGET_TICKS);

            String decisions = events.since(mark, "suit_immunity_decided");
            assertEquals("a full suit must never be judged unprotected while its tank has oxygen;"
                    + " decisions since the flip: " + decisions,
                    0, Events.countRecords(decisions, "immune", "false"));
            String hurts = events.since(mark, "living_hurt");
            assertEquals("a suited player must take no vacuum damage; what hurt him since the flip: "
                    + hurts, 0, Events.countRecords(hurts, "source", "Vacuum"));

            int clientAirAfter = clientChestAirOnceSynced(drainClientMark, v -> v >= 0 && v < 1000,
                    "the drained tank must reach the client's chest slot");
            int chestAirAfter = readChestAirComponentRoute();
            double healthAfter = health(bot().reportState());
            scenario().record("chestAirAfter", chestAirAfter).record("clientChestAir", clientAirAfter)
                    .record("healthAfter", healthAfter);

            // >= 0 as well as < 1000: the -1 this reader answers for an armour slot the client has
            // not been sent at all is below 1000 too, and would read as a drain it never saw.
            assertTrue("client-rendered chest state must reflect the drain; client=" + clientAirAfter
                    + " server=" + chestAirAfter, clientAirAfter >= 0 && clientAirAfter < FULL_TANK);
            assertTrue("chest air must decrease through the CHEST sub-inventory route in vacuum;"
                    + " before=1000 after=" + chestAirAfter + " drains=" + drains,
                    chestAirAfter < FULL_TANK);
            assertTrue("a full suit must keep isImmune=true while the tank has oxygen; healthStart="
                    + healthStart + " healthAfter=" + healthAfter, healthAfter >= healthStart);
        } finally {
            restoreDim(originalDensity);
        }
    }

    // ── a zone that is still PRESSURISED but no longer breathable ─────────────

    /**
     * Builds a sealed room in this scenario's plot, seals it with a powered vent, overwrites the
     * zone's gas so it reads pressurised-but-stale, and stands the player inside it in survival.
     *
     * <p>The dimension around the room is left BREATHABLE on purpose. Every other scenario in this
     * class makes the whole dimension a vacuum, which would make "the player was hurt" true whether
     * or not the room ever became a zone at all. Here the only thing in the world that can hurt
     * anyone is the room's own air — so an arrangement that silently failed to build a zone surfaces
     * as the control below staying at full health, instead of as a false pass.</p>
     *
     * @return the vent's position as {@code dim x y z}, for the probes the scenario then runs
     */
    private String sealStaleZoneAndStandInIt() throws Exception {
        String at = buildSealedRoomWithVent();

        // Pressurised, and short of oxygen: the three partials still total one atmosphere, so this
        // is emphatically NOT the vacuum every other scenario here uses - it is a room whose air has
        // been breathed. 50 000 ppm sits below lifeSupportMinPartialO2's 160 000 ppm default.
        arrangeProbe("stellurgytest vent setair " + at
                + " " + ppm(790_000) + " " + ppm(50_000) + " " + ppm(160_000));

        String infoCommand = "stellurgytest vent info " + at;
        Reply info = Reply.of(infoCommand, exec(infoCommand));
        scenario().record("ventInfo", info.toString());
        // What is asserted is the room's STATE, not the number that was written into it. The vent
        // holding the seal is powered and fuelled, so it is adding the oxygen it pays for the whole
        // time this room exists, and the composition sits a little above what setair asked for.
        // Pinning `"airO2":50000` as a literal made the arrangement fail the moment that started
        // working - and it was redundant anyway: `lowO2` IS the statement that the oxygen is below
        // what a person needs, derived from this very number, and the pressure says the room is not
        // a vacuum. The subject is "pressurised, and too thin to breathe"; these three say it.
        long o2 = info.longInteger("airO2");
        scenario().requireArranged("the room must actually BE a zone before anyone stands in it, and"
                + " it must read as pressurised-but-stale rather than as vacuum: " + info,
                o2 > 0 && info.longInteger("airPressure") == 100
                        && "lowO2".equals(info.text("blobAtmosphere")));

        standInTheRoomUnhurt();
        return at;
    }

    /**
     * The room itself: a sealed stone box in this scenario's plot with a powered, sealed vent in its
     * floor. What the air inside it then IS belongs to the caller - a stale zone and an overheated
     * one are the same room with different contents, and sharing the geometry is what keeps them
     * comparable.
     *
     * @return the vent's position as {@code dim x y z}, for the probes the scenario then runs
     */
    private String buildSealedRoomWithVent() throws Exception {
        int dim = plot().dim;
        int vx = plot().x(ROOM_DX), vy = ROOM_Y, vz = plot().z(ROOM_DZ);
        String at = dim + " " + vx + " " + vy + " " + vz;

        scenario().arranging("build a sealed room and seal it with a powered vent");
        arrangeProbe("stellurgytest fill " + dim + " " + (vx - 2) + " " + (vy - 1) + " " + (vz - 2)
                + " " + (vx + 2) + " " + vy + " " + (vz + 2) + " minecraft:stone");
        for (int yy = vy + 1; yy <= vy + 2; yy++) {
            arrangeProbe("stellurgytest fill " + dim + " " + (vx - 2) + " " + yy + " " + (vz - 2)
                    + " " + (vx + 2) + " " + yy + " " + (vz + 2) + " minecraft:stone");
            arrangeProbe("stellurgytest fill " + dim + " " + (vx - 1) + " " + yy + " " + (vz - 1)
                    + " " + (vx + 1) + " " + yy + " " + (vz + 1) + " minecraft:air");
        }
        arrangeProbe("stellurgytest fill " + dim + " " + (vx - 2) + " " + (vy + 3) + " " + (vz - 2)
                + " " + (vx + 2) + " " + (vy + 3) + " " + (vz + 2) + " minecraft:stone");

        Reply placed = arrangeProbe("stellurgytest place " + at + " stellurgy:oxygenVent");
        scenario().requireArranged("the vent must place: " + placed, placed.bool("placed"));
        arrangeProbe("stellurgytest energy inject " + at + " 1000000");
        arrangeProbe("stellurgytest fluid inject " + at + " oxygen 16000");
        arrangeProbe("stellurgytest tile force-tick " + at + " 1");
        arrangeProbe("stellurgytest vent reseal " + at);
        arrangeProbe("stellurgytest tile force-tick " + at + " 5");
        return at;
    }

    /**
     * A probe that is a step of the arrangement, refused as one unless the verb reported {@code ok}.
     * A dropped reply cannot say the step did not happen, and the scenario would then measure a room
     * that was never built.
     */
    private Reply arrangeProbe(String command) throws Exception {
        Reply reply = Reply.of(command, exec(command));
        scenario().requireArranged(command + " must report ok: " + reply, reply.ok());
        return reply;
    }

    /** Puts the player inside the sealed room, on its floor, at full health. */
    private void standInTheRoomUnhurt() throws Exception {
        int vx = plot().x(ROOM_DX), vy = ROOM_Y, vz = plot().z(ROOM_DZ);
        // Stand him in the room while still CREATIVE: creative short-circuits
        // AtmosphereNeedsSuit.isImmune, so the room cannot hurt him yet. Checking health in survival
        // instead measured the subject: the room bit once during the settling ticks and the
        // precondition read 19.0, i.e. this scenario refusing to run because its own contract had
        // already fired.
        // WHERE he stands is a link on the client applying the placement onto a floor it holds —
        // not a settle of ten ticks, which was a guess at how long that takes on this box.
        standOnFloorTheClientHolds(vx + 0.5, vy + 1, vz + 0.5, 0f, 0f,
                "the player must be standing on the sealed room's floor before the window opens");

        // A creative player takes no fall or suffocation damage, so this cannot catch a bad
        // arrival — that is the placement link's job. What it catches is a player who comes into
        // this scenario already hurt from an earlier one on the same client, with regeneration off.
        double health = health(bot().reportState());
        scenario().record("healthInRoom", health);
        scenario().requireArranged("the player must be at full health INSIDE the sealed room before"
                + " the window opens; client health=" + health, health >= 20.0);
    }

    /**
     * Closes the arrangement: survival LAST, with no settling tick after it, so the damage window
     * starts where the scenario says it does and not a second earlier.
     */
    private double openSurvivalWindow() throws Exception {
        exec("gamerule naturalRegeneration false");
        exec("gamemode survival @a");
        double health = health(bot().reportState());
        scenario().record("healthAtWindowOpen", health);
        return health;
    }

    // ── a zone that is breathable and far too HOT ─────────────────────────────

    /**
     * The first rung of the thermal failure ladder, on a real client: a compartment whose air has
     * been driven past the crew threshold hurts the person standing in it.
     *
     * <p>The room's air is <b>breathable</b> throughout - the same one atmosphere with the same
     * oxygen - so nothing here can be confused with this class's other hazards. The only thing that
     * changes between the control and the subject is the temperature of that air.</p>
     *
     * <p>The control leg is what makes the subject a measurement rather than a coincidence: the
     * player stands in the very same sealed room at cabin temperature, in survival, with
     * regeneration off, and must come out of it untouched. Without it, "his health fell" is also
     * what suffocating in a badly built box looks like.</p>
     *
     * <p>red-witnessed: one inversion per verdict, 2026-09-30. CONTROL — {@code PressurizedAir}
     * made a ticking, unbreathable atmosphere ({@code Atmosphere:41}) that raises the heat row
     * ({@code AtmosphereHazards:139}): "control leg: the room itself must not hurt him while it is at
     * cabin temperature … start=20.0 after=16.0". HURTS — {@code AtmosphereHazards:139} dropping the
     * heat row: "a compartment past the crew threshold must hurt the person in it … no
     * `client_health_updated` below 20.0". HEAT ITSELF — {@code HazardExposure:145} skipping the damage
     * of the heat row only, so it still sets him alight: "the overheated room must itself deal him
     * heat damage — not only set him alight — no `living_hurt` carrying who = ForgeTestClient and
     * source = Heat was recorded within 200 ticks", with the fire's own {@code living_hurt} records
     * in the window, 2026-09-30. The rung, cabin and player-name premises are arrangements and are
     * not witnessed.</p>
     */
    @Test
    public void overheatedZoneAirHurtsAnUnsuitedCrewman() throws Exception {
        int originalDensity = snapshotDensity();
        try {
            setDensityAndConfirm(100, true);
            int veryHot = configInt("shipHeatCrewVeryHotKelvin");
            int ambient = configInt("shipHeatAmbientKelvin");
            scenario().requireArranged("the crew rung must be switched on, and the cabin must start"
                    + " below it, or this scenario asks nothing: rung=" + veryHot
                    + " ambient=" + ambient, veryHot > 0 && ambient < veryHot);

            String at = buildSealedRoomWithVent();
            setRoomAir(at, ambient * 1000);
            String cabin = atmosphereInRoom();
            scenario().requireArranged("premise: a breathable room at cabin temperature must be an"
                    + " ordinary room: " + cabin, "PressurizedAir".equals(cabin));
            standInTheRoomUnhurt();
            exec("stellurgytest player clear-armor");

            scenario().measuring("health in the same room while it is merely warm");
            double healthStart = openSurvivalWindow();
            // WINDOW: healthStart and healthCold, eighty ticks apart in the room at cabin temperature;
            // the control asserts their difference, so the interval is how long the room had to hurt.
            bot().waitTicks(80);
            double healthCold = health(bot().reportState());
            scenario().record("healthAfterControlWindow", healthCold);
            assertTrue("control leg: the room itself must not hurt him while it is at cabin"
                    + " temperature, or the drop below would be about the box and not the heat;"
                    + " start=" + healthStart + " after=" + healthCold, healthCold >= healthStart);

            scenario().arranging("drive the same room's air past the crew threshold");
            // Who the room is about to hurt, by name, so the damage wait below is about THIS player.
            Reply standing = Reply.of(exec("stellurgytest atmosphere for-player"));
            scenario().requireArranged("the server must name the player standing in the room: "
                    + standing, standing.ok());
            String who = standing.text("player");
            // Marked BEFORE the stimulus, so the waits below are about the damage THIS heating caused
            // and cannot be satisfied by anything the control leg already recorded.
            Events serverEvents = events();
            long hotDamageMark = serverEvents.markInstrumented();
            long hotMark = clientEvents().mark();
            setRoomAir(at, (veryHot + 10) * 1000);
            String hostile = atmosphereInRoom();
            scenario().record("atmosphereWhenHot", hostile);
            scenario().requireArranged("the overheated room must present a hostile atmosphere before"
                    + " anyone can be hurt by it: " + hostile, "VeryHot".equals(hostile));

            scenario().asserting("an overheated compartment hurts the crew standing in it");
            // THE HEAT ITSELF, by its damage source. The heat row also sets him alight, and the fire
            // hurts him on its own, so the client's health falling below cannot say the heat did it —
            // a row that ignited him and dealt nothing would pass that link. `living_hurt` is the
            // server's LivingHurtEvent, fired only for a hit that got past the invulnerability window,
            // and it names the damage source, so this is the row's own hit, landed.
            String heatHit = serverEvents.awaitRecordWithFields(hotDamageMark, "living_hurt",
                    "the overheated room must itself deal him heat damage — not only set him alight",
                    LINK_BUDGET_TICKS, "who", who, "source", "Heat");
            scenario().record("heatDamage", heatHit);
            double healthHot = awaitClientHealthBelow(hotMark, healthCold,
                    "a compartment past the crew threshold must hurt the person in it - the room IS"
                            + " the hazard, and the CLIENT must be told the damage rather than the"
                            + " server merely holding it (he was on " + healthCold + " while the"
                            + " same room was merely warm)",
                    LINK_BUDGET_TICKS);
            scenario().record("healthAfterHeating", healthHot);
        } finally {
            restoreDim(originalDensity);
        }
    }

    /** Overwrites the room's air: breathable sea-level gas at a stated temperature, in milliK. */
    private void setRoomAir(String at, int milliK) throws Exception {
        arrangeProbe("stellurgytest vent setair " + at
                + " " + ppm(790_000) + " " + ppm(210_000) + " 0 " + milliK);
    }

    /** What a person standing in the room breathes, as the handler publishes it. */
    private String atmosphereInRoom() throws Exception {
        String resp = exec("stellurgytest atmosphere get " + plot().dim + " " + plot().x(ROOM_DX) + " "
                + (ROOM_Y + 1) + " " + plot().z(ROOM_DZ));
        Reply reply = Reply.of(resp);
        assertTrue("no atmosphere type in: " + resp, reply.has("type"));
        return reply.text("type");
    }

    /** A tuned threshold read off the server, so no assertion here restates one. */
    private int configInt(String key) throws Exception {
        String resp = exec("stellurgytest config get " + key);
        Reply reply = Reply.of(resp);
        scenario().requireArranged("config get " + key + " failed: " + resp, reply.ok());
        assertTrue("no value in: " + resp, reply.has("value"));
        return reply.integer("value");
    }

    /**
     * The control, and the reason the scenario below means anything: a room whose oxygen has fallen
     * under the breathable floor hurts someone standing in it with no suit.
     *
     * <p>This is the half of the suit-fallback contract that is NOT a breach. A breach is already
     * covered by this class's vacuum scenarios; this is the other failure the life-support design
     * names — regeneration not keeping up, leaving a room still full of gas and still lethal.</p>
     *
     * <p>red-witnessed: with {@code HazardExposure:145} no longer applying a row's damage: "a
     * pressurised room below the breathable oxygen floor must hurt an unsuited player, and the CLIENT
     * must be told it (he started at 20.0) — no `client_health_updated` below 20.0", 2026-09-30.</p>
     *
     * <p>Not asserted: a closing comparison of his health against the start, because the wait above
     * already requires a client health below the start, so the comparison could not go red on its
     * own.</p>
     */
    @Test
    public void staleZoneAirHurtsAnUnsuitedPlayer() throws Exception {
        int originalDensity = snapshotDensity();
        try {
            setDensityAndConfirm(100, true);
            String at = sealStaleZoneAndStandInIt();
            exec("stellurgytest player clear-armor");

            scenario().measuring("health before the stale-air window");
            // Marked before the window opens: the claim is about damage taken IN it, and a mark
            // taken afterwards would accept a record from the arrangement.
            long staleMark = clientEvents().mark();
            double healthStart = openSurvivalWindow();

            scenario().asserting("stale zone air damages an unsuited player");
            double healthAfter = awaitClientHealthBelow(staleMark, healthStart,
                    "a pressurised room below the breathable oxygen floor must hurt an unsuited"
                            + " player, and the CLIENT must be told it (he started at "
                            + healthStart + ")",
                    LINK_BUDGET_TICKS);
            scenario().record("healthAfter", healthAfter)
                    .record("ventInfoAfter", exec("stellurgytest vent info " + at));
        } finally {
            restoreDim(originalDensity);
        }
    }

    /**
     * The suit fallback, on the case that is not a breach: the suit is a personal contour ON TOP of
     * the zone air, so in a room life support can no longer keep breathable the crew breathe from
     * the suit — and pay for it.
     *
     * <p>Both halves are asserted because either alone is satisfiable by a broken system: unchanged
     * health alone is what a room that never went stale looks like (which is what the control above
     * rules out), and a falling air buffer alone is what a suit draining without protecting anybody
     * looks like.</p>
     *
     * <p>red-witnessed: one inversion per verdict, 2026-09-30. DRAIN — {@code AtmosphereHazards:135}
     * dropping the low-oxygen row: "the stale air must reach the suit's buffer … no
     * `suit_air_drained` carrying route = enchanted". NO REFUSAL — {@code ItemAirUtils:177} spending
     * the air and then refusing protection anyway: "the suit must protect its wearer from stale zone
     * air; decisions since the window opened: …". NO HEALTH — {@code Atmosphere:137} applying the
     * exposure even to a protected player: "and no health may have been spent on it;
     * healthStart=20.0 healthAfter=19.0". PAYS — {@code ItemAirUtils:77} reporting the air spent
     * without writing it: "and it must PAY for that protection … before=1000 after=1000". Not
     * witnessed: the client's rendering of the drained suit, and the full-suit premise.</p>
     */
    @Test
    public void staleZoneAirDrainsTheSuitAndNotTheCrew() throws Exception {
        int originalDensity = snapshotDensity();
        try {
            setDensityAndConfirm(100, true);
            sealStaleZoneAndStandInIt();

            scenario().arranging("equip an air-carrying suit");
            arrangeProbe("stellurgytest player equip-airsuit 1000");
            assertEquals("the suit must start full so any fall belongs to this window",
                    1000, readChestAir());

            // Survival only once the suit is on: an unprotected settling tick here would spend the
            // wearer's health on the very hazard this scenario claims the suit covers.
            // Both marks BEFORE survival begins: in creative the hazard path never asks the suit, so
            // the first payment can only come after this point, and a mark taken later could miss it.
            Events events = events();
            long mark = events.markInstrumented();
            long clientMark = clientEvents().mark();
            scenario().measuring("health and suit air before the stale-air window");
            double healthStart = openSurvivalWindow();

            scenario().asserting("the suit covers the stale zone, and spends air doing it");
            // A LINK, not a budget: the suit paying is a record production publishes
            // (`suit_air_drained`, route "enchanted" for this suit — the chest's own buffer), and the
            // hazard path only spends inside its once-a-second gate, so a fixed wait was a guess at
            // how many of those gates a box of this speed would fit in.
            String drains = events.awaitRecordWithField(mark, "suit_air_drained", "route", "enchanted",
                    "the stale air must reach the suit's buffer — a drain is the proof the hazard path"
                            + " asked the suit at all", LINK_BUDGET_TICKS);

            // The suit HELD while it paid: the gate never recorded a refusal. The decision recorder
            // is edge-only, so the claim is the ABSENCE of `immune:false` in a window the drain above
            // proves was watched.
            String decisions = events.since(mark, "suit_immunity_decided");
            assertEquals("the suit must protect its wearer from stale zone air; decisions since the"
                            + " window opened: " + decisions + " | drains: " + drains,
                    0, Events.countRecords(decisions, "immune", "false"));

            int chestAirAfter = readChestAir();
            double healthAfter = health(bot().reportState());
            scenario().record("chestAirAfter", chestAirAfter).record("healthAfter", healthAfter);
            assertTrue("and no health may have been spent on it; healthStart=" + healthStart
                    + " healthAfter=" + healthAfter, healthAfter >= healthStart);
            assertTrue("and it must PAY for that protection — a fallback that costs nothing is not a"
                    + " fallback; before=1000 after=" + chestAirAfter, chestAirAfter < 1000);
            int clientAirAfter = clientChestAirOnceSynced(clientMark, v -> v < 1000,
                    "the client must render the drained suit, not a stale full one");
            scenario().record("clientChestAir", clientAirAfter);
            assertTrue("the client must render the drained suit, not a stale full one; client="
                    + clientAirAfter, clientAirAfter < 1000);
        } finally {
            restoreDim(originalDensity);
        }
    }
}
