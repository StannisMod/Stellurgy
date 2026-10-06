package dev.stannismod.stellurgy.test.unit;

import java.lang.reflect.Field;

import net.minecraft.util.math.BlockPos;
import net.minecraft.world.DimensionType;
import net.minecraft.world.World;
import net.minecraft.world.WorldProvider;
import net.minecraft.world.WorldServer;

import org.junit.Before;
import org.junit.BeforeClass;
import org.junit.Test;

import sun.misc.Unsafe;

import dev.stannismod.stellurgy.subsystem.network.ISubsystemCable;
import dev.stannismod.stellurgy.subsystem.network.ISubsystemSink;
import dev.stannismod.stellurgy.subsystem.network.ISubsystemSource;
import dev.stannismod.stellurgy.subsystem.network.SubsystemNetworkDomain;
import dev.stannismod.stellurgy.subsystem.network.SubsystemNetworkManager;
import dev.stannismod.stellurgy.test.MinecraftBootstrap;

import static org.junit.Assert.assertArrayEquals;

/**
 * How one network's solve splits a supply among its sinks: priority tiers in order, and within a
 * tier a max-min fair share that respects what the cables can carry.
 *
 * <p>Everything here is the test's own: a {@link SubsystemNetworkManager} it builds, a domain of its
 * own, and nodes that only count what they were given. The world is a bare instance whose one used
 * property is its dimension number — the solver asks a world nothing else — allocated without a
 * constructor because a real one needs a running server, which this subject does not.</p>
 */
public class SubsystemNetworkFairShareTest {

    /** This test's own domain, so nothing the mod registers can join its networks. */
    private final SubsystemNetworkDomain domain = new SubsystemNetworkDomain("FairShareTest") {
    };

    /** A world whose only answered question is its dimension. */
    private World world;

    @BeforeClass
    public static void bootstrap() {
        MinecraftBootstrap.ensure();
    }

    @Before
    public void bareWorld() throws Exception {
        Field theUnsafe = Unsafe.class.getDeclaredField("theUnsafe");
        theUnsafe.setAccessible(true);
        World bare = (World) ((Unsafe) theUnsafe.get(null)).allocateInstance(WorldServer.class);
        Field provider = World.class.getDeclaredField("provider");
        provider.setAccessible(true);
        provider.set(bare, new WorldProvider() {
            @Override
            public DimensionType getDimensionType() {
                return DimensionType.OVERWORLD;
            }
        });
        world = bare;
    }

    /**
     * Two sinks of one priority asking 100 each from a source of 100 get 50 each — not 100 and 0 in
     * whatever order a path search reached them.
     *
     * <p>red-witnessed: with {@code SubsystemNetworkManager#solve} at {@code maxFlow += shareTier(solver, superSource, superSink, sinkRefs, requestedBySink, tierSinks);}
     * replaced by opening every tier sink's edge to its request and running one plain max flow (the
     * shape it shipped with), this fails with "two equal sinks must split a scarce supply evenly
     * (west, east): arrays first differed at element [0]; expected:&lt;50&gt; but was:&lt;100&gt;"
     * (2026-10-06).</p>
     */
    @Test
    public void equalSinksSplitAScarceSupplyEvenly() throws Exception {
        SubsystemNetworkManager manager = new SubsystemNetworkManager();
        manager.register(new Supply(at(0), 100));
        Demand west = new Demand(at(-1), 100, 0);
        Demand east = new Demand(at(1), 100, 0);
        manager.register(west);
        manager.register(east);

        manager.tick(domain, world);

        assertArrayEquals("two equal sinks must split a scarce supply evenly (west, east)",
                new int[]{50, 50}, new int[]{west.received, east.received});
    }

    /**
     * A sink asking less than an even share gets what it asked, and the surplus goes to the other:
     * 30 and 100 from 100 is 30 and 70.
     *
     * <p>Plain max flow also answers 30/70 here — nothing but the request caps the small sink — so
     * this pins the share's cap, not the fairness the other cases pin.</p>
     *
     * <p>red-witnessed: with {@code SubsystemNetworkManager#shareTier} at {@code given[i] += Math.min(low, requested[i] - given[i]);}
     * raising every sink by the full {@code low}, uncapped by its request, this fails with "the small
     * request is met in full and the surplus goes to the other (small, large): arrays first differed
     * at element [0]; expected:&lt;30&gt; but was:&lt;70&gt;" (2026-10-06).</p>
     */
    @Test
    public void aSmallRequestIsMetAndItsSurplusGoesToTheOthers() throws Exception {
        SubsystemNetworkManager manager = new SubsystemNetworkManager();
        manager.register(new Supply(at(0), 100));
        Demand small = new Demand(at(-1), 30, 0);
        Demand large = new Demand(at(1), 100, 0);
        manager.register(small);
        manager.register(large);

        manager.tick(domain, world);

        assertArrayEquals("the small request is met in full and the surplus goes to the other (small, large)",
                new int[]{30, 70}, new int[]{small.received, large.received});
    }

    /**
     * The share is computed through the cables: a sink behind a cable carrying 10 gets 10, and the
     * other sink gets everything else — the tier still receives its whole max flow.
     *
     * <p>red-witnessed: with {@code SubsystemNetworkManager#shareTier} at {@code trialFlow(solver, from, to, refs, given, Collections.singletonList(i), requested, 1) > 0}
     * answered as always false — every sink retired at the first bottleneck — this fails with "...
     * (behind the cable, open): arrays first differed at element [1]; expected:&lt;90&gt; but
     * was:&lt;10&gt;" (2026-10-06).</p>
     *
     * <p>red-witnessed: with {@code SubsystemNetworkManager#solve} at {@code maxFlow += shareTier(solver, superSource, superSink, sinkRefs, requestedBySink, tierSinks);}
     * replaced by one plain max flow per tier (the shipped shape), this fails with "... arrays first
     * differed at element [0]; expected:&lt;10&gt; but was:&lt;0&gt;" (2026-10-06).</p>
     */
    @Test
    public void aCableLimitedSinkLeavesTheRestToTheOthers() throws Exception {
        SubsystemNetworkManager manager = new SubsystemNetworkManager();
        manager.register(new Supply(at(0), 100));
        manager.register(new Line(at(-1), 10));
        Demand behindTheCable = new Demand(at(-2), 100, 0);
        Demand open = new Demand(at(1), 100, 0);
        manager.register(behindTheCable);
        manager.register(open);

        manager.tick(domain, world);

        assertArrayEquals("the sink behind a 10-a-tick cable gets what the cable carries and the open one the"
                        + " rest (behind the cable, open)",
                new int[]{10, 90}, new int[]{behindTheCable.received, open.received});
    }

    /**
     * Tiers still go in priority order, and the fair share is within a tier: a priority-1 sink is
     * filled first, and the two priority-0 sinks split what is left.
     *
     * <p>red-witnessed: with {@code SubsystemNetworkManager#solve} at {@code TreeSet<Integer> priorityTiers = new TreeSet<>(Collections.reverseOrder());}
     * built in natural (ascending) order, this fails with "... (high, west, east): arrays first
     * differed at element [0]; expected:&lt;100&gt; but was:&lt;0&gt;" (2026-10-06).</p>
     *
     * <p>red-witnessed: with {@code SubsystemNetworkManager#solve} at {@code maxFlow += shareTier(solver, superSource, superSink, sinkRefs, requestedBySink, tierSinks);}
     * replaced by one plain max flow per tier (the shipped shape), this fails with "... arrays first
     * differed at element [1]; expected:&lt;25&gt; but was:&lt;50&gt;" (2026-10-06).</p>
     */
    @Test
    public void aHigherTierIsFilledBeforeTheLowerTierShares() throws Exception {
        SubsystemNetworkManager manager = new SubsystemNetworkManager();
        manager.register(new Supply(at(0), 150));
        Demand high = new Demand(new BlockPos(0, 1, 0), 100, 1);
        Demand west = new Demand(at(-1), 100, 0);
        Demand east = new Demand(at(1), 100, 0);
        manager.register(high);
        manager.register(west);
        manager.register(east);

        manager.tick(domain, world);

        assertArrayEquals("the high-priority sink is filled first and the lower tier splits what is left"
                        + " (high, west, east)",
                new int[]{100, 25, 25}, new int[]{high.received, west.received, east.received});
    }

    // ---- the test's own network

    private static BlockPos at(int x) {
        return new BlockPos(x, 0, 0);
    }

    private abstract class Node {
        private final BlockPos pos;

        Node(BlockPos pos) {
            this.pos = pos;
        }

        public SubsystemNetworkDomain getNetworkDomain() {
            return domain;
        }

        public World getNodeWorld() {
            return world;
        }

        public BlockPos getNodePos() {
            return pos;
        }
    }

    private final class Supply extends Node implements ISubsystemSource {
        private final int available;

        Supply(BlockPos pos, int available) {
            super(pos);
            this.available = available;
        }

        @Override
        public int getAvailable() {
            return available;
        }

        @Override
        public int extract(int amount) {
            return amount;
        }
    }

    private final class Demand extends Node implements ISubsystemSink {
        private final int requested;
        private final int priority;
        private int received;

        Demand(BlockPos pos, int requested, int priority) {
            super(pos);
            this.requested = requested;
            this.priority = priority;
        }

        @Override
        public int getRequested() {
            return requested;
        }

        @Override
        public int getFreeCapacity() {
            return requested;
        }

        @Override
        public int receive(int amount) {
            received += amount;
            return amount;
        }

        @Override
        public int getPriority() {
            return priority;
        }
    }

    private final class Line extends Node implements ISubsystemCable {
        private final int throughput;

        Line(BlockPos pos, int throughput) {
            super(pos);
            this.throughput = throughput;
        }

        @Override
        public int getThroughputPerTick() {
            return throughput;
        }

        @Override
        public void addTransferred(int amount) {
        }
    }
}
