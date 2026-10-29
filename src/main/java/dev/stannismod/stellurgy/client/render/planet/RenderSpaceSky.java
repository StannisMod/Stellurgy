package dev.stannismod.stellurgy.client.render.planet;

import dev.stannismod.stellurgy.api.dimension.solar.StellarBody;
import dev.stannismod.stellurgy.api.stations.ISpaceObject;
import dev.stannismod.stellurgy.dimension.DimensionProperties;
import dev.stannismod.stellurgy.inventory.TextureResources;
import dev.stannismod.stellurgy.libvulpes.util.Vector3F;
import dev.stannismod.stellurgy.stations.SpaceObjectManager;
import dev.stannismod.stellurgy.util.AstronomicalBodyHelper;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.BufferBuilder;
import net.minecraft.client.renderer.GlStateManager;
import net.minecraft.util.EnumFacing;
import net.minecraft.util.ResourceLocation;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3d;
import org.lwjgl.opengl.GL11;

import java.util.Objects;

/**
 * The sky of a space station: the world it orbits below it, as a sphere at the angle the station's
 * altitude gives — or, for a station round a star, that star — and every other body as the planetary sky
 * draws it.
 */
public class RenderSpaceSky extends RenderPlanetarySky {

    Minecraft mc = Minecraft.getMinecraft();

    public RenderSpaceSky() {
        super();
    }

    @Override
    protected void drawStar(BufferBuilder buffer, StellarBody sun, DimensionProperties properties, long solarOrbitalDistance, float sunSize, Vec3d sunColor, float multiplier) {
        DimensionProperties parentProperties = properties.getParentProperties();
        if (parentProperties != null && sun != parentProperties.getStarData())
            super.drawStar(buffer, sun, properties, solarOrbitalDistance, sunSize, sunColor, multiplier);
    }

    /**
     * The body the station orbits, drawn from the station's own ALTITUDE rather than from the angle the
     * planetary sky computed for it — that angle is a moon's-orbit reading and means nothing at a station.
     */
    @Override
    public void renderPlanet2(BufferBuilder buffer, DimensionProperties properties, double halfAngle, float alphaMultiplier, double shadowAngle, boolean hasRing, float[] shadowColorMultiplier, float alphaMultiplier2) {
        ISpaceObject spaceObject = SpaceObjectManager.getSpaceManager().getSpaceStationFromBlockCoords(mc.player.getPosition());
        if (spaceObject == null)
            return;
        double altitudeMetres = AstronomicalBodyHelper.stationAltitudeKm(spaceObject.getOrbitalDistance()) * 1000.0D;

        if (properties.isStar()) {
            drawStationStar(buffer, properties.getStar(), altitudeMetres);
            return;
        }
        if (Objects.equals(properties.customIcon, "void"))
            return;

        double radiusMetres = skyRadiusEarths(properties) * AstronomicalBodyHelper.EARTH_RADIUS_METRES;
        if (radiusMetres <= 0d) {
            reportSizeless(properties);
            return;
        }
        double angle = ApparentSize.halfAngleFor(radiusMetres, radiusMetres + altitudeMetres, SkySphere.markerHalfAngle());
        double centre = SkySphere.centreDistance(angle, 100.0D);
        double radius = centre * Math.sin(angle);
        float[] atmColor = properties.skyColor;

        GL11.glPushMatrix();
        GL11.glTranslated(0, -centre, 0);

        GL11.glPushAttrib(GL11.GL_ALL_ATTRIB_BITS);
        GlStateManager.disableFog();
        GlStateManager.blendFunc(GL11.GL_ONE, GL11.GL_ZERO);
        mc.renderEngine.bindTexture(getTextureForPlanet(properties));
        GL11.glTexParameterf(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MAG_FILTER, GL11.GL_LINEAR);
        GL11.glTexParameterf(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MIN_FILTER, GL11.GL_LINEAR);
        GlStateManager.color(1f, 1f, 1f, alphaMultiplier);
        // The surface slides past underneath.
        layer(buffer, radius, System.currentTimeMillis() / 1000000d % 1, 0.0D, 1.0D);
        GL11.glPopAttrib();

        if (properties.hasDecorators() && (properties.isGasGiant() || properties.hasAtmosphere())) {
            GlStateManager.blendFunc(GL11.GL_SRC_ALPHA, GL11.GL_ONE);
            mc.renderEngine.bindTexture(DimensionProperties.getAtmosphereLEOResource());
            if (properties.isGasGiant()) {
                // Cloud bands drifting at different rates, each a shell a little above the last.
                GlStateManager.color(atmColor[0], atmColor[1], atmColor[2], 0.5f);
                int bands = 6;
                double drift = Minecraft.getSystemTime() / 100000d % 1;
                for (int i = 0; i < bands; i++) {
                    layer(buffer, radius * (1.0D + 0.002D * (i + 1)), drift * (i - bands / 4.0D), -drift * i,
                            Math.max(1, i));
                }
            } else {
                GlStateManager.color(1f, 1f, 1f, 0.5f);
                layer(buffer, radius * 1.002D, System.currentTimeMillis() / 100000d % 1, 0.0D, 1.0D);
            }
            // The haze: untextured shells in the sky's colour.
            GlStateManager.disableTexture2D();
            GlStateManager.blendFunc(GL11.GL_SRC_ALPHA, GL11.GL_ONE_MINUS_SRC_ALPHA);
            GlStateManager.color(atmColor[0], atmColor[1], atmColor[2], 0.08f);
            for (int i = 0; i < 5; i++) {
                facingCamera(buffer, radius * (1.015D + 0.003D * i), 0.0D);
            }
            GlStateManager.enableTexture2D();
        }

        GlStateManager.color(1f, 1f, 1f, 1f);
        GlStateManager.enableFog();
        GL11.glPopMatrix();
    }

    /** One shell of the world below, its picture shifted by ({@code du}, {@code dv}) and tiled {@code tile} times. */
    private static void layer(BufferBuilder buffer, double radius, double du, double dv, double tile) {
        GL11.glMatrixMode(GL11.GL_TEXTURE);
        GL11.glPushMatrix();
        GL11.glTranslated(du, dv, 0);
        GL11.glScaled(tile, tile, 1);
        GL11.glMatrixMode(GL11.GL_MODELVIEW);
        facingCamera(buffer, radius, 0.0D);
        GL11.glMatrixMode(GL11.GL_TEXTURE);
        GL11.glPopMatrix();
        GL11.glMatrixMode(GL11.GL_MODELVIEW);
    }

    /** The star a station orbits, at the angle its altitude above the star gives. */
    private void drawStationStar(BufferBuilder buffer, StellarBody star, double altitudeMetres) {
        double radiusMetres = AstronomicalBodyHelper.starRadiusEarths(star) * AstronomicalBodyHelper.EARTH_RADIUS_METRES;
        double angle = ApparentSize.halfAngleFor(radiusMetres, radiusMetres + altitudeMetres, SkySphere.markerHalfAngle());

        if (star.isBlackHole()) {
            GL11.glDepthMask(true);
            GL11.glEnable(GL11.GL_ALPHA_TEST);
            GL11.glAlphaFunc(GL11.GL_GREATER, 0.01f);
            GL11.glDisable(GL11.GL_BLEND);
            GL11.glPushMatrix();
            GL11.glRotatef(140, -1, 0, 0);
            GL11.glRotatef(180, 0, 1, 0);
            double centre = SkySphere.centreDistance(angle, 100.0D);
            double core = centre * Math.sin(angle);
            GL11.glTranslated(0, centre, 0);

            GlStateManager.color(0, 0, 0);
            GlStateManager.disableCull();
            renderSphere(0, 0, 0, (float) core, 32, 23);
            GL11.glEnable(GL11.GL_BLEND);
            GL11.glDepthMask(false);

            float ro = -20f;
            mc.renderEngine.bindTexture(TextureResources.locationAccretionDiskDense);
            GL11.glPushMatrix();
            GL11.glRotatef(90, 0f, 1f, 0f);
            GL11.glRotatef((System.currentTimeMillis() % (int) (360 * 360 * 5f)) / (360f * 5f), 0, 1, 0);
            GlStateManager.color(1f, .7f, .5f, 1f);
            SkySphere.emitDisc(buffer, core * 3.5D);
            GL11.glPopMatrix();

            float speedMult = 5;
            mc.renderEngine.bindTexture(TextureResources.locationAccretionDisk);
            GL11.glPushMatrix();
            GL11.glRotatef(60 + ro, 1, 0, 0);
            GL11.glRotatef((System.currentTimeMillis() % (int) (360 * 360 * speedMult)) / (360f * speedMult), 0, 1, 0);
            GlStateManager.color(1f, .5f, .4f, 1f);
            SkySphere.emitDisc(buffer, core * 20.0D);
            GL11.glPopMatrix();

            GL11.glPushMatrix();
            GL11.glTranslatef(0, -0.1f, 0);
            GL11.glRotatef(60 + ro, 1, 0, 0);
            GL11.glRotatef((System.currentTimeMillis() % (int) (360 * 200 * speedMult)) / (200f * speedMult), 0, 1, 0);
            GlStateManager.color(0.8f, .7f, .4f, 1f);
            SkySphere.emitDisc(buffer, core * 15.0D);
            GL11.glPopMatrix();

            GL11.glPushMatrix();
            GL11.glTranslatef(0, -0.2f, 0);
            GL11.glRotatef(60 + ro, 1, 0, 0);
            GL11.glRotatef((System.currentTimeMillis() % (int) (36000 * speedMult)) / (100f * speedMult), 0, 1, 0);
            GlStateManager.color(0.2f, .4f, 1f, 1f);
            SkySphere.emitDisc(buffer, core * 7.5D);
            GL11.glPopMatrix();

            GlStateManager.enableCull();
            GL11.glPopMatrix();
        } else {
            mc.renderEngine.bindTexture(TextureResources.locationSunPng);
            GlStateManager.color(star.getColor()[0], star.getColor()[1], star.getColor()[2], 1);
            GL11.glPushMatrix();
            GL11.glRotatef(60, -60, 0, 1);
            drawStarSphere(buffer, angle, 20.0D, star.getColor());
            GL11.glPopMatrix();
        }
        GlStateManager.depthMask(true);
        GL11.glClear(GL11.GL_DEPTH_BUFFER_BIT);
        GlStateManager.depthMask(false);
    }

    @Override
    protected EnumFacing getRotationAxis(DimensionProperties properties,
                                         BlockPos pos) {
        try {
            return SpaceObjectManager.getSpaceManager().getSpaceStationFromBlockCoords(pos).getForwardDirection().rotateY();
        } catch (Exception e) {
            return EnumFacing.EAST;
        }
    }

    @Override
    protected void rotateAroundAxis() {
        Vector3F<Float> axis = getRotateAxis();
        ISpaceObject spaceObject = SpaceObjectManager.getSpaceManager().getSpaceStationFromBlockCoords(mc.player.getPosition());
        if (spaceObject != null) {
            GL11.glRotated(spaceObject.getRotation(EnumFacing.UP) * 360, 0, 1, 0);
            GL11.glRotated(spaceObject.getRotation(EnumFacing.EAST) * 360, 1, 0, 0);
        }
    }

    @Override
    protected ResourceLocation getTextureForPlanet(DimensionProperties properties) {
        return properties.getPlanetIconLEO();
    }
}
