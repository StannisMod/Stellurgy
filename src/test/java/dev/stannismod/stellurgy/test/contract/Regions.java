package dev.stannismod.stellurgy.test.contract;

import java.lang.annotation.*;

/**
 * The regions of a quantified clause this method visits: {@code @Regions(of = "INV-SFM-12", value =
 * {"saturated", "an empty wheel"})}.
 *
 * <p>A clause whose text lists {@code REGIONS: `a`, `b`, `c`} is pinned only when the methods that pin
 * it visit every listed region between them, and a region named here must be one the clause lists. That
 * the method really reaches each region -- counted at the call -- is the reviewer's to check; this is the
 * claim, held where the compiler keeps it.</p>
 */
@Documented
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.METHOD)
@Repeatable(Regions.List.class)
public @interface Regions {

    /** The anchor of the clause whose regions these are; the method must also {@link Pins} it. */
    String of();

    /** The regions visited, each spelled as the clause spells it. */
    String[] value();

    /** The container javac builds when a method visits the regions of more than one clause. */
    @Documented
    @Retention(RetentionPolicy.RUNTIME)
    @Target(ElementType.METHOD)
    @interface List {
        Regions[] value();
    }
}
