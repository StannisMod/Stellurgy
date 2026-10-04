package dev.stannismod.stellurgy.test.server;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * The game directory a group's shared server boots over: a {@link WorldSeed} class, instantiated by
 * {@link SharedServerScope} before the class run's one boot.
 *
 * <p>A class annotation and not a hook on the test instance, because the shared server is booted when
 * the class run OPENS, before any instance exists to be asked. The seeder is a class with a public
 * no-argument constructor, so nothing static carries the seed.</p>
 *
 * <p>A seed is the WORLD's premise — a galaxy declared in {@code planetDefs.xml}, a config read at
 * boot — and every scenario of the group stands in it. Scenarios needing two different premises are
 * two groups.</p>
 */
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.TYPE)
public @interface SeededWorld {

    Class<? extends WorldSeed> value();
}
