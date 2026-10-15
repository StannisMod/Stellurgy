package com.github.stannismod.forge.testing.server;

import java.util.Map;

/**
 * What a game directory holds before its FIRST boot, stated as files the harness writes — never as a
 * path the test writes through.
 *
 * <p>A consuming project implements it with a TYPE that renders its own vocabulary (config keys, a
 * planet catalogue) into files; {@link ServerRoot#seed} writes them. Two abstract methods on purpose: a
 * lambda cannot implement it, so every implementation is a named class a build can hold to a list.</p>
 */
public interface StartFiles {

    /**
     * Every file of the start: a path RELATIVE to the game directory (forward slashes, no {@code ..}) to
     * its text, written as UTF-8.
     */
    Map<String, String> files();

    /** One line saying what the start is, for the boot's log. */
    String describe();
}
