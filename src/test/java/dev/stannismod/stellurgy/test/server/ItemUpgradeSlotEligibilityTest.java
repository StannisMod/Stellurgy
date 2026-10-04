package dev.stannismod.stellurgy.test.server;

import dev.stannismod.stellurgy.test.FixtureSite;
import dev.stannismod.stellurgy.test.MachineInfo;
import dev.stannismod.stellurgy.test.Reply;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/**
 * Modular armour components: which slot each upgrade fits, and the suit work station writing a
 * component into the armour.
 *
 * <p>{@link
 * dev.stannismod.stellurgy.item.components.ItemUpgrade} slot
 * eligibility dispatch by meta.</p>
 *
 * <p>Production (lines 92-98 of {@code ItemUpgrade.isAllowedInSlot}):
 * dispatches strictly on {@code componentStack.getItemDamage()}:</p>
 *
 * <ul>
 *   <li>meta = {@code legUpgradeDamage} (2) or
 *       {@code speedUpgradeDamage} (1) &rarr; LEGS only.</li>
 *   <li>meta = {@code bootsUpgradeDamage} (3) &rarr; FEET only.</li>
 *   <li>any other meta (0, 4, 5, ...) &rarr; HEAD only.</li>
 * </ul>
 *
 * <p>Player-visible: armor crafting / module-slot acceptance —
 * placing a leg upgrade into the helmet module slot is rejected by
 * the GUI. Pinning slot eligibility per meta guards against any
 * regression that mixes the slot dispatch (e.g. a bootsUpgrade
 * landing in LEGS).</p>
 *
 * <p>NOT pinned: the specific magic numbers (2, 3, 1)
 * — only the slot-dispatch outcome matters. If a future refactor
 * renames metas, this test continues to check the outcome.</p>
 */
public class ItemUpgradeSlotEligibilityTest extends AbstractSharedServerTest {

    private static final String ID = "stellurgy:itemUpgrade";

    @Test
    public void hoverUpgradeMeta0OnlyFitsHead() throws Exception {
        assertSlots(0, true, false, false, false);
    }

    @Test
    public void flightSpeedMeta1OnlyFitsLegs() throws Exception {
        assertSlots(1, false, false, true, false);
    }

    @Test
    public void bionicLegsMeta2OnlyFitsLegs() throws Exception {
        assertSlots(2, false, false, true, false);
    }

    @Test
    public void landingBootsMeta3OnlyFitsFeet() throws Exception {
        assertSlots(3, false, false, false, true);
    }

    @Test
    public void antiFogVisorMeta4OnlyFitsHead() throws Exception {
        assertSlots(4, true, false, false, false);
    }

    @Test
    public void earthbrightVisorMeta5OnlyFitsHead() throws Exception {
        assertSlots(5, true, false, false, false);
    }

    /**
     * A jetpack put into a suit work station's component slot is written into the chestplate in
     * slot 0, and the slot reads it back through the armour.
     *
     * <p>{@link dev.stannismod.stellurgy.tile.TileSuitWorkStation} is a passive 5-slot container:
     * slot 0 holds the armour piece, slots 1-4 its components. Assembly is not ticked —
     * {@code setInventorySlotContents(slot >= 1, IArmorComponent)} calls
     * {@code addArmorComponent(world, armor, component, slot-1)} on the armour in slot 0, and
     * {@code getStackInSlot(slot >= 1)} reads through to {@code getComponentInSlot(armor, slot-1)}.
     * A regression that drops the dispatch leaves the chestplate's NBT unchanged.</p>
     */
    @Test
    public void chestplateGainsJetpackComponentWhenJetpackPlacedInComponentSlot() throws Exception {
        FixtureSite s = site();
        String at = " 0 " + s.x + " " + s.y + " " + s.z;

        String place = exec("stellurgytest place" + at + " stellurgy:suitWorkStation");
        assertTrue("suitWorkStation place failed: " + place, Reply.of(place).bool("placed"));
        String info0 = exec("stellurgytest machine info" + at);
        assertEquals("expected TileSuitWorkStation tile: " + info0,
                "TileSuitWorkStation", MachineInfo.of(info0).tileSimpleName());

        // TileSuitWorkStation.slotArray is populated only when the GUI-open path calls getModules();
        // on a freshly placed server tile it is an array of nulls and setInventorySlotContents(0)
        // NPEs iterating it. The probe invokes getModules(0, null), populating it as a side effect.
        String initMods = exec("stellurgytest tile init-modules" + at);
        assertTrue("init-modules probe failed: " + initMods, Reply.of(initMods).ok());

        String fillArmor = exec("stellurgytest hatch fill" + at + " 0 stellurgy:spaceChestplate 1");
        assertTrue("chestplate fill failed: " + fillArmor, Reply.of(fillArmor).ok());

        String pre = exec("stellurgytest hatch read" + at + " nbt");
        assertEquals("slot 0 must hold the chestplate this test placed: " + pre, "stellurgy:spacechestplate",
                Reply.of("stellurgytest hatch read", pre).element("slots", "slot", "0").text("item"));
        assertTrue("fresh chestplate must not contain jetPack token yet: " + pre,
                !slotZeroNbt(pre).contains("jetpack"));

        String fillJet = exec("stellurgytest hatch fill" + at + " 1 stellurgy:jetPack 1");
        assertTrue("jetPack fill failed: " + fillJet, Reply.of(fillJet).ok());

        String post = exec("stellurgytest hatch read" + at + " nbt");
        assertEquals("slot 0 must still hold the same chestplate after the dispatch: " + post,
                "stellurgy:spacechestplate",
                Reply.of("stellurgytest hatch read", post).element("slots", "slot", "0").text("item"));
        // Forge normalises resource paths, hence the lower-cased token.
        assertTrue("chestplate NBT must contain jetpack reference after addArmorComponent: " + post,
                slotZeroNbt(post).contains("jetpack"));
        // SLOT 1 specifically — the read-through contract is about that index.
        assertEquals("slot 1 must report the jetpack via getComponentInSlot read-through: " + post,
                "stellurgy:jetpack",
                Reply.of("stellurgytest hatch read", post).element("slots", "slot", "1").text("item"));
    }

    /**
     * The NBT dump of SLOT 0 — the chestplate — lower-cased. Reading slot 0's own dump, not the whole
     * reply, is what keeps the jetpack STACK beside it from answering both the negative and the
     * positive claim. An NBT dump is Mojangson, and the contract is that the component id appears in it.
     */
    private static String slotZeroNbt(String hatchRead) {
        return Reply.of("stellurgytest hatch read", hatchRead).element("slots", "slot", "0")
                .text("nbt").toLowerCase(java.util.Locale.ROOT);
    }

    private void assertSlots(int meta, boolean head, boolean chest, boolean legs, boolean feet)
            throws Exception {
        String resp = exec("stellurgytest infra item-armor-slot " + ID + " " + meta + " 1");
        assertTrue("item-armor-slot must succeed: " + resp,
                Reply.of(resp).ok());
        assertTrue("meta=" + meta + " head expected=" + head + "; resp=" + resp,
                String.valueOf(head).equals(Reply.of(resp).text("head")));
        assertTrue("meta=" + meta + " chest expected=" + chest + "; resp=" + resp,
                String.valueOf(chest).equals(Reply.of(resp).text("chest")));
        assertTrue("meta=" + meta + " legs expected=" + legs + "; resp=" + resp,
                String.valueOf(legs).equals(Reply.of(resp).text("legs")));
        assertTrue("meta=" + meta + " feet expected=" + feet + "; resp=" + resp,
                String.valueOf(feet).equals(Reply.of(resp).text("feet")));
    }
}
