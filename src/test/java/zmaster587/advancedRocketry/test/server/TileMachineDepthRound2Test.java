package zmaster587.advancedRocketry.test.server;

// migrated to AbstractSharedServerTest
import zmaster587.advancedRocketry.test.Reply;
import org.junit.Test;

import zmaster587.advancedRocketry.test.EnergyStore;
import zmaster587.advancedRocketry.test.FixtureSite;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * Extends {@link TileMachineDepthTest}
 * onto the next batch of "important but not yet pinned" tile families:
 *
 *   - {@code suitWorkStation}    &rarr; {@code TileSuitWorkStation}
 *   - {@code deployableRocketBuilder} &rarr; {@code TileUnmannedVehicleAssembler}
 *   - {@code landingPad}         &rarr; {@code TileLandingPad}
 *   - {@code fuelingStation}     &rarr; {@code TileFuelingStation}
 *   - {@code terraformer}        &rarr; {@code TileAtmosphereTerraformer}
 *
 * Same contract surface as round 1: probe the registry name resolves to the
 * expected tile class, probe the capability surface (RF / IInventory / fluid)
 * that production code reads, and where the tile is ITickable, drive
 * {@code force-tick} once to prove the update loop doesn't NPE.
 *
 * No gameplay numbers — those need either a real assembly fixture (UV
 * assembler) or a real multiblock skeleton (terraformer), both of which
 * are out of scope for the per-tile depth tier. Round 2 just nails down
 * "the tile exists, exposes its declared capabilities, and ticks
 * without crashing".
 *
 * Spread positions far enough apart from round 1's {@code BASE_X / BASE_Z}
 * (200,200 + offsets up to 100) that JVM-shared test state can't leak.
 */
public class TileMachineDepthRound2Test extends AbstractSharedServerTest {

    private static final int DIM = 0;
    private static final int BASE_X = 400;
    private static final int BASE_Z = 400;
    private static final int Y = FixtureSite.OPEN_AIR_Y;

    private static String ok(java.util.List<String> resp) {
        return String.join("\n", resp);
    }

    /** What the Forge energy capability at one block reports — refusing a block that is not there. */
    private EnergyStore energy(int x, int z) throws Exception {
        return EnergyStore.at(cmd -> ok(client().execute(cmd)), DIM, x, Y, z);
    }

    /** Same place() helper as round 1 — see {@link TileMachineDepthTest#place}
     *  for the air-pre-clear rationale. */
    private void place(String blockId, int x, int y, int z) throws Exception {
        client().execute("artest place " + DIM + " " + x + " " + y + " " + z + " minecraft:air");
        String r = ok(client().execute(
                "artest place " + DIM + " " + x + " " + y + " " + z + " " + blockId));
        assertTrue("place(" + blockId + ") at " + x + "," + y + "," + z + " failed: " + r,
                Reply.of(r).bool("placed"));
    }

    @Test
    public void suitWorkStationExposesInventoryAndCorrectTileClass() throws Exception {
        // TileSuitWorkStation: bare TileEntity + IInventory + IModularInventory.
        // Critical because EVA-suit assembly is gated entirely by its slot map;
        // an unannounced rename of the underlying class would break the suit
        // GUI silently (no recipe error — just empty output slot forever).
        int x = BASE_X, z = BASE_Z;
        place("advancedrocketry:suitWorkStation", x, Y, z);

        // The energy probe always reports tileClass even when the tile lacks
        // CapabilityEnergy — use it to pin the FQN. Suit workstation has NO
        // energy capability (it's a manual assembler), so hasEnergy=false is
        // the expected contract.
        // The reader refuses the `no tile entity` reply, which is what the presence check stood
        // for — and it has to, because THIS test's claim is `hasEnergy:false`, which that reply
        // would satisfy by the block not being there at all.
        EnergyStore stored = energy(x, z);
        assertTrue("tileClass should mention TileSuitWorkStation: " + stored.raw(),
                "TileSuitWorkStation".equals(stored.tileSimpleName()));
        assertFalse("suit work station is a manual assembler — must NOT report energy cap: "
                        + stored.raw(),
                stored.hasEnergy);

        // The hatch-read probe is the IInventory contract gate; size must be
        // strictly positive (the GUI binds slots by index — 0 slots = the
        // entire crafting matrix renders empty).
        String hatch = ok(client().execute(
                "artest hatch read " + DIM + " " + x + " " + Y + " " + z));
        // Against the refusal rather than against two of its possible texts: the pair of
        // substrings passed for every OTHER refusal the probe can write.
        Reply hatchReply = Reply.of("artest hatch read", hatch);
        assertFalse("suit work station must be IInventory-accessible: " + hatch,
                hatchReply.refused());
        // And the size is COMPARED, not searched. `!contains("\"size\":0,")` needed the comma:
        // where `size` is the object's last field the needle cannot match, so a zero-slot
        // inventory — the defect this line exists to catch — passed it.
        assertTrue("suit work station hatch-read should expose size>0: " + hatch,
                hatchReply.integer("size") > 0);
    }

    @Test
    public void unmannedVehicleAssemblerReportsAssemblerLineageAndIsTickable() throws Exception {
        // TileUnmannedVehicleAssembler extends TileRocketAssemblingMachine —
        // shares all the rocket-builder plumbing (assembly slots, scan logic,
        // status flags). The capability-exposed energy face should mirror the
        // assembler family. Pin lineage via tileClass; pin tickability via
        // force-tick.
        int x = BASE_X + 8, z = BASE_Z;
        place("advancedrocketry:deployableRocketBuilder", x, Y, z);

        EnergyStore stored = energy(x, z);
        assertTrue("tileClass should mention TileUnmannedVehicleAssembler: " + stored.raw(),
                "TileUnmannedVehicleAssembler".equals(stored.tileSimpleName()));

        // Round 1 pinned the rocket builder's energy contract; UV assembler
        // shares the same parent so it MUST also have an energy face. If the
        // parent ever drops the capability, this assertion surfaces it.
        assertTrue("UV assembler must expose CapabilityEnergy (inherits from "
                        + "RocketAssemblingMachine): " + stored.raw(),
                stored.hasEnergy);

        String tickResp = ok(client().execute(
                "artest tile force-tick " + DIM + " " + x + " " + Y + " " + z + " 3"));
        // The assembler family is ITickable; force-tick must succeed and not
        // crash on a not-yet-scanned (empty) build area.
        assertTrue("UV assembler force-tick must not error: " + tickResp,
                Reply.of(tickResp).ok());
    }

    @Test
    public void landingPadIsInventoryHatchSubclass() throws Exception {
        // TileLandingPad extends TileInventoryHatch; it's a passive marker
        // tile whose IInventory slots store fuel-related items for rocket
        // landings. A capability regression here would silently break
        // station-to-planet rocket return: the rocket would no longer find
        // the pad's "is this an AR landing pad" sentinel.
        int x = BASE_X + 16, z = BASE_Z;
        place("advancedrocketry:landingPad", x, Y, z);

        EnergyStore stored = energy(x, z);
        assertTrue("tileClass should mention TileLandingPad: " + stored.raw(),
                "TileLandingPad".equals(stored.tileSimpleName()));

        // TileInventoryHatch implements IInventory — the hatch-read probe
        // discriminates by IInventory, so its success here pins the
        // parent-class contract surface.
        String hatch = ok(client().execute(
                "artest hatch read " + DIM + " " + x + " " + Y + " " + z));
        assertFalse("landing pad must be IInventory-accessible (extends "
                        + "TileInventoryHatch): " + hatch,
                Reply.of("artest hatch read", hatch).refused());
    }

    @Test
    public void fuelingStationExposesEnergyAndFluidCapabilities() throws Exception {
        // TileFuelingStation extends TileInventoriedRFConsumerTank — it must
        // have BOTH an energy cap (RF consumer) and a fluid cap (tank that
        // accepts rocket fuel). These two together are what make a fueling
        // station functional; lose either and the per-tick fuel-transfer
        // loop silently no-ops on every rocket on the pad.
        int x = BASE_X + 24, z = BASE_Z;
        place("advancedrocketry:fuelingStation", x, Y, z);

        EnergyStore stored = energy(x, z);
        assertTrue("tileClass should mention TileFuelingStation: " + stored.raw(),
                "TileFuelingStation".equals(stored.tileSimpleName()));
        assertTrue("fueling station must expose CapabilityEnergy (RF consumer): "
                        + stored.raw(),
                stored.hasEnergy);

        // The fluid probe surfaces IFluidHandler presence; its error path is
        // "no tile entity" / "tile has no IFluidHandler". Anything else
        // (whether or not the tank is empty) confirms the cap survives.
        String fluid = ok(client().execute(
                "artest fluid stored " + DIM + " " + x + " " + Y + " " + z));
        assertFalse("fueling station tank cap silently dropped: " + fluid,
                Reply.of("artest fluid stored", fluid).refused());
    }

    @Test
    public void terraformerReportsCorrectTileClassPreAssembly() throws Exception {
        // TileAtmosphereTerraformer extends TileMultiPowerConsumer — it's
        // the *controller* tile of an inert multiblock skeleton. The actual
        // multiblock won't form from a single isolated place; until it's
        // assembled, the controller DOES NOT expose CapabilityEnergy (gated
        // on `isComplete`). What we CAN pin in isolation is the
        // controller's tileClass + that the pre-assembly state is the
        // expected hasEnergy=false (rather than e.g. throwing during
        // capability lookup, which would crash any energy pipe routing
        // adjacent to an unassembled terraformer skeleton).
        int x = BASE_X + 32, z = BASE_Z;
        place("advancedrocketry:terraformer", x, Y, z);

        EnergyStore stored = energy(x, z);
        assertTrue("tileClass should mention TileAtmosphereTerraformer: " + stored.raw(),
                "TileAtmosphereTerraformer".equals(stored.tileSimpleName()));
        // Contract surprise pinned here: a pre-assembly multiblock controller
        // is "cap-dark" — it has no IEnergyStorage until the structure forms.
        // If a refactor changes the polarity of `isComplete` and the
        // controller starts exposing the cap unconditionally, energy pipes
        // would happily inject RF into a phantom buffer that never updates.
        assertFalse("pre-assembly terraformer controller must NOT expose "
                        + "CapabilityEnergy (gated on isComplete): " + stored.raw(),
                stored.hasEnergy);
    }

    @Test
    public void terraformerIsolatedControllerForceTickIsSafe() throws Exception {
        // Companion to the previous test: terraformer multiblocks expose a
        // tick loop on the controller; for an unassembled skeleton the loop
        // MUST early-exit cleanly (NPE on the `isComplete` check would have
        // shipped a runtime crash to every modpack player whose terraformer
        // partially-broke). force-tick a few ticks and assert no exception.
        int x = BASE_X + 32, z = BASE_Z + 8;
        place("advancedrocketry:terraformer", x, Y, z);

        String tickResp = ok(client().execute(
                "artest tile force-tick " + DIM + " " + x + " " + Y + " " + z + " 3"));
        // Either the tile is ITickable and ticks cleanly, OR the probe
        // reports "tile not ITickable" (some libVulpes multiblock controllers
        // delegate ticking to the host structure). Either contract is fine —
        // but a thrown exception is NOT.
        Reply tick = Reply.of("artest tile force-tick", tickResp);
        assertTrue("terraformer force-tick threw or hard-errored: " + tickResp,
                tick.ok() || tick.refusedWith("tile not ITickable"));
    }
}
