package dev.stannismod.stellurgy.command.test;

import java.util.ArrayList;
import java.util.List;

import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.world.WorldEvent;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;

/**
 * The bus subscriptions that belong to ONE server's life, and the thing that takes them off again.
 *
 * <p>A test-mode listener that is registered once per JVM behind a static "already registered" flag
 * has no owner: a JVM that runs a second server (an integrated client that stops one world and opens
 * another) keeps the first server's subscriber, and anything it holds, for the rest of the process.
 * So such listeners are held here instead. One of these is made in
 * {@link TestProbeCommandRegistration#registerIfTestMode} when a server starts, and every subscriber
 * it {@link #hold}s goes on the bus with it.</p>
 *
 * <p><b>The release</b> is that server's overworld unloading. {@code MinecraftServer.stopServer}
 * posts {@code WorldEvent.Unload} for every world it saves before it returns, and the overworld is
 * never unloaded while the server runs (Forge's {@code DimensionManager.canUnloadWorld} refuses a
 * dimension whose type loads its spawn), so a server-side unload of dimension 0 is the server
 * stopping. On it, everything held — and this — leaves the bus; nothing is kept for the next
 * server.</p>
 */
final class ServerScoped {

    private final List<Object> held = new ArrayList<>();

    private ServerScoped() {
    }

    /** A new scope for the server that is starting, already listening for its end. */
    static ServerScoped start() {
        ServerScoped scope = new ServerScoped();
        MinecraftForge.EVENT_BUS.register(scope);
        return scope;
    }

    /** Put {@code subscriber} on the bus for the life of this server, and answer it. */
    <T> T hold(T subscriber) {
        MinecraftForge.EVENT_BUS.register(subscriber);
        held.add(subscriber);
        return subscriber;
    }

    @SubscribeEvent
    public void onWorldUnload(WorldEvent.Unload event) {
        if (event.getWorld().isRemote || event.getWorld().provider.getDimension() != 0) {
            return;
        }
        for (Object subscriber : held) {
            MinecraftForge.EVENT_BUS.unregister(subscriber);
        }
        held.clear();
        MinecraftForge.EVENT_BUS.unregister(this);
    }
}
