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

    /** The stamp of the most recent deck hold opened on this body, counting up from {@code 0} before
     *  the first: a pending deck point compares it to tell a hold opened during its window from one
     *  that predates it. Held by the body, so the two sides of one player in one JVM count apart. */
    long stellurgy$holdStamp();

    void stellurgy$setHoldStamp(long stamp);

    /** How many teleports this body has been sent or has applied on this side, counting up from
     *  {@code 0}: the server counts each position-look it sends the player, his client each one it
     *  applies. An intent to hold the body remembers the count it began at, and a different count
     *  means somebody has put the body somewhere since. */
    int stellurgy$teleports();

    void stellurgy$countTeleport();
}
