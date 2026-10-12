package com.github.stannismod.forge.testing.client.bridge;

import java.util.ArrayList;
import java.util.List;

/**
 * The client-side statics an e2e may call ({@code invoke_static_int}), BY NAME: one row per (class,
 * method), each with its kind. Anything else is refused at the call.
 *
 * <p><b>The table is code.</b> It was a text file on the test classpath until 2026-10-09, and a file under
 * the build directory is one the test JVM can rewrite before the client boots; a row compiled into the
 * bridge cannot be. The rows name the consuming project's instruments — this harness is vendored into
 * that project and edited in place, so the table lives where the bridge reads it, not in a resource the
 * bridge discovers.</p>
 *
 * <p><b>Why names and not a package.</b> A package admits every static anybody ever writes there — a helper
 * that calls the mod, one that clears a property — and each is a decision the player never made. A row
 * names one member whose body was read when the row was added; a new static is unusable in an e2e until
 * somebody has said what it is, here. What the body may reach is also checked in the build: the test
 * bindings check walks every row's method and refuses a write outside the test source set or a call into
 * the game beyond the reads it names.</p>
 */
public final class ClientInstruments {

    /** What an instrument does. Both are admitted; the kind is the claim a reviewer checks against the body. */
    public enum Kind {
        /** Opens, peeks or closes a recording window the test owns; writes only the window's own state. */
        RECORDER,
        /** Answers a value; writes nothing. */
        READ
    }

    private ClientInstruments() {
    }

    /**
     * Every row, as {@code {class, method, kind}} — a new list on every call, so nothing holds the table but
     * this method's body.
     */
    public static List<String[]> rows() {
        List<String[]> rows = new ArrayList<>();
        // The seat-gate window SeatDelivery opens on the client.
        rows.add(new String[]{"dev.stannismod.stellurgy.test.trace.SeatGateWindow", "open", Kind.RECORDER.name()});
        rows.add(new String[]{"dev.stannismod.stellurgy.test.trace.SeatGateWindow", "peek", Kind.RECORDER.name()});
        rows.add(new String[]{"dev.stannismod.stellurgy.test.trace.SeatGateWindow", "close", Kind.RECORDER.name()});
        return rows;
    }

    /** The kind of {@code className#method}, or {@code null} when it is no instrument. */
    static Kind kindOf(String className, String method) {
        for (String[] row : rows()) {
            if (row[0].equals(className) && row[1].equals(method)) {
                return Kind.valueOf(row[2]);
            }
        }
        return null;
    }

    /** What the table holds, for a refusal to name. */
    static String describe() {
        StringBuilder out = new StringBuilder("[");
        for (String[] row : rows()) {
            if (out.length() > 1) {
                out.append(", ");
            }
            out.append(row[0]).append('#').append(row[1]);
        }
        return out.append(']').toString();
    }
}
