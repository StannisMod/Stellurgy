package com.github.stannismod.forge.testing.server;

import com.google.gson.JsonObject;

/**
 * The server bridge could not run a command, or the command threw: its reply was {@code ok:false}.
 *
 * <p>An {@link AssertionError} because that is what {@link TestClient#execute} has always thrown for it —
 * a caller that let it propagate keeps failing the test exactly as before. It carries the bridge's reply
 * as DATA so a caller that must tell one failure from another (a refusal of the e2e mode, say) reads the
 * {@code thrown} class and {@code thrownMessage} fields by name, never out of the message text.</p>
 */
public final class ServerCommandFailed extends AssertionError {

    private static final long serialVersionUID = 1L;

    private final String command;
    private final String reply;

    ServerCommandFailed(String command, JsonObject reply) {
        super("Server command failed over the bridge: " + command + " -> "
                + (reply.has("error") ? reply.get("error").getAsString() : reply.toString()));
        this.command = command;
        this.reply = reply.toString();
    }

    /** The command that was sent, as sent. */
    public String command() {
        return command;
    }

    /** The bridge's whole reply, a fresh copy on every call. */
    public JsonObject reply() {
        return new com.google.gson.JsonParser().parse(reply).getAsJsonObject();
    }
}
