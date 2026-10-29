package dev.stannismod.stellurgy.client.render.planet;

import dev.stannismod.stellurgy.util.AstronomicalBodyHelper;

/**
 * How big a fed body is drawn in the cell sky, given how far away it is <b>and how big it is</b>.
 *
 * <p>The rule is one sentence: <b>a body is drawn at the angle it truly subtends, {@code asin(r/d)},
 * and never smaller than a visible marker.</b> A world you orbit fills half the sky, because it does.</p>
 *
 * <p>There is no ceiling below the truth. A logarithmic compression clamped at about ten degrees stood
 * here before, defended as "correct and unreadable"; it drew an Earth seen from twice its shell — 59
 * degrees across — at 20, and an Earth a pilot was about to land on no bigger. The maintainer had asked
 * for the real size, and the compression overrode the request. The floor stays, because a star at a
 * fraction of a pixel is not drawn at all; that is the only departure from the truth.</p>
 *
 * <p>Pure arithmetic — no GL, no client state — so the rule can be checked without a client.</p>
 *
 * Every static field of this type is effectively final, process lifetime: built once at class initialisation.
 */
public final class ApparentSize {

    /**
     * The smallest a body is drawn, in screen PIXELS across. A body that truly subtends less is still drawn
     * this big, so a distant world is a visible marker instead of a sub-pixel speck that blinks out; it is
     * the only place the drawn size departs from the truth, and it is measured in pixels because that is
     * the thing it protects. {@code tunable}.
     */
    public static final double MARKER_DIAMETER_PIXELS = 2.0d;
    /**
     * The largest angular radius drawn, 89 degrees. Not a choice about how big a world may LOOK — the true
     * angle tends to 90 degrees as the observer reaches the surface, and a world you orbit fills half the
     * sky — but the representation's limit: at 90 the drawn sphere degenerates. A ship never gets there in
     * space; the descent fires at the shell, outside the surface.
     */
    public static final double MAX_HALF_ANGLE = Math.toRadians(89.0d);

    private ApparentSize() {
    }

    /**
     * The angular radius, in radians, of a body {@link #MARKER_DIAMETER_PIXELS} across on a screen
     * {@code heightPixels} tall that shows {@code verticalFovDegrees} of sky from top to bottom.
     */
    public static double markerHalfAngle(double verticalFovDegrees, int heightPixels) {
        return Math.toRadians(verticalFovDegrees) / Math.max(1, heightPixels) * (MARKER_DIAMETER_PIXELS / 2.0d);
    }

    /**
     * The angular radius, in radians, to draw a body of {@code radiusBlocks} seen from
     * {@code distanceBlocks}: the TRUE one, {@code asin(r / d)}, raised to {@code floorHalfAngle} — the
     * screen's {@link #markerHalfAngle} — for a body too small to see.
     *
     * <p>Asked only of a SPHERE with a radius. A body with none is not drawn as a sphere at all — a belt
     * is a ring, a station a label, and a sphere that arrived without its radius is a broken feed the
     * caller reports — so a non-positive radius here is a caller's error and is refused, never given a
     * marker that would pass for a distant world. A non-finite or non-positive DISTANCE is the nearest
     * thing there is, so it takes the maximum rather than becoming invisible.</p>
     */
    public static double halfAngleFor(double radiusBlocks, double distanceBlocks, double floorHalfAngle) {
        if (Double.isNaN(radiusBlocks) || radiusBlocks <= 0d) {
            throw new IllegalArgumentException("a body with no radius (" + radiusBlocks
                    + ") has no angular size; it is not drawn as a sphere");
        }
        if (Double.isNaN(distanceBlocks) || distanceBlocks <= 0d) {
            return MAX_HALF_ANGLE;
        }
        double ratio = radiusBlocks / distanceBlocks;
        double angle = ratio >= 1d ? Math.PI / 2d : Math.asin(ratio);
        return Math.max(floorHalfAngle, Math.min(MAX_HALF_ANGLE, angle));
    }


    /**
     * A distance in CHART BLOCKS, rendered the way a pilot reads it.
     *
     * <p>This is the one a sky label wants, because every length the body feed carries is a chart
     * length. One chart block is {@link AstronomicalBodyHelper#METRES_PER_CHART_BLOCK} metres, so
     * printing the block count itself under a unit of length understates the range by that whole
     * factor — Earth at 5 657 554 blocks read as "6 Mm" where the truth is 1.41 Gm, and a pilot
     * deciding whether a burn is worth making was reading a number 250 times too small.</p>
     */
    public static String formatChartDistance(double distanceChartBlocks) {
        return formatDistance(Math.max(0d, distanceChartBlocks)
                * AstronomicalBodyHelper.METRES_PER_CHART_BLOCK);
    }

    /**
     * A distance IN METRES, rendered the way a pilot reads it: whole metres under 10 km, then km,
     * then Mm, then Gm. The label has to be legible at a glance across six decades, and
     * "1183472901 m" is not.
     *
     * <p>Takes metres and not blocks on purpose: the two metrics are never added and a formatter that
     * accepted either would be the place they got confused. A caller holding a chart length wants
     * {@link #formatChartDistance}.</p>
     */
    public static String formatDistance(double distanceMetres) {
        double d = Math.max(0d, distanceMetres);
        if (d < 10_000d) {
            return Math.round(d) + " m";
        }
        if (d < 10_000_000d) {
            return Math.round(d / 1_000d) + " km";
        }
        if (d < 10_000_000_000d) {
            return Math.round(d / 1_000_000d) + " Mm";
        }
        return Math.round(d / 1_000_000_000d) + " Gm";
    }
}
