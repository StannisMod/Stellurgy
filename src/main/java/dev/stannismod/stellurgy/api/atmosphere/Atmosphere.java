package dev.stannismod.stellurgy.api.atmosphere;

import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityLivingBase;
import dev.stannismod.stellurgy.api.StellurgyConfiguration;

/**
 * A named atmosphere: what the rest of the mod passes around when it means "the air here".
 * <p>
 * <b>A class, not an interface.</b> It used to be {@code IAtmosphere}, which invited anyone to supply
 * their own answers to "can this be breathed" and "will this burn" — and those answers then had to be
 * trusted, because nothing could check them against anything. Air is a composition and everything
 * else about it is derived from that; an implementation that could assert otherwise is a second
 * source of truth wearing an extension point's clothes.
 * <p>
 * <b>And it can no longer be told to lie.</b> The two setters that let a pack flip breathability and
 * combustion on a shared singleton are gone. Adding an atmosphere is a matter of data — a composition
 * — rather than of overriding a method that says what the data means.
 * <p>
 * What survives here is the NAME and the two flags the rest of the mod still reads. Both flags are
 * still assigned by hand at construction, which is the last of the old model left standing: they
 * become derivations of a composition when planets carry one.
 * <p>Every static field of this type is effectively final, process lifetime: built once at class initialisation, and holds an immutable value.</p>
 */
public class Atmosphere {

    /** Packet-safe send for atmosphere effects: FakePlayers / headless test
     *  players have no network connection — a raw sendToPlayer would NPE in
     *  the netty pipeline and crash the server tick loop. */
    public static void sendToRealPlayer(dev.stannismod.stellurgy.libvulpes.network.BasePacket packet,
                                        net.minecraft.entity.player.EntityPlayer player) {
        if (player instanceof net.minecraft.entity.player.EntityPlayerMP
                && ((net.minecraft.entity.player.EntityPlayerMP) player).connection == null) {
            return;
        }
        dev.stannismod.stellurgy.libvulpes.network.PacketHandler.sendToPlayer(packet, player);
    }


    //We're probably not getting a polluted atmosphere type
    public static final Atmosphere AIR = new Atmosphere(false, true, "air");
    public static final Atmosphere PRESSURIZEDAIR = new Atmosphere(false, true, true, "PressurizedAir");
    // Twelve of these used to be twelve CLASSES, each carrying its own copy of the same tick method.
    // What they do now lives in one table, and what is left of them here is a name and two flags.
    public static final Atmosphere LOWOXYGEN = new Atmosphere(true, false, true, "lowO2");
    public static final Atmosphere HIGHOXYGEN = new Atmosphere(true, false, true, "highO2");
    public static final Atmosphere VACUUM = new Atmosphere(true, false, false, "vacuum");
    public static final Atmosphere HIGHPRESSURE = new Atmosphere(true, false, true, "HighPressure");
    public static final Atmosphere SUPERHIGHPRESSURE = new Atmosphere(true, false, true, "SuperHighPressure");
    public static final Atmosphere VERYHOT = new Atmosphere(true, false, true, "VeryHot");
    public static final Atmosphere SUPERHEATED = new Atmosphere(true, false, true, "Superheated");
    public static final Atmosphere NOO2 = new Atmosphere(true, false, false, "NoO2");
    public static final Atmosphere HIGHPRESSURENOO2 = new Atmosphere(true, false, false, "HighPressureNoO2");
    public static final Atmosphere SUPERHIGHPRESSURENOO2 = new Atmosphere(true, false, false, "SuperHighPressureNoO2");
    public static final Atmosphere VERYHOTNOO2 = new Atmosphere(true, false, false, "VeryHotNoO2");
    public static final Atmosphere SUPERHEATEDNOO2 = new Atmosphere(true, false, false, "SuperheatedNoOxygen");


    private final boolean allowsCombustion;
    private final boolean isBreathable;
    private final boolean canTick;
    private final String name;

    public Atmosphere(boolean canTick, boolean isBreathable, String name) {
        this(canTick, isBreathable, isBreathable, name);
    }

    public Atmosphere(boolean canTick, boolean isBreathable, boolean allowsCombustion, String name) {
        this.allowsCombustion = allowsCombustion;
        this.isBreathable = isBreathable;
        this.canTick = canTick;
        this.name = name;
    }

    /**
     * Should the gas run a tick on every player in it?  Calls onTick(EntityLiving base)
     *
     * @return true if the atmosphere performs an action every tick
     */
    public boolean canTick() {
        return canTick;
    }

    //TODO: check for all entities

    /**
     * @param player living entity inside this atmosphere we are ticking
     * @return true if the atmosphere does not affect the entity in any way
     */
    public boolean isImmune(EntityLivingBase player) {
        return dev.stannismod.stellurgy.atmosphere.hazard.AtmosphereHazards.isImmune(
                dev.stannismod.stellurgy.atmosphere.hazard.AtmosphereHazards.exposureOf(this),
                player);
    }

    public boolean isImmune(Class<? extends Entity> clazz) {
        return isBreathable() || StellurgyConfiguration.getCurrentConfig().bypassEntity.contains(clazz);
    }

    public boolean isBreathable() {
        return isBreathable;
    }

    /**
     * To be used to check if combustion can occur in this atmosphere, furnaces, torches, engines, etc could run this check
     *
     * @return true if the atmosphere is combustable
     */
    public boolean allowsCombustion() {
        return allowsCombustion;
    }

    /**
     * @return unlocalized message to display when player is in the gas with no protection
     */
    public String getDisplayMessage() {
        String key = dev.stannismod.stellurgy.atmosphere.hazard.AtmosphereHazards
                .exposureOf(this).messageKey();
        return key.isEmpty() ? "" : dev.stannismod.stellurgy.libvulpes.LibVulpes.proxy.getLocalizedString(key);
    }

    //TODO: tick for all entities

    /**
     * If the canTick() returns true then then this is called every tick on EntityLivingBase objects located inside this atmosphere
     *
     * @param player entity being ticked
     */
    public void onTick(EntityLivingBase player) {
        dev.stannismod.stellurgy.atmosphere.hazard.HazardExposure exposure =
                dev.stannismod.stellurgy.atmosphere.hazard.AtmosphereHazards.exposureOf(this);
        // Nothing acts on this tick: ask no further. Asking about protection is not free — it spends
        // a unit of the suit's air — so it is asked only when there is something to be protected FROM.
        if (!exposure.firesOn(player.world.getTotalWorldTime())) {
            return;
        }
        if (dev.stannismod.stellurgy.atmosphere.hazard.AtmosphereHazards.isImmune(exposure, player)) {
            return;
        }
        exposure.applyTo(player);
    }

    public String getUnlocalizedName() {
        return name;
    }
}
