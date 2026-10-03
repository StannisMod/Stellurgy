package dev.stannismod.stellurgy.util;

import dev.stannismod.stellurgy.api.StellurgyConfiguration;
import dev.stannismod.stellurgy.api.dimension.IDimensionProperties;
import dev.stannismod.stellurgy.dimension.DimensionManager;
import dev.stannismod.stellurgy.stations.SpaceStationObject;

public class PlanetaryTravelHelper {
    /**
     * @param currentDimensionID     the dimension ID of the current planet
     * @param destinationDimensionID the dimension ID of the destination planet
     * @return boolean on whether the travel is between two bodies in a planetary systen
     */
    public static boolean isTravelBetweenBodiesWithinPlanetarySystem(int currentDimensionID, int destinationDimensionID) {
        boolean isPlanetMoonSystem = false;
        IDimensionProperties launchworldProperties = DimensionManager.getInstance().getDimensionProperties(currentDimensionID);

        //If it's a moon, we need to check moon -> planet and moon -> moon
        //Otherwise, we need to check planet -> moon
        //Failing any of those means it's not within the bodies of the planetary system
        if (launchworldProperties.isMoon()) {
            isPlanetMoonSystem = (destinationDimensionID == launchworldProperties.getParentPlanet());
            for (int moonDimID : launchworldProperties.getParentProperties().getChildPlanets()) {
                if (destinationDimensionID == moonDimID) {
                    isPlanetMoonSystem = true;
                    break;
                }
            }
        } else {
            for (int moonDimID : launchworldProperties.getChildPlanets()) {
                if (destinationDimensionID == moonDimID) {
                    isPlanetMoonSystem = true;
                    break;
                }
            }
        }

        return isPlanetMoonSystem;
    }

    /**
     * @param currentDimensionID     the dimension ID of the current planet
     * @param destinationDimensionID the dimension ID of the destination planet
     * @param toAsteroids            whether the mission is to an asteroid
     * @return integer for the number of extra blocks the rocket will have to burn for to complete its injection burn when orbit height has been reached
     */
    public static int getTransbodyInjectionBurn(int currentDimensionID, int destinationDimensionID, boolean toAsteroids) {
        int baseInjectionHeight = StellurgyConfiguration.getCurrentConfig().transBodyInjection;
        //This is probably one of the worst ways to do this and I don't really care about realism, just tapering results.... if this turns out to be realistic well then, that's nice.
        //Not like the mod has an semblance of a concept of orbital mechanics anyway :P
        //This is vaugely a multiplier based on TLI burns, burning for 2x as long can get you 4x as far
        //This grabs the body distance multipier, then takes the square root of it, or if warp multiplies by the config option for that
        return (isTravelBetweenBodiesWithinPlanetarySystem(currentDimensionID, destinationDimensionID)) ? (int) (baseInjectionHeight * Math.pow(getBodyDistanceMultiplier(currentDimensionID, destinationDimensionID, toAsteroids), 0.5d)) : (int) StellurgyConfiguration.getCurrentConfig().warpTBIBurnMult * baseInjectionHeight;
    }

    /**
     * @param currentDimensionID     the dimension ID of the current planet
     * @param destinationDimensionID the dimension ID of the destination planet
     * @param toAsteroids            whether the mission is to an asteroid
     * @return double for the burn length needed to reach this particular destination
     */
    public static double getBodyDistanceMultiplier(int currentDimensionID, int destinationDimensionID, boolean toAsteroids) {
        double bodyDistanceMultiplier = 1.0d;
        IDimensionProperties destinationProperties = DimensionManager.getInstance().getDimensionProperties(destinationDimensionID);
        if (destinationProperties.isMoon()) {
            bodyDistanceMultiplier = moonBurnMultiplier(destinationProperties);
        } else {
            for (int moonDimID : destinationProperties.getChildPlanets()) {
                if (currentDimensionID == moonDimID) {
                    bodyDistanceMultiplier = moonBurnMultiplier(DimensionManager.getInstance().getDimensionProperties(moonDimID));
                }
            }
        }
        //If it's asteroids, check the config for the multiplier there
        if (toAsteroids) {
            bodyDistanceMultiplier = StellurgyConfiguration.getCurrentConfig().asteroidTBIBurnMult;
        }
        return bodyDistanceMultiplier;
    }

    /**
     * Moon-view units at which a trip between a planet and its moon takes one base burn: 100, as a
     * moon's raw distance was read while Luna stood at 150, so a trip to Luna is 1.5 base burns
     * again (see {@link AstronomicalBodyHelper#MOON_VIEW_UNITS_AT_LUNA}).
     */
    private static final double MOON_VIEW_UNITS_PER_BASE_BURN = 100d;

    /** The burn multiplier for a trip between {@code moon} and its planet, either way. */
    private static double moonBurnMultiplier(IDimensionProperties moon) {
        return AstronomicalBodyHelper.moonViewUnits(moon.getOrbitalDist()) / MOON_VIEW_UNITS_PER_BASE_BURN;
    }

    /**
     * @param currentDimensionID     the dimension ID of the current planet
     * @param destinationDimensionID the dimension ID of the destination planet
     * @return boolean for whether this is anywhere within the planetary system, not just between bodies
     */
    public static boolean isTravelAnywhereInPlanetarySystem(int currentDimensionID, int destinationDimensionID) {
        return isTravelWithinOrbit(currentDimensionID, destinationDimensionID) || isTravelBetweenBodiesWithinPlanetarySystem(currentDimensionID, destinationDimensionID);
    }

    /**
     * @param currentDimensionID     the dimension ID of the current planet
     * @param destinationDimensionID the dimension ID of the destination planet
     * @return boolean for whether the destination is anywhere in the orbit of the current body, but not to another body
     */
    public static boolean isTravelWithinOrbit(int currentDimensionID, int destinationDimensionID) {
        return (currentDimensionID == destinationDimensionID);
    }

    /**
     * @param spaceStation the space station within this pairing
     * @param planetID     the dimension ID of the planet we are either launching from or going to
     * @return boolean for whether this trip is soley within geostationary orbit to/from the ground
     */
    public static boolean isTravelWithinGeostationaryOrbit(SpaceStationObject spaceStation, int planetID) {
        //Returns true if the planet and the dimension (can be any!) are the same parent and if station is 36300 > x > 35500 km
        //  && 181.0f >= spaceStation.getOrbitalDistance()
        // upper limit removed because it causes players to not know how it works
        return spaceStation.getOrbitingPlanetId() == planetID && (spaceStation.getOrbitalDistance() >= 177.0f);
    }
}
