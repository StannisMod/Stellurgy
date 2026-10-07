package dev.stannismod.stellurgy.test.server;

import org.junit.Test;

import dev.stannismod.stellurgy.libvulpes.tile.multiblock.TileMultiPowerConsumer;
import dev.stannismod.stellurgy.test.Events;
import dev.stannismod.stellurgy.test.FixtureSite;
import dev.stannismod.stellurgy.test.FluidStored;
import dev.stannismod.stellurgy.test.GameTicks;
import dev.stannismod.stellurgy.test.MachineInfo;
import dev.stannismod.stellurgy.test.Reply;

import static dev.stannismod.stellurgy.test.ArrangementFailure.arrangementFailed;
import static dev.stannismod.stellurgy.test.ArrangementFailure.requireArranged;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * The machine library's own rules in a running game: what a teardown leaves in a machine's hatches,
 * how a recipe pays for its ingredients when several hatches or several ingredients could pay, and
 * how a controller forms around a part whose chunk has gone and come back.
 *
 * <p>NEW-GROUP: the vendored machine library's base classes — formation, teardown and the recipe
 * cycle that every recipe machine inherits. The existing machine classes are each about ONE machine
 * (its recipe end to end, its structure validating); none of them holds the rules all of them share
 * and none of them owns.</p>
 *
 * <p>Every scenario but one stands a chemical reactor: it is the one shipped recipe machine with two
 * fluid input hatches, and it has the item hatches the other scenarios need. Its structure is two rows
 * deep — controller and power plugs in front, the hatches behind — which is what lets a chunk border
 * run between the controller and its item hatch. The one exception stands a precision laser etcher,
 * the machine whose recipes keep one of their ingredients.</p>
 *
 * <p>What this does not see: a player. Formation is asked through the probe's call of the
 * controller's own {@code attemptCompleteStructure}, a teardown is the controller block being
 * replaced with air (which runs the block's own break hook, as a player's break does), and a recipe
 * starts when the machine is switched on, which is the hatch-change path a GUI toggle also takes.</p>
 */
public class MachineLibraryGroupTest extends AbstractSharedServerTest {

    /** The machine every scenario stands — see the class comment for why this one. */
    private static final String MACHINE = "chemical-reactor";

    /** Its tile's simple class name, which is what {@code machine recipe-info} is keyed by. */
    private static final String MACHINE_CLASS = "TileChemicalReactor";

    /**
     * Bit 3 of a hatch's variant is "formed" — {@code BlockMultiblockStructure#hideBlock} sets it,
     * {@code destroyStructure} clears it.
     */
    private static final int FORMED_BIT = 8;

    /** What the teardown scenario puts in an item hatch: any stack the hatch holds whole. */
    private static final String HELD_ITEM = "minecraft:dirt";
    private static final int HELD_ITEMS = 5;

    /** What it puts in a fluid hatch: any fluid and amount the hatch takes whole. */
    private static final String HELD_FLUID = "water";
    private static final int HELD_FLUID_MB = 1000;

    /** The item the ingredient-claiming scenario lists twice: any item an input hatch takes. */
    private static final String CLAIMED_ITEM = "minecraft:stick";

    /** The ingredient whose recipe the fluid scenario starts: the only reactor recipe it appears in. */
    private static final String FLUID_RECIPE_ITEM = "minecraft:bone";

    /** One read step of {@link Events}' waits, in ticks — the granularity a link is noticed at. */
    private static final int READ_STEP_TICKS = 5;

    // ---- the fixture -------------------------------------------------------------------------

    /** A reactor standing at a chosen controller position, formed, with where its parts are. */
    private static final class Reactor {
        final int dim;
        final int cx, cy, cz;
        final int[] input;
        final int[][] fluidInputs;
        final int[][] powerPlugs;

        Reactor(int dim, int cx, int cy, int cz, Reply fixture) {
            this.dim = dim;
            this.cx = cx;
            this.cy = cy;
            this.cz = cz;
            this.input = fixture.requireBlockPos("inputPos");
            this.fluidInputs = fixture.blockPosArray("liquidInputPositions");
            this.powerPlugs = fixture.blockPosArray("powerPositions");
        }

        String controller() {
            return dim + " " + cx + " " + cy + " " + cz;
        }
    }

    private static String at(int dim, int[] pos) {
        return dim + " " + pos[0] + " " + pos[1] + " " + pos[2];
    }

    /** Builds the reactor's blocks with its controller at {@code (cx, cy, cz)}; not yet formed. */
    private Reactor standReactor(int dim, int cx, int cy, int cz) throws Exception {
        String cmd = "stellurgytest fixture machine " + MACHINE + " " + dim + " " + cx + " " + cy + " " + cz;
        Reply fixture = arrange(cmd);
        requireArranged("every cell of the reactor must have resolved to a block: " + fixture,
                fixture.integer("unresolved") == 0);
        Reactor reactor = new Reactor(dim, cx, cy, cz, fixture);
        requireArranged("the reactor must report more than one fluid input hatch: " + fixture,
                reactor.fluidInputs.length > 1);
        return reactor;
    }

    /** Forms a stood reactor, refusing as an arrangement failure when it does not form. */
    private void form(Reactor reactor) throws Exception {
        String cmd = "stellurgytest machine try-complete " + reactor.controller();
        Reply formed = arrange(cmd);
        requireArranged("the reactor must form before the scenario starts: " + formed, formed.bool("isComplete"));
    }

    private boolean isComplete(Reactor reactor) throws Exception {
        return MachineInfo.at(this::exec, reactor.dim, reactor.cx, reactor.cy, reactor.cz).isComplete();
    }

    private int blockMeta(int dim, int[] pos) throws Exception {
        return ask("stellurgytest block at " + at(dim, pos)).integer("meta");
    }

    private Reply hatchContents(int dim, int[] pos) throws Exception {
        return ask("stellurgytest hatch read " + at(dim, pos));
    }

    private FluidStored fluidIn(int dim, int[] pos) throws Exception {
        return FluidStored.of(exec("stellurgytest fluid stored " + at(dim, pos)));
    }

    private int fill(int dim, int[] pos, String fluid, int mb) throws Exception {
        return arrange("stellurgytest fluid inject " + at(dim, pos) + " " + fluid + " " + mb).integer("filled");
    }

    private boolean chunkLoaded(int dim, int chunkX, int chunkZ) throws Exception {
        return arrange("stellurgytest chunk loaded " + dim + " " + chunkX + " " + chunkZ).bool("loaded");
    }

    private Events events() {
        return new Events(this::exec, ticks -> GameTicks.advance(client(), GameTicks.server(), ticks),
                evictionReports());
    }

    // ---- teardown ----------------------------------------------------------------------------

    /**
     * Tearing a machine down leaves its hatches holding what they held.
     *
     * <p>Contract: fails if {@code TileMultiBlock#destroyBlockAt} stops deciding that a part whose
     * block keeps a tile after unforming keeps THAT tile — the one holding the items and fluid — and
     * gives it up for a fresh, empty one. The hatch is not broken here; only the controller is.</p>
     * <p>red-witnessed: with {@code TileMultiBlock#destroyBlockAt} at {@code if(!unformed.getBlock().hasTileEntity(unformed))} removed (every pointer invalidated), fails: "after the teardown the item hatch must still hold the 5 minecraft:dirt it held and the fluid hatch its 1000 mB of water — items: ("size":4,"slots":[]), fluid: ("tileClass":"dev.stannismod.stellurgy.li …" (2026-10-07).</p>
     */
    @Test
    public void tearingDownAMachineLeavesItsHatchesHoldingWhatTheyHeld() throws Exception {
        FixtureSite site = clearedSite(2, 3, "the reactor's volume");
        Reactor reactor = standReactor(site.dim, site.x + 2, site.y + 2, site.z + 1);
        form(reactor);
        int[] fluidHatch = reactor.fluidInputs[0];

        arrange("stellurgytest hatch fill " + at(reactor.dim, reactor.input) + " 0 " + HELD_ITEM + " " + HELD_ITEMS);
        requireArranged("the fluid hatch must take all of what it is given",
                fill(reactor.dim, fluidHatch, HELD_FLUID, HELD_FLUID_MB) == HELD_FLUID_MB);
        requireArranged("the item hatch must hold its stack before the teardown: "
                        + hatchContents(reactor.dim, reactor.input),
                hatchContents(reactor.dim, reactor.input).holdsElementWithAll("slots",
                        "slot", "0", "item", HELD_ITEM, "count", String.valueOf(HELD_ITEMS)));
        requireArranged("the item hatch must stand formed before the teardown",
                (blockMeta(reactor.dim, reactor.input) & FORMED_BIT) != 0);

        arrange("stellurgytest place " + reactor.controller() + " minecraft:air");

        assertEquals("removing the controller must have unformed the item hatch — otherwise nothing was"
                        + " torn down and the readings below say nothing about a teardown",
                0, blockMeta(reactor.dim, reactor.input) & FORMED_BIT);
        Reply items = hatchContents(reactor.dim, reactor.input);
        FluidStored fluid = fluidIn(reactor.dim, fluidHatch);
        assertTrue("after the teardown the item hatch must still hold the " + HELD_ITEMS + " " + HELD_ITEM
                        + " it held and the fluid hatch its " + HELD_FLUID_MB + " mB of " + HELD_FLUID
                        + " — items: " + items + ", fluid: " + fluid,
                items.holdsElementWithAll("slots", "slot", "0", "item", HELD_ITEM,
                        "count", String.valueOf(HELD_ITEMS))
                        && fluid.amountOf(HELD_FLUID) == HELD_FLUID_MB);
    }

    // ---- the recipe cycle --------------------------------------------------------------------

    /** The one reactor recipe naming {@link #FLUID_RECIPE_ITEM}: its fluid and how much of it. */
    private Reply fluidRecipeIngredient() throws Exception {
        String first = "stellurgytest machine recipe-info " + MACHINE_CLASS + " 0";
        int total = ask(first).integer("totalRecipes");
        Reply found = null;
        int matches = 0;
        for (int i = 0; i < total; i++) {
            Reply recipe = ask("stellurgytest machine recipe-info " + MACHINE_CLASS + " " + i);
            if (recipe.holdsElement("ingredients", "item", FLUID_RECIPE_ITEM)) {
                matches++;
                found = recipe;
            }
        }
        if (matches != 1 || found.objectArray("fluidIngredients").length != 1) {
            arrangementFailed("exactly one reactor recipe must name " + FLUID_RECIPE_ITEM + ", with one fluid"
                    + " ingredient — found " + matches + (found == null ? "" : ", the last: " + found));
        }
        return Reply.of("the " + FLUID_RECIPE_ITEM + " recipe's fluid ingredient",
                found.objectArray("fluidIngredients")[0]);
    }

    /**
     * A recipe draws its fluid once, however many fluid input hatches could pay it.
     *
     * <p>Contract: fails if {@code TileMultiblockMachine#drainFluidIngredients} stops deciding that each
     * fluid ingredient is drained as what is STILL OWED of it, hatch after hatch, rather than in full
     * from every hatch that holds it. Each hatch here holds exactly one recipe's worth, so the two
     * answers differ by one whole recipe's worth.</p>
     * <p>red-witnessed (taken while this loop stood in {@code consumeItems}; moved unchanged into
     * {@code drainFluidIngredients} 2026-10-07): with {@code TileMultiblockMachine#drainFluidIngredients} at {@code FluidStack drainedFluid = fluidInput.drainInternal(stillOwed, true);} draining the whole ingredient from each hatch, its {@code fluidInputCounter[i] <= 0} guard removed, fails: "starting the recipe must draw 10 mB of nitrogen in total from the 2 hatches, which held 10 each — left: ("tileClass":"dev.stannismod.stellurgy.libvulpes.tile.multiblock.hatch.TileFluidHatch","hasFluid …" (2026-10-07).</p>
     */
    @Test
    public void aRecipeDrawsItsFluidOnceAcrossTwoFluidInputHatches() throws Exception {
        Reply ingredient = fluidRecipeIngredient();
        String fluid = ingredient.text("fluid");
        int owed = ingredient.integer("amount");

        FixtureSite site = clearedSite(2, 3, "the reactor's volume");
        Reactor reactor = standReactor(site.dim, site.x + 2, site.y + 2, site.z + 1);
        form(reactor);
        for (int[] hatch : reactor.fluidInputs) {
            requireArranged("each fluid input hatch must take one recipe's worth of " + fluid,
                    fill(reactor.dim, hatch, fluid, owed) == owed);
        }
        arrange("stellurgytest hatch fill " + at(reactor.dim, reactor.input) + " 0 " + FLUID_RECIPE_ITEM + " 1");

        Reply enabled = arrange("stellurgytest machine set-enabled " + reactor.controller() + " true");
        requireArranged("the reactor must switch on: " + enabled, enabled.bool("enabled"));

        MachineInfo info = MachineInfo.at(this::exec, reactor.dim, reactor.cx, reactor.cy, reactor.cz);
        assertTrue("switching the reactor on must have started a recipe: " + info,
                info.reply().bool("isRunning"));
        Reply items = hatchContents(reactor.dim, reactor.input);
        assertFalse("the recipe that started must be the " + FLUID_RECIPE_ITEM + " one — its "
                        + FLUID_RECIPE_ITEM + " must have been taken: " + items,
                items.holdsElement("slots", "item", FLUID_RECIPE_ITEM));

        int left = 0;
        StringBuilder each = new StringBuilder();
        for (int[] hatch : reactor.fluidInputs) {
            FluidStored stored = fluidIn(reactor.dim, hatch);
            left += stored.amountOf(fluid);
            each.append(' ').append(stored);
        }
        int held = reactor.fluidInputs.length * owed;
        assertEquals("starting the recipe must draw " + owed + " mB of " + fluid + " in total from the "
                        + reactor.fluidInputs.length + " hatches, which held " + owed + " each — left:" + each,
                held - owed, left);
    }

    /**
     * A recipe that lists the same item twice needs two of it, and starting it takes two.
     *
     * <p>Contract: fails if {@code TileMultiblockMachine#canProcessRecipe} stops deciding that a slot
     * already promised to one ingredient cannot also pay the next, or if
     * {@code TileMultiblockMachine#consumeItems} stops taking from exactly the slots that check
     * claimed. No shipped recipe repeats an item, so the recipe is handed to the machine by the probe
     * (see the {@code vulpes recipe-claim} verb); what is under test is how the machine matches it.</p>
     * <p>red-witnessed: with {@code TileMultiblockMachine#claimFrom} at {@code stackInSlot.getCount() - takenFromSlot[i] >= stack.getCount()} without {@code - takenFromSlot[i]}, fails: "one minecraft:stick must not satisfy a recipe that lists it twice" (2026-10-07).</p>
     */
    @Test
    public void anIngredientListedTwiceNeedsAndTakesTwoItems() throws Exception {
        FixtureSite site = clearedSite(2, 3, "the reactor's volume");
        Reactor reactor = standReactor(site.dim, site.x + 2, site.y + 2, site.z + 1);
        form(reactor);
        String claim = "stellurgytest vulpes recipe-claim " + reactor.controller() + " " + CLAIMED_ITEM + " 1 ";

        arrange("stellurgytest hatch fill " + at(reactor.dim, reactor.input) + " 0 " + CLAIMED_ITEM + " 1");
        assertTrue("one " + CLAIMED_ITEM + " must satisfy a recipe that lists it once",
                arrange(claim + "1 false").bool("canProcess"));
        assertFalse("one " + CLAIMED_ITEM + " must not satisfy a recipe that lists it twice",
                arrange(claim + "2 false").bool("canProcess"));

        arrange("stellurgytest hatch fill " + at(reactor.dim, reactor.input) + " 0 " + CLAIMED_ITEM + " 2");
        Reply started = arrange(claim + "2 true");
        assertTrue("two " + CLAIMED_ITEM + " must satisfy a recipe that lists it twice: " + started,
                started.bool("canProcess") && started.bool("consumed"));
        Reply items = hatchContents(reactor.dim, reactor.input);
        assertFalse("starting a recipe that lists " + CLAIMED_ITEM + " twice must take both: " + items,
                items.holdsElement("slots", "item", CLAIMED_ITEM));
    }

    /** The etching lens, which the precision laser etcher's recipes list and never use up. */
    private static final String LENS = "stellurgy:lens";

    /**
     * The precision laser etcher takes exactly the slots its start check claimed, and keeps its lens.
     *
     * <p>Contract: fails if the etcher stops starting a recipe through
     * {@code TileMultiblockMachine#consumeItems} — the claim {@code canProcessRecipe} made — and takes
     * its ingredients by a first-fit of its own, or if {@code TilePrecisionLaserEtcher#consumesIngredient}
     * stops deciding that the lens stays. The recipe is handed to the machine by the probe (the
     * {@code vulpes recipe-take} verb): a lens, one stick, then two sticks, against a hatch holding the
     * lens, a stack of two sticks and a stack of one. Only one matching exists — the single stick
     * from the stack of one, the pair from the stack of two — and a first-fit that hands the single
     * stick the stack of two finds no pair left for the second ingredient, so it starts the recipe
     * with two sticks still in the hatch.</p>
     * <p>red-witnessed: with {@code TilePrecisionLaserEtcher#consumesIngredient} at
     * {@code return !isLensItem(ingredient);} overridden by the etcher's former {@code consumeItems}
     * (its own first-fit over {@code getItemInPorts()} skipping {@code isLensItem(stack)}) put back,
     * fails: "starting the recipe must take all three sticks it claimed — hatch now: (… slot 1 stick
     * count 1, slot 2 stick count 1)" (2026-10-07). With the same {@code return !isLensItem(ingredient);}
     * answering true, fails: "the etcher must keep its lens, which the recipe needs present and never
     * uses up — hatch now: ("size":4,"slots":[])" (2026-10-07).</p>
     */
    @Test
    public void theEtcherTakesWhatItsCheckClaimedAndKeepsItsLens() throws Exception {
        FixtureSite site = clearedSite(1, 3, "the etcher's volume");
        int cx = site.x + 2;
        int cy = site.y + 1;
        int cz = site.z + 1;
        String controller = site.dim + " " + cx + " " + cy + " " + cz;
        Reply fixture = arrange("stellurgytest fixture machine precision-laser-etcher " + controller);
        requireArranged("every cell of the etcher must have resolved to a block: " + fixture,
                fixture.integer("unresolved") == 0);
        int[] input = fixture.requireBlockPos("inputPos");
        Reply formed = arrange("stellurgytest machine try-complete " + controller);
        requireArranged("the etcher must form before the scenario starts: " + formed, formed.bool("isComplete"));

        arrange("stellurgytest hatch fill " + at(site.dim, input) + " 0 " + LENS + " 1");
        arrange("stellurgytest hatch fill " + at(site.dim, input) + " 1 " + CLAIMED_ITEM + " 2");
        arrange("stellurgytest hatch fill " + at(site.dim, input) + " 2 " + CLAIMED_ITEM + " 1");
        Reply before = hatchContents(site.dim, input);
        requireArranged("the hatch must hold the lens, two sticks and one stick before the recipe starts: "
                        + before,
                before.holdsElementWithAll("slots", "slot", "0", "item", LENS, "count", "1")
                        && before.holdsElementWithAll("slots", "slot", "1", "item", CLAIMED_ITEM, "count", "2")
                        && before.holdsElementWithAll("slots", "slot", "2", "item", CLAIMED_ITEM, "count", "1"));

        Reply started = arrange("stellurgytest vulpes recipe-take " + controller + " true "
                + LENS + " 1 " + CLAIMED_ITEM + " 1 " + CLAIMED_ITEM + " 2");
        requireArranged("the hatch must satisfy the recipe, or nothing was started: " + started,
                started.bool("canProcess") && started.bool("consumed"));

        Reply items = hatchContents(site.dim, input);
        assertFalse("starting the recipe must take all three sticks it claimed — hatch now: " + items,
                items.holdsElement("slots", "item", CLAIMED_ITEM));
        assertTrue("the etcher must keep its lens, which the recipe needs present and never uses up —"
                        + " hatch now: " + items,
                items.holdsElementWithAll("slots", "slot", "0", "item", LENS, "count", "1"));
    }

    // ---- formation across a chunk border -----------------------------------------------------

    /**
     * Stands a reactor whose controller row and hatch row are in two different chunks, side by side
     * along Z: the controller's chunk is {@code controllerChunk}, the item input hatch's is
     * {@code hatchChunk}.
     */
    private Reactor standAcrossAChunkBorder(FixtureSite site) throws Exception {
        int cx = site.x + 2;
        int cz = site.z | 15;
        Reactor reactor = standReactor(site.dim, cx, site.y + 2, cz);
        requireArranged("the controller and its item input hatch must stand in different chunks: controller z="
                        + cz + ", hatch z=" + reactor.input[2],
                (cz >> 4) != (reactor.input[2] >> 4));
        requireArranged("the reactor's three columns must share one chunk along X: x=" + (cx - 1) + ".." + (cx + 1),
                ((cx - 1) >> 4) == ((cx + 1) >> 4));
        return reactor;
    }

    /**
     * Asking a controller to form does not load the chunk of the part it asks about.
     *
     * <p>Contract: fails if {@code TileMultiBlock#completeStructure} stops deciding "this part is not
     * loaded, refuse" from what is in memory, and asks a question that loads the chunk to answer it.
     * The power plugs are swapped for plain Forge input plugs first: the creative plug the fixture
     * places pushes energy into its neighbours every tick, and its neighbour across the border would
     * load that chunk on its own.</p>
     * <p>red-witnessed: with {@code TileMultiBlock#completeStructure} at {@code if(!world.isBlockLoaded(globalPos, false))} asking {@code world.getChunkFromBlockCoords(globalPos).isLoaded()}, fails: "a controller whose part stands in an unloaded chunk must refuse to form, and asking must not load that chunk — chunk loaded after the attempt: true, attempt" (2026-10-07).</p>
     */
    @Test
    public void formingAMachineDoesNotLoadThePartsChunkItAsksAbout() throws Exception {
        FixtureSite site = clearedSite(12, 3, "the reactor's volume, which reaches the next chunk south");
        Reactor reactor = standAcrossAChunkBorder(site);
        for (int[] plug : reactor.powerPlugs) {
            arrange("stellurgytest place " + at(reactor.dim, plug) + " libvulpes:forgepowerinput");
        }
        form(reactor);
        int chunkX = reactor.cx >> 4;
        int controllerChunkZ = reactor.cz >> 4;
        int hatchChunkZ = reactor.input[2] >> 4;
        Events events = events();

        arrange("stellurgytest chunk forceload " + reactor.dim + " " + chunkX + " " + controllerChunkZ);
        try {
            long mark = events.markInstrumented();
            Reply drop = arrange("stellurgytest vulpes chunk-drop " + reactor.dim + " " + chunkX + " " + hatchChunkZ);
            requireArranged("the hatch's chunk must be out of memory before the controller is asked: " + drop,
                    drop.bool("dropped") && !chunkLoaded(reactor.dim, chunkX, hatchChunkZ));
            // A dropped chunk's tiles hear of it on the world's next tick (World#updateEntities), and
            // the hatch tells its controller then; the record is that telling.
            events.awaitRecordWithFields(mark, "multiblock_part_lost",
                    "the item hatch unloading must un-complete its controller", READ_STEP_TICKS,
                    "x", String.valueOf(reactor.cx), "y", String.valueOf(reactor.cy), "z", String.valueOf(reactor.cz));
            requireArranged("the item hatch unloading must have un-completed its controller",
                    !isComplete(reactor));
            requireArranged("the hatch's chunk must still be out of memory when the controller is asked, or a"
                    + " load below could not be put down to the asking", !chunkLoaded(reactor.dim, chunkX, hatchChunkZ));

            Reply attempt = arrange("stellurgytest machine try-complete " + reactor.controller());
            boolean loadedByTheAttempt = chunkLoaded(reactor.dim, chunkX, hatchChunkZ);

            assertTrue("a controller whose part stands in an unloaded chunk must refuse to form, and asking"
                            + " must not load that chunk — chunk loaded after the attempt: " + loadedByTheAttempt
                            + ", attempt: " + attempt,
                    !loadedByTheAttempt && !attempt.bool("isComplete"));

            Reply reload = arrange("stellurgytest chunk cycle " + reactor.dim + " " + chunkX + " " + hatchChunkZ);
            requireArranged("the hatch's chunk must be back for the control: " + reload, reload.bool("reloaded"));
            Reply control = arrange("stellurgytest machine try-complete " + reactor.controller());
            requireArranged("with the part's chunk back the same structure must form, or the refusal above"
                    + " cannot be put down to the unloaded chunk: " + control, control.bool("isComplete"));
        } finally {
            exec("stellurgytest chunk release " + reactor.dim + " " + chunkX + " " + controllerChunkZ);
        }
    }

    /**
     * A part whose chunk unloads un-completes its controller, though the controller stands one block
     * from the border and its own chunk stays loaded.
     *
     * <p>Contract: fails if {@code TilePointer#getFinalPointedTile} stops deciding that a master is
     * reachable when its OWN chunk is loaded, and asks instead whether an area around it is — which,
     * for a controller on a chunk border, is false exactly when the part's chunk has gone, so the part
     * never tells it and the controller runs on with a part that is not in the world. The power plugs
     * are swapped for plain Forge input plugs first: the creative plug the fixture places pushes energy
     * into its neighbours every tick, and its neighbour across the border would load that chunk back
     * and make the area whole again.</p>
     * <p>red-witnessed: with {@code TilePointer#getFinalPointedTile} at {@code if(world.isBlockLoaded(masterBlockPos, false))} asking {@code world.isAreaLoaded(masterBlockPos, 1)}, fails: "the item hatch, unloading, must tell its controller across the chunk border — no `multiblock_part_lost` carrying x = 4022 and y = 152 and z = 4031 was recorded within 5 ticks. What DID happen since th …" (2026-10-07).</p>
     */
    @Test
    public void aPartUnloadingAcrossAChunkBorderUncompletesItsController() throws Exception {
        FixtureSite site = clearedSite(12, 3, "the reactor's volume, which reaches the next chunk south");
        Reactor reactor = standAcrossAChunkBorder(site);
        for (int[] plug : reactor.powerPlugs) {
            arrange("stellurgytest place " + at(reactor.dim, plug) + " libvulpes:forgepowerinput");
        }
        form(reactor);
        int chunkX = reactor.cx >> 4;
        int controllerChunkZ = reactor.cz >> 4;
        int hatchChunkZ = reactor.input[2] >> 4;
        Events events = events();

        arrange("stellurgytest chunk forceload " + reactor.dim + " " + chunkX + " " + controllerChunkZ);
        try {
            long mark = events.markInstrumented();
            Reply drop = arrange("stellurgytest vulpes chunk-drop " + reactor.dim + " " + chunkX + " " + hatchChunkZ);
            requireArranged("the hatch's chunk must be out of memory: " + drop,
                    drop.bool("dropped") && !chunkLoaded(reactor.dim, chunkX, hatchChunkZ));

            // A dropped chunk's tiles hear of it on the world's next tick (World#updateEntities), which
            // runs every tick here because the controller's chunk is held; one read step covers it.
            events.awaitRecordWithFields(mark, "multiblock_part_lost",
                    "the item hatch, unloading, must tell its controller across the chunk border", READ_STEP_TICKS,
                    "x", String.valueOf(reactor.cx), "y", String.valueOf(reactor.cy), "z", String.valueOf(reactor.cz));
            requireArranged("the hatch's chunk must still be out of memory when the telling is read, or it may"
                    + " have come from the chunk loading back", !chunkLoaded(reactor.dim, chunkX, hatchChunkZ));
            assertFalse("a controller whose part has unloaded must not stand complete", isComplete(reactor));
        } finally {
            exec("stellurgytest chunk release " + reactor.dim + " " + chunkX + " " + controllerChunkZ);
        }
    }

    /**
     * A recipe machine whose item hatch's chunk unloaded and came back forms again on its own.
     *
     * <p>Contract: fails if {@code TileMultiblockMachine#update} stops asking an incomplete machine to
     * re-form every {@code TileMultiPowerConsumer#FORMATION_RETRY_PERIOD_TICKS}. The unload is real:
     * the hatch's chunk is saved, dropped and read back, and the hatch un-completes its controller as
     * it unloads. Nobody right-clicks anything afterwards.</p>
     * <p>red-witnessed: with {@code TileMultiblockMachine#update} at {@code retryFormationIfDue();} removed, fails: "the machine must form again on its own once its part is back — no `multiblock_formation` carrying x = 4022 and y = 152 and z = 4031 and complete = true was recorded within 1010 ticks. What DID happen  …" (2026-10-07).</p>
     */
    @Test
    public void aRecipeMachineWhosePartCameBackFormsAgainOnItsOwn() throws Exception {
        FixtureSite site = clearedSite(12, 3, "the reactor's volume, which reaches the next chunk south");
        Reactor reactor = standAcrossAChunkBorder(site);
        form(reactor);
        int chunkX = reactor.cx >> 4;
        int controllerChunkZ = reactor.cz >> 4;
        int hatchChunkZ = reactor.input[2] >> 4;
        Events events = events();

        arrange("stellurgytest chunk forceload " + reactor.dim + " " + chunkX + " " + controllerChunkZ);
        boolean hatchChunkHeld = false;
        try {
            long mark = events.markInstrumented();
            Reply cycle = arrange("stellurgytest chunk cycle " + reactor.dim + " " + chunkX + " " + hatchChunkZ);
            requireArranged("the hatch's chunk must have been dropped and read back: " + cycle,
                    cycle.bool("dropped") && cycle.bool("reloaded"));
            arrange("stellurgytest chunk forceload " + reactor.dim + " " + chunkX + " " + hatchChunkZ);
            hatchChunkHeld = true;

            events.awaitRecordWithFields(mark, "multiblock_part_lost",
                    "the item hatch unloading must un-complete its controller", READ_STEP_TICKS,
                    "x", String.valueOf(reactor.cx), "y", String.valueOf(reactor.cy), "z", String.valueOf(reactor.cz));

            // The budget is production's retry period, plus two read steps for the wait to notice.
            // Nothing tries to form the machine between the mark and the unload — it is complete — so
            // a complete formation in this window comes after the part was lost.
            events.awaitRecordWithFields(mark, "multiblock_formation",
                    "the machine must form again on its own once its part is back",
                    (int) TileMultiPowerConsumer.FORMATION_RETRY_PERIOD_TICKS + 2 * READ_STEP_TICKS,
                    "x", String.valueOf(reactor.cx), "y", String.valueOf(reactor.cy), "z", String.valueOf(reactor.cz),
                    "complete", "true");

            assertTrue("the machine must stand complete after re-forming, not only have formed once",
                    isComplete(reactor));
        } finally {
            exec("stellurgytest chunk release " + reactor.dim + " " + chunkX + " " + controllerChunkZ);
            if (hatchChunkHeld) {
                exec("stellurgytest chunk release " + reactor.dim + " " + chunkX + " " + hatchChunkZ);
            }
        }
    }
}
