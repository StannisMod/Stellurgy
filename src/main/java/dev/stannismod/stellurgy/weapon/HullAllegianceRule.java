package dev.stannismod.stellurgy.weapon;

import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;

import dev.stannismod.stellurgy.affs.util.CodeUtils;
import dev.stannismod.stellurgy.integration.vs.VSIntegration;
import dev.stannismod.stellurgy.subsystem.network.SubsystemNetworkManager;
import dev.stannismod.stellurgy.tile.weapon.TileTurret;
import dev.stannismod.stellurgy.tile.weapon.TileWeaponConsole;

/**
 * The friend-or-foe rules a weapon console can put its network on, in the order its button cycles
 * through them.
 */
public enum HullAllegianceRule implements HullAllegiance {

    /**
     * A ship is a friend when its own weapons carry our code: a console or a gun aboard it whose
     * network (or own) code is ours. The ship's armament vouches for it — the rule whose answer the
     * battery's owner controls, by setting the same code on both installations.
     */
    CODE_ON_WEAPONS("msg.weaponConsole.allegiance.codeOnWeapons") {
        @Override
        public boolean isFriend(World world, String shipId, String accessCode) {
            String ours = CodeUtils.normalize(accessCode);
            if (ours.isEmpty() || shipId == null) {
                return false;
            }
            SubsystemNetworkManager manager = SubsystemNetworkManager.of(world);
            if (manager == null) {
                return false;
            }
            for (TileWeaponConsole console : manager.nodesIn(WeaponNetworkDomain.INSTANCE, world,
                    TileWeaponConsole.class)) {
                if (aboard(world, shipId, console.getPos())
                        && ours.equals(CodeUtils.normalize(console.getAccessCode()))) {
                    return true;
                }
            }
            for (TileTurret gun : manager.nodesIn(WeaponNetworkDomain.INSTANCE, world, TileTurret.class)) {
                if (aboard(world, shipId, gun.getPos())
                        && ours.equals(CodeUtils.normalize(gun.getEffectiveAccessCode()))) {
                    return true;
                }
            }
            return false;
        }
    },

    /**
     * A ship is a friend when somebody aboard it carries our code: the crew vouches for the hull, the
     * same credential a creature presents for itself.
     */
    CODE_ON_CREW("msg.weaponConsole.allegiance.codeOnCrew") {
        @Override
        public boolean isFriend(World world, String shipId, String accessCode) {
            if (CodeUtils.normalize(accessCode).isEmpty() || shipId == null) {
                return false;
            }
            for (EntityPlayer player : world.playerEntities) {
                if (VSIntegration.shipIdsAt(world, player.posX, player.posY, player.posZ).contains(shipId)
                        && CodeUtils.entityHasMatchingCode(player, accessCode)) {
                    return true;
                }
            }
            return false;
        }
    },

    /** No ship is a friend. The battery's own ship never becomes its target in the first place. */
    NONE("msg.weaponConsole.allegiance.none") {
        @Override
        public boolean isFriend(World world, String shipId, String accessCode) {
            return false;
        }
    };

    /** Effectively final, process lifetime: set once when the object is built. */
    private final String langKey;

    HullAllegianceRule(String langKey) {
        this.langKey = langKey;
    }

    /** The console's sentence for this rule. */
    public String getLangKey() {
        return langKey;
    }

    /** The rule the console's button moves to from this one. */
    public HullAllegianceRule next() {
        HullAllegianceRule[] all = values();
        return all[(ordinal() + 1) % all.length];
    }

    private static boolean aboard(World world, String shipId, BlockPos pos) {
        return shipId.equals(VSIntegration.registeredShipIdManagingBlock(world, pos));
    }
}
