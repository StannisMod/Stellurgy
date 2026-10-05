package dev.stannismod.stellurgy.subsystem.heat;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;

import net.minecraft.block.Block;
import net.minecraft.block.material.Material;
import net.minecraft.block.state.IBlockState;
import net.minecraft.init.Blocks;
import net.minecraft.item.ItemBlock;
import net.minecraft.item.ItemStack;
import net.minecraft.util.math.AxisAlignedBB;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;
import net.minecraftforge.oredict.OreDictionary;
import org.apache.logging.log4j.LogManager;
import dev.stannismod.stellurgy.api.Constants;
import dev.stannismod.stellurgy.api.StellurgyConfiguration;

import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.Reader;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * What every material in the game is worth thermally, in one player-editable table.
 *
 * <p><b>Keyed by MATERIAL, never by item and never by block.</b> Density, specific heat and a
 * ceiling temperature are properties of a substance; an iron ingot, an iron block and a foreign
 * mod's iron rod are all the same substance in different shapes, so they resolve to one row here and
 * their capacities differ only by how much of it there is. A table keyed by item would have to be
 * filled in for every mod that ever ships an ingot - which is exactly the shape that cannot be
 * maintained and is why the weight system learned the same lesson before this one.</p>
 *
 * <p><b>Resolution is by ore dictionary</b>, because that is the one naming scheme the whole
 * ecosystem already agrees on: {@code ingotIron}, {@code blockIron}, {@code dustIron} and
 * {@code nuggetIron} all yield "iron", from any mod, with no integration code on either side. A
 * stack that matches nothing at all resolves to nothing, and every caller treats that as "this
 * cannot be a slug" rather than as a default material - inventing a capacity for an unknown
 * substance is how a table stops describing the game.</p>
 *
 * <p><b>Why AR carries its own numbers even where GregTech is present.</b> GT's material registry
 * has a mass and, for the materials that need a blast furnace, a blast temperature - but it carries
 * no density and no specific heat at all (checked against the pinned 2.8.10 sources), so two of the
 * three figures this table needs could not come from it. The table is therefore AR's, and the JSON
 * is the extension point: a pack that wants GT's exact numbers, or an addon that adds a material,
 * writes a row rather than patching code.</p>
 * <p>Every static field of this type is effectively final, process lifetime: built once at class initialisation, and holds an immutable value. The table is read from the install's file when the class loads and is never replaced.</p>
 */
public final class ThermalMaterials {

    /** The ore-dictionary prefixes a material can hide behind, longest first so "block" wins. */
    private static final List<String> PREFIXES = Collections.unmodifiableList(Arrays.asList(
            "block", "ingot", "plate", "stick", "gear", "dust", "nugget", "gem", "ore"));

    /**
     * What one item of each ore-dictionary shape is worth in millilitres, against a block of a cubic
     * metre. These are the unit conventions the tech ecosystem already runs on (nine ingots to a
     * block, nine nuggets to an ingot), not a balance decision of ours.
     */
    private static final Map<String, Long> PREFIX_VOLUMES = prefixVolumes();

    /**
     * What each vanilla {@link Material} is made of, as far as heat is concerned. Coarse on purpose -
     * this is the fallback, and it only has to be RIGHT rather than precise: a stone slab is stone,
     * an anvil is iron, and anything the game does not describe this way stays unknown.
     */
    private static final Map<Material, String> VANILLA_MATERIAL_NAMES = vanillaMaterialNames();

    /**
     * The table this install runs on: {@code config/advRocketry/thermalMaterials.json}, read once, when
     * this class loads, on whichever side loads it.
     *
     * <p><b>A constant, not state.</b> Nothing reloads the file while the game runs — an edit takes
     * effect on the next launch — and the object is immutable, so every reader on either side sees the
     * one table the install was started with. A caller that needs a different table (a test writing
     * its own file) builds one with {@link #load(String)} and holds it itself.</p>
     */
    public static final ThermalMaterials INSTANCE = load("config/advRocketry/thermalMaterials.json");

    private final Map<String, ThermalMaterial> materials;

    private ThermalMaterials(Map<String, ThermalMaterial> materials) {
        this.materials = Collections.unmodifiableMap(new LinkedHashMap<>(materials));
    }

    private static Map<String, Long> prefixVolumes() {
        Map<String, Long> volumes = new LinkedHashMap<>();
        volumes.put("block", 1_000_000L);
        volumes.put("ingot", 1_000_000L / 9);
        volumes.put("plate", 1_000_000L / 9);
        volumes.put("dust", 1_000_000L / 9);
        volumes.put("gem", 1_000_000L / 9);
        volumes.put("ore", 1_000_000L / 9);
        volumes.put("gear", 4_000_000L / 9);
        volumes.put("stick", 1_000_000L / 18);
        volumes.put("nugget", 1_000_000L / 81);
        return Collections.unmodifiableMap(volumes);
    }

    private static Map<Material, String> vanillaMaterialNames() {
        Map<Material, String> names = new LinkedHashMap<>();
        names.put(Material.ROCK, "stone");
        names.put(Material.IRON, "iron");
        names.put(Material.ANVIL, "iron");
        names.put(Material.WOOD, "wood");
        names.put(Material.GLASS, "glass");
        names.put(Material.ICE, "ice");
        names.put(Material.PACKED_ICE, "ice");
        names.put(Material.SNOW, "snow");
        names.put(Material.CRAFTED_SNOW, "snow");
        names.put(Material.SAND, "sand");
        names.put(Material.GROUND, "dirt");
        names.put(Material.GRASS, "dirt");
        names.put(Material.CLAY, "clay");
        names.put(Material.CLOTH, "wool");
        names.put(Material.CARPET, "wool");
        return Collections.unmodifiableMap(names);
    }

    /**
     * The material this stack is made of, or {@code null} when nothing in the table matches it.
     *
     * <p>Two sources, in this order, and the order is the whole of it. The ore dictionary is asked
     * FIRST because it is SPECIFIC - {@code ingotIron} says iron and nothing else. Only when it is
     * silent does the block's own vanilla {@link Material} answer, which is coarse by construction
     * (one {@code IRON} covers every metal-looking block in the game) and is therefore a last resort
     * rather than a peer. Without it a stone slab - which no mod names in the ore dictionary - would
     * have a size and no substance, and a table that knows how big something is but not what it is
     * cannot answer the only question it exists for.</p>
     */
    public ThermalMaterial of(ItemStack stack) {
        if (stack == null || stack.isEmpty()) {
            return null;
        }
        for (int id : OreDictionary.getOreIDs(stack)) {
            ThermalMaterial found = byOreName(OreDictionary.getOreName(id));
            if (found != null) {
                return found;
            }
        }
        return byVanillaMaterial(placedBlock(stack));
    }

    /** The substance a block is made of, as its own vanilla {@link Material} names it. */
    public ThermalMaterial byVanillaMaterial(Block block) {
        if (block == null || block == Blocks.AIR) {
            return null;
        }
        @SuppressWarnings("deprecation")
        Material vanilla = block.getDefaultState().getMaterial();
        return byName(VANILLA_MATERIAL_NAMES.get(vanilla));
    }

    /** The material behind an ore-dictionary name such as {@code ingotIron}, or {@code null}. */
    public ThermalMaterial byOreName(String oreName) {
        if (oreName == null || oreName.isEmpty()) {
            return null;
        }
        for (String prefix : PREFIXES) {
            if (oreName.length() > prefix.length() && oreName.startsWith(prefix)) {
                ThermalMaterial found = byName(oreName.substring(prefix.length()));
                if (found != null) {
                    return found;
                }
            }
        }
        return byName(oreName);
    }

    /** The material with this name, case-insensitively, or {@code null}. */
    public ThermalMaterial byName(String name) {
        return name == null ? null : materials.get(name.toLowerCase(Locale.ROOT));
    }

    /**
     * How much heat a slug of {@code material} of this volume can carry, in heat units.
     *
     * <p>The physics is {@link ThermalMaterial#joulesPerCubicMetre}; this is where it becomes the
     * currency the rest of the system deals in, and it is the ONLY place the conversion happens. The
     * span starts at the cabin temperature the whole subsystem calls ambient and stops a margin short
     * of the material's ceiling, so what comes out is a lump you can still pick up rather than a
     * puddle you cannot.</p>
     *
     * @param millilitres the volume of the slug, in thousandths of a litre
     */
    public static long slugCapacity(ThermalMaterial material, long millilitres) {
        if (material == null || millilitres <= 0L) {
            return 0L;
        }
        StellurgyConfiguration config = StellurgyConfiguration.getCurrentConfig();
        int joulesPerUnit = Math.max(1, config.shipHeatSlugJoulesPerUnit);
        long perCubicMetre = material.joulesPerCubicMetre(
                HeatNetwork.ambientKelvin(), Math.max(0, config.shipHeatSlugMarginKelvin));
        // A cubic metre is a million millilitres, and the multiply comes FIRST on purpose: dividing
        // first rounds a weak material's litre away to nothing before it has been converted at all.
        // Tungsten's ~9 GJ/m3 times a full cubic metre is 9e15, well inside a long.
        return perCubicMetre * millilitres / 1_000_000L / joulesPerUnit;
    }

    /**
     * How much SUBSTANCE stands at this position, in millilitres - a full block is a cubic metre.
     *
     * <p>Read off the block's own COLLISION boxes rather than its bounding box, and the difference is
     * not pedantry: {@code Block.getBoundingBox} defaults to the full cube and a staircase returns one,
     * while its collision list is the half slab plus the quarter step - three quarters of a block,
     * which is what a staircase is made of. A slab answers a half, a fence answers its post and arms.
     * So the volume is DERIVED from what the player built, the same way the capacity is derived from
     * what it is built OF, and nothing in this chain is a number somebody typed.</p>
     *
     * <p>A block with no collision at all - a torch, a plant - answers zero and therefore carries no
     * heat. That is the honest answer rather than a gap: there is no lump of anything there to heat.</p>
     */
    public static long volumeMillilitres(World world, BlockPos pos) {
        if (world == null || pos == null) {
            return 0L;
        }
        IBlockState state = world.getBlockState(pos);
        List<AxisAlignedBB> boxes = new ArrayList<>();
        // An entity box the size of the block itself: every piece of this block intersects it, and
        // nothing of the neighbours does.
        AxisAlignedBB whole = new AxisAlignedBB(pos, pos.add(1, 1, 1));
        try {
            state.addCollisionBoxToList(world, pos, whole, boxes, null, false);
        } catch (RuntimeException e) {
            // A block that refuses to describe itself without an entity is not a lump we can measure.
            return 0L;
        }
        double cubicMetres = 0.0D;
        for (AxisAlignedBB box : boxes) {
            cubicMetres += (box.maxX - box.minX) * (box.maxY - box.minY) * (box.maxZ - box.minZ);
        }
        return (long) (cubicMetres * 1_000_000L);
    }

    /**
     * How much heat the block at this position can absorb before it gives up, in heat units - the
     * material's capacity and the block's own volume, neither of them authored.
     *
     * <p>This is the melting rung's question, and it is the same arithmetic a slug's charge is, which
     * is exactly what C12 HEAT-18 means by one table with two consumers.</p>
     */
    public long blockCapacity(World world, BlockPos pos) {
        if (world == null || pos == null) {
            return 0L;
        }
        ThermalMaterial material = of(new ItemStack(world.getBlockState(pos).getBlock()));
        return slugCapacity(material, volumeMillilitres(world, pos));
    }

    /**
     * How much substance one item is, in millilitres, or {@code 0} when nothing says.
     *
     * <p>An item has no bounding box to measure, so the volume comes from the ore-dictionary PREFIX
     * instead - which is the same ecosystem convention the capacity itself is resolved through, and
     * carries the same arithmetic every tech mod already agrees on: nine ingots to a block, nine
     * nuggets to an ingot, a plate is an ingot flattened, a rod is half of one.</p>
     */
    public static long volumeMillilitres(ItemStack stack) {
        if (stack == null || stack.isEmpty()) {
            return 0L;
        }
        // A block ANSWERS FOR ITSELF, and it outranks the ore dictionary rather than backing it up:
        // `blockIron` means "a block of iron" and a prefix table can only ASSUME that is a full cube,
        // while the block itself knows. Where the two disagree - a mod's half-height plate registered
        // under a block prefix - the shape in the world is the truth about how much substance it is.
        long fromBlock = placedBlockVolume(stack);
        if (fromBlock > 0L) {
            return fromBlock * stack.getCount();
        }
        long best = 0L;
        for (int id : OreDictionary.getOreIDs(stack)) {
            best = Math.max(best, volumeMillilitres(OreDictionary.getOreName(id)));
        }
        return best * stack.getCount();
    }

    /**
     * The volume of the block an item would PLACE, for an item the ore dictionary says nothing about.
     *
     * <p>A stone slab is the case that needs this: no mod registers a shape for it, so the ore
     * dictionary has no answer, and yet the thing in your hand is plainly half a block of stone. The
     * block it places knows its own shape and says so from its state alone.</p>
     *
     * <p><b>The known limit, stated rather than hidden.</b> Without a world this can only ask for the
     * state's single box, not the collision LIST, so a shape made of several pieces reads as its
     * outline: a staircase in the hand answers a whole block where the same staircase placed answers
     * three quarters. There is no world to ask for the pieces, and guessing a fraction would be the
     * authored number this whole chain exists to avoid. The moment it is placed, the exact read takes
     * over.</p>
     */
    private static Block placedBlock(ItemStack stack) {
        if (stack == null || !(stack.getItem() instanceof ItemBlock)) {
            return null;
        }
        Block block = ((ItemBlock) stack.getItem()).getBlock();
        return block == Blocks.AIR ? null : block;
    }

    private static long placedBlockVolume(ItemStack stack) {
        Block block = placedBlock(stack);
        if (block == null) {
            return 0L;
        }
        try {
            @SuppressWarnings("deprecation")
            IBlockState state = block.getStateFromMeta(stack.getMetadata());
            @SuppressWarnings("deprecation")
            AxisAlignedBB box = state.getBoundingBox(null, BlockPos.ORIGIN);
            double cubicMetres = (box.maxX - box.minX) * (box.maxY - box.minY)
                    * (box.maxZ - box.minZ);
            return (long) (cubicMetres * 1_000_000L);
        } catch (RuntimeException e) {
            // A block that cannot describe itself without a world in hand is one we decline to guess
            // at. Zero means "nobody said", which every caller already treats as "not a slug".
            return 0L;
        }
    }

    /**
     * How much substance ONE item of this ore-dictionary shape is, in millilitres, or {@code 0} when
     * the name carries no shape this understands.
     *
     * <p>Split out from the stack version so the arithmetic can be exercised without a registered
     * game: the stack version's only extra job is asking the ore dictionary what names a stack has.</p>
     */
    public static long volumeMillilitres(String oreName) {
        if (oreName == null) {
            return 0L;
        }
        for (Map.Entry<String, Long> prefix : PREFIX_VOLUMES.entrySet()) {
            if (oreName.length() > prefix.getKey().length() && oreName.startsWith(prefix.getKey())) {
                return prefix.getValue();
            }
        }
        return 0L;
    }

    /** Every material the table knows, in the order it was written. */
    public Map<String, ThermalMaterial> all() {
        return materials;
    }

    // ─── the table on disk ─────────────────────────────────────────────────────

    /**
     * The table in {@code file}: every row the file holds, plus every shipped row it lacks, written
     * back when rows were added. An absent file is created with the shipped table; an unreadable or
     * empty one is read as the shipped table.
     */
    public static ThermalMaterials load(String file) {
        return new ThermalMaterials(read(file));
    }

    private static Map<String, ThermalMaterial> read(String file) {
        File f = new File(file);
        if (!f.exists()) {
            Map<String, ThermalMaterial> shipped = defaults();
            save(file, shipped);
            return shipped;
        }
        try (Reader r = new FileReader(file)) {
            Gson gson = new GsonBuilder().disableHtmlEscaping().create();
            JsonObject root = gson.fromJson(r, JsonObject.class);
            Map<String, ThermalMaterial> parsed = new LinkedHashMap<>();
            if (root != null && root.has("materials") && root.get("materials").isJsonObject()) {
                JsonObject table = root.getAsJsonObject("materials");
                for (Map.Entry<String, com.google.gson.JsonElement> entry : table.entrySet()) {
                    if (!entry.getValue().isJsonObject()) {
                        continue;
                    }
                    JsonObject row = entry.getValue().getAsJsonObject();
                    ThermalMaterial material = new ThermalMaterial(
                            entry.getKey(),
                            row.has("density") ? row.get("density").getAsInt() : 0,
                            row.has("specificHeat") ? row.get("specificHeat").getAsInt() : 0,
                            row.has("ceilingKelvin") ? row.get("ceilingKelvin").getAsInt() : 0);
                    parsed.put(entry.getKey().toLowerCase(Locale.ROOT), material);
                }
            }
            if (parsed.isEmpty()) {
                return defaults();
            }
            // A row the player wrote wins; a row the file has never heard of comes from the shipped
            // table. Without this merge a file written by an older version shadows every material
            // added since, and a wooden hull reads as a substance nobody knows - with nothing to say
            // so, because the table it did load looks healthy.
            List<String> added = new ArrayList<>();
            for (Map.Entry<String, ThermalMaterial> shipped : defaults().entrySet()) {
                if (!parsed.containsKey(shipped.getKey())) {
                    parsed.put(shipped.getKey(), shipped.getValue());
                    added.add(shipped.getKey());
                }
            }
            if (!added.isEmpty()) {
                LogManager.getLogger(Constants.modId).info(
                        "{} predates the shipped materials {}; added them with their shipped values",
                        file, added);
                save(file, parsed);
            }
            return parsed;
        } catch (Exception e) {
            e.printStackTrace();
            return defaults();
        }
    }

    private static void save(String file, Map<String, ThermalMaterial> materials) {
        File parent = new File(file).getParentFile();
        if (parent != null) {
            parent.mkdirs();
        }
        try (FileWriter w = new FileWriter(file)) {
            Gson gson = new GsonBuilder().disableHtmlEscaping().setPrettyPrinting().create();
            JsonObject table = new JsonObject();
            for (ThermalMaterial material : materials.values()) {
                JsonObject row = new JsonObject();
                row.addProperty("density", material.densityKgPerCubicMetre());
                row.addProperty("specificHeat", material.specificHeatJoulesPerKgKelvin());
                row.addProperty("ceilingKelvin", material.ceilingKelvin());
                table.add(material.name(), row);
            }
            JsonObject root = new JsonObject();
            root.add("materials", table);
            w.write(gson.toJson(root));
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    /**
     * The shipped table: ordinary handbook physics, in SI.
     *
     * <p>Nothing here is balanced and nothing here should be. What the numbers produce is a
     * progression that teaches something true - a lead slug is barely better than a canister of
     * water despite weighing eleven times as much, because lead gives up at 600 K, while graphite is
     * beaten only by tungsten because it stays solid to nearly 3 900 K. Melting point matters more
     * than density, and the table says so by being real rather than by being tuned.</p>
     */
    private static Map<String, ThermalMaterial> defaults() {
        Map<String, ThermalMaterial> m = new LinkedHashMap<>();
        // name                             rho     c      ceiling (K)
        put(m, new ThermalMaterial("water", 1000, 4182, 373));      // boils, never melts
        put(m, new ThermalMaterial("lead", 11340, 129, 600));
        put(m, new ThermalMaterial("aluminium", 2700, 897, 933));
        put(m, new ThermalMaterial("aluminum", 2700, 897, 933));    // the other spelling, same row
        put(m, new ThermalMaterial("copper", 8960, 385, 1358));
        put(m, new ThermalMaterial("gold", 19300, 129, 1337));
        put(m, new ThermalMaterial("silver", 10490, 235, 1235));
        put(m, new ThermalMaterial("iron", 7874, 449, 1811));
        put(m, new ThermalMaterial("steel", 7850, 466, 1700));
        put(m, new ThermalMaterial("nickel", 8908, 444, 1728));
        put(m, new ThermalMaterial("titanium", 4506, 523, 1941));
        put(m, new ThermalMaterial("tungsten", 19300, 134, 3695));
        put(m, new ThermalMaterial("carbon", 2260, 709, 3900));     // graphite sublimes
        put(m, new ThermalMaterial("graphite", 2260, 709, 3900));
        put(m, new ThermalMaterial("stone", 2700, 790, 1473));
        put(m, new ThermalMaterial("wood", 700, 1700, 573));        // chars rather than melts
        put(m, new ThermalMaterial("glass", 2500, 840, 1700));      // softens
        put(m, new ThermalMaterial("ice", 917, 2100, 273));
        put(m, new ThermalMaterial("snow", 300, 2090, 273));
        put(m, new ThermalMaterial("sand", 1600, 830, 1983));       // silica
        put(m, new ThermalMaterial("dirt", 1300, 800, 1500));
        put(m, new ThermalMaterial("clay", 1750, 920, 1800));
        put(m, new ThermalMaterial("wool", 100, 1300, 500));
        put(m, new ThermalMaterial("obsidian", 2650, 840, 1500));
        return m;
    }

    private static void put(Map<String, ThermalMaterial> table, ThermalMaterial material) {
        table.put(material.name().toLowerCase(Locale.ROOT), material);
    }
}
