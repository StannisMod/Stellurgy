package dev.stannismod.stellurgy.subsystem.heat;

import dev.stannismod.stellurgy.api.StellurgyConfiguration;
import dev.stannismod.stellurgy.api.capability.IHeatEmitter;

/**
 * The heat a machine's work leaves behind, as a component a machine can hold.
 *
 * <p><b>This is the ship thermal system's supply side, and for a long time it did not exist.</b> The
 * whole subsystem — pipes, accumulators, radiators, the chiller, the emergency dump, the melting
 * hull, the drive's refusal — is machinery for moving heat somewhere, and nothing on a ship made any.
 * A single life-support machine hand-implemented the accrual, so every test injected a charge
 * directly and a real ship's loop sat at ambient forever, with every rung of the failure ladder
 * unreachable. A subsystem with one supplier, and that supplier not one of the things a player thinks
 * of as hot, is a subsystem that has not been connected to the game.
 *
 * <p>It is a COMPONENT rather than a base class on purpose: a machine already has a parent, and
 * whether it makes waste heat is not what it is. Holding one of these and forwarding two methods is
 * the whole contract.
 *
 * <p>The buffer is deliberately shallow — about a second of the largest draw since a loop last
 * collected. Heat that no loop comes
 * to collect is heat that went into the air around the machine, which is what happens on a planet
 * where nobody built a coolant loop, and it is why a base needs no thermal build at all.
 */
public final class WasteHeat implements IHeatEmitter {

    /** How many ticks of un-collected production the buffer keeps before the rest is simply lost. */
    private static final int BUFFER_TICKS = 20;

    private int pending;

    /**
     * Record energy a machine has just spent. A share of it comes back as heat a loop can pick up.
     *
     * <p>Derived from what was ACTUALLY spent rather than from the machine's rating, so a machine
     * running at a tenth of its rate heats a ship a tenth as fast — the same relation its power cost
     * already has, and the reason an idle ship is a cool one.
     *
     * <p>A spend bounds only what IT adds, never what is already banked. A machine may pay more than
     * once before a loop collects — a plant regenerates and then pays a port's upkeep in the same
     * tick — and sizing the whole buffer by the latest payment let a 1-FE upkeep discard the
     * regeneration's heat. Spending nothing makes no heat and destroys none.
     */
    public void spend(int energySpent) {
        if (!HeatNetwork.enabled()) {
            pending = 0;
            return;
        }
        if (energySpent <= 0)
            return;
        int fraction = Math.max(0, StellurgyConfiguration.getCurrentConfig().shipHeatWasteFraction);
        long made = (long) energySpent * fraction / 1000L;
        long cap = made * BUFFER_TICKS;
        if (pending < cap)
            pending = (int) Math.min(cap, pending + made);
    }

    @Override
    public int getPendingHeat() {
        return pending;
    }

    @Override
    public int takeHeat(int amount) {
        int taken = Math.max(0, Math.min(amount, pending));
        pending -= taken;
        return taken;
    }
}
