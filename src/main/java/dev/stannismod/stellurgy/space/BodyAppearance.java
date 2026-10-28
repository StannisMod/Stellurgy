package dev.stannismod.stellurgy.space;

import dev.stannismod.stellurgy.api.Constants;
import dev.stannismod.stellurgy.api.dimension.solar.StellarBody;
import dev.stannismod.stellurgy.dimension.DimensionManager;
import dev.stannismod.stellurgy.dimension.DimensionProperties;
import dev.stannismod.stellurgy.inventory.TextureResources;
import dev.stannismod.stellurgy.universe.BodyProfile;
import dev.stannismod.stellurgy.universe.SystemBody;
import dev.stannismod.stellurgy.universe.SystemBodyKind;
import dev.stannismod.stellurgy.universe.UniverseRegistry;
import net.minecraft.util.ResourceLocation;

import java.util.Optional;

/**
 * What a body looks like in a cell's sky — the picture and the colour it is drawn with — decided on the
 * server, which alone knows every body: a star by its own light, a realized world by its properties, and a
 * world nobody has landed on by the profile a descent would materialize, so it looks in the sky as it will
 * once somebody has.
 */
public final class BodyAppearance {

    /** Drawn as it is, no tint. */
    public static final int UNTINTED = 0xFFFFFF;

    private final ResourceLocation texture;
    private final int tint;

    private BodyAppearance(ResourceLocation texture, int tint) {
        this.texture = texture;
        this.tint = tint;
    }

    /** The picture to draw the body with. */
    public ResourceLocation texture() {
        return texture;
    }

    /** The colour the picture is multiplied by, as {@code 0xRRGGBB}. */
    public int tint() {
        return tint;
    }

    /**
     * The appearance of {@code body} in {@code registry}'s universe. A body whose system cannot place it
     * is drawn with the unknown-world picture, and the log says which one.
     */
    public static BodyAppearance of(SystemBody body, UniverseRegistry registry) {
        if (body.kind() == SystemBodyKind.STAR) {
            StellarBody star = registry.starAt(body.name()).orElse(null);
            if (star == null) {
                star = DimensionManager.getInstance().getStar(body.starId());
            }
            if (star == null) {
                return unknown(body, "its star is in no catalogue");
            }
            if (star.isBlackHole()) {
                return new BodyAppearance(TextureResources.locationBlackHole_icon, UNTINTED);
            }
            return new BodyAppearance(TextureResources.locationSunPng, rgb(star.getColor()));
        }
        if (body.dimId() != Constants.INVALID_PLANET) {
            DimensionProperties props = DimensionManager.getInstance().getDimensionPropertiesOrNull(body.dimId());
            if (props != null) {
                return new BodyAppearance(props.getPlanetIcon(), UNTINTED);
            }
        }
        if (body.kind() == SystemBodyKind.ASTEROID_BELT) {
            return new BodyAppearance(DimensionProperties.PlanetIcons.ASTEROID.getResource(), UNTINTED);
        }
        Optional<BodyProfile> profile = registry.profileOf(body);
        if (!profile.isPresent()) {
            return unknown(body, "its system cannot place it");
        }
        BodyProfile p = profile.get();
        return new BodyAppearance(DimensionProperties.PlanetIcons.forClimate(p.kind() == SystemBodyKind.GAS_GIANT,
                false, p.pressure(), p.temperatureKelvin()).getResource(), UNTINTED);
    }

    /** The unknown-world picture: what a body nothing can be said about is drawn with. */
    public static BodyAppearance unknownWorld() {
        return new BodyAppearance(DimensionProperties.PlanetIcons.UNKNOWN.getResource(), UNTINTED);
    }

    private static BodyAppearance unknown(SystemBody body, String why) {
        dev.stannismod.stellurgy.Stellurgy.logger.warn("[SKY] {} at {} is drawn as an unknown world: {}", body.kind(), body.name().cellKey(), why);
        return unknownWorld();
    }

    private static int rgb(float[] color) {
        return (channel(color[0]) << 16) | (channel(color[1]) << 8) | channel(color[2]);
    }

    private static int channel(float value) {
        return Math.max(0, Math.min(255, Math.round(value * 255.0F)));
    }
}
