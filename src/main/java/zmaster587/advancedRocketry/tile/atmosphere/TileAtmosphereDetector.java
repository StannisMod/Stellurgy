package zmaster587.advancedRocketry.tile.atmosphere;

import io.netty.buffer.ByteBuf;
import net.minecraft.block.state.IBlockState;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.network.PacketBuffer;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.util.EnumFacing;
import net.minecraft.util.ITickable;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;
import net.minecraftforge.fml.relauncher.Side;
import zmaster587.advancedRocketry.AdvancedRocketry;
import zmaster587.advancedRocketry.api.AdvancedRocketryBlocks;
import zmaster587.advancedRocketry.api.atmosphere.Atmosphere;
import zmaster587.advancedRocketry.api.atmosphere.AtmosphereAssertion;
import zmaster587.advancedRocketry.api.atmosphere.AtmosphereRegister;
import zmaster587.advancedRocketry.atmosphere.AtmosphereAssertions;
import zmaster587.advancedRocketry.atmosphere.AtmosphereHandler;
import zmaster587.advancedRocketry.block.BlockRedstoneEmitter;
import zmaster587.libVulpes.LibVulpes;
import zmaster587.libVulpes.inventory.modules.*;
import zmaster587.libVulpes.network.PacketHandler;
import zmaster587.libVulpes.network.PacketMachine;
import zmaster587.libVulpes.util.INetworkMachine;

import javax.annotation.Nullable;
import java.util.LinkedList;
import java.util.List;
import java.util.Locale;

public class TileAtmosphereDetector extends TileEntity implements ITickable, IModularInventory, IButtonInventory, INetworkMachine {

    private AtmosphereAssertion assertionToDetect;

    private static final int BUTTON_COLOR_NORMAL = 0xFF22FF22;
    private static final int BUTTON_COLOR_SELECTED = 0xFFFFFF55;
    private static final int BUTTON_BG_NORMAL = 0xFFFFFFFF;
    private static final int BUTTON_BG_SELECTED = 0xFF444444;

    public TileAtmosphereDetector() {
        assertionToDetect = AtmosphereAssertion.BREATHABLE;
    }


    @Override
    public void update() {
        if (!world.isRemote && world.getWorldTime() % 10 == 0) {
            IBlockState state = world.getBlockState(pos);
            boolean detectedAtm = false;

            //TODO: Galacticcraft support
            detectedAtm = statementHolds();

            if (((BlockRedstoneEmitter) state.getBlock()).getState(world, state, pos) != detectedAtm) {
                ((BlockRedstoneEmitter) state.getBlock()).setState(world, state, pos, detectedAtm);
            }
        }
    }

    /**
     * Whether the statement this detector watches holds on any face it can see.
     * <p>
     * <b>Public because a test harness has to be able to ask the question the game asks.</b> The
     * detector samples on a world-clock modulo that a force-ticked headless server never reaches, so
     * something has to drive it — and the probe that did used to carry its OWN copy of this loop.
     * Two implementations of one rule stay in step exactly as long as nobody edits one of them.
     */
    public boolean statementHolds() {
        AtmosphereHandler handler = AtmosphereHandler.getOxygenHandler(world.provider.getDimension());
        if (handler == null) {
            // No handler for this dimension: the only thing anyone can honestly say about the air is
            // that it is ordinary, so only the statement that it is breathable holds.
            return assertionToDetect == AtmosphereAssertion.BREATHABLE;
        }
        for (EnumFacing direction : EnumFacing.values()) {
            if (!world.getBlockState(pos.offset(direction)).isOpaqueCube()
                    && AtmosphereAssertions.holdsAt(handler, pos.offset(direction), assertionToDetect)) {
                return true;
            }
        }
        return false;
    }

    @Override
    public boolean shouldRefresh(World world, BlockPos pos,
                                 IBlockState oldState, IBlockState newSate) {
        return (oldState.getBlock() != newSate.getBlock());
    }


    @Override
    public List<ModuleBase> getModules(int id, EntityPlayer player) {
        List<ModuleBase> modules = new LinkedList<>();
        List<ModuleBase> btns = new LinkedList<>();

        AtmosphereAssertion[] assertions = AtmosphereAssertion.values();

        for (int i = 0; i < assertions.length; i++) {
            AtmosphereAssertion assertion = assertions[i];

            btns.add(AdvancedRocketry.proxy.createAtmosphereDetectorButton(
                    60,
                    4 + i * 24,
                    i,
                    assertion,
                    getLocalizedAssertionName(assertion),
                    this,
                    zmaster587.libVulpes.inventory.TextureResources.buttonBuild
            ));
        }

        ModuleContainerPan panningContainer = new ModuleContainerPan(
                5, 20, btns, new LinkedList<>(),
                zmaster587.libVulpes.inventory.TextureResources.starryBG,
                160, 100, 0, 500
        );
        modules.add(panningContainer);
        return modules;
    }


    @Override
    public String getModularInventoryName() {
        return AdvancedRocketryBlocks.blockOxygenDetection.getLocalizedName();
    }

    @Override
    public boolean canInteractWithContainer(@Nullable EntityPlayer entity) {
        return true;
    }

    @Override
    public void onInventoryButtonPressed(int buttonId) {
        AtmosphereAssertion[] assertions = AtmosphereAssertion.values();

        if (buttonId < 0 || buttonId >= assertions.length) {
            return;
        }

        AtmosphereAssertion previous = assertionToDetect;
        assertionToDetect = assertions[buttonId];

        if (world == null || world.isRemote) {
            String atmosphereName = getLocalizedAssertionName(assertionToDetect);

            if (previous == assertionToDetect) {
                AdvancedRocketry.proxy.sendClientStatusMessage(
                        "msg.advancedrocketry.atmosphereDetector.alreadySelected",
                        atmosphereName
                );
            }
            else {
                AdvancedRocketry.proxy.sendClientStatusMessage(
                        "msg.advancedrocketry.atmosphereDetector.selected",
                        atmosphereName
                );
            }

            PacketHandler.sendToServer(new PacketMachine(this, (byte) 0));
        }
    }
    public boolean isAssertionSelected(AtmosphereAssertion assertion) {
        return assertionToDetect == assertion;
    }

    /** What the button says. Falls back to the constant's own name where a pack has no translation. */
    public static String getLocalizedAssertionName(AtmosphereAssertion assertion) {
        if (assertion == null) {
            return "";
        }

        String key = assertion.messageKey();
        String label = LibVulpes.proxy.getLocalizedString(key);

        return label.equals(key) ? assertion.name() : label;
    }

    private static boolean isSameAtmosphere(Atmosphere first, Atmosphere second) {
        if (first == second) {
            return true;
        }

        if (first == null || second == null) {
            return false;
        }

        return first.getUnlocalizedName().equals(second.getUnlocalizedName());
    }

    @Override
    public void writeDataToNetwork(ByteBuf out, byte id) {
        // The assertion travels by NAME rather than by ordinal, so that adding one in the middle of
        // the enum cannot silently re-point every detector already placed in a world.
        if (id == 0) {
            PacketBuffer buf = new PacketBuffer(out);
            buf.writeShort(assertionToDetect.name().length());
            buf.writeString(assertionToDetect.name());
        }
    }

    @Override
    public void readDataFromNetwork(ByteBuf in, byte packetId,
                                    NBTTagCompound nbt) {
        if (packetId == 0) {
            PacketBuffer buf = new PacketBuffer(in);
            nbt.setString("assertion", buf.readString(buf.readShort()));
        }
    }

    @Override
    public void useNetworkData(EntityPlayer player, Side side, byte id,
                               NBTTagCompound nbt) {
        if (id == 0) {
            assertionToDetect = parseAssertion(nbt.getString("assertion"));
        }
    }

    @Override
    public NBTTagCompound writeToNBT(NBTTagCompound nbt) {
        super.writeToNBT(nbt);

        nbt.setString("assertion", assertionToDetect.name());
        return nbt;
    }

    @Override
    public void readFromNBT(NBTTagCompound nbt) {
        super.readFromNBT(nbt);

        assertionToDetect = parseAssertion(nbt.getString("assertion"));
    }

    /**
     * An assertion this build does not know reads as "is it breathable" — the same default a fresh
     * detector starts on. Never a guess at what was meant: a detector whose statement has been removed
     * from the game should sit on the harmless one rather than on whichever happens to be first.
     */
    private static AtmosphereAssertion parseAssertion(String name) {
        if (name != null && !name.isEmpty()) {
            for (AtmosphereAssertion candidate : AtmosphereAssertion.values()) {
                if (candidate.name().equals(name)) {
                    return candidate;
                }
            }
        }
        return AtmosphereAssertion.BREATHABLE;
    }
}
