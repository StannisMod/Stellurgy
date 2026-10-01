package dev.stannismod.stellurgy.test.integration;

import dev.stannismod.stellurgy.dimension.DimensionManager;
import dev.stannismod.stellurgy.dimension.DimensionProperties;
import dev.stannismod.stellurgy.stations.SpaceObjectManager;
import dev.stannismod.stellurgy.stations.SpaceStationObject;
import dev.stannismod.stellurgy.test.MinecraftBootstrap;
import dev.stannismod.stellurgy.util.Asteroid;
import org.junit.BeforeClass;
import org.junit.Test;

import static org.junit.Assert.*;

/**
 * A world opened after another in the same process starts from its own galaxy: nothing the previous
 * server knew or counted reaches it.
 *
 * <p>Single player is where this bites — one client process runs one integrated server after
 * another — and the dedicated-server harness cannot show it, because it forks a JVM per boot. So
 * the two servers here are the two lifetimes the server-start and server-stop hooks open and close,
 * driven through the same methods those hooks call.</p>
 *
 * <p>Each assertion names something a player would meet in the second world: the first station is
 * not #1, satellite ids continue the old count, the one-time moon and warp advancements are already
 * spent, an asteroid kind from the old server's file is still offered, a planet discovered in the old
 * world is known, Earth has lost its size.</p>
 */
public class ServerLifetimeStartsFreshTest {

    @BeforeClass
    public static void bootstrap() {
        MinecraftBootstrap.ensure();
    }

    @Test
    public void theNextServerInheritsNothingFromThePreviousOne() {
        DimensionManager first = DimensionManager.getInstance();
        SpaceObjectManager firstStations = SpaceObjectManager.getSpaceManager();
        first.setReachedMoon(true);
        first.setReachedWarp(true);
        first.getNextSatelliteId();
        first.getNextSatelliteId();
        first.getAsteroidTypes().put("previousServersAsteroid", new Asteroid());
        first.knownPlanets.add(4242);
        firstStations.registerSpaceObject(new SpaceStationObject(), 0, 1);

        MinecraftBootstrap.restartServerLifetime();

        DimensionManager second = DimensionManager.getInstance();
        SpaceObjectManager secondStations = SpaceObjectManager.getSpaceManager();
        assertNotSame("a new server must get a new galaxy, not the last one emptied", first, second);
        assertNotSame("a new server must get new stations, not the last ones emptied",
                firstStations, secondStations);

        assertFalse("moon progression of the previous world", second.hasReachedMoon());
        assertFalse("warp progression of the previous world", second.hasReachedWarp());
        assertEquals("satellite ids must start over", 0L, second.getNextSatelliteId());
        assertEquals("the first station of the new world must be #1", 1, secondStations.getNextStationId());
        assertTrue("asteroid kinds of the previous server: " + second.getAsteroidTypes().keySet(),
                second.getAsteroidTypes().isEmpty());
        assertFalse("a planet known in the previous world", second.isPlanetKnown(4242));

        DimensionProperties earth = second.getOverworldProperties();
        assertEquals("Earth", earth.getName());
        assertEquals("Earth's own temperature, not a planet's generic 100 K", 286, earth.getAverageTemp());
        assertEquals("the overworld is the body the mass unit is defined by", 1d, earth.getMass(), 0d);
        assertEquals("the overworld is the body the radius unit is defined by", 1d, earth.getRadius(), 0d);
    }
}
