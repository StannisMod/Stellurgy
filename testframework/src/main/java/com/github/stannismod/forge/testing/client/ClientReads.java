package com.github.stannismod.forge.testing.client;

import com.google.gson.JsonObject;

import java.io.IOException;

/**
 * What a test may READ of a real client, and the client's clock — and nothing it could ACT with.
 *
 * <p>The e2e base hands a test this and a {@link PlayerInput}, and no {@link ClientBot}: a verb that names
 * its target or writes the state a player's input would have produced is not on either type, so an e2e
 * that names one does not compile. A mechanics test holds the whole {@link ClientBot}, which is also one
 * of these, so a reader written against this type serves both.</p>
 *
 * <p>Two members are not pure reads, and are here because a read needs them: the client's own buffers are
 * emptied ({@link #clearChat}, {@link #clearSounds}) so a later read is about what came after, and
 * {@link #invokeStaticInt} calls a static of the test's own instruments — a recorder or a trace window.
 * In e2e mode the client refuses that call on any (class, method) its instrument table does not name.</p>
 *
 * <p>Each method's reply is described where {@link ClientBot} implements it.</p>
 */
public interface ClientReads {

    void waitTicks(int ticks) throws IOException;

    /** Only the ticks on which the player can act — see {@link ClientBot#waitWorldTicks(int)}. */
    void waitWorldTicks(int ticks) throws IOException;

    JsonObject reportButtons() throws IOException;

    JsonObject reportSlots() throws IOException;

    JsonObject reportState() throws IOException;

    JsonObject reportRidingEntity() throws IOException;

    JsonObject tileNbt(int x, int y, int z) throws IOException;

    JsonObject invokeStaticInt(String className, String methodName, int... args) throws IOException;

    JsonObject screenshot(String name) throws IOException;

    JsonObject reportChat(int limit) throws IOException;

    JsonObject reportPlayerItems() throws IOException;

    JsonObject reportEntities(String classContains, double radius) throws IOException;

    JsonObject reportMouseOver() throws IOException;

    JsonObject reportMods() throws IOException;

    JsonObject reportWeather() throws IOException;

    JsonObject reportSpawn() throws IOException;

    JsonObject reportSounds() throws IOException;

    JsonObject eventMark() throws IOException;

    JsonObject eventsSince(long seq, String type) throws IOException;

    JsonObject blockState(int x, int y, int z) throws IOException;

    void clearSounds() throws IOException;

    JsonObject clearChat() throws IOException;

    boolean isAlive(int timeoutMillis);
}
