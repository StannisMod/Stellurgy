package dev.stannismod.stellurgy.weapon;

import org.apache.logging.log4j.Logger;
import dev.stannismod.stellurgy.Stellurgy;
import dev.stannismod.stellurgy.subsystem.network.ISubsystemNetworkController;
import dev.stannismod.stellurgy.subsystem.network.ISubsystemNetworkNode;
import dev.stannismod.stellurgy.subsystem.network.SubsystemNetworkDomain;
import dev.stannismod.stellurgy.subsystem.network.SubsystemNetworkState;
import dev.stannismod.stellurgy.tile.weapon.TileWeaponConsole;

import java.util.List;

/**
 * The weapons network: energy shared between guns, and one place to point them all.
 *
 * <h3>The network is a convenience, and nothing depends on it</h3>
 * <p>Every gun works alone. It holds its own energy buffer, picks its own target and fires with no
 * cable, console or network attached — a battery of one is a supported build, not a degraded one.
 * What joining a network buys is what a player would otherwise do by hand: one console aiming a
 * dozen guns at the same thing, and a shared supply that fills the guns that matter first under a
 * deficit. Losing the network loses those conveniences and nothing else, which is why no code path
 * below asks whether a state exists before deciding whether a gun may fire.</p>
 *
 * <h3>The commodity is Forge Energy</h3>
 * <p>Guns are sinks, generators and capacitor banks are sources, and the unit is FE per tick — the
 * same unit the rest of the mod's power is in, so a player wiring a gun into a ship's supply is not
 * learning a second kind of energy.</p>
 */
public final class WeaponNetworkDomain extends SubsystemNetworkDomain {

    /** Effectively final, process lifetime: built once at class initialisation. A domain holds nothing but its name. */
    public static final WeaponNetworkDomain INSTANCE = new WeaponNetworkDomain();

    private WeaponNetworkDomain() {
        super("Weapon");
    }

    @Override
    public SubsystemNetworkState newState() {
        return new WeaponNetworkState();
    }

    @Override
    public void onComponentRebuilt(SubsystemNetworkState state, List<ISubsystemNetworkController> controllers,
                                   List<ISubsystemNetworkNode> members) {
        if (!(state instanceof WeaponNetworkState)) {
            return;
        }
        WeaponNetworkState weapons = (WeaponNetworkState) state;
        boolean hasConsole = false;
        for (ISubsystemNetworkController controller : controllers) {
            hasConsole |= controller instanceof TileWeaponConsole;
        }
        weapons.setHasConsole(hasConsole);
        // A network with no console left commands nothing. Keeping the last console's target would
        // leave a battery firing at a point nobody can retract, which is the one failure mode a
        // player cannot fix by breaking something.
        if (controllers.isEmpty()) {
            weapons.dropTarget();
            return;
        }
        seedFromLatestOrders(weapons, controllers);
    }

    /**
     * The network carries the LATEST orders any of its consoles holds — the last order wins.
     *
     * <p>The network is not saved, so after a restart this is the only way its orders come back, and
     * the same rule settles every other way consoles can disagree: two batteries joined by a cable,
     * or a console that was unloaded while the others were given a new order. A console's copy is
     * taken when it is no older than the network's own orders: an inherited network that has been
     * told nothing since keeps what it has unless a console says something newer, and one whose
     * target was dropped for want of a console gets it back from the console that returns. Orders
     * the network was given and no console has copied yet are newer than any console's, and stand.</p>
     */
    private static void seedFromLatestOrders(WeaponNetworkState weapons,
                                             List<ISubsystemNetworkController> controllers) {
        TileWeaponConsole latest = null;
        long latestStamp = weapons.getOrdersStamp();
        for (ISubsystemNetworkController controller : controllers) {
            if (!(controller instanceof TileWeaponConsole)) {
                continue;
            }
            TileWeaponConsole console = (TileWeaponConsole) controller;
            long stamp = console.getSavedOrdersStamp();
            if (stamp != WeaponNetworkState.NO_STAMP && stamp >= latestStamp
                    && (latest == null || stamp > latest.getSavedOrdersStamp())) {
                latest = console;
                latestStamp = stamp;
            }
        }
        if (latest != null) {
            weapons.adoptOrders(latest.getSavedOrders(), latestStamp);
        }
    }

    @Override
    public Logger getLogger() {
        return Stellurgy.logger;
    }
}
