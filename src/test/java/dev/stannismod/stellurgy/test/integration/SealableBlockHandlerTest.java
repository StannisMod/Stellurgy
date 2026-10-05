package dev.stannismod.stellurgy.test.integration;

import net.minecraft.block.Block;
import net.minecraft.block.material.Material;
import net.minecraft.init.Blocks;
import org.junit.BeforeClass;
import org.junit.Test;
import dev.stannismod.stellurgy.test.MinecraftBootstrap;
import dev.stannismod.stellurgy.util.SealableBlockHandler;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * SealableBlockHandler — pure list-management logic.
 *
 * The world-level seal check ({@code isBlockSealed}) needs a real World and is
 * exercised in scenario tests. Here we only cover allow/ban list
 * mutation and {@code loadDefaultData}.
 *
 * <p>Each test builds its own handler, so nothing it lists reaches the game's
 * {@link SealableBlockHandler#INSTANCE} or another test.</p>
 */
public class SealableBlockHandlerTest {

    private final SealableBlockHandler handler = new SealableBlockHandler();

    @BeforeClass
    public static void bootstrap() throws Exception {
        MinecraftBootstrap.ensure();
    }

    @Test
    public void defaultSealableBlocksLoaded() throws Exception {
        handler.loadDefaultData();

        // The set explicitly enumerated in loadDefaultData() must end up on the ban list.
        assertTrue(handler.isMaterialBanned(Material.AIR));
        assertTrue(handler.isMaterialBanned(Material.FIRE));
        assertTrue(handler.isMaterialBanned(Material.LEAVES));
        assertTrue(handler.isMaterialBanned(Material.WEB));
        assertTrue(handler.isMaterialBanned(Material.PLANTS));
        assertTrue(handler.isMaterialBanned(Material.CACTUS));
        assertTrue(handler.isMaterialBanned(Material.PORTAL));
        assertTrue(handler.isMaterialBanned(Material.VINE));
        assertTrue(handler.isMaterialBanned(Material.SPONGE));
        assertTrue(handler.isMaterialBanned(Material.SAND));
        // Stone is sealable, must NOT be banned by default.
        assertFalse(handler.isMaterialBanned(Material.ROCK));
    }

    @Test
    public void whitelistOverridesDetection() throws Exception {
        Block target = Blocks.LEAVES;

        // First put it on the ban list…
        handler.addUnsealableBlock(target);
        assertTrue(handler.isBlockBanned(target));

        // …then overriding via addSealableBlock must remove it from the ban list.
        handler.addSealableBlock(target);
        assertFalse("addSealableBlock must remove the block from the ban list",
                handler.isBlockBanned(target));
        assertTrue("addSealableBlock must put the block onto the allow list",
                handler.getOverriddenSealableBlocks().contains(target));
    }

    @Test
    public void blacklistOverridesDetection() throws Exception {
        Block target = Blocks.STONE;

        handler.addSealableBlock(target);
        assertTrue(handler.getOverriddenSealableBlocks().contains(target));

        handler.addUnsealableBlock(target);
        assertTrue(handler.isBlockBanned(target));
        assertFalse("addUnsealableBlock must remove the block from the allow list",
                handler.getOverriddenSealableBlocks().contains(target));
    }

    @Test
    public void addingSameBlockTwiceDoesNotDuplicate() throws Exception {
        Block target = Blocks.GRAVEL;

        handler.addSealableBlock(target);
        handler.addSealableBlock(target);

        // Allow list contains the block exactly once.
        long count = handler.getOverriddenSealableBlocks().stream()
                .filter(b -> b == target)
                .count();
        // assertEquals(long, long) is unambiguous, so explicit cast.
        org.junit.Assert.assertEquals(1L, count);
    }
}
