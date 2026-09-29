package dev.stannismod.stellurgy.integration.vs;

/**
 * An entity's deck episode, stored ON the entity (a mixin adds the field to {@code Entity}).
 *
 * <p>The state belongs to the body it describes and goes away with it, so nothing process-wide has
 * to be kept, keyed or released. {@code null} means no deck holds the entity.</p>
 */
public interface DeckHeld {

    DeckFrameTick.Episode stellurgy$deckEpisode();

    void stellurgy$setDeckEpisode(DeckFrameTick.Episode episode);
}
