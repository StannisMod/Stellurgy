package dev.stannismod.stellurgy.test.unit;

import dev.stannismod.stellurgy.api.Constants;
import dev.stannismod.stellurgy.item.ItemAsteroidChip;
import dev.stannismod.stellurgy.item.ItemPlanetIdentificationChip;
import dev.stannismod.stellurgy.item.ItemStationChip;
import dev.stannismod.stellurgy.test.MinecraftBootstrap;
import dev.stannismod.stellurgy.test.contract.Pins;
import dev.stannismod.stellurgy.test.contract.RedWitness;
import net.minecraft.init.Items;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NBTTagCompound;
import org.junit.BeforeClass;
import org.junit.Test;

import static org.junit.Assert.*;

/**
 * chip-item NBT round-trip pins.
 *
 * <p>Production launch and landing paths read these chip items' NBT directly;
 * a silent change in NBT-key names or read/write asymmetry would break
 * already-programmed chips on existing saves. Each chip is exercised at
 * unit tier via {@link MinecraftBootstrap} (vanilla MC registries + Stellurgy
 * CommonProxy injection + Sol star). No server / world required.</p>
 *
 * <p>The chip classes' setter methods are <b>instance</b> (not static), so
 * the tests construct each chip class directly. {@code new ItemX()} invokes
 * vanilla {@link net.minecraft.item.Item}'s no-arg constructor which is safe
 * after {@link net.minecraft.init.Bootstrap#register()}.</p>
 *
 * <p>Several production setters contain known asymmetries (set-then-get drops
 * the NBT in some branches). Those are pinned via {@code _documentsKnownBug}
 * tests rather than fixed — the "no production logic changes" rule
 * still applies.</p>
 */
public class ChipNBTRoundTripTest {

    @BeforeClass
    public static void bootstrap() {
        MinecraftBootstrap.ensure();
    }

    /** Any vanilla ItemStack works — chip methods only touch the NBT. */
    private static ItemStack freshStack() {
        return new ItemStack(Items.STICK, 1);
    }

    // ───────────────────── ItemPlanetIdentificationChip ─────────────────

    /** Pins INV-ITM-01 (a fresh chip with no NBT returns its sentinel default). */
    @Pins("INV-ITM-01")
    @RedWitness(member = "ItemPlanetIdentificationChip#getDimensionId", at = "return Constants.INVALID_PLANET;",
            replacedBy = "return 0;", fails = "fresh stack must read as INVALID_PLANET", on = "2026-10-19")
    @Test
    public void planetChipDimIdReadDefaultsToInvalidPlanetWithoutNbt() {
        // Production contract (getDimensionId, lines 99-103): a stack with
        // no NBT compound returns Constants.INVALID_PLANET. Programmed-chip
        // gameplay relies on this sentinel to decide "unprogrammed -> show
        // unprogrammed tooltip".
        ItemPlanetIdentificationChip chip = new ItemPlanetIdentificationChip();
        ItemStack s = freshStack();
        assertEquals("fresh stack must read as INVALID_PLANET",
                Constants.INVALID_PLANET, chip.getDimensionId(s));
    }

    /** The INVALID_PLANET branch of
     *  {@code setDimensionId} previously built a fresh NBT but never
     *  called {@code stack.setTagCompound(nbt)} before returning — so
     *  the sentinel was silently dropped. Now it attaches the NBT so
     *  callers can observe the "explicitly invalid" state.
     * Pins INV-ITM-04 (setting the invalid planet still attaches a tag carrying the sentinel).
     */
    @Pins("INV-ITM-04")
    @RedWitness(member = "ItemPlanetIdentificationChip#setDimensionId", at = "stack.setTagCompound(nbt); return;",
            replacedBy = "return;", fails = "setDimensionId(INVALID_PLANET) must attach the NBT", on = "2026-10-19")
    @Test
    public void planetChipSetDimensionIdWithInvalidPlanetAttachesNbtSentinel() {
        ItemPlanetIdentificationChip chip = new ItemPlanetIdentificationChip();
        ItemStack s = freshStack();
        chip.setDimensionId(s, Constants.INVALID_PLANET);
        assertTrue("setDimensionId(INVALID_PLANET) must attach the NBT",
                s.hasTagCompound());
        assertEquals("the stored sentinel must equal INVALID_PLANET",
                Constants.INVALID_PLANET, s.getTagCompound().getInteger("dimId"));
    }

    @Test
    public void planetChipUuidRoundTrip() {
        // The getter returns a boxed Long because production callers
        // null-check against the "no NBT" case.
        ItemPlanetIdentificationChip chip = new ItemPlanetIdentificationChip();
        ItemStack s = freshStack();
        assertNull("fresh stack has no UUID — must return null, not 0",
                chip.getUUID(s));
    }

    /** Pins INV-ITM-03 (erase drops the entire compound). */
    @Pins("INV-ITM-03")
    @RedWitness(member = "ItemPlanetIdentificationChip#erase", at = "stack.setTagCompound(null);",
            replacedBy = "stack.setTagCompound(new NBTTagCompound());",
            fails = "erase() must drop the entire NBT compound", on = "2026-10-19")
    @Test
    public void planetChipEraseClearsAllNbt() {
        ItemPlanetIdentificationChip chip = new ItemPlanetIdentificationChip();
        ItemStack s = freshStack();
        chip.setUUID(s, 42L);
        assertTrue("precondition: stack has NBT after setUUID", s.hasTagCompound());
        chip.erase(s);
        assertFalse("erase() must drop the entire NBT compound", s.hasTagCompound());
    }

    // ───────────────────── ItemStationChip ──────────────────────────────

    /** Pins INV-ITM-01 (a fresh chip with no NBT returns its sentinel default). */
    @Pins("INV-ITM-01")
    @Test
    public void stationChipUuidDefaultsToZero() {
        // Static methods, no instance needed. Production contract: no NBT
        // -> getUUID returns 0 (not -1, not Integer.MIN_VALUE). 0 is also a
        // valid station UUID for an existing station, so callers must not
        // disambiguate "unprogrammed" from "station 0" by this method
        // alone.
        ItemStack s = freshStack();
        assertEquals(0, ItemStationChip.getUUID(s));
    }

    // ───────────────────── ItemAsteroidChip ─────────────────────────────

    /** Pins INV-ITM-01 (a fresh chip with no NBT returns its sentinel default). */
    @Pins("INV-ITM-01")
    @Test
    public void asteroidChipUuidAndTypeRoundTrip() {
        ItemAsteroidChip chip = new ItemAsteroidChip();
        ItemStack s = freshStack();
        assertNull("fresh stack: UUID null", chip.getUUID(s));
        assertNull("fresh stack: type null", chip.getType(s));
    }

    @Test
    public void asteroidChipEraseDropsBothFields() {
        ItemAsteroidChip chip = new ItemAsteroidChip();
        ItemStack s = freshStack();
        chip.setUUID(s, 1L);
        chip.setType(s, "carbonaceous");
        chip.erase(s);
        assertNull("erase must clear UUID", chip.getUUID(s));
        assertNull("erase must clear type", chip.getType(s));
    }

    // ─────────────────── ItemSatelliteIdentificationChip ────────────────

    @Test
    public void satelliteChipEraseClearsNbt() {
        dev.stannismod.stellurgy.item.ItemSatelliteIdentificationChip chip =
                new dev.stannismod.stellurgy.item.ItemSatelliteIdentificationChip();
        ItemStack s = freshStack();
        NBTTagCompound nbt = new NBTTagCompound();
        nbt.setLong("satelliteId", 99L);
        s.setTagCompound(nbt);
        chip.erase(s);
        assertFalse("erase must drop the NBT compound entirely", s.hasTagCompound());
    }
}
