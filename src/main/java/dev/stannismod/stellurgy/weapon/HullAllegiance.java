package dev.stannismod.stellurgy.weapon;

import net.minecraft.world.World;

/**
 * Whether a ship is on our side, for an installation that knows its own access code.
 *
 * <p>A creature proves it is a friend by carrying the code; a hull carries nothing. So what makes a
 * HULL a friend is a rule, and installations differ in which rule they trust — which is why this is a
 * question with several answers rather than one check. The weapon console picks the rule its network
 * goes by; {@link HullAllegianceRule} holds the ones that ship.</p>
 *
 * <p>A rule never answers "friend" for an empty code: an installation that has set none recognises
 * nobody, hull or creature, so that a battery that shoots nothing cannot be confused with a broken
 * one.</p>
 */
public interface HullAllegiance {

    /**
     * @param shipId     the physics substrate's id of the hull being asked about
     * @param accessCode the asking installation's code; empty when it has set none
     */
    boolean isFriend(World world, String shipId, String accessCode);
}
