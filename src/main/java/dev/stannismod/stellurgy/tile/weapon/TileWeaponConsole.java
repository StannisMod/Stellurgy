package dev.stannismod.stellurgy.tile.weapon;

import io.netty.buffer.ByteBuf;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.inventory.Container;
import net.minecraft.inventory.IContainerListener;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.util.ITickable;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.RayTraceResult;
import net.minecraft.util.math.Vec3d;
import net.minecraft.util.text.TextComponentTranslation;
import net.minecraft.world.World;
import net.minecraftforge.fml.common.network.ByteBufUtils;
import net.minecraftforge.fml.relauncher.Side;
import dev.stannismod.stellurgy.api.StellurgyConfiguration;
import dev.stannismod.stellurgy.api.StellurgyBlocks;
import dev.stannismod.stellurgy.api.sensor.TargetTrack;
import dev.stannismod.stellurgy.integration.vs.VSIntegration;
import dev.stannismod.stellurgy.subsystem.network.ISubsystemNetworkController;
import dev.stannismod.stellurgy.subsystem.network.SubsystemNetworkDomain;
import dev.stannismod.stellurgy.subsystem.network.SubsystemNetworkManager;
import dev.stannismod.stellurgy.subsystem.network.SubsystemNetworkState;
import dev.stannismod.stellurgy.subsystem.network.SubsystemNetworkStatus;
import dev.stannismod.stellurgy.weapon.HullAllegianceRule;
import dev.stannismod.stellurgy.weapon.TurretMechanism;
import dev.stannismod.stellurgy.weapon.WeaponNetworkDomain;
import dev.stannismod.stellurgy.weapon.WeaponNetworkState;
import dev.stannismod.stellurgy.libvulpes.LibVulpes;
import dev.stannismod.stellurgy.libvulpes.inventory.modules.IButtonInventory;
import dev.stannismod.stellurgy.libvulpes.inventory.modules.IModularInventory;
import dev.stannismod.stellurgy.libvulpes.inventory.modules.ModuleBase;
import dev.stannismod.stellurgy.libvulpes.inventory.modules.ModuleButton;
import dev.stannismod.stellurgy.libvulpes.inventory.modules.ModuleText;
import dev.stannismod.stellurgy.libvulpes.interfaces.ILinkAimedTile;
import dev.stannismod.stellurgy.libvulpes.inventory.TextureResources;
import dev.stannismod.stellurgy.libvulpes.network.PacketHandler;
import dev.stannismod.stellurgy.libvulpes.network.PacketMachine;
import dev.stannismod.stellurgy.libvulpes.util.INetworkMachine;

import javax.annotation.Nonnull;
import java.util.ArrayList;
import java.util.List;

/**
 * One place to point a battery, and the only thing the weapons network adds that a gun cannot do
 * alone.
 *
 * <h3>It edits the network's orders, and keeps them across a restart</h3>
 * <p>Every button below writes to {@link WeaponNetworkState} and every readout reads from it, so two
 * consoles on one network cannot disagree while it runs — they are looking at the same object. The
 * network itself is never saved, though, so each console also keeps a COPY of the orders with the
 * time they were given, writes it to its save, and a rebuilt network takes the latest copy any of its
 * consoles holds ({@link WeaponNetworkDomain}). The copy is never answered from.</p>
 *
 * <h3>What it is FOR</h3>
 * <p>Convenience, not capability. Every gun on the network already aims and fires by itself; what a
 * console buys is doing it to a dozen guns at once, and being able to say "track but do not shoot"
 * without walking to each of them. A network with no console is a working battery whose guns are
 * commanded individually — which is exactly what the guns' own tests pin.</p>
 */
public class TileWeaponConsole extends TileEntity implements ITickable, ISubsystemNetworkController,
        ILinkAimedTile, IModularInventory, IButtonInventory, INetworkMachine {

    private static final int BUTTON_HOLD_FIRE = 0;
    private static final int BUTTON_CLEAR_TARGET = 1;
    private static final int BUTTON_HULL_ALLEGIANCE = 2;

    private static final byte NET_TOGGLE_HOLD_FIRE = 0;
    private static final byte NET_CLEAR_TARGET = 1;
    /** Server&rarr;client: what the open screen should say. See {@link ReadoutSync}. */
    private static final byte NET_READOUT = 2;
    private static final byte NET_NEXT_HULL_ALLEGIANCE = 3;

    /**
     * How far a player may be from the console's centre and still use it, squared, in blocks: the
     * 64.0 that vanilla's {@code TileEntityLockableLoot#isUsableByPlayer} and
     * {@code TileEntityFurnace#isUsableByPlayer} compare against, so a console is exactly as far
     * away as a chest is.
     */
    private static final double CONTAINER_REACH_SQ = 64.0D;

    /** The screen's lines, in the order {@link #readoutLines} produces them. */
    private static final int READOUT_LINES = 5;

    private static final String NBT_ORDERS = "weaponOrders";
    private static final String NBT_ORDERS_STAMP = "weaponOrdersStamp";

    private boolean registered;

    /** The network's orders as of the last copy, saved with this block; null before the first. */
    private NBTTagCompound savedOrders;
    /** When {@link #savedOrders} were given, in world total time. */
    private long savedOrdersStamp = WeaponNetworkState.NO_STAMP;
    /** The network state and revision {@link #savedOrders} were copied from; not saved. */
    private WeaponNetworkState copiedFrom;
    private int copiedRevision;

    /** The open screen's readout lines; refilled each time the server sends a readout. */
    private final List<ModuleText> readouts = new ArrayList<>();

    @Override
    public void update() {
        if (world == null || world.isRemote) {
            return;
        }
        if (VSIntegration.isOnUnnamedShip(world, pos)) {
            // Same rule as a gun's: aboard a ship nobody has named, this console's own position is a
            // shipyard address, so it must not join a network or command anything.
            return;
        }
        if (!registered) {
            SubsystemNetworkManager.of(world).register(this);
            SubsystemNetworkManager.of(world).markDirty(WeaponNetworkDomain.INSTANCE, world);
            registered = true;
        }
    }

    // ---- network membership

    @Override
    public SubsystemNetworkDomain getNetworkDomain() {
        return WeaponNetworkDomain.INSTANCE;
    }

    @Override
    public World getNodeWorld() {
        return world;
    }

    @Override
    public BlockPos getNodePos() {
        return pos;
    }

    /**
     * The network hands its state over on every solve, and the console copies the ORDERS whenever
     * they have changed — never to answer from: every getter below still reads the network. The copy
     * is what this console writes to its save, because the network itself is never saved.
     */
    @Override
    public void applyNetworkState(SubsystemNetworkState state) {
        if (state instanceof WeaponNetworkState && world != null && !world.isRemote
                && copyOrders((WeaponNetworkState) state)) {
            markDirty();
        }
    }

    /**
     * Take the network's orders and their stamp, unless this copy is already of them.
     *
     * @return whether the copy changed, which is when the block needs saving again
     */
    private boolean copyOrders(WeaponNetworkState state) {
        if (state == copiedFrom && state.getOrdersRevision() == copiedRevision) {
            return false;
        }
        savedOrdersStamp = state.stampOrders(world.getTotalWorldTime());
        savedOrders = state.writeOrders();
        copiedFrom = state;
        copiedRevision = state.getOrdersRevision();
        return true;
    }

    /**
     * The orders this console last copied from its network, as it saves them; null when it has never
     * been on one. Read by the network's rebuild, which takes the latest orders its consoles hold.
     */
    public NBTTagCompound getSavedOrders() {
        return savedOrders == null ? null : savedOrders.copy();
    }

    /** When {@link #getSavedOrders} were given, or {@link WeaponNetworkState#NO_STAMP}. */
    public long getSavedOrdersStamp() {
        return savedOrders == null ? WeaponNetworkState.NO_STAMP : savedOrdersStamp;
    }

    /**
     * The network this console is on, or null when it stands alone.
     *
     * <p>SERVER ONLY: the network lives in the server session, and asking from a client world throws
     * {@link dev.stannismod.stellurgy.util.WrongSideException}. Nothing a client shows is derived from
     * this — the screen is told by the server instead ({@link ReadoutSync}). Every getter below
     * inherits this.</p>
     */
    public WeaponNetworkState network() {
        SubsystemNetworkState state = SubsystemNetworkManager.getState(WeaponNetworkDomain.INSTANCE,
                world, pos);
        return state instanceof WeaponNetworkState ? (WeaponNetworkState) state : null;
    }

    // ---- the commands a console exists to give

    /**
     * Give the network an order through this console: {@code change} edits the network's state, and
     * this console copies the result on the spot rather than at the next solve. The copy carries the
     * order's stamp, so a network joined to another in this same tick still sees this console as
     * holding the latest order — waiting for the solve would leave the order on a state the join may
     * discard.
     *
     * @return false when the console is on no network, which commands nothing
     */
    private boolean order(java.util.function.Consumer<WeaponNetworkState> change) {
        WeaponNetworkState state = network();
        if (state == null) {
            return false;
        }
        change.accept(state);
        if (copyOrders(state)) {
            markDirty();
        }
        return true;
    }

    /** Point every gun on this network at a world point. */
    public boolean assignTarget(Vec3d target) {
        return order(state -> state.setTarget(target));
    }

    /** Point every gun on this network at an entity, and keep pointing as it moves. */
    public boolean assignTargetEntity(java.util.UUID entity) {
        return order(state -> state.setTargetEntity(entity));
    }

    /**
     * The credential a target may present to be recognised as friendly. Set on the network rather
     * than per gun, because "who is on our side" is a property of the installation, and a battery
     * whose guns disagreed about it would shoot its own crew at random.
     */
    public boolean setAccessCode(String code) {
        return order(state -> state.setAccessCode(code));
    }

    public String getAccessCode() {
        WeaponNetworkState state = network();
        return state == null ? "" : state.getAccessCode();
    }

    public java.util.UUID getTargetEntity() {
        WeaponNetworkState state = network();
        return state == null ? null : state.getTargetEntity();
    }

    /** Point every gun on this network at a ship, by the physics substrate's id, and keep following it. */
    public boolean assignTargetShip(String shipId) {
        return order(state -> {
            state.clearTarget();
            state.setTargetShip(shipId);
        });
    }

    public String getTargetShip() {
        WeaponNetworkState state = network();
        return state == null ? null : state.getTargetShip();
    }

    /** The rule this network tells a friendly hull by; see {@link HullAllegianceRule}. */
    public boolean setHullAllegiance(HullAllegianceRule rule) {
        return order(state -> state.setHullAllegiance(rule));
    }

    public HullAllegianceRule getHullAllegiance() {
        WeaponNetworkState state = network();
        return state == null ? null : state.getHullAllegiance();
    }

    public boolean clearTarget() {
        return order(WeaponNetworkState::clearTarget);
    }

    /**
     * Track but do not shoot. Deliberately a separate switch from having a target: a battery
     * watching an approaching ship without firing on it is the normal state of a defended station,
     * and clearing the target to stop the shooting would lose the tracking too.
     */
    public boolean setHoldFire(boolean hold) {
        return order(state -> state.setHoldFire(hold));
    }

    public boolean isHoldFire() {
        WeaponNetworkState state = network();
        return state != null && state.isHoldFire();
    }

    public Vec3d getTarget() {
        WeaponNetworkState state = network();
        return state == null ? null : state.getTarget();
    }

    /**
     * What the installation's sensor is currently holding, or null. Shown beside the assigned target
     * rather than instead of it: the two are different things, and a crew that cannot see which one
     * their guns are going on cannot tell an acquisition they want from one they need to override.
     */
    public TargetTrack getAcquiredTrack() {
        WeaponNetworkState state = network();
        return state == null || world == null ? null
                : state.getAcquiredTrack(world.getTotalWorldTime());
    }

    /** How many guns this console is commanding, as the last solve counted them. */
    public int getGunCount() {
        WeaponNetworkState state = network();
        return state == null ? 0 : state.getSinkCount();
    }

    /**
     * How many of this network's guns are pointing where they were told, and how many are asking for
     * a bearing they cannot reach.
     *
     * <p>Read off the member tiles rather than accumulated into the network state: the mounts already
     * know, and a second copy updated on a different cadence would be a readout that disagrees with
     * the guns it describes. A saturated count above zero is the console's answer to "why is nothing
     * being hit" — the target is outside somebody's arc, which is a fact about the BUILD, not a
     * fault.</p>
     *
     * @return {@code [onTarget, saturated, total]}
     */
    public int[] getMountTelemetry() {
        WeaponNetworkState state = network();
        int onTarget = 0, saturated = 0, total = 0;
        if (state == null || world == null) {
            return new int[] {0, 0, 0};
        }
        for (BlockPos member : state.getMemberPositions()) {
            TileEntity tile = world.getTileEntity(member);
            if (!(tile instanceof TileTurret)) {
                continue;
            }
            total++;
            TurretMechanism mount = ((TileTurret) tile).getMechanism();
            if (mount.isOnTarget()) {
                onTarget++;
            }
            if (mount.isSaturated()) {
                saturated++;
            }
        }
        return new int[] {onTarget, saturated, total};
    }

    public String getNetworkStatusText() {
        return readoutText(networkStatusKey());
    }

    /**
     * The same status as a stable machine token ({@code balanced}, {@code powerLimited}, ...).
     *
     * <p>Derived from the lang key rather than declared beside it, so there is one vocabulary and
     * not two. A probe or a log wants an identifier that survives translation; a player wants a
     * sentence in his own language. These are different needs and this is the first of them.</p>
     */
    public String getNetworkStatusToken() {
        String key = networkStatusKey();
        return key.substring(key.lastIndexOf('.') + 1);
    }

    private String networkStatusKey() {
        WeaponNetworkState state = network();
        if (state == null) {
            return "msg.weaponConsole.status.noNetwork";
        }
        switch (state.getStatus()) {
            case SubsystemNetworkStatus.DISCONNECTED:
                return "msg.weaponConsole.status.disconnected";
            case SubsystemNetworkStatus.SOURCE_LIMITED:
                return "msg.weaponConsole.status.powerLimited";
            case SubsystemNetworkStatus.SINK_LIMITED:
                return "msg.weaponConsole.status.idle";
            case SubsystemNetworkStatus.CABLE_LIMITED:
                return "msg.weaponConsole.status.cableLimited";
            case SubsystemNetworkStatus.BALANCED:
                return "msg.weaponConsole.status.balanced";
            default:
                return "msg.weaponConsole.status.unknown";
        }
    }

    /**
     * One readout line, translated where a translation exists.
     *
     * <p>A whole sentence per key with its placeholders in it, never a label concatenated with a
     * value: word order is not the same in every language, and a line assembled from fragments can
     * only ever come out in English order. {@code getModules} runs on both sides — the client proxy
     * translates and the common one hands the key straight back, which then formats to itself
     * because a key carries no format specifiers.</p>
     */
    private static String readoutText(String key, Object... args) {
        return String.format(LibVulpes.proxy.getLocalizedString(key), args);
    }

    // ---- linker: the way a player names a target for the whole battery (see LinkerDesignation)

    @Override
    public boolean onLinkStart(@Nonnull ItemStack item, TileEntity entity, EntityPlayer player, World world) {
        LinkerDesignation.bind(item, this, player);
        return true;
    }

    @Override
    public boolean onLinkComplete(@Nonnull ItemStack item, TileEntity entity, EntityPlayer player, World world) {
        LinkerDesignation.bind(item, this, player);
        return true;
    }

    /**
     * The order goes to the NETWORK, so every gun on it takes it; a console on no network commands
     * nothing and says so rather than appearing to have taken the order.
     */
    @Override
    public boolean onLinkAimed(@Nonnull ItemStack linker, @Nonnull RayTraceResult aimedAt, EntityPlayer player) {
        boolean taken = order(state -> {
            state.clearTarget();
            if (aimedAt.typeOfHit == RayTraceResult.Type.ENTITY) {
                state.setTargetEntity(aimedAt.entityHit.getUniqueID());
            } else {
                state.setTarget(aimedAt.hitVec);
            }
        });
        if (!taken) {
            player.sendMessage(new TextComponentTranslation("msg.weaponLinker.noNetwork"));
            return false;
        }
        LinkerDesignation.confirm(player, aimedAt);
        return true;
    }

    // ---- GUI

    /**
     * The screen. Built on both sides — libVulpes pairs the server's container with the client's — but
     * its READOUT is the server's alone: the client builds the lines blank and the server fills them
     * ({@link ReadoutSync}), because everything they describe lives in a network the client JVM does
     * not run. Blank until the first readout lands, which claims nothing, rather than a client-side
     * "no network" that would be a confident wrong answer.
     */
    @Override
    public List<ModuleBase> getModules(int id, EntityPlayer player) {
        List<ModuleBase> modules = new ArrayList<>();
        readouts.clear();

        modules.add(new ModuleButton(10, 20, BUTTON_HOLD_FIRE,
                LibVulpes.proxy.getLocalizedString("msg.weaponConsole.holdFire"), this,
                TextureResources.buttonBuild, 80, 18));
        modules.add(new ModuleButton(10, 42, BUTTON_CLEAR_TARGET,
                LibVulpes.proxy.getLocalizedString("msg.weaponConsole.clearTarget"), this,
                TextureResources.buttonBuild, 80, 18));
        modules.add(new ModuleButton(94, 20, BUTTON_HULL_ALLEGIANCE,
                LibVulpes.proxy.getLocalizedString("msg.weaponConsole.hullAllegiance"), this,
                TextureResources.buttonBuild, 80, 18));

        for (int line = 0; line < READOUT_LINES; line++) {
            ModuleText text = new ModuleText(10, 68 + 12 * line, "", 0x2b2b2b);
            readouts.add(text);
            modules.add(text);
        }
        modules.add(new ReadoutSync(this));
        return modules;
    }

    /**
     * Everything the screen says, as the server knows it. Server only — see {@link #network()}.
     *
     * <p>A tag rather than finished text: the client translates into its own language, and the
     * server's proxy cannot. A target or a contact is present in the tag only when it exists.</p>
     */
    NBTTagCompound readoutTag() {
        NBTTagCompound tag = new NBTTagCompound();
        tag.setString("status", networkStatusKey());
        tag.setBoolean("holding", isHoldFire());
        int[] mounts = getMountTelemetry();
        tag.setInteger("guns", getGunCount());
        tag.setInteger("onTarget", mounts[0]);
        tag.setInteger("saturated", mounts[1]);
        Vec3d target = getTarget();
        if (target != null) {
            tag.setDouble("targetX", target.x);
            tag.setDouble("targetY", target.y);
            tag.setDouble("targetZ", target.z);
        }
        if (getTargetShip() != null) {
            tag.setBoolean("targetShip", true);
        }
        HullAllegianceRule allegiance = getHullAllegiance();
        if (allegiance != null) {
            tag.setString("allegiance", allegiance.getLangKey());
        }
        TargetTrack acquired = getAcquiredTrack();
        if (acquired != null) {
            tag.setBoolean("locked", acquired.isLocked(StellurgyConfiguration.getCurrentConfig()
                    .fireControlSensorLockQualityToFire));
            tag.setDouble("distance", acquired.getDistance());
            tag.setDouble("quality", acquired.getQuality());
        }
        return tag;
    }

    /** The screen's lines for a readout, in the client's language; {@link #READOUT_LINES} of them. */
    private static String[] readoutLines(NBTTagCompound readout) {
        String status = readoutText(readout.getString("status"));
        String network = readout.getBoolean("holding")
                ? readoutText("msg.weaponConsole.line.networkHolding", status)
                : readoutText("msg.weaponConsole.line.network", status);
        int guns = readout.getInteger("guns");
        int onTarget = readout.getInteger("onTarget");
        int saturated = readout.getInteger("saturated");
        String gunLine = saturated > 0
                ? readoutText("msg.weaponConsole.line.gunsOutOfArc", guns, onTarget, saturated)
                : readoutText("msg.weaponConsole.line.guns", guns, onTarget);
        String targetLine = readout.getBoolean("targetShip")
                ? readoutText("msg.weaponConsole.line.targetShip")
                : readout.hasKey("targetX")
                ? readoutText("msg.weaponConsole.line.target", readout.getDouble("targetX"),
                        readout.getDouble("targetY"), readout.getDouble("targetZ"))
                : readoutText("msg.weaponConsole.line.targetNone");
        String allegianceLine = readout.hasKey("allegiance")
                ? readoutText("msg.weaponConsole.line.allegiance", readoutText(readout.getString("allegiance")))
                : "";
        String sensorLine = !readout.hasKey("distance")
                ? readoutText("msg.weaponConsole.line.sensorNone")
                : readoutText(readout.getBoolean("locked") ? "msg.weaponConsole.line.sensor"
                                : "msg.weaponConsole.line.sensorTooPoor",
                        readout.getDouble("distance"), readout.getDouble("quality"));
        return new String[] {network, gunLine, targetLine, sensorLine, allegianceLine};
    }

    /** Client: a readout from the server arrived for the open screen. */
    private void showReadout(NBTTagCompound readout) {
        String[] lines = readoutLines(readout);
        for (int line = 0; line < readouts.size() && line < lines.length; line++) {
            readouts.get(line).setText(lines[line]);
        }
    }

    /**
     * Carries the readout to whoever has this console's screen open, while it is open.
     *
     * <p>Lives in the container, not in the tile's update tag, because the readout matters only to a
     * player looking at it: a container exists exactly while one is, and is per player. The tile
     * itself owns nothing to replicate — every line is the network's state or the guns' — so an
     * update tag would mean every console recomputing and pushing, forever, for screens nobody has
     * open.</p>
     *
     * <p>Sent whenever the readout differs from the last one sent — once on opening, then on each
     * change. Not thresholded: a closing contact changes its displayed distance about every tick, the
     * player is watching exactly that, and the cost is one small packet a tick to one player. Its own
     * packet rather than a window property, because a window property is a {@code short} and a
     * target coordinate is not.</p>
     */
    private static final class ReadoutSync extends ModuleBase {

        private final TileWeaponConsole console;
        private NBTTagCompound sent;

        ReadoutSync(TileWeaponConsole console) {
            super(0, 0);
            this.console = console;
        }

        @Override
        public int numberOfChangesToSend() {
            return 1;
        }

        /**
         * Overridden whole: the base answers yes on two consecutive ticks for one change, which for a
         * packet means sending it twice.
         */
        @Override
        public boolean isUpdateRequired(int localId) {
            World world = console.getWorld();
            return world != null && !world.isRemote && !console.readoutTag().equals(sent);
        }

        @Override
        public void sendChanges(Container container, IContainerListener listener, int variableId, int localId) {
            if (listener instanceof EntityPlayerMP) {
                sent = console.readoutTag();
                PacketHandler.sendToPlayer(new PacketMachine(console, NET_READOUT), (EntityPlayerMP) listener);
            }
        }
    }

    /**
     * A button runs on the CLIENT ({@code ModuleButton.actionPerform} is client-only), and the state it
     * edits — the weapon network's — lives only on the server, so the press travels as a packet and is
     * applied in {@link #useNetworkData}. The toggle is computed THERE, from the server's own flag, so a
     * client whose readout is a tick stale still flips the real one.
     */
    @Override
    public void onInventoryButtonPressed(int buttonId) {
        if (buttonId == BUTTON_HOLD_FIRE) {
            PacketHandler.sendToServer(new PacketMachine(this, NET_TOGGLE_HOLD_FIRE));
        } else if (buttonId == BUTTON_CLEAR_TARGET) {
            PacketHandler.sendToServer(new PacketMachine(this, NET_CLEAR_TARGET));
        } else if (buttonId == BUTTON_HULL_ALLEGIANCE) {
            PacketHandler.sendToServer(new PacketMachine(this, NET_NEXT_HULL_ALLEGIANCE));
        }
    }

    @Override
    public void writeDataToNetwork(ByteBuf out, byte id) {
        if (id == NET_READOUT) {
            ByteBufUtils.writeTag(out, readoutTag());
        }
    }

    @Override
    public void readDataFromNetwork(ByteBuf in, byte packetId, NBTTagCompound nbt) {
        if (packetId == NET_READOUT) {
            nbt.setTag("readout", ByteBufUtils.readTag(in));
        }
    }

    @Override
    public void useNetworkData(EntityPlayer player, Side side, byte id, NBTTagCompound nbt) {
        if (side.isClient()) {
            if (id == NET_READOUT) {
                showReadout(nbt.getCompoundTag("readout"));
            }
            return;
        }
        // Who may press these buttons — the sender's world, his reach — is judged for every machine
        // packet before it gets here (PacketMachine, PacketSenderCheck), and refused there out loud.
        if (id == NET_TOGGLE_HOLD_FIRE) {
            setHoldFire(!isHoldFire());
        } else if (id == NET_CLEAR_TARGET) {
            clearTarget();
        } else if (id == NET_NEXT_HULL_ALLEGIANCE) {
            // The next rule after the server's own, as for hold-fire: a stale screen still steps the
            // real one.
            HullAllegianceRule current = getHullAllegiance();
            if (current != null) {
                setHullAllegiance(current.next());
            }
        }
    }

    @Override
    public String getModularInventoryName() {
        return StellurgyBlocks.blockWeaponConsole.getLocalizedName();
    }

    /**
     * Vanilla's rule for who may use a container, applied to a console that has no inventory: the
     * player is in this console's world, the console is still the block there, and he is within
     * {@link #CONTAINER_REACH_SQ}. It answers both for the open screen ({@code ContainerModular}
     * closes it when this turns false) and for a press arriving as a packet.
     *
     * <p>The distance is {@code EntityPlayer#getDistanceSq(double, double, double)} on purpose: on a
     * ship this console's position is a shipyard address, and Valkyrien Skies' overwrite of that
     * method measures to where the block really is.</p>
     */
    @Override
    public boolean canInteractWithContainer(EntityPlayer entity) {
        return entity != null && world != null && entity.world == world && !isInvalid()
                && entity.getDistanceSq(pos.getX() + 0.5D, pos.getY() + 0.5D, pos.getZ() + 0.5D)
                <= CONTAINER_REACH_SQ;
    }

    // ---- lifecycle

    @Override
    public void invalidate() {
        super.invalidate();
        if (world != null && !world.isRemote) {
            SubsystemNetworkManager.of(world).unregister(this);
            // The domain clears the target when a component loses its last console: a battery left
            // firing at a point nobody can retract is the one failure a player cannot fix by
            // breaking something.
            SubsystemNetworkManager.of(world).markDirty(WeaponNetworkDomain.INSTANCE, world);
        }
        registered = false;
    }

    @Override
    public void onChunkUnload() {
        super.onChunkUnload();
        if (world != null && !world.isRemote) {
            SubsystemNetworkManager.of(world).unregister(this);
        }
        registered = false;
    }

    /**
     * Saves the network's orders as this console last copied them, with their stamp. An order given
     * in the same tick as the save has not been through a solve yet, so the copy is refreshed here
     * first — otherwise a world saved straight after a button press would come back without it.
     */
    @Override
    public NBTTagCompound writeToNBT(NBTTagCompound nbt) {
        super.writeToNBT(nbt);
        if (world != null && !world.isRemote) {
            WeaponNetworkState state = network();
            if (state != null) {
                copyOrders(state);
            }
        }
        if (savedOrders != null) {
            nbt.setTag(NBT_ORDERS, savedOrders.copy());
            nbt.setLong(NBT_ORDERS_STAMP, savedOrdersStamp);
        }
        return nbt;
    }

    @Override
    public void readFromNBT(NBTTagCompound nbt) {
        super.readFromNBT(nbt);
        if (nbt.hasKey(NBT_ORDERS)) {
            savedOrders = nbt.getCompoundTag(NBT_ORDERS);
            savedOrdersStamp = nbt.getLong(NBT_ORDERS_STAMP);
        } else {
            savedOrders = null;
            savedOrdersStamp = WeaponNetworkState.NO_STAMP;
        }
        copiedFrom = null;
    }
}
