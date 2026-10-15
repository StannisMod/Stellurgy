package dev.stannismod.stellurgy.command.test;

/**
 * The server-side statics a MECHANICS test may call through {@code /stellurgytest invoke-static}, BY NAME —
 * the mechanics tier's table beside {@link E2EInstrument}, and the only place one is declared. Every other
 * static call is refused at the call by {@link E2EModeGate} on a server launched for a mechanics test.
 *
 * <p>Taken from what the whole server tier asked of a counting server (2026-10-12, every distinct
 * {@code invoke-static} target a full run sent), each row's body read when it was added. A row is matched on
 * the EXACT class and method strings the call carries, before anything loads. The members live in the test
 * source set; what their bodies may write is checked in the build (the test bindings check walks every row
 * and refuses a write to a field outside the test source set).</p>
 */
enum MechanicsInstrument {

    SERVER_EVENT_LOG_MARK("dev.stannismod.stellurgy.test.trace.ServerEventLog", "markReply", Kind.READ),
    SERVER_EVENT_LOG_SINCE("dev.stannismod.stellurgy.test.trace.ServerEventLog", "sinceReply", Kind.READ),
    SPAWN_MEMORY_SNAPSHOT("dev.stannismod.stellurgy.test.trace.SpawnMemory", "snapshot", Kind.READ),
    SPAWN_MEMORY_RESET("dev.stannismod.stellurgy.test.trace.SpawnMemory", "reset", Kind.RECORDER),
    MOTION_TRACE_READING("dev.stannismod.stellurgy.test.trace.MotionTrace", "serverReading", Kind.READ),
    SEAT_DELIVERY_WINDOW_OPEN("dev.stannismod.stellurgy.test.trace.SeatDeliveryWindow", "open", Kind.RECORDER),
    SEAT_DELIVERY_WINDOW_PEEK("dev.stannismod.stellurgy.test.trace.SeatDeliveryWindow", "peek", Kind.RECORDER),
    SEAT_DELIVERY_WINDOW_CLOSE("dev.stannismod.stellurgy.test.trace.SeatDeliveryWindow", "close", Kind.RECORDER),
    PHYSICS_STEP_WINDOW_OPEN("dev.stannismod.stellurgy.test.trace.PhysicsStepWindow", "open", Kind.RECORDER),
    PHYSICS_STEP_WINDOW_CLOSE("dev.stannismod.stellurgy.test.trace.PhysicsStepWindow", "close", Kind.RECORDER),
    SIDE_TRACE_DISCARD_WINDOWS("dev.stannismod.stellurgy.test.trace.SideTrace", "discardServerWindows", Kind.RECORDER),
    // A window the behaviour mixin MixinArrivalSpawnMountRefusal reads to refuse that many seat mounts; open and
    // close write only the window's own count.
    MOUNT_REFUSAL_ARMING_OPEN("dev.stannismod.stellurgy.test.trace.MountRefusalArming", "open", Kind.RECORDER),
    MOUNT_REFUSAL_ARMING_CLOSE("dev.stannismod.stellurgy.test.trace.MountRefusalArming", "close", Kind.RECORDER);

    /** What an instrument does; the kind is the claim a reviewer checks against the body. */
    enum Kind {
        /** Answers a value of the test's own recorders; writes nothing. */
        READ,
        /** Opens, peeks, closes or clears a recording the test owns; writes only that recording's state. */
        RECORDER
    }

    /**
     * The instrument's class, as the call names it. Effectively final — a constant: written once by the
     * constructor at class initialisation, for the JVM's life, and a String is immutable.
     */
    private final String className;
    /** The instrument's method. Effectively final — a constant, written as {@link #className} is. */
    private final String methodName;
    /** What it does. Effectively final — a constant, written as {@link #className} is; an enum value. */
    private final Kind kind;

    MechanicsInstrument(String className, String methodName, Kind kind) {
        this.className = className;
        this.methodName = methodName;
        this.kind = kind;
    }

    /** The instrument {@code className#methodName} names, or {@code null} when it is none. */
    static MechanicsInstrument named(String className, String methodName) {
        for (MechanicsInstrument instrument : values()) {
            if (instrument.className.equals(className) && instrument.methodName.equals(methodName)) {
                return instrument;
            }
        }
        return null;
    }

    /** Every row as {@code class#method}, for a refusal to name. */
    static String listing() {
        StringBuilder out = new StringBuilder("[");
        for (MechanicsInstrument instrument : values()) {
            if (out.length() > 1) {
                out.append(", ");
            }
            out.append(instrument.className).append('#').append(instrument.methodName);
        }
        return out.append(']').toString();
    }
}
