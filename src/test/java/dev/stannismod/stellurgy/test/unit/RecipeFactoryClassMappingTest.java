package dev.stannismod.stellurgy.test.unit;

import org.junit.Test;
import dev.stannismod.stellurgy.recipe.RecipeCentrifuge;
import dev.stannismod.stellurgy.recipe.RecipeChemicalReactor;
import dev.stannismod.stellurgy.recipe.RecipeCrystallizer;
import dev.stannismod.stellurgy.recipe.RecipeCuttingMachine;
import dev.stannismod.stellurgy.recipe.RecipeElectricArcFurnace;
import dev.stannismod.stellurgy.recipe.RecipeElectrolyser;
import dev.stannismod.stellurgy.recipe.RecipeLathe;
import dev.stannismod.stellurgy.recipe.RecipePrecisionAssembler;
import dev.stannismod.stellurgy.recipe.RecipePrecisionLaserEtcher;
import dev.stannismod.stellurgy.recipe.RecipeRollingMachine;
import dev.stannismod.stellurgy.tile.multiblock.machine.TileCentrifuge;
import dev.stannismod.stellurgy.tile.multiblock.machine.TileChemicalReactor;
import dev.stannismod.stellurgy.tile.multiblock.machine.TileCrystallizer;
import dev.stannismod.stellurgy.tile.multiblock.machine.TileCuttingMachine;
import dev.stannismod.stellurgy.tile.multiblock.machine.TileElectricArcFurnace;
import dev.stannismod.stellurgy.tile.multiblock.machine.TileElectrolyser;
import dev.stannismod.stellurgy.tile.multiblock.machine.TileLathe;
import dev.stannismod.stellurgy.tile.multiblock.machine.TilePrecisionAssembler;
import dev.stannismod.stellurgy.tile.multiblock.machine.TilePrecisionLaserEtcher;
import dev.stannismod.stellurgy.tile.multiblock.machine.TileRollingMachine;
import zmaster587.libVulpes.recipe.RecipeMachineFactory;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

/**
 *
 * Each {@code Recipe*} class is a thin {@code RecipeMachineFactory} subclass
 * whose only job is to bind the parsed recipe JSON to a specific tile
 * machine via {@link RecipeMachineFactory#getMachine()}. A typo (wrong tile
 * class) would silently route recipes to the wrong machine — recipes "stop
 * working" with no error. Pin the mapping here so any future rename surfaces
 * immediately.
 */
public class RecipeFactoryClassMappingTest {

    @Test
    public void recipeLatheBindsToTileLathe() {
        assertBinding(new RecipeLathe(), TileLathe.class);
    }

    @Test
    public void recipeCentrifugeBindsToTileCentrifuge() {
        assertBinding(new RecipeCentrifuge(), TileCentrifuge.class);
    }

    @Test
    public void recipeCrystallizerBindsToTileCrystallizer() {
        assertBinding(new RecipeCrystallizer(), TileCrystallizer.class);
    }

    @Test
    public void recipeCuttingMachineBindsToTileCuttingMachine() {
        assertBinding(new RecipeCuttingMachine(), TileCuttingMachine.class);
    }

    @Test
    public void recipeElectricArcFurnaceBindsToTileElectricArcFurnace() {
        assertBinding(new RecipeElectricArcFurnace(), TileElectricArcFurnace.class);
    }

    @Test
    public void recipeElectrolyserBindsToTileElectrolyser() {
        assertBinding(new RecipeElectrolyser(), TileElectrolyser.class);
    }

    @Test
    public void recipeChemicalReactorBindsToTileChemicalReactor() {
        assertBinding(new RecipeChemicalReactor(), TileChemicalReactor.class);
    }

    @Test
    public void recipePrecisionAssemblerBindsToTilePrecisionAssembler() {
        assertBinding(new RecipePrecisionAssembler(), TilePrecisionAssembler.class);
    }

    @Test
    public void recipePrecisionLaserEtcherBindsToTilePrecisionLaserEtcher() {
        assertBinding(new RecipePrecisionLaserEtcher(), TilePrecisionLaserEtcher.class);
    }

    @Test
    public void recipeRollingMachineBindsToTileRollingMachine() {
        assertBinding(new RecipeRollingMachine(), TileRollingMachine.class);
    }

    private static void assertBinding(RecipeMachineFactory factory, Class<?> expected) {
        Class<?> bound = factory.getMachine();
        assertNotNull("getMachine() returned null for " + factory.getClass().getSimpleName(), bound);
        assertEquals(
                "Recipe factory " + factory.getClass().getSimpleName()
                        + " bound to unexpected tile class — recipes would silently route to the wrong machine",
                expected, bound);
        // Each Recipe* extends RecipeMachineFactory directly; preserve the inheritance shape
        // so the recipe-loader's instanceof check keeps working.
        assertTrue("Recipe factory " + factory.getClass().getSimpleName()
                        + " no longer extends RecipeMachineFactory",
                RecipeMachineFactory.class.isAssignableFrom(factory.getClass()));
    }
}
