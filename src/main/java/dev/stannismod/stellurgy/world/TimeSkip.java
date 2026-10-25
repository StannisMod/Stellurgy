package dev.stannismod.stellurgy.world;

import dev.stannismod.stellurgy.Stellurgy;
import dev.stannismod.stellurgy.api.StellurgyConfiguration;
import dev.stannismod.stellurgy.libvulpes.network.PacketHandler;
import dev.stannismod.stellurgy.mixin.WorldServerSleepAccessor;
import dev.stannismod.stellurgy.network.PacketSpaceClockSync;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.WorldServer;

import java.util.ArrayList;
import java.util.List;
import java.util.OptionalLong;

/**
 * The time skip (C16 CLOCK-7): the universe moved forward at once, every body's time of day with it.
 *
 * <p>Two ways in. A SLEEP: by default when every non-spectator player on the server is asleep, in whatever
 * worlds; with {@code sleepSkipNeedsEveryPlayer} off, when every one of one world is. The universe moves to
 * the next dawn of the world where the sleep completed — with several, the earliest — and their sleepers wake
 * with their weather reset, as vanilla wakes one world. A world whose skip is locked ({@link TimeSkipPolicy})
 * never completes a sleep, and in the server-wide mode a sleeper there keeps the night for everybody. A world
 * with no dawn of its own (not a body, or a body locked to its star) moves the universe to the overworld's.
 * And {@code /time}, an operator's command ({@link TimeCommandGuard}).</p>
 */
public final class TimeSkip {

    private TimeSkip() {
    }

    /** Move the universe {@code ticks} forward and tell every client at once (C16 CLOCK-8). */
    public static void skip(MinecraftServer server, long ticks) {
        if (ticks <= 0L) {
            return;
        }
        Stellurgy.serverState().skipSpaceClock(ticks);
        for (EntityPlayerMP player : server.getPlayerList().getPlayers()) {
            PacketHandler.sendToPlayer(PacketSpaceClockSync.current(), player);
        }
    }

    /**
     * Whether {@code world}'s sleep completes this tick — asked where vanilla asks whether all its players are
     * asleep, so a {@code true} runs vanilla's own wake-up for this world. A completed sleep has already moved
     * the universe and woken the other worlds it took.
     */
    public static boolean nightEnds(WorldServer world) {
        if (!world.areAllPlayersAsleep()) {
            return false;
        }
        if (!TimeSkipPolicy.allows(world)) {
            return true; // the lock's own path: the sleepers are told when dawn is and woken, nothing moves
        }
        MinecraftServer server = world.getMinecraftServer();
        List<WorldServer> occupied = new ArrayList<>();
        List<Sleepers> parts = new ArrayList<>();
        int completing = -1;
        for (WorldServer other : server.worlds) {
            if (other == null || other.playerEntities.isEmpty()) {
                continue;
            }
            if (other == world) {
                completing = parts.size();
            }
            occupied.add(other);
            parts.add(new Sleepers(other.areAllPlayersAsleep(), TimeSkipPolicy.allows(other),
                    TimeOfDay.ticksToDawn(other)));
        }
        OptionalLong skip = skipFor(StellurgyConfiguration.getCurrentConfig().sleepSkipNeedsEveryPlayer, parts,
                completing, TimeOfDay.ticksToDawn(server.getWorld(0)));
        if (!skip.isPresent()) {
            return false; // the night does not end yet: everybody waits in bed
        }
        skip(server, skip.getAsLong());
        if (StellurgyConfiguration.getCurrentConfig().sleepSkipNeedsEveryPlayer) {
            for (WorldServer other : occupied) {
                if (other != world) {
                    ((WorldServerSleepAccessor) other).stellurgy$wakeAllPlayers();
                }
            }
        }
        return true;
    }

    /** One occupied world's part in a sleep. Immutable. */
    public static final class Sleepers {
        final boolean allAsleep;
        final boolean allowed;
        final OptionalLong dawn;

        /**
         * @param allAsleep whether every non-spectator player of the world is asleep
         * @param allowed   whether its skip is allowed ({@link TimeSkipPolicy})
         * @param dawn      space ticks to its next dawn, empty for a world with none
         */
        public Sleepers(boolean allAsleep, boolean allowed, OptionalLong dawn) {
            this.allAsleep = allAsleep;
            this.allowed = allowed;
            this.dawn = dawn;
        }
    }

    /**
     * What a sleep completing in {@code worlds.get(completing)} comes to: the space ticks the universe moves, or
     * empty when the night does not end. With {@code everyPlayer}, every occupied world must be all asleep and
     * allowed, and the skip is the earliest of their dawns; without it, the completing world alone takes part.
     * A sleep taking no world with a dawn moves the universe to {@code overworldDawn}; with none, by nothing.
     */
    public static OptionalLong skipFor(boolean everyPlayer, List<Sleepers> worlds, int completing,
                                       OptionalLong overworldDawn) {
        Sleepers own = worlds.get(completing);
        if (!own.allAsleep || !own.allowed) {
            return OptionalLong.empty();
        }
        List<Sleepers> taking = new ArrayList<>();
        if (everyPlayer) {
            for (Sleepers part : worlds) {
                if (!part.allAsleep || !part.allowed) {
                    return OptionalLong.empty();
                }
                taking.add(part);
            }
        } else {
            taking.add(own);
        }
        long earliest = Long.MAX_VALUE;
        for (Sleepers part : taking) {
            if (part.dawn.isPresent()) {
                earliest = Math.min(earliest, part.dawn.getAsLong());
            }
        }
        if (earliest == Long.MAX_VALUE) {
            earliest = overworldDawn.isPresent() ? overworldDawn.getAsLong() : 0L;
        }
        return OptionalLong.of(earliest);
    }
}
