package dev.stannismod.stellurgy.test.contract;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Why a mechanics class runs on a boot of its own rather than as a method of a group: one or more reasons
 * from the closed list {@link Reason}. The build ({@code checkTestBindings}) requires it on every concrete
 * class of an own-boot base — the classes that had none when the rule was set are a committed debt list
 * that only shrinks — and refuses it on any other class. The javadoc of the class says WHY the reason holds
 * there; this says WHICH.
 */
@Documented
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.TYPE)
public @interface OwnBoot {

    Reason[] reason();

    /** What forces a boot of its own. Nothing else does: a class's own config or work directory never has. */
    enum Reason {
        /** A server restart that is the subject: a running server shared with siblings cannot be stopped. */
        SERVER_RESTART,
        /** A global mutation that cannot be undone between scenarios (a world's clock driven forward, a body
         *  realized), so it would be the premise of every class that ran next. */
        GLOBAL_MUTATION,
        /** The server's initial registry pristine, as a precondition the test ASSERTS (never one it obtains
         *  by running first: JUnit 4 promises no order). */
        PRISTINE_REGISTRY,
        /** A reading that only ever increases: no reset can return it, so a threshold on it is satisfied by
         *  whatever ran before. */
        CUMULATIVE_READING,
        /** A property of the child JVM, set at its launch. */
        JVM_PROPERTY,
        /** A file the mod reads once, at start (planet definitions, a config read at boot): the file IS the
         *  start, so the boot is the subject. */
        STARTUP_FILE
    }
}
