package zmaster587.advancedRocketry.test.unit;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import org.junit.Test;

import zmaster587.advancedRocketry.space.CellCrossingController;
import zmaster587.advancedRocketry.space.DescentController;
import zmaster587.advancedRocketry.space.ShipEntryController;
import zmaster587.advancedRocketry.space.ShipLedger;
import zmaster587.advancedRocketry.space.ShipTransitManager;
import zmaster587.advancedRocketry.space.SpaceManager;
import zmaster587.advancedRocketry.space.SpaceSubsystem;

import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/**
 * The one promise the space subsystem's lifetime has to keep: <b>the server's subsystem is the mod's,
 * and nothing outside the mod's own server-lifecycle hooks can replace it or take it away.</b>
 *
 * <p><b>Why this is checked by reflection rather than by driving something.</b> The contract is now
 * carried by the SHAPE of the code: there is no seam to exercise, because a seam is exactly what it
 * forbids. Ledger #235 was a setter that took five services, kept no copy of them, and offered a
 * "clear" that assigned five nulls — the production subsystem then answered {@code null} for the rest
 * of the boot, since the hook that builds it runs once per server start. Its replacement was a single
 * static {@code current} plus an install/restore handle, which kept that particular accident from
 * recurring but formalised the deeper one: a class with a public constructor is not a singleton, so a
 * "current" one of itself cannot mean anything, and two instances could be alive at once with
 * visibility depending on which of six static accessors a caller happened to use. That cost an hour of
 * misdiagnosis on 2026-08-25, twice blamed on the test fixtures.</p>
 *
 * <p><b>Why these assertions can fail.</b> Each names a shape the code actually had. The static field
 * of its own type IS {@code SpaceSubsystem.current}; the static service accessors ARE
 * {@code space()/ledger()/transit()/entry()/cellCrossings()/descent()}; the writer on the owner IS
 * {@code install(SpaceSubsystem)}. Re-add any of them and the corresponding assertion goes red — which
 * is the entire point, because none of the three announces itself at a call site.</p>
 */
public class SpaceSubsystemOwnershipTest {

    /** The mod class, loaded WITHOUT running its static initializer (which needs the Forge bootstrap). */
    private static Class<?> modClass() throws ClassNotFoundException {
        return Class.forName("zmaster587.advancedRocketry.AdvancedRocketry", false,
                SpaceSubsystemOwnershipTest.class.getClassLoader());
    }

    /** The six services a subsystem owns. A static handing any of these out is a global back door. */
    private static final List<Class<?>> SERVICES = Arrays.asList(
            SpaceManager.class, ShipLedger.class, ShipTransitManager.class,
            ShipEntryController.class, CellCrossingController.class, DescentController.class);

    @Test
    public void theSubsystemKeepsNoStaticReferenceToOneOfItself() {
        List<String> offenders = new ArrayList<>();
        for (Field f : SpaceSubsystem.class.getDeclaredFields()) {
            if (Modifier.isStatic(f.getModifiers()) && f.getType() == SpaceSubsystem.class) {
                offenders.add(f.getName());
            }
        }
        assertTrue("SpaceSubsystem must hold no static reference to a subsystem - it has a public "
                + "constructor, so a 'current' one of itself is not a thing it can know: " + offenders,
                offenders.isEmpty());
    }

    @Test
    public void theSubsystemHandsOutNoServiceThroughAStatic() {
        List<String> offenders = new ArrayList<>();
        for (Method m : SpaceSubsystem.class.getDeclaredMethods()) {
            if (Modifier.isStatic(m.getModifiers())
                    && m.getParameterTypes().length == 0
                    && SERVICES.contains(m.getReturnType())) {
                offenders.add(m.getName() + "() -> " + m.getReturnType().getSimpleName());
            }
        }
        assertTrue("a service must be read off a subsystem somebody already has in hand, never "
                + "fetched from a static - that is how two stacks end up half-visible to one "
                + "caller: " + offenders, offenders.isEmpty());
    }

    @Test
    public void onlyTheOwnerCanWriteTheServersSubsystemAndItIsNotStatic() throws Exception {
        Class<?> mod = modClass();
        Field owned = null;
        for (Field f : mod.getDeclaredFields()) {
            if (f.getType() == SpaceSubsystem.class) {
                if (owned != null) {
                    fail("the mod must hold ONE space subsystem, not several: "
                            + owned.getName() + " and " + f.getName());
                }
                owned = f;
            }
        }
        if (owned == null) {
            fail("the mod must OWN the server's space subsystem - no field of that type on "
                    + mod.getName() + ", so the lifetime has gone back to living inside the "
                    + "subsystem itself");
        }
        assertTrue("the owned subsystem must be private: " + owned,
                Modifier.isPrivate(owned.getModifiers()));
        assertTrue("the owned subsystem must be an INSTANCE field - a static one belongs to the JVM, "
                + "while a subsystem belongs to one server: " + owned,
                !Modifier.isStatic(owned.getModifiers()));

        // No public way IN. A reader is fine and is how every caller reaches it; anything that ACCEPTS
        // a subsystem from outside is a seam that can leave a running server without one.
        List<String> writers = new ArrayList<>();
        for (Method m : mod.getDeclaredMethods()) {
            if (Modifier.isPublic(m.getModifiers())
                    && Arrays.asList(m.getParameterTypes()).contains(SpaceSubsystem.class)) {
                writers.add(m.getName());
            }
        }
        assertTrue("nothing outside the mod's server-lifecycle hooks may hand it a subsystem: "
                + writers, writers.isEmpty());
    }
}
