package com.github.stannismod.forge.testing.client;

import com.google.gson.JsonObject;

import java.io.IOException;

import static com.github.stannismod.forge.testing.client.ClientChannel.command;

/**
 * The {@link ClientReads} of one client, over its channel. Not public: a test receives it as the
 * interface, so it cannot be cast into anything that acts.
 */
final class ReadVerbs implements ClientReads {

    private final ClientChannel channel;

    ReadVerbs(ClientChannel channel) {
        this.channel = channel;
    }

    @Override
    public void waitTicks(int ticks) throws IOException {
        JsonObject command = command("wait_ticks");
        command.addProperty("ticks", ticks);
        channel.call(command);
    }

    @Override
    public void waitWorldTicks(int ticks) throws IOException {
        JsonObject command = command("wait_world_ticks");
        command.addProperty("ticks", ticks);
        channel.call(command);
    }

    @Override
    public JsonObject reportButtons() throws IOException {
        return channel.call(command("report_buttons"));
    }

    @Override
    public JsonObject reportSlots() throws IOException {
        return channel.call(command("report_slots"));
    }

    @Override
    public JsonObject reportState() throws IOException {
        return channel.call(command("report_state"));
    }

    @Override
    public JsonObject reportRidingEntity() throws IOException {
        return channel.call(command("report_riding_entity"));
    }

    /**
     * Not a {@link ClientReads} member: the bridge initialises the class it names by reflection, so its
     * static initialiser runs whatever it does, and a child serving an e2e refuses the command. Only
     * {@link ClientBot} offers it.
     */
    JsonObject readStaticField(String className, String fieldName) throws IOException {
        JsonObject command = command("read_static_field");
        command.addProperty("className", className);
        command.addProperty("fieldName", fieldName);
        return channel.call(command);
    }

    @Override
    public JsonObject tileNbt(int x, int y, int z) throws IOException {
        JsonObject command = command("tile_nbt");
        command.addProperty("x", x);
        command.addProperty("y", y);
        command.addProperty("z", z);
        return channel.call(command);
    }

    @Override
    public JsonObject invokeStaticInt(String className, String methodName, int... args) throws IOException {
        JsonObject command = command("invoke_static_int");
        command.addProperty("className", className);
        command.addProperty("methodName", methodName);
        com.google.gson.JsonArray intArgs = new com.google.gson.JsonArray();
        for (int arg : args) {
            intArgs.add(arg);
        }
        command.add("intArgs", intArgs);
        return channel.call(command);
    }

    @Override
    public JsonObject screenshot(String name) throws IOException {
        JsonObject command = command("screenshot");
        command.addProperty("name", name);
        return channel.call(command);
    }

    @Override
    public JsonObject reportChat(int limit) throws IOException {
        JsonObject command = command("report_chat");
        command.addProperty("limit", limit);
        return channel.call(command);
    }

    @Override
    public JsonObject reportPlayerItems() throws IOException {
        return channel.call(command("report_player_items"));
    }

    @Override
    public JsonObject reportEntities(String classContains, double radius) throws IOException {
        JsonObject command = command("report_entities");
        command.addProperty("classContains", classContains);
        command.addProperty("radius", radius);
        return channel.call(command);
    }

    @Override
    public JsonObject reportMouseOver() throws IOException {
        return channel.call(command("report_mouse_over"));
    }

    @Override
    public JsonObject reportMods() throws IOException {
        return channel.call(command("report_mods"));
    }

    @Override
    public JsonObject reportWeather() throws IOException {
        return channel.call(command("report_weather"));
    }

    @Override
    public JsonObject reportSpawn() throws IOException {
        return channel.call(command("report_spawn"));
    }

    @Override
    public JsonObject reportSounds() throws IOException {
        return channel.call(command("report_sounds"));
    }

    @Override
    public JsonObject eventMark() throws IOException {
        return channel.call(command("event_mark"));
    }

    @Override
    public JsonObject eventsSince(long seq, String type) throws IOException {
        JsonObject command = command("event_since");
        command.addProperty("seq", seq);
        if (type != null) {
            command.addProperty("type", type);
        }
        return channel.call(command);
    }

    @Override
    public JsonObject blockState(int x, int y, int z) throws IOException {
        JsonObject command = command("block_state");
        command.addProperty("x", x);
        command.addProperty("y", y);
        command.addProperty("z", z);
        return channel.call(command);
    }

    @Override
    public void clearSounds() throws IOException {
        channel.call(command("clear_sounds"));
    }

    @Override
    public JsonObject clearChat() throws IOException {
        return channel.call(command("clear_chat"));
    }

    @Override
    public boolean isAlive(int timeoutMillis) {
        return channel.isAlive(timeoutMillis);
    }
}
