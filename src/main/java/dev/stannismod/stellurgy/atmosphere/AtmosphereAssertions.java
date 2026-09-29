package dev.stannismod.stellurgy.atmosphere;

import javax.annotation.Nullable;

import net.minecraft.util.math.BlockPos;
import dev.stannismod.stellurgy.api.atmosphere.Atmosphere;
import dev.stannismod.stellurgy.api.atmosphere.AtmosphereAssertion;
import dev.stannismod.stellurgy.api.atmosphere.AtmosphereHazard;
import dev.stannismod.stellurgy.atmosphere.hazard.AtmosphereHazards;

/**
 * Whether a statement about the air is true at a position.
 * <p>
 * <b>Asked of the AIR wherever there is any.</b> A sealed zone has a composition and every question
 * below is answered from it. Outdoors there is no composition yet — a planet still carries a density
 * and a named atmosphere — so the questions that can be answered from a name are, and the two that
 * cannot (is it poisonous, is it eating the hull) answer NO rather than guessing. That is the honest
 * shape of a half-migrated model: the fallback is visible, it is in one place, and it disappears when
 * planets carry compositions of their own.
 */
public final class AtmosphereAssertions {

    private AtmosphereAssertions() {
    }

    public static boolean holdsAt(@Nullable AtmosphereHandler handler, BlockPos pos,
                                  AtmosphereAssertion assertion) {
        if (assertion == null) {
            return false;
        }
        AirState air = handler == null ? null : handler.getAirStateAt(pos);
        Atmosphere published = handler == null ? null : handler.getAtmosphereType(pos);
        switch (assertion) {
            case BREATHABLE:
                return breathable(air, published);
            case NOT_BREATHABLE:
                return !breathable(air, published);
            case COMBUSTIBLE:
                return handler != null && handler.allowsCombustionAt(pos);
            case NOT_COMBUSTIBLE:
                return handler != null && !handler.allowsCombustionAt(pos);
            case VACUUM:
                // Emptiness is a fact about quantity, so the air answers it directly where there is
                // a zone; outdoors the label is the only thing that knows.
                return air != null ? air.getTotalPressure() <= 0L : published == Atmosphere.VACUUM;
            case TOXIC:
                return air != null && air.isToxic();
            case CORROSIVE:
                return air != null && air.corrosionIndex() > 0.0D;
            case SUFFOCATING:
                return raises(published, AtmosphereHazard.SUFFOCATION);
            case TOO_HOT:
                return raises(published, AtmosphereHazard.HEAT);
            case TOO_DENSE:
                return raises(published, AtmosphereHazard.PRESSURE);
            case OXYGEN_RICH:
                return raises(published, AtmosphereHazard.OXYGEN_TOXICITY);
            default:
                return false;
        }
    }

    private static boolean breathable(@Nullable AirState air, @Nullable Atmosphere published) {
        if (air != null) {
            return air.isBreathableAir();
        }
        return published != null && published.isBreathable();
    }

    private static boolean raises(@Nullable Atmosphere published, AtmosphereHazard hazard) {
        return published != null
                && AtmosphereHazards.exposureOf(published).hazards().contains(hazard);
    }
}
