package zmaster587.advancedRocketry.api.armor;

import java.util.Set;

import net.minecraft.item.ItemStack;
import zmaster587.advancedRocketry.api.atmosphere.AtmosphereHazard;

import javax.annotation.Nonnull;

public interface IProtectiveArmor {
    /**
     * Whether this piece keeps its wearer safe from what an atmosphere is doing to them.
     * <p>
     * <b>Asked about HAZARDS rather than about a named atmosphere.</b> A piece of armour used to be
     * handed a whole atmosphere and expected to recognise it, which meant a list of every atmosphere
     * the mod shipped, inside every implementation — and a modpack that added air of its own got no
     * protection from anything until each of those lists was edited. What a suit stops is a kind of
     * harm, and that is a far shorter and far more stable list.
     *
     * @param hazards             what this air is doing; protecting means answering for ALL of them
     * @param needsSuppliedOxygen whether there is no oxidiser outside to concentrate, so a suit here
     *                            has to breathe from what it carries. This is what decides whether
     *                            wearing one costs anything.
     * @param stack               the piece being asked about
     * @param commitProtection    true when the answer is being acted on, so anything protection
     *                            actually consumes may be consumed now. False merely enquires — and
     *                            an enquiry must never spend anything, because the same question is
     *                            asked speculatively from places that are not a tick.
     */
    boolean protectsFrom(Set<AtmosphereHazard> hazards, boolean needsSuppliedOxygen,
                         @Nonnull ItemStack stack, boolean commitProtection);
}
