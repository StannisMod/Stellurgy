package dev.stannismod.stellurgy.test.contract;

import java.lang.annotation.*;

/**
 * The contract clauses this test method pins, by anchor: {@code @Pins("INV-SFM-12")}.
 *
 * <p>An anchor is defined in {@code docs/system} as a bold list item. A {@code [T]} clause names the
 * methods that pin it, and it counts as pinned only through a named method that carries this annotation
 * with its anchor AND a witness ({@link RedWitness}, or the older {@link LegacyWitness}). The compiler attaches the annotation to the method it is
 * written on, and {@link PinMapCheck} reads it by reflection inside the build, so no method can inherit
 * another's anchor from a comment above it. The javadoc says WHY the method pins the clause; this says
 * THAT it does.</p>
 */
@Documented
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.METHOD)
public @interface Pins {

    /** The anchors this method pins; every one must be defined in {@code docs/system}. */
    String[] value();
}
