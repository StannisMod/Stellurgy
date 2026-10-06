package dev.stannismod.stellurgy.test.trace;

import net.minecraft.tileentity.TileEntity;
import net.minecraft.util.EnumFacing;
import net.minecraft.util.ResourceLocation;
import net.minecraft.world.World;
import net.minecraftforge.common.capabilities.Capability;
import net.minecraftforge.common.capabilities.ICapabilityProvider;
import net.minecraftforge.event.AttachCapabilitiesEvent;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;

import dev.stannismod.stellurgy.api.capability.CapabilityDamageAware;
import dev.stannismod.stellurgy.api.damage.DamageOccurrence;
import dev.stannismod.stellurgy.api.damage.IDamageAware;

/**
 * Records the {@link DamageOccurrence}s delivered to units, by ATTACHING the capability to every tile
 * on a test server.
 *
 * <p>Attaching rather than implementing is the point: it is exactly the route a foreign mod takes to
 * make somebody else's machine damage-aware, so what this exercises is the shipped delivery path and
 * not a private one. Nothing in production carries {@code IDamageAware} yet — enrolling a real unit
 * means designing that unit's own consequence, which is its owner's decision — so without this the
 * interface would have no consumer and no test could tell whether it delivers.</p>
 *
 * <p>Each delivered occurrence becomes ONE {@code damage_occurrence} record in the server's
 * {@link ServerEventLog}, so a scenario reads what its own blow delivered as a window from a mark,
 * narrowed by position. Payload: {@code dim} (the occurrence's world, {@code null} without one),
 * {@code x} / {@code y} / {@code z}, {@code cause}, {@code kind}, {@code stageBefore},
 * {@code stageAfter}, {@code maxStage}, {@code spent}, {@code destroyed}, {@code ship},
 * {@code hasWorld}, {@code hasWhere}. The instrument {@code damage_occurrence_recorder} is declared on
 * every attach, whether or not the tile is ever struck.</p>
 *
 * <p>Stateless and subscribed as a CLASS, like {@link ServerEventRecorder}, which registers it: every
 * record goes into the log of the server the occurrence's world belongs to, so nothing here outlives
 * a server.</p>
 */
public final class DamageOccurrenceRecorder {

    static final String INSTRUMENT = "damage_occurrence_recorder";

    private DamageOccurrenceRecorder() {
    }

    @SubscribeEvent
    public static void onAttach(AttachCapabilitiesEvent<TileEntity> event) {
        if (CapabilityDamageAware.DAMAGE_AWARE == null) {
            return;
        }
        ServerEventLog log = ServerEventRecorder.logFor(
                event.getObject() == null ? null : event.getObject().getWorld());
        if (log != null) {
            log.noteInstrumentEntered(INSTRUMENT);
        }
        event.addCapability(new ResourceLocation("stellurgy", "test_damage_recorder"), new Provider());
    }

    static void record(DamageOccurrence o) {
        World world = o.getWorld();
        ServerEventLog log = ServerEventRecorder.logFor(world);
        if (log == null) {
            return;
        }
        log.noteInstrumentEntered(INSTRUMENT);
        World clock = world != null ? world : net.minecraftforge.common.DimensionManager.getWorld(0);
        log.record("server", clock == null ? 0L : clock.getTotalWorldTime(), "damage_occurrence",
                "\"dim\":" + (world == null ? "null" : String.valueOf(world.provider.getDimension()))
                        + ",\"x\":" + o.getPos().getX() + ",\"y\":" + o.getPos().getY()
                        + ",\"z\":" + o.getPos().getZ()
                        + ",\"cause\":\"" + o.getCause() + "\",\"kind\":"
                        + (o.getKind() == null ? "null" : "\"" + o.getKind() + "\"")
                        + ",\"stageBefore\":" + o.getStageBefore()
                        + ",\"stageAfter\":" + o.getStageAfter()
                        + ",\"maxStage\":" + o.getMaxStage()
                        + ",\"spent\":" + o.getBudgetSpent()
                        + ",\"destroyed\":" + o.isDestroyed()
                        + ",\"ship\":" + (o.getShipId() == null ? "null" : "\"" + o.getShipId() + "\"")
                        + ",\"hasWorld\":" + (world != null)
                        + ",\"hasWhere\":" + (o.getWhere() != null));
    }

    /** The provider half of the attachment; one listener per tile, holding nothing. */
    private static final class Provider implements ICapabilityProvider {
        private final IDamageAware listener = DamageOccurrenceRecorder::record;

        @Override
        public boolean hasCapability(Capability<?> capability, EnumFacing facing) {
            return capability == CapabilityDamageAware.DAMAGE_AWARE;
        }

        @Override
        @SuppressWarnings("unchecked")
        public <T> T getCapability(Capability<T> capability, EnumFacing facing) {
            return capability == CapabilityDamageAware.DAMAGE_AWARE ? (T) listener : null;
        }
    }
}
