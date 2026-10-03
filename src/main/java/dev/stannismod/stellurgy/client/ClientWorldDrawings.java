package dev.stannismod.stellurgy.client;

import net.minecraft.client.Minecraft;
import net.minecraft.nbt.NBTBase;
import net.minecraft.util.EnumFacing;
import net.minecraft.util.ResourceLocation;
import net.minecraft.world.World;
import net.minecraftforge.common.capabilities.Capability;
import net.minecraftforge.common.capabilities.CapabilityInject;
import net.minecraftforge.common.capabilities.CapabilityManager;
import net.minecraftforge.common.capabilities.ICapabilityProvider;
import net.minecraftforge.event.AttachCapabilitiesEvent;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;

import javax.annotation.Nullable;
import java.util.function.Consumer;

/**
 * What a client world is drawing of the war — its beams and its rounds in flight — held BY that world.
 *
 * <p>Attached to every client world as it is built, so its lifetime is the world's: the drawings are
 * born empty with a world and go when the client lets that world go. Nothing clears them, and nothing
 * needs to — a change of dimension or server builds a new world, and with it new, empty drawings.
 * Packet handlers and renderers reach them through the world they already hold.</p>
 */
public final class ClientWorldDrawings implements ICapabilityProvider {

    /** OWNER: the Forge loader, which fills it once the capability is registered; never released. */
    @CapabilityInject(ClientWorldDrawings.class)
    private static Capability<ClientWorldDrawings> DRAWINGS = null;

    private static final ResourceLocation KEY = new ResourceLocation("stellurgy", "client_drawings");

    private final ClientBeamTracker beams = new ClientBeamTracker();
    private final ClientShotTracker shots = new ClientShotTracker();

    private ClientWorldDrawings() {
    }

    public ClientBeamTracker beams() {
        return beams;
    }

    public ClientShotTracker shots() {
        return shots;
    }

    /**
     * This client world's drawings.
     *
     * @throws IllegalArgumentException for a server world, which draws nothing
     * @throws IllegalStateException    for a client world built without them, which means the
     *                                  capability was never registered or attached
     */
    public static ClientWorldDrawings of(World world) {
        if (world == null || !world.isRemote) {
            throw new IllegalArgumentException("drawings belong to a client world, not to "
                    + (world == null ? "no world" : "a server world"));
        }
        ClientWorldDrawings drawings = DRAWINGS == null ? null : world.getCapability(DRAWINGS, null);
        if (drawings == null) {
            throw new IllegalStateException("client world of dim " + world.provider.getDimension()
                    + " carries no drawings: the capability was not registered or not attached");
        }
        return drawings;
    }

    /**
     * Applies a packet's news to the drawings of the world the client is in when it is applied.
     *
     * <p>Called from a packet's {@code executeClient}, which the channel runs on the client thread in
     * the same queue vanilla's own packets are applied from — so a round announced after a change of
     * dimension finds the new world here. With no world loaded there is nothing to draw on, and the
     * news is dropped with it.</p>
     */
    public static void apply(Consumer<ClientWorldDrawings> action) {
        World world = Minecraft.getMinecraft().world;
        if (world != null) {
            action.accept(of(world));
        }
    }

    /** Registers the capability and the handler that gives every client world its drawings. */
    public static void register() {
        CapabilityManager.INSTANCE.register(ClientWorldDrawings.class,
                new Capability.IStorage<ClientWorldDrawings>() {
                    // Never persisted: a drawing is a picture of what the server said this session,
                    // and the provider is not serializable, so Forge has nothing to call these for.
                    @Override
                    public NBTBase writeNBT(Capability<ClientWorldDrawings> capability,
                                            ClientWorldDrawings instance, EnumFacing side) {
                        return null;
                    }

                    @Override
                    public void readNBT(Capability<ClientWorldDrawings> capability,
                                        ClientWorldDrawings instance, EnumFacing side, NBTBase nbt) {
                    }
                },
                ClientWorldDrawings::new);
        net.minecraftforge.common.MinecraftForge.EVENT_BUS.register(new Attach());
    }

    @Override
    public boolean hasCapability(Capability<?> capability, @Nullable EnumFacing facing) {
        return capability == DRAWINGS;
    }

    @Override
    @Nullable
    public <T> T getCapability(Capability<T> capability, @Nullable EnumFacing facing) {
        return capability == DRAWINGS ? DRAWINGS.cast(this) : null;
    }

    /** Gives each client world its own drawings as the world is built. */
    public static final class Attach {

        @SubscribeEvent
        public void attach(AttachCapabilitiesEvent<World> event) {
            if (event.getObject().isRemote && !event.getCapabilities().containsKey(KEY)) {
                event.addCapability(KEY, new ClientWorldDrawings());
            }
        }
    }
}
