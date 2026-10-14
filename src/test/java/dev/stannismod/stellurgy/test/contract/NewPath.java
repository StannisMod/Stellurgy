package dev.stannismod.stellurgy.test.contract;

import java.lang.annotation.*;

/**
 * The player's path an e2e walks that no existing e2e walks: an e2e is extended before one is added, so
 * a new e2e class names the path it is for. The build requires it on every e2e class and refuses it on any
 * other, and refuses a blank path.
 */
@Documented
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.TYPE)
public @interface NewPath {

    /** The path, as a player's walk from his start. */
    String value();
}
