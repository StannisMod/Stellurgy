package dev.stannismod.stellurgy.libvulpes.util;

import net.minecraft.util.ResourceLocation;
import dev.stannismod.stellurgy.libvulpes.LibVulpes;

public class TeslaCapabilityProvider {

	public static void registerCap() {
	    //MinecraftForge.EVENT_BUS.register(TeslaCapabilityProvider.class);
	    LibVulpes.logger.info("Tesla integration loaded");
	}
	private static final ResourceLocation KEY = new ResourceLocation("libvulpes:ProviderTesla");

//	@SubscribeEvent
//	public static void attachCapabilities(AttachCapabilitiesEvent.TileEntity evt)
//	{
//		if (evt.getCapabilities().containsKey(KEY)) {
//			return;
//		}
//		TileEntity te = evt.getTileEntity();
//		if (te instanceof TileEntityRFConsumer || te instanceof TilePlugBase) {
//			evt.addCapability(KEY, te);
//		}
//	}

}
