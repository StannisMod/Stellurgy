package zmaster587.advancedRocketry.atmosphere;

import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityLivingBase;
import zmaster587.advancedRocketry.api.ARConfiguration;
import zmaster587.advancedRocketry.api.IAtmosphere;
import zmaster587.advancedRocketry.api.atmosphere.AtmosphereRegister;

public class AtmosphereType implements IAtmosphere {

    /** Packet-safe send for atmosphere effects: FakePlayers / headless test
     *  players have no network connection — a raw sendToPlayer would NPE in
     *  the netty pipeline and crash the server tick loop. */
    public static void sendToRealPlayer(zmaster587.libVulpes.network.BasePacket packet,
                                        net.minecraft.entity.player.EntityPlayer player) {
        if (player instanceof net.minecraft.entity.player.EntityPlayerMP
                && ((net.minecraft.entity.player.EntityPlayerMP) player).connection == null) {
            return;
        }
        zmaster587.libVulpes.network.PacketHandler.sendToPlayer(packet, player);
    }


    //We're probably not getting a polluted atmosphere type
    public static final AtmosphereType AIR = new AtmosphereType(false, true, "air");
    public static final AtmosphereType PRESSURIZEDAIR = new AtmosphereType(false, true, true, "PressurizedAir");
    // Twelve of these used to be twelve CLASSES, each carrying its own copy of the same tick method.
    // What they do now lives in one table, and what is left of them here is a name and two flags.
    public static final AtmosphereType LOWOXYGEN = new AtmosphereType(true, false, true, "lowO2");
    public static final AtmosphereType HIGHOXYGEN = new AtmosphereType(true, false, true, "highO2");
    public static final AtmosphereType VACUUM = new AtmosphereType(true, false, false, "vacuum");
    public static final AtmosphereType HIGHPRESSURE = new AtmosphereType(true, false, true, "HighPressure");
    public static final AtmosphereType SUPERHIGHPRESSURE = new AtmosphereType(true, false, true, "SuperHighPressure");
    public static final AtmosphereType VERYHOT = new AtmosphereType(true, false, true, "VeryHot");
    public static final AtmosphereType SUPERHEATED = new AtmosphereType(true, false, true, "Superheated");
    public static final AtmosphereType NOO2 = new AtmosphereType(true, false, false, "NoO2");
    public static final AtmosphereType HIGHPRESSURENOO2 = new AtmosphereType(true, false, false, "HighPressureNoO2");
    public static final AtmosphereType SUPERHIGHPRESSURENOO2 = new AtmosphereType(true, false, false, "SuperHighPressureNoO2");
    public static final AtmosphereType VERYHOTNOO2 = new AtmosphereType(true, false, false, "VeryHotNoO2");
    public static final AtmosphereType SUPERHEATEDNOO2 = new AtmosphereType(true, false, false, "SuperheatedNoOxygen");

    static {
        AtmosphereRegister.getInstance().registerAtmosphere(AIR);
        AtmosphereRegister.getInstance().registerAtmosphere(PRESSURIZEDAIR);
        AtmosphereRegister.getInstance().registerAtmosphere(VACUUM);
        AtmosphereRegister.getInstance().registerAtmosphere(LOWOXYGEN);
        AtmosphereRegister.getInstance().registerAtmosphere(HIGHOXYGEN);
        AtmosphereRegister.getInstance().registerAtmosphere(HIGHPRESSURE);
        AtmosphereRegister.getInstance().registerAtmosphere(SUPERHIGHPRESSURE);
        AtmosphereRegister.getInstance().registerAtmosphere(VERYHOT);
        AtmosphereRegister.getInstance().registerAtmosphere(SUPERHEATED);
        AtmosphereRegister.getInstance().registerAtmosphere(NOO2);
        AtmosphereRegister.getInstance().registerAtmosphere(HIGHPRESSURENOO2);
        AtmosphereRegister.getInstance().registerAtmosphere(SUPERHIGHPRESSURENOO2);
        AtmosphereRegister.getInstance().registerAtmosphere(VERYHOTNOO2);
        AtmosphereRegister.getInstance().registerAtmosphere(SUPERHEATEDNOO2);
    }

    private boolean allowsCombustion;
    private boolean isBreathable;
    private boolean canTick;
    private String name;

    public AtmosphereType(boolean canTick, boolean isBreathable, String name) {
        this.allowsCombustion = isBreathable;
        this.isBreathable = isBreathable;
        this.canTick = canTick;
        this.name = name;
    }

    public AtmosphereType(boolean canTick, boolean isBreathable, boolean allowsCombustion, String name) {
        this(canTick, isBreathable, name);
        this.allowsCombustion = allowsCombustion;
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
        return zmaster587.advancedRocketry.atmosphere.hazard.AtmosphereHazards.isImmune(
                zmaster587.advancedRocketry.atmosphere.hazard.AtmosphereHazards.exposureOf(this),
                player);
    }

    public boolean isImmune(Class<? extends Entity> clazz) {
        return isBreathable() || ARConfiguration.getCurrentConfig().bypassEntity.contains(clazz);
    }

    @Override
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
     * Sets the atmosphere to be breathable or not breathable
     *
     * @param isBreathable
     */
    public void setIsBreathable(boolean isBreathable) {
        this.isBreathable = isBreathable;
    }

    /**
     * Sets the atmosphere to allow combustion or not to allow combustion
     *
     * @param allowsCombustion
     */
    public void setAllowsCombustion(boolean allowsCombustion) {
        this.allowsCombustion = allowsCombustion;
    }

    /**
     * @return unlocalized message to display when player is in the gas with no protection
     */
    public String getDisplayMessage() {
        String key = zmaster587.advancedRocketry.atmosphere.hazard.AtmosphereHazards
                .exposureOf(this).messageKey();
        return key.isEmpty() ? "" : zmaster587.libVulpes.LibVulpes.proxy.getLocalizedString(key);
    }

    //TODO: tick for all entities

    /**
     * If the canTick() returns true then then this is called every tick on EntityLivingBase objects located inside this atmosphere
     *
     * @param player entity being ticked
     */
    public void onTick(EntityLivingBase player) {
        zmaster587.advancedRocketry.atmosphere.hazard.AtmosphereHazards.exposureOf(this)
                .applyTo(player);
    }

    @Override
    public String getUnlocalizedName() {
        return name;
    }
}
