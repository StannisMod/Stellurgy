package dev.stannismod.stellurgy.test.unit;

import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.inventory.Container;
import org.junit.BeforeClass;
import org.junit.Test;
import sun.misc.Unsafe;
import dev.stannismod.stellurgy.Stellurgy;
import dev.stannismod.stellurgy.test.MinecraftBootstrap;
import dev.stannismod.stellurgy.util.RocketInventoryHelper;

import java.lang.reflect.Field;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * unit pin for the inventory-bypass redirect
 * logic that the mixins
 * ({@code MixinEntityPlayer(MP)InventoryAccess}) delegate to.
 *
 * <p>The mixins are one-liners that call
 * {@link RocketInventoryHelper#shouldAllowContainerInteract}, so a unit
 * test of that helper is the actual behavioural pin for both redirects.
 * This avoids needing a real {@code EntityPlayer} GUI session (which is
 * the constraint that pushed an end-to-end pin into the
 * testClient e2e harness).</p>
 *
 * <h2>What's pinned</h2>
 * <ol>
 *   <li>Bypass set member &rarr; return {@code true}, container.canInteractWith
 *       MUST NOT be invoked (vanilla close-screen path is skipped
 *       outright).</li>
 *   <li>Non-bypass-set player &rarr; delegates to
 *       {@code container.canInteractWith(player)} verbatim, both true and
 *       false outcomes propagate.</li>
 * </ol>
 *
 * <h2>How EntityPlayer is faked</h2>
 *
 * <p>{@link Unsafe#allocateInstance} returns a zero-initialised
 * {@link EntityPlayer} reference. The bypass set only compares entities,
 * which is by entity id — so each fake is given its own — and invokes no
 * other {@code EntityPlayer} method, so the uninitialised instance is safe
 * as a marker object. The set is the bootstrap server's. The same trick is
 * used by other MC unit tests in this tree (see
 * {@code MinecraftBootstrap} usage above).</p>
 */
public class RocketInventoryHelperRedirectTest {

    private static Unsafe UNSAFE;

    @BeforeClass
    public static void setupBootstrap() throws Exception {
        MinecraftBootstrap.ensure();
        Field theUnsafe = Unsafe.class.getDeclaredField("theUnsafe");
        theUnsafe.setAccessible(true);
        UNSAFE = (Unsafe) theUnsafe.get(null);
    }

    // Entities compare by entity id, and an Unsafe-allocated one has id 0; each fake gets its own id
    // so that two of them are two players. Counting down from -1 keeps clear of every real entity id.
    private static final AtomicInteger NEXT_FAKE_ID = new AtomicInteger(-1);

    private static RocketInventoryHelper bypass() {
        return Stellurgy.serverState().rocketInventory;
    }

    private static EntityPlayerMP fakePlayer() throws InstantiationException {
        // EntityPlayer is abstract; allocate a concrete EntityPlayerMP via
        // Unsafe (skips the ctor, so no NetworkManager / GameProfile /
        // PlayerInteractionManager required).
        EntityPlayerMP player = (EntityPlayerMP) UNSAFE.allocateInstance(EntityPlayerMP.class);
        player.setEntityId(NEXT_FAKE_ID.getAndDecrement());
        return player;
    }

    private static Container recordingContainer(AtomicInteger calls, boolean retval) {
        return new Container() {
            @Override
            public boolean canInteractWith(EntityPlayer playerIn) {
                calls.incrementAndGet();
                return retval;
            }
        };
    }

    @Test
    public void bypassPlayerSkipsCanInteractWithRegardlessOfDistance() throws Exception {
        EntityPlayerMP player = fakePlayer();
        bypass().addPlayerToInventoryBypass(player);
        AtomicInteger calls = new AtomicInteger();
        // If the redirect helper consults the container, our recording
        // stub flips calls > 0. Pinning calls==0 proves the bypass branch
        // short-circuits — i.e. the MC close-screen block is skipped.
        boolean allowed = RocketInventoryHelper.shouldAllowContainerInteract(
                recordingContainer(calls, /* canInteractWith */ false), player);
        assertTrue("bypass player must keep container open", allowed);
        assertEquals("container.canInteractWith must NOT be consulted "
                + "for a bypass player", 0, calls.get());
    }

    @Test
    public void nonBypassPlayerDelegatesToContainerCanInteractWithTrue() throws Exception {
        EntityPlayer player = fakePlayer();
        AtomicInteger calls = new AtomicInteger();
        boolean allowed = RocketInventoryHelper.shouldAllowContainerInteract(
                recordingContainer(calls, /* canInteractWith */ true), player);
        assertTrue("non-bypass + canInteractWith=true must allow", allowed);
        assertEquals("container.canInteractWith MUST be invoked exactly once",
                1, calls.get());
    }

    @Test
    public void nonBypassPlayerDelegatesToContainerCanInteractWithFalse() throws Exception {
        EntityPlayer player = fakePlayer();
        AtomicInteger calls = new AtomicInteger();
        boolean allowed = RocketInventoryHelper.shouldAllowContainerInteract(
                recordingContainer(calls, /* canInteractWith */ false), player);
        assertFalse("non-bypass + canInteractWith=false must close", allowed);
        assertEquals("container.canInteractWith MUST be invoked exactly once",
                1, calls.get());
    }

    @Test
    public void removingPlayerFromBypassRestoresVanillaSemantics() throws Exception {
        EntityPlayerMP player = fakePlayer();
        bypass().addPlayerToInventoryBypass(player);
        assertTrue(RocketInventoryHelper.canPlayerBypassInvChecks(player));
        bypass().removePlayerFromInventoryBypass(player);
        assertFalse(RocketInventoryHelper.canPlayerBypassInvChecks(player));

        // After removal, the helper must defer to container.canInteractWith
        // exactly as it would for a player that was never added.
        AtomicInteger calls = new AtomicInteger();
        boolean allowed = RocketInventoryHelper.shouldAllowContainerInteract(
                recordingContainer(calls, /* canInteractWith */ false), player);
        assertFalse(allowed);
        assertEquals(1, calls.get());
    }

    @Test
    public void bypassIsScopedToTheSpecificPlayerInstance() throws Exception {
        EntityPlayerMP p1 = fakePlayer();
        EntityPlayerMP p2 = fakePlayer();
        bypass().addPlayerToInventoryBypass(p1);

        assertTrue("p1 is in bypass", RocketInventoryHelper.canPlayerBypassInvChecks(p1));
        assertFalse("p2 must NOT inherit p1's bypass",
                RocketInventoryHelper.canPlayerBypassInvChecks(p2));

        AtomicInteger calls = new AtomicInteger();
        boolean p2Allowed = RocketInventoryHelper.shouldAllowContainerInteract(
                recordingContainer(calls, /* canInteractWith */ false), p2);
        assertFalse("p2 must take vanilla path", p2Allowed);
        assertEquals("p2 must consult the container", 1, calls.get());
    }
}
