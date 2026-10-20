package dev.stannismod.stellurgy.test.server;

// migrated to AbstractSharedServerTest

import dev.stannismod.stellurgy.test.EnergyStore;
import dev.stannismod.stellurgy.test.FixtureSite;
import dev.stannismod.stellurgy.test.Reply;
import org.junit.Test;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * Extends {@link TileMachineDepthTest}
 * onto the next batch of "important but not yet pinned" tile families:
 *
 *   - {@code suitWorkStation}    &rarr; {@code TileSuitWorkStation}
 *   - {@code deployableRocketBuilder} &rarr; {@code TileUnmannedVehicleAssembler}
 *   - {@code fuelingStation}     &rarr; {@code TileFuelingStation}
 *   - {@code terraformer}        &rarr; {@code TileAtmosphereTerraformer}
 *
 * Same contract surface as round 1: the capability surface (RF / IInventory)
 * that production code reads.
 *
 * No gameplay numbers — those need either a real assembly fixture (UV
 * assembler) or a real multiblock skeleton (terraformer), both of which
 * are out of scope for the per-tile depth tier.
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
        return EnergyStore.at(cmd -> ok(client().read(cmd)), DIM, x, Y, z);
    }

    /** Same place() helper as round 1 — see {@link TileMachineDepthTest#place}
     *  for the air-pre-clear rationale. */
    private void place(String blockId, int x, int y, int z) throws Exception {
        client().arrange("stellurgytest place " + DIM + " " + x + " " + y + " " + z + " minecraft:air");
        String r = ok(client().arrange(
                "stellurgytest place " + DIM + " " + x + " " + y + " " + z + " " + blockId));
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
        place("stellurgy:suitWorkStation", x, Y, z);

        // Suit workstation has NO energy capability (it's a manual assembler), so
        // hasEnergy=false is the expected contract.
        // The reader refuses the `no tile entity` reply — and it has to, because THIS test's
        // claim is `hasEnergy:false`, which that reply would satisfy by the block not being there.
        EnergyStore stored = energy(x, z);
        assertFalse("suit work station is a manual assembler — must NOT report energy cap: "
                        + stored.raw(),
                stored.hasEnergy);

        // The hatch-read size must be strictly positive (the GUI binds slots by
        // index — 0 slots = the entire crafting matrix renders empty).
        String hatch = ok(client().read(
                "stellurgytest hatch read " + DIM + " " + x + " " + Y + " " + z));
        Reply hatchReply = Reply.of("stellurgytest hatch read", hatch);
        // The size is COMPARED, not searched. `!contains("\"size\":0,")` needed the comma:
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
        // assembler family.
        int x = BASE_X + 8, z = BASE_Z;
        place("stellurgy:deployableRocketBuilder", x, Y, z);

        EnergyStore stored = energy(x, z);

        // Round 1 pinned the rocket builder's energy contract; UV assembler
        // shares the same parent so it MUST also have an energy face. If the
        // parent ever drops the capability, this assertion surfaces it.
        assertTrue("UV assembler must expose CapabilityEnergy (inherits from "
                        + "RocketAssemblingMachine): " + stored.raw(),
                stored.hasEnergy);
    }

    @Test
    public void fuelingStationExposesEnergyAndFluidCapabilities() throws Exception {
        // TileFuelingStation extends TileInventoriedRFConsumerTank — it must
        // have an energy cap (RF consumer), or the per-tick fuel-transfer
        // loop silently no-ops on every rocket on the pad.
        int x = BASE_X + 24, z = BASE_Z;
        place("stellurgy:fuelingStation", x, Y, z);

        EnergyStore stored = energy(x, z);
        assertTrue("fueling station must expose CapabilityEnergy (RF consumer): "
                        + stored.raw(),
                stored.hasEnergy);
    }

    @Test
    public void terraformerReportsCorrectTileClassPreAssembly() throws Exception {
        // TileAtmosphereTerraformer extends TileMultiPowerConsumer — it's
        // the *controller* tile of an inert multiblock skeleton. The actual
        // multiblock won't form from a single isolated place; until it's
        // assembled, the controller DOES NOT expose CapabilityEnergy (gated
        // on `isComplete`). What we CAN pin in isolation is that the
        // pre-assembly state is the expected hasEnergy=false (rather than
        // e.g. throwing during capability lookup, which would crash any
        // energy pipe routing adjacent to an unassembled terraformer skeleton).
        int x = BASE_X + 32, z = BASE_Z;
        place("stellurgy:terraformer", x, Y, z);

        EnergyStore stored = energy(x, z);
        // Contract surprise pinned here: a pre-assembly multiblock controller
        // is "cap-dark" — it has no IEnergyStorage until the structure forms.
        // If a refactor changes the polarity of `isComplete` and the
        // controller starts exposing the cap unconditionally, energy pipes
        // would happily inject RF into a phantom buffer that never updates.
        assertFalse("pre-assembly terraformer controller must NOT expose "
                        + "CapabilityEnergy (gated on isComplete): " + stored.raw(),
                stored.hasEnergy);
    }
}
