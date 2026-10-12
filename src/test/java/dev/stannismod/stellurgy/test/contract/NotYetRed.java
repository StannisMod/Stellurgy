package dev.stannismod.stellurgy.test.contract;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Repeatable;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * A break of production that was tried and left a verdict GREEN: an attempt, never a witness. It is a
 * separate annotation so that nothing can count it as one -- a record that says "not red" and still
 * reads as a {@link RedWitness} is the failure this type exists to make unwritable.
 *
 * <p>Only records taken before such attempts stopped being shippable carry it: a verdict that stays
 * green under its inversion has no witness, and its test does not ship until it does.</p>
 */
@Documented
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.METHOD, ElementType.TYPE})
@Repeatable(NotYetRed.List.class)
public @interface NotYetRed {

    /** What was broken and what stayed green, verbatim. */
    String prose();

    /** The container javac builds when a method carries more than one attempt. */
    @Documented
    @Retention(RetentionPolicy.RUNTIME)
    @Target({ElementType.METHOD, ElementType.TYPE})
    @interface List {
        NotYetRed[] value();
    }
}
