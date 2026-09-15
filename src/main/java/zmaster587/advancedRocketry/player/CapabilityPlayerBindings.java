package zmaster587.advancedRocketry.player;

import net.minecraft.entity.Entity;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.nbt.NBTBase;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.util.EnumFacing;
import net.minecraft.util.ResourceLocation;
import net.minecraftforge.common.capabilities.Capability;
import net.minecraftforge.common.capabilities.CapabilityInject;
import net.minecraftforge.common.capabilities.CapabilityManager;
import net.minecraftforge.event.AttachCapabilitiesEvent;
import net.minecraftforge.event.entity.player.PlayerEvent;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;

/**
 * Where {@link IPlayerBindings} is registered, attached and carried across a death.
 *
 * <p>Modelled on this mod's existing capabilities, with one deliberate difference recorded in
 * {@link PlayerBindings}: the storage is real work, because a player is not a host that persists its
 * own NBT the way a tile is.</p>
 */
public class CapabilityPlayerBindings {

    @CapabilityInject(IPlayerBindings.class)
    public static Capability<IPlayerBindings> PLAYER_BINDINGS = null;

    private static final ResourceLocation KEY =
            new ResourceLocation("advancedrocketry", "player_bindings");

    /**
     * This player's bindings, or {@code null} before the capability is registered or on an entity
     * that has none.
     *
     * <p>Returning null rather than an empty object on purpose: an empty object is indistinguishable
     * from a real answer, and "the capability is not there" is a broken mod rather than a player
     * bound to nothing.</p>
     */
    public static IPlayerBindings get(EntityPlayer player) {
        return (player == null || PLAYER_BINDINGS == null)
                ? null : player.getCapability(PLAYER_BINDINGS, null);
    }

    public static void register() {
        CapabilityManager.INSTANCE.register(IPlayerBindings.class,
                new Capability.IStorage<IPlayerBindings>() {
                    @Override
                    public NBTBase writeNBT(Capability<IPlayerBindings> capability,
                                            IPlayerBindings instance, EnumFacing side) {
                        // The provider serializes itself (PlayerBindings is ICapabilitySerializable),
                        // which is the path Forge actually takes for an attached capability. This
                        // exists because the manager requires it, and it answers with the same bytes
                        // rather than an empty tag, so a caller who does reach it is not handed a
                        // silently empty record.
                        return instance instanceof PlayerBindings
                                ? ((PlayerBindings) instance).serializeNBT() : new NBTTagCompound();
                    }

                    @Override
                    public void readNBT(Capability<IPlayerBindings> capability,
                                        IPlayerBindings instance, EnumFacing side, NBTBase nbt) {
                        if (instance instanceof PlayerBindings && nbt instanceof NBTTagCompound) {
                            ((PlayerBindings) instance).deserializeNBT((NBTTagCompound) nbt);
                        }
                    }
                },
                PlayerBindings::new);
    }

    @SubscribeEvent
    public void attach(AttachCapabilitiesEvent<Entity> event) {
        if (event.getObject() instanceof EntityPlayer
                && !event.getCapabilities().containsKey(KEY)) {
            event.addCapability(KEY, new PlayerBindings());
        }
    }

    /**
     * CARRY THE BINDINGS THROUGH A DEATH.
     *
     * <p>Forge does not copy capabilities across a respawn: {@code EntityPlayerMP.copyFrom} takes
     * exactly one thing out of the dying player's entity data — the {@code PlayerPersisted} sub-tag
     * — and leaves everything else to this event. Without this handler a player who dies aboard his
     * ship loses the only record of which ship it was, silently, and every promise the space
     * subsystem makes about a returning crew member quietly stops holding for him. Measured and
     * ledgered 2026-09-14; it is how the mod behaved until this class existed.</p>
     *
     * <p>Copied on END-OF-LIFE too, not only on a dimension return: a player carries what he was
     * bound to through both, because from where he is standing they are the same event.</p>
     */
    @SubscribeEvent
    public void carryAcrossDeath(PlayerEvent.Clone event) {
        IPlayerBindings old = get(event.getOriginal());
        IPlayerBindings fresh = get(event.getEntityPlayer());
        if (old == null || fresh == null) {
            return;
        }
        fresh.setAboard(old.aboard());
        fresh.setGraceUntil(old.graceUntil());
    }
}
