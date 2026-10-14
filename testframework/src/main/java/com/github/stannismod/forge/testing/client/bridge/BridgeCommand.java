package com.github.stannismod.forge.testing.client.bridge;

/**
 * Every command the client bridge answers, and WHAT each one is — the single source of that answer.
 *
 * <p>A command the bridge's dispatch handles but this enum does not name is refused as unknown, and a
 * constant here with no handler answers "no handler", so the two cannot drift apart silently. The KIND is
 * what the e2e mode decides on ({@link com.github.stannismod.forge.testing.E2EMode}): a child serving an
 * e2e refuses, at the call, every command whose kind is not admitted. Until 2026-10-08 this
 * classification was a javadoc table on {@code ClientBot}, read by a text scan of the tests; a table
 * nothing executes can be edited without anything noticing.</p>
 *
 * <p>Adding a command means choosing its kind here, with the reason, in the same change. "It is only a
 * read" is the claim a reviewer checks: a READS command that writes state a player could not, or a
 * PIPELINE command that reaches a screen or a binding past the input queue, is a misclassification —
 * and the second kind of it is what an e2e would then be able to do in a player's name.</p>
 */
public enum BridgeCommand {

    INPUT("input", Kind.PIPELINE,
            "input events queued to LWJGL's own mouse and keyboard, read by the game's own input loop"),
    SET_LOOK("set_look", Kind.LOOK, "the head turned to a direction through Entity.turn; what it then picks is vanilla's trace"),
    TURN_LOOK("turn_look", Kind.LOOK, "Entity.turn, the method the mouse handler feeds"),

    WAIT_WORLD("wait_world", Kind.HARNESS, "waits for the client world"),
    WAIT_TICKS("wait_ticks", Kind.HARNESS, "waits for client ticks"),
    WAIT_WORLD_TICKS("wait_world_ticks", Kind.HARNESS, "waits for client ticks with a live world"),
    CLEAR_CHAT("clear_chat", Kind.HARNESS, "the harness's observation channel emptied"),
    CLEAR_SOUNDS("clear_sounds", Kind.HARNESS, "the harness's sound buffer emptied"),
    RECONNECT("reconnect", Kind.HARNESS, "the client's lifecycle: quit and rejoin"),
    DISCONNECT("disconnect", Kind.HARNESS, "the client's lifecycle: quit"),
    CONNECT("connect", Kind.HARNESS, "the client's lifecycle: rejoin"),
    SET_FRAMEBUFFER("set_framebuffer", Kind.HARNESS, "the render target"),
    SET_RENDER_DISTANCE("set_render_distance", Kind.HARNESS, "a menu option"),
    SET_FRAME_RATE("set_frame_rate", Kind.HARNESS, "a menu option"),
    SET_HUD_HIDDEN("set_hud_hidden", Kind.HARNESS, "a menu option (F1)"),
    SHUTDOWN("shutdown", Kind.HARNESS, "the client's lifecycle: exit"),

    REPORT_BUTTONS("report_buttons", Kind.READS, "the open screen's buttons"),
    REPORT_SLOTS("report_slots", Kind.READS, "the open container's slots"),
    REPORT_STATE("report_state", Kind.READS, "the client's own view of itself"),
    REPORT_RIDING_ENTITY("report_riding_entity", Kind.READS, "the entity the player rides"),
    READ_STATIC_FIELD("read_static_field", Kind.STATIC_READ,
            "a class named by a string, initialised and read by reflection: its static initialiser runs,"
                    + " whatever it does"),
    REPORT_CHAT("report_chat", Kind.READS, "the chat overlay"),
    REPORT_PLAYER_ITEMS("report_player_items", Kind.READS, "the player's stacks"),
    REPORT_ENTITIES("report_entities", Kind.READS, "entities near the player"),
    REPORT_MOUSE_OVER("report_mouse_over", Kind.READS, "what the crosshair is on"),
    REPORT_MODS("report_mods", Kind.READS, "the mod list"),
    REPORT_WEATHER("report_weather", Kind.READS, "the client's weather"),
    REPORT_SPAWN("report_spawn", Kind.READS, "the client's spawn point"),
    REPORT_SOUNDS("report_sounds", Kind.READS, "the sounds asked for"),
    EVENT_MARK("event_mark", Kind.READS, "the client event log's sequence"),
    EVENT_SINCE("event_since", Kind.READS, "the client event log since a mark"),
    BLOCK_STATE("block_state", Kind.READS, "a block in the client world"),
    TILE_NBT("tile_nbt", Kind.READS, "a client tile's update tag"),
    SCREENSHOT("screenshot", Kind.READS, "the drawn frame"),

    INVOKE_STATIC_INT("invoke_static_int", Kind.STATIC_TARGET,
            "a static method of the class it is handed; in e2e mode only a named instrument (ClientInstruments)"),
    INVOKE_STATIC_CHAIN("invoke_static_chain", Kind.STATIC_CHAIN,
            "a static method and then any public method of each result, which no package rule bounds"),

    SET_KEY("set_key", Kind.BYPASS, "a key binding's state written, past the input queue"),
    SELECT_HOTBAR("select_hotbar", Kind.BYPASS, "the held slot written; a player presses the number key"),
    RIGHT_CLICK_BLOCK("right_click_block", Kind.BYPASS, "processRightClickBlock on a named block, past the pick"),
    INTERACT_BLOCK("interact_block", Kind.BYPASS, "processRightClickBlock on a named block, past the pick"),
    USE_ITEM("use_item", Kind.BYPASS, "processRightClick with the held item, past the pick"),
    USE_MOUSE_OVER("use_mouse_over", Kind.BYPASS,
            "rightClickMouse called directly: past the use key's edge and its delay timer"),
    SEND_CHAT("send_chat", Kind.BYPASS, "a chat packet sent past the chat screen"),
    CLICK_SCREEN_POINT("click_screen_point", Kind.BYPASS,
            "the screen's mouseClicked called directly, a press without its release"),
    CLICK_BUTTON("click_button", Kind.BYPASS, "the screen's mouseClicked called directly, a press only"),
    CLICK_BUTTON_RATIO("click_button_ratio", Kind.BYPASS, "the screen's mouseClicked called directly, a press only"),
    CLICK_BUTTON_ID("click_button_id", Kind.BYPASS, "actionPerformed called on a button named by its id"),
    CLICK_BUTTON_AT("click_button_at", Kind.BYPASS,
            "the screen's press and release called directly, past handleMouseInput"),
    CLICK_SLOT_AT("click_slot_at", Kind.BYPASS, "the screen's press and release called directly, past handleMouseInput"),
    CLICK_SLOT("click_slot", Kind.BYPASS, "handleMouseClick on a slot named by its index"),
    DRAG_SCREEN_POINT("drag_screen_point", Kind.BYPASS, "the screen's press, drag and release called directly"),
    PRESS_SCREEN_KEY("press_screen_key", Kind.BYPASS, "the screen's keyTyped called directly"),
    TYPE_TEXT("type_text", Kind.BYPASS, "the screen's keyTyped called directly"),
    FOCUS_FIELD("focus_field", Kind.BYPASS, "a text field focused by a direct write"),
    CLOSE_SCREEN("close_screen", Kind.BYPASS, "the screen closed directly; a player presses escape"),
    RESET_CLIENT_STATE("reset_client_state", Kind.BYPASS, "the screen closed and every key released directly"),
    TILE_MODULES_THROWS("tile_modules_throws", Kind.BYPASS, "a tile's GUI modules built by calling the mod");

    /** What a command is, for a test that must act as a player would. */
    public enum Kind {
        /** The act travels the path a real keyboard, mouse or screen input travels. */
        PIPELINE(true),
        /** The player's head turned. A bot has no mouse delta an unfocused window would turn by. */
        LOOK(true),
        /** Observes, changes nothing a player could. */
        READS(true),
        /** The client's lifecycle, the harness's own buffers, or an option a player sets in the menus. */
        HARNESS(true),
        /** A static call; in e2e mode admitted only on a (class, method) the client's instrument table names. */
        STATIC_TARGET(true),
        /** A static call followed by calls on what it returned: no package rule bounds it. */
        STATIC_CHAIN(false),
        /**
         * A static field read by reflection off a class named by a string. Not a READ: resolving the name
         * initialises the class, and a static initialiser may do anything. No e2e reads one.
         */
        STATIC_READ(false),
        /** Names its target, or writes the state a player's input would have produced. */
        BYPASS(false);

        /** A constant: a final boolean written by the constructor, nothing mutable reachable. */
        private final boolean admittedInE2E;

        Kind(boolean admittedInE2E) {
            this.admittedInE2E = admittedInE2E;
        }

        /** Whether a child in e2e mode answers a command of this kind at all. */
        public boolean admittedInE2E() {
            return admittedInE2E;
        }
    }

    /** A constant: a final String, which is immutable. */
    private final String wireName;
    /** A constant: a final reference to an enum value. */
    private final Kind kind;
    /** A constant: a final String, which is immutable. */
    private final String reason;

    BridgeCommand(String wireName, Kind kind, String reason) {
        this.wireName = wireName;
        this.kind = kind;
        this.reason = reason;
    }

    /** The {@code command} value of a request. */
    public String wireName() {
        return wireName;
    }

    public Kind kind() {
        return kind;
    }

    /** Why the command is of its kind — what a refusal names. */
    public String reason() {
        return reason;
    }

    /** The command a request names, or {@code null} for one this bridge does not have. */
    public static BridgeCommand byWireName(String wireName) {
        for (BridgeCommand command : values()) {
            if (command.wireName.equals(wireName)) {
                return command;
            }
        }
        return null;
    }
}
