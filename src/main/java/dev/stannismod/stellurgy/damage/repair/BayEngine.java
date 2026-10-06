package dev.stannismod.stellurgy.damage.repair;

import net.minecraft.block.Block;
import net.minecraft.block.state.IBlockState;
import net.minecraft.item.Item;
import net.minecraft.item.ItemBlock;
import net.minecraft.item.ItemBlockSpecial;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.nbt.NBTTagList;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;
import net.minecraftforge.items.IItemHandler;

import dev.stannismod.stellurgy.api.StellurgyConfiguration;
import dev.stannismod.stellurgy.damage.DamageState;
import dev.stannismod.stellurgy.damage.RepairOutcome;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.function.BooleanSupplier;
import java.util.function.IntUnaryOperator;

/**
 * The repair ladder's T1 rung as one law: what a step of repair costs, what pays for it, and how the
 * energy for it goes in. Every machine on that rung holds one and runs its work through it — the
 * ship's repair bay and the rocket service station's own repair alike — so the two differ only in
 * how they REACH their work, never in what the work costs.
 *
 * <h3>What pays</h3>
 * <p>Finished blocks, and only those (see {@link #isFinishedBlock}): that is the line between this
 * rung and a fabricator that eats raw materials. A spare equals the price only at the same item
 * damage, so a worn spare does not pay for a pristine stage.</p>
 * <ul>
 *   <li><b>A stage</b> is paid from credit. When the machine holds none for that kind of block it
 *       draws one spare from its reserve and turns it into as many stages of credit as the block has;
 *       restaging spends that credit a stage at a time, so mending a block fully never costs more than
 *       replacing it.</li>
 *   <li><b>A hole</b> is paid by the block that fills it. The machine that places it takes it from
 *       its reserve as it places it; here it is only checked to be on hand.</li>
 * </ul>
 *
 * <h3>What it takes</h3>
 * <p>Forge Energy actually delivered: a stage costs {@code repairBayEnergyPerStage}, a hole a block's
 * full run of stages, put in at the rate the machine's size buys ({@link RepairBaySize}). What has
 * been put toward the next step is banked and saved with the machine and spent by whichever step
 * comes next, so a machine that was unloaded or unpowered resumes, and is never billed for work it
 * did not do.</p>
 */
public final class BayEngine {

    private static final String NBT_CREDITS = "credits";
    private static final String NBT_CREDIT_ITEM = "item";
    private static final String NBT_CREDIT_STAGES = "stages";
    private static final String NBT_BANKED = "banked";

    /** Stages paid for and not yet spent, by the kind of block that paid for them. */
    private final Map<String, Integer> credits = new HashMap<>();
    /** Energy already put toward the next step of work. */
    private int banked;
    /** The fraction of a unit of energy this tick's rate left over; under one, so never saved. */
    private double carry;

    /** One step of repair: what pays for it, and how many stages one spare of it is worth. */
    public static final class Step {
        private final ItemStack price;
        /** Stages of credit one spare buys; zero for a hole, which is paid by the block placed. */
        private final int creditPerSpare;

        private Step(ItemStack price, int creditPerSpare) {
            this.price = price;
            this.creditPerSpare = creditPerSpare;
        }

        /**
         * Taking one stage off the block {@code state} standing at {@code pos} of {@code world}, a
         * block of {@code maxStage} stages — priced by the item that stands for that block. Empty
         * when that item is not a finished block: such damage has no price a reserve could ever
         * hold, which is a different answer from a price not on hand.
         */
        public static Optional<Step> restage(World world, BlockPos pos, IBlockState state, int maxStage) {
            ItemStack price = state.getBlock().getItem(world, pos, state);
            return isFinishedBlock(price) ? Optional.of(new Step(price, maxStage)) : Optional.empty();
        }

        /** Filling a hole with {@code block} in {@code state}. Empty when no finished block stands for it. */
        static Optional<Step> rebuild(Block block, IBlockState state) {
            ItemStack price = new ItemStack(Item.getItemFromBlock(block), 1, block.damageDropped(state));
            return isFinishedBlock(price) ? Optional.of(new Step(price, 0)) : Optional.empty();
        }

        /** The item this step is paid in. */
        ItemStack price() {
            return price;
        }

        private boolean isStage() {
            return creditPerSpare > 0;
        }

        private int energyCost() {
            int perStage = StellurgyConfiguration.getCurrentConfig().repairBayEnergyPerStage;
            return isStage() ? perStage : perStage * DamageState.DEFAULT_MAX_STAGE;
        }

        private String creditKey() {
            return price.getItem().getRegistryName() + "@" + price.getMetadata();
        }
    }

    /**
     * Whether {@code step} can be paid for now: from credit already held, or from a spare in
     * {@code reserve}.
     */
    public boolean canPay(Step step, IItemHandler reserve) {
        return heldCredit(step) > 0 || slotOf(step.price, reserve) >= 0;
    }

    /**
     * One tick of work on {@code step}: put energy in from {@code draw} at {@code powerPerTick}, and
     * once enough is in, {@code apply} the step and pay for it.
     *
     * <p>{@code draw} takes up to the amount it is asked for out of the machine's buffer and answers
     * what it took. {@code apply} does the step — lowers the stage, places the block — and answers
     * whether it landed; a step that did not land costs nothing and is tried again.</p>
     *
     * @return {@link RepairOutcome#REPAIRED} when the step landed and was paid,
     *     {@link RepairOutcome#REPAIRING} while energy is still going in,
     *     {@link RepairOutcome#NO_MATERIALS} when nothing on hand pays for it, and
     *     {@link RepairOutcome#NO_CHARGE} when energy was wanted and none came. Neither refusal
     *     spends anything.
     */
    public RepairOutcome work(Step step, IItemHandler reserve, IntUnaryOperator draw, double powerPerTick,
                              BooleanSupplier apply) {
        if (!canPay(step, reserve)) {
            return RepairOutcome.NO_MATERIALS;
        }
        int cost = step.energyCost();
        int needed = cost - banked;
        if (needed > 0) {
            carry += powerPerTick;
            int want = (int) Math.min(needed, Math.floor(carry));
            int got = draw.applyAsInt(want);
            // Never let power that found nothing to do pile up into a later burst.
            carry = Math.min(carry - got, 1.0D);
            banked += got;
            if (want > 0 && got == 0) {
                return RepairOutcome.NO_CHARGE;
            }
        }
        if (banked < cost) {
            return RepairOutcome.REPAIRING;
        }
        if (step.isStage()) {
            holdCredit(step, reserve);
        }
        if (!apply.getAsBoolean()) {
            return RepairOutcome.REPAIRING;
        }
        if (step.isStage()) {
            credits.put(step.creditKey(), heldCredit(step) - 1);
        }
        banked -= cost;
        return RepairOutcome.REPAIRED;
    }

    /**
     * Make sure at least one stage of credit for {@code step} is held, drawing one spare from
     * {@code reserve} into credit when none is. A spare drawn becomes credit held by the machine, so
     * a step that then does not land loses nothing. Called only after {@link #canPay} said yes in the
     * same call, so with no credit held the spare is there.
     */
    private void holdCredit(Step step, IItemHandler reserve) {
        if (heldCredit(step) > 0) {
            return;
        }
        reserve.extractItem(slotOf(step.price, reserve), 1, false);
        credits.put(step.creditKey(), step.creditPerSpare);
    }

    private int heldCredit(Step step) {
        Integer held = credits.get(step.creditKey());
        return held == null ? 0 : held;
    }

    /** The first slot of {@code reserve} holding {@code wanted} at its own item damage, or -1. */
    static int slotOf(ItemStack wanted, IItemHandler reserve) {
        for (int i = 0; i < reserve.getSlots(); i++) {
            ItemStack held = reserve.getStackInSlot(i);
            if (!held.isEmpty() && ItemStack.areItemsEqual(held, wanted)) {
                return i;
            }
        }
        return -1;
    }

    /**
     * Whether {@code stack} is a finished block — the only thing a machine on this rung can be paid
     * in, and so the only thing its reserve should take. One rule for both, so a block the reserve
     * refuses is never reported as one the reserve merely lacks.
     */
    static boolean isFinishedBlock(ItemStack stack) {
        Item item = stack.getItem();
        return !stack.isEmpty() && (item instanceof ItemBlock || item instanceof ItemBlockSpecial);
    }

    /** Write the credit held and the energy banked into the machine's own tag. */
    public void writeTo(NBTTagCompound nbt) {
        NBTTagList list = new NBTTagList();
        for (Map.Entry<String, Integer> credit : credits.entrySet()) {
            if (credit.getValue() > 0) {
                NBTTagCompound tag = new NBTTagCompound();
                tag.setString(NBT_CREDIT_ITEM, credit.getKey());
                tag.setInteger(NBT_CREDIT_STAGES, credit.getValue());
                list.appendTag(tag);
            }
        }
        nbt.setTag(NBT_CREDITS, list);
        nbt.setInteger(NBT_BANKED, banked);
    }

    /** Read back what {@link #writeTo} wrote; what was held before is replaced, not added to. */
    public void readFrom(NBTTagCompound nbt) {
        credits.clear();
        NBTTagList list = nbt.getTagList(NBT_CREDITS, 10);
        for (int i = 0; i < list.tagCount(); i++) {
            NBTTagCompound tag = list.getCompoundTagAt(i);
            credits.put(tag.getString(NBT_CREDIT_ITEM), tag.getInteger(NBT_CREDIT_STAGES));
        }
        banked = nbt.getInteger(NBT_BANKED);
    }
}
