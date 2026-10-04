package com.github.stannismod.forge.testing.junit;

import java.lang.annotation.ElementType;
import java.lang.annotation.Inherited;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Which {@link TestClassScope} a class run by {@link ClassScopeRunner} shares between its methods.
 * Inherited, like {@code @RunWith}, so a base class declares it once for all its subclasses; a
 * subclass may declare its own to replace it.
 */
@Inherited
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.TYPE)
public @interface ClassScope {

    Class<? extends TestClassScope> value();
}
