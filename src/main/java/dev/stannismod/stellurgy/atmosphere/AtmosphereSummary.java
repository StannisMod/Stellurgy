package dev.stannismod.stellurgy.atmosphere;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import dev.stannismod.stellurgy.api.atmosphere.Atmosphere;
import dev.stannismod.stellurgy.api.atmosphere.AtmosphereAssertion;
import dev.stannismod.stellurgy.atmosphere.hazard.AtmosphereHazards;

/**
 * What the client is TOLD about the air around a player: enough to draw, and nothing to decide with.
 * <p>
 * <b>The client decides nothing.</b> Every threshold, every damage, every gate is resolved on the
 * server; what crosses is for the eyes. That is why this carries finished answers — is it breathable,
 * what warning to show, what to call it — rather than a composition the client would have to
 * interpret. A client that interpreted would be a second implementation of the model, and the two
 * would disagree the moment one of them was tuned.
 * <p>
 * <b>And it carries no atmosphere NAME.</b> What a player reads is the set of statements that are
 * true of the air, in a fixed order — a derived label, not an identity. Nothing branches on it; if it
 * is stale by a fraction of a second, the worst that happens is that a line of text lags, which is
 * the whole point of only sending it for display.
 */
public final class AtmosphereSummary {

    /** What a client that has been told nothing yet assumes: ordinary air, at one atmosphere. */
    public static final AtmosphereSummary UNKNOWN =
            new AtmosphereSummary(100, true, "", Collections.<String>emptyList());

    private final int pressureCentiAtm;
    private final boolean breathable;
    private final String warningKey;
    private final List<String> assertions;

    public AtmosphereSummary(int pressureCentiAtm, boolean breathable, String warningKey,
                             List<String> assertions) {
        this.pressureCentiAtm = pressureCentiAtm;
        this.breathable = breathable;
        this.warningKey = warningKey == null ? "" : warningKey;
        this.assertions = Collections.unmodifiableList(new ArrayList<>(assertions));
    }

    /** Hundredths of an atmosphere, which is what the fog and the analyser have always spoken. */
    public int pressureCentiAtm() {
        return pressureCentiAtm;
    }

    public boolean breathable() {
        return breathable;
    }

    /** The lang key for the warning shown to somebody standing in this unprotected, or empty. */
    public String warningKey() {
        return warningKey;
    }

    /** The statements that are true of this air, in enum order. This IS the name, derived. */
    public List<String> assertions() {
        return assertions;
    }

    /**
     * Take the reading, server-side. Everything here is answered by the model; nothing is a flag
     * stored beside it.
     */
    public static AtmosphereSummary of(AtmosphereHandler handler, net.minecraft.entity.Entity entity) {
        if (handler == null || entity == null) {
            return UNKNOWN;
        }
        Atmosphere published = handler.getAtmosphereType(entity);
        net.minecraft.util.math.BlockPos pos = entity.getPosition();
        List<String> holding = new ArrayList<>();
        for (AtmosphereAssertion assertion : AtmosphereAssertion.values()) {
            if (AtmosphereAssertions.holdsAt(handler, pos, assertion)) {
                holding.add(assertion.name());
            }
        }
        return new AtmosphereSummary(
                handler.getAtmospherePressure(entity),
                published != null && published.isBreathable(),
                published == null ? "" : AtmosphereHazards.exposureOf(published).messageKey(),
                holding);
    }
}
