package dev.stannismod.stellurgy.api;

public class Constants {
    public static final String modId = "stellurgy";
    /**
     * Load order only - nothing here is required. libVulpes ships inside this mod now, and these
     * are the orderings it declared while it was a mod of its own, carried over unchanged. Two have
     * a reason visible in its source: it reads IC2's items and Immersive Engineering's coils during
     * init. The cofhcore and buildcraft ones predate this tree and were not re-examined.
     */
    public static final String DEPENDENCIES = "after:ic2;after:cofhcore;after:buildcraft|core;after:immersiveengineering";
    public static final int INVALID_PLANET = Integer.MIN_VALUE + 1; //min value is used for warp
    public static final int GENTYPE_ASTEROID = 2;
    public static final int STAR_ID_OFFSET = 10000;

    /**
     * Config category and key of the per-dimension WorldInfo master switch.
     *
     * <p>Shared because two readers must agree on them and cannot share code:
     * {@code StellurgyConfiguration} reads the flag in mod pre-init, while
     * {@code StellurgyMixinPlugin} must read the same value during the coremod phase,
     * long before that singleton is populated. These are compile-time constants
     * (JLS §13.1), so javac inlines the literal and the coremod never loads this
     * class — but renaming either one now breaks compilation instead of silently
     * leaving the mixin gate stuck open.</p>
     */
    public static final String CONFIG_CATEGORY_PLANET = "Planet";
    public static final String CONFIG_KEY_PER_DIM_WORLD_INFO = "perDimWorldInfo";
}
