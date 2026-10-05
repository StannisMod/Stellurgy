package dev.stannismod.stellurgy.api;

import net.minecraft.util.ResourceLocation;
import net.minecraft.world.biome.Biome;
import net.minecraftforge.registries.IForgeRegistry;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.LinkedList;
import java.util.List;


/**
 * Stores information relating to the biomes and biome registry of Stellurgy
 */
public class StellurgyBiomes {

    /** Effectively final, process lifetime: built once at class initialisation. */
    public static final StellurgyBiomes instance = new StellurgyBiomes();
    /** Effectively final, process lifetime: written only by Stellurgy.register. */
    public static Biome moonBiome;
    /** Effectively final, process lifetime: written only by Stellurgy.register. */
    public static Biome hotDryBiome;
    /** Effectively final, process lifetime: written only by Stellurgy.register. */
    public static Biome alienForest;
    /** Effectively final, process lifetime: written only by Stellurgy.register. */
    public static Biome spaceBiome;
    /** Effectively final, process lifetime: written only by Stellurgy.register. */
    public static Biome stormLandsBiome;
    /** Effectively final, process lifetime: written only by Stellurgy.register. */
    public static Biome crystalChasms;
    /** Effectively final, process lifetime: written only by Stellurgy.register. */
    public static Biome swampDeepBiome;
    /** Effectively final, process lifetime: written only by Stellurgy.register. */
    public static Biome marsh;
    /** Effectively final, process lifetime: written only by Stellurgy.register. */
    public static Biome oceanSpires;
    /** Effectively final, process lifetime: written only by Stellurgy.register. */
    public static Biome moonBiomeDark;
    /** Effectively final, process lifetime: written only by Stellurgy.register. */
    public static Biome volcanic;
    /** Effectively final, process lifetime: written only by Stellurgy.register. */
    public static Biome volcanicBarren;
    /**
     * Effectively final, process lifetime: filled only by StellurgyBiomes.registerBlackListBiome,
     * StellurgyBiomes.blackListVanillaBiomes.
     */
    private List<Integer> blackListedBiomeIds;
    /** Effectively final, process lifetime: filled only by StellurgyBiomes.registerBiome. */
    private List<Biome> registeredBiomes;
    /** Effectively final, process lifetime: filled only by StellurgyBiomes.registerHighPressureBiome. */
    private List<Biome> registeredHighPressureBiomes;
    /** Effectively final, process lifetime: filled only by StellurgyBiomes.registerSingleBiome. */
    private List<Biome> registeredSingleBiome;

    private StellurgyBiomes() {
        registeredBiomes = new ArrayList<>();
        registeredHighPressureBiomes = new LinkedList<>();
        blackListedBiomeIds = new ArrayList<>();
        registeredSingleBiome = new ArrayList<>();
    }

    @Nullable
    public static Biome getBiome(String string) {
        Biome biome = Biome.REGISTRY.getObject(new ResourceLocation(string));

        //Fallback to ID
        if (biome == null) {
            biome = Biome.getBiome(Integer.parseInt(string));
        }

        return biome;
    }

    /**
     * TODO: support id's higher than 255.
     * Any biome registered through vanilla forge does not need to be registered here
     *
     * @param biome          Biome to register with Stellurgy's Biome registry
     * @param iForgeRegistry
     */
    public void registerBiome(Biome biome, IForgeRegistry<Biome> iForgeRegistry) {
        registeredBiomes.add(biome);
        iForgeRegistry.register(biome);
    }

    /**
     * Registers biomes you don't want to spawn on any planet unless registered with highpressure or similar feature
     */
    public void registerBlackListBiome(Biome biome) {
        blackListedBiomeIds.add(Biome.getIdForBiome(biome));
    }

    /**
     * Gets a list of the blacklisted Biome Ids
     */
    public List<Integer> getBlackListedBiomes() {
        return blackListedBiomeIds;
    }

    /**
     * Registers a biome as high pressure for use with the planet generators (It will only spawn on planets with high pressure)
     *
     * @param biome
     */
    public void registerHighPressureBiome(Biome biome) {
        registeredHighPressureBiomes.add(biome);
        registerBlackListBiome(biome);
    }

    public List<Biome> getHighPressureBiomes() {
        return registeredHighPressureBiomes;
    }

    /**
     * Registers a biome to have a chance to spawn as the only biome on a planet, will not register the biome if it is in the blacklist already
     *
     * @param biome
     */
    public void registerSingleBiome(Biome biome) {
        if (!blackListedBiomeIds.contains(Biome.getIdForBiome(biome)))
            registeredSingleBiome.add(biome);
    }

    public void blackListVanillaBiomes() {
        //Good grief... this is long, better than making users do it though..
        for (int i = 0; i < 40; i++)
            blackListedBiomeIds.add(i);

        blackListedBiomeIds.add(127);
        blackListedBiomeIds.add(129);
        blackListedBiomeIds.add(130);
        blackListedBiomeIds.add(131);
        blackListedBiomeIds.add(132);
        blackListedBiomeIds.add(133);
        blackListedBiomeIds.add(134);
        blackListedBiomeIds.add(140);
        blackListedBiomeIds.add(149);
        blackListedBiomeIds.add(151);
        blackListedBiomeIds.add(155);
        blackListedBiomeIds.add(156);
        blackListedBiomeIds.add(157);
        blackListedBiomeIds.add(158);
        blackListedBiomeIds.add(160);
        blackListedBiomeIds.add(161);
        blackListedBiomeIds.add(162);
        blackListedBiomeIds.add(163);
        blackListedBiomeIds.add(164);
        blackListedBiomeIds.add(165);
        blackListedBiomeIds.add(166);
        blackListedBiomeIds.add(167);
    }

    public List<Biome> getSingleBiome() {
        return registeredSingleBiome;
    }

    /**
     * Gets Biomes from Stellurgy's biomes registry.  If it does not exist attepts to retrieve from vanilla forge
     *
     * @param id biome id
     * @return Biome retrieved from the biome ID
     */
    public Biome getBiomeById(int id) {

        for (Biome biome : registeredBiomes) {
            if (Biome.getIdForBiome(biome) == id)
                return biome;
        }

        return Biome.getBiome(id);
    }

}
