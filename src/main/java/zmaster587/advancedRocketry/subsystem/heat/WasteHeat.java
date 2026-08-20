package zmaster587.advancedRocketry.subsystem.heat;

import zmaster587.advancedRocketry.api.ARConfiguration;
import zmaster587.advancedRocketry.api.capability.IHeatEmitter;

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
 * <p>The buffer is deliberately shallow — about a second of the current draw. Heat that no loop comes
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
     */
    public void spend(int energySpent) {
        if (energySpent <= 0 || !HeatNetwork.enabled()) {
            pending = 0;
            return;
        }
        int fraction = Math.max(0, ARConfiguration.getCurrentConfig().shipHeatWasteFraction);
        long made = (long) energySpent * fraction / 1000L;
        long cap = (long) energySpent * fraction / 1000L * BUFFER_TICKS;
        pending = (int) Math.max(0L, Math.min(cap, pending + made));
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
