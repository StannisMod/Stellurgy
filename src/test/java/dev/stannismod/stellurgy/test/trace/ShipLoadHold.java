package dev.stannismod.stellurgy.test.trace;

import net.minecraft.server.MinecraftServer;

/**
 * Whether a TEST server keeps every ship loaded with no player near it — on for the life of the
 * server, off for a scenario whose subject is an unloaded ship ({@code stellurgytest vs permaload}).
 *
 * <p>The physics substrate loads a ship only while a player is within its load distance and queues an
 * unload every tick for one that is not — right for a real game, and unreachable for a headless test,
 * which has no player to spare and often none in the world at all. So every scenario that assembles a
 * craft wants it held, and it is held by default rather than asked for: 45 classes once called the
 * verb by hand, and 28 of them switched it back off when they finished, which turned it off for
 * whatever ran next.</p>
 *
 * <p>A field of the server object, added by {@code MixinMinecraftServerShipLoadHold} and read at the
 * substrate's load decision by {@code MixinShipLoadingHold} — so the shipped game carries no switch a
 * test can throw, and a second server in one JVM starts from the default again. Test source set.</p>
 */
public interface ShipLoadHold {

    boolean stellurgyTest$shipsHeldLoaded();

    void stellurgyTest$holdShipsLoaded(boolean hold);

    /** The probe verb's entry: the in-jar probe reaches it by name, so a released jar has none. */
    static void set(MinecraftServer server, boolean hold) {
        ((ShipLoadHold) server).stellurgyTest$holdShipsLoaded(hold);
    }
}
