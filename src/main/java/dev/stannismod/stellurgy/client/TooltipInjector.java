package dev.stannismod.stellurgy.client;

import net.minecraft.client.gui.GuiScreen;
import net.minecraft.client.resources.I18n;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.util.ResourceLocation;
import net.minecraft.util.text.TextFormatting;
import net.minecraftforge.client.event.ModelRegistryEvent;
import net.minecraftforge.event.entity.player.ItemTooltipEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;
import net.minecraftforge.fml.relauncher.Side;
import net.minecraftforge.fml.relauncher.SideOnly;
import org.lwjgl.input.Keyboard;
import dev.stannismod.stellurgy.api.Constants;
import dev.stannismod.stellurgy.api.StellurgyConfiguration;

import net.minecraftforge.fluids.FluidStack;
import net.minecraftforge.fluids.FluidRegistry;
import dev.stannismod.stellurgy.api.fuel.FuelRegistry;
import dev.stannismod.stellurgy.api.fuel.FuelRegistry.FuelType;

import javax.annotation.Nullable;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Every static field of this type is effectively final, process lifetime: built once at class initialisation.
 */
@Mod.EventBusSubscriber(modid = Constants.modId, value = Side.CLIENT)
public final class TooltipInjector {

    private TooltipInjector() {}

    /** Maps exact registry IDs -> base tooltip lang key */
    private static final Map<String, String> KEY_BY_ID = new HashMap<>();
    /** Fallback: maps the unlocalized-name tail -> base tooltip lang key */
    private static final Map<String, String> KEY_BY_SUFFIX = new HashMap<>();
    /** Optional: dynamic args for formatted lines (usually used in .alt.2) */
    @FunctionalInterface interface ArgProvider { Object[] get(ItemStack s); }
    private static final Map<String, ArgProvider> ARGS_BY_BASEKEY = new HashMap<>();
    /** For items that need per-stack (e.g., meta) keys */
    private static final Map<String, java.util.function.Function<ItemStack, String>> KEY_RESOLVER_BY_ID = new HashMap<>();


    static {
        // ---- CO2 Scrubber / Oxygen Vent ---- ----
        KEY_BY_ID.put("stellurgy:oxygenscrubber", "tooltip.stellurgy.scrubber");
        KEY_BY_ID.put("stellurgy:oxygenvent",  "tooltip.stellurgy.oxygenvent");

        ARGS_BY_BASEKEY.put("tooltip.stellurgy.oxygenvent",
                s -> new Object[] { StellurgyConfiguration.getCurrentConfig().oxygenVentSize });

        KEY_BY_ID.put("stellurgy:carbonscrubbercartridge", "tooltip.stellurgy.scrubbercart");
        KEY_BY_ID.put("libvulpes:linker", "tooltip.stellurgy.libvulpes.linker");


        // ---- Structure Tower ----
        KEY_BY_ID.put("stellurgy:structuretower", "tooltip.stellurgy.structuretower");
        KEY_BY_ID.put("libvulpes:structuremachine", "tooltip.libvulpes.structuremachine");
        KEY_BY_ID.put("libvulpes:advstructuremachine", "tooltip.libvulpes.advstructuremachine");


        // --- ItemUpgrade (meta-based) 6 Space Suit Components---
        KEY_RESOLVER_BY_ID.put("stellurgy:itemupgrade",
                s -> "tooltip.stellurgy.itemupgrade." + s.getItemDamage());
        KEY_RESOLVER_BY_ID.put("stellurgy:item_upgrade",
                s -> "tooltip.stellurgy.itemupgrade." + s.getItemDamage());

        // ---- Guidance Computer ----
        KEY_BY_ID.put("stellurgy:guidancecomputer", "tooltip.stellurgy.guidancecomputer");
        KEY_BY_ID.put("stellurgy:servicemonitor", "tooltip.stellurgy.servicemonitor");
        KEY_BY_ID.put("stellurgy:servicestation", "tooltip.stellurgy.servicestation");
        KEY_BY_ID.put("stellurgy:oxygencharger", "tooltip.stellurgy.oxygencharger");


        // --- Station Controllers 
        KEY_BY_ID.put("stellurgy:orientationcontroller", "tooltip.stellurgy.orientationctrl");
        KEY_BY_ID.put("stellurgy:gravitycontroller",     "tooltip.stellurgy.gravityctrl");
        KEY_BY_ID.put("stellurgy:altitudecontroller",    "tooltip.stellurgy.altitudectrl");


        KEY_BY_ID.put("stellurgy:smallairlockdoor", "tooltip.stellurgy.smallairlock");
        KEY_BY_ID.put("stellurgy:planetselector",       "tooltip.stellurgy.planetselector");
        KEY_BY_ID.put("stellurgy:planetholoselector",   "tooltip.stellurgy.planetholoselector");
        KEY_BY_ID.put("stellurgy:circlelight", "tooltip.stellurgy.circlelight");
        KEY_BY_ID.put("stellurgy:monitoringstation",  "tooltip.stellurgy.monitoringstation");
        KEY_BY_ID.put("stellurgy:satellitebuilder", "tooltip.stellurgy.satellitebuilder");
        KEY_BY_ID.put("stellurgy:satellitecontrolcenter",   "tooltip.stellurgy.satellitecontrolcenter");


        // --- Satellite Primary Function (metas 0..6)
        KEY_RESOLVER_BY_ID.put("stellurgy:satelliteprimaryfunction", s -> {
            switch (s.getMetadata() & 7) {
                case 0: return "tooltip.stellurgy.satfunc.optical";
                case 1: return "tooltip.stellurgy.satfunc.composition";
                case 2: return "tooltip.stellurgy.satfunc.mass";
                case 3: return "tooltip.stellurgy.satfunc.microwave";
                case 4: return "tooltip.stellurgy.satfunc.oremapping";
                case 5: return "tooltip.stellurgy.satfunc.biomechanger";
                case 6: return "tooltip.stellurgy.satfunc.weather";
                default: return null;
            }
        });
        // camelCase fallback
        KEY_RESOLVER_BY_ID.put("stellurgy:satellitePrimaryFunction",
            KEY_RESOLVER_BY_ID.get("stellurgy:satelliteprimaryfunction"));

        // --- Satellite Power Source (metas 0..1)
        KEY_RESOLVER_BY_ID.put("stellurgy:satellitepowersource", s -> {
            switch (s.getMetadata() & 1) {
                case 0: return "tooltip.stellurgy.satpower.0"; // Basic solar
                case 1: return "tooltip.stellurgy.satpower.1"; // Advanced solar
                default: return null;
            }
        });
        KEY_RESOLVER_BY_ID.put("stellurgy:satellitePowerSource",
        KEY_RESOLVER_BY_ID.get("stellurgy:satellitepowersource"));

        // ---- LibVulpes Battery (meta 0..1) ----
        KEY_RESOLVER_BY_ID.put("libvulpes:battery", s -> "tooltip.libvulpes.battery." + (s.getMetadata() & 1));

        // ---- ID Chips / Chips ----
        KEY_BY_ID.put("stellurgy:satelliteidchip",   "tooltip.stellurgy.satidchip");
        KEY_BY_ID.put("stellurgy:planetidchip",      "tooltip.stellurgy.planetidchip");
        KEY_BY_ID.put("stellurgy:stationchip",       "tooltip.stellurgy.stationchip");
        KEY_BY_ID.put("stellurgy:spacestationchip",  "tooltip.stellurgy.stationchip");
        KEY_BY_ID.put("stellurgy:elevatorchip",      "tooltip.stellurgy.elevatorchip");
        KEY_BY_ID.put("stellurgy:asteroidchip",      "tooltip.stellurgy.asteroidchip");


        // ---- Energy multiblocks ----
        KEY_BY_ID.put("stellurgy:blackholegenerator", "tooltip.stellurgy.blackholegen");
        KEY_BY_ID.put("stellurgy:microwavereciever",  "tooltip.stellurgy.microwavereceiver");
        KEY_BY_ID.put("stellurgy:solarpanel",  "tooltip.stellurgy.solarpanel");
        KEY_BY_ID.put("stellurgy:solararray",         "tooltip.stellurgy.solararray");
        KEY_BY_ID.put("stellurgy:solararraypanel",    "tooltip.stellurgy.solararraypanel");

        // Advanced Data Bus
        KEY_BY_ID.put("stellurgy:databusbig",    "tooltip.stellurgy.databusbig");
        // ---- BlockStellurgyHatch (registered as stellurgy:loader), meta 0..6 ----
        KEY_RESOLVER_BY_ID.put("stellurgy:loader", s -> {
            final int v = s.getMetadata() & 7; // strip redstone/state bit
            switch (v) {
                case 0: return "tooltip.stellurgy.hatch.databus";
                case 1: return "tooltip.stellurgy.hatch.satellite";
                case 2: return "tooltip.stellurgy.hatch.item_unloader";
                case 3: return "tooltip.stellurgy.hatch.item_loader";
                case 4: return "tooltip.stellurgy.hatch.fluid_unloader";
                case 5: return "tooltip.stellurgy.hatch.fluid_loader";
                case 6: return "tooltip.stellurgy.hatch.gca";
                default: return null;
            }
        });

        // ---- Processing / Machines / Multiblocks----
        KEY_BY_ID.put("stellurgy:arcfurnace", "tooltip.stellurgy.arcfurnace");
        KEY_BY_ID.put("stellurgy:rollingmachine",     "tooltip.stellurgy.rollingmachine");
        KEY_BY_ID.put("stellurgy:lathe",              "tooltip.stellurgy.lathe");
        KEY_BY_ID.put("stellurgy:crystallizer",       "tooltip.stellurgy.crystallizer");
        KEY_BY_ID.put("stellurgy:cuttingmachine",     "tooltip.stellurgy.cuttingmachine");
        KEY_BY_ID.put("stellurgy:precisionassemblingmachine", "tooltip.stellurgy.precisionassembler");
        KEY_BY_ID.put("stellurgy:electrolyser",       "tooltip.stellurgy.electrolyser");
        KEY_BY_ID.put("stellurgy:chemicalreactor",        "tooltip.stellurgy.chemreactor");
        KEY_BY_ID.put("stellurgy:precisionlaseretcher","tooltip.stellurgy.precisionlaseretcher");
        KEY_BY_ID.put("stellurgy:observatory",        "tooltip.stellurgy.observatory");
        KEY_BY_ID.put("stellurgy:planetanalyser",     "tooltip.stellurgy.planetanalyser");
        KEY_BY_ID.put("stellurgy:centrifuge",         "tooltip.stellurgy.centrifuge");
        KEY_BY_ID.put("stellurgy:orbitalregistry",         "tooltip.stellurgy.orbitalregistry");
        KEY_BY_ID.put("stellurgy:warpcore",           "tooltip.stellurgy.warpcore");
        KEY_BY_ID.put("stellurgy:beacon",             "tooltip.stellurgy.beacon");
        KEY_BY_ID.put("stellurgy:biomescanner",       "tooltip.stellurgy.biomescan");
        KEY_BY_ID.put("stellurgy:railgun",            "tooltip.stellurgy.railgun");
        KEY_BY_ID.put("stellurgy:spaceelevatorcontroller", "tooltip.stellurgy.spaceelevatorctrl");
        KEY_BY_ID.put("stellurgy:terraformer", "tooltip.stellurgy.atmosterraformer");
        KEY_BY_ID.put("stellurgy:gravitymachine", "tooltip.stellurgy.gravitymachine");
        KEY_BY_ID.put("stellurgy:spacelaser",  "tooltip.stellurgy.spacelaser");

        // ---- Building / components ----
        KEY_BY_ID.put("stellurgy:concrete",        "tooltip.stellurgy.concrete");
        KEY_BY_ID.put("stellurgy:blastbrick",      "tooltip.stellurgy.blastbrick");
        KEY_BY_ID.put("stellurgy:iquartzcrucible",       "tooltip.stellurgy.qcrucible");
        KEY_BY_ID.put("stellurgy:sawblade",        "tooltip.stellurgy.sawblade");
        KEY_BY_ID.put("stellurgy:vacuumlaser", "tooltip.stellurgy.vacuumlaser");
        
        KEY_BY_ID.put("stellurgy:hovercraft", "tooltip.stellurgy.hovercraft");

        // ---- Pump ----
        KEY_BY_ID.put("stellurgy:blockpump", "tooltip.stellurgy.pump");

        // ---- Remotes ----
        KEY_BY_ID.put("stellurgy:biomechanger", "tooltip.stellurgy.biomechangerremote");
        KEY_BY_ID.put("stellurgy:weathercontroller", "tooltip.stellurgy.weathercontrollerremote");
        KEY_BY_ID.put("stellurgy:orescanner", "tooltip.stellurgy.orescanner");


        // ---- Crafting items ----
        KEY_BY_ID.put("stellurgy:sawbladeiron", "tooltip.stellurgy.sawbladeiron");
        KEY_BY_ID.put("stellurgy:wafer",        "tooltip.stellurgy.wafer");
        KEY_BY_ID.put("stellurgy:itemcircuitplate", "tooltip.stellurgy.circuitplate");
        KEY_BY_ID.put("stellurgy:lens",          "tooltip.stellurgy.itemlens");
        KEY_BY_ID.put("stellurgy:ic",           "tooltip.stellurgy.circuitic");
        KEY_BY_ID.put("stellurgy:miscpart",     "tooltip.stellurgy.miscpart");
        KEY_BY_ID.put("stellurgy:misc",     "tooltip.stellurgy.misc");

        // ---- Assemblers ----
        KEY_BY_ID.put("stellurgy:rocketbuilder", "tooltip.stellurgy.rocketassembler");
        KEY_BY_ID.put("stellurgy:stationbuilder", "tooltip.stellurgy.stationassembler");
        KEY_BY_ID.put("stellurgy:deployablerocketbuilder", "tooltip.stellurgy.deployablerocketassembler");

        // ---- LibVulpes blocks ----
        KEY_BY_ID.put("libvulpes:coalgenerator", "tooltip.stellurgy.libvulpes.coalgenerator"); 
        KEY_BY_ID.put("libvulpes:hatch", "tooltip.stellurgy.libvulpes.hatch");
        KEY_BY_ID.put("libvulpes:forgepowerinput", "tooltip.stellurgy.libvulpes.forgepowerinput"); 
        KEY_BY_ID.put("libvulpes:forgepoweroutput", "tooltip.stellurgy.libvulpes.forgepoweroutput"); 
        KEY_BY_ID.put("libvulpes:creativepowerbattery", "tooltip.stellurgy.libvulpes.creativepowerbattery");

        // ---- Fuel Tanks ----
        KEY_BY_ID.put("stellurgy:fueltank", "tooltip.stellurgy.fueltank");
        KEY_BY_ID.put("stellurgy:bipropellantfueltank", "tooltip.stellurgy.bipropfueltank");
        KEY_BY_ID.put("stellurgy:oxidizerfueltank", "tooltip.stellurgy.oxidizerfueltank");
        KEY_BY_ID.put("stellurgy:nuclearfueltank", "tooltip.stellurgy.nuclearfueltank");

        // Monoprop tank
        ARGS_BY_BASEKEY.put("tooltip.stellurgy.fueltank",
            s -> new Object[]{ listFluidsFor(FuelType.LIQUID_MONOPROPELLANT, 6) });
        // Biprop fuel tank
        ARGS_BY_BASEKEY.put("tooltip.stellurgy.bipropfueltank",
            s -> new Object[]{ listFluidsFor(FuelType.LIQUID_BIPROPELLANT, 6) });
        // Oxidizer tank
        ARGS_BY_BASEKEY.put("tooltip.stellurgy.oxidizerfueltank",
            s -> new Object[]{ listFluidsFor(FuelType.LIQUID_OXIDIZER, 6) });
        // Nuclear working fluid tank
        ARGS_BY_BASEKEY.put("tooltip.stellurgy.nuclearfueltank",
            s -> new Object[]{ listFluidsFor(FuelType.NUCLEAR_WORKING_FLUID, 6) });

        // Example for adding more items later (no code changes beyond these lines):
        // KEY_BY_ID.put("stellurgy:carbonscrubbercartridge", "tooltip.stellurgy.scrubbercart");
        // KEY_BY_SUFFIX.put("carbonScrubberCartridge",              "tooltip.stellurgy.scrubbercart");
        // ARGS_BY_BASEKEY.put("tooltip.stellurgy.scrubbercart", s -> new Object[]{ Math.max(0, s.getMaxDamage() - s.getItemDamage()) });
    }

    @SubscribeEvent
    public static void onModels(ModelRegistryEvent e) { /* no-op */ }

    @SubscribeEvent
    public static void onTooltip(ItemTooltipEvent e) {
        final ItemStack stack = e.getItemStack();
        if (stack.isEmpty()) return;

        final List<String> tooltip = e.getToolTip();

        // Insert before the advanced "modid:item" line when advanced tooltips are on
        final int insertAt = (e.getFlags().isAdvanced() && tooltip.size() > 1)
                ? tooltip.size() - 1
                : tooltip.size();

        final Item item = stack.getItem();
        @Nullable final ResourceLocation id = item.getRegistryName();
        String baseKey = null;

        if (id != null) {
            java.util.function.Function<ItemStack, String> res = KEY_RESOLVER_BY_ID.get(id.toString());
            if (res != null) {
                baseKey = res.apply(stack); // e.g., tooltip.stellurgy.itemupgrade.3
            }
            if (baseKey == null) {
                baseKey = KEY_BY_ID.get(id.toString());
            }
        }

        // Fallback: tail of unlocalized name (1.12 style)
        if (baseKey == null) {
            final String transKey = item.getUnlocalizedName(stack);
            final int dot = transKey.lastIndexOf('.');
            if (dot > 0) {
                final String tail = transKey.substring(dot + 1);
                baseKey = KEY_BY_SUFFIX.get(tail);
            }
        }

        if (baseKey != null) {
            renderShiftAlt(stack, tooltip, baseKey, insertAt);
        }
    }

    // ----- Generic renderer for base/shift/alt blocks -----
    @SideOnly(Side.CLIENT)
    public static void renderShiftAlt(ItemStack s, List<String> t, String baseKey, int idx) {
        final ArgProvider ap = ARGS_BY_BASEKEY.get(baseKey);
        final boolean hasShift = I18n.hasKey(baseKey + ".shift.1");
        final boolean hasAlt   = I18n.hasKey(baseKey + ".alt.1") || ap != null;

        // Base block: base, base.1, base.2, ...
        for (int i = 0; i <= 8; i++) {
            final String k = (i == 0) ? baseKey : (baseKey + "." + i);
            if (!I18n.hasKey(k)) {
                if (i == 0) continue; // no bare base line; try .1 anyway
                break;                // stop when sequence ends
            }
            t.add(idx++, TextFormatting.GRAY + (ap != null ? I18n.format(k, ap.get(s)) : I18n.format(k)));
        }

        // Shift block (shift.1..N)
        if (hasShift) {
            if (GuiScreen.isShiftKeyDown()) {
                for (int i = 1; i <= 8; i++) {
                    final String k = baseKey + ".shift." + i;
                    if (!I18n.hasKey(k)) break;
                    t.add(idx++, TextFormatting.GRAY + (ap != null ? I18n.format(k, ap.get(s)) : I18n.format(k)));
                }
            } else if (I18n.hasKey("tooltip.stellurgy.hold_shift")) {
                t.add(idx++, TextFormatting.DARK_GRAY.toString() + TextFormatting.ITALIC +
                        I18n.format("tooltip.stellurgy.hold_shift"));
            }
        }

        // Alt block (alt.1..N)
        if (hasAlt) {
            if (isAltDown()) {
                for (int i = 1; i <= 8; i++) {
                    final String k = baseKey + ".alt." + i;
                    if (!I18n.hasKey(k)) break;
                    t.add(idx++, TextFormatting.GRAY + (ap != null ? I18n.format(k, ap.get(s)) : I18n.format(k)));
                }
            } else if (I18n.hasKey("tooltip.stellurgy.hold_alt")) {
                t.add(idx++, TextFormatting.DARK_GRAY.toString() + TextFormatting.ITALIC +
                        I18n.format("tooltip.stellurgy.hold_alt"));
            }
        }
    }



    // ----- Helpers -----

    @SideOnly(Side.CLIENT)
    private static String listFluidsFor(FuelType type, int max) {
        java.util.List<String> names = new java.util.ArrayList<>();
        for (net.minecraftforge.fluids.Fluid f : FluidRegistry.getRegisteredFluids().values()) {
            try {
                if (FuelRegistry.instance.isFuel(type, f)) {
                    // Localized name (1 bucket)
                    names.add(new FluidStack(f, 1000).getLocalizedName());
                    if (names.size() >= max) break;
                }
            } catch (Throwable t) {
                // be defensive against any odd registry states
            }
        }
        if (names.isEmpty()) return I18n.hasKey("tooltip.stellurgy.none") ? I18n.format("tooltip.stellurgy.none") : "None";
        // if there are more than max, add an ellipsis
        int total = 0;
        for (net.minecraftforge.fluids.Fluid f : FluidRegistry.getRegisteredFluids().values())
            if (FuelRegistry.instance.isFuel(type, f)) total++;
        String s = String.join(", ", names);
        return (total > names.size()) ? (s + ", …") : s;
    }

    private static int addIfPresentGray(List<String> t, String key, int idx) {
        if (I18n.hasKey(key)) {
            t.add(idx, TextFormatting.GRAY + I18n.format(key));
            return idx + 1;
        }
        return idx;
    }

    private static int addIfPresentDarkGray(List<String> t, String key, int idx) {
        if (I18n.hasKey(key)) {
            t.add(idx, TextFormatting.DARK_GRAY + I18n.format(key));
            return idx + 1;
        }
        return idx;
    }

    private static int addIfPresentDarkGrayFmt(List<String> t, String key, int idx, Object... args) {
        if (I18n.hasKey(key)) {
            t.add(idx, TextFormatting.DARK_GRAY + I18n.format(key, args));
            return idx + 1;
        }
        return idx;
    }

    @SideOnly(Side.CLIENT)
    public static int computeInsertIndex(List<String> tooltip, boolean advanced) {
        return (advanced && tooltip.size() > 1) ? tooltip.size() - 1 : tooltip.size();
    }    

    @SideOnly(Side.CLIENT)
    public static boolean isAltDown() {
        return Keyboard.isKeyDown(Keyboard.KEY_LMENU) || Keyboard.isKeyDown(Keyboard.KEY_RMENU);
    }
}
