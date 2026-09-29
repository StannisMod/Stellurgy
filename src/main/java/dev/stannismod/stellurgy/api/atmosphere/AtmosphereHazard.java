package dev.stannismod.stellurgy.api.atmosphere;

/**
 * What an atmosphere can do to the people in it — one constant per KIND of harm, never per kind of
 * air.
 * <p>
 * <b>This is the thing a suit protects against.</b> Protection used to be asked of the atmosphere
 * itself: a chest plate was handed a whole named atmosphere and answered yes or no, which meant every
 * new kind of air had to be added to a list inside every piece of armour. A hazard is the smaller and
 * more durable question — a suit stops heat, or it does not — so adding an atmosphere stops being a
 * change to the things that survive it.
 * <p>
 * <b>Declaration order is severity order</b>, and one thing reads it: the message a player is shown
 * names the most severe hazard they are standing in. That is why a scorching room with nothing to
 * breathe tells you about the air rather than the heat — suffocating is the more urgent of the two,
 * and it was the more urgent one before this enum existed too.
 */
public enum AtmosphereHazard {

    /** No air at all. Not the same as having nothing to breathe: the body is also decompressing. */
    DECOMPRESSION(true),

    /** Air, but not enough oxidiser in it to keep a person going. */
    SUFFOCATION(false),

    /** Too much oxidiser. Breathing it is slow poisoning rather than immediate. */
    OXYGEN_TOXICITY(false),

    /** Air dense enough to hurt, with everything that comes with depth. */
    PRESSURE(true),

    /** Air hot enough to burn. */
    HEAT(true);

    private final boolean needsFullSuit;

    AtmosphereHazard(boolean needsFullSuit) {
        this.needsFullSuit = needsFullSuit;
    }

    /**
     * Whether surviving this needs the whole suit rather than something sealed over the face.
     * <p>
     * A hazard that acts on what you BREATHE is answered by a helmet and a supply; one that acts on
     * your whole body — crushing, cooking, the absence of any pressure at all — is not. Where several
     * hazards are present the strictest of them decides, which is how the fourteen hand-written
     * "does this need a full suit" answers this replaced are reproduced without any of them.
     */
    public boolean needsFullSuit() {
        return needsFullSuit;
    }
}
