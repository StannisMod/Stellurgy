package org.valkyrienskies.mod.proxy;

import net.minecraft.block.Block;
import org.valkyrienskies.mod.client.EventsClient;
import org.valkyrienskies.mod.client.VSKeyHandler;
import org.valkyrienskies.mod.common.ValkyrienSkiesMod;

import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.block.model.ModelResourceLocation;
import net.minecraft.client.resources.IReloadableResourceManager;
import net.minecraft.item.Item;
import net.minecraftforge.client.model.ModelLoader;
import net.minecraftforge.client.model.obj.OBJLoader;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.fml.common.event.FMLPostInitializationEvent;
import net.minecraftforge.fml.common.event.FMLPreInitializationEvent;

public class ClientProxy extends CommonProxy {
    // This can be called from addon code because it doesnt set namespace:id.
    public void registerItemRender(Item item, int meta) {
        ModelLoader.setCustomModelResourceLocation(item, meta,
            new ModelResourceLocation(item.getRegistryName(), "inventory"));
    }

    /**
     * A field of the sided proxy, so a static by transitivity. Final, lifetime the client process:
     * created with the proxy Forge injects, registered on the bus once by {@link #preInit}.
     *
     * Effectively final, process lifetime: set once when the object is built.
     */
    private final VSKeyHandler keyEvents = new VSKeyHandler();

    @Override
    public void preInit(FMLPreInitializationEvent e) {
        super.preInit(e);
        OBJLoader.INSTANCE.addDomain(ValkyrienSkiesMod.MOD_ID.toLowerCase());

        // Register events
        MinecraftForge.EVENT_BUS.register(new EventsClient());
        MinecraftForge.EVENT_BUS.register(keyEvents);

        registerAnimations();
    }

    @Override
    public void postInit(FMLPostInitializationEvent e) {
        super.postInit(e);
        Minecraft.getMinecraft().getFramebuffer().enableStencil();

        registerBlockItem(dev.stannismod.stellurgy.Stellurgy.instance.valkyrienSkies.captainsChair);
        registerBlockItem(dev.stannismod.stellurgy.Stellurgy.instance.valkyrienSkies.passengerChair);
    }

    private void registerAnimations() {

    }

    // Registers the inventory model for the ItemBlock of "toRegister"
    private static void registerBlockItem(Block toRegister) {
        Item item = Item.getItemFromBlock(toRegister);
        Minecraft.getMinecraft()
                .getRenderItem()
                .getItemModelMesher()
                .register(item, 0, new ModelResourceLocation(
                        ValkyrienSkiesMod.MOD_ID + ":" + item.getUnlocalizedName()
                                .substring(5), "inventory"));
    }

}
