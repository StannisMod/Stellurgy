package com.github.stannismod.forge.testing.client;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.io.IOException;

import static com.github.stannismod.forge.testing.client.ClientChannel.command;

/**
 * What a PLAYER does to a client: keys, mouse buttons, the cursor, typing, and where he looks — and
 * nothing else.
 *
 * <p><b>Every verb that reaches a screen or a key binding is built from ONE primitive</b>,
 * {@link #deliver}: input events handed to the client's keyboard and mouse, which the game then reads in
 * its own input loop (the client bridge's {@code VirtualInput}). A screen gets them through its own
 * {@code handleMouseInput} / {@code handleKeyboardInput}, the world through the key bindings with their
 * press edge, Forge's input events and the use key's delay timer. So a click is a cursor, a press AND a
 * release; a key held is a key down until a key up; and what any of it hits is the game's decision, never
 * this class's. There is no verb here that names its target past that loop, and none can be assembled
 * from these: a press without its release, or a use without the use key's edge, is not expressible.</p>
 *
 * <p><b>Where he looks</b> ({@link #setLook}, {@link #turnLook}) is the one thing not sent as an event: an
 * unfocused test window feeds no mouse delta to the camera, so the head is turned through
 * {@code Entity.turn} — the method the game's own mouse handler feeds its deltas into, so every hook on the
 * turn runs and vanilla clamps the pitch — and what the crosshair then picks is vanilla's own trace.
 * Neither reaches a screen.</p>
 *
 * <p>Each verb answers once a client tick has read its events.</p>
 */
public final class PlayerInput {

    /** Mouse buttons enter a key binding as {@code -100 + button}: left -100, right -99, middle -98. */
    private static final int MOUSE_KEY_OFFSET = 100;
    /** A cursor move carries no button. */
    private static final int NO_BUTTON = -1;
    /** LWJGL's return key and the character a real return types. */
    private static final int KEY_RETURN = 28;
    private static final int CHARACTER_RETURN = 13;
    /** How many cursor steps a drag takes between its two points. */
    private static final int DRAG_STEPS = 8;

    private final ClientChannel channel;

    /**
     * The e2e's server, told ONCE — before the first input or look reaches the client — that the player's
     * walk has begun ({@link com.github.stannismod.forge.testing.E2EMode#WALK_BEGINS_COMMAND}), after which
     * it admits no build. {@code null} for a client that serves no e2e.
     */
    private final com.github.stannismod.forge.testing.server.TestClient walkServer;

    /** Whether {@link #walkServer} has been told. Lives with this input, which lives with its client. */
    private boolean walkBegun;

    PlayerInput(ClientChannel channel, com.github.stannismod.forge.testing.server.TestClient walkServer) {
        this.channel = channel;
        this.walkServer = walkServer;
    }

    /**
     * The first thing every verb does: an e2e's server learns, before the player's first act reaches the
     * client, that his walk has begun. A failure to tell it is the verb's failure — the input is not sent.
     */
    private void walkBegins() throws IOException {
        if (walkServer == null || walkBegun) {
            return;
        }
        try {
            walkServer.arrange(com.github.stannismod.forge.testing.E2EMode.WALK_BEGINS_COMMAND);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new IOException("interrupted while telling the server the player's walk began", interrupted);
        }
        walkBegun = true;
    }

    /**
     * A key or a mouse button goes down ({@code pressed}) or up. {@code keyCode} is LWJGL's key code, or a
     * mouse button as a key binding names it ({@code -100 + button}: the use key is -99). A mouse button
     * acts at the cursor where it already is.
     */
    public JsonObject setKey(int keyCode, boolean pressed) throws IOException {
        // A mouse button pressed into the world acts on whatever the crosshair picks, and a turned head picks
        // what vanilla's trace says, not what the turn aimed at: the pick is confirmed before the press.
        if (keyCode < 0 && pressed && channel.lookUnconfirmed()) {
            throw new IOException("a mouse button pressed after a turn of the head whose pick nobody confirmed:"
                    + " ask ClientReads#reportMouseOver what the crosshair picks after the last setLook / turnLook,"
                    + " then press");
        }
        JsonArray events = new JsonArray();
        if (keyCode < 0) {
            events.add(mouse(keyCode + MOUSE_KEY_OFFSET, pressed));
        } else {
            events.add(key(keyCode, pressed, 0));
        }
        return deliver(events);
    }

    public JsonObject holdKey(int keyCode) throws IOException {
        return setKey(keyCode, true);
    }

    public JsonObject releaseKey(int keyCode) throws IOException {
        return setKey(keyCode, false);
    }

    /** A click on the open screen: the cursor to {@code (x, y)} in its own coordinates, a press, a release. */
    public JsonObject click(int x, int y, int button) throws IOException {
        JsonArray events = new JsonArray();
        events.add(at(mouse(NO_BUTTON, false), x, y));
        events.add(at(mouse(button, true), x, y));
        events.add(at(mouse(button, false), x, y));
        return deliver(events);
    }

    /** A drag on the open screen: pressed at the start, carried in steps, released at the end. */
    public JsonObject drag(int startX, int startY, int endX, int endY, int button) throws IOException {
        JsonArray events = new JsonArray();
        events.add(at(mouse(NO_BUTTON, false), startX, startY));
        events.add(at(mouse(button, true), startX, startY));
        for (int step = 1; step <= DRAG_STEPS; step++) {
            int x = startX + (int) Math.round((endX - startX) * (step / (double) DRAG_STEPS));
            int y = startY + (int) Math.round((endY - startY) * (step / (double) DRAG_STEPS));
            events.add(at(mouse(NO_BUTTON, false), x, y));
        }
        events.add(at(mouse(button, false), endX, endY));
        return deliver(events);
    }

    /**
     * A click on the centre of the button whose {@code GuiButton.id} is {@code id}, on the open screen or in
     * one of its modules. The id only FINDS the point — the screen decides what is under it, so a button
     * panned out of its module's view is found and then clicked on nothing. Refused when no such button
     * is visible and enabled. Answers the click's reply with the point, {@code x} and {@code y}.
     */
    public JsonObject clickButtonAt(int id) throws IOException {
        JsonObject found = null;
        for (JsonElement element : channel.call(command("report_buttons")).getAsJsonArray("buttons")) {
            JsonObject button = element.getAsJsonObject();
            if (button.get("id").getAsInt() == id) {
                found = button;
                break;
            }
        }
        if (found == null) {
            throw new IOException("No GUI button with id " + id + " on the open screen");
        }
        if (!found.get("visible").getAsBoolean() || !found.get("enabled").getAsBoolean()) {
            throw new IOException("GUI button id " + id + " is not clickable: " + found);
        }
        int x = found.get("x").getAsInt() + found.get("width").getAsInt() / 2;
        int y = found.get("y").getAsInt() + found.get("height").getAsInt() / 2;
        return clickAndSay(x, y, 0);
    }

    /**
     * A click on the centre of container slot {@code slot} of the open container screen — the index only
     * finds the point; {@code GuiContainer} decides which slot is hit, and puts a held stack down on the
     * RELEASE. Answers the click's reply with the point, {@code x} and {@code y}.
     */
    public JsonObject clickSlotAt(int slot, int button) throws IOException {
        JsonObject state = channel.call(command("report_state"));
        JsonObject found = null;
        for (JsonElement element : channel.call(command("report_slots")).getAsJsonArray("slots")) {
            JsonObject candidate = element.getAsJsonObject();
            if (candidate.get("slot").getAsInt() == slot) {
                found = candidate;
                break;
            }
        }
        if (found == null) {
            throw new IOException("No slot " + slot + " in the open container (screen "
                    + state.get("screen").getAsString() + ")");
        }
        // A slot is drawn 16x16 at (guiLeft + xPos, guiTop + yPos); its centre is what a player aims at.
        int x = state.get("guiLeft").getAsInt() + found.get("x").getAsInt() + 8;
        int y = state.get("guiTop").getAsInt() + found.get("y").getAsInt() + 8;
        return clickAndSay(x, y, button);
    }

    /** A key pressed and released on whatever has the keyboard — escape closes a screen its own way. */
    public JsonObject pressScreenKey(int keyCode) throws IOException {
        JsonArray events = new JsonArray();
        events.add(key(keyCode, true, 0));
        events.add(key(keyCode, false, 0));
        return deliver(events);
    }

    /** {@code text} typed, character by character, into whatever has the keyboard. */
    public JsonObject typeText(String text) throws IOException {
        JsonArray events = new JsonArray();
        for (int i = 0; i < text.length(); i++) {
            events.add(key(0, true, text.charAt(i)));
            events.add(key(0, false, 0));
        }
        return deliver(events);
    }

    /** {@link #typeText} and then the return key. */
    public JsonObject pressEnterAfterTyping(String text) throws IOException {
        typeText(text);
        JsonArray events = new JsonArray();
        events.add(key(KEY_RETURN, true, CHARACTER_RETURN));
        events.add(key(KEY_RETURN, false, 0));
        return deliver(events);
    }

    /**
     * The head turned to an absolute yaw and pitch (degrees; negative pitch is up), by the delta from where
     * it is, through {@code Entity.turn}. A pitch past straight up or down stops there; NaN or an infinity is
     * refused ({@code refusedKind} {@code ARGUMENTS}). The reply's {@code yaw} / {@code pitch} are where the
     * head ended up. What the crosshair then picks is vanilla's trace — confirm it with
     * {@link ClientReads#reportMouseOver()} before a press: a mouse button pressed after a look nobody
     * confirmed is refused ({@link #setKey}).
     */
    public JsonObject setLook(float yaw, float pitch) throws IOException {
        walkBegins();
        JsonObject command = command("set_look");
        command.addProperty("yaw", yaw);
        command.addProperty("pitch", pitch);
        return channel.call(command);
    }

    /** The head turned by a raw mouse delta, through {@code Entity.turn} — see {@link ClientBot#turnLook}. */
    public JsonObject turnLook(float deltaYaw, float deltaPitch) throws IOException {
        walkBegins();
        JsonObject command = command("turn_look");
        command.addProperty("deltaYaw", deltaYaw);
        command.addProperty("deltaPitch", deltaPitch);
        return channel.call(command);
    }

    private JsonObject clickAndSay(int x, int y, int button) throws IOException {
        JsonObject reply = click(x, y, button);
        reply.addProperty("x", x);
        reply.addProperty("y", y);
        return reply;
    }

    /** THE primitive: input events, in order, read by the game's own input loop. */
    private JsonObject deliver(JsonArray events) throws IOException {
        walkBegins();
        JsonObject command = command("input");
        command.add("events", events);
        return channel.call(command);
    }

    private static JsonObject mouse(int button, boolean down) {
        JsonObject event = new JsonObject();
        event.addProperty("device", "mouse");
        event.addProperty("button", button);
        event.addProperty("down", down);
        return event;
    }

    private static JsonObject at(JsonObject event, int x, int y) {
        event.addProperty("x", x);
        event.addProperty("y", y);
        return event;
    }

    private static JsonObject key(int keyCode, boolean down, int character) {
        JsonObject event = new JsonObject();
        event.addProperty("device", "key");
        event.addProperty("key", keyCode);
        event.addProperty("down", down);
        event.addProperty("character", character);
        return event;
    }
}
