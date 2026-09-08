package zmaster587.advancedRocketry.test.mixin;

import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityLivingBase;
import net.minecraft.world.World;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import zmaster587.advancedRocketry.integration.vs.ShipFrameTravel;
import zmaster587.advancedRocketry.integration.vs.VSIntegration;
import zmaster587.advancedRocketry.test.trace.ShipFrameGuardState;
import zmaster587.advancedRocketry.test.trace.TestTrace;

/**
 * Watches AR's own ship-frame travel, which is where a crew member's velocity is composed.
 *
 * <h2>Why this method</h2>
 *
 * <p>The phase recorder placed the launch BETWEEN ticks — after one {@code Entity.move} returned and
 * before the next began — and named the caller that followed it: {@code EntityLivingBase.travel}.
 * AR's {@code ShipFrameTravel.travel} is invoked from exactly there, ahead of {@code move}, so a
 * velocity it writes lands in that window and shows up as "between-ticks" with vanilla's travel
 * beneath it. Only the public entry is instrumented: its two branches are private and take a package-private
 * state type, which an injector's signature would have to name, and the entry already answers the
 * question being asked — did AR's ship-frame travel leave this velocity behind.</p>
 *
 * <p>It announces itself on ENTRY, before any threshold, so that a silence is readable rather than
 * merely empty — a body this method never handled and one it handled without writing are different
 * answers, and only the registry tells them apart. The {@code handled} field carries the return
 * value, which says whether AR took the body at all.</p>
 *
 * <p>A mixin from the tests into the product, which is the allowed direction; test source set,
 * queued by the harness coremod, absent from a released jar.</p>
 */
@Mixin(value = ShipFrameTravel.class, remap = false)
public abstract class MixinShipFrameTravelWrites {

    /** Motion magnitude worth a record. A crew member riding a deck sits far below this. */
    private static final double MOTION_REPORT = 4.0;

    /**
     * The body whose tick is being resolved right now, per thread.
     *
     * <p>{@code noteTickHistory} is entity-less — which is exactly why production used to accumulate
     * its per-tick line into one JVM-global ring and why nothing could tell one body's story out of
     * it. Its three callers all take the entity as their first parameter, so it is captured at their
     * HEADs and read back a few frames later at the seam that has the numbers. Per THREAD because
     * this class resolves on the client and the server both, and an integrated game runs the two in
     * one JVM.</p>
     *
     * <p>A private static on a mixin is allowed; a non-private one is not, and this is why the field
     * is not simply shared.</p>
     */
    private static final ThreadLocal<EntityLivingBase> ARTEST$RESOLVING = new ThreadLocal<>();

    @Inject(method = "travel", at = @At("HEAD"))
    private static void arTest$enterTravel(EntityLivingBase entity, float strafe, float vertical,
                                           float forward, float jumpMovementFactor, CallbackInfoReturnable<Boolean> cir) {
        ARTEST$RESOLVING.set(entity);
    }

    /**
     * The per-tick line, recorded where production used to append it to a ring.
     *
     * <p>Everything the line carried arrives here as a PARAMETER — the path character, the committed
     * ship-frame point, the carry and whether the body ended the tick on its deck — so no local
     * capture is involved, which is the injection form this project treats as a child-JVM fatal when
     * it is wrong.</p>
     *
     * <p>Recorded per resolved tick, so it turns its own 256-deep ring over in about thirteen seconds
     * — a reader takes a mark and asks for the window it cares about, which is what the global ring
     * could not offer.</p>
     */
    @Inject(method = "noteTickHistory", at = @At("HEAD"))
    private static void arTest$tickLine(char path, double heldX, double heldY, double heldZ,
                                        double carryX, double carryY, double carryZ,
                                        boolean onDeck, int obstacleCount, CallbackInfo ci) {
        EntityLivingBase entity = ARTEST$RESOLVING.get();
        if (entity == null) {
            return;
        }
        TestTrace.instrument(entity, "ship_frame_tick_events");
        double[] walk = ARTEST$WALK.get();
        // The SAME line production used to append to its ring, byte for byte: eighteen readers across
        // two test classes parse this format, and changing it and them in one step would have been a
        // rewrite of the parsing layer on top of a move. What changed is WHO builds it and where it
        // goes — a record attributed to this body, in the ring the reader can take a mark in, instead
        // of one JVM-global string that held every body at once.
        String line = String.format(java.util.Locale.ROOT,
                "%d%c|B=%.3f,%.3f,%.3f|H=%.3f,%.3f,%.3f|m=%.4f,%.4f,%.4f|c=%.4f|in=%.1f/%.1f|d=%d"
                        + "|s=%d%d/%d|w=%d",
                resolvedTicks, path,
                lastBodyLocalX, lastBodyLocalY, lastBodyLocalZ, heldX, heldY, heldZ,
                walk[2], walk[3], walk[4],
                Math.sqrt(carryX * carryX + carryY * carryY + carryZ * carryZ),
                walk[0], walk[1], onDeck ? 1 : 0,
                lastSweepCollidedX ? 1 : 0, lastSweepCollidedZ ? 1 : 0, obstacleCount,
                lastCommitWorldTime);
        // The line, and the same numbers as NUMBERS. The line is what eighteen existing readers
        // parse; the fields beside it are what a reader asking one question reads without a format
        // to reverse-engineer, and they are what replaced the probe verb that used to publish ten
        // statics from whichever side happened to be asked.
        TestTrace.record(entity, "ship_frame_tick",
                "\"e\":" + entity.getEntityId()
                        + ",\"who\":\"" + TestTrace.json(entity.getName()) + "\""
                        + ",\"path\":\"" + path + "\""
                        + ",\"onDeck\":" + onDeck
                        + ",\"obstacles\":" + obstacleCount
                        + ",\"carryX\":" + carryX + ",\"carryY\":" + carryY + ",\"carryZ\":" + carryZ
                        + ",\"inStrafe\":" + walk[0] + ",\"inForward\":" + walk[1]
                        + ",\"motionShipX\":" + walk[2] + ",\"motionShipY\":" + walk[3]
                        + ",\"motionShipZ\":" + walk[4]
                        + ",\"line\":\"" + TestTrace.json(line) + "\"");
    }

    /**
     * The walk inputs and the ship-frame motion of the tick being resolved, per thread.
     *
     * <p>Production hands these over at {@code noteWalkInputs}, a few frames before the seam that
     * has the committed point — the same relay {@link #ARTEST$RESOLVING} performs for the body, and
     * for the same reason: the two facts are computed in different places and belong in one record.
     * Per THREAD because this class resolves on the client and the server both, in one JVM.</p>
     *
     * <p><b>Its staleness is the production behaviour it replaces, not a new one.</b> Only the
     * ABOARD path computes a ship-frame motion; the two flying paths work in the world frame, so on
     * an {@code 'f'} or {@code 'h'} tick these five numbers are the last aboard tick's. That was
     * equally true of the five statics this holder replaces — the difference is that it is written
     * down here instead of being inferred from a probe reply months later. Zeroes until the first
     * aboard tick, which is a body that has not walked yet.</p>
     */
    private static final ThreadLocal<double[]> ARTEST$WALK = new ThreadLocal<double[]>() {
        @Override
        protected double[] initialValue() {
            return new double[]{0.0, 0.0, 0.0, 0.0, 0.0};
        }
    };

    /**
     * The sideways-drag discriminator, recorded where production computes it.
     *
     * <p>A constant lateral ship-frame motion at ZERO input names an external motion writer; a
     * correct-magnitude motion at NONZERO input pointing off the look direction names a wrong walk
     * basis. Both halves have to be read from the SAME tick for that to discriminate anything, which
     * is what a record gives and what five statics polled from another JVM could not: the reader got
     * whichever body was resolved last, on whichever side it happened to ask.</p>
     */
    @Inject(method = "noteWalkInputs", at = @At("HEAD"))
    private static void arTest$walkInputs(EntityLivingBase entity, float strafe, float forward,
                                          float deckYaw, double motionShipX, double motionShipY,
                                          double motionShipZ, CallbackInfo ci) {
        if (entity == null) {
            return;
        }
        TestTrace.instrument(entity, "ship_frame_walk_events");
        ARTEST$WALK.set(new double[]{strafe, forward, motionShipX, motionShipY, motionShipZ});
        TestTrace.record(entity, "ship_frame_walk",
                "\"e\":" + entity.getEntityId()
                        + ",\"inStrafe\":" + strafe + ",\"inForward\":" + forward
                        + ",\"deckYaw\":" + deckYaw
                        + ",\"motionShipX\":" + motionShipX
                        + ",\"motionShipY\":" + motionShipY
                        + ",\"motionShipZ\":" + motionShipZ);
    }

    /**
     * Render-vs-collision pose skew, measured where production commits a subspace point to a world
     * position.
     *
     * <p>A ship is DRAWN through the client's interpolated render transform; every collision and
     * standing computation for a resolved body maps through the GAME-TICK transform. The distance
     * between the two is the gap between the surface the player sees and the surface he stands on.
     * Production used to compute it and publish it through nine statics that held whatever body was
     * resolved last; the arithmetic is three subtractions and the render transform is a public query,
     * so the whole observation belongs on this side.</p>
     *
     * <p><b>What the record buys over the statics it replaces.</b> The reader used to poll
     * a since-deleted {@code lastRenderSkew} static every few ticks and take the maximum, so a spike
     * that rose and fell between two reads was invisible — the test class said so in its own
     * javadoc, as a known limit of the instrument. One record per commit means
     * the maximum is taken over every sample production produced in the window, not over the ones a
     * poll happened to land on.</p>
     *
     * <p>Client-side only: the render transform never advances on a dedicated server, so a
     * server-side comparison would measure the tick pose against itself.</p>
     */
    @Inject(method = "noteCommittedPose", at = @At("HEAD"))
    private static void arTest$committedPose(World world, String shipId,
                                             double subX, double subY, double subZ,
                                             double[] worldPos, String mode, CallbackInfo ci) {
        EntityLivingBase entity = ARTEST$RESOLVING.get();
        if (entity == null) {
            return;
        }
        TestTrace.instrument(entity, "render_pose_skew_events");
        if (!world.isRemote) {
            return;
        }
        // The PAIR is recorded whatever the renderer is doing — the held subspace point and the world
        // position this side committed for it are what the commit IS, and a reader on the other side
        // maps the same point through its own transform to get the cross-side divergence. Only the
        // skew itself depends on a render pose existing this frame, and its absence is recorded as
        // `drawn:false` rather than as no record at all: "the commit happened and the renderer had no
        // pose" and "the commit never happened" are different answers.
        double[] drawn = VSIntegration.renderToWorldFrameFor(world, shipId, subX, subY, subZ);
        String skew = "";
        if (drawn != null) {
            double dx = worldPos[0] - drawn[0];
            double dy = worldPos[1] - drawn[1];
            double dz = worldPos[2] - drawn[2];
            skew = ",\"skew\":" + Math.sqrt(dx * dx + dy * dy + dz * dz);
        }
        TestTrace.record(entity, "render_pose_skew",
                "\"e\":" + entity.getEntityId()
                        + ",\"ship\":\"" + TestTrace.json(shipId) + "\""
                        + ",\"mode\":\"" + TestTrace.json(mode) + "\""
                        + ",\"drawn\":" + (drawn != null)
                        + skew
                        + ",\"subX\":" + subX + ",\"subY\":" + subY + ",\"subZ\":" + subZ
                        + ",\"commitX\":" + worldPos[0]
                        + ",\"commitY\":" + worldPos[1]
                        + ",\"commitZ\":" + worldPos[2]);
    }

    /**
     * The subspace census, TAKEN here rather than read from production.
     *
     * <p>Does this side's world actually hold the ship's blocks at the subspace coordinates every
     * deck probe, sweep and interior gate reads? A side whose world never received those chunks
     * answers every one of those reads with "air", and the mechanics degrade silently. The census
     * separates that world-content failure ({@code chunkLoaded:false} / {@code nonAir:0}) from a
     * sweep defect (blocks present, collision boxes still not found).</p>
     *
     * <p>Production ran this measurement itself, on every client tick near a ship, to keep nine
     * statics fresh for tests — a block scan per tick in shipped code. It is read-only and public,
     * so it happens here now: the same call, at the same cadence, in the runs that ask for it.</p>
     *
     * <p>One record per sample, so a reader takes the LATEST rather than a static that every
     * scenario in a shared client wrote over. Its own 256-deep ring turns over in about thirteen
     * seconds, which is far longer than the "what does the client hold right now" question needs.</p>
     */
    @Inject(method = "clientCensusTick", at = @At("HEAD"))
    private static void arTest$census(EntityLivingBase entity, CallbackInfo ci) {
        if (entity == null || entity.world == null || !entity.world.isRemote) {
            return;
        }
        TestTrace.instrument(entity, "subspace_census_events");
        java.util.Map<String, Object> m = ShipFrameTravel.subspaceCensusFor(entity);
        if (m == null) {
            return; // no ship claims this position on this side — not a reading, an absence
        }
        TestTrace.record(entity, "subspace_census",
                "\"e\":" + entity.getEntityId()
                        + ",\"ship\":\"" + TestTrace.json(String.valueOf(m.get("shipId"))) + "\""
                        + ",\"tracked\":" + Boolean.TRUE.equals(m.get("tracked"))
                        + ",\"subPos\":\"" + TestTrace.json(String.valueOf(m.get("subPos"))) + "\""
                        + ",\"chunkLoaded\":" + Boolean.TRUE.equals(m.get("chunkLoaded"))
                        + ",\"nonAir\":" + m.get("nonAir")
                        + ",\"collisionBoxes\":" + m.get("collisionBoxes")
                        + ",\"region\":\"" + TestTrace.json(String.valueOf(m.get("region"))) + "\""
                        + ",\"regionNonAir\":" + m.get("regionNonAir"));
    }

    /**
     * How a seed attempt ended, for the body it was for.
     *
     * <p>A dismount whose seed never lands hands the body to vanilla's world-frame dismount spot,
     * which on a non-upright ship maps off the deck — so the outcome is the fact, and WHICH body it
     * was for is what makes it this scenario's. Production kept four lifetime counters and the text
     * of the most recent refusal: they could say that some seed somewhere had been refused, never
     * whose, when, or whether it was the one under test.</p>
     */
    @Inject(method = "noteSeedOutcome", at = @At("HEAD"))
    private static void arTest$seedOutcome(Entity entity, String shipId, String outcome,
                                           CallbackInfo ci) {
        if (entity == null || entity.world == null) {
            return;
        }
        TestTrace.instrument(entity, "ship_frame_seed_events");
        TestTrace.record(entity, "ship_frame_seed",
                "\"e\":" + entity.getEntityId()
                        + ",\"ship\":\"" + TestTrace.json(String.valueOf(shipId)) + "\""
                        + ",\"outcome\":\"" + TestTrace.json(String.valueOf(outcome)) + "\"");
    }

    /**
     * A pass of the re-seat that puts aboard bodies back on their deck points after the ships move.
     *
     * <p>Recorded only when it MOVED something, and the instrument announces the pass either way —
     * so "the pass never ran", "it ran and the ship was still" and "it re-seated N bodies" are three
     * different readings. The counter this replaces was a lifetime total, which made the sensitivity
     * gate that reads it satisfiable by any earlier scenario in a shared client: it said "this
     * mechanism exists in this JVM", where the gate's own prose claims "it ran during this roll".</p>
     */
    @Inject(method = "followShipPoses", at = @At("RETURN"))
    private static void arTest$reseatPass(World world, CallbackInfoReturnable<Integer> cir) {
        if (world == null) {
            return;
        }
        TestTrace.instrumentHere("deck_reseat_pass_events");
        int bodies = cir.getReturnValue() == null ? 0 : cir.getReturnValue();
        if (bodies <= 0) {
            return;
        }
        TestTrace.recordHere("deck_reseat_pass",
                "\"remote\":" + world.isRemote + ",\"bodies\":" + bodies);
    }

    /**
     * A world-frame mover asking to displace a body the ship-frame resolver holds.
     *
     * <p>The discriminator for a crew member dragged around in small jerks while the resolution
     * holds him: something is still pushing him through the world pipeline, and this names what
     * ({@code type}) and by how much. Recorded per REQUEST, where production kept a lifetime count
     * and the shape of the most recent one — so "did anything push this body during my window, and
     * how often" could not be asked at all.</p>
     *
     * <p>Every request is recorded, including a zero-length one: production's own trace line
     * suppressed those below an epsilon, and a mover that asks for nothing on every tick is a
     * different finding from one that never asks.</p>
     */
    @Inject(method = "noteWorldMove", at = @At("HEAD"))
    private static void arTest$worldMove(Entity entity, String type, double x, double y, double z,
                                         CallbackInfo ci) {
        if (entity == null || entity.world == null) {
            return;
        }
        TestTrace.instrument(entity, "ship_frame_world_move_events");
        TestTrace.record(entity, "ship_frame_world_move",
                "\"e\":" + entity.getEntityId()
                        + ",\"mover\":\"" + TestTrace.json(String.valueOf(type)) + "\""
                        + ",\"dx\":" + x + ",\"dy\":" + y + ",\"dz\":" + z);
    }

    /**
     * What the external-move guard measured, per pass, on the body it was judging.
     *
     * <p>The pair is the point. A frame step means nothing without the allowance it was compared
     * against, and production used to publish the two as separate statics that a reader sampled one
     * field at a time from another JVM — so the step could be one body's and the allowance another's,
     * and neither could be tied to the pass that dropped a capture. One record carries both, plus the
     * two discriminator vectors that name WHICH writer moved the body: {@code frameMoved} is the deck
     * stepping under an unmoved body, {@code entityMoved} is something moving the body itself.</p>
     *
     * <p>Recorded on every pass, which is the same per-tick cadence as the tick line and turns its
     * own 256-deep ring over in about thirteen seconds — a reader takes a mark and asks for the
     * window it cares about.</p>
     */
    @Inject(method = "noteGuardPass", at = @At("HEAD"))
    private static void arTest$guardPass(Entity entity, double frameStep, double allowed,
                                         double carrySeen, double frameMovedX, double frameMovedY,
                                         double frameMovedZ, double entityMovedX,
                                         double entityMovedY, double entityMovedZ,
                                         CallbackInfo ci) {
        if (entity == null || entity.world == null) {
            return;
        }
        TestTrace.instrument(entity, "deck_guard_pass_events");
        // Held for the per-tick deck-pose trace, which needs the step and its allowance in the SAME
        // row as the craft's pose; see ShipFrameGuardState for why that is not the record's job.
        ShipFrameGuardState.note(entity.world.isRemote, frameStep, allowed, carrySeen);
        TestTrace.record(entity, "deck_guard_pass",
                "\"e\":" + entity.getEntityId()
                        + ",\"frameStep\":" + frameStep
                        + ",\"allowed\":" + allowed
                        + ",\"carrySeen\":" + carrySeen
                        + ",\"frameMovedX\":" + frameMovedX
                        + ",\"frameMovedY\":" + frameMovedY
                        + ",\"frameMovedZ\":" + frameMovedZ
                        + ",\"entityMovedX\":" + entityMovedX
                        + ",\"entityMovedY\":" + entityMovedY
                        + ",\"entityMovedZ\":" + entityMovedZ);
    }

    /**
     * Whether the solid the hull-stand sweep consumes IS the body's own world volume.
     *
     * <p>A hull-stand body is a WORLD-upright capsule standing on the ship's outer surface. Sweeping
     * a box that is axis-aligned in SUBSPACE instead puts every contact {@code h·sin(tilt/2)} from
     * where the body visibly is — at the reported ~160° attitude, about 1.8 blocks, which is the
     * "I walk about a block beside the blocks I see" report.</p>
     *
     * <p>Measured against {@code entity.getEntityBoundingBox()} — the body's own world volume,
     * maintained by vanilla from its position — because that is a value with a DIFFERENT writer.
     * Production's own numbers cannot check this: the box is built from the mapped feet and the
     * body's width and height, so a comparison against those three is arithmetic that comes out zero
     * however the box was constructed. That is precisely what the {@code lastHullBoxMismatch} static
     * this replaces had become — production wrote a literal {@code 0.0} into it, and the assertion
     * reading it could not fail.</p>
     *
     * <p>What the offset legitimately contains in a healthy tick: the body's position is where the
     * previous tick's commit left it, while the box is anchored at the feet mapped THIS tick, so the
     * two differ by about one tick of deck motion — hundredths of a block. The defect it must
     * separate that from is two orders larger.</p>
     */
    @Inject(method = "noteHullCollisionSolid", at = @At("HEAD"))
    private static void arTest$hullSolid(EntityLivingBase entity, double[] box, CallbackInfo ci) {
        if (entity == null || box == null || box.length < 6) {
            return;
        }
        TestTrace.instrument(entity, "hull_collision_solid_events");
        net.minecraft.util.math.AxisAlignedBB real = entity.getEntityBoundingBox();
        double dx = (box[0] + box[3]) / 2.0 - (real.minX + real.maxX) / 2.0;
        double dy = (box[1] + box[4]) / 2.0 - (real.minY + real.maxY) / 2.0;
        double dz = (box[2] + box[5]) / 2.0 - (real.minZ + real.maxZ) / 2.0;
        TestTrace.record(entity, "hull_collision_solid",
                "\"e\":" + entity.getEntityId()
                        + ",\"offset\":" + Math.sqrt(dx * dx + dy * dy + dz * dz)
                        // The extents travel too: an offset alone cannot tell a solid that sits in
                        // the right place from one that is the wrong SIZE there, and a failure is
                        // read by whoever arrives months later.
                        + ",\"sweptW\":" + (box[3] - box[0])
                        + ",\"sweptH\":" + (box[4] - box[1])
                        + ",\"bodyW\":" + (real.maxX - real.minX)
                        + ",\"bodyH\":" + (real.maxY - real.minY));
    }

    // The values the line carries that are not parameters of this seam. SHADOWED rather than read
    // through an accessor: they are production's own intermediate state for one tick, and the
    // appendix names a shadowed field as a legitimate source for a record. Production no longer
    // publishes any of them — the public ones that remain are read by other tests and go with their
    // own slices.
    @Shadow private static volatile long resolvedTicks;
    @Shadow private static volatile double lastBodyLocalX;
    @Shadow private static volatile double lastBodyLocalY;
    @Shadow private static volatile double lastBodyLocalZ;
    @Shadow private static volatile boolean lastSweepCollidedX;
    @Shadow private static volatile boolean lastSweepCollidedZ;
    @Shadow private static volatile long lastCommitWorldTime;

    @Inject(method = "travel", at = @At("RETURN"))
    private static void arTest$afterTravel(EntityLivingBase entity, float strafe, float vertical,
                                           float forward, float jumpMovementFactor,
                                           CallbackInfoReturnable<Boolean> cir) {
        arTest$note(entity, "ship_frame_travel", cir.getReturnValue());
    }

    private static void arTest$note(EntityLivingBase entity, String instrument, Object handled) {
        if (entity == null) {
            return;
        }
        TestTrace.instrument(entity, instrument);
        if (Math.abs(entity.motionY) <= MOTION_REPORT) {
            return;
        }
        TestTrace.record(entity, "ship_frame_motion",
                "\"e\":" + entity.getEntityId()
                        + ",\"who\":\"" + TestTrace.json(entity.getName()) + "\""
                        + ",\"where\":\"" + instrument + "\""
                        + ",\"handled\":" + handled
                        + ",\"motionY\":" + TestTrace.fmt(entity.motionY)
                        + ",\"atY\":" + TestTrace.fmt(entity.posY)
                        + ",\"by\":\"" + TestTrace.json(TestTrace.callerTrail()) + "\"");
    }
}
