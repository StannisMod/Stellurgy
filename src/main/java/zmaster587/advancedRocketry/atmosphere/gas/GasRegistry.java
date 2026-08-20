package zmaster587.advancedRocketry.atmosphere.gas;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Every substance an atmosphere in this mod can be made of.
 * <p>
 * <b>A gas is here because something reads it</b> — as a resource, or as the driver of a hazard.
 * Nothing is listed for completeness: argon and the noble traces are real and are deliberately absent,
 * because storage nobody consults is a list rather than a model. Adding one later is a row here plus a
 * threshold, never a class.
 * <p>
 * The eleven below are what the solar system actually needs: the air of Earth, Mars and Venus, the
 * nitrogen-methane of Titan, the hydrogen-helium of the giants with their ammonia and hydrogen
 * sulphide decks, and carbon monoxide for the thin cold worlds.
 * <p>
 * <b>Thresholds are authored in parts per million of an atmosphere</b> — so the numbers below read as
 * ppm and can be compared with the real exposure limits they came from. The composition itself is
 * stored a thousand times finer, and the conversion happens once, in the constructor.
 */
public final class GasRegistry {

    private static final Map<String, Gas> BY_NAME = new HashMap<>();
    private static final List<Gas> ALL = new ArrayList<>();

    /** The diluent. Inert on purpose: it is what makes an oxygen FRACTION mean something. */
    public static final Gas NITROGEN = register(new Gas("nitrogen", "nitrogen", 28.0D, 0, GasRole.INERT));

    /** The oxidiser. Breathing and fire both want it, at different concentrations. */
    public static final Gas OXYGEN = register(new Gas("oxygen", "oxygen", 32.0D, 0, GasRole.OXIDISER));

    /** What a crew makes. Harmful in its own right well before it displaces the oxygen. */
    public static final Gas CARBON_DIOXIDE =
            register(new Gas("carbondioxide", "carbon_dioxide", 44.0D, 50_000, GasRole.WASTE));

    /** Vapour. Its job in the model is that it turns a corrosive gas into an acid. */
    public static final Gas WATER = register(new Gas("water", "water", 18.0D, 0, GasRole.SOLVENT));

    /** The giants are mostly this, and it burns with an oxidiser you brought. */
    public static final Gas HYDROGEN = register(new Gas("hydrogen", "hydrogen", 2.0D, 0, GasRole.FUEL));

    /** Inert, light, and the reason a big cold world keeps what a small warm one loses. */
    public static final Gas HELIUM = register(new Gas("helium", "helium", 4.0D, 0, GasRole.INERT));

    /** Titan's other half, and a cloud deck on every giant. */
    public static final Gas METHANE = register(new Gas("methane", "methane", 16.0D, 0, GasRole.FUEL));

    /** A giant's upper cloud, poisonous at a few hundred ppm. */
    public static final Gas AMMONIA =
            register(new Gas("ammonia", "ammonia", 17.0D, 300, GasRole.TOXIC, GasRole.FUEL));

    /** Uranus's clouds and every volcanic world: poison, and acid once it is wet. */
    public static final Gas HYDROGEN_SULFIDE =
            register(new Gas("hydrogensulfide", "hydrogen_sulfide", 34.0D, 100,
                    GasRole.TOXIC, GasRole.CORROSIVE));

    /** Venus and Io. The same pair of roles, and the reason a hull there is consumable. */
    public static final Gas SULFUR_DIOXIDE =
            register(new Gas("sulfurdioxide", "sulfur_dioxide", 64.0D, 100,
                    GasRole.TOXIC, GasRole.CORROSIVE));

    /** Thin cold worlds carry it, and it burns — which is why it is not merely a poison. */
    public static final Gas CARBON_MONOXIDE =
            register(new Gas("carbonmonoxide", "carbon_monoxide", 28.0D, 100,
                    GasRole.TOXIC, GasRole.FUEL));

    private GasRegistry() {
    }

    private static Gas register(Gas gas) {
        if (gas.roles().isEmpty()) {
            throw new IllegalArgumentException("a gas with no role has no reason to be modelled: "
                    + gas.name());
        }
        BY_NAME.put(gas.name(), gas);
        ALL.add(gas);
        return gas;
    }

    /** Every modelled substance, in declaration order. */
    public static List<Gas> all() {
        return Collections.unmodifiableList(ALL);
    }

    /**
     * The gas of this name, or null. Null is what a save written by a pack that has since removed a
     * gas reads as, and a caller must drop that share rather than guess at a substitute.
     */
    public static Gas byName(String name) {
        return name == null ? null : BY_NAME.get(name);
    }

    /** Every gas with this role. The shape every predicate asks in: "is there an oxidiser here". */
    public static List<Gas> withRole(GasRole role) {
        List<Gas> found = new ArrayList<>();
        for (Gas gas : ALL) {
            if (gas.is(role)) {
                found.add(gas);
            }
        }
        return found;
    }
}
