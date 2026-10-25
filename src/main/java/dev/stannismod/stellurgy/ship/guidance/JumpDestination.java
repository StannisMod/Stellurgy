package dev.stannismod.stellurgy.ship.guidance;

import dev.stannismod.stellurgy.api.Constants;
import dev.stannismod.stellurgy.space.GalacticCoord;

/**
 * Where the jump a motion intent needs is aimed: what the craft's navigation computer is pointed at for
 * the leg to another system — the same pair a pilot points it at, a BODY (its planet world, and the
 * address it was last seen at) or a bare PLACE (an address, nothing tracked).
 *
 * <p>Immutable.</p>
 */
public final class JumpDestination {

    /** The planet world of the body aimed at; {@link Constants#INVALID_PLANET} for a place. */
    private final int body;
    private final GalacticCoord address;

    private JumpDestination(int body, GalacticCoord address) {
        if (address == null) {
            throw new IllegalArgumentException("a jump is aimed at an address");
        }
        this.body = body;
        this.address = address;
    }

    /**
     * The body whose surface is the planet world {@code dimension}, last seen at {@code observed}: the
     * navigation computer follows it, predicting where it will be when the jump ends.
     */
    public static JumpDestination toBody(int dimension, GalacticCoord observed) {
        if (dimension == Constants.INVALID_PLANET) {
            throw new IllegalArgumentException("a body aimed at has a planet world");
        }
        return new JumpDestination(dimension, observed);
    }

    /** The place {@code address} itself, tracked by nothing. */
    public static JumpDestination toPlace(GalacticCoord address) {
        return new JumpDestination(Constants.INVALID_PLANET, address);
    }

    /** The planet world of the body aimed at; {@link Constants#INVALID_PLANET} when a place is. */
    public int body() {
        return body;
    }

    /** The place aimed at, or the address the body aimed at was last seen at. */
    public GalacticCoord address() {
        return address;
    }

    @Override
    public boolean equals(Object o) {
        if (!(o instanceof JumpDestination)) {
            return false;
        }
        JumpDestination d = (JumpDestination) o;
        return body == d.body && address.equals(d.address);
    }

    @Override
    public int hashCode() {
        return 31 * body + address.hashCode();
    }

    @Override
    public String toString() {
        return body == Constants.INVALID_PLANET ? "place " + address.cellKey() : "body " + body;
    }
}
