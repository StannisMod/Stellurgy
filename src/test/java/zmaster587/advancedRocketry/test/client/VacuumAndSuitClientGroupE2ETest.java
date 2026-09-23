package zmaster587.advancedRocketry.test.client;

import com.google.gson.JsonObject;
import org.junit.FixMethodOrder;
import org.junit.Test;
import org.junit.runners.MethodSorters;


import zmaster587.advancedRocketry.test.Events;
import zmaster587.advancedRocketry.test.GameTicks;
import zmaster587.advancedRocketry.test.Reply;

import zmaster587.advancedRocketry.test.Plot;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/**
 * Vacuum, suits, and the air a player breathes. Eight scenarios, one client.
 *
 * <p>Every member works the same lever — flip the overworld's atmosphere density and watch what
 * happens to a player who is, or is not, wearing something that protects him — and every one of them
 * reads the outcome on the real client: health as the client renders it, the armour NBT the
 * inventory screen draws.</p>
 *
 * <h2>Why these eight share one harness</h2>
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
 * overwritten rather than inherited, and a set that has not propagated yet is an ARRANGEMENT
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
 * and each carried an {@code artest fill … minecraft:air} pre-clear because on some world seeds a
 * hillside filled that volume and the player suffocated — damage that a "vacuum hurts an unsuited
 * player" assertion happily accepts for the wrong reason (the ledger entry for that false green is
 * why the pre-clear exists). Here each scenario builds its own stone platform in open air inside its
 * own plot, so there is no terrain to clear and no seed that can put a hill in it.</p>
 *
 * <h2>What these scenarios WAIT for, and the one thing none of them can see</h2>
 *
 * <p>Every subject here is a link in one chain the atmosphere tick walks: the suit gate decided
 * ({@code suit_immunity_decided}), the suit paid for the decision ({@code suit_air_drained}, carrying
 * the {@code route} — {@code component} for the AR chest's pressure tank, {@code enchanted} for the
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
        String fill = exec("artest fill " + dim + " " + plot().x(PAD_DX) + " " + PAD_Y + " "
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

        exec("artest player clear-armor");
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
        String planet = exec("artest planet info " + plot().dim);
        return Reply.of("artest planet info", planet).integer(DENSITY);
    }

    /**
     * Sets the dimension's density and reads it back, as an ARRANGEMENT check.
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
        String set = exec("artest atmosphere set-density " + plot().dim + " " + density);
        scenario().requireArranged("set-density " + density + " failed: " + set,
                Reply.of(set).ok());
        ClientPoll.Result<Integer> reads = ClientPoll.until(
                bot()::waitTicks, this::snapshotDensity,
                d -> expectBreathable ? d >= 1 : d == 0, 2, 20);
        scenario().record("densityReadBack", reads.toString());
        scenario().requireArranged("the dimension must READ " + (expectBreathable ? "breathable"
                        + " (>=1)" : "vacuum (0)") + " before the window opens; " + reads,
                reads.satisfied);
    }

    private void restoreDim(int originalDensity) {
        try {
            exec("artest atmosphere set-density " + plot().dim + " " + Math.max(originalDensity, 1));
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
        String resp = exec("artest player held-air");
        Reply held = Reply.of("artest player held-air", resp);
        assertTrue("held-air response must include chestAir: " + resp, held.has(CHEST_AIR));
        return held.integer(CHEST_AIR);
    }

    /**
     * Server-side chest air via the COMPONENT route. {@code ItemSpaceChest} stores its O2 buffer
     * inside an embedded inventory's pressure-tank FluidStacks rather than as a top-level NBT key,
     * so the static-NBT probe reads 0 for it.
     */
    private int readChestAirComponentRoute() throws Exception {
        String resp = exec("artest player held-air-component-route");
        Reply held = Reply.of("artest player held-air-component-route", resp);
        assertTrue("held-air-component-route response must include chestAir: " + resp,
                held.has(CHEST_AIR));
        return held.integer(CHEST_AIR);
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
     * breathable atmosphere. The breathable {@code AtmosphereType.onTick} is a no-op, so
     * {@code protectsFrom} &rarr; {@code decrementAir} is never called and the tank's oxygen stays
     * at its initial value.
     */
    @Test
    public void breathableAtmosphereDoesNotDrainChestTank() throws Exception {
        int originalDensity = snapshotDensity();
        try {
            standOnOwnPlatformInSurvival();
            setDensityAndConfirm(100, true);

            scenario().arranging("equip the suit chest with a full pressure tank");
            String equip = exec("artest player equip-space-chest 1000");
            scenario().requireArranged("equip-space-chest must succeed: " + equip,
                    Reply.of(equip).ok());
            assertEquals("baseline chestAir", 1000, readChestAirComponentRoute());

            // The client armor[2] NBT syncs a tick or two AFTER the server-side equip; sampling it
            // immediately reads the -1 "not-synced" sentinel. Event-gated, not time-gated.
            ClientPoll.Result<Integer> baseline = ClientPoll.until(
                    bot()::waitTicks, this::clientChestAir, v -> v == 1000, 2, 20);
            scenario().requireArranged("client-rendered baseline must agree (1000); " + baseline,
                    baseline.satisfied);

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
     */
    @Test
    public void drainedChestTankTransitionsToVacuumDamage() throws Exception {
        int originalDensity = snapshotDensity();
        try {
            standOnOwnPlatformInSurvival();

            scenario().arranging("equip the suit chest with only three millibuckets of oxygen");
            String equip = exec("artest player equip-space-chest 3");
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
     */
    @Test
    public void standingOnPoweredPadRefillsSuitAir() throws Exception {
        int dim = plot().dim;
        int px = plot().x(PAD_DX + 1), py = PAD_Y, pz = plot().z(PAD_DZ + 1);

        scenario().arranging("place a charge pad, fill it with oxygen, and suit the player up");
        // The pad replaces one block of this scenario's own platform, so the player stands ON the
        // pad with solid ground either side of him.
        standOnOwnPlatformInSurvival();
        String place = exec("artest place " + dim + " " + px + " " + py + " " + pz
                + " advancedrocketry:oxygencharger");
        scenario().requireArranged("pad placement must succeed: " + place,
                Reply.of(place).ok());
        String inj = exec("artest fluid inject " + dim + " " + px + " " + py + " " + pz
                + " oxygen 8000");
        scenario().requireArranged("fluid inject must succeed: " + inj, Reply.of(inj).ok());

        // initialOxygen=500: half of the pressure tank's 1000 mB capacity, which leaves headroom for
        // the pad to actually add fluid. Equipping a full tank short-circuits the pad's
        // canPerformFunction body (amtFluid = 0) and the test would measure nothing.
        String equip = exec("artest player equip-space-chest 500");
        scenario().requireArranged("equip-space-chest must succeed: " + equip,
                Reply.of(equip).ok());

        scenario().measuring("the suit's air before standing on the pad");
        int airBefore = readChestAirComponentRoute();
        scenario().record("chestAirBefore", airBefore);
        scenario().requireArranged("baseline chest air must be > 0 (the probe filled the pressure"
                + " tank); actual=" + airBefore + " equip=" + equip, airBefore > 0);

        scenario().asserting("standing on the powered pad raises the suit's air, on both sides");
        Events events = events();
        long mark = events.markInstrumented();
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
        // The armour slot's NBT reaches the client on its own packet, some ticks after the fill the
        // event above reports. A persistent VALUE, so a bounded read-back rather than an event wait.
        ClientPoll.Result<Integer> synced = ClientPoll.until(
                bot()::waitTicks, this::clientChestAir, v -> v > airBefore, 2, 20);
        int clientAfter = synced.value == null ? -1 : synced.value.intValue();
        scenario().record("chestAirAfter", airAfter).record("clientChestAir", synced.toString());
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
     */
    @Test
    public void suitedPlayerInBreathableDimDoesNotLoseChestAir() throws Exception {
        int originalDensity = snapshotDensity();
        try {
            standOnOwnPlatformInSurvival();
            setDensityAndConfirm(100, true);

            scenario().arranging("equip the enchanted air suit with a full buffer");
            String equip = exec("artest player equip-airsuit 1000");
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
     */
    @Test
    public void suitedPlayerInVacuumLosesChestAirOverTime() throws Exception {
        int originalDensity = snapshotDensity();
        try {
            standOnOwnPlatformInSurvival();

            scenario().arranging("equip the enchanted air suit with a full buffer");
            String equip = exec("artest player equip-airsuit 1000");
            scenario().requireArranged("equip-airsuit must succeed: " + equip,
                    Reply.of(equip).ok());
            assertEquals("baseline chest air before vacuum exposure", 1000, readChestAir());

            double healthStart = health(bot().reportState());
            scenario().measuring("health before the vacuum window").record("healthStart", healthStart);
            Events events = events();
            long mark = events.markInstrumented();
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
                    + hurts + " | suit-diag " + exec("artest player suit-diag"),
                    0, Events.countRecords(hurts, "source", "Vacuum"));

            int chestAirAfter = readChestAir();
            // The armour NBT reaches the client on its own packet, after the drain the event above
            // reports — a persistent VALUE, so a bounded read-back rather than an event wait.
            ClientPoll.Result<Integer> synced = ClientPoll.until(
                    bot()::waitTicks, this::clientChestAir, v -> v >= 0 && v < 1000, 2, 20);
            int clientAir = synced.value == null ? -1 : synced.value.intValue();
            double healthAfter = health(bot().reportState());
            scenario().record("chestAirAfter", chestAirAfter).record("clientChestAir", synced.toString())
                    .record("healthAfter", healthAfter);

            assertTrue("chest air must decrease in vacuum with suit; before=1000 after="
                    + chestAirAfter + " drains=" + drains, chestAirAfter < FULL_TANK);
            assertTrue("client-rendered chest air must reflect the drain; client=" + clientAir
                    + " server=" + chestAirAfter, clientAir >= 0 && clientAir < FULL_TANK);
            // Health lost to anything other than vacuum (suffocation, fall, …) is a fixture failure
            // rather than a suit failure, and the message must say which — so the damage SOURCE
            // goes in the text beside the delta.
            assertTrue("suited player must not take vacuum damage; healthStart=" + healthStart
                    + " healthAfter=" + healthAfter + " diag=" + exec("artest player suit-diag"),
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
     */
    @Test
    public void vacuumDrainsOxygenFromChestSubInventoryTank() throws Exception {
        int originalDensity = snapshotDensity();
        try {
            standOnOwnPlatformInSurvival();

            scenario().arranging("equip the suit chest with a full pressure tank");
            String equip = exec("artest player equip-space-chest 1000");
            scenario().requireArranged("equip-space-chest must succeed: " + equip,
                    Reply.of(equip).ok());
            scenario().requireArranged("equip-space-chest must report oxygen filled in tank: "
                    + equip, (Reply.of(equip).integer("tankFilled") == FULL_TANK));
            assertEquals("baseline chestAir read via ItemAirUtils -> ItemSpaceChest.getAirRemaining"
                    + " -> sum of FluidStack amounts must equal 1000",
                    1000, readChestAirComponentRoute());

            double healthStart = health(bot().reportState());
            scenario().measuring("health before the vacuum window").record("healthStart", healthStart);
            Events events = events();
            long mark = events.markInstrumented();
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

            // The armour NBT reaches the client on its own packet — a persistent VALUE, read back
            // with a bounded poll rather than waited for as a link.
            ClientPoll.Result<Integer> synced = ClientPoll.until(
                    bot()::waitTicks, this::clientChestAir, v -> v >= 0 && v < 1000, 2, 20);
            int clientAirAfter = synced.value == null ? -1 : synced.value.intValue();
            int chestAirAfter = readChestAirComponentRoute();
            double healthAfter = health(bot().reportState());
            scenario().record("chestAirAfter", chestAirAfter).record("clientChestAir", synced.toString())
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
}
