package dev.stannismod.stellurgy.inventory;

import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.item.ItemStack;
import net.minecraft.util.EnumHand;
import net.minecraft.world.World;
import net.minecraftforge.fml.common.network.IGuiHandler;
import dev.stannismod.stellurgy.api.satellite.SatelliteBase;
import dev.stannismod.stellurgy.dimension.DimensionManager;
import dev.stannismod.stellurgy.satellite.SatelliteOreMapping;
import zmaster587.libVulpes.inventory.modules.IModularInventory;

public class GuiHandler implements IGuiHandler {

    /**
     * The ore-mapping satellite's GUI id - Stellurgy's only GUI of its own. libVulpes is folded into
     * this mod's container, so its modular ids ({@code MODULAR} .. {@code MODULARFULLSCREEN},
     * ordinals 0-3) travel in the same id space and this handler serves them too; Stellurgy's own ids
     * therefore start past the last of them. This id used to be the ordinal 2, which is libVulpes'
     * {@code MODULARCENTEREDFULLSCREEN}: harmless while libVulpes owned a container and a handler of
     * its own, a collision the moment both ids arrive here.
     */
    public static final int ORE_MAPPING_SATELLITE = zmaster587.libVulpes.inventory.GuiHandler.guiId.values().length;

    // Stateless dispatcher for every libVulpes modular id. One shared instance - it holds no state.
    private static final zmaster587.libVulpes.inventory.GuiHandler LIBVULPES =
            new zmaster587.libVulpes.inventory.GuiHandler();

    //X coord is entity ID num if entity
    @Override
    public Object getServerGuiElement(int ID, EntityPlayer player, World world,
                                      int x, int y, int z) {

        Object tile;

        if (x == -1 && y < -1) {
            ItemStack stack = player.getHeldItem(EnumHand.MAIN_HAND);

            //If there is latency or some desync odd things can happen so check for that
            if (stack.isEmpty() || !(stack.getItem() instanceof IModularInventory)) {
                return null;
            }
        }

        if (ID == ORE_MAPPING_SATELLITE) {
            SatelliteBase satellite = DimensionManager.getInstance().getSatellite(y);

            if (!(satellite instanceof SatelliteOreMapping) || satellite.getDimensionId() != world.provider.getDimension())
                satellite = null;

            return new ContainerOreMappingSatellite((SatelliteOreMapping) satellite, player.inventory);
        }
        // Every other id is libVulpes'. FML keeps ONE handler per container, and this container is
        // the only one libVulpes has, so without this delegation every libVulpes GUI resolves to
        // null and never opens. An id outside libVulpes' enum answers null there.
        return LIBVULPES.getServerGuiElement(ID, player, world, x, y, z);
    }

    @Override
    public Object getClientGuiElement(int ID, EntityPlayer player, World world,
                                      int x, int y, int z) {

        if (x == -1 && y < -1) {
            ItemStack stack = player.getHeldItem(EnumHand.MAIN_HAND);

            //If there is latency or some desync odd things can happen so check for that
            if (stack.isEmpty() || !(stack.getItem() instanceof IModularInventory)) {
                return null;
            }
        }

        if (ID == ORE_MAPPING_SATELLITE) {

            SatelliteBase satellite = DimensionManager.getInstance().getSatellite(y);

            if (!(satellite instanceof SatelliteOreMapping) || satellite.getDimensionId() != world.provider.getDimension())
                satellite = null;

            return new GuiOreMappingSatellite((SatelliteOreMapping) satellite, player);
        }
        // Every other id is libVulpes' - see the server side above.
        return LIBVULPES.getClientGuiElement(ID, player, world, x, y, z);
    }
}
