package dev.stannismod.stellurgy.weapon;

import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.util.math.Vec3d;
import dev.stannismod.stellurgy.Stellurgy;
import dev.stannismod.stellurgy.api.sensor.TargetTrack;
import dev.stannismod.stellurgy.subsystem.network.SubsystemNetworkState;

/**
 * What a weapons network agrees on: where it is pointing, and whether it may shoot.
 *
 * <h3>One target, held by the network rather than by a console</h3>
 * <p>Two consoles on one network cannot disagree, because neither of them owns this — they both
 * edit it. That also survives the console being broken and rebuilt, and it is what makes "assign a
 * target" a network-level act rather than a message a console has to keep re-sending to each gun.</p>
 *
 * <h3>Hold-fire is a separate switch from having a target</h3>
 * <p>Aiming and shooting are different decisions: a battery tracking an approaching ship without
 * firing on it is the normal state of a defended station. So clearing the target is not how one
 * stops the shooting, and holding fire does not make the guns forget where the enemy is.</p>
 *
 * <h3>What a human said outranks what a machine found</h3>
 * <p>An assigned target and an acquired one are kept in separate fields rather than one field
 * written by both. A sensor refreshing its contact every few ticks would otherwise silently overrule
 * the order a player gave a minute ago, and the player would have no way to hold a target the sensor
 * disagrees about. So the acquisition is only consulted when nobody has said anything.</p>
 *
 * <h3>The ORDERS outlive this object; the acquisition does not</h3>
 * <p>The network itself is never saved — it is rebuilt from the world — so what a player ordered
 * (the target, hold-fire, the code, the hull rule) is kept by the consoles: each copies the orders
 * with the time they were given ({@link #stampOrders}), and a rebuilt network takes the orders of
 * whichever console holds the latest ({@link WeaponNetworkDomain}). The sensor's acquisition is left
 * out on purpose: it is a fact about this moment, and a sensor finds it again.</p>
 */
public class WeaponNetworkState extends SubsystemNetworkState {

    /** The stamp of orders nobody has given: older than any world time. */
    public static final long NO_STAMP = Long.MIN_VALUE;

    private static final String NBT_ACCESS_CODE = "accessCode";
    private static final String NBT_HOLD_FIRE = "holdFire";
    private static final String NBT_HULL_ALLEGIANCE = "hullAllegiance";
    private static final String NBT_TARGET_X = "targetX";
    private static final String NBT_TARGET_Y = "targetY";
    private static final String NBT_TARGET_Z = "targetZ";
    private static final String NBT_TARGET_ENTITY = "targetEntity";
    private static final String NBT_TARGET_SHIP = "targetShip";

    private Vec3d target;
    private java.util.UUID targetEntity;
    private String targetShip;
    private HullAllegianceRule hullAllegiance = HullAllegianceRule.CODE_ON_WEAPONS;
    private String accessCode = "";
    private boolean holdFire;
    private TargetTrack acquiredTrack;
    private long acquiredExpiryTick;

    /** Bumped by every order; how a console learns there is something new to copy. */
    private int ordersRevision;
    /** The revision {@link #ordersStamp} was taken for. Equal to {@link #ordersRevision} once stamped. */
    private int stampedRevision;
    /** The world time the current orders were given, or {@link #NO_STAMP} for orders nobody gave. */
    private long ordersStamp = NO_STAMP;

    /** Where the network's guns are pointed, in WORLD coordinates, or null when nothing is assigned. */
    public Vec3d getTarget() {
        return target;
    }

    public void setTarget(Vec3d target) {
        this.target = target;
        ordersRevision++;
    }

    public void clearTarget() {
        dropTarget();
        ordersRevision++;
    }

    /**
     * Forget the target WITHOUT it counting as an order: the network has lost its last console, and
     * what was ordered there is not retracted — a console that comes back brings it back — it is only
     * not carried out by a battery nobody can command.
     */
    void dropTarget() {
        this.target = null;
        this.targetEntity = null;
        this.targetShip = null;
    }

    /**
     * The ship every gun on this network is following, by the physics substrate's id, or null. The
     * guns aim at the hull as a whole, wherever it is this tick, and lead it by its own motion.
     */
    public String getTargetShip() {
        return targetShip;
    }

    public void setTargetShip(String shipId) {
        this.targetShip = shipId;
        ordersRevision++;
    }

    /**
     * The rule this installation tells a friendly HULL by. A creature proves itself with the code it
     * carries; a hull carries nothing, so which ship counts as ours is the installation's choice. A new
     * network starts on {@link HullAllegianceRule#CODE_ON_WEAPONS}: the one rule whose answer the
     * battery's own owner controls, by setting the same code on both installations.
     */
    public HullAllegianceRule getHullAllegiance() {
        return hullAllegiance;
    }

    public void setHullAllegiance(HullAllegianceRule rule) {
        this.hullAllegiance = rule == null ? HullAllegianceRule.CODE_ON_WEAPONS : rule;
        ordersRevision++;
    }

    /**
     * The entity every gun on this network is following, or null. Kept beside the point target
     * rather than replacing it: a battery told to shell a position and a battery told to track a
     * ship are different orders, and one of them survives the target moving.
     */
    public java.util.UUID getTargetEntity() {
        return targetEntity;
    }

    public void setTargetEntity(java.util.UUID entity) {
        this.targetEntity = entity;
        ordersRevision++;
    }

    /**
     * The network's access code — the credential a target may present to be recognised as friendly.
     * Empty means "no code set", which recognises nobody: an unarmed default that shoots everything
     * is safer than one that shoots nothing, because the second is indistinguishable from a broken
     * gun.
     */
    public String getAccessCode() {
        return accessCode == null ? "" : accessCode;
    }

    public void setAccessCode(String code) {
        this.accessCode = code == null ? "" : code;
        ordersRevision++;
    }

    /** True while the network's guns must track but not shoot. */
    public boolean isHoldFire() {
        return holdFire;
    }

    public void setHoldFire(boolean holdFire) {
        this.holdFire = holdFire;
        ordersRevision++;
    }

    /**
     * What the network's sensor is currently holding, or null when it is holding nothing.
     *
     * <p>Takes the world's clock because a track EXPIRES. A sensor publishes on its own cadence and
     * can stop publishing for reasons a gun cannot see — its chunk unloaded, its block was broken,
     * the whole installation lost power — and none of those should leave a battery firing at where
     * something used to be. An acquisition that nobody is refreshing goes quiet by itself.</p>
     */
    public TargetTrack getAcquiredTrack(long worldTime) {
        return acquiredTrack == null || worldTime > acquiredExpiryTick ? null : acquiredTrack;
    }

    /** Publish a contact, good for {@code holdTicks} from now. Called by the sensor, nobody else. */
    public void setAcquiredTrack(TargetTrack track, long worldTime, int holdTicks) {
        this.acquiredTrack = track;
        this.acquiredExpiryTick = worldTime + Math.max(0, holdTicks);
    }

    public void clearAcquiredTrack() {
        this.acquiredTrack = null;
        this.acquiredExpiryTick = 0L;
    }

    // ---- the orders as something a console keeps

    /** Changes with every order given on this network; equal values mean "nothing new to copy". */
    public int getOrdersRevision() {
        return ordersRevision;
    }

    /**
     * When the current orders were given, stamping them with {@code now} if nobody has yet. Every
     * console of a network copies the orders on the same solve, so they all take the SAME stamp, and
     * an order is stamped at most one solve after it was given.
     *
     * @param now the world's total time — the clock saved with the world, so a stamp taken before a
     *            restart compares correctly with one taken after it
     */
    public long stampOrders(long now) {
        if (stampedRevision != ordersRevision) {
            ordersStamp = now;
            stampedRevision = ordersRevision;
        }
        return ordersStamp;
    }

    /**
     * The stamp of the current orders, or {@link #NO_STAMP} for orders nobody gave. Orders given
     * since the last {@link #stampOrders} are newer than any stamp: they were given after everything
     * that was copied out.
     */
    public long getOrdersStamp() {
        return stampedRevision != ordersRevision ? Long.MAX_VALUE : ordersStamp;
    }

    /** The orders as a console stores them. The acquisition is not an order and is not in here. */
    public NBTTagCompound writeOrders() {
        NBTTagCompound tag = new NBTTagCompound();
        tag.setString(NBT_ACCESS_CODE, getAccessCode());
        tag.setBoolean(NBT_HOLD_FIRE, holdFire);
        tag.setString(NBT_HULL_ALLEGIANCE, hullAllegiance.name());
        if (target != null) {
            tag.setDouble(NBT_TARGET_X, target.x);
            tag.setDouble(NBT_TARGET_Y, target.y);
            tag.setDouble(NBT_TARGET_Z, target.z);
        }
        if (targetEntity != null) {
            tag.setUniqueId(NBT_TARGET_ENTITY, targetEntity);
        }
        if (targetShip != null) {
            tag.setString(NBT_TARGET_SHIP, targetShip);
        }
        return tag;
    }

    /**
     * Take these orders, given at {@code stamp}, in place of the current ones — the rebuild seeding a
     * network from the console that holds the latest. Replaces every order, so what the tag does not
     * carry (no target, say) is not carried over from before.
     */
    public void adoptOrders(NBTTagCompound tag, long stamp) {
        accessCode = tag.getString(NBT_ACCESS_CODE);
        holdFire = tag.getBoolean(NBT_HOLD_FIRE);
        hullAllegiance = HullAllegianceRule.CODE_ON_WEAPONS;
        String rule = tag.getString(NBT_HULL_ALLEGIANCE);
        try {
            hullAllegiance = HullAllegianceRule.valueOf(rule);
        } catch (IllegalArgumentException unknown) {
            // A saved name this build does not know. The network starts on the default rule, and the
            // log says that is a substitute rather than what was ordered.
            Stellurgy.logger.warn("[WeaponNetwork] a console's saved hull rule '{}' is unknown; the "
                    + "network takes {} instead of the rule that was ordered", rule, hullAllegiance);
        }
        target = tag.hasKey(NBT_TARGET_X)
                ? new Vec3d(tag.getDouble(NBT_TARGET_X), tag.getDouble(NBT_TARGET_Y), tag.getDouble(NBT_TARGET_Z))
                : null;
        targetEntity = tag.hasUniqueId(NBT_TARGET_ENTITY) ? tag.getUniqueId(NBT_TARGET_ENTITY) : null;
        targetShip = tag.hasKey(NBT_TARGET_SHIP) ? tag.getString(NBT_TARGET_SHIP) : null;
        ordersRevision++;
        stampedRevision = ordersRevision;
        ordersStamp = stamp;
    }

    @Override
    public SubsystemNetworkState copy() {
        WeaponNetworkState copy = new WeaponNetworkState();
        copyInto(copy);
        copy.target = target;
        copy.targetEntity = targetEntity;
        copy.targetShip = targetShip;
        copy.hullAllegiance = hullAllegiance;
        copy.accessCode = accessCode;
        copy.holdFire = holdFire;
        copy.acquiredTrack = acquiredTrack;
        copy.acquiredExpiryTick = acquiredExpiryTick;
        copy.ordersRevision = ordersRevision;
        copy.stampedRevision = stampedRevision;
        copy.ordersStamp = ordersStamp;
        return copy;
    }
}
