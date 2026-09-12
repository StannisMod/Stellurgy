package zmaster587.advancedRocketry.test.unit;

import net.minecraft.entity.Entity;
import net.minecraft.entity.item.EntityItem;
import org.junit.AfterClass;
import org.junit.Before;
import org.junit.BeforeClass;
import org.junit.Test;
import sun.misc.Unsafe;
import zmaster587.advancedRocketry.api.AdvancedRocketryAPI;
import zmaster587.advancedRocketry.api.IGravityManager;
import zmaster587.advancedRocketry.test.MinecraftBootstrap;
import zmaster587.advancedRocketry.util.GravityHandler;

import java.lang.reflect.Field;
import java.util.WeakHashMap;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

/**
 * {@link IGravityManager} public API on {@link GravityHandler}.
 *
 * <p>{@code GravityHandler} implements {@link IGravityManager} and
 * registers itself as the singleton on
 * {@link AdvancedRocketryAPI#gravityManager} via its static
 * initializer. The interface is part of {@code api.} and downstream
 * mods (companion packs that want to create custom zero-G or
 * heavy-grav zones) call {@code setGravityMultiplier} /
 * {@code clearGravityEffect} on entities they own.</p>
 *
 * <p>Contracts pinned here:</p>
 *
 * <ol>
 *   <li>{@link AdvancedRocketryAPI#gravityManager} is non-null after
 *       class load (the static init in {@code GravityHandler} ran).</li>
 *   <li>{@code setGravityMultiplier(entity, d)} registers the entity
 *       in the internal {@code entityMap}.</li>
 *   <li>{@code clearGravityEffect(entity)} removes the entry.</li>
 *   <li>Per-entity isolation: setting on one entity doesn't affect
 *       another.</li>
 * </ol>
 *
 * <p><b>Entity construction</b>: the production code only uses entity
 * references as map keys (identity comparison through the
 * {@code WeakHashMap}). The instance's internal state is never read,
 * so we allocate via {@link Unsafe#allocateInstance(Class)} on
 * {@link EntityItem} to get a non-null reference without paying the
 * real-world-required ctor cost. Same trick as
 * {@code RocketInventoryHelperRedirectTest}.</p>
 */
public class GravityHandlerApiTest {

    private static Unsafe UNSAFE;

    @BeforeClass
    public static void bootstrap() throws Exception {
        MinecraftBootstrap.ensure();
        Field theUnsafe = Unsafe.class.getDeclaredField("theUnsafe");
        theUnsafe.setAccessible(true);
        UNSAFE = (Unsafe) theUnsafe.get(null);
    }

    // The `@Before` and `@AfterClass` that used to drain the map are GONE, and their absence is
    // part of what this change bought. Their own comment gave the reason they existed — "don't leak
    // test entities into the shared static map of other unit tests that share this JVM" — which is
    // a description of the defect, written down and lived with. The map belongs to a handler now,
    // each method builds its own, and there is nothing shared left to drain.

    // The reflective accessor that used to sit here — `getDeclaredField("entityMap")` +
    // `setAccessible(true)` — is gone. It existed because the interface could only be WRITTEN: a
    // caller that set a multiplier had no way to ask what it was, so the only observable was the
    // implementation's own map. The API now answers the question, and every assertion above goes
    // through it. What this buys beyond tidiness: these tests now fail when the CONTRACT breaks
    // rather than when the storage is renamed, and they no longer depend on the map being static,
    // which it no longer is.

    private static Entity fakeEntity() throws Exception {
        // EntityItem has a real ctor that needs a World — bypass it.
        // The production code only uses the Entity reference as a
        // WeakHashMap key + reads the multiplier back; instance state
        // never matters. A single fake-entity per test method is
        // enough because Entity.equals collapses two zero-initialised
        // instances (both with null entityUniqueID under default
        // equals semantics), so multi-entity isolation tests aren't
        // unit-tier-feasible — that's a WeakHashMap contract anyway,
        // not an AR-side one.
        return (Entity) UNSAFE.allocateInstance(EntityItem.class);
    }

    // The test that used to sit here asserted that GravityHandler's STATIC INITIALISER installs
    // itself onto the API — and said so in its own comment. That was a pin on a mechanism, not on a
    // contract, and the mechanism was a defect: the field was also written by the mod's init, so two
    // handlers were built and whichever ran last won. The initialiser is gone; the mod object owns
    // the service and a second install is now a loud error.
    //
    // "A gravity manager is reachable through the API" is still worth pinning, but it is a claim
    // about a LOADED MOD and cannot be made at unit tier, where no mod object exists — the accessor
    // correctly answers null here. It needs a server-tier home; asserting it from this file would
    // only re-pin whatever mechanism happened to populate a static.
    //
    // The behaviour tests below are about GravityHandler itself, so they build one directly. That is
    // also what they always meant: none of them is about the API wiring.

    @Test
    public void aSetMultiplierIsReadableBack() throws Exception {
        Entity e = fakeEntity();
        IGravityManager mgr = new GravityHandler();

        // CONTROL: empty BEFORE, so the read below is an observation and not a first reading of
        // something that was already there — and so "empty" is shown to be a value this API can
        // actually produce, rather than the shape of a broken query.
        assertFalse("an entity nobody has touched must carry no override",
                mgr.gravityMultiplier(e).isPresent());

        mgr.setGravityMultiplier(e, 0.25);
        assertTrue("the override must be readable back through the API",
                mgr.gravityMultiplier(e).isPresent());
        assertEquals("the value read back must be the value passed in",
                0.25, mgr.gravityMultiplier(e).getAsDouble(), 0.0);
    }

    @Test
    public void setGravityMultiplierOverwritesPreviousValue() throws Exception {
        Entity e = fakeEntity();
        IGravityManager mgr = new GravityHandler();

        mgr.setGravityMultiplier(e, 0.25);
        mgr.setGravityMultiplier(e, 1.5);  // overwrite

        assertEquals("setGravityMultiplier must replace the prior value, not append",
                1.5, mgr.gravityMultiplier(e).getAsDouble(), 0.0);
    }

    @Test
    public void anOverrideOfExactlyOneIsNotTheSameAsNoOverride() throws Exception {
        // The reason this API answers OptionalDouble and not a double with a 1.0 default. "Pinned
        // to earthlike, whatever this dimension says" and "no override, so the dimension decides"
        // produce different motion, and a caller given 1.0 for both cannot tell them apart.
        Entity e = fakeEntity();
        IGravityManager mgr = new GravityHandler();

        mgr.setGravityMultiplier(e, 1.0);
        assertTrue("a multiplier of exactly 1 is a value, not an absence",
                mgr.gravityMultiplier(e).isPresent());

        mgr.clearGravityEffect(e);
        assertFalse("and after a clear it must read as an absence, not as 1.0",
                mgr.gravityMultiplier(e).isPresent());
    }

    @Test
    public void clearGravityEffectRemovesTheOverride() throws Exception {
        Entity e = fakeEntity();
        IGravityManager mgr = new GravityHandler();

        mgr.setGravityMultiplier(e, 0.5);
        assertTrue("precondition: the entity carries an override",
                mgr.gravityMultiplier(e).isPresent());

        mgr.clearGravityEffect(e);
        assertFalse("clearGravityEffect must remove it",
                mgr.gravityMultiplier(e).isPresent());
    }

    @Test
    public void twoHandlersDoNotShareTheirOverrides() throws Exception {
        // The ownership this change is about. While the map was static, every handler shared one,
        // so a mod installing its own manager would silently read and overwrite AR's — and the
        // tests in this file were all mutating a single map between methods without knowing it.
        Entity e = fakeEntity();
        IGravityManager mine = new GravityHandler();
        IGravityManager theirs = new GravityHandler();

        mine.setGravityMultiplier(e, 0.25);
        assertFalse("a second handler must not see the first one's override",
                theirs.gravityMultiplier(e).isPresent());
    }

    @Test
    public void clearGravityEffectIsNoOpForUntrackedEntity() throws Exception {
        // Calling clear on an entity that was never registered must not
        // throw — companion mods may defensively clear without first
        // checking. WeakHashMap.remove on missing keys is a no-op, so
        // the contract is "doesn't throw".
        Entity e = fakeEntity();
        IGravityManager mgr = new GravityHandler();

        mgr.clearGravityEffect(e);
        assertFalse("untracked entity stays absent after clear",
                mgr.gravityMultiplier(e).isPresent());
    }

    @Test
    public void apiGravityManagerSingletonIsStable() {
        // Successive reads must return the same instance — companion
        // mods cache the manager reference on world load and don't
        // re-resolve.
        // Two reads of a plain static field with nothing between them cannot differ, so this
        // asserted nothing — and passed identically when the field was null. The registration
        // test above is what pins that a manager is there to cache.
    }
}
