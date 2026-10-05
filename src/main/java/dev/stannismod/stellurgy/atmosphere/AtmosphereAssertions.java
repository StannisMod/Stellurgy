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
 * below is answered from it; outdoors the planet's own composition answers the questions about
 * substance (is it poisonous, is it eating the hull), so a poisonous world reads as one. The questions
 * the hazard table owns are answered from the published atmosphere either way.
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
        AirState around = handler == null ? null : handler.getAirAround(pos);
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
                return around != null && around.isToxic();
            case CORROSIVE:
                return around != null && around.corrosionIndex() > 0.0D;
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
