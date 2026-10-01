package dev.stannismod.stellurgy.client;

import net.minecraft.client.entity.EntityPlayerSP;
import net.minecraft.entity.Entity;
import net.minecraft.nbt.NBTBase;
import net.minecraft.util.EnumFacing;
import net.minecraft.util.ResourceLocation;
import net.minecraftforge.common.capabilities.Capability;
import net.minecraftforge.common.capabilities.CapabilityInject;
import net.minecraftforge.common.capabilities.CapabilityManager;
import net.minecraftforge.common.capabilities.ICapabilityProvider;
import net.minecraftforge.event.AttachCapabilitiesEvent;
import net.minecraftforge.fml.relauncher.Side;
import net.minecraftforge.fml.relauncher.SideOnly;

/**
 * The raw input of the local player as he pilots or walks a deck: mouse motion not yet consumed, the
 * camera-pin baseline the next mouse delta is measured from, the engine-start hold, the deck-frame
 * look, and the edges of the keys sampled per tick.
 *
 * <p>Owned by the {@link EntityPlayerSP} it describes, as a capability, and gone with it. Minecraft
 * builds a new client player on every dimension change and respawn and copies none of this across,
 * which is what the input wants: the pin is invalid on the new body, so its first tick reads a zero
 * delta — the same thing that happens when a pilot sits down — and nothing measured against the old
 * body's rotation leaks into the new one. What the pilot COMMANDS lives on the craft
 * ({@link PilotCommand}), and the craft's attitude is read off the ship; neither is input.</p>
 *
 * <p>Client main thread only: the tick, the mouse event, the render and the position packet that
 * re-pins the camera all run there.</p>
 */
@SideOnly(Side.CLIENT)
public final class PilotInput {

    /**
     * Effectively final, client lifetime: injected by Forge after {@link #register()} runs in the
     * client's pre-init, and never written by this mod. Approved by the maintainer 2026-10-01.
     */
    @CapabilityInject(PilotInput.class)
    private static Capability<PilotInput> capability = null;

    // ---- KeyBindings: piloting ----

    /** Whether the space key was down at the last key event, for the edge sent to the server. */
    boolean spaceKeyDown;
    /** Whether the last rocket tick found Free Flight active, for the transition flash. */
    boolean freeFlightActive;
    /** Whether the ship-pilot mouse baseline is valid; false makes the next tick's delta zero. */
    boolean shipPinValid;
    /** RAW mouse motion, in vanilla look degrees, accumulated since the last ship-pilot tick. */
    float pendingCursorYawDeg;
    float pendingCursorPitchDeg;
    /** The player rotation the camera was pinned to at the end of the previous tick. */
    float lastPinnedYaw;
    float lastPinnedPitch;
    /** Whether the camera has been pinned to the craft this flight. */
    boolean cameraPinValid;
    /** Client ticks of ship control: the clock the input-repeat cadence counts on. */
    long shipInputTick;
    /** Engine-start hold progress, 0..{@code KeyBindings.ENGINE_START_HOLD_TICKS}. */
    int engineStartHoldTicks;
    /** Whether this hold has already sent its one engine-start packet. */
    boolean engineStartSent;
    /** Ticks left to flash the engine-state line, and which line. */
    int engineFlashTicks;
    boolean engineFlashStarted;
    /** Mouse motion captured at the head of a position packet, re-applied at its return. */
    float pendingMouseYaw;
    float pendingMousePitch;
    boolean teleportCaptureArmed;

    // ---- DeckLook: the look held in the deck frame ----

    /** Whether the deck-frame look currently owns the player's aim. */
    boolean deckActive;
    /** The held look in the DECK frame, degrees; pitch clamped to +/-90 like vanilla. */
    double deckYawDeg;
    double deckPitchDeg;
    /** What the deck look last wrote into the world rotation; NaN = never written. */
    float lastWrittenYaw = Float.NaN;
    float lastWrittenPitch = Float.NaN;

    private PilotInput() {
    }

    /** {@code player}'s input. Throws when it carries none: that is a broken mod, not a player at rest. */
    public static PilotInput of(EntityPlayerSP player) {
        PilotInput input = capability == null ? null : player.getCapability(capability, null);
        if (input == null) {
            throw new IllegalStateException("the local player carries no PilotInput; the capability was not registered or not attached");
        }
        return input;
    }

    /** Register the capability. Client pre-init: it must exist before the first player is built. */
    public static void register() {
        CapabilityManager.INSTANCE.register(PilotInput.class, new Capability.IStorage<PilotInput>() {
            @Override
            public NBTBase writeNBT(Capability<PilotInput> cap, PilotInput instance, EnumFacing side) {
                return null; // input is never persisted
            }

            @Override
            public void readNBT(Capability<PilotInput> cap, PilotInput instance, EnumFacing side, NBTBase nbt) {
            }
        }, PilotInput::new);
    }

    /** Give the local player a fresh input as it is built. */
    public static void attach(AttachCapabilitiesEvent<Entity> event) {
        if (!(event.getObject() instanceof EntityPlayerSP)) {
            return;
        }
        PilotInput input = new PilotInput();
        event.addCapability(new ResourceLocation("stellurgy", "pilot_input"), new ICapabilityProvider() {
            @Override
            public boolean hasCapability(Capability<?> cap, EnumFacing facing) {
                return cap == capability;
            }

            @Override
            public <C> C getCapability(Capability<C> cap, EnumFacing facing) {
                return cap == capability ? capability.cast(input) : null;
            }
        });
    }
}
