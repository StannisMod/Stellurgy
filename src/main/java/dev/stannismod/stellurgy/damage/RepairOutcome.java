package dev.stannismod.stellurgy.damage;

/**
 * Every answer a rung of the repair ladder gives, as one vocabulary.
 *
 * <p>One type for every rung so that "out of charge" on the hand tool and "out of charge" on a bay
 * are the same answer, which a caller tells apart by comparing values rather than message strings.
 * It carries no words: what a welder says about being empty and what a bay's screen says are
 * different sentences, and each rung owns its own.</p>
 *
 * <p>Not every rung gives every answer. The welder acts once and answers {@link #REPAIRED}; a bay
 * keeps working and answers {@link #REPAIRING}. Only a bay can be somewhere it has nothing to serve
 * ({@link #NO_STRUCTURE}) or meet a hole it may not fill ({@link #UNFILLABLE}).</p>
 */
public enum RepairOutcome {
    /** The welder took a stage off. */
    REPAIRED,
    /** A bay is working a damaged position of its ship. */
    REPAIRING,
    /** Nothing here is damaged — or, for a bay, nothing that another bay has not already taken. */
    UNDAMAGED,
    /** The damaged block has no price: nothing crafts it (welder), or no item stands for it (bay). */
    NO_RECIPE,
    /** The price exists and is not on hand. */
    NO_MATERIALS,
    /** No energy to do the work with. */
    NO_CHARGE,
    /** The only work is a hole whose recorded block no longer exists; the record is kept. */
    UNFILLABLE,
    /** A bay that is not on a ship: there is no structure for it to serve. */
    NO_STRUCTURE
}
