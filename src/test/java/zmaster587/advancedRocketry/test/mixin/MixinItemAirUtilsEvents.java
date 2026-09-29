package zmaster587.advancedRocketry.test.mixin;

import net.minecraft.item.ItemStack;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import zmaster587.advancedRocketry.test.trace.TestTrace;
import zmaster587.advancedRocketry.util.ItemAirUtils;

/**
 * Air LEAVING an enchanted (non-AR) chestplate as an event — the second of the two routes a suit's
 * air can drain by.
 *
 * <h2>The event</h2>
 *
 * <p>{@code suit_air_drained} with {@code route:"enchanted"} — RETURN of the singleton's
 * {@code decrementAir(ItemStack, int)}: production has just rewritten the stack's {@code "air"}
 * tag and is answering how much it actually took. The method has one return, so RETURN and TAIL are
 * the same instruction here and no local is read; the return value is the only thing consulted.
 * Payload: {@code requested} (the amount asked for), {@code drained} (what production says it
 * removed — {@code 0} when the suit was already empty, less than {@code requested} when it ran
 * dry mid-decrement), {@code item} (the stack's item registry name; {@code "empty"} for an absent
 * stack, {@code "null"} when the item has no registry name), and {@code route:"enchanted"}. The
 * sibling record with {@code route:"component"} is taken on {@code ItemSpaceChest.decrementAir} by
 * its own mixin, and the two payloads carry the SAME four field names with the same sentinels, so a
 * test may read one stream and tell the routes apart by {@code route} alone; the two routes are
 * different classes with different storage (an {@code "air"} integer on the stack here, a fluid in
 * a sub-inventory component there), and a test awaiting a drain may filter on {@code route} or
 * accept either.</p>
 *
 * <h2>Why the singleton's class, and not the wrapper</h2>
 *
 * <p>Every drain on this route goes through {@code ItemAirUtils.INSTANCE.decrementAir}: the
 * atmosphere's suit check wraps the chestplate in an {@code ItemAirWrapper} whose
 * {@code protectsFromSubstance} calls {@code decrementAir(stack, 1)}, and the wrapper delegates to
 * the singleton. The wrapper is a static nested class ({@code ItemAirUtils$ItemAirWrapper}) that a
 * mixin on the outer class would not see; the singleton's method is the one funnel beneath it and
 * beneath any direct caller, so one seam covers both.</p>
 *
 * <h2>Side</h2>
 *
 * <p>The seam has no entity in hand — it is an item-stack operation — so the record is routed by
 * the calling thread's effective side ({@code recordHere}). The drain a suit test means runs on the
 * SERVER, from the atmosphere tick's suit check (on the atmosphere's own cadence, not every tick)
 * and from the test probe. A client-log record is NOT by itself a finding, and an earlier version of
 * this note said it was: {@code PlanetEventHandler.playerTick} asks
 * {@code AtmosphereType.LOWOXYGEN.isImmune} for any living entity {@code isInWater()} without a
 * side gate, so a suited swimmer reaches this seam every tick on BOTH sides. That is also the only
 * path chatty enough to matter — twenty records a second per side turns the 256-deep ring over in
 * about thirteen seconds, so after a swim this type is a window and not a history. The seam is left
 * unfiltered on purpose all the same: what it carries is the COUNT of drains, and an edge filter on
 * a counter deletes the fact.</p>
 *
 * <h2>What this mixin is silent about</h2>
 *
 * <p>It does not say WHOSE suit drained — the stack carries no owner, and the atmosphere check
 * that caused it is the {@code suit_immunity_decided} event's to name. It does not see a
 * component-suit drain ({@code ItemSpaceChest}, the other route). It does not record the air
 * remaining afterwards nor the fill side ({@code increment}). And it records the request whether or
 * not the stack was a valid air container — the validity gate ({@code isStackValidAirContainer})
 * runs in the caller before the wrapper is built, so a direct call on an unenchanted stack still
 * drains and still records. Order against any other event is measured on a run, never assumed.</p>
 */
@Mixin(ItemAirUtils.class)
public abstract class MixinItemAirUtilsEvents {

    private static final String INSTRUMENT = "suit_air_enchanted_events";

    @Inject(method = "decrementAir", at = @At("RETURN"))
    private void arTest$drained(ItemStack stack, int amt, CallbackInfoReturnable<Integer> cir) {
        TestTrace.instrumentHere(INSTRUMENT);
        TestTrace.recordHere("suit_air_drained", "\"requested\":" + amt
                + ",\"drained\":" + cir.getReturnValueI()
                + ",\"item\":\"" + arTest$itemName(stack) + "\""
                + ",\"route\":\"enchanted\"");
    }

    /**
     * The same three answers the component route's mixin gives for the same field — {@code "empty"}
     * for an absent stack, {@code "null"} for one whose item has no registry name, the registry name
     * otherwise. Byte-identical to {@code MixinItemSpaceChestEvents}'s helper on purpose: the two
     * files write the SAME event type and a reader that has to know which mixin produced a row
     * before it can read the row cannot compare the two routes at all.
     */
    private static String arTest$itemName(ItemStack stack) {
        if (stack == null || stack.isEmpty()) {
            return "empty";
        }
        return TestTrace.json(String.valueOf(stack.getItem().getRegistryName()));
    }
}
