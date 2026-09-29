package zmaster587.advancedRocketry.test.mixin;

import net.minecraft.nbt.NBTTagCompound;
import net.minecraftforge.fml.common.FMLCommonHandler;
import net.minecraftforge.fml.relauncher.Side;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import com.github.stannismod.forge.testing.client.bridge.ForgeTestClientBootstrap;

import zmaster587.advancedRocketry.api.SatelliteRegistry;
import zmaster587.advancedRocketry.api.satellite.SatelliteBase;
import zmaster587.advancedRocketry.command.test.TestEventLog;
import zmaster587.advancedRocketry.test.trace.TestTrace;

/**
 * A satellite's {@code dataType} was looked up in the registry while loading it from NBT — and
 * either produced a satellite or was dropped — as an event, on whichever side did the loading.
 *
 * <p>{@code SatelliteRegistry.createFromNBT} is the single funnel every persisted or wire-borne
 * satellite passes through: a dimension's save ({@code DimensionProperties.readFromNBT}), a rocket's
 * cargo ({@code EntityRocket}), and the two client-bound packets ({@code PacketSatellite},
 * {@code PacketSatellitesUpdate}). Production resolves the type name against the registry and,
 * when nothing is registered under it, returns {@code null} with a warning so the caller drops the
 * satellite instead of re-saving it under a placeholder type. {@code satellite_nbt_resolved} records
 * that decision at the method's RETURN: {@code dataType} is the name production looked up,
 * {@code resolved} is whether it got a satellite back. Both returns of the target — the early
 * {@code null} for an unknown type and the final one carrying the loaded satellite — are read off
 * the same {@code CallbackInfoReturnable}, so the record cannot disagree with what the caller
 * receives.</p>
 *
 * <h2>Routing by PHYSICAL side</h2>
 *
 * <p>The client packets are decoded where the network layer hands them over, which may be the netty
 * thread rather than the client thread; Forge's effective side is thread-derived and answers
 * {@code CLIENT} for any thread that is not the server thread — including a dedicated server's own
 * netty thread. So this seam does not ask the effective side. It asks the physical side of the JVM
 * ({@code FMLCommonHandler.getSide()}): on the client JVM the record goes straight to the harness's
 * client log ({@code ForgeTestClientBootstrap.recordEvent}, which is thread-safe), on the server JVM
 * to the server log via {@link TestTrace#recordServer}. The test harness runs its client against a
 * separate server process, so the physical side is exactly the log the resolution belongs to.</p>
 *
 * <h2>What it is silent about</h2>
 *
 * <p>It does not say WHICH caller loaded the satellite — a save, a rocket, or a packet — nor which
 * dimension or satellite id it will be attached to: the registry is dim-less, the id lives inside
 * the NBT the caller owns, and adding it here would duplicate a read the caller is about to make.
 * It does not see a satellite that was never sent through this funnel (one built in-world and added
 * directly), nor an in-place refresh of an already-known satellite
 * ({@code PacketSatellitesUpdate} calls {@code readFromNBT} on the existing instance instead). And
 * it does not see a throw: an exception escaping {@code readFromNBT} skips the RETURN.</p>
 *
 * <p>The routing itself has one blind spot, and it is the price of choosing the physical side: a
 * client JVM hosting an INTEGRATED server is one JVM, so a resolution done by that integrated
 * server — a dimension's save load, on the server thread — is stamped CLIENT and written to the
 * client log. The two-process harness this suite runs never hits that (its client has no integrated
 * server, and its server JVM is physically {@code SERVER} on every thread), but a chain read from a
 * single-process run must not treat a client-log record here as proof the CLIENT resolved anything.
 * The alternative — the effective side — is wrong in the case this seam exists for, since a netty
 * thread answers {@code CLIENT} on a dedicated server too, which would put a server record in a
 * client log that the server JVM has no way to write.</p>
 */
@Mixin(SatelliteRegistry.class)
public abstract class MixinSatelliteRegistryEvents {

    private static final String INSTRUMENT = "satellite_registry_events";

    @Inject(method = "createFromNBT", at = @At("RETURN"))
    private static void arTest$resolved(NBTTagCompound nbt, CallbackInfoReturnable<SatelliteBase> cir) {
        boolean client = FMLCommonHandler.instance().getSide() == Side.CLIENT;
        arTest$instrument(client);
        // RETURN fires at both exits (the early null for an unknown type and the final loaded one);
        // the payload is composed only from the argument and the return value, which exist at both.
        String dataType = nbt == null ? "" : nbt.getString("dataType");
        SatelliteBase satellite = cir.getReturnValue();
        String payload = "\"dataType\":\"" + TestTrace.json(dataType) + "\",\"resolved\":"
                + (satellite != null);
        if (client) {
            ForgeTestClientBootstrap.recordEvent("satellite_nbt_resolved", payload);
        } else {
            TestTrace.recordServer("satellite_nbt_resolved", payload);
        }
    }

    /** The instrument note, routed by the same physical side as the record — not by thread. */
    private static void arTest$instrument(boolean client) {
        if (client) {
            ForgeTestClientBootstrap.noteInstrumentEntered(INSTRUMENT);
        } else {
            TestEventLog.noteInstrumentEntered(INSTRUMENT);
        }
    }
}
