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

    /**
     * What an instrument does; the kind is the claim a reviewer checks against the body. An e2e admits
     * RECORDER and READ only ({@link #rows}); a mechanics client admits every kind ({@link #mechanicsRows}).
     */
    public enum Kind {
        /** Opens, peeks or closes a recording window the test owns; writes only the window's own state. */
        RECORDER,
        /** Answers a value; writes nothing. */
        READ,
        /** Arms a one-shot arrangement a declared test mixin carries out (a shove, a packet hand-over). */
        ARMING,
        /** Feeds RAW input to the mod's own entry point the input handler calls — never the decision's outcome. */
        FEED_INPUT,
        /** Sends the mod's own packet from a forged client-side origin, to exercise the server's handling. */
        FORGED_PACKET
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

    /**
     * Every row a MECHANICS client admits ({@code invoke_static_int}), as {@code {class, method, kind}}: the
     * e2e's rows and the mechanics tier's own — taken from what a counting run of the whole client tier
     * asked (2026-10-12), each body read when its row was added. A new list on every call.
     */
    public static List<String[]> mechanicsRows() {
        List<String[]> rows = rows();
        String trace = "dev.stannismod.stellurgy.test.trace.";
        String client = "dev.stannismod.stellurgy.test.client.";
        for (String window : new String[]{"DeckCameraState", "DeckGateWindow", "FlightCameraState", "RemoteModelWindow",
                "RenderFrameWindow"}) {
            rows.add(new String[]{trace + window, "open", Kind.RECORDER.name()});
            rows.add(new String[]{trace + window, "peek", Kind.RECORDER.name()});
            rows.add(new String[]{trace + window, "close", Kind.RECORDER.name()});
        }
        for (String window : new String[]{"DeckPoseTraceWindow", "FrameStepWindow"}) {
            rows.add(new String[]{trace + window, "open", Kind.RECORDER.name()});
            rows.add(new String[]{trace + window, "close", Kind.RECORDER.name()});
        }
        rows.add(new String[]{trace + "SideTrace", "discardClientWindows", Kind.RECORDER.name()});
        rows.add(new String[]{trace + "RespawnPacketArming", "open", Kind.ARMING.name()});
        rows.add(new String[]{trace + "RespawnPacketArming", "close", Kind.ARMING.name()});
        rows.add(new String[]{trace + "ShoveArming", "open", Kind.ARMING.name()});
        rows.add(new String[]{trace + "ShoveArming", "close", Kind.ARMING.name()});
        // Holds the client's ship load decisions for the behaviour mixin that skips the pass while a window is
        // open: the client that fell behind, which the lagging-reload scenario arranges.
        rows.add(new String[]{trace + "ShipLoadDecisionHold", "open", Kind.ARMING.name()});
        rows.add(new String[]{trace + "ShipLoadDecisionHold", "release", Kind.ARMING.name()});
        rows.add(new String[]{trace + "ShipLoadDecisionHold", "close", Kind.ARMING.name()});
        // Renders the client half of the flight recorder and records it in the client's own log at the moment
        // the test asks: the smoothness scenario's client read.
        rows.add(new String[]{trace + "MotionTraceClientSummary", "record", Kind.RECORDER.name()});
        rows.add(new String[]{client + "ClientDimensions", "forgeRegisteredOnClient", Kind.READ.name()});
        rows.add(new String[]{client + "ClientDimensions", "stellurgyRegistryOnClient", Kind.READ.name()});
        rows.add(new String[]{client + "ClientSpaceClock", "nowOnClient", Kind.READ.name()});
        rows.add(new String[]{client + "ClientSpaceClock", "hasSyncOnClient", Kind.READ.name()});
        rows.add(new String[]{client + "ItemModelReader", "airDrawsMissingOnClient", Kind.READ.name()});
        rows.add(new String[]{client + "ItemModelReader", "repairWelderDrawsMissingOnClient", Kind.READ.name()});
        rows.add(new String[]{client + "ForgedMachinePress", "pressOnClient", Kind.FORGED_PACKET.name()});
        // The one production member: the method the mouse handler calls with a raw delta while the pilot is
        // seated. It is fed the raw input; what it decides is the subject of the tests that call it.
        rows.add(new String[]{"dev.stannismod.stellurgy.client.KeyBindings", "acceptShipPilotMouseDelta",
                Kind.FEED_INPUT.name()});
        return rows;
    }

    /** The kind of {@code className#method} among the mechanics rows, or {@code null} when it is none. */
    static Kind mechanicsKindOf(String className, String method) {
        for (String[] row : mechanicsRows()) {
            if (row[0].equals(className) && row[1].equals(method)) {
                return Kind.valueOf(row[2]);
            }
        }
        return null;
    }

    /** What the mechanics table holds, for a refusal to name. */
    static String describeMechanics() {
        StringBuilder out = new StringBuilder("[");
        for (String[] row : mechanicsRows()) {
            if (out.length() > 1) {
                out.append(", ");
            }
            out.append(row[0]).append('#').append(row[1]);
        }
        return out.append(']').toString();
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
