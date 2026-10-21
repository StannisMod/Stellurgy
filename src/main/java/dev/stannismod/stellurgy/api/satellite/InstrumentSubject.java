package dev.stannismod.stellurgy.api.satellite;

/**
 * What a satellite's primary function looks at, and so which way its instruments face: toward the body
 * it orbits, or away from it into space.
 */
public enum InstrumentSubject {
    /** The body itself — its surface, air, mass or weather. Instruments face the body. */
    BODY,
    /** Everything else — the sky beyond the body. Instruments face away from it. */
    SPACE
}
