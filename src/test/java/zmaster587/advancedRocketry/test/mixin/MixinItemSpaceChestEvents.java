package zmaster587.advancedRocketry.test.mixin;

import net.minecraft.item.ItemStack;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import zmaster587.advancedRocketry.armor.ItemSpaceChest;
import zmaster587.advancedRocketry.test.trace.TestTrace;

/**
 * The AR space-suit chest's two air transfers as events — the COMPONENT route, where the oxygen
 * buffer is a fluid inside a pressure-tank component in the chest's own sub-inventory, not an
 * {@code "air"} integer on the stack.
 *
 * <p>{@code suit_air_filled} is one call of {@code ItemSpaceChest.increment(ItemStack, int)}
 * returning: the charge pad ({@code TileGasChargePad}) and the {@code /artest} fill probe ask for
 * {@code requested} mB of oxygen and the chest reports how much its tank components actually took
 * ({@code filled}, the method's own return value, which is {@code 0} for a stack without NBT or
 * with no oxygen-capable component). {@code suit_air_drained} is one call of
 * {@code ItemSpaceChest.decrementAir(ItemStack, int)} returning: in production it is reached from
 * {@code ItemSpaceChest.protectsFromSubstance} — {@code AtmosphereNeedsSuit.protectsFrom} asks the
 * chest's PROTECTIVEARMOR capability with {@code commitProtection = true} and the chest pays one mB
 * ({@code requested = 1}) from a component, {@code drained} being what it actually got. A drain that
 * returns less than requested is the suit running dry, and {@code protectsFromSubstance} turns
 * false on exactly that read. Both carry {@code item} (the stack's item registry name) and the
 * drain carries {@code route:"component"} so it can be told apart from the enchanted-armour route
 * ({@code ItemAirUtils.decrementAir}, recorded by another mixin under the same type).</p>
 *
 * <p>Recorded on whichever side calls — {@code recordHere}, routed by the effective side of the
 * calling thread, since a stack has no world to route by. Injected at RETURN: both targets have two
 * returns (the transfer and the {@code return 0} of a stack without NBT) and the record reads only
 * the return value, never a local, so every exit is safe to observe and a refused transfer is
 * recorded as a transfer of zero rather than lost.</p>
 *
 * <p><b>The client is NOT out of scope, and one caller is per-tick.</b> The drain a suit test means
 * runs on the server, from the atmosphere tick's suit check every ten or twenty game ticks. But
 * {@code PlanetEventHandler.playerTick} also asks {@code AtmosphereType.LOWOXYGEN.isImmune} for any
 * living entity {@code isInWater()}, and that branch is NOT side-gated: while a suited player swims,
 * this seam is reached EVERY tick on BOTH sides, so records appear in the client log too and the
 * 256-deep ring turns over in about thirteen seconds. A test that reads this type after a swim is
 * reading a window, not a history; one that awaits a drain must await it on the side its scenario
 * drains on. It stays unfiltered anyway, deliberately: the fact this event carries is the COUNT of
 * transfers, and an edge filter on a counter destroys exactly what it is for. Off the water path a
 * player in vacuum records two drains a second and the ring holds about two minutes of them.</p>
 *
 * <p>SILENT about: which component slot paid or was filled (the record is per stack, not per
 * component); the enchanted route's drains (another mixin); a stack whose item is not the space
 * chest (the singleton is the only receiver, but the stack it is handed is whatever the caller
 * passed — {@code item} says which); the client's rendered O2 bar; and whether the atmosphere
 * accepted the protection afterwards ({@code suit_immunity_decided} is that event).</p>
 *
 * <p>And {@code suit_air_filled} is the COMPONENT route's fill only. The enchanted route has an
 * {@code increment} of its own ({@code ItemAirUtils.increment}, which the same charge pad reaches
 * for an enchanted chestplate) and NOTHING in this wave records it — a silence on
 * {@code suit_air_filled} therefore does not mean no suit was charged. Its drain half is recorded,
 * under this same {@code suit_air_drained} type with {@code route:"enchanted"}, so the two routes
 * are asymmetric on purpose: drains are comparable across routes, fills are not.</p>
 */
@Mixin(ItemSpaceChest.class)
public abstract class MixinItemSpaceChestEvents {

    private static final String INSTRUMENT = "suit_air_events";

    @Inject(method = "increment", at = @At("RETURN"))
    private void arTest$airFilled(ItemStack stack, int amt, CallbackInfoReturnable<Integer> cir) {
        TestTrace.instrumentHere(INSTRUMENT);
        TestTrace.recordHere("suit_air_filled", "\"requested\":" + amt
                + ",\"filled\":" + cir.getReturnValue()
                + ",\"item\":\"" + arTest$itemName(stack) + "\"");
    }

    @Inject(method = "decrementAir", at = @At("RETURN"))
    private void arTest$airDrained(ItemStack stack, int amt, CallbackInfoReturnable<Integer> cir) {
        TestTrace.instrumentHere(INSTRUMENT);
        TestTrace.recordHere("suit_air_drained", "\"requested\":" + amt
                + ",\"drained\":" + cir.getReturnValue()
                + ",\"item\":\"" + arTest$itemName(stack) + "\",\"route\":\"component\"");
    }

    private static String arTest$itemName(ItemStack stack) {
        if (stack == null || stack.isEmpty()) {
            return "empty";
        }
        return TestTrace.json(String.valueOf(stack.getItem().getRegistryName()));
    }
}
