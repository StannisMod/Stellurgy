package zmaster587.advancedRocketry.api.atmosphere;

/**
 * A statement that is either true or false of the air somewhere.
 * <p>
 * <b>This is what a detector watches, instead of a name.</b> It used to compare the air against one
 * of fourteen named atmospheres and emit redstone when they were the same object. That made the wiring
 * a player could build a function of the mod's VOCABULARY rather than of the air: "is this Superheated
 * with no oxygen" was expressible and "is this poisonous" was not, for no better reason than that
 * somebody had written a class for the first and not the second.
 * <p>
 * Every constant here is a question about the air. Several of them no named atmosphere could ask —
 * nothing in the old list meant "toxic" or "eating my hull" — and two of them (breathable and its
 * negation) replace nine of the fourteen buttons between them, because eight of those names differed
 * only in ways a player wiring a bulkhead does not care about.
 * <p>
 * The constant NAME is what goes into save data and across the wire. That is a name of an ASSERTION,
 * not of an atmosphere: nothing branches on what the air is CALLED, which is the whole point.
 */
public enum AtmosphereAssertion {

    /** Somebody could stand here without a suit. */
    BREATHABLE,

    /** They could not. The commonest thing to wire a bulkhead or an alarm to. */
    NOT_BREATHABLE,

    /** A fire would take. Asked of the oxidiser, never of whether the air is breathable. */
    COMBUSTIBLE,

    /** Nothing will light here — a furnace bay's interlock, or a fuel store's. */
    NOT_COMBUSTIBLE,

    /** There is no air at all. Not the same as having nothing to breathe. */
    VACUUM,

    /** Something here is past its own poisoning limit. No named atmosphere could say this. */
    TOXIC,

    /** The air is attacking exposed metal. Nor this. */
    CORROSIVE,

    /** There is air, and not enough oxidiser in it to keep a person going. */
    SUFFOCATING,

    /** Hot enough to hurt the people in it. */
    TOO_HOT,

    /** Dense enough to hurt them. */
    TOO_DENSE,

    /** So much oxidiser that breathing it is poisoning — and that anything will burn. */
    OXYGEN_RICH;

    /** The lang key a button carries. One key per assertion, and adding one is a row and a key. */
    public String messageKey() {
        return "msg.atmosphere.assertion." + name().toLowerCase(java.util.Locale.ROOT);
    }
}
