package dev.stannismod.stellurgy.command.test;

/**
 * The server-side statics an e2e may call through {@code /stellurgytest invoke-static}, BY NAME — the
 * table beside {@link ProbeVerbKind}, and the only place an instrument is declared. Every other static
 * call is refused at the call by {@link E2EModeGate}.
 *
 * <p><b>Why names and not a package.</b> A package admits every static anybody ever writes there — a
 * helper that calls the mod, one that clears a property the run's mode is read from — and each of those
 * is a decision the player never made. A row names one member whose body was read when the row was
 * added; a new static is unusable in an e2e until somebody has said what it is, here.</p>
 *
 * <p>The members live in the test source set, so this table holds their names as strings, and a row is
 * matched on the EXACT class and method strings the call carries — before anything is loaded, so judging
 * a refused class never initialises it. Overloads of a listed method share its row. The client's table is
 * in the harness's client bridge ({@code ClientInstruments}), in code. What each row's body may reach is
 * checked in the build: the test bindings check walks it and refuses a write outside the test source set
 * or a call into the game beyond the reads it names.</p>
 */
enum E2EInstrument {

    SERVER_EVENT_LOG_MARK("dev.stannismod.stellurgy.test.trace.ServerEventLog", "markReply", Kind.READ),
    SERVER_EVENT_LOG_SINCE("dev.stannismod.stellurgy.test.trace.ServerEventLog", "sinceReply", Kind.READ),
    SPAWN_MEMORY_SNAPSHOT("dev.stannismod.stellurgy.test.trace.SpawnMemory", "snapshot", Kind.READ),
    SEAT_DELIVERY_WINDOW_OPEN("dev.stannismod.stellurgy.test.trace.SeatDeliveryWindow", "open", Kind.RECORDER),
    SEAT_DELIVERY_WINDOW_PEEK("dev.stannismod.stellurgy.test.trace.SeatDeliveryWindow", "peek", Kind.RECORDER),
    SEAT_DELIVERY_WINDOW_CLOSE("dev.stannismod.stellurgy.test.trace.SeatDeliveryWindow", "close", Kind.RECORDER);

    /** What an instrument does. Both are admitted; the kind is the claim a reviewer checks against the body. */
    enum Kind {
        /** Answers a value of the test's own recorders; writes nothing. */
        READ,
        /** Opens, peeks or closes a recording window the test owns; writes only the window's own state. */
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

    E2EInstrument(String className, String methodName, Kind kind) {
        this.className = className;
        this.methodName = methodName;
        this.kind = kind;
    }

    /** The instrument {@code className#methodName} names, or {@code null} when it is none. */
    static E2EInstrument named(String className, String methodName) {
        for (E2EInstrument instrument : values()) {
            if (instrument.className.equals(className) && instrument.methodName.equals(methodName)) {
                return instrument;
            }
        }
        return null;
    }

    /** Every row as {@code class#method}, for a refusal to name. */
    static String listing() {
        StringBuilder out = new StringBuilder("[");
        for (E2EInstrument instrument : values()) {
            if (out.length() > 1) {
                out.append(", ");
            }
            out.append(instrument.className).append('#').append(instrument.methodName);
        }
        return out.append(']').toString();
    }
}
