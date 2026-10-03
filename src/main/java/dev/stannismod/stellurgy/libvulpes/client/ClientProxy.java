package dev.stannismod.stellurgy.libvulpes.client;

import net.minecraft.block.Block;
import net.minecraft.client.Minecraft;
import net.minecraft.client.audio.ITickableSound;
import net.minecraft.client.audio.SoundHandler;
import net.minecraft.client.renderer.block.model.ModelResourceLocation;
import net.minecraft.client.resources.I18n;
import net.minecraft.item.Item;
import net.minecraft.util.SoundCategory;
import net.minecraft.util.SoundEvent;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;
import net.minecraftforge.client.model.ModelLoader;
import net.minecraftforge.client.model.obj.OBJLoader;
import net.minecraftforge.common.MinecraftForge;
import dev.stannismod.stellurgy.libvulpes.LibVulpes;
import dev.stannismod.stellurgy.libvulpes.api.LibVulpesBlocks;
import dev.stannismod.stellurgy.libvulpes.api.LibVulpesItems;
import dev.stannismod.stellurgy.libvulpes.common.CommonProxy;
import dev.stannismod.stellurgy.libvulpes.entity.fx.FxErrorBlock;

import java.util.LinkedList;

public class ClientProxy extends CommonProxy {
	@Override
	public String getLocalizedString(String str) {
		return I18n.format(str);
	}
	
	@Override
	public void init() {
		LibVulpes.materialRegistry.init();
	}
	
	@Override
	public void preInit() {
		OBJLoader.INSTANCE.addDomain("libvulpes");
	}
	
	@Override
	public void registerEventHandlers() {
		MinecraftForge.EVENT_BUS.register(LibVulpesItems.itemHoloProjector);
	}
	@Override
	public void spawnParticle(String particle, World world, double x, double y, double z, double motionX, double motionY, double motionZ) {

		if(particle.equals("errorBox")) {
			FxErrorBlock fx = new FxErrorBlock(world, x, y, z);
			Minecraft.getMinecraft().effectRenderer.addEffect(fx);
		}
	}
	@Override
	public void preInitItems()
	{
        //Register Item models
        ModelLoader.setCustomModelResourceLocation(LibVulpesItems.itemLinker, 0, new ModelResourceLocation(LibVulpesItems.itemLinker.getRegistryName(), "inventory"));
        ModelLoader.setCustomModelResourceLocation(LibVulpesItems.itemHoloProjector, 0, new ModelResourceLocation(LibVulpesItems.itemHoloProjector.getRegistryName(), "inventory"));
        ModelLoader.setCustomModelResourceLocation(LibVulpesItems.itemBattery, 0, new ModelResourceLocation("libvulpes:smallBattery", "inventory"));
        ModelLoader.setCustomModelResourceLocation(LibVulpesItems.itemBattery, 1, new ModelResourceLocation("libvulpes:small2xBattery", "inventory"));
	}
	
	@Override
	public void preInitBlocks()
	{
		LinkedList<Item> blockItems = new LinkedList<>();
		
		blockItems.add(Item.getItemFromBlock(LibVulpesBlocks.blockAdvancedMotor));
		blockItems.add(Item.getItemFromBlock(LibVulpesBlocks.blockAdvStructureBlock));
		blockItems.add(Item.getItemFromBlock(LibVulpesBlocks.blockCoalGenerator));
		blockItems.add(Item.getItemFromBlock(LibVulpesBlocks.blockCreativeInputPlug));
		blockItems.add(Item.getItemFromBlock(LibVulpesBlocks.blockEliteMotor));
		blockItems.add(Item.getItemFromBlock(LibVulpesBlocks.blockEnhancedMotor));
		blockItems.add(Item.getItemFromBlock(LibVulpesBlocks.blockForgeInputPlug));
		blockItems.add(Item.getItemFromBlock(LibVulpesBlocks.blockForgeOutputPlug));
		if(LibVulpesBlocks.blockIC2Plug != null)
			blockItems.add(Item.getItemFromBlock(LibVulpesBlocks.blockIC2Plug));
		blockItems.add(Item.getItemFromBlock(LibVulpesBlocks.blockMotor));
		//blockItems.add(Item.getItemFromBlock(LibVulpesBlocks.blockRFBattery));
		//blockItems.add(Item.getItemFromBlock(LibVulpesBlocks.blockRFOutput));
		blockItems.add(Item.getItemFromBlock(LibVulpesBlocks.blockStructureBlock));
		
		for(Item blockItem2 : blockItems)
			ModelLoader.setCustomModelResourceLocation(blockItem2, 0, new ModelResourceLocation(blockItem2.getRegistryName(), "inventory"));
		
		for(Block block : LibVulpesBlocks.itemBlocks)
			ModelLoader.setCustomModelResourceLocation(Item.getItemFromBlock(block), 0, new ModelResourceLocation(block.getRegistryName(), "inventory"));
	}
	
	@Override
	public void playSound(Object sound) {
		if(sound instanceof ITickableSound) {
			ITickableSound sound2 = (ITickableSound)sound;
			//does not work - game crashes sometimes...
			if (!Minecraft.getMinecraft().getSoundHandler().isSoundPlaying(sound2)) {
				Minecraft.getMinecraft().getSoundHandler().playSound(sound2);
			}

		}
	}
	
	@Override
	public void playSound(World worldObj, BlockPos pos, SoundEvent event, SoundCategory cat, float volume, float pitch) {
		worldObj.playSound(Minecraft.getMinecraft().player, pos, event, SoundCategory.BLOCKS, Minecraft.getMinecraft().gameSettings.getSoundLevel(cat)*volume,  0.975f + worldObj.rand.nextFloat()*0.05f);
		
	}
}
