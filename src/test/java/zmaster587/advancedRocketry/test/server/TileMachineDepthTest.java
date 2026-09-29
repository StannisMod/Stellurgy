package zmaster587.advancedRocketry.test.server;

import zmaster587.advancedRocketry.test.Reply;
import org.junit.Test;

import zmaster587.advancedRocketry.test.EnergyStore;
import zmaster587.advancedRocketry.test.FixtureSite;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * tile-machine isolated coverage.
 *
 * Production previously had ZERO per-tile-class regression nets;
 * {@code MultiMachineControllerSmokeTest} touches assembler-style
 * controllers, but the individual machines (solar panel, fluid tank,
 * force-field projector, guidance computer, oxygen vent, pump,
 * satellite builder) are exercised only indirectly. A capability rename
 * or NBT-key drift on any of them currently surfaces as a runtime
 * NullPointerException in production — not a test failure.
 *
 * Each test below uses the existing
 *   {@code /artest place <dim> <x> <y> <z> <blockId>} &rarr; place a tile
 *   {@code /artest tile force-tick <dim> <x> <y> <z> <ticks>} &rarr; drive it
 *   {@code /artest energy stored / inject <dim> <x> <y> <z>} &rarr; cap probe
 *   {@code /artest tile state <dim> <x> <y> <z>} &rarr; state probe (where present)
 * The tests pin the *contract surface* (tile class FQN, capability
 * presence, force-tick survives without crashing). They do NOT assert
 * gameplay numbers (production has no canonical "solar panel produces
 * X RF/tick under simulated daylight" reference) — those belong in
 * a future tier.
 */
public class TileMachineDepthTest extends AbstractSharedServerTest {

    private static final int DIM = 0;
    // Stay near spawn so the chunk is loaded; spread far enough apart that
    // tiles placed by separate tests don't interact.
    private static final int BASE_X = 200;
    private static final int BASE_Z = 200;
    private static final int Y = FixtureSite.OPEN_AIR_Y; // above terrain to avoid stone overwrite quirks

    /** What the Forge energy capability at one block reports — refusing a block that is not there. */
    private EnergyStore energy(int x, int z) throws Exception {
        return EnergyStore.at(cmd -> ok(client().execute(cmd)), DIM, x, Y, z);
    }

    private static String ok(java.util.List<String> resp) {
        return String.join("\n", resp);
    }

    /**
     * Place a block, pre-clearing the position with air. The pre-clear is
     * necessary because some AR blocks' tile-entity wiring depends on the
     * neighbour-update + onBlockPlaced chain that vanilla setBlockState
     * doesn't always trigger when overwriting a non-air block (e.g. terrain).
     */
    private void place(String blockId, int x, int y, int z) throws Exception {
        // Clear first.
        client().execute("artest place " + DIM + " " + x + " " + y + " " + z + " minecraft:air");
        String r = ok(client().execute(
                "artest place " + DIM + " " + x + " " + y + " " + z + " " + blockId));
        assertTrue("place(" + blockId + ") at " + x + "," + y + "," + z + " failed: " + r,
                Reply.of(r).bool("placed"));
    }

    @Test
    public void solarGeneratorExposesEnergyCapAndSurvivesForceTick() throws Exception {
        // NOTE: the registry name "solarPanel" is the *plain decorative
        // block* — it has no tile entity. The tile-bearing machine is
        // "solarGenerator" (bound to TileSolarPanel internally). Easy
        // mistake — pin so a future test author lands on the right block.
        int x = BASE_X, z = BASE_Z;
        place("advancedrocketry:solarGenerator", x, Y, z);

        EnergyStore stored = energy(x, z);
        assertTrue("solar generator must expose CapabilityEnergy: " + stored.raw(),
                stored.hasEnergy);
        assertEquals("the tile standing there must be the solar panel: " + stored.raw(),
                "TileSolarPanel", stored.tileSimpleName());

        // Force-tick — should not crash, even with no daylight on dim 0
        // at world spawn (production handles "no sky" gracefully).
        String tickResp = ok(client().execute(
                "artest tile force-tick " + DIM + " " + x + " " + Y + " " + z + " 5"));
        assertTrue("solar generator force-tick must not error: " + tickResp,
                Reply.of(tickResp).ok());
    }

    @Test
    public void fluidTankPlacesAndExposesTileClass() throws Exception {
        int x = BASE_X + 4, z = BASE_Z;
        place("advancedrocketry:liquidTank", x, Y, z);

        // No /artest fluid info -> use the energy probe just to confirm tile
        // class via tileClass field (probe reports it on every call).
        // Capability check via /artest fluid (existing probe) is the
        // strongest evidence the tank actually exposes IFluidHandler.
        String tankResp = ok(client().execute(
                "artest fluid stored " + DIM + " " + x + " " + Y + " " + z));
        // Probe returns either {"ok":true,...} or {"error":…}. The claim is that the tile is
        // THERE, so it is made against the refusal itself rather than against the rendering: a
        // substring test for `no tile entity` was also satisfied by every OTHER refusal, so a
        // probe that said `world not loaded` read as a tile standing where none was.
        assertFalse("fluid tank place silently dropped tile: " + tankResp,
                Reply.of(tankResp).refused());
        // The tile should be TileFluidTank (or its TileFluidHatch parent).
        // tileClass is only emitted by the energy probe, so reuse that
        // to verify the tile lives.
        EnergyStore storedResp = energy(x, z);
        // The family is named by its MEMBERS rather than by a fragment of their spelling: the
        // substring form accepted any class whose name happened to carry those letters, in any
        // package, including one belonging to another mod entirely.
        String tile = storedResp.tileSimpleName();
        assertTrue("liquidTank must be a TileFluidTank-family class: " + storedResp.raw(),
                "TileFluidTank".equals(tile) || "TileFluidHatch".equals(tile));
    }

    @Test
    public void guidanceComputerHasInventorySlotAccessibleByHatchProbe() throws Exception {
        // TileGuidanceComputer extends TileInventoryHatch, so the
        // /artest hatch read probe must dump at least one slot (even if
        // empty). If the inventory size dropped to 0 the entire
        // ship-builder UI would break silently.
        int x = BASE_X + 8, z = BASE_Z;
        place("advancedrocketry:guidanceComputer", x, Y, z);

        String hatchResp = ok(client().execute(
                "artest hatch read " + DIM + " " + x + " " + Y + " " + z));
        Reply hatch = Reply.of("artest hatch read", hatchResp);
        assertFalse("guidance computer must accept hatch-read probe: " + hatchResp,
                hatch.refused());
        // The hatch probe reports either {"slots":[...]} or {"size":N}; both
        // imply the inventory was discoverable. Asked of the fields, so a `size` belonging to
        // some nested member cannot answer for the inventory's own.
        assertTrue("guidance computer hatch-read should yield slot info: " + hatchResp,
                hatch.has("slots") || hatch.has("size"));
    }

    @Test
    public void oxygenVentExposesTileAndAcceptsForceTick() throws Exception {
        // TileOxygenVent is an inventoried RF-consumer tank — placing it
        // and force-ticking once exercises the implements-chain
        // (IBlobHandler, IModularInventory, INetworkMachine, IToggleable, …).
        // A subtle rename of one of those would NPE in the toggle path.
        int x = BASE_X + 12, z = BASE_Z;
        place("advancedrocketry:oxygenVent", x, Y, z);

        EnergyStore storedResp = energy(x, z);
        assertTrue("oxygenVent must expose CapabilityEnergy (RF consumer): "
                        + storedResp.raw(),
                storedResp.hasEnergy);
        assertTrue("tileClass should mention OxygenVent: " + storedResp.raw(),
                "TileOxygenVent".equals(storedResp.tileSimpleName()));

        String tickResp = ok(client().execute(
                "artest tile force-tick " + DIM + " " + x + " " + Y + " " + z + " 2"));
        assertTrue("oxygenVent force-tick must not error: " + tickResp,
                !Reply.of(tickResp).has("error"));
    }

    @Test
    public void pumpPlacesAndExposesFluidCap() throws Exception {
        int x = BASE_X + 16, z = BASE_Z;
        place("advancedrocketry:blockPump", x, Y, z);

        String fluidResp = ok(client().execute(
                "artest fluid stored " + DIM + " " + x + " " + Y + " " + z));
        // Pump implements IFluidHandler — the probe must reach it. Against the refusal itself,
        // as above: any other refusal used to read as success here.
        assertFalse("pump place silently dropped tile: " + fluidResp,
                Reply.of(fluidResp).refused());
    }

    @Test
    public void satelliteBuilderPlacesAndReportsCorrectTileClass() throws Exception {
        int x = BASE_X + 20, z = BASE_Z;
        place("advancedrocketry:satelliteBuilder", x, Y, z);

        // The builder is a heavy machine (RF consumer + assembly slots).
        // Pin its tileClass via the energy probe so a rename surfaces here
        // before any GUI test fails.
        // The reader refuses the `no tile entity` reply, which is what the not-found check stood for.
        EnergyStore storedResp = energy(x, z);
        assertTrue("tileClass should mention SatelliteBuilder: " + storedResp.raw(),
                "TileSatelliteBuilder".equals(storedResp.tileSimpleName()));
    }

    @Test
    public void virginAirPositionHasNoTileEntity() throws Exception {
        // Sanity: the test setup itself is honest — a virgin position
        // (no place call) must report "no tile entity" rather than
        // accidentally finding a leftover tile from a previous test.
        // Skip the place() helper because setBlockState(air->air) returns
        // false, which would trip the helper's placed=true assertion;
        // here we just want to assert the *initial* state.
        int x = BASE_X + 100, z = BASE_Z + 100;
        // LEFT RAW, and this is the one site that must be: the subject here IS the error shape.
        // `EnergyStore` refuses that reply — correctly, since every other site in this tier would
        // otherwise read it as a machine with no capability — so a reading of it cannot be the
        // thing being asserted.
        String stored = ok(client().execute(
                "artest energy stored " + DIM + " " + x + " " + Y + " " + z));
        // THIS refusal and not merely some refusal: the claim is that the block is empty, and a
        // probe answering `world not loaded` satisfied the substring form just as well.
        assertEquals("virgin position must not have a tile entity: " + stored,
                "no tile entity", Reply.of(stored).error());
    }
}
