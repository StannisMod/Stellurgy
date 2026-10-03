package dev.stannismod.stellurgy.tile.multiblock;

import dev.stannismod.stellurgy.atmosphere.AirState;
import dev.stannismod.stellurgy.tile.heat.TileWasteHeatPowerConsumer;

import io.netty.buffer.ByteBuf;
import net.minecraft.client.Minecraft;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.init.Blocks;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.util.EnumFacing;
import net.minecraft.util.SoundCategory;
import net.minecraft.util.SoundEvent;
import net.minecraft.util.math.AxisAlignedBB;
import net.minecraft.util.math.BlockPos;
import net.minecraftforge.fluids.FluidStack;
import net.minecraftforge.fluids.capability.IFluidHandler;
import net.minecraftforge.fml.relauncher.Side;
import net.minecraftforge.fml.relauncher.SideOnly;
import dev.stannismod.stellurgy.Stellurgy;
import dev.stannismod.stellurgy.api.StellurgyConfiguration;
import dev.stannismod.stellurgy.api.StellurgyBlocks;
import dev.stannismod.stellurgy.api.StellurgyFluids;
import dev.stannismod.stellurgy.dimension.DimensionManager;
import dev.stannismod.stellurgy.dimension.DimensionProperties;
import dev.stannismod.stellurgy.util.AudioRegistry;
import dev.stannismod.stellurgy.world.provider.WorldProviderPlanet;
import dev.stannismod.stellurgy.libvulpes.LibVulpes;
import dev.stannismod.stellurgy.libvulpes.api.LibVulpesBlocks;
import dev.stannismod.stellurgy.libvulpes.block.RotatableBlock;
import dev.stannismod.stellurgy.libvulpes.gui.CommonResources;
import dev.stannismod.stellurgy.libvulpes.inventory.TextureResources;
import dev.stannismod.stellurgy.libvulpes.inventory.modules.*;
import dev.stannismod.stellurgy.libvulpes.network.PacketHandler;
import dev.stannismod.stellurgy.libvulpes.network.PacketMachine;
import dev.stannismod.stellurgy.libvulpes.tile.multiblock.TileMultiblockMachine;
import dev.stannismod.stellurgy.libvulpes.util.INetworkMachine;
import dev.stannismod.stellurgy.libvulpes.util.IconResource;

import java.util.LinkedList;
import java.util.List;

//This code is a complete mess. it should be rewritten just like the space laser, but it kinda works, so I'll leave it with this for now

public class TileAtmosphereTerraformer extends TileWasteHeatPowerConsumer implements INetworkMachine {

    /**
     * Each gas's share of one thickening step: the step is one hundredth of an atmosphere, the
     * density readout's own unit, split evenly because the machine drains nitrogen and oxygen at the
     * one rate ({@code terraformliquidRate} for both).
     */
    private static final long STEP_PER_GAS = AirState.ONE_ATM / 200L;

    private static final Object[][][] structure = new Object[][][]{
            {{null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null},
                    {null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null},
                    {null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null},
                    {null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null},
                    {null, null, null, null, null, null, null, null, StellurgyBlocks.blockOxygenVent, null, null, null, null, null, null, null, null},
                    {null, null, null, null, null, StellurgyBlocks.blockOxygenVent, LibVulpesBlocks.blockAdvStructureBlock, LibVulpesBlocks.blockAdvStructureBlock, LibVulpesBlocks.blockAdvStructureBlock, LibVulpesBlocks.blockAdvStructureBlock, LibVulpesBlocks.blockAdvStructureBlock, StellurgyBlocks.blockOxygenVent, null, null, null, null, null},
                    {null, null, null, null, null, LibVulpesBlocks.blockAdvStructureBlock, LibVulpesBlocks.blockAdvStructureBlock, LibVulpesBlocks.blockAdvStructureBlock, LibVulpesBlocks.blockAdvStructureBlock, LibVulpesBlocks.blockAdvStructureBlock, LibVulpesBlocks.blockAdvStructureBlock, LibVulpesBlocks.blockAdvStructureBlock, null, null, null, null, null},
                    {null, null, null, null, null, LibVulpesBlocks.blockAdvStructureBlock, LibVulpesBlocks.blockAdvStructureBlock, LibVulpesBlocks.blockAdvStructureBlock, LibVulpesBlocks.blockAdvStructureBlock, LibVulpesBlocks.blockAdvStructureBlock, LibVulpesBlocks.blockAdvStructureBlock, LibVulpesBlocks.blockAdvStructureBlock, null, null, null, null, null},
                    {null, null, null, null, StellurgyBlocks.blockOxygenVent, LibVulpesBlocks.blockAdvStructureBlock, LibVulpesBlocks.blockAdvStructureBlock, LibVulpesBlocks.blockAdvStructureBlock, LibVulpesBlocks.blockAdvStructureBlock, LibVulpesBlocks.blockAdvStructureBlock, LibVulpesBlocks.blockAdvStructureBlock, LibVulpesBlocks.blockAdvStructureBlock, StellurgyBlocks.blockOxygenVent, null, null, null, null},
                    {null, null, null, null, null, LibVulpesBlocks.blockAdvStructureBlock, LibVulpesBlocks.blockAdvStructureBlock, LibVulpesBlocks.blockAdvStructureBlock, LibVulpesBlocks.blockAdvStructureBlock, LibVulpesBlocks.blockAdvStructureBlock, LibVulpesBlocks.blockAdvStructureBlock, LibVulpesBlocks.blockAdvStructureBlock, null, null, null, null, null},
                    {null, null, null, null, null, LibVulpesBlocks.blockAdvStructureBlock, LibVulpesBlocks.blockAdvStructureBlock, LibVulpesBlocks.blockAdvStructureBlock, LibVulpesBlocks.blockAdvStructureBlock, LibVulpesBlocks.blockAdvStructureBlock, LibVulpesBlocks.blockAdvStructureBlock, LibVulpesBlocks.blockAdvStructureBlock, null, null, null, null, null},
                    {null, null, null, null, null, StellurgyBlocks.blockOxygenVent, LibVulpesBlocks.blockAdvStructureBlock, LibVulpesBlocks.blockAdvStructureBlock, LibVulpesBlocks.blockAdvStructureBlock, LibVulpesBlocks.blockAdvStructureBlock, LibVulpesBlocks.blockAdvStructureBlock, StellurgyBlocks.blockOxygenVent, null, null, null, null, null},
                    {null, null, null, null, null, null, null, null, StellurgyBlocks.blockOxygenVent, null, null, null, null, null, null, null, null},
                    {null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null},
                    {null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null},
                    {null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null},
                    {null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null}},

            {{null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null},
                    {null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null},
                    {null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null},
                    {null, null, null, null, null, null, null, null, LibVulpesBlocks.blockAdvStructureBlock, null, null, null, null, null, null, null, null},
                    {null, null, null, null, null, null, LibVulpesBlocks.blockAdvStructureBlock, LibVulpesBlocks.blockAdvStructureBlock, LibVulpesBlocks.blockAdvStructureBlock, LibVulpesBlocks.blockAdvStructureBlock, LibVulpesBlocks.blockAdvStructureBlock, null, null, null, null, null, null},
                    {null, null, null, null, null, LibVulpesBlocks.blockAdvStructureBlock, LibVulpesBlocks.blockAdvStructureBlock, LibVulpesBlocks.blockAdvStructureBlock, LibVulpesBlocks.blockAdvStructureBlock, LibVulpesBlocks.blockAdvStructureBlock, LibVulpesBlocks.blockAdvStructureBlock, LibVulpesBlocks.blockAdvStructureBlock, null, null, null, null, null},
                    {null, null, null, null, LibVulpesBlocks.blockAdvStructureBlock, LibVulpesBlocks.blockAdvStructureBlock, LibVulpesBlocks.blockAdvStructureBlock, LibVulpesBlocks.blockAdvStructureBlock, LibVulpesBlocks.blockAdvStructureBlock, LibVulpesBlocks.blockAdvStructureBlock, LibVulpesBlocks.blockAdvStructureBlock, LibVulpesBlocks.blockAdvStructureBlock, LibVulpesBlocks.blockAdvStructureBlock, null, null, null, null},
                    {null, null, null, null, LibVulpesBlocks.blockAdvStructureBlock, LibVulpesBlocks.blockAdvStructureBlock, LibVulpesBlocks.blockAdvStructureBlock, LibVulpesBlocks.blockAdvStructureBlock, LibVulpesBlocks.blockAdvStructureBlock, LibVulpesBlocks.blockAdvStructureBlock, LibVulpesBlocks.blockAdvStructureBlock, LibVulpesBlocks.blockAdvStructureBlock, LibVulpesBlocks.blockAdvStructureBlock, null, null, null, null},
                    {null, null, null, LibVulpesBlocks.blockAdvStructureBlock, LibVulpesBlocks.blockAdvStructureBlock, LibVulpesBlocks.blockAdvStructureBlock, LibVulpesBlocks.blockAdvStructureBlock, LibVulpesBlocks.blockAdvStructureBlock, LibVulpesBlocks.blockAdvStructureBlock, LibVulpesBlocks.blockAdvStructureBlock, LibVulpesBlocks.blockAdvStructureBlock, LibVulpesBlocks.blockAdvStructureBlock, LibVulpesBlocks.blockAdvStructureBlock, LibVulpesBlocks.blockAdvStructureBlock, null, null, null},
                    {null, null, null, null, LibVulpesBlocks.blockAdvStructureBlock, LibVulpesBlocks.blockAdvStructureBlock, LibVulpesBlocks.blockAdvStructureBlock, LibVulpesBlocks.blockAdvStructureBlock, LibVulpesBlocks.blockAdvStructureBlock, LibVulpesBlocks.blockAdvStructureBlock, LibVulpesBlocks.blockAdvStructureBlock, LibVulpesBlocks.blockAdvStructureBlock, LibVulpesBlocks.blockAdvStructureBlock, null, null, null, null},
                    {null, null, null, null, LibVulpesBlocks.blockAdvStructureBlock, LibVulpesBlocks.blockAdvStructureBlock, LibVulpesBlocks.blockAdvStructureBlock, LibVulpesBlocks.blockAdvStructureBlock, LibVulpesBlocks.blockAdvStructureBlock, LibVulpesBlocks.blockAdvStructureBlock, LibVulpesBlocks.blockAdvStructureBlock, LibVulpesBlocks.blockAdvStructureBlock, LibVulpesBlocks.blockAdvStructureBlock, null, null, null, null},
                    {null, null, null, null, null, LibVulpesBlocks.blockAdvStructureBlock, LibVulpesBlocks.blockAdvStructureBlock, LibVulpesBlocks.blockAdvStructureBlock, LibVulpesBlocks.blockAdvStructureBlock, LibVulpesBlocks.blockAdvStructureBlock, LibVulpesBlocks.blockAdvStructureBlock, LibVulpesBlocks.blockAdvStructureBlock, null, null, null, null, null},
                    {null, null, null, null, null, null, LibVulpesBlocks.blockAdvStructureBlock, LibVulpesBlocks.blockAdvStructureBlock, LibVulpesBlocks.blockAdvStructureBlock, LibVulpesBlocks.blockAdvStructureBlock, LibVulpesBlocks.blockAdvStructureBlock, null, null, null, null, null, null},
                    {null, null, null, null, null, null, null, null, LibVulpesBlocks.blockAdvStructureBlock, null, null, null, null, null, null, null, null},
                    {null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null},
                    {null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null},
                    {null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null}},

            {{null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null},
                    {null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null},
                    {null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null},
                    {null, null, null, null, null, null, LibVulpesBlocks.blockAdvStructureBlock, LibVulpesBlocks.blockAdvStructureBlock, LibVulpesBlocks.blockAdvStructureBlock, LibVulpesBlocks.blockAdvStructureBlock, LibVulpesBlocks.blockAdvStructureBlock, null, null, null, null, null, null},
                    {null, null, null, null, null, LibVulpesBlocks.blockAdvStructureBlock, LibVulpesBlocks.blockAdvStructureBlock, LibVulpesBlocks.blockAdvStructureBlock, LibVulpesBlocks.blockAdvStructureBlock, LibVulpesBlocks.blockAdvStructureBlock, LibVulpesBlocks.blockAdvStructureBlock, LibVulpesBlocks.blockAdvStructureBlock, null, null, null, null, null},
                    {null, null, null, null, LibVulpesBlocks.blockAdvStructureBlock, LibVulpesBlocks.blockAdvStructureBlock, LibVulpesBlocks.blockAdvStructureBlock, LibVulpesBlocks.blockAdvStructureBlock, LibVulpesBlocks.blockAdvStructureBlock, LibVulpesBlocks.blockAdvStructureBlock, LibVulpesBlocks.blockAdvStructureBlock, LibVulpesBlocks.blockAdvStructureBlock, LibVulpesBlocks.blockAdvStructureBlock, null, null, null, null},
                    {null, null, null, LibVulpesBlocks.blockAdvStructureBlock, LibVulpesBlocks.blockAdvStructureBlock, LibVulpesBlocks.blockAdvStructureBlock, LibVulpesBlocks.blockAdvStructureBlock, LibVulpesBlocks.blockAdvStructureBlock, LibVulpesBlocks.blockAdvStructureBlock, LibVulpesBlocks.blockAdvStructureBlock, LibVulpesBlocks.blockAdvStructureBlock, LibVulpesBlocks.blockAdvStructureBlock, LibVulpesBlocks.blockAdvStructureBlock, LibVulpesBlocks.blockAdvStructureBlock, null, null, null},
                    {null, null, null, LibVulpesBlocks.blockAdvStructureBlock, LibVulpesBlocks.blockAdvStructureBlock, LibVulpesBlocks.blockAdvStructureBlock, LibVulpesBlocks.blockAdvStructureBlock, LibVulpesBlocks.blockAdvStructureBlock, LibVulpesBlocks.blockAdvStructureBlock, LibVulpesBlocks.blockAdvStructureBlock, LibVulpesBlocks.blockAdvStructureBlock, LibVulpesBlocks.blockAdvStructureBlock, LibVulpesBlocks.blockAdvStructureBlock, LibVulpesBlocks.blockAdvStructureBlock, null, null, null},
                    {null, null, null, LibVulpesBlocks.blockAdvStructureBlock, LibVulpesBlocks.blockAdvStructureBlock, LibVulpesBlocks.blockAdvStructureBlock, LibVulpesBlocks.blockAdvStructureBlock, LibVulpesBlocks.blockAdvStructureBlock, LibVulpesBlocks.blockAdvStructureBlock, LibVulpesBlocks.blockAdvStructureBlock, LibVulpesBlocks.blockAdvStructureBlock, LibVulpesBlocks.blockAdvStructureBlock, LibVulpesBlocks.blockAdvStructureBlock, LibVulpesBlocks.blockAdvStructureBlock, null, null, null},
                    {null, null, null, LibVulpesBlocks.blockAdvStructureBlock, LibVulpesBlocks.blockAdvStructureBlock, LibVulpesBlocks.blockAdvStructureBlock, LibVulpesBlocks.blockAdvStructureBlock, LibVulpesBlocks.blockAdvStructureBlock, LibVulpesBlocks.blockAdvStructureBlock, LibVulpesBlocks.blockAdvStructureBlock, LibVulpesBlocks.blockAdvStructureBlock, LibVulpesBlocks.blockAdvStructureBlock, LibVulpesBlocks.blockAdvStructureBlock, LibVulpesBlocks.blockAdvStructureBlock, null, null, null},
                    {null, null, null, LibVulpesBlocks.blockAdvStructureBlock, LibVulpesBlocks.blockAdvStructureBlock, LibVulpesBlocks.blockAdvStructureBlock, LibVulpesBlocks.blockAdvStructureBlock, LibVulpesBlocks.blockAdvStructureBlock, LibVulpesBlocks.blockAdvStructureBlock, LibVulpesBlocks.blockAdvStructureBlock, LibVulpesBlocks.blockAdvStructureBlock, LibVulpesBlocks.blockAdvStructureBlock, LibVulpesBlocks.blockAdvStructureBlock, LibVulpesBlocks.blockAdvStructureBlock, null, null, null},
                    {null, null, null, null, LibVulpesBlocks.blockAdvStructureBlock, LibVulpesBlocks.blockAdvStructureBlock, LibVulpesBlocks.blockAdvStructureBlock, LibVulpesBlocks.blockAdvStructureBlock, LibVulpesBlocks.blockAdvStructureBlock, LibVulpesBlocks.blockAdvStructureBlock, LibVulpesBlocks.blockAdvStructureBlock, LibVulpesBlocks.blockAdvStructureBlock, LibVulpesBlocks.blockAdvStructureBlock, null, null, null, null},
                    {null, null, null, null, null, LibVulpesBlocks.blockAdvStructureBlock, LibVulpesBlocks.blockAdvStructureBlock, LibVulpesBlocks.blockAdvStructureBlock, LibVulpesBlocks.blockAdvStructureBlock, LibVulpesBlocks.blockAdvStructureBlock, LibVulpesBlocks.blockAdvStructureBlock, LibVulpesBlocks.blockAdvStructureBlock, null, null, null, null, null},
                    {null, null, null, null, null, null, LibVulpesBlocks.blockAdvStructureBlock, LibVulpesBlocks.blockAdvStructureBlock, LibVulpesBlocks.blockAdvStructureBlock, LibVulpesBlocks.blockAdvStructureBlock, LibVulpesBlocks.blockAdvStructureBlock, null, null, null, null, null, null},
                    {null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null},
                    {null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null},
                    {null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null}},

            {{null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null},
                    {null, null, null, null, null, null, null, Blocks.CLAY, null, Blocks.CLAY, null, null, null, null, null, null, null},
                    {null, null, null, null, null, null, null, Blocks.CLAY, null, Blocks.CLAY, null, null, null, null, null, null, null},
                    {null, null, null, null, null, null, LibVulpesBlocks.blockAdvStructureBlock, Blocks.CLAY, LibVulpesBlocks.blockAdvStructureBlock, Blocks.CLAY, LibVulpesBlocks.blockAdvStructureBlock, null, null, null, null, null, null},
                    {null, null, null, null, null, LibVulpesBlocks.blockAdvStructureBlock, LibVulpesBlocks.blockAdvStructureBlock, LibVulpesBlocks.blockAdvStructureBlock, LibVulpesBlocks.blockAdvStructureBlock, LibVulpesBlocks.blockAdvStructureBlock, LibVulpesBlocks.blockAdvStructureBlock, LibVulpesBlocks.blockAdvStructureBlock, null, null, null, null, null},
                    {null, null, null, null, LibVulpesBlocks.blockAdvStructureBlock, LibVulpesBlocks.blockAdvStructureBlock, LibVulpesBlocks.blockAdvStructureBlock, LibVulpesBlocks.blockAdvStructureBlock, LibVulpesBlocks.blockAdvStructureBlock, LibVulpesBlocks.blockAdvStructureBlock, LibVulpesBlocks.blockAdvStructureBlock, LibVulpesBlocks.blockAdvStructureBlock, LibVulpesBlocks.blockAdvStructureBlock, null, null, null, null},
                    {null, null, null, LibVulpesBlocks.blockAdvStructureBlock, LibVulpesBlocks.blockAdvStructureBlock, LibVulpesBlocks.blockAdvStructureBlock, LibVulpesBlocks.blockAdvStructureBlock, LibVulpesBlocks.blockAdvStructureBlock, LibVulpesBlocks.blockAdvStructureBlock, LibVulpesBlocks.blockAdvStructureBlock, LibVulpesBlocks.blockAdvStructureBlock, LibVulpesBlocks.blockAdvStructureBlock, LibVulpesBlocks.blockAdvStructureBlock, LibVulpesBlocks.blockAdvStructureBlock, null, null, null},
                    {null, Blocks.CLAY, Blocks.CLAY, Blocks.CLAY, LibVulpesBlocks.blockAdvStructureBlock, LibVulpesBlocks.blockAdvStructureBlock, LibVulpesBlocks.blockAdvStructureBlock, LibVulpesBlocks.blockAdvStructureBlock, LibVulpesBlocks.blockAdvStructureBlock, LibVulpesBlocks.blockAdvStructureBlock, LibVulpesBlocks.blockAdvStructureBlock, LibVulpesBlocks.blockAdvStructureBlock, LibVulpesBlocks.blockAdvStructureBlock, Blocks.CLAY, Blocks.CLAY, Blocks.CLAY, null},
                    {null, null, null, LibVulpesBlocks.blockAdvStructureBlock, LibVulpesBlocks.blockAdvStructureBlock, LibVulpesBlocks.blockAdvStructureBlock, LibVulpesBlocks.blockAdvStructureBlock, LibVulpesBlocks.blockAdvStructureBlock, LibVulpesBlocks.blockAdvStructureBlock, LibVulpesBlocks.blockAdvStructureBlock, LibVulpesBlocks.blockAdvStructureBlock, LibVulpesBlocks.blockAdvStructureBlock, LibVulpesBlocks.blockAdvStructureBlock, LibVulpesBlocks.blockAdvStructureBlock, null, null, null},
                    {null, Blocks.CLAY, Blocks.CLAY, Blocks.CLAY, LibVulpesBlocks.blockAdvStructureBlock, LibVulpesBlocks.blockAdvStructureBlock, LibVulpesBlocks.blockAdvStructureBlock, LibVulpesBlocks.blockAdvStructureBlock, LibVulpesBlocks.blockAdvStructureBlock, LibVulpesBlocks.blockAdvStructureBlock, LibVulpesBlocks.blockAdvStructureBlock, LibVulpesBlocks.blockAdvStructureBlock, LibVulpesBlocks.blockAdvStructureBlock, Blocks.CLAY, Blocks.CLAY, Blocks.CLAY, null},
                    {null, null, null, LibVulpesBlocks.blockAdvStructureBlock, LibVulpesBlocks.blockAdvStructureBlock, LibVulpesBlocks.blockAdvStructureBlock, LibVulpesBlocks.blockAdvStructureBlock, LibVulpesBlocks.blockAdvStructureBlock, LibVulpesBlocks.blockAdvStructureBlock, LibVulpesBlocks.blockAdvStructureBlock, LibVulpesBlocks.blockAdvStructureBlock, LibVulpesBlocks.blockAdvStructureBlock, LibVulpesBlocks.blockAdvStructureBlock, LibVulpesBlocks.blockAdvStructureBlock, null, null, null},
                    {null, null, null, null, LibVulpesBlocks.blockAdvStructureBlock, LibVulpesBlocks.blockAdvStructureBlock, LibVulpesBlocks.blockAdvStructureBlock, LibVulpesBlocks.blockAdvStructureBlock, LibVulpesBlocks.blockAdvStructureBlock, LibVulpesBlocks.blockAdvStructureBlock, LibVulpesBlocks.blockAdvStructureBlock, LibVulpesBlocks.blockAdvStructureBlock, LibVulpesBlocks.blockAdvStructureBlock, null, null, null, null},
                    {null, null, null, null, null, LibVulpesBlocks.blockAdvStructureBlock, LibVulpesBlocks.blockAdvStructureBlock, LibVulpesBlocks.blockAdvStructureBlock, LibVulpesBlocks.blockAdvStructureBlock, LibVulpesBlocks.blockAdvStructureBlock, LibVulpesBlocks.blockAdvStructureBlock, LibVulpesBlocks.blockAdvStructureBlock, null, null, null, null, null},
                    {null, null, null, null, null, null, LibVulpesBlocks.blockAdvStructureBlock, Blocks.CLAY, LibVulpesBlocks.blockAdvStructureBlock, Blocks.CLAY, LibVulpesBlocks.blockAdvStructureBlock, null, null, null, null, null, null},
                    {null, null, null, null, null, null, null, Blocks.CLAY, null, Blocks.CLAY, null, null, null, null, null, null, null},
                    {null, null, null, null, null, null, null, Blocks.CLAY, null, Blocks.CLAY, null, null, null, null, null, null, null},
                    {null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null}},

            {{null, null, null, null, null, null, null, Blocks.CLAY, null, Blocks.CLAY, null, null, null, null, null, null, null},
                    {null, null, null, null, null, null, null, Blocks.CLAY, null, Blocks.CLAY, null, null, null, null, null, null, null},
                    {null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null},
                    {null, null, null, null, null, null, LibVulpesBlocks.blockAdvStructureBlock, LibVulpesBlocks.blockAdvStructureBlock, LibVulpesBlocks.blockAdvStructureBlock, LibVulpesBlocks.blockAdvStructureBlock, LibVulpesBlocks.blockAdvStructureBlock, null, null, null, null, null, null},
                    {null, null, null, null, null, LibVulpesBlocks.blockAdvStructureBlock, LibVulpesBlocks.blockAdvStructureBlock, LibVulpesBlocks.blockAdvStructureBlock, LibVulpesBlocks.blockAdvStructureBlock, LibVulpesBlocks.blockAdvStructureBlock, LibVulpesBlocks.blockAdvStructureBlock, LibVulpesBlocks.blockAdvStructureBlock, null, null, null, null, null},
                    {null, null, null, null, LibVulpesBlocks.blockAdvStructureBlock, LibVulpesBlocks.blockAdvStructureBlock, LibVulpesBlocks.blockAdvStructureBlock, LibVulpesBlocks.blockAdvStructureBlock, LibVulpesBlocks.blockAdvStructureBlock, LibVulpesBlocks.blockAdvStructureBlock, LibVulpesBlocks.blockAdvStructureBlock, LibVulpesBlocks.blockAdvStructureBlock, LibVulpesBlocks.blockAdvStructureBlock, null, null, null, null},
                    {null, null, null, LibVulpesBlocks.blockAdvStructureBlock, LibVulpesBlocks.blockAdvStructureBlock, LibVulpesBlocks.blockAdvStructureBlock, LibVulpesBlocks.blockAdvStructureBlock, LibVulpesBlocks.blockAdvStructureBlock, LibVulpesBlocks.blockAdvStructureBlock, LibVulpesBlocks.blockAdvStructureBlock, LibVulpesBlocks.blockAdvStructureBlock, LibVulpesBlocks.blockAdvStructureBlock, LibVulpesBlocks.blockAdvStructureBlock, LibVulpesBlocks.blockAdvStructureBlock, null, null, null},
                    {Blocks.CLAY, Blocks.CLAY, null, LibVulpesBlocks.blockAdvStructureBlock, LibVulpesBlocks.blockAdvStructureBlock, LibVulpesBlocks.blockAdvStructureBlock, LibVulpesBlocks.blockAdvStructureBlock, LibVulpesBlocks.blockAdvStructureBlock, LibVulpesBlocks.blockAdvStructureBlock, LibVulpesBlocks.blockAdvStructureBlock, LibVulpesBlocks.blockAdvStructureBlock, LibVulpesBlocks.blockAdvStructureBlock, LibVulpesBlocks.blockAdvStructureBlock, LibVulpesBlocks.blockAdvStructureBlock, null, Blocks.CLAY, Blocks.CLAY},
                    {null, null, null, LibVulpesBlocks.blockAdvStructureBlock, LibVulpesBlocks.blockAdvStructureBlock, LibVulpesBlocks.blockAdvStructureBlock, LibVulpesBlocks.blockAdvStructureBlock, LibVulpesBlocks.blockAdvStructureBlock, LibVulpesBlocks.blockAdvStructureBlock, LibVulpesBlocks.blockAdvStructureBlock, LibVulpesBlocks.blockAdvStructureBlock, LibVulpesBlocks.blockAdvStructureBlock, LibVulpesBlocks.blockAdvStructureBlock, LibVulpesBlocks.blockAdvStructureBlock, null, null, null},
                    {Blocks.CLAY, Blocks.CLAY, null, LibVulpesBlocks.blockAdvStructureBlock, LibVulpesBlocks.blockAdvStructureBlock, LibVulpesBlocks.blockAdvStructureBlock, LibVulpesBlocks.blockAdvStructureBlock, LibVulpesBlocks.blockAdvStructureBlock, LibVulpesBlocks.blockAdvStructureBlock, LibVulpesBlocks.blockAdvStructureBlock, LibVulpesBlocks.blockAdvStructureBlock, LibVulpesBlocks.blockAdvStructureBlock, LibVulpesBlocks.blockAdvStructureBlock, LibVulpesBlocks.blockAdvStructureBlock, null, Blocks.CLAY, Blocks.CLAY},
                    {null, null, null, LibVulpesBlocks.blockAdvStructureBlock, LibVulpesBlocks.blockAdvStructureBlock, LibVulpesBlocks.blockAdvStructureBlock, LibVulpesBlocks.blockAdvStructureBlock, LibVulpesBlocks.blockAdvStructureBlock, LibVulpesBlocks.blockAdvStructureBlock, LibVulpesBlocks.blockAdvStructureBlock, LibVulpesBlocks.blockAdvStructureBlock, LibVulpesBlocks.blockAdvStructureBlock, LibVulpesBlocks.blockAdvStructureBlock, LibVulpesBlocks.blockAdvStructureBlock, null, null, null},
                    {null, null, null, null, LibVulpesBlocks.blockAdvStructureBlock, LibVulpesBlocks.blockAdvStructureBlock, LibVulpesBlocks.blockAdvStructureBlock, LibVulpesBlocks.blockAdvStructureBlock, LibVulpesBlocks.blockAdvStructureBlock, LibVulpesBlocks.blockAdvStructureBlock, LibVulpesBlocks.blockAdvStructureBlock, LibVulpesBlocks.blockAdvStructureBlock, LibVulpesBlocks.blockAdvStructureBlock, null, null, null, null},
                    {null, null, null, null, null, LibVulpesBlocks.blockAdvStructureBlock, LibVulpesBlocks.blockAdvStructureBlock, LibVulpesBlocks.blockAdvStructureBlock, LibVulpesBlocks.blockAdvStructureBlock, LibVulpesBlocks.blockAdvStructureBlock, LibVulpesBlocks.blockAdvStructureBlock, LibVulpesBlocks.blockAdvStructureBlock, null, null, null, null, null},
                    {null, null, null, null, null, null, LibVulpesBlocks.blockAdvStructureBlock, LibVulpesBlocks.blockAdvStructureBlock, LibVulpesBlocks.blockAdvStructureBlock, LibVulpesBlocks.blockAdvStructureBlock, LibVulpesBlocks.blockAdvStructureBlock, null, null, null, null, null, null},
                    {null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null},
                    {null, null, null, null, null, null, null, Blocks.CLAY, null, Blocks.CLAY, null, null, null, null, null, null, null},
                    {null, null, null, null, null, null, null, Blocks.CLAY, null, Blocks.CLAY, null, null, null, null, null, null, null}},

            {{null, null, null, null, null, null, null, Blocks.CLAY, null, Blocks.CLAY, null, null, null, null, null, null, null},
                    {null, null, null, null, null, null, null, Blocks.CLAY, null, Blocks.CLAY, null, null, null, null, null, null, null},
                    {null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null},
                    {null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null},
                    {null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null},
                    {null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null},
                    {null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null},
                    {Blocks.CLAY, Blocks.CLAY, null, null, null, null, null, LibVulpesBlocks.blockAdvStructureBlock, LibVulpesBlocks.blockAdvStructureBlock, LibVulpesBlocks.blockAdvStructureBlock, null, null, null, null, null, Blocks.CLAY, Blocks.CLAY},
                    {null, null, null, null, null, null, null, LibVulpesBlocks.blockAdvStructureBlock, LibVulpesBlocks.blockAdvStructureBlock, LibVulpesBlocks.blockAdvStructureBlock, null, null, null, null, null, null, null},
                    {Blocks.CLAY, Blocks.CLAY, null, null, null, null, null, LibVulpesBlocks.blockAdvStructureBlock, LibVulpesBlocks.blockAdvStructureBlock, LibVulpesBlocks.blockAdvStructureBlock, null, null, null, null, null, Blocks.CLAY, Blocks.CLAY},
                    {null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null},
                    {null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null},
                    {null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null},
                    {null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null},
                    {null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null},
                    {null, null, null, null, null, null, null, Blocks.CLAY, null, Blocks.CLAY, null, null, null, null, null, null, null},
                    {null, null, null, null, null, null, null, Blocks.CLAY, null, Blocks.CLAY, null, null, null, null, null, null, null}},

            {{null, null, null, null, null, null, null, Blocks.CLAY, null, Blocks.CLAY, null, null, null, null, null, null, null},
                    {null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null},
                    {null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null},
                    {null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null},
                    {null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null},
                    {null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null},
                    {null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null},
                    {Blocks.CLAY, null, null, null, null, null, null, LibVulpesBlocks.blockAdvStructureBlock, LibVulpesBlocks.blockAdvStructureBlock, LibVulpesBlocks.blockAdvStructureBlock, null, null, null, null, null, null, Blocks.CLAY},
                    {null, null, null, null, null, null, null, LibVulpesBlocks.blockAdvStructureBlock, LibVulpesBlocks.blockAdvStructureBlock, LibVulpesBlocks.blockAdvStructureBlock, null, null, null, null, null, null, null},
                    {Blocks.CLAY, null, null, null, null, null, null, LibVulpesBlocks.blockAdvStructureBlock, LibVulpesBlocks.blockAdvStructureBlock, LibVulpesBlocks.blockAdvStructureBlock, null, null, null, null, null, null, Blocks.CLAY},
                    {null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null},
                    {null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null},
                    {null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null},
                    {null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null},
                    {null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null},
                    {null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null},
                    {null, null, null, null, null, null, null, Blocks.CLAY, null, Blocks.CLAY, null, null, null, null, null, null, null}},

            {{null, null, null, null, null, null, null, Blocks.CLAY, null, Blocks.CLAY, null, null, null, null, null, null, null},
                    {null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null},
                    {null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null},
                    {null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null},
                    {null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null},
                    {null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null},
                    {null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null},
                    {Blocks.CLAY, null, null, null, null, null, null, LibVulpesBlocks.blockAdvStructureBlock, 'c', LibVulpesBlocks.blockAdvStructureBlock, null, null, null, null, null, null, Blocks.CLAY},
                    {null, null, null, null, null, null, null, LibVulpesBlocks.blockAdvStructureBlock, LibVulpesBlocks.blockAdvStructureBlock, LibVulpesBlocks.blockAdvStructureBlock, null, null, null, null, null, null, null},
                    {Blocks.CLAY, null, null, null, null, null, null, LibVulpesBlocks.blockAdvStructureBlock, LibVulpesBlocks.blockAdvStructureBlock, LibVulpesBlocks.blockAdvStructureBlock, null, null, null, null, null, null, Blocks.CLAY},
                    {null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null},
                    {null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null},
                    {null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null},
                    {null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null},
                    {null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null},
                    {null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null},
                    {null, null, null, null, null, null, null, Blocks.CLAY, null, Blocks.CLAY, null, null, null, null, null, null, null}},

            {{null, null, null, null, null, null, null, Blocks.CLAY, null, Blocks.CLAY, null, null, null, null, null, null, null},
                    {null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null},
                    {null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null},
                    {null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null},
                    {null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null},
                    {null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null},
                    {null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null},
                    {Blocks.CLAY, null, null, null, null, null, null, LibVulpesBlocks.blockAdvStructureBlock, LibVulpesBlocks.blockAdvStructureBlock, LibVulpesBlocks.blockAdvStructureBlock, null, null, null, null, null, null, Blocks.CLAY},
                    {null, null, null, null, null, null, null, 'P', LibVulpesBlocks.blockAdvStructureBlock, 'P', null, null, null, null, null, null, null},
                    {Blocks.CLAY, null, null, null, null, null, null, LibVulpesBlocks.blockAdvStructureBlock, 'P', LibVulpesBlocks.blockAdvStructureBlock, null, null, null, null, null, null, Blocks.CLAY},
                    {null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null},
                    {null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null},
                    {null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null},
                    {null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null},
                    {null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null},
                    {null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null},
                    {null, null, null, null, null, null, null, Blocks.CLAY, null, Blocks.CLAY, null, null, null, null, null, null, null}},

            {{null, null, null, null, null, null, null, Blocks.CLAY, null, Blocks.CLAY, null, null, null, null, null, null, null},
                    {null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null},
                    {null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null},
                    {null, null, null, null, null, null, StellurgyBlocks.blockConcrete, StellurgyBlocks.blockConcrete, StellurgyBlocks.blockConcrete, StellurgyBlocks.blockConcrete, StellurgyBlocks.blockConcrete, null, null, null, null, null, null},
                    {null, null, null, null, null, StellurgyBlocks.blockConcrete, StellurgyBlocks.blockConcrete, StellurgyBlocks.blockFuelTank, StellurgyBlocks.blockFuelTank, StellurgyBlocks.blockFuelTank, StellurgyBlocks.blockConcrete, StellurgyBlocks.blockConcrete, null, null, null, null, null},
                    {null, null, null, null, StellurgyBlocks.blockConcrete, StellurgyBlocks.blockConcrete, StellurgyBlocks.blockConcrete, StellurgyBlocks.blockFuelTank, StellurgyBlocks.blockFuelTank, StellurgyBlocks.blockFuelTank, StellurgyBlocks.blockConcrete, StellurgyBlocks.blockConcrete, StellurgyBlocks.blockConcrete, null, null, null, null},
                    {null, null, null, StellurgyBlocks.blockConcrete, StellurgyBlocks.blockConcrete, StellurgyBlocks.blockConcrete, StellurgyBlocks.blockConcrete, StellurgyBlocks.blockConcrete, StellurgyBlocks.blockConcrete, StellurgyBlocks.blockConcrete, StellurgyBlocks.blockConcrete, StellurgyBlocks.blockConcrete, StellurgyBlocks.blockConcrete, StellurgyBlocks.blockConcrete, null, null, null},
                    {Blocks.CLAY, null, null, StellurgyBlocks.blockConcrete, StellurgyBlocks.blockFuelTank, StellurgyBlocks.blockFuelTank, StellurgyBlocks.blockConcrete, StellurgyBlocks.blockConcrete, StellurgyBlocks.blockConcrete, StellurgyBlocks.blockConcrete, StellurgyBlocks.blockConcrete, StellurgyBlocks.blockFuelTank, StellurgyBlocks.blockFuelTank, StellurgyBlocks.blockConcrete, null, null, Blocks.CLAY},
                    {null, null, null, StellurgyBlocks.blockConcrete, StellurgyBlocks.blockFuelTank, StellurgyBlocks.blockFuelTank, StellurgyBlocks.blockConcrete, StellurgyBlocks.blockConcrete, StellurgyBlocks.blockConcrete, StellurgyBlocks.blockConcrete, StellurgyBlocks.blockConcrete, StellurgyBlocks.blockFuelTank, StellurgyBlocks.blockFuelTank, StellurgyBlocks.blockConcrete, null, null, null},
                    {Blocks.CLAY, null, null, StellurgyBlocks.blockConcrete, StellurgyBlocks.blockFuelTank, StellurgyBlocks.blockFuelTank, StellurgyBlocks.blockConcrete, StellurgyBlocks.blockConcrete, StellurgyBlocks.blockConcrete, StellurgyBlocks.blockConcrete, StellurgyBlocks.blockConcrete, StellurgyBlocks.blockFuelTank, StellurgyBlocks.blockFuelTank, StellurgyBlocks.blockConcrete, null, null, Blocks.CLAY},
                    {null, null, null, StellurgyBlocks.blockConcrete, StellurgyBlocks.blockConcrete, StellurgyBlocks.blockConcrete, StellurgyBlocks.blockConcrete, StellurgyBlocks.blockConcrete, StellurgyBlocks.blockConcrete, StellurgyBlocks.blockConcrete, StellurgyBlocks.blockConcrete, StellurgyBlocks.blockConcrete, StellurgyBlocks.blockConcrete, StellurgyBlocks.blockConcrete, null, null, null},
                    {null, null, null, null, StellurgyBlocks.blockConcrete, StellurgyBlocks.blockConcrete, StellurgyBlocks.blockConcrete, StellurgyBlocks.blockFuelTank, StellurgyBlocks.blockFuelTank, StellurgyBlocks.blockFuelTank, StellurgyBlocks.blockConcrete, StellurgyBlocks.blockConcrete, StellurgyBlocks.blockConcrete, null, null, null, null},
                    {null, null, null, null, null, StellurgyBlocks.blockConcrete, StellurgyBlocks.blockConcrete, StellurgyBlocks.blockFuelTank, StellurgyBlocks.blockFuelTank, StellurgyBlocks.blockFuelTank, StellurgyBlocks.blockConcrete, StellurgyBlocks.blockConcrete, null, null, null, null, null},
                    {null, null, null, null, null, null, StellurgyBlocks.blockConcrete, StellurgyBlocks.blockConcrete, StellurgyBlocks.blockConcrete, StellurgyBlocks.blockConcrete, StellurgyBlocks.blockConcrete, null, null, null, null, null, null},
                    {null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null},
                    {null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null},
                    {null, null, null, null, null, null, null, Blocks.CLAY, null, Blocks.CLAY, null, null, null, null, null, null, null}},

            {{null, null, null, null, null, null, null, Blocks.CLAY, null, Blocks.CLAY, null, null, null, null, null, null, null},
                    {null, null, null, null, null, null, null, Blocks.CLAY, null, Blocks.CLAY, null, null, null, null, null, null, null},
                    {null, null, null, null, null, null, null, Blocks.CLAY, null, Blocks.CLAY, null, null, null, null, null, null, null},
                    {null, null, null, null, null, null, StellurgyBlocks.blockConcrete, StellurgyBlocks.blockConcrete, StellurgyBlocks.blockConcrete, StellurgyBlocks.blockConcrete, StellurgyBlocks.blockConcrete, null, null, null, null, null, null},
                    {null, null, null, null, null, StellurgyBlocks.blockConcrete, StellurgyBlocks.blockConcrete, StellurgyBlocks.blockFuelTank, StellurgyBlocks.blockFuelTank, StellurgyBlocks.blockFuelTank, StellurgyBlocks.blockConcrete, StellurgyBlocks.blockConcrete, null, null, null, null, null},
                    {null, null, null, null, StellurgyBlocks.blockConcrete, StellurgyBlocks.blockConcrete, StellurgyBlocks.blockConcrete, StellurgyBlocks.blockFuelTank, StellurgyBlocks.blockFuelTank, StellurgyBlocks.blockFuelTank, StellurgyBlocks.blockConcrete, StellurgyBlocks.blockConcrete, StellurgyBlocks.blockConcrete, null, null, null, null},
                    {null, null, null, StellurgyBlocks.blockConcrete, StellurgyBlocks.blockConcrete, StellurgyBlocks.blockConcrete, StellurgyBlocks.blockConcrete, StellurgyBlocks.blockConcrete, StellurgyBlocks.blockConcrete, StellurgyBlocks.blockConcrete, StellurgyBlocks.blockConcrete, StellurgyBlocks.blockConcrete, StellurgyBlocks.blockConcrete, StellurgyBlocks.blockConcrete, null, null, null},
                    {Blocks.CLAY, Blocks.CLAY, Blocks.CLAY, StellurgyBlocks.blockConcrete, StellurgyBlocks.blockFuelTank, StellurgyBlocks.blockFuelTank, StellurgyBlocks.blockConcrete, StellurgyBlocks.blockConcrete, StellurgyBlocks.blockConcrete, StellurgyBlocks.blockConcrete, StellurgyBlocks.blockConcrete, StellurgyBlocks.blockFuelTank, StellurgyBlocks.blockFuelTank, StellurgyBlocks.blockConcrete, Blocks.CLAY, Blocks.CLAY, Blocks.CLAY},
                    {null, null, null, StellurgyBlocks.blockConcrete, StellurgyBlocks.blockFuelTank, StellurgyBlocks.blockFuelTank, StellurgyBlocks.blockConcrete, StellurgyBlocks.blockConcrete, StellurgyBlocks.blockConcrete, StellurgyBlocks.blockConcrete, StellurgyBlocks.blockConcrete, StellurgyBlocks.blockFuelTank, StellurgyBlocks.blockFuelTank, StellurgyBlocks.blockConcrete, null, null, null},
                    {Blocks.CLAY, Blocks.CLAY, Blocks.CLAY, StellurgyBlocks.blockConcrete, StellurgyBlocks.blockFuelTank, StellurgyBlocks.blockFuelTank, StellurgyBlocks.blockConcrete, StellurgyBlocks.blockConcrete, StellurgyBlocks.blockConcrete, StellurgyBlocks.blockConcrete, StellurgyBlocks.blockConcrete, StellurgyBlocks.blockFuelTank, StellurgyBlocks.blockFuelTank, StellurgyBlocks.blockConcrete, Blocks.CLAY, Blocks.CLAY, Blocks.CLAY},
                    {null, null, null, StellurgyBlocks.blockConcrete, StellurgyBlocks.blockConcrete, StellurgyBlocks.blockConcrete, StellurgyBlocks.blockConcrete, StellurgyBlocks.blockConcrete, StellurgyBlocks.blockConcrete, StellurgyBlocks.blockConcrete, StellurgyBlocks.blockConcrete, StellurgyBlocks.blockConcrete, StellurgyBlocks.blockConcrete, StellurgyBlocks.blockConcrete, null, null, null},
                    {null, null, null, null, StellurgyBlocks.blockConcrete, StellurgyBlocks.blockConcrete, StellurgyBlocks.blockConcrete, StellurgyBlocks.blockFuelTank, StellurgyBlocks.blockFuelTank, StellurgyBlocks.blockFuelTank, StellurgyBlocks.blockConcrete, StellurgyBlocks.blockConcrete, StellurgyBlocks.blockConcrete, null, null, null, null},
                    {null, null, null, null, null, StellurgyBlocks.blockConcrete, StellurgyBlocks.blockConcrete, StellurgyBlocks.blockFuelTank, StellurgyBlocks.blockFuelTank, StellurgyBlocks.blockFuelTank, StellurgyBlocks.blockConcrete, StellurgyBlocks.blockConcrete, null, null, null, null, null},
                    {null, null, null, null, null, null, StellurgyBlocks.blockConcrete, StellurgyBlocks.blockConcrete, StellurgyBlocks.blockConcrete, StellurgyBlocks.blockConcrete, StellurgyBlocks.blockConcrete, null, null, null, null, null, null},
                    {null, null, null, null, null, null, null, Blocks.CLAY, null, Blocks.CLAY, null, null, null, null, null, null, null},
                    {null, null, null, null, null, null, null, Blocks.CLAY, null, Blocks.CLAY, null, null, null, null, null, null, null},
                    {null, null, null, null, null, null, null, Blocks.CLAY, null, Blocks.CLAY, null, null, null, null, null, null, null}},

            {{null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null},
                    {null, null, null, null, null, null, null, Blocks.CLAY, null, Blocks.CLAY, null, null, null, null, null, null, null},
                    {null, null, null, null, null, null, null, Blocks.CLAY, null, Blocks.CLAY, null, null, null, null, null, null, null},
                    {null, null, null, null, null, null, StellurgyBlocks.blockConcrete, Blocks.CLAY, 'L', Blocks.CLAY, StellurgyBlocks.blockConcrete, null, null, null, null, null, null},
                    {null, null, null, null, null, StellurgyBlocks.blockConcrete, StellurgyBlocks.blockConcrete, StellurgyBlocks.blockFuelTank, StellurgyBlocks.blockFuelTank, StellurgyBlocks.blockFuelTank, StellurgyBlocks.blockConcrete, StellurgyBlocks.blockConcrete, null, null, null, null, null},
                    {null, null, null, null, StellurgyBlocks.blockConcrete, StellurgyBlocks.blockConcrete, StellurgyBlocks.blockConcrete, StellurgyBlocks.blockFuelTank, StellurgyBlocks.blockFuelTank, StellurgyBlocks.blockFuelTank, StellurgyBlocks.blockConcrete, StellurgyBlocks.blockConcrete, StellurgyBlocks.blockConcrete, null, null, null, null},
                    {null, null, null, StellurgyBlocks.blockConcrete, StellurgyBlocks.blockConcrete, StellurgyBlocks.blockConcrete, StellurgyBlocks.blockConcrete, StellurgyBlocks.blockConcrete, StellurgyBlocks.blockConcrete, StellurgyBlocks.blockConcrete, StellurgyBlocks.blockConcrete, StellurgyBlocks.blockConcrete, StellurgyBlocks.blockConcrete, StellurgyBlocks.blockConcrete, null, null, null},
                    {null, Blocks.CLAY, Blocks.CLAY, Blocks.CLAY, StellurgyBlocks.blockFuelTank, StellurgyBlocks.blockFuelTank, StellurgyBlocks.blockConcrete, StellurgyBlocks.blockConcrete, StellurgyBlocks.blockConcrete, StellurgyBlocks.blockConcrete, StellurgyBlocks.blockConcrete, StellurgyBlocks.blockFuelTank, StellurgyBlocks.blockFuelTank, Blocks.CLAY, Blocks.CLAY, Blocks.CLAY, null},
                    {null, null, null, 'L', StellurgyBlocks.blockFuelTank, StellurgyBlocks.blockFuelTank, StellurgyBlocks.blockConcrete, StellurgyBlocks.blockConcrete, StellurgyBlocks.blockConcrete, StellurgyBlocks.blockConcrete, StellurgyBlocks.blockConcrete, StellurgyBlocks.blockFuelTank, StellurgyBlocks.blockFuelTank, 'L', null, null, null},
                    {null, Blocks.CLAY, Blocks.CLAY, Blocks.CLAY, StellurgyBlocks.blockFuelTank, StellurgyBlocks.blockFuelTank, StellurgyBlocks.blockConcrete, StellurgyBlocks.blockConcrete, StellurgyBlocks.blockConcrete, StellurgyBlocks.blockConcrete, StellurgyBlocks.blockConcrete, StellurgyBlocks.blockFuelTank, StellurgyBlocks.blockFuelTank, Blocks.CLAY, Blocks.CLAY, Blocks.CLAY, null},
                    {null, null, null, StellurgyBlocks.blockConcrete, StellurgyBlocks.blockConcrete, StellurgyBlocks.blockConcrete, StellurgyBlocks.blockConcrete, StellurgyBlocks.blockConcrete, StellurgyBlocks.blockConcrete, StellurgyBlocks.blockConcrete, StellurgyBlocks.blockConcrete, StellurgyBlocks.blockConcrete, StellurgyBlocks.blockConcrete, StellurgyBlocks.blockConcrete, null, null, null},
                    {null, null, null, null, StellurgyBlocks.blockConcrete, StellurgyBlocks.blockConcrete, StellurgyBlocks.blockConcrete, StellurgyBlocks.blockFuelTank, StellurgyBlocks.blockFuelTank, StellurgyBlocks.blockFuelTank, StellurgyBlocks.blockConcrete, StellurgyBlocks.blockConcrete, StellurgyBlocks.blockConcrete, null, null, null, null},
                    {null, null, null, null, null, StellurgyBlocks.blockConcrete, StellurgyBlocks.blockConcrete, StellurgyBlocks.blockFuelTank, StellurgyBlocks.blockFuelTank, StellurgyBlocks.blockFuelTank, StellurgyBlocks.blockConcrete, StellurgyBlocks.blockConcrete, null, null, null, null, null},
                    {null, null, null, null, null, null, StellurgyBlocks.blockConcrete, Blocks.CLAY, 'L', Blocks.CLAY, StellurgyBlocks.blockConcrete, null, null, null, null, null, null},
                    {null, null, null, null, null, null, null, Blocks.CLAY, null, Blocks.CLAY, null, null, null, null, null, null, null},
                    {null, null, null, null, null, null, null, Blocks.CLAY, null, Blocks.CLAY, null, null, null, null, null, null, null},
                    {null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null}}};
    private ModuleToggleSwitch buttonIncrease, buttonDecrease;
    private ModuleRadioButton radioButton;
    private ModuleText text;

    private boolean outOfFluid;
    private int last_mode;
    int requiredN2 = 0, requiredO2 = 0;

    boolean client_contructed = false;

    public TileAtmosphereTerraformer() {
        super();

        completionTime = (int) (18000 * StellurgyConfiguration.getCurrentConfig().terraformSpeed);
        buttonIncrease = new ModuleToggleSwitch(40, 20, 1, LibVulpes.proxy.getLocalizedString("msg.terraformer.atminc"), this, TextureResources.buttonScan, 80, 16, true);
        buttonDecrease = new ModuleToggleSwitch(40, 38, 2, LibVulpes.proxy.getLocalizedString("msg.terraformer.atmdec"), this, TextureResources.buttonScan, 80, 16, false);
        text = new ModuleText(10, 100, "", 0x282828);
        powerPerTick = 1000;

        List<ModuleToggleSwitch> buttons = new LinkedList<>();
        buttons.add(buttonIncrease);
        buttons.add(buttonDecrease);
        radioButton = new ModuleRadioButton(this, buttons);

        outOfFluid = false;
        last_mode = radioButton.getOptionSelected();

    }

    @Override
    public void update() {
        super.update();
    }

    private int getCompletionTime() {
        return (int) (18000 * StellurgyConfiguration.getCurrentConfig().terraformSpeed);
    }

    @Override
    @SideOnly(Side.CLIENT)
    public double getMaxRenderDistanceSquared() {
        return 320 * 320;
    }

    @Override
    public List<ModuleBase> getModules(int ID, EntityPlayer player) {
        List<ModuleBase> modules =  super.getModules(ID, player);

        //Backgrounds
        if (world.isRemote) {
            modules.add(new ModuleImage(173, 0, new IconResource(90, 0, 84, 88, CommonResources.genericBackground)));
        }

        modules.add(radioButton);
        modules.add(new ModuleProgress(30, 57, 0, dev.stannismod.stellurgy.inventory.TextureResources.terraformProgressBar, this));
        modules.add(text);


        setText();

        int i = 0;
        modules.add(new ModuleText(180, 10, "Gas Status", 0x282828));
        for (IFluidHandler tile : fluidInPorts) {
            modules.add(new ModuleLiquidIndicator(180 + i * 16, 30, tile));
            i++;
        }

        return modules;
    }

    private void setText() {

        String statusText;


        if (outOfFluid)
            statusText = LibVulpes.proxy.getLocalizedString("msg.terraformer.outofgas");

        else if (isRunning())
            statusText = LibVulpes.proxy.getLocalizedString("msg.terraformer.running");
        else
            statusText = LibVulpes.proxy.getLocalizedString("msg.terraformer.notrunning");

        text.setText(String.format("%s:\n%s\n\n%s: %.2f", LibVulpes.proxy.getLocalizedString("msg.terraformer.status"), statusText, LibVulpes.proxy.getLocalizedString("msg.terraformer.pressure"), DimensionManager.getInstance().getDimensionProperties(world.provider.getDimension()).getAtmosphereDensity() / 100f));

    }

    @Override
    public Object[][][] getStructure() {
        return structure;
    }

    @Override
    public AxisAlignedBB getRenderBoundingBox() {
        return new AxisAlignedBB(pos.add(-15, -15, -15), pos.add(15, 15, 15));
    }

    @Override
    protected void onRunningPoweredTick() {
        //System.out.println("energy:"+this.batteries.getUniversalEnergyStored());

        if (world.isRemote && !outOfFluid) {
            if (Minecraft.getMinecraft().gameSettings.particleSetting < 2) {
                EnumFacing dir = RotatableBlock.getFront(world.getBlockState(pos)).getOpposite();

                for (int i = 0; i < 3; i++) {


                    if (radioButton.getOptionSelected() == 0) {
                        float xMot = (float) ((world.rand.nextGaussian()) / 40f);
                        float zMot = (float) ((world.rand.nextGaussian()) / 40f);
                        BlockPos offsetPos = pos.offset(dir);
                        Stellurgy.proxy.spawnParticle("rocketSmoke", world, offsetPos.getX() + 5, pos.getY() + 7, offsetPos.getZ() + 0.5, xMot, 0.02f, zMot);
                        Stellurgy.proxy.spawnParticle("rocketSmoke", world, offsetPos.getX() - 4, pos.getY() + 7, offsetPos.getZ() + 0.5, xMot, 0.02f, zMot);
                        Stellurgy.proxy.spawnParticle("rocketSmoke", world, offsetPos.getX() + 0.5f, pos.getY() + 7, offsetPos.getZ() - 4, xMot, 0.02f, zMot);
                        Stellurgy.proxy.spawnParticle("rocketSmoke", world, offsetPos.getX() + 0.5f, pos.getY() + 7, offsetPos.getZ() + 5, xMot, 0.02f, zMot);

                    } else {
                        float xMot = (float) ((world.rand.nextGaussian()) / 4f);
                        float yMot = (float) (world.rand.nextGaussian() / 20f);
                        float zMot = (float) ((world.rand.nextGaussian()) / 4f);
                        BlockPos offsetPos = pos.offset(dir);
                        Stellurgy.proxy.spawnParticle("rocketSmokeInverse", world, offsetPos.getX() + 5, pos.getY() + 7, offsetPos.getZ() + 0.5, xMot, 0.4f + yMot, zMot);
                        Stellurgy.proxy.spawnParticle("rocketSmokeInverse", world, offsetPos.getX() - 4, pos.getY() + 7, offsetPos.getZ() + 0.5, xMot, 0.4f + yMot, zMot);
                        Stellurgy.proxy.spawnParticle("rocketSmokeInverse", world, offsetPos.getX() + 0.5f, pos.getY() + 7, offsetPos.getZ() - 4, xMot, 0.4f + yMot, zMot);
                        Stellurgy.proxy.spawnParticle("rocketSmokeInverse", world, offsetPos.getX() + 0.5f, pos.getY() + 7, offsetPos.getZ() + 5, xMot, 0.4f + yMot, zMot);
                    }
                }
            }
        }
        if (!StellurgyConfiguration.getCurrentConfig().terraformRequiresFluid)
            return;

        if (!world.isRemote) {
            if (last_mode != radioButton.getOptionSelected()) {
                last_mode = radioButton.getOptionSelected();
                this.setProgress(0, 0);
            }
            if (radioButton.getOptionSelected() == 0) {

                if (requiredN2 == 0 && requiredO2 == 0) {
                    requiredN2 = StellurgyConfiguration.getCurrentConfig().terraformliquidRate;
                    requiredO2 = StellurgyConfiguration.getCurrentConfig().terraformliquidRate;
                }

                for (IFluidHandler handler : fluidInPorts) {
                    FluidStack fStack = handler.drain(new FluidStack(StellurgyFluids.fluidNitrogen, requiredN2), true);

                    if (fStack != null)
                        requiredN2 -= fStack.amount;

                    fStack = handler.drain(new FluidStack(StellurgyFluids.fluidOxygen, requiredO2), true);

                    if (fStack != null)
                        requiredO2 -= fStack.amount;
                }


                if (requiredN2 != 0 || requiredO2 != 0) {
                    setOOF(true);
                } else {
                    setOOF(false);
                }
            } else {
                setOOF(false);
            }
        }

        if (!outOfFluid) {
            /////////from the super method
            if (!world.isRemote)
                useEnergy(powerPerTick);
            //Increment for both client and server
            currentTime++;

            if (currentTime == completionTime)
                processComplete();
            /////////from the super method
        }
    }

    public SoundEvent getSound() {
        return AudioRegistry.machineLarge;
    }

    @Override
    public int getSoundDuration() {
        return 80;
    }

    @Override
    protected void playMachineSound(SoundEvent event) {
        world.playSound(getPos().getX(), getPos().getY() + 7, getPos().getZ(), event, SoundCategory.BLOCKS, Minecraft.getMinecraft().gameSettings.getSoundLevel(SoundCategory.BLOCKS), 0.975f + world.rand.nextFloat() * 0.05f, false);
    }

    @Override
    public boolean isRunning() {

        boolean bool = getMachineEnabled() &&
                //super.isRunning() &&
                dev.stannismod.stellurgy.api.StellurgyConfiguration.getCurrentConfig().enableTerraforming;

        if (!bool)
            currentTime = 0;

        return bool;
    }

    public void setOOF(boolean x) {
        if (!x && outOfFluid) {
            outOfFluid = false;
            this.world.notifyBlockUpdate(this.pos, this.world.getBlockState(this.pos), this.world.getBlockState(this.pos), 3);

            //System.out.println("s oof false");
        } else if (x && !outOfFluid) {
            outOfFluid = true;
            this.world.notifyBlockUpdate(this.pos, this.world.getBlockState(this.pos), this.world.getBlockState(this.pos), 3);

            //System.out.println("s oof true");
        }
    }

    @Override
    public void setMachineRunning(boolean running) {
        super.setMachineRunning(running);
        this.world.notifyBlockUpdate(this.pos, this.world.getBlockState(this.pos), this.world.getBlockState(this.pos), 3);

    }


    @Override
    protected void processComplete() {
        super.processComplete();
        completionTime = getCompletionTime();

        DimensionProperties properties = DimensionManager.getInstance().getDimensionProperties(world.provider.getDimension());
        if (!world.isRemote && properties != null && properties.getId() == world.provider.getDimension() && ((world.provider.getClass().equals(WorldProviderPlanet.class) &&
                properties.isNativeDimension) || StellurgyConfiguration.getCurrentConfig().allowTerraformNonStellurgy)) {
            if (buttonIncrease.getState() && properties.getAtmosphereDensity() < 1600) {
                // A step ADDS what the machine drained, so an oxygen-free world thickened this way
                // gains oxygen it can eventually breathe.
                properties.addToAtmosphere(new AirState(STEP_PER_GAS, STEP_PER_GAS, 0L));
                if (buttonIncrease.getState() && properties.getAtmosphereDensity() >= 1600) {
                    this.setMachineEnabled(false);
                    this.setMachineRunning(false);
                    markDirty();
                    this.world.notifyBlockUpdate(this.pos, this.world.getBlockState(this.pos), this.world.getBlockState(this.pos), 3);

                }
            }
            if (buttonDecrease.getState() && properties.getAtmosphereDensity() > 0) {
                properties.setAtmosphereDensity(properties.getAtmosphereDensity() - 1);
                if (buttonDecrease.getState() && properties.getAtmosphereDensity() <= 0) {
                    this.setMachineEnabled(false);
                    this.setMachineRunning(false);
                    markDirty();
                    this.world.notifyBlockUpdate(this.pos, this.world.getBlockState(this.pos), this.world.getBlockState(this.pos), 3);

                }
            }
        }
    }

    @Override
    public void readDataFromNetwork(ByteBuf in, byte packetId,
                                    NBTTagCompound nbt) {
        super.readDataFromNetwork(in, packetId, nbt);

        if (packetId == (byte) TileMultiblockMachine.NetworkPackets.TOGGLE.ordinal()) {
            radioButton.setOptionSelected(in.readByte());
        }
    }

    @Override
    public void writeDataToNetwork(ByteBuf out, byte id) {
        super.writeDataToNetwork(out, id);

        if (id == (byte) TileMultiblockMachine.NetworkPackets.TOGGLE.ordinal()) {
            out.writeByte(radioButton.getOptionSelected());
        }
    }

    @Override
    public void setMachineEnabled(boolean enabled) {
        super.setMachineEnabled(enabled);

        if (getMachineEnabled())
            completionTime = getCompletionTime();
    }

    @Override
    public void useNetworkData(EntityPlayer player, Side side, byte id,
                               NBTTagCompound nbt) {
        super.useNetworkData(player, side, id, nbt);
        if(!world.isRemote) {
            setOOF(false);
            markDirty();
            this.world.notifyBlockUpdate(this.pos, this.world.getBlockState(this.pos), this.world.getBlockState(this.pos), 3);

        }
    }

    @Override
    public void onInventoryButtonPressed(int buttonId) {
        //if (hasValidBiomeChanger()) {
        super.onInventoryButtonPressed(buttonId);
        if (buttonId == 1 || buttonId == 2) {
            PacketHandler.sendToServer(new PacketMachine(this, (byte) TileMultiblockMachine.NetworkPackets.TOGGLE.ordinal()));
        }
        //}
    }

    @Override
    protected void writeNetworkData(NBTTagCompound nbt) {
        super.writeNetworkData(nbt);
        nbt.setInteger("selected", radioButton.getOptionSelected());
        nbt.setBoolean("oofluid", outOfFluid);
        //System.out.println("write oof:"+outOfFluid);
    }

    @Override
    protected void readNetworkData(NBTTagCompound nbt) {
        super.readNetworkData(nbt);
        radioButton.setOptionSelected(nbt.getInteger("selected"));
        outOfFluid = nbt.getBoolean("oofluid");
        //System.out.println("oof:"+outOfFluid);

        if (world !=null && world.isRemote){
            //if (!client_contructed)
                //client_contructed = this.completeStructure(this.world.getBlockState(this.pos));

            setText();
        }
    }

    @Override
    public NBTTagCompound writeToNBT(NBTTagCompound nbt) {
        super.writeToNBT(nbt);
        return nbt;
    }

    @Override
    public void readFromNBT(NBTTagCompound nbt) {
        super.readFromNBT(nbt);
        outOfFluid = false;
    }

    @Override
    public String getMachineName() {
        return StellurgyBlocks.blockAtmosphereTerraformer.getLocalizedName();
    }
}