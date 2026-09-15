package zmaster587.advancedRocketry.player;

import java.util.ArrayList;
import java.util.List;

import javax.annotation.Nullable;

import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.util.EnumFacing;
import net.minecraftforge.common.capabilities.Capability;
import net.minecraftforge.common.capabilities.ICapabilitySerializable;

import zmaster587.advancedRocketry.space.ShipAboardTag;

/**
 * The default {@link IPlayerBindings}, and its own capability provider.
 *
 * <p>One class rather than two because the provider has nothing to provide but this object, and a
 * separate provider would be a second file whose whole content is a cast.</p>
 *
 * <h2>Serialization is NOT a no-op here, unlike this mod's other capabilities</h2>
 *
 * <p>{@code CapabilityWear} and {@code CapabilitySpaceArmor} hang off tiles and items that persist
 * their own NBT, so their {@code IStorage} does nothing. A capability on a PLAYER has no such host:
 * Forge writes it through the provider, and {@link #serializeNBT} below is the only thing that puts
 * these facts on disk. Getting that wrong loses a player's ship silently.</p>
 *
 * <p>The aboard record is written through {@link ShipAboardTag}'s own NBT functions rather than
 * re-encoded here: that shape is documented, exercised and full of decisions (an absent posture key
 * means SEATED, an absent coordinate means "in no cell"), and a second encoder would be a second
 * place for those decisions to live.</p>
 */
public class PlayerBindings implements IPlayerBindings, ICapabilitySerializable<NBTTagCompound> {

    private static final String GRACE_UNTIL = "graceUntil";

    private ShipAboardTag.Aboard aboard;
    private long graceUntil;

    @Override
    public ShipAboardTag.Aboard aboard() {
        return aboard;
    }

    @Override
    public void setAboard(ShipAboardTag.Aboard aboard) {
        this.aboard = aboard;
    }

    @Override
    public long graceUntil() {
        return graceUntil;
    }

    @Override
    public void setGraceUntil(long worldTime) {
        this.graceUntil = worldTime;
    }

    @Override
    public List<String> boundTo(long worldTime) {
        List<String> bound = new ArrayList<>();
        if (aboard != null) {
            bound.add("aboard record");
        }
        // The grace is a DEADLINE, so an expired one is not a binding: it constrains nothing and
        // reporting it would make a release claim work it did not do.
        if (graceUntil > worldTime) {
            bound.add("rocket transfer grace");
        }
        return bound;
    }

    @Override
    public List<String> releaseAll(long worldTime) {
        List<String> released = boundTo(worldTime);
        aboard = null;
        graceUntil = 0L;
        return released;
    }

    // ---- capability plumbing --------------------------------------------------------------------

    @Override
    public boolean hasCapability(Capability<?> capability, @Nullable EnumFacing facing) {
        return capability == CapabilityPlayerBindings.PLAYER_BINDINGS;
    }

    @Override
    @Nullable
    public <T> T getCapability(Capability<T> capability, @Nullable EnumFacing facing) {
        return capability == CapabilityPlayerBindings.PLAYER_BINDINGS
                ? CapabilityPlayerBindings.PLAYER_BINDINGS.cast(this) : null;
    }

    @Override
    public NBTTagCompound serializeNBT() {
        NBTTagCompound tag = new NBTTagCompound();
        ShipAboardTag.write(tag, aboard);
        if (graceUntil != 0L) {
            tag.setLong(GRACE_UNTIL, graceUntil);
        }
        return tag;
    }

    @Override
    public void deserializeNBT(NBTTagCompound tag) {
        if (tag == null) {
            return;
        }
        aboard = ShipAboardTag.read(tag);
        graceUntil = tag.getLong(GRACE_UNTIL);
    }
}
