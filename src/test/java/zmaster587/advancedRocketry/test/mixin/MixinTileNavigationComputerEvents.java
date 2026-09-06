package zmaster587.advancedRocketry.test.mixin;

import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.util.math.BlockPos;
import net.minecraftforge.fml.relauncher.Side;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import zmaster587.advancedRocketry.item.ItemMemoryCrystal;
import zmaster587.advancedRocketry.space.GalacticCoord;
import zmaster587.advancedRocketry.test.trace.TestTrace;
import zmaster587.advancedRocketry.tile.TileNavigationComputer;

/**
 * The navigation console's four facts as events: a command ARRIVED at the console, the console
 * AIMED the ship at a body, a crystal was COPIED into the ship's own, and the console TOLD the
 * player something.
 *
 * <h2>What each event is</h2>
 *
 * <ul>
 *   <li>{@code nav_command_received} — a console packet reached the SERVER copy of the tile
 *       ({@code useNetworkData}, HEAD, only when {@code side.isServer()}). The record carries the
 *       packet id, the sender, the {@code pick} index the packet named ({@code -1} when the packet
 *       carries none) and how many addresses the ship's crystal knew at that instant — so a "picked
 *       the n-th address" can be read against the list it was picked from. The ids are the tile's
 *       own private constants and a reader needs them spelled out: 0 copy, 1 erase source, 2 clear
 *       target, 3 pick, 4 aim typed, 5 channel sync, 6 arm/disarm, 7 sky labels. Every one of them
 *       is a GUI button press — the console sends nothing on a timer — so this event is not on a
 *       per-tick path and needs no edge.</li>
 *   <li>{@code nav_target_picked} — the console aimed the ship at a BODY ({@code setTargetBody},
 *       HEAD): the body's dimension id and the cell it was last observed in. A pick with
 *       {@code INVALID_PLANET} still lands here (the method delegates to the hand-typed form after
 *       this hook has run), and the record says so through {@code targetDim}.</li>
 *   <li>{@code crystal_copied} — the add-only copy from the source slot into the ship's crystal
 *       finished ({@code copySourceIntoShipCrystal}, every RETURN), with the number of addresses
 *       gained or refreshed and whether a ship crystal was even inserted. A {@code changed} of
 *       {@code 0} with {@code shipCrystal:false} is the early "nothing to copy INTO" exit; with
 *       {@code shipCrystal:true} it is a copy that found nothing new.</li>
 *   <li>{@code nav_console_told} — the console sent the player a translated line ({@code tell},
 *       HEAD): the lang key, in production's own words ({@code msg.jump.armed},
 *       {@code msg.jump.disarmed}, the no-target refusal, the sky-label toggles). A chat line is
 *       the only thing the player sees of an arm attempt, and grepping chat for a translated string
 *       is what the tests used to do.</li>
 * </ul>
 *
 * <h2>Side</h2>
 *
 * <p>Every record is routed by the calling thread's effective side. All four seams are reached from
 * the server branch of the packet handler in practice, so they land in the server log; a client
 * call — the tile is a common class — would land in the client log rather than be dropped. The
 * message hook routes by the player's own world where a player is in hand, which is the exact
 * answer.</p>
 *
 * <h2>Silent about</h2>
 *
 * <p>The CLIENT half of a command: the handler returns at once on the client, before this hook's
 * condition, so a packet that never left the client is indistinguishable here from one that was
 * never sent. A target set by the hand-typed or clear paths ({@code setTarget} directly) — only a
 * BODY pick passes through {@code setTargetBody}. An address that reaches the ship's crystal by any
 * route other than the copy button (the channel sync, a crystal written elsewhere). A chat line
 * sent by anything other than the console's own {@code tell} — the jump gate's own messages at the
 * helm are another seam's.</p>
 *
 * <p>Two records mean less than they look like. {@code nav_console_told} is taken at the HEAD of
 * {@code tell}, which says nothing at all when the player is null — the hook records that case
 * anyway, with {@code who:"null"}, so a record here is "the console was ASKED to say this", never
 * "the player was told". And it is the KEY, not the rendered line: what the player reads depends on
 * his language file, and nothing here sees that translation. {@code nav_target_picked} fires on
 * every aim, including a re-pick of the body the console was already aimed at — it is an ACT, not a
 * change, and a test that wants a change must compare consecutive records itself.</p>
 */
@Mixin(TileNavigationComputer.class)
public abstract class MixinTileNavigationComputerEvents {

    private static final String INSTRUMENT = "nav_computer_events";

    @Inject(method = "useNetworkData", at = @At("HEAD"))
    private void arTest$commandReceived(EntityPlayer player, Side side, byte id, NBTTagCompound nbt,
                                        CallbackInfo ci) {
        TestTrace.instrumentHere(INSTRUMENT);
        if (side == null || !side.isServer()) {
            return; // the client branch returns at once; only the owning side handles a command
        }
        TileNavigationComputer self = (TileNavigationComputer) (Object) this;
        int pick = nbt != null && nbt.hasKey("pick") ? nbt.getInteger("pick") : -1;
        TestTrace.recordHere("nav_command_received", "\"pos\":\"" + arTest$xyz(self.getPos())
                + "\",\"id\":" + id
                + ",\"who\":\"" + TestTrace.json(player == null ? "null" : player.getName())
                + "\",\"pick\":" + pick
                + ",\"known\":" + self.shipCrystal().size());
    }

    @Inject(method = "setTargetBody", at = @At("HEAD"))
    private void arTest$targetPicked(int dimId, GalacticCoord observed, CallbackInfo ci) {
        TestTrace.instrumentHere(INSTRUMENT);
        TileNavigationComputer self = (TileNavigationComputer) (Object) this;
        TestTrace.recordHere("nav_target_picked", "\"pos\":\"" + arTest$xyz(self.getPos())
                + "\",\"targetDim\":" + dimId
                + ",\"target\":\"" + (observed == null ? "null" : TestTrace.json(observed.cellKey()))
                + "\"");
    }

    // RETURN, not TAIL: the method has two exits (no ship crystal -> 0; the copy's count) and both
    // are copies that FINISHED. No locals are read, so the early exit's shorter frame is harmless;
    // the return value is read off the callback, never recomputed.
    @Inject(method = "copySourceIntoShipCrystal", at = @At("RETURN"))
    private void arTest$crystalCopied(CallbackInfoReturnable<Integer> cir) {
        TestTrace.instrumentHere(INSTRUMENT);
        TileNavigationComputer self = (TileNavigationComputer) (Object) this;
        boolean shipCrystal = ItemMemoryCrystal.isCrystal(
                self.getStackInSlot(TileNavigationComputer.SLOT_SHIP));
        TestTrace.recordHere("crystal_copied", "\"pos\":\"" + arTest$xyz(self.getPos())
                + "\",\"changed\":" + cir.getReturnValueI()
                + ",\"shipCrystal\":" + shipCrystal);
    }

    @Inject(method = "tell", at = @At("HEAD"))
    private static void arTest$told(EntityPlayer player, String langKey, CallbackInfo ci) {
        if (player != null && player.world != null) {
            TestTrace.instrument(player, INSTRUMENT);
            TestTrace.record(player, "nav_console_told", "\"who\":\"" + TestTrace.json(player.getName())
                    + "\",\"key\":\"" + TestTrace.json(langKey) + "\"");
            return;
        }
        // Production says nothing to a null player; the hook still says it ran, and that it was
        // asked to tell nobody.
        TestTrace.instrumentHere(INSTRUMENT);
        TestTrace.recordHere("nav_console_told", "\"who\":\"null\",\"key\":\""
                + TestTrace.json(langKey) + "\"");
    }

    private static String arTest$xyz(BlockPos pos) {
        return pos == null ? "null" : pos.getX() + "," + pos.getY() + "," + pos.getZ();
    }
}
