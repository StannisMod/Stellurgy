package dev.stannismod.stellurgy.integration.waila;

import mcp.mobius.waila.api.IWailaPlugin;
import mcp.mobius.waila.api.IWailaRegistrar;
import mcp.mobius.waila.api.WailaPlugin;
import net.minecraft.block.Block;
import dev.stannismod.stellurgy.entity.EntityRocket;

@WailaPlugin
public class WailaIntegration implements IWailaPlugin {

	@Override
	public void register(IWailaRegistrar waila) {
        RocketEntityProvider rep = new RocketEntityProvider();
		waila.registerNBTProvider(rep, EntityRocket.class);
        waila.registerHeadProvider(rep, EntityRocket.class);
        waila.registerBodyProvider(rep, EntityRocket.class);

		DataBlockProvider dbp = new DataBlockProvider();
		waila.registerNBTProvider(dbp, Block.class);
		waila.registerBodyProvider(dbp, Block.class);
	}
    
}
