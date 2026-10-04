package dev.stannismod.stellurgy.test.unit;

import net.minecraft.entity.Entity;
import net.minecraft.entity.item.EntityItem;
import org.junit.Before;
import org.junit.BeforeClass;
import org.junit.Test;
import sun.misc.Unsafe;
import dev.stannismod.stellurgy.api.IGravityManager;
import dev.stannismod.stellurgy.test.MinecraftBootstrap;
import dev.stannismod.stellurgy.util.GravityHandler;

import java.lang.reflect.Field;

import static org.junit.Assert.assertFalse;

/**
 * {@link IGravityManager} public API on {@link GravityHandler}: each handler owns its own overrides.
 *
 * <p><b>Entity construction</b>: the production code only uses entity
 * references as map keys (identity comparison through the
 * {@code WeakHashMap}). The instance's internal state is never read,
 * so we allocate via {@link Unsafe#allocateInstance(Class)} on
 * {@link EntityItem} to get a non-null reference without paying the
 * real-world-required ctor cost.</p>
 */
public class GravityHandlerApiTest {

    private Unsafe unsafe;

    @BeforeClass
    public static void bootstrap() {
        MinecraftBootstrap.ensure();
    }

    @Before
    public void reachUnsafe() throws Exception {
        Field theUnsafe = Unsafe.class.getDeclaredField("theUnsafe");
        theUnsafe.setAccessible(true);
        unsafe = (Unsafe) theUnsafe.get(null);
    }

    private Entity fakeEntity() throws Exception {
        // EntityItem has a real ctor that needs a World — bypass it.
        return (Entity) unsafe.allocateInstance(EntityItem.class);
    }

    @Test
    public void twoHandlersDoNotShareTheirOverrides() throws Exception {
        // The ownership this change is about. While the map was static, every handler shared one,
        // so a mod installing its own manager would silently read and overwrite Stellurgy's — and the
        // tests in this file were all mutating a single map between methods without knowing it.
        Entity e = fakeEntity();
        IGravityManager mine = new GravityHandler();
        IGravityManager theirs = new GravityHandler();

        mine.setGravityMultiplier(e, 0.25);
        assertFalse("a second handler must not see the first one's override",
                theirs.gravityMultiplier(e).isPresent());
    }
}
