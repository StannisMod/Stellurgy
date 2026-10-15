package com.github.stannismod.forge.testing.client;

import com.google.gson.JsonObject;

import java.io.IOException;

/**
 * The client REFUSED a command because it serves an e2e and the command's kind is not admitted there.
 *
 * <p>An {@link IOException}, as every other failed client command is, so a caller that lets it propagate
 * fails exactly as before. It carries the client's reply as DATA — {@code refusedCommand},
 * {@code refusedKind} — so a caller that must know it was this refusal reads those fields by name.</p>
 */
public final class ClientCommandRefused extends IOException {

    private static final long serialVersionUID = 1L;

    private final String reply;

    ClientCommandRefused(String message, JsonObject reply) {
        super(message);
        this.reply = reply.toString();
    }

    /** The client's whole reply, a fresh copy on every call. */
    public JsonObject reply() {
        return new com.google.gson.JsonParser().parse(reply).getAsJsonObject();
    }
}
