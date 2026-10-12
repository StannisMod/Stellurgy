package dev.stannismod.stellurgy.test.server;

import dev.stannismod.stellurgy.test.client.GameDirSeed;

/**
 * What a group's game directory holds before its shared server boots, handed to the seed by the class's
 * {@link AbstractSharedServerTest#seedGameDirectory}.
 */
public interface WorldSeed {

    /** Declare the group's premise into {@code seed}. */
    void seed(GameDirSeed seed);
}
