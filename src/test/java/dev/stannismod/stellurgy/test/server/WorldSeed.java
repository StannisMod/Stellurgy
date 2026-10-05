package dev.stannismod.stellurgy.test.server;

import dev.stannismod.stellurgy.test.client.GameDirSeed;

/** What a {@link SeededWorld} group's game directory holds before its shared server boots. */
public interface WorldSeed {

    /** Declare the group's premise into {@code seed}. */
    void seed(GameDirSeed seed);
}
