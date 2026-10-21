package dev.stannismod.stellurgy.entity;

import dev.stannismod.stellurgy.Stellurgy;
import dev.stannismod.stellurgy.api.satellite.SatelliteBase;
import dev.stannismod.stellurgy.dimension.DimensionManager;
import dev.stannismod.stellurgy.dimension.DimensionProperties;
import dev.stannismod.stellurgy.inventory.TextureResources;
import dev.stannismod.stellurgy.item.ItemSatellite;
import dev.stannismod.stellurgy.libvulpes.inventory.GuiHandler;
import dev.stannismod.stellurgy.libvulpes.inventory.modules.IModularInventory;
import dev.stannismod.stellurgy.libvulpes.inventory.modules.ModuleBase;
import dev.stannismod.stellurgy.libvulpes.inventory.modules.ModuleTexturedLimitedSlotArray;
import dev.stannismod.stellurgy.satellite.OrbitalPartsInventory;
import dev.stannismod.stellurgy.satellite.SatelliteAttitude;
import dev.stannismod.stellurgy.space.SpaceSubsystem;
import dev.stannismod.stellurgy.universe.UniverseRegistry;
import io.netty.buffer.ByteBuf;
import net.minecraft.entity.Entity;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.network.datasync.DataParameter;
import net.minecraft.network.datasync.DataSerializers;
import net.minecraft.network.datasync.EntityDataManager;
import net.minecraft.util.DamageSource;
import net.minecraft.util.EnumHand;
import net.minecraft.util.math.Rotations;
import net.minecraft.world.World;
import net.minecraftforge.fml.common.registry.IEntityAdditionalSpawnData;
import org.joml.Vector3d;
import org.joml.Vector3dc;

import java.util.LinkedList;
import java.util.List;
import java.util.Optional;

/**
 * A satellite where it stands: a block-sized object at its point of its body's cell, made of the parts it
 * was built from. Kinematic — it integrates nothing and never moves, because the cell's frame is what
 * flies the orbit. The satellite itself is the record in its body's satellite list; this entity is only
 * its presence in a live cell, so it is never saved with the cell and is rebuilt from the record when the
 * chunk it stands in loads.
 *
 * <p>Right-click opens the builder's part panel: a repair in orbit. An explosion or a projectile destroys
 * it, and so do two blows from a player in quick succession — one is taken for a missed click. A
 * destroyed satellite leaves nothing: its record goes and only the explosion is seen.</p>
 */
public class EntitySatellite extends Entity implements IModularInventory, IEntityAdditionalSpawnData {

    /** Ticks between attitude recomputations: the sky feed's cadence, so the parts turn as the sky does. */
    private static final int ATTITUDE_PERIOD_TICKS = 20;
    /** Ticks within which a second blow from a player counts as meant; an armour stand's window. */
    private static final long SECOND_BLOW_WINDOW_TICKS = 5L;
    /** Effectively final, process lifetime: the key of a synced field, created once at class initialisation. */
    private static final DataParameter<Rotations> TO_STAR = EntityDataManager.createKey(EntitySatellite.class, DataSerializers.ROTATIONS);
    /** Effectively final, process lifetime: the key of a synced field, created once at class initialisation. */
    private static final DataParameter<Rotations> TO_BODY = EntityDataManager.createKey(EntitySatellite.class, DataSerializers.ROTATIONS);

    private long satelliteId = -1L;
    /** World tick of the last blow from a player that did not destroy it; -1 before any. */
    private long lastBlowTick = -1L;
    private boolean attitudeKnown;

    public EntitySatellite(World world) {
        super(world);
        setSize(1f, 1f);
        setNoGravity(true);
    }

    public EntitySatellite(World world, long satelliteId, double x, double y, double z) {
        this(world);
        this.satelliteId = satelliteId;
        setPosition(x, y, z);
    }

    public long getSatelliteId() {
        return satelliteId;
    }

    /** The record this entity stands for, or empty once it is gone from its body's list. */
    public Optional<SatelliteBase> satellite() {
        return Optional.ofNullable(DimensionManager.getInstance().getSatellite(satelliteId));
    }

    /** Unit vector toward the nearest star as last computed, cell axes; zero until the first computation. */
    public Vector3dc toStar() {
        return vector(dataManager.get(TO_STAR));
    }

    /** Unit vector toward the body as last computed, cell axes; zero until the first computation. */
    public Vector3dc toBody() {
        return vector(dataManager.get(TO_BODY));
    }

    @Override
    protected void entityInit() {
        dataManager.register(TO_STAR, new Rotations(0f, 0f, 0f));
        dataManager.register(TO_BODY, new Rotations(0f, 0f, 0f));
    }

    @Override
    public void onUpdate() {
        if (world.isRemote) {
            return;
        }
        // Smeared by id so the satellites of one cell do not all recompute on the same tick; the first
        // update computes at once, so a satellite never appears unoriented.
        if (attitudeKnown && (ticksExisted + satelliteId) % ATTITUDE_PERIOD_TICKS != 0L) {
            return;
        }
        Optional<SatelliteBase> satellite = satellite();
        if (!satellite.isPresent()) {
            setDead();
            return;
        }
        // Its life is brought up to date while someone is near enough to see it.
        satellite.get().bringUpToNow();
        Optional<SatelliteAttitude> attitude = SatelliteAttitude.of(UniverseRegistry.get(world), satellite.get(),
                SpaceSubsystem.spaceClock());
        if (attitude.isPresent()) {
            dataManager.set(TO_STAR, rotations(attitude.get().toStar()));
            dataManager.set(TO_BODY, rotations(attitude.get().toBody()));
            attitudeKnown = true;
        }
    }

    /** Never saved with the cell: the record in the body's list is the satellite, this is its presence. */
    @Override
    public boolean writeToNBTOptional(NBTTagCompound compound) {
        return false;
    }

    @Override
    public boolean writeToNBTAtomically(NBTTagCompound compound) {
        return false;
    }

    @Override
    protected void readEntityFromNBT(NBTTagCompound compound) {
    }

    @Override
    protected void writeEntityToNBT(NBTTagCompound compound) {
    }

    @Override
    public boolean canBeCollidedWith() {
        return !isDead;
    }

    @Override
    public boolean canBePushed() {
        return false;
    }

    @Override
    public boolean processInitialInteract(EntityPlayer player, EnumHand hand) {
        if (!world.isRemote && hand == EnumHand.MAIN_HAND && satellite().isPresent()) {
            player.openGui(Stellurgy.instance, GuiHandler.guiId.MODULAR.ordinal(), world, getEntityId(), -1, 0);
        }
        return true;
    }

    @Override
    public boolean attackEntityFrom(DamageSource source, float amount) {
        if (world.isRemote || isDead || isEntityInvulnerable(source)) {
            return false;
        }
        if (source.isExplosion() || source.isProjectile()) {
            destroy();
            return true;
        }
        if (source.getImmediateSource() instanceof EntityPlayer) {
            long now = world.getTotalWorldTime();
            if (lastBlowTick >= 0L && now - lastBlowTick <= SECOND_BLOW_WINDOW_TICKS) {
                destroy();
            } else {
                lastBlowTick = now;
            }
            return true;
        }
        return false;
    }

    /** The satellite is gone: its record leaves its body's list, and only the explosion is seen. */
    private void destroy() {
        satellite().ifPresent(sat -> {
            DimensionProperties props = DimensionManager.getInstance().getDimensionPropertiesOrNull(sat.getDimensionId());
            if (props != null) {
                props.removeSatellite(sat.getId());
            }
        });
        world.newExplosion(this, posX, posY + height / 2d, posZ, 0f, false, false);
        setDead();
    }

    @Override
    public List<ModuleBase> getModules(int id, EntityPlayer player) {
        List<ModuleBase> modules = new LinkedList<>();
        Optional<SatelliteBase> satellite = satellite();
        if (!satellite.isPresent() || !(satellite.get().getItemStackFromSatellite().getItem() instanceof ItemSatellite)) {
            return modules;
        }
        OrbitalPartsInventory parts = new OrbitalPartsInventory(satellite.get(), !world.isRemote);
        // The builder's panel, at the builder's places: the primary function, then the generators, then the rest.
        modules.add(new ModuleTexturedLimitedSlotArray(152, 10, parts, 0, 1, TextureResources.functionComponent));
        modules.add(new ModuleTexturedLimitedSlotArray(116, 30, parts, 1, 4, TextureResources.powercomponent));
        modules.add(new ModuleTexturedLimitedSlotArray(116, 50, parts, 4, 7, TextureResources.ioSlot));
        return modules;
    }

    @Override
    public String getModularInventoryName() {
        return satellite().map(SatelliteBase::getName).orElse("");
    }

    @Override
    public boolean canInteractWithContainer(EntityPlayer player) {
        return !isDead && player.getDistanceSq(this) < 64d;
    }

    @Override
    public void writeSpawnData(ByteBuf buffer) {
        buffer.writeLong(satelliteId);
    }

    @Override
    public void readSpawnData(ByteBuf buffer) {
        satelliteId = buffer.readLong();
    }

    private static Rotations rotations(Vector3dc v) {
        return new Rotations((float) v.x(), (float) v.y(), (float) v.z());
    }

    private static Vector3dc vector(Rotations r) {
        return new Vector3d(r.getX(), r.getY(), r.getZ());
    }
}
