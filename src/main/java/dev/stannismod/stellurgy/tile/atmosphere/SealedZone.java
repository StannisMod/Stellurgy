package dev.stannismod.stellurgy.tile.atmosphere;

import dev.stannismod.stellurgy.api.StellurgyConfiguration;
import dev.stannismod.stellurgy.api.util.IBlobHandler;
import dev.stannismod.stellurgy.atmosphere.AirState;
import dev.stannismod.stellurgy.atmosphere.AtmosphereHandler;
import dev.stannismod.stellurgy.libvulpes.util.HashedBlockPosition;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.util.math.BlockPos;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/**
 * The zone one block holds: whether its room is closed, whether its hull has opened since, and what
 * the room holds. Every block that defines a zone keeps one — the oxygen vent and the ventilation
 * port — so a zone lives by one rule whichever machine anchors it.
 *
 * <p><b>A zone's air is live exactly while the zone is sealed.</b> Nothing here asks whether anything
 * is SUPPLYING the room: a supply that stops is a supply that stopped, and the crew go on breathing
 * what is there.</p>
 *
 * <p><b>A new zone holds the air that was in its place.</b> When it is registered and nothing was
 * saved for it, its gases are the air around its anchor — a planet's outdoors, vacuum in space —
 * never a breathable atmosphere out of nothing. Its published atmosphere is derived from those gases
 * from the start, because the flood fill that seals it runs off-thread and the room would otherwise
 * read as pressurised while it is still being measured.</p>
 *
 * <p>With {@code lifeSupportZones} off a zone has no gases worth keeping and this does not touch them:
 * the anchor declares the room's atmosphere itself, as it did before zones had contents.</p>
 */
final class SealedZone {

    /** How often a zone that is not sealed re-runs the flood fill that decides whether it is. */
    static final int SEAL_CHECK_TICKS = 100;
    /** How often a breached zone lets its air out. */
    private static final int VENT_TICKS = 20;

    private final IBlobHandler owner;
    private final Runnable changed;

    private boolean sealed;
    /**
     * This zone has held a seal at least once since it loaded. It is what tells "the room opened"
     * apart from "the room is not built yet": both look like no seal, no zone, air in hand.
     */
    private boolean everSealed;
    /**
     * The hull is OPEN — set only where the room is actually gone: a seal that held and then found its
     * zone empty, or a seal check that ran and failed. Not persisted: after a load the check runs again
     * and answers for itself.
     */
    private boolean breached;
    /** Starts due, so an anchor placed into a finished room answers on its first tick. */
    private int ticksSinceSealCheck = SEAL_CHECK_TICKS;
    /**
     * A seal check started an off-thread flood fill that has not answered. Until it does the zone is
     * neither sealed nor breached, and the check asks again every tick rather than every interval.
     */
    private boolean awaitingFill;
    private int ticksSinceVenting;
    /** Gases read back from the save, waiting for the zone to be registered. */
    @Nullable
    private AirState pendingAirState;
    private boolean registered;

    /**
     * @param owner   the block whose flood fill this zone is
     * @param changed called whenever what this zone would save has changed, so the owner is saved
     */
    SealedZone(@Nonnull IBlobHandler owner, @Nonnull Runnable changed) {
        this.owner = owner;
        this.changed = changed;
    }

    boolean isSealed() {
        return sealed;
    }

    boolean isRegistered() {
        return registered;
    }

    /** Register the zone with the world's handler, giving it its air. Called on the owner's first tick. */
    void register(@Nonnull AtmosphereHandler handler, @Nonnull BlockPos anchor) {
        handler.registerBlob(owner, anchor);
        if (StellurgyConfiguration.getCurrentConfig().lifeSupportZones) {
            AirState air = pendingAirState != null ? pendingAirState : airAround(handler, anchor);
            handler.setAirState(owner, air);
            handler.setAtmosphereType(owner, air.deriveAtmosphere());
        } else if (pendingAirState != null) {
            handler.setAirState(owner, pendingAirState);
        }
        pendingAirState = null;
        sealed = false;
        registered = true;
    }

    private static AirState airAround(AtmosphereHandler handler, BlockPos anchor) {
        AirState around = handler.getAirAround(anchor);
        return around == null ? AirState.vacuum() : around.copy();
    }

    /**
     * One tick of keeping the zone.
     *
     * @param canHold whether the owner can hold a zone this tick — switched on and powered
     * @return whether the seal changed this tick
     */
    boolean tick(@Nonnull AtmosphereHandler handler, @Nonnull BlockPos anchor, boolean canHold) {
        boolean wasSealed = sealed;
        everSealed |= sealed;
        int size = handler.getBlobSize(owner);
        // Both orders happen: breaking the hull runs a block update that can empty the zone before
        // the owner's own tick comes round, so "sealed" alone misses a player opening a door.
        if (everSealed && size == 0) {
            breached = true;
        }
        if (sealed && size == 0) {
            sealed = false;
        }
        if (sealed && !canHold) {
            drop(handler);
        } else if (!sealed && canHold && ++ticksSinceSealCheck >= SEAL_CHECK_TICKS) {
            ticksSinceSealCheck = 0;
            checkSeal(handler, anchor);
            if (awaitingFill) {
                ticksSinceSealCheck = SEAL_CHECK_TICKS;
            }
        }
        ventBreachedAir(handler);
        return wasSealed != sealed;
    }

    /**
     * Run the flood fill now and take its answer. The fill may run off-thread, in which case the
     * answer is "not yet": the zone is left neither sealed nor breached, and a later check reads what
     * that fill found instead of starting another.
     */
    boolean checkSeal(@Nonnull AtmosphereHandler handler, @Nonnull BlockPos anchor) {
        Boolean answered = null;
        if (awaitingFill) {
            if (handler.isFilling(owner)) {
                return false;
            }
            awaitingFill = false;
            // Null when the zone was cleared after the fill started: that answer is about a zone that
            // is gone, so the check starts over below rather than taking it.
            answered = handler.lastFillClosed(owner);
        }
        boolean ok;
        if (answered != null) {
            ok = answered;
        } else {
            ok = handler.addBlock(owner, new HashedBlockPosition(anchor));
            if (!ok && handler.isFilling(owner)) {
                // Taking this as "open" let a reloaded room out to space until the next check.
                awaitingFill = true;
                return false;
            }
        }
        // The fill ANSWERED, so its answer stands either way: sealed means the hull closed, failed
        // means it is open.
        breached = !ok;
        everSealed |= ok;
        sealed = ok;
        if (ok && StellurgyConfiguration.getCurrentConfig().lifeSupportZones) {
            handler.refreshDerivedAtmosphere(owner);
        }
        return ok;
    }

    /** The owner cannot hold the zone (switched off, browning out): the room stops being one. */
    void drop(@Nonnull AtmosphereHandler handler) {
        handler.clearBlob(owner);
        sealed = false;
        awaitingFill = false;
    }

    /**
     * A breached zone loses its air to space instead of losing it to bookkeeping. Clearing the zone
     * empties its cells and keeps its gases, so the air outlives the room exactly as long as it takes
     * to escape. A hole needs no electricity, so this runs whatever the owner's power. All gases go
     * together: vacuum does not sort them.
     */
    private void ventBreachedAir(AtmosphereHandler handler) {
        if (!breached || sealed || !StellurgyConfiguration.getCurrentConfig().lifeSupportZones) {
            return;
        }
        long ratePerSecond = StellurgyConfiguration.getCurrentConfig().lifeSupportBreachVentRate;
        if (ratePerSecond <= 0L || ++ticksSinceVenting < VENT_TICKS) {
            return;
        }
        ticksSinceVenting = 0;
        AirState air = handler.getAirState(owner);
        if (air == null || air.getTotalPressure() <= 0L) {
            return;
        }
        air.drawNitrogen(ratePerSecond);
        air.drawOxygen(ratePerSecond);
        air.drawCarbonDioxide(ratePerSecond);
        changed.run();
    }

    /** The zone's gases: the live ones once registered, otherwise what waits to be restored. */
    @Nullable
    AirState air(@Nullable AtmosphereHandler handler) {
        if (handler != null) {
            AirState live = handler.getAirState(owner);
            if (live != null) {
                return live;
            }
        }
        return pendingAirState;
    }

    void writeToNBT(@Nonnull NBTTagCompound nbt, @Nullable AtmosphereHandler handler) {
        AirState air = air(handler);
        if (air != null) {
            NBTTagCompound airTag = new NBTTagCompound();
            air.writeToNBT(airTag);
            nbt.setTag("airState", airTag);
        }
    }

    /**
     * The room's shape is rebuilt from the world, but what was IN it is not derivable from blocks — a
     * cabin the crew had half used would come back full. Held until the zone is registered.
     */
    void readFromNBT(@Nonnull NBTTagCompound nbt) {
        pendingAirState = nbt.hasKey("airState") ? AirState.readFromNBT(nbt.getCompoundTag("airState")) : null;
    }
}
