package com.github.stannismod.forge.testing.server;

/**
 * A change to ONE file a boot left in its game directory, applied by {@link ServerRoot#editWhileDown}
 * while no server runs on that directory — the arrangement of a test whose subject is what the NEXT boot
 * makes of the change.
 *
 * <p>A consuming project implements it with a TYPE naming the file (the world's saved planet catalogue,
 * say) and the edit; the harness reads the file, hands its text to {@link #edit} and writes the answer.
 * Two abstract methods on purpose: a lambda cannot implement it, so every implementation is a named
 * class a build can hold to a list.</p>
 */
public interface BetweenBootsEdit {

    /** The file, RELATIVE to the game directory (forward slashes, no {@code ..}). */
    String file();

    /**
     * The file's new text, given the text the last boot left. An answer equal to {@code saved} is refused:
     * an edit that changes nothing arranges nothing.
     */
    String edit(String saved);
}
