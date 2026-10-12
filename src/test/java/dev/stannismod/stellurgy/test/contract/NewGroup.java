package dev.stannismod.stellurgy.test.contract;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * The mechanic cluster a class opens a group for, when no existing group holds it. On a class of an
 * own-boot base it is that base's alternative to {@link OwnBoot}: the class is (a member of) a group that
 * has no shared base to stand on yet. The build refuses a blank cluster. The javadoc says why no existing
 * group holds it.
 */
@Documented
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.TYPE)
public @interface NewGroup {

    /** The mechanic cluster the group holds. */
    String value();
}
