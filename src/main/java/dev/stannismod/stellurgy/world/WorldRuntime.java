package dev.stannismod.stellurgy.world;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;

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

/**
 * State that a WORLD owns, for exactly as long as that world object exists — on the server and on
 * the client alike.
 *
 * <p>A world is the natural owner of anything addressed by it: which emitters stand in it, the HUD
 * message stamped with its clock. Kept anywhere longer-lived, such state outlives its world — into the
 * next world a single-player client opens, where a matching dimension number makes it look current.
 * Attached here, it is dropped with the world object: on server stop, on disconnect, and on every
 * dimension change of the client, because each of those builds a new world.</p>
 *
 * <p>Each subsystem keeps its own part, keyed by the part's class; this class knows none of them. Not
 * persisted: a part that must survive a restart belongs in the save, not here. Looking a part up is
 * safe from any thread — the physics thread reads one — and a part is responsible for its own
 * fields' thread safety.</p>
 */
public final class WorldRuntime {

    /**
     * Effectively final: assigned once by Forge's capability injection after {@link #register()}
     * runs in pre-init, and never written by this mod; lives as long as the process.
     */
    @CapabilityInject(WorldRuntime.class)
    private static Capability<WorldRuntime> capability = null;

    private final Map<Class<?>, Object> parts = new ConcurrentHashMap<>();

    private WorldRuntime() {
    }

    /**
     * {@code world}'s part of type {@code type}, built by {@code create} the first time it is asked
     * for. Throws when the world carries no runtime: that is a broken mod, not an empty world, and a
     * fresh part handed back in its place would be indistinguishable from a real one.
     */
    public static <T> T of(World world, Class<T> type, Supplier<T> create) {
        WorldRuntime runtime = capability == null ? null : world.getCapability(capability, null);
        if (runtime == null) {
            throw new IllegalStateException("world " + world.provider.getDimension()
                    + " carries no WorldRuntime; the capability was not registered or not attached");
        }
        return type.cast(runtime.parts.computeIfAbsent(type, ignored -> create.get()));
    }

    /** Register the capability. Pre-init: it must exist before the first world is built. */
    public static void register() {
        CapabilityManager.INSTANCE.register(WorldRuntime.class, new Capability.IStorage<WorldRuntime>() {
            @Override
            public NBTBase writeNBT(Capability<WorldRuntime> cap, WorldRuntime instance, EnumFacing side) {
                return null; // nothing here is persisted, by definition
            }

            @Override
            public void readNBT(Capability<WorldRuntime> cap, WorldRuntime instance, EnumFacing side,
                                NBTBase nbt) {
            }
        }, WorldRuntime::new);
    }

    /** Attaches a fresh runtime to every world as it is built. */
    public static final class Attach {

        @SubscribeEvent
        public static void attach(AttachCapabilitiesEvent<World> event) {
            ResourceLocation key = new ResourceLocation("stellurgy", "world_runtime");
            if (event.getCapabilities().containsKey(key)) {
                return;
            }
            WorldRuntime runtime = new WorldRuntime();
            event.addCapability(key, new ICapabilityProvider() {
                @Override
                public boolean hasCapability(Capability<?> cap, EnumFacing facing) {
                    return cap == capability;
                }

                @Override
                public <C> C getCapability(Capability<C> cap, EnumFacing facing) {
                    return cap == capability ? capability.cast(runtime) : null;
                }
            });
        }
    }
}
