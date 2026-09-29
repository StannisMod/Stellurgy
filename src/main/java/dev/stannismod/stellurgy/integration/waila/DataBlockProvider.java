package dev.stannismod.stellurgy.integration.waila;

import java.util.List;

import mcp.mobius.waila.api.IWailaConfigHandler;
import mcp.mobius.waila.api.IWailaDataAccessor;
import mcp.mobius.waila.api.IWailaDataProvider;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;
import dev.stannismod.stellurgy.api.DataStorage;
import dev.stannismod.stellurgy.api.DataStorage.DataType;
import dev.stannismod.stellurgy.integration.dataloaders.AbstractDataContext;
import dev.stannismod.stellurgy.integration.dataloaders.DataBlockDataLoader;
import dev.stannismod.stellurgy.integration.dataloaders.DataBlockDataLoaderServer;
import dev.stannismod.stellurgy.integration.dataloaders.WirelessTransceiverDataLoader;
import dev.stannismod.stellurgy.integration.dataloaders.WirelessTransceiverDataLoaderServer;
import dev.stannismod.stellurgy.tile.TileWirelessTransceiver;

public class DataBlockProvider implements IWailaDataProvider {

    @Override
    public NBTTagCompound getNBTData(EntityPlayerMP player, TileEntity te, NBTTagCompound tag, World world, BlockPos pos) {
        if (te != null) {
            if (te instanceof TileWirelessTransceiver) {
                WirelessTransceiverDataLoader loader = new WirelessTransceiverDataLoaderServer((TileWirelessTransceiver) te);
                NBTTagCompound transceiverData = new NBTTagCompound();
                transceiverData.setBoolean("linked", loader.isLinked());
                transceiverData.setBoolean("extracting", loader.isExtracting());
                transceiverData.setInteger("networkId", loader.getNetworkId());
                tag.setTag("stellurgy_transceiver", transceiverData);
            }

            DataStorage ds = DataBlockDataLoader.getDataStorage(te);
            if (ds != null) {
                DataBlockDataLoader loader = new DataBlockDataLoaderServer(ds);
                NBTTagCompound dataStorageData = new NBTTagCompound();
                dataStorageData.setBoolean("locked", loader.isLocked());
                dataStorageData.setInteger("data", loader.getDataAmount());
                dataStorageData.setInteger("maxData", loader.getMaxData());
                dataStorageData.setInteger("type", loader.getDataType().id);
                tag.setTag("stellurgy_data", dataStorageData);
            }
        }

        return tag;
    }

    static class WailaWirelessTransceiverLoader extends WirelessTransceiverDataLoader {
        NBTTagCompound nbt;

        WailaWirelessTransceiverLoader(NBTTagCompound nbt) {
            this.nbt = nbt;
        }

		@Override
		public boolean isLinked() {
			return nbt.getBoolean("linked");
		}

		@Override
		public boolean isExtracting() {
			return nbt.getBoolean("extracting");
		}

		@Override
		public int getNetworkId() {
			return nbt.getInteger("networkId");
		}
        
    }

    static class WailaDataBlockLoader extends DataBlockDataLoader {
        NBTTagCompound nbt;

        WailaDataBlockLoader(NBTTagCompound nbt) {
            this.nbt = nbt;
        }

		@Override
		public DataType getDataType() {
			return DataType.getById(nbt.getInteger("type"));
		}

		@Override
		public boolean isLocked() {
			return nbt.getBoolean("locked");
		}

		@Override
		public int getDataAmount() {
			return nbt.getInteger("data");
		}

		@Override
		public int getMaxData() {
			return nbt.getInteger("maxData");
		}
        
    }

    @Override
    public List<String> getWailaBody(ItemStack itemStack, List<String> tooltip, IWailaDataAccessor accessor, IWailaConfigHandler config) {
        NBTTagCompound nbt = accessor.getNBTData();
        boolean showLocked = true;
        AbstractDataContext ctx = new WailaDataContext(tooltip);
        if (nbt.hasKey("stellurgy_transceiver")) {
            showLocked = false;
            WirelessTransceiverDataLoader loader = new WailaWirelessTransceiverLoader(nbt.getCompoundTag("stellurgy_transceiver"));
            loader.addWirelessDataInfo(ctx);
        }
        if (nbt.hasKey("stellurgy_data")) {
            DataBlockDataLoader loader = new WailaDataBlockLoader(nbt.getCompoundTag("stellurgy_data"));
            loader.addCommonDataInfo(ctx, showLocked);
        }
        return tooltip;
    }

}
