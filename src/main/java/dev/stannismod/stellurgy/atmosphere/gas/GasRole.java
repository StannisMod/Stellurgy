package dev.stannismod.stellurgy.atmosphere.gas;

/**
 * What a gas DOES, which is the only reason it is in the model at all.
 * <p>
 * A gas is not filed under a role — it declares the roles it has, and a gas may have several: methane
 * is fuel and asphyxiant, hydrogen sulphide is poison and acid. The roles exist so that a predicate
 * can ask "is there an oxidiser here" without naming oxygen, which is what lets a world with a
 * different chemistry work without new code.
 * <p>
 * <b>A role with no predicate reading it may not exist.</b> That is the rule that keeps this from
 * growing to chemistry's size: storage nobody consults is not a model, it is a list.
 */
public enum GasRole {

    /** Pressure and nothing else: it fills a hull and it is what a breach loses. */
    INERT,

    /** Supports breathing and combustion. Oxygen today; the predicates never say so. */
    OXIDISER,

    /** What a crew makes and a scrubber removes. */
    WASTE,

    /** Burns with an oxidiser you supply. Worth extracting; worth being careful around. */
    FUEL,

    /** Harms by its own limit, whatever the pressure and whatever else is in the air. */
    TOXIC,

    /** Attacks hull and equipment over time rather than the crew; worse when it is wet and hot. */
    CORROSIVE,

    /** Water vapour's own role: it is what turns a corrosive gas into an acid. */
    SOLVENT
}
