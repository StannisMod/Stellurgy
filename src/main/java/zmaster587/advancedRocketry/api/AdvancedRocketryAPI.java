package zmaster587.advancedRocketry.api;

import net.minecraft.enchantment.Enchantment;
import zmaster587.advancedRocketry.AdvancedRocketry;
import zmaster587.advancedRocketry.api.atmosphere.IAtmosphereSealHandler;
import zmaster587.advancedRocketry.api.dimension.solar.IGalaxy;

/**
 * The mod's services, for anything outside it that needs one.
 *
 * <p>Created by Dark(DarkGuardsman, Robert) on 1/6/2016.</p>
 *
 * <h2>Why these are methods and not fields</h2>
 *
 * <p>They were four {@code public static} fields, assigned from wherever each service happened to be
 * constructed. A mutable static holding a collaborator has one characteristic failure — several
 * writers, no protocol, last one wins — and this had already produced it: the gravity manager was
 * assigned both from the mod's own init and from a static initialiser on {@code GravityHandler},
 * which runs when that class is loaded, which is what constructing it at the other site does. Two
 * handlers were built, the first published and then replaced; anything reading the field in between
 * held the orphan.</p>
 *
 * <p>The services are owned by the mod object now — a singleton Forge guarantees, rather than one
 * this class asserts — and each has exactly one writer whose second call is a loud error. The
 * accessors below are short on purpose: a caller that needs a service should not have to recite a
 * lifecycle to get one.</p>
 *
 * <p>A {@code null} answer means the service is not installed YET, which for a caller running after
 * mod init cannot happen; it is not a case to code around, and a caller that sees one has found a
 * lifecycle bug worth reporting rather than a value worth defaulting.</p>
 */
public class AdvancedRocketryAPI {

    /** Whether a block seals an atmosphere, and the registry behind that answer. */
    public static IAtmosphereSealHandler atmosphereSealHandler() {
        return AdvancedRocketry.instance == null ? null : AdvancedRocketry.instance.sealHandler();
    }

    /** Space stations and the objects that live in orbit. */
    public static ISpaceObjectManager spaceObjectManager() {
        return AdvancedRocketry.instance == null ? null : AdvancedRocketry.instance.spaceObjects();
    }

    /** Stars, planets and the dimensions behind them. */
    public static IGalaxy galaxy() {
        return AdvancedRocketry.instance == null ? null : AdvancedRocketry.instance.galaxy();
    }

    /** Per-entity gravity, including what other mods have overridden it with. */
    public static IGravityManager gravityManager() {
        return AdvancedRocketry.instance == null ? null : AdvancedRocketry.instance.gravity();
    }

    /** Left as a field deliberately: it is a registry object Forge fills, not a service, and it
     *  belongs to the same family as the block and item holders. */
    public static Enchantment enchantmentSpaceProtection;
}
