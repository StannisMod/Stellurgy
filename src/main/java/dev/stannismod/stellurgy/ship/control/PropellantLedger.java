package dev.stannismod.stellurgy.ship.control;

import java.util.HashMap;
import java.util.Map;
import java.util.TreeMap;

/**
 * One craft's account of its propellant between the physics step that EJECTS it and the game tick that
 * DRAINS it out of the holders aboard (C10 STAT-39, STAT-40).
 *
 * <p>Two threads meet here. The physics thread asks for a {@link #budget} at the top of a step and
 * {@link #book books} what the step's command ejected; the game thread, once per tick, takes what is
 * owed out of the holders as whole millibuckets, and {@link #settle settles} what it drained together
 * with what the holders now hold. The unit of publication is the pair (pool aboard, mass ejected but not
 * yet drained), and a budget is always the first less the second — so mass a step already ejected is
 * never offered to the next, and mass taken out of a holder is never counted twice. Every method holds
 * the ledger's lock; the four are the only readers and writers of its maps.</p>
 *
 * <p>Durable state it is not: what is owed at an unload is under one tick's ejection plus the fraction of
 * a millibucket per fluid, and it is lost with the instance (C10 STAT-40 states the bound).</p>
 */
public final class PropellantLedger {

    private final Map<String, Double> aboardKg = new HashMap<>();
    private final Map<String, Double> undrainedKg = new HashMap<>();
    private boolean metered;

    /** Consumption switched off: every budget is unmetered, nothing is booked, and nothing is owed. */
    public synchronized void unmetered() {
        metered = false;
        aboardKg.clear();
        undrainedKg.clear();
    }

    /** What the next step may eject: per fluid, the pool aboard less what is ejected and not yet drained. */
    public synchronized PropellantBudget budget() {
        if (!metered) {
            return PropellantBudget.unmetered();
        }
        Map<String, Double> available = new HashMap<>();
        for (Map.Entry<String, Double> e : aboardKg.entrySet()) {
            double owed = undrainedKg.getOrDefault(e.getKey(), 0.0D);
            available.put(e.getKey(), Math.max(0.0D, e.getValue() - owed));
        }
        return PropellantBudget.of(available);
    }

    /** Book what a step ejected, kg per fluid; ignored while unmetered. */
    public synchronized void book(Map<String, Double> ejectedKg) {
        if (!metered) {
            return;
        }
        for (Map.Entry<String, Double> e : ejectedKg.entrySet()) {
            if (e.getValue() > 0.0D) {
                undrainedKg.merge(e.getKey(), e.getValue(), Double::sum);
            }
        }
    }

    /** What is ejected and not yet drained, kg per fluid: what the next drain owes. */
    public synchronized Map<String, Double> undrained() {
        return new TreeMap<>(undrainedKg);
    }

    /**
     * The game tick's half, as one act: {@code drainedKg} was taken out of the holders since the last
     * settle, and the holders now hold {@code aboardKg}. Metered from here on.
     */
    public synchronized void settle(Map<String, Double> drainedKg, Map<String, Double> aboardKg) {
        metered = true;
        for (Map.Entry<String, Double> e : drainedKg.entrySet()) {
            double left = undrainedKg.getOrDefault(e.getKey(), 0.0D) - e.getValue();
            if (left > 0.0D) {
                undrainedKg.put(e.getKey(), left);
            } else {
                undrainedKg.remove(e.getKey());
            }
        }
        this.aboardKg.clear();
        this.aboardKg.putAll(aboardKg);
    }

    /** The pool aboard as last settled, kg per fluid; empty while unmetered. */
    public synchronized Map<String, Double> aboard() {
        return new TreeMap<>(aboardKg);
    }
}
