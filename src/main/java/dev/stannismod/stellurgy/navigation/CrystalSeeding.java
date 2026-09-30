package dev.stannismod.stellurgy.navigation;

import java.util.Optional;

import net.minecraft.server.MinecraftServer;
import net.minecraft.world.World;

import dev.stannismod.stellurgy.dimension.DimensionManager;
import dev.stannismod.stellurgy.dimension.DimensionProperties;
import dev.stannismod.stellurgy.space.GalacticCoord;
import dev.stannismod.stellurgy.universe.InfoTier;
import dev.stannismod.stellurgy.universe.SystemBodyKind;
import dev.stannismod.stellurgy.universe.UniverseRegistry;

/**
 * What a brand-new memory crystal already knows.
 *
 * <p>A first crystal is not blank. It carries the address of the world the pilot came from, plus the
 * places the setting treats as common knowledge. Without that, a player's first navigation computer
 * would have nowhere at all to point, and the only way to get a first address would be to fly blind
 * to a coordinate typed at random.</p>
 *
 * <p>What counts as common knowledge is {@code planetsMustBeDiscovered}'s question, and it is asked
 * here: with discovery OFF (the default) every authored body is seeded, because nothing in that
 * regime is meant to need discovering; with it ON, only the bodies the pack author marked known.
 * Either way the home body's own zone is skipped: its moons are found with a telescope — see the
 * loop.</p>
 *
 * <p>Everything seeded here is recorded at {@link InfoTier#TELESCOPE}: common knowledge is knowing a
 * place exists, not having surveyed it.</p>
 */
public final class CrystalSeeding {

    private CrystalSeeding() {
    }

    /**
     * The starter set of addresses for {@code world}'s server: the home world's own coordinate, and
     * every common-knowledge body outside the home world's zone. Empty when the universe registry is not up —
     * a crystal made before the world is ready is simply blank, never broken.
     */
    public static CrystalMemory starterFor(World world) {
        CrystalMemory memory = new CrystalMemory();
        if (world == null) {
            return memory;
        }
        MinecraftServer server = world.getMinecraftServer();
        UniverseRegistry registry = server == null ? null : UniverseRegistry.get(server);
        if (registry == null) {
            return memory;
        }
        // The SPACE clock, not this world's. Two crystals merge by freshness — same body, newer
        // observation wins — so a stamp is only comparable against another stamp from the same
        // counter. Every dimension but the overworld advances only while it ticks, so a crystal made
        // on a moon and one made at home would be dated on two unrelated clocks and the merge would
        // pick by where the player happened to be standing rather than by what he had seen more
        // recently.
        long now = dev.stannismod.stellurgy.space.SpaceSubsystem.spaceClock();

        GalacticCoord home = coordOf(registry, 0);
        if (home != null) {
            memory.record(new CrystalEntry(home, nameOf(0), SystemBodyKind.PLANET,
                    InfoTier.TELESCOPE, now, 0));
        }

        DimensionManager dims = DimensionManager.getInstance();
        // WHICH bodies count as common knowledge is the discovery flag's own question, and until now
        // this seeding never asked it. With planetsMustBeDiscovered=false nothing in the game is
        // supposed to need discovering - the rocket destination gate and the station list both read
        // it exactly that way - so a first crystal carries every body the pack authored. With the
        // flag on, only the bodies the author marked known are common knowledge, which is the older
        // behaviour and stays.
        Iterable<Integer> candidates =
                dev.stannismod.stellurgy.api.StellurgyConfiguration.getCurrentConfig().planetsMustBeDiscovered
                        ? dims.knownPlanets
                        : java.util.Arrays.asList(dims.getRegisteredDimensions());
        if (candidates == null) {
            return memory;
        }
        for (Integer dimId : candidates) {
            if (dimId == null || dimId == 0) {
                continue;
            }
            GalacticCoord coord = coordOf(registry, dimId);
            if (coord == null) {
                continue;
            }
            // Skipped: the HOME BODY'S OWN ZONE - the home world (recorded above) and every moon that
            // has a cell inside its zone. A moon is a destination of its own, and the home world's
            // moons are the first thing a player's telescope is for: the observatory's local radar
            // names them (TelescopeScan.characterise), a starter crystal does not. The other planets
            // of the home system are NOT in this zone - each has a cell of its own in the galactic
            // lattice - so they stay common knowledge like any other body.
            if (home != null && isInZoneOf(coord, home)) {
                continue;
            }
            // The dim id is the entry's IDENTITY: bodies orbit, so the coordinate recorded here is
            // where this one stood at seeding time and nothing more. A pick aims at the body.
            memory.record(new CrystalEntry(coord, nameOf(dimId), SystemBodyKind.PLANET,
                    InfoTier.TELESCOPE, now, dimId));
        }
        return memory;
    }

    /**
     * Whether {@code coord} names {@code body}'s own cell or a cell anywhere inside its zone. A zoned
     * key is its zone's key plus a suffix, so containment is a prefix up to a separator.
     */
    private static boolean isInZoneOf(GalacticCoord coord, GalacticCoord body) {
        String bodyKey = body.cellKey();
        String key = coord.cellKey();
        return key.equals(bodyKey) || key.startsWith(bodyKey + GalacticCoord.ZONE_SEPARATOR);
    }

    private static GalacticCoord coordOf(UniverseRegistry registry, int dimId) {
        Optional<GalacticCoord> coord = registry.coordForPlanet(dimId);
        return coord.isPresent() ? coord.get() : null;
    }

    private static String nameOf(int dimId) {
        DimensionProperties props = DimensionManager.getInstance().getDimensionProperties(dimId);
        return props == null || props.getName() == null ? "" : props.getName();
    }
}
