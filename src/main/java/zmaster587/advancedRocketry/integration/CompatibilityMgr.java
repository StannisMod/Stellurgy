package zmaster587.advancedRocketry.integration;

/**
 * Which optional mods this launch found.
 *
 * <p>OWNER of both flags: the LOADER; LIFETIME: the launch. A mod list is built once by FML and does
 * not change while the game runs, which is why an answer taken at init stays true — and why neither
 * flag is released anywhere.</p>
 *
 * <p>A third flag, Thermal Expansion, was removed: it was written by a constructor and by a method
 * nothing called, and read nowhere at all. So was the instance the mod held solely to run that
 * constructor. A flag nobody reads reads like a capability check that exists.</p>
 */
public class CompatibilityMgr {

    public static boolean isSpongeInstalled;

    /**
     * Whether GregTech is on this launch's mod list.
     *
     * <p><b>DELIBERATELY UNREAD FOR NOW, and this note is the reason it survives.</b> GregTech
     * compatibility means recipes, and those are not designed yet; the flag is kept because that
     * design's first question is whether GregTech is there at all, and because the answer costs one
     * call at init.</p>
     *
     * <p>The id is GregTech CE Unofficial's own ({@code GTValues.MODID}, {@code "gregtech"}), which
     * is what this project builds against. The flag this replaces asked for {@code "gregtech_addon"}
     * — a 1.7.10-era addon's id — so it would have answered "no" on every launch, and nothing would
     * have noticed, because nothing read it.</p>
     */
    public static boolean isGregtechInstalled;
}
