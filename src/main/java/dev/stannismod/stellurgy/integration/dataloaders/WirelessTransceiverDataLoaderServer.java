package dev.stannismod.stellurgy.integration.dataloaders;

import dev.stannismod.stellurgy.tile.TileWirelessTransceiver;

public class WirelessTransceiverDataLoaderServer extends WirelessTransceiverDataLoader {

    DataBlockDataLoaderServer data;
    TileWirelessTransceiver transceiver;

    public WirelessTransceiverDataLoaderServer(TileWirelessTransceiver transceiver) {
        this.transceiver = transceiver;
    }

	@Override
	public boolean isLinked() {
		return transceiver.isLinkedWireless();
	}

	@Override
	public boolean isExtracting() {
		return transceiver.isExtractModeWireless();
	}

	@Override
	public int getNetworkId() {
		return transceiver.getWirelessNetworkId();
	}
    
}
