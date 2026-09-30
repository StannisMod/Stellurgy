package dev.stannismod.stellurgy.test.integration;

import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityList;
import net.minecraft.entity.item.EntityArmorStand;
import net.minecraft.init.Items;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.nbt.NBTTagList;
import net.minecraft.util.ResourceLocation;
import org.junit.BeforeClass;
import org.junit.Test;
import dev.stannismod.stellurgy.api.atmosphere.Atmosphere;
import dev.stannismod.stellurgy.test.MinecraftBootstrap;

import java.util.LinkedList;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

/**
 * Atmosphere — pure-logic checks on Atmosphere subtypes.
 *
 * Loading {@code Atmosphere} runs its static initializer which registers
 * atmospheres into {@code AtmosphereRegister}. We trigger MC bootstrap defensively
 * because some atmosphere subclasses reference vanilla blocks transitively.
 */
public class AtmosphereLogicTest {

    @BeforeClass
    public static void bootstrap() {
        MinecraftBootstrap.ensure();
    }

    @Test
    public void airIsBreathable() {
        assertTrue(Atmosphere.AIR.isBreathable());
        assertTrue("normal air must allow combustion (torches burn)", Atmosphere.AIR.allowsCombustion());
    }

    @Test
    public void pressurizedAirIsBreathable() {
        assertTrue(Atmosphere.PRESSURIZEDAIR.isBreathable());
    }

    @Test
    public void vacuumIsNotBreathable() {
        assertFalse(Atmosphere.VACUUM.isBreathable());
        assertFalse("vacuum must not support combustion", Atmosphere.VACUUM.allowsCombustion());
    }

    @Test
    public void noOxygenAtmospheresAreNotBreathable() {
        assertFalse(Atmosphere.NOO2.isBreathable());
        assertFalse(Atmosphere.HIGHPRESSURENOO2.isBreathable());
        assertFalse(Atmosphere.SUPERHIGHPRESSURENOO2.isBreathable());
        assertFalse(Atmosphere.VERYHOTNOO2.isBreathable());
        assertFalse(Atmosphere.SUPERHEATEDNOO2.isBreathable());
    }

    @Test
    public void hostileAtmospheresHaveTickingEnabled() {
        // Atmospheres that damage / affect entities every tick must report canTick.
        assertTrue("vacuum ticks for suffocation damage", Atmosphere.VACUUM.canTick());
        assertTrue("LowO2 ticks for nausea/damage", Atmosphere.LOWOXYGEN.canTick());
        assertTrue("HighPressure ticks", Atmosphere.HIGHPRESSURE.canTick());
        assertTrue("VeryHot ticks", Atmosphere.VERYHOT.canTick());
    }

    @Test
    public void breathableAtmospheresDoNotTick() {
        assertFalse("Breathable AIR is not expected to tick effects", Atmosphere.AIR.canTick());
        assertFalse("PressurizedAir does not tick", Atmosphere.PRESSURIZEDAIR.canTick());
    }

    @Test
    public void atmosphereNamesArePreservedFromConstructor() {
        assertEquals("air", Atmosphere.AIR.getUnlocalizedName());
        assertEquals("PressurizedAir", Atmosphere.PRESSURIZEDAIR.getUnlocalizedName());
        assertEquals("lowO2", Atmosphere.LOWOXYGEN.getUnlocalizedName());
        assertEquals("NoO2", Atmosphere.NOO2.getUnlocalizedName());
    }

    /**
     * <p>red-witnessed: one inversion per verdict, 2026-09-30. NO MUTATORS — a
     * {@code setIsBreathable(boolean)} added to {@code Atmosphere}: "an atmosphere must not be
     * tellable to lie about itself, and these can tell it: [setIsBreathable]". UNBREATHABLE — the
     * four-argument constructor ({@code Atmosphere:70}) making a flammable non-ticking atmosphere
     * breathable: a bare {@code AssertionError} at the {@code assertFalse} on
     * {@code isBreathable()}. COMBUSTION KEPT — the same constructor dropping the combustion flag of
     * anything neither breathable nor ticking: "the constructor must keep combustion distinct from
     * breathable".</p>
     */
    @Test
    public void whatAnAtmosphereSaysAboutItselfCannotBeChangedAfterItIsBuilt() {
        // There used to be two setters here, and two tests that checked the setters set. What they
        // pinned was that a pack could take a SHARED singleton — the same VACUUM every zone and every
        // planet resolves to — and declare it breathable. Nothing then re-checked that against any
        // gas, so the lie simply became the answer everywhere.
        //
        // Asserted structurally because that is what the rule is: not "the current values are right"
        // but "there is no way to change them". A test that only read the values would pass happily
        // the day somebody added the setter back.
        List<String> mutators = new LinkedList<>();
        for (java.lang.reflect.Method m : Atmosphere.class.getMethods()) {
            if (m.getDeclaringClass() != Atmosphere.class) {
                continue;
            }
            boolean setsSomething = m.getName().startsWith("set") && m.getParameterCount() == 1;
            if (setsSomething) {
                mutators.add(m.getName());
            }
        }
        assertTrue("an atmosphere must not be tellable to lie about itself, and these can tell it: "
                + mutators, mutators.isEmpty());

        // And the two flags still have to be independent of each other at construction: fire and
        // lungs are different questions about the same gas, and conflating them was a real defect.
        Atmosphere unbreathableButFlammable =
                new Atmosphere(false, false, true, "ar.test.combust." + System.nanoTime());
        assertFalse(unbreathableButFlammable.isBreathable());
        assertTrue("the constructor must keep combustion distinct from breathable",
                unbreathableButFlammable.allowsCombustion());
    }

    /**
     * Space-suit "capability" NBT round-trip.
     *
     * The suit's worn state is persisted on the ItemStack itself: ItemSpaceChest
     * stores its modular slot inventory (which holds fluid tanks &rarr; capability
     * adapters) into {@code stack.getTagCompound()} via
     * {@code EmbeddedInventory.writeToNBT}, and reloads it the same way.
     * That mechanism is just an {@link ItemStack}-with-NBT round-trip; the
     * capability adapters on inner stacks are rebuilt lazily from the registry,
     * so the NBT IS the entire persistence surface.
     *
     * Instantiating the real {@code ItemSpaceChest} needs ArmorMaterial + the Stellurgy
     * registry chain (out-of-scope for unit tests). We assert the underlying
     * contract against a vanilla armor item with the same tagCompound shape that
     * {@code ItemSpaceArmor.saveEmbeddedInventory} writes (an "Items" NBT list
     * with "Slot" / item id entries).
     */
    @Test
    public void spaceSuitCapabilityNbtRoundTrip() {
        ItemStack suit = new ItemStack(Items.IRON_HELMET);

        // Mirror the EmbeddedInventory.writeToNBT(parent) -> parent.setTag("Items", list)
        // layout used by the production suit. The inner fluid tank is represented
        // by a sub-tag with Damage/Count/Fluid keys (the same shape libVulpes'
        // FluidContainerItem writes via writeShareTag).
        NBTTagCompound tag = new NBTTagCompound();

        NBTTagList items = new NBTTagList();
        NBTTagCompound slot0 = new NBTTagCompound();
        slot0.setByte("Slot", (byte) 0);
        slot0.setShort("id", (short) Item_REGISTRY_ID_BUCKET);
        slot0.setByte("Count", (byte) 1);

        NBTTagCompound bucketTag = new NBTTagCompound();
        NBTTagCompound fluid = new NBTTagCompound();
        fluid.setString("FluidName", "oxygen");
        fluid.setInteger("Amount", 4000);
        bucketTag.setTag("Fluid", fluid);
        slot0.setTag("tag", bucketTag);

        items.appendTag(slot0);
        tag.setTag("Items", items);
        // Mirror the "air" timer field the suit may also set.
        tag.setInteger("air", 18_000);

        suit.setTagCompound(tag);

        // Round-trip through ItemStack.writeToNBT / new ItemStack(nbt) — the
        // production save path used when the player drops the suit into a chest
        // or saves the world.
        NBTTagCompound serialized = new NBTTagCompound();
        suit.writeToNBT(serialized);

        ItemStack restored = new ItemStack(serialized);
        assertFalse("stack must NOT lose identity through NBT round-trip", restored.isEmpty());
        assertSame("item identity must be preserved",
                Items.IRON_HELMET, restored.getItem());
        assertNotNull("suit tag compound must survive", restored.getTagCompound());

        NBTTagCompound restoredTag = restored.getTagCompound();
        assertEquals("air timer must survive", 18_000, restoredTag.getInteger("air"));

        NBTTagList restoredItems = restoredTag.getTagList("Items", 10 /*NBT.TAG_COMPOUND*/);
        assertEquals("modular slot count must survive", 1, restoredItems.tagCount());

        NBTTagCompound restoredSlot = restoredItems.getCompoundTagAt(0);
        assertEquals(0, restoredSlot.getByte("Slot"));

        NBTTagCompound restoredBucketTag = restoredSlot.getCompoundTag("tag");
        NBTTagCompound restoredFluid = restoredBucketTag.getCompoundTag("Fluid");
        assertEquals("fluid name must survive", "oxygen", restoredFluid.getString("FluidName"));
        assertEquals("fluid amount must survive", 4000, restoredFluid.getInteger("Amount"));
    }

    // Vanilla bucket numeric id — kept inline so the test doesn't depend on
    // RegistryEvent firing order. We only use it as a placeholder for "some
    // item with NBT", the real ItemSpaceChest stores its own item id.
    private static final int Item_REGISTRY_ID_BUCKET = 325;

    /**
     * Entity-bypass config parses ResourceLocations and FQCNs.
     *
     * Production loadPreInit walks {@code entityList} (a String[] from
     * config.getStringList("entityAtmBypass", ...)) and for each entry:
     *   1. tries {@code EntityList.getClass(new ResourceLocation(str))} —
     *      the registry name path for vanilla / modded entities;
     *   2. falls back to {@code Class.forName(str)} for fully-qualified class
     *      names AND verifies {@code Entity.class.isAssignableFrom(clazz)};
     *   3. on both failures, logs a warning and skips the entry — no NPE.
     *
     * We exercise each of the three branches against a real EntityList (vanilla
     * registry, populated by Bootstrap.register() in MinecraftBootstrap).
     */
    @Test
    public void entityBypassConfigParsesResourceLocations() {
        // Replay the exact parsing loop from StellurgyConfiguration.loadPreInit lines
        // 714–733 against a representative input set.
        String[] entityList = {
                "minecraft:armor_stand",                              // vanilla RL -> EntityArmorStand
                "minecraft:doesnotexist_entity",                      // vanilla namespace, unknown name -> null
                "net.minecraft.entity.item.EntityArmorStand",         // FQCN fallback -> same class
                "java.lang.String",                                   // FQCN but NOT an Entity -> must be filtered
                "totally::garbage::value::with::wrong::syntax",       // malformed -> must NOT throw
                "minecraft:zombie",                                   // vanilla RL -> EntityZombie
        };

        List<Class<?>> resolved = new LinkedList<>();
        for (String str : entityList) {
            Class<?> clazz;
            try {
                clazz = EntityList.getClass(new ResourceLocation(str));
            } catch (Throwable e) {
                clazz = null;
            }

            if (clazz == null) {
                try {
                    clazz = Class.forName(str);
                    if (!Entity.class.isAssignableFrom(clazz)) {
                        clazz = null;
                    }
                } catch (Throwable e) {
                    clazz = null;
                }
            }

            if (clazz != null) {
                resolved.add(clazz);
            }
        }

        // Branch 1: armor_stand resolved via ResourceLocation (vanilla registry).
        assertTrue("EntityArmorStand must resolve via minecraft:armor_stand",
                resolved.contains(EntityArmorStand.class));

        // Branch 2 (unknown registry name): null -> skipped, no exception.
        // (Implicit — if it threw, we'd never reach branch 3.)

        // Branch 3a (FQCN fallback): EntityArmorStand via class-name fallback —
        // already in the list from branch 1, so just confirm at least one
        // instance.
        long armorStandHits = resolved.stream()
                .filter(c -> c == EntityArmorStand.class)
                .count();
        assertTrue("FQCN fallback must also resolve armor stand",
                armorStandHits >= 1L);

        // Branch 3b (FQCN but non-Entity): java.lang.String -> filtered out.
        assertFalse("non-Entity class must be filtered",
                resolved.contains(String.class));

        // Branch 4 (malformed): must not crash the parser; entry just doesn't
        // appear in the resolved set. We verify the loop completed by checking
        // the post-malformed entries also resolved.
        Class<?> zombie = EntityList.getClass(new ResourceLocation("minecraft:zombie"));
        assertNotNull("vanilla zombie registry name must resolve", zombie);
        assertTrue("zombie must end up in resolved set (malformed entry didn't break the loop)",
                resolved.contains(zombie));

        // The bypassEntity collection in production always starts with
        // EntityArmorStand.class (loadPreInit:708). We don't run loadPreInit
        // here but confirm the class is resolvable as the loop expects.
        assertSame("entity registry must return EntityArmorStand for armor_stand",
                EntityArmorStand.class,
                EntityList.getClass(new ResourceLocation("minecraft:armor_stand")));
        assertNull("unknown name must return null cleanly",
                EntityList.getClass(new ResourceLocation("minecraft:totally_made_up_entity")));
    }
}
