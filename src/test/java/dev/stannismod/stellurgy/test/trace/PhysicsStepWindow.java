package dev.stannismod.stellurgy.test.trace;

import java.util.List;

import net.minecraft.world.World;

/**
 * What ONE flight computer's craft did on the PHYSICS clock between a test's {@code open} and its
 * {@code close}: its velocity and angular velocity at the first and the last physics step, the largest
 * angular speed at any step ({@code maxAngularSpeed}, radians per engine second), how much
 * physics time lay between them, and the same two differences taken over only the steps in which the
 * controller was AT the craft's authority.
 *
 * <h2>Why the physics clock and not the game tick</h2>
 *
 * <p>A craft's velocity integrates on the physics thread, which runs at its own rate and may run
 * several steps in one game tick or fall behind the game when the box is loaded. An acceleration
 * computed as a velocity difference over GAME ticks is therefore a statement about how fast the
 * machine was; over the summed {@code dt} of the steps that produced the difference it is a statement
 * about the craft. The step the flight computer's controller is handed is the step the solver
 * integrates, so it is read there.</p>
 *
 * <h2>What a sample is</h2>
 *
 * <p>Taken as the controller is ENTERED: the velocity the solver carries into this step, before this
 * step's controller force and gravity are added to it. Two consecutive samples therefore differ by
 * exactly one step's worth of everything that acts on the craft — the controller's wrench, gravity,
 * and any air the world has — and the first-to-last difference over the {@code dt} of every step in
 * between is the craft's mean acceleration over the window.</p>
 *
 * <h2>The saturated phase</h2>
 *
 * <p>A controller told to reach a velocity pushes at the craft's authority only until it gets there,
 * and how long that takes depends on the authority itself — so a window sized in game ticks would mix
 * an unknown share of "at authority" with "holding the target". The controller says, on return,
 * whether it delivered less than the flight law asked for; the velocity change a step produced is
 * seen at the NEXT step's entry. So a step's change is booked into the {@code sat*} sums exactly when
 * the step that produced it reported saturation, and {@code satEngineAccel*} is the craft's
 * acceleration while at its authority — the quantity a readout's authority predicts.</p>
 *
 * <h2>Contacts — whether the craft was in free air</h2>
 *
 * <p>A verdict about how a craft moves under its own authority, or falls under gravity alone, holds
 * only if nothing else touched it. So the window also counts, for the ship it is watching, the physics
 * mod's collider ticks ({@code colliderTicks} — the instrument was listening) and the collision impulses
 * that collider applied ({@code contacts}), both fed by a test mixin at the collider's own decision. A
 * zero {@code contacts} means "free air" only beside a non-zero {@code colliderTicks}. The ship is bound
 * at the window's first sample, so a contact before that sample is not counted.</p>
 *
 * <p>Test source set: absent from a released jar. The physics thread writes, the server thread
 * closes, so every accumulator is read and written under the window's own monitor.</p>
 */
public final class PhysicsStepWindow implements TraceWindow {

    private final int dim;
    private final int x;
    private final int y;
    private final int z;

    /** The physics object this window's computer steps with, bound at the first sample. */
    private Object ship;
    private int colliderTicks;
    private int contacts;
    private int steps;
    private double seconds;
    private double pendingDt;
    private boolean pendingSaturated;
    private final double[] firstV = new double[3];
    private final double[] lastV = new double[3];
    private final double[] firstW = new double[3];
    private final double[] lastW = new double[3];
    /** The largest angular speed any sample carried — a turn that grew and was then arrested still shows. */
    private double maxW;
    private double firstMass;
    private double lastMass;
    private int saturatedSteps;
    private int returnedSteps;
    private double satSeconds;
    private final double[] satDv = new double[3];
    private final double[] satDw = new double[3];

    private PhysicsStepWindow(int dim, int x, int y, int z) {
        this.dim = dim;
        this.x = x;
        this.y = y;
        this.z = z;
    }

    /** Open a window on the flight computer at this address (a subspace one on an assembled ship). */
    public static int open(int dim, int x, int y, int z) {
        return SideTrace.here().open(new PhysicsStepWindow(dim, x, y, z));
    }

    /** Write the window's numbers as they stand, without ending it. */
    public static int peek(int handle) {
        SideTrace.here().window(handle, PhysicsStepWindow.class).write(handle, "open");
        return handle;
    }

    /** End the window and write its record. */
    public static int close(int handle) {
        SideTrace.here().close(handle, PhysicsStepWindow.class).write(handle, "closed");
        return handle;
    }

    /** Feed every open window watching this computer — called by the controller mixin at entry. */
    public static void enter(World world, Object ship, int x, int y, int z, double dt,
                             double vx, double vy, double vz, double wx, double wy, double wz,
                             double mass) {
        List<PhysicsStepWindow> open = SideTrace.of(world).windows(PhysicsStepWindow.class);
        int dim = world.provider.getDimension();
        for (int i = 0; i < open.size(); i++) {
            PhysicsStepWindow w = open.get(i);
            if (w.dim == dim && w.x == x && w.y == y && w.z == z) {
                w.sample(ship, dt, vx, vy, vz, wx, wy, wz, mass);
            }
        }
    }

    /** The collider ran a step for {@code ship} — called by the collider mixin. */
    public static void colliderTick(World world, Object ship) {
        List<PhysicsStepWindow> open = SideTrace.of(world).windows(PhysicsStepWindow.class);
        for (int i = 0; i < open.size(); i++) {
            open.get(i).collider(ship, false);
        }
    }

    /** The collider applied a collision impulse to {@code ship} — called by the collider mixin. */
    public static void contact(World world, Object ship) {
        List<PhysicsStepWindow> open = SideTrace.of(world).windows(PhysicsStepWindow.class);
        for (int i = 0; i < open.size(); i++) {
            open.get(i).collider(ship, true);
        }
    }

    private synchronized void collider(Object of, boolean impulse) {
        if (ship == null || ship != of) {
            return;
        }
        if (impulse) {
            contacts++;
        } else {
            colliderTicks++;
        }
    }

    /** Feed the controller's own verdict on the step it just ran — called at return. */
    public static void returned(World world, int x, int y, int z, boolean saturated) {
        List<PhysicsStepWindow> open = SideTrace.of(world).windows(PhysicsStepWindow.class);
        int dim = world.provider.getDimension();
        for (int i = 0; i < open.size(); i++) {
            PhysicsStepWindow w = open.get(i);
            if (w.dim == dim && w.x == x && w.y == y && w.z == z) {
                w.verdict(saturated);
            }
        }
    }

    private synchronized void sample(Object of, double dt, double vx, double vy, double vz,
                                     double wx, double wy, double wz, double mass) {
        if (steps == 0) {
            ship = of;
            firstV[0] = vx; firstV[1] = vy; firstV[2] = vz;
            firstW[0] = wx; firstW[1] = wy; firstW[2] = wz;
            firstMass = mass;
        } else {
            // The step BEFORE this sample is what carried the craft from the previous velocity to
            // this one, so its dt — and its saturation verdict — belong to this difference.
            seconds += pendingDt;
            if (pendingSaturated) {
                satSeconds += pendingDt;
                satDv[0] += vx - lastV[0]; satDv[1] += vy - lastV[1]; satDv[2] += vz - lastV[2];
                satDw[0] += wx - lastW[0]; satDw[1] += wy - lastW[1]; satDw[2] += wz - lastW[2];
            }
        }
        pendingDt = dt;
        pendingSaturated = false;
        lastV[0] = vx; lastV[1] = vy; lastV[2] = vz;
        lastW[0] = wx; lastW[1] = wy; lastW[2] = wz;
        maxW = Math.max(maxW, Math.sqrt(wx * wx + wy * wy + wz * wz));
        lastMass = mass;
        steps++;
    }

    private synchronized void verdict(boolean saturated) {
        if (steps == 0) {
            return; // the return of a step whose entry this window did not see
        }
        returnedSteps++;
        pendingSaturated = saturated;
        if (saturated) {
            saturatedSteps++;
        }
    }

    private synchronized void write(int handle, String state) {
        StringBuilder p = new StringBuilder();
        p.append("\"handle\":").append(handle)
                .append(",\"state\":\"").append(state).append('"')
                .append(",\"dim\":").append(dim)
                .append(",\"afcX\":").append(x).append(",\"afcY\":").append(y).append(",\"afcZ\":").append(z)
                .append(",\"steps\":").append(steps)
                .append(",\"returnedSteps\":").append(returnedSteps)
                .append(",\"saturatedSteps\":").append(saturatedSteps)
                .append(",\"engineSeconds\":").append(num(seconds))
                .append(",\"satEngineSeconds\":").append(num(satSeconds))
                .append(",\"firstMassKg\":").append(num(firstMass))
                .append(",\"lastMassKg\":").append(num(lastMass))
                .append(",\"maxAngularSpeed\":").append(steps > 0 ? num(maxW) : "null")
                .append(",\"colliderTicks\":").append(colliderTicks)
                .append(",\"contacts\":").append(contacts);
        String[] axes = {"X", "Y", "Z"};
        for (int i = 0; i < 3; i++) {
            p.append(",\"v0").append(axes[i]).append("\":").append(num(firstV[i]))
                    .append(",\"v1").append(axes[i]).append("\":").append(num(lastV[i]))
                    .append(",\"w0").append(axes[i]).append("\":").append(num(firstW[i]))
                    .append(",\"w1").append(axes[i]).append("\":").append(num(lastW[i]));
        }
        // The window's own quotients, in the engine's units (blocks and radians per ENGINE second²),
        // or null when there was no time to divide by — which a reader must be able to tell from a
        // measured zero.
        for (int i = 0; i < 3; i++) {
            p.append(",\"engineAccel").append(axes[i]).append("\":")
                    .append(seconds > 0.0 ? num((lastV[i] - firstV[i]) / seconds) : "null")
                    .append(",\"engineAngAccel").append(axes[i]).append("\":")
                    .append(seconds > 0.0 ? num((lastW[i] - firstW[i]) / seconds) : "null")
                    .append(",\"satEngineAccel").append(axes[i]).append("\":")
                    .append(satSeconds > 0.0 ? num(satDv[i] / satSeconds) : "null")
                    .append(",\"satEngineAngAccel").append(axes[i]).append("\":")
                    .append(satSeconds > 0.0 ? num(satDw[i] / satSeconds) : "null");
        }
        TestTrace.recordServer("physics_step_window", p.toString());
    }

    /**
     * A double as the shortest text that reads back as the same double — full precision, because a
     * reader compares these against a readout to parts in a billion and a fixed number of printed
     * figures would set that resolution instead of the craft. A non-finite value has no JSON number
     * and is written as null, which a reader must be able to tell from a measured zero.
     */
    private static String num(double v) {
        return Double.isNaN(v) || Double.isInfinite(v) ? "null" : Double.toString(v);
    }
}
