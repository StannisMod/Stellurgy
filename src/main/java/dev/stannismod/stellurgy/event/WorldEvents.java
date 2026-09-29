package dev.stannismod.stellurgy.event;

import net.minecraft.world.World;
import net.minecraftforge.common.ForgeChunkManager.LoadingCallback;
import net.minecraftforge.common.ForgeChunkManager.Ticket;

import java.util.List;

public class WorldEvents implements LoadingCallback {

    @Override
    public void ticketsLoaded(List<Ticket> tickets, World world) {

    }
}
