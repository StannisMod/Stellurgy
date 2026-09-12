package zmaster587.advancedRocketry.test.mixin;

import net.minecraft.tileentity.TileEntity;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import zmaster587.advancedRocketry.test.trace.TestTrace;
import zmaster587.advancedRocketry.tile.TileAdvancedFlightComputer;

/**
 * Every flight computer a world is actually ITERATING when it ticks its tile entities, with the
 * loop's own skip conditions evaluated on the loop's own thread.
 *
 * <h2>The question no probe can answer</h2>
 *
 * <p>"This computer is in the ticking list, its chunk is loaded, it is inside the border, it is not
 * invalid — and it has never been ticked" is not a state the game can be in, yet it is what a probe
 * reports. The probe is why: resolving a computer by position walks the shipyard for its block and
 * then asks the world for the tile, and BOTH of those load the chunk and register its tiles for
 * ticking. So every condition such a reply reports is a condition the reply may have just created,
 * and a {@code true} from it proves nothing about the moment the loop last ran.</p>
 *
 * <p>This reads the same conditions from INSIDE that tick, off objects the list already holds. It
 * resolves nothing and loads nothing: {@code isBlockLoaded} asks for a chunk that is already there
 * and never provides one, and the border is a predicate on coordinates. What it reports is therefore
 * what the loop itself saw.</p>
 *
 * <h2>What it says and what it is silent about</h2>
 *
 * <p>{@code flight_computers_in_tick_loop} carries one record per sampled tick per world: how many
 * tile entities that world is iterating in total, and one entry per flight computer among them —
 * its position, and the three conditions that decide whether the loop calls its {@code update}.
 * A computer that is ABSENT from the record is absent from the list, which is the single most
 * useful reading here and the one that cannot be had from outside.</p>
 *
 * <p>A world iterating NO flight computer records that too, as an empty {@code computers} array
 * beside its total — deliberately, because "this world has none in its list" is the reading this
 * seam exists to produce and it must not arrive as silence. SILENT about WHY a computer is missing:
 * being dropped from the list and never having been added look identical here.</p>
 *
 * <h2>No records at all is the loudest reading of the three</h2>
 *
 * <p>This injects into {@code World.updateEntities}, and {@code WorldServer} overrides it: with no
 * player and no persistent chunk in that world, it returns BEFORE calling {@code super} once
 * {@code updateEntityTick} passes 300 — fifteen seconds. So a world that has gone quiet under that
 * rule never enters this method at all, and the instrument's own absence from the log is the
 * finding: not "no computer was in the list", but "this world stopped ticking anything". The
 * instrument is declared on every sampled pass so that the three cases stay apart — the name absent
 * from {@code instruments} means the seam never loaded, the name present with no record means the
 * world stopped, and a record with an empty array means the list genuinely holds no computer.</p>
 *
 * <p>Sampled, not per tick: the list is walked once every {@value #EVERY_N_TICKS} calls per world,
 * because this scans a list that holds every tickable tile in the world and the question is a
 * standing state rather than an event.</p>
 */
@Mixin(World.class)
public abstract class MixinWorldFlightComputerTickCensus {

    private static final String INSTRUMENT = "flight_computer_tick_loop_census";

    /** How many of this world's tile-entity passes are sampled: one in this many. */
    private static final int EVERY_N_TICKS = 20;

    /** This world's own call counter, so two worlds do not share a phase. */
    @Unique
    private int arTest$passes = 0;

    @Inject(method = "updateEntities", at = @At("RETURN"))
    private void arTest$flightComputersInTickLoop(CallbackInfo ci) {
        World self = (World) (Object) this;
        if (self.isRemote) {
            return; // the control loop is server-side; a client's list answers a different question
        }
        if (arTest$passes++ % EVERY_N_TICKS != 0) {
            return;
        }
        // DECLARED BEFORE THE SCAN, and before any early return below it. The whole value of this
        // seam is that an ABSENT computer is a reading — and "no world iterated one" is
        // indistinguishable from "this mixin never loaded" unless the instrument announces itself on
        // every pass it actually runs. The first version declared it only when it had something to
        // say, which made its silence unreadable in exactly the case it was built for.
        TestTrace.instrumentHere(INSTRUMENT);
        StringBuilder computers = new StringBuilder();
        int found = 0;
        // The list is iterated by index rather than by iterator: this runs at the RETURN of the very
        // method that iterates it, and handing vanilla's own list a second iterator is how a tick
        // loop acquires a ConcurrentModificationException it did not have before.
        for (int i = 0; i < self.tickableTileEntities.size(); i++) {
            TileEntity tile = self.tickableTileEntities.get(i);
            if (!(tile instanceof TileAdvancedFlightComputer)) {
                continue;
            }
            BlockPos pos = tile.getPos();
            if (found++ > 0) {
                computers.append(',');
            }
            computers.append("{\"pos\":\"").append(pos.getX()).append(',').append(pos.getY())
                    .append(',').append(pos.getZ()).append('"')
                    .append(",\"identity\":").append(System.identityHashCode(tile))
                    .append(",\"invalid\":").append(tile.isInvalid())
                    .append(",\"hasWorld\":").append(tile.hasWorld())
                    // The loop's own two, read the loop's own way — allowEmpty false included.
                    .append(",\"blockLoaded\":").append(self.isBlockLoaded(pos, false))
                    .append(",\"inBorder\":").append(self.getWorldBorder().contains(pos))
                    .append('}');
        }
        TestTrace.recordServer("flight_computers_in_tick_loop",
                "\"dim\":" + self.provider.getDimension()
                        + ",\"tickables\":" + self.tickableTileEntities.size()
                        + ",\"computers\":[" + computers + "]");
    }
}
