package com.github.stannismod.forge.testing.client;

import com.google.gson.JsonObject;
import net.minecraft.util.EnumFacing;
import net.minecraft.util.EnumHand;

import java.io.Closeable;
import java.io.IOException;
import java.net.Socket;
import java.time.Duration;

import static com.github.stannismod.forge.testing.client.ClientChannel.command;

/**
 * The harness's hands and eyes on a real client — ALL of them, for a mechanics test.
 *
 * <p><b>What each command IS</b> — an act through the input pipeline, a read, the harness's own, or a
 * bypass that names its target or writes what a player's input would have produced — is decided in ONE
 * place, the client bridge's {@code BridgeCommand}, which is what a client in e2e mode refuses by. Until
 * 2026-10-08 it was a javadoc table here, read by a text scan; a table nothing executes can be flipped
 * without anything noticing.</p>
 *
 * <p><b>Three views of one client.</b> {@link #input()} is a player's input and nothing else
 * ({@link PlayerInput}); this class is also the client's {@link ClientReads}; and the rest of its verbs
 * are the shortcuts a mechanics test may take because the input pipeline is not its subject —
 * {@link #interactBlock}, {@link #useItem}, {@link #sendChat} and the direct screen calls. An e2e is
 * handed the first two and never this class, so it cannot name a shortcut at all.</p>
 *
 * <p>The direct screen verbs here ({@link #setKey}, {@link #clickButtonAt}, {@link #clickSlotAt},
 * {@link #pressScreenKey}, {@link #typeText}, …) call the screen's or the binding's methods themselves.
 * Their {@link PlayerInput} namesakes put the same input through the game's own input loop instead.</p>
 */
public final class ClientBot implements Closeable, ClientReads {

    /**
     * How long the bot waits for a reply — and the OUTER budget of the whole bridge.
     *
     * <p><b>Every deadline the client side runs must be strictly shorter than this.</b> An inner
     * budget equal to the outer one can never be reported: the client finishes waiting exactly when
     * the caller has already given up, so its own diagnosis — which names what it was waiting for —
     * is written to a socket nobody is reading any more. Measured 2026-09-22: `wait_world` ran a
     * two-minute deadline of its own, inside a `runOnClientThread` whose future `get` was also two
     * minutes, inside this. A slow world load was therefore delivered as "the client bridge did not
     * answer", and the client's own "Timed out waiting for the client world to load" could not
     * reach anyone by construction.</p>
     *
     * <p>A constant: a final {@code long} computed from a fixed duration.</p>
     */
    public static final long READ_TIMEOUT_MILLIS = Duration.ofMinutes(2).toMillis();

    private final ClientChannel channel;
    private final ReadVerbs reads;
    private final PlayerInput input;

    /**
     * @param walkServer the e2e's server, told once that the player's walk has begun before his first input
     *                   reaches this client ({@link PlayerInput}); {@code null} for a client that serves no e2e
     */
    ClientBot(Socket socket, com.github.stannismod.forge.testing.server.TestClient walkServer) throws IOException {
        this.channel = new ClientChannel(socket);
        this.reads = new ReadVerbs(channel);
        this.input = new PlayerInput(channel, walkServer);
    }

    /** This client's reads alone, as a type that cannot be cast into anything that acts. */
    public ClientReads reads() {
        return reads;
    }

    /** A player's input to this client — see {@link PlayerInput}. */
    public PlayerInput input() {
        return input;
    }

    public void waitForWorld() throws IOException {
        channel.call(command("wait_world"));
    }

    /**
     * A REAL relog: quits the current server connection and reconnects to the same address,
     * exactly as the player's disconnect + rejoin would. The server performs a full logout
     * (player data saved to disk) and a fresh login; the client rebuilds its world and player
     * entity. Follow with {@link #waitForWorld()} - the reconnect is asynchronous.
     *
     * <p>The address is read off the live connection, so this reconnects to the server the
     * client is attached to RIGHT NOW. It therefore cannot follow a server that has been
     * restarted in between: each harness boot reserves a fresh port, so the old address is
     * dead. To span a restart, run two harness lifecycles against the same workDir and start
     * a second client instead.</p>
     */
    public void reconnect() throws IOException {
        channel.call(command("reconnect"));
    }

    /**
     * The DISCONNECT half of {@link #reconnect()}: quits the current server connection and stays
     * at the main menu. The server performs a full player logout (data saved to disk) and keeps
     * running WITHOUT the player - use this when a test must act on the server while the player
     * is genuinely offline (e.g. someone takes his seat), then {@link #connect()} back. The
     * server address is remembered inside the client for that later connect.
     */
    public void disconnect() throws IOException {
        channel.call(command("disconnect"));
    }

    /**
     * The CONNECT half: rejoins the server a prior {@link #disconnect()} left - the server sees
     * a fresh login and re-reads the player's saved data. Asynchronous like {@link #reconnect()}:
     * follow with {@link #waitForWorld()}. Fails if no disconnect ran before it. Like reconnect,
     * this cannot span a server restart (each harness boot reserves a fresh port).
     */
    public void connect() throws IOException {
        channel.call(command("connect"));
    }

    @Override
    public void waitTicks(int ticks) throws IOException {
        reads.waitTicks(ticks);
    }

    /**
     * Only the ticks on which the player can act: {@link #waitTicks} also counts the menu and the
     * terrain screen. Its failure says whether the client stopped ticking or ticked without a world.
     */
    @Override
    public void waitWorldTicks(int ticks) throws IOException {
        reads.waitWorldTicks(ticks);
    }

    public void selectHotbar(int slot) throws IOException {
        JsonObject command = command("select_hotbar");
        command.addProperty("slot", slot);
        channel.call(command);
    }

    public void rightClickBlock(int x, int y, int z, EnumFacing face, EnumHand hand) throws IOException {
        JsonObject command = command("right_click_block");
        command.addProperty("x", x);
        command.addProperty("y", y);
        command.addProperty("z", z);
        command.addProperty("face", face.name());
        command.addProperty("hand", hand.name());
        channel.call(command);
    }

    /** The screen's own {@code mouseClicked} at a point, called directly — a press without a release. */
    public void clickScreenPoint(int x, int y, int button) throws IOException {
        JsonObject command = command("click_screen_point");
        command.addProperty("x", x);
        command.addProperty("y", y);
        command.addProperty("button", button);
        channel.call(command);
    }

    public void clickButton(int index) throws IOException {
        JsonObject command = command("click_button");
        command.addProperty("index", index);
        channel.call(command);
    }

    public void clickButtonAtRatio(int index, double ratio) throws IOException {
        JsonObject command = command("click_button_ratio");
        command.addProperty("index", index);
        command.addProperty("ratio", ratio);
        channel.call(command);
    }

    /**
     * Lists every {@link net.minecraft.client.gui.GuiButton} on the open GUI:
     * each entry carries {@code id}, {@code text}, {@code x}/{@code y}/{@code width}/
     * {@code height}, {@code enabled} and {@code visible}. Use the stable
     * {@code id} (assigned by the mod, not the list position) to drive
     * {@link #clickButtonById(int)}.
     */
    @Override
    public JsonObject reportButtons() throws IOException {
        return reads.reportButtons();
    }

    /**
     * Clicks the GUI button whose {@code GuiButton.id} equals {@code id} —
     * robust against button-list ordering. Fails if no such button exists or it
     * is hidden / disabled.
     */
    public void clickButtonById(int id) throws IOException {
        JsonObject command = command("click_button_id");
        command.addProperty("id", id);
        channel.call(command);
    }

    /**
     * Clicks the button whose {@code GuiButton.id} equals {@code id} at its point: the cursor is put at
     * the button's centre and the screen's own {@code mouseClicked} and {@code mouseReleased} are called
     * there, so the screen decides what is under the point. The id only FINDS the point. Fails if no such
     * button exists or it is hidden or disabled. {@link PlayerInput#clickButtonAt} is the same click
     * through the game's input loop.
     */
    public JsonObject clickButtonAt(int id) throws IOException {
        JsonObject command = command("click_button_at");
        command.addProperty("id", id);
        return channel.call(command);
    }

    /**
     * Clicks a container slot at the slot's centre on screen, press and release called directly on the
     * screen, so {@code GuiContainer} decides which slot is hit and what the click does with the held
     * stack. {@link PlayerInput#clickSlotAt} is the same click through the game's input loop.
     */
    public JsonObject clickSlotAt(int slot, int button) throws IOException {
        JsonObject command = command("click_slot_at");
        command.addProperty("slot", slot);
        command.addProperty("button", button);
        return channel.call(command);
    }

    /**
     * Presses a key on the open screen through its own {@code keyTyped}, called directly — escape
     * ({@code Keyboard.KEY_ESCAPE}) closes it by whatever that screen does with escape.
     */
    public void pressScreenKey(int keyCode) throws IOException {
        JsonObject command = command("press_screen_key");
        command.addProperty("keyCode", keyCode);
        channel.call(command);
    }

    /**
     * Lists every slot of the open {@link net.minecraft.client.gui.inventory.GuiContainer}:
     * each entry carries {@code slot} (the container slot number), {@code index} (the slot's index in its
     * own inventory, {@code Slot#getSlotIndex}), {@code x}/
     * {@code y}, {@code playerSlot} (true for the player-inventory portion),
     * {@code hasStack}, {@code item} (registry name) and {@code count}.
     */
    @Override
    public JsonObject reportSlots() throws IOException {
        return reads.reportSlots();
    }

    /**
     * Performs a container slot interaction, mirroring
     * {@code GuiContainer.handleMouseClick}. {@code mode} is a
     * {@link net.minecraft.inventory.ClickType} name — {@code PICKUP} for a
     * normal click, {@code QUICK_MOVE} for shift-click, etc.
     */
    public void clickSlot(int slot, int button, String mode) throws IOException {
        JsonObject command = command("click_slot");
        command.addProperty("slot", slot);
        command.addProperty("button", button);
        command.addProperty("mode", mode);
        channel.call(command);
    }

    public void dragScreenPoint(int startX, int startY, int endX, int endY, int button) throws IOException {
        JsonObject command = command("drag_screen_point");
        command.addProperty("startX", startX);
        command.addProperty("startY", startY);
        command.addProperty("endX", endX);
        command.addProperty("endY", endY);
        command.addProperty("button", button);
        channel.call(command);
    }

    public void focusField(String fieldName) throws IOException {
        JsonObject command = command("focus_field");
        command.addProperty("field", fieldName);
        channel.call(command);
    }

    public void typeText(String text) throws IOException {
        JsonObject command = command("type_text");
        command.addProperty("text", text);
        channel.call(command);
    }

    public void pressEnterAfterTyping(String text) throws IOException {
        JsonObject command = command("type_text");
        command.addProperty("text", text);
        command.addProperty("pressEnter", true);
        channel.call(command);
    }

    /**
     * The client's own view of itself: screen, GUI geometry, player position / health / held item,
     * and — when a world is loaded — the {@code dimension} it renders and that world's
     * {@code worldType} name, as the client learned it from the join/respawn packet.
     */
    @Override
    public JsonObject reportState() throws IOException {
        return reads.reportState();
    }

    /**
     * Client-side view of the entity the player is currently riding. Reports
     * {@code riding} (bool), and when riding: {@code entityClass}, {@code entityId},
     * {@code posX}/{@code posY}/{@code posZ} and {@code motionX}/{@code motionY}/
     * {@code motionZ}. This is the authoritative way to assert what the player's
     * CLIENT actually renders — distinct from a server-side entity query — so it
     * catches client-side position-sync / interpolation regressions.
     */
    @Override
    public JsonObject reportRidingEntity() throws IOException {
        return reads.reportRidingEntity();
    }

    /**
     * Writes a key-binding's state on the client directly: {@code KeyBinding.isKeyDown()} (held
     * movement keys), a single {@code isPressed()} edge, and Forge's {@code InputEvent.KeyInputEvent}, so
     * mod input handlers fire their real packet path whether they poll on {@code ClientTickEvent} or
     * subscribe to {@code KeyInputEvent}. It does this whether or not a screen is open, which a real key
     * does not; {@link PlayerInput#setKey} puts the key through the game's own input loop instead.
     *
     * <p>The event carries no key code (it never did): a handler that reads
     * {@code Keyboard.getEventKey()} directly instead of polling its own
     * {@code KeyBinding} sees LWJGL's empty event state and stays unreachable.</p>
     *
     * @param keyCode LWJGL key code (e.g. {@link org.lwjgl.input.Keyboard#KEY_Z})
     * @param pressed true to hold the key down, false to release it
     */
    public void setKey(int keyCode, boolean pressed) throws IOException {
        JsonObject command = command("set_key");
        command.addProperty("keyCode", keyCode);
        command.addProperty("pressed", pressed);
        channel.call(command);
    }

    /** Convenience: hold a key down ({@link #setKey(int, boolean) setKey(keyCode, true)}). */
    public void holdKey(int keyCode) throws IOException {
        setKey(keyCode, true);
    }

    /** Convenience: release a key ({@link #setKey(int, boolean) setKey(keyCode, false)}). */
    public void releaseKey(int keyCode) throws IOException {
        setKey(keyCode, false);
    }

    /**
     * Sets the client player's look direction, exactly as the mouse would after
     * accumulating movement. Drives {@code EntityPlayerSP.rotationYaw/rotationPitch}
     * (and the prev-tick fields, so there is no render interpolation jump), so mod
     * code that reads the player's look on {@code ClientTickEvent} (e.g. a flight
     * controller that aims a craft at where the pilot is looking) exercises its
     * real path — not a server-side shortcut.
     *
     * @param yaw   absolute yaw in degrees
     * @param pitch absolute pitch in degrees (negative = up, MC convention)
     */
    public void setLook(float yaw, float pitch) throws IOException {
        input.setLook(yaw, pitch);
    }

    /**
     * Turns the client player's look by a RAW mouse delta, through the game's own
     * {@code Entity.turn} - the exact method the real mouse handler feeds accumulated
     * movement into. Unlike {@link #setLook} (which writes the rotation fields directly,
     * like a teleport), this exercises every mod hook installed on the turn path, e.g.
     * a frame-relative look transform for a player standing on a moving platform.
     *
     * <p>Deltas are in vanilla mouse units: {@code rotationYaw += deltaYaw * 0.15},
     * {@code rotationPitch -= deltaPitch * 0.15} (positive {@code deltaPitch} looks UP),
     * pitch clamped to +-90.</p>
     */
    public void turnLook(float deltaYaw, float deltaPitch) throws IOException {
        input.turnLook(deltaYaw, deltaPitch);
    }

    /**
     * Reflectively reads a static field on the client and returns its
     * {@code String.valueOf(...)} as {@code value} (plus {@code isNull},
     * {@code type}). Lets a test assert arbitrary client-side mod state (HUD
     * text, render flags, …) without the framework depending on the mod.
     *
     * @param className fully-qualified class name (loaded on the client classpath)
     * @param fieldName a static field on that class or a superclass
     */
    public JsonObject readStaticField(String className, String fieldName) throws IOException {
        return reads.readStaticField(className, fieldName);
    }

    /**
     * What the CLIENT's tile entity at these coordinates holds, as its own update tag.
     *
     * <p>The honest observable for "did a server-side change reach the client": a renderer's output
     * cannot be read from a test, but the state it draws from can. Answers {@code present},
     * {@code tile} (class name) and {@code nbt} (the update tag, stringified).</p>
     */
    @Override
    public JsonObject tileNbt(int x, int y, int z) throws IOException {
        return reads.tileNbt(x, y, z);
    }

    /**
     * Call a static {@code void}/value method with {@code int} parameters on the CLIENT thread.
     *
     * <p>Pointing this at the mod's own raw-delta entry point runs exactly the code a real mouse move
     * runs, and pointing it at a test recorder reads that recorder. It calls whatever it is handed, so a
     * client in e2e mode refuses it on any class outside the packages the run admits.</p>
     */
    @Override
    public JsonObject invokeStaticInt(String className, String methodName, int... args)
            throws IOException {
        return reads.invokeStaticInt(className, methodName, args);
    }

    /**
     * Capture what the client is actually drawing, to {@code <gameDir>/screenshots/<name>.png}.
     *
     * <p>The bot cannot press F2 - vanilla dispatches that from the raw LWJGL key-event queue, which
     * {@link #setKey} does not feed - so this calls {@code ScreenShotHelper} on the client thread
     * instead. The game directory is the harness {@code root()}, which is deleted after the test, so
     * read or copy the file before teardown.</p>
     *
     * @return {@code path}, {@code exists}, {@code bytes}, {@code width}, {@code height}, and
     *         {@code framebuffer} (whether the FBO capture path was used)
     */
    @Override
    public JsonObject screenshot(String name) throws IOException {
        return reads.screenshot(name);
    }

    /**
     * Turn the client's framebuffer object on or off at runtime, returning its {@code previous} value.
     *
     * <p>{@link #screenshot} can only see real pixels when it is on: otherwise the last frame lives in a
     * back buffer whose contents the driver may discard at the swap. The harness leaves it off for
     * driver safety, so a test that means to LOOK at the frame enables it, renders a few
     * ({@link #waitTicks}), captures, and puts it back.</p>
     *
     * <p><b>Known limit — enabling it here does NOT reliably capture the WORLD.</b> Measured 2026-07-29:
     * after a runtime enable, the recreated framebuffer receives the HUD pass but not the world pass, so
     * a capture comes back as the framebuffer's own clear colour (opaque WHITE) with only the HUD drawn
     * over it. It looks exactly like "the world rendered nothing", which is how it cost a session: three
     * runs read an empty instrument as a verdict about a renderer. A test that measures WORLD pixels must
     * start the client with the FBO already on ({@code -PclientFbo=true}, i.e.
     * {@code -Dforge.test.client.fbo=true}) and carry a control frame from a scene it knows is
     * non-empty.</p>
     */
    public JsonObject setFramebuffer(boolean enabled) throws IOException {
        JsonObject command = command("set_framebuffer");
        command.addProperty("enabled", enabled);
        return channel.call(command);
    }

    /**
     * Set the client's render distance in chunks at runtime, returning its {@code previous} value.
     *
     * <p>The sibling of {@link #setFramebuffer}, and needed for the same kind of reason: vanilla runs the
     * whole SKY pass - and therefore a dimension's custom {@code IRenderHandler} - only when
     * {@code renderDistanceChunks >= 4}. The harness pins it at 2, so a test that captures a sky must
     * raise it first (and restore it after), or it will screenshot an empty frame for a reason that has
     * nothing to do with what it is testing. The value also drives the sky projection's far plane
     * ({@code farPlaneDistance * 2}), so pick one that clears the geometry's radius.</p>
     *
     * @return {@code previous}, {@code chunks}, and {@code skyPassEnabled} (whether the new value passes
     *         vanilla's sky gate)
     */
    public JsonObject setRenderDistance(int chunks) throws IOException {
        JsonObject command = command("set_render_distance");
        command.addProperty("chunks", chunks);
        return channel.call(command);
    }

    /**
     * Raise or lower the client's frame-rate cap at runtime, returning its {@code previous} value.
     *
     * <p>The harness seeds {@code maxFps:30}, which is right for a test that only needs the client to
     * run and wrong for one that reads the rendered frame as a CLOCK: a hitch a player sees at 120 fps
     * is averaged away by a 30 fps sampler, so a smoothness measurement taken at the default reads a
     * clean number off an instrument that cannot resolve what it is looking for. Raise it for the
     * measurement and put it back, exactly as {@link #setRenderDistance} does for the sky.</p>
     *
     * @return {@code previous}, {@code limitFramerate} (read back), {@code effectiveLimit} (what vanilla
     *         actually syncs to), {@code capped}, and {@code vsync} — vsync overrides the cap at the
     *         driver, so a caller that raised the limit and still sees 60 finds the reason here
     */
    public JsonObject setFrameRate(int fps) throws IOException {
        JsonObject command = command("set_frame_rate");
        command.addProperty("fps", fps);
        return channel.call(command);
    }

    /**
     * Hide or show the whole in-game HUD (vanilla's F1), returning its {@code previous} value.
     *
     * <p>Required before a {@link #screenshot} that MEASURES the rendered world. The chat overlay carries
     * this harness's own per-command completion markers, so it changes between two captures; the
     * crosshair inverts to white over a dark background. Both are frame differences the test did not
     * cause. Hiding also drains the toast queue, because toasts are drawn outside vanilla's hideGUI gate
     * - so call this immediately before each capture, not once at the start.</p>
     */
    public JsonObject setHudHidden(boolean hidden) throws IOException {
        JsonObject command = command("set_hud_hidden");
        command.addProperty("hidden", hidden);
        return channel.call(command);
    }

    /**
     * Right-clicks the HELD item with no block target: routes through
     * {@code PlayerControllerMP.processRightClick} (the real
     * {@code CPacketPlayerTryUseItem} path), so {@code Item.onItemRightClick}
     * runs on both sides against the real player. Returns the client-side
     * {@code EnumActionResult} name under {@code result}.
     */
    public JsonObject useItem() throws IOException {
        return channel.call(command("use_item"));
    }

    /**
     * Recent lines of the client chat overlay, newest first, i18n already
     * resolved — exactly the text the player reads. The honest observation
     * for "the player received a chat message". The response also carries
     * {@code overlay} — the last ACTION-BAR message ({@code setOverlayMessage}
     * / the GAME_INFO chat type, which never enters the chat list) — and
     * {@code overlayTicks} (&gt; 0 while it is still on screen).
     */
    @Override
    public JsonObject reportChat(int limit) throws IOException {
        return reads.reportChat(limit);
    }

    /**
     * Client-side view of the player's held / offhand / armor / main-inventory
     * stacks ({@code id}, {@code count}, {@code nbt} string). This is the
     * synced state the HUD and inventory screen render from.
     */
    @Override
    public JsonObject reportPlayerItems() throws IOException {
        return reads.reportPlayerItems();
    }

    /**
     * Entities in the CLIENT world within {@code radius} of the player whose
     * class name contains {@code classContains} (empty = all). Pins "the
     * client actually sees the entity" — spawn sync, tracking range, render
     * presence — which no server-side query can.
     */
    @Override
    public JsonObject reportEntities(String classContains, double radius) throws IOException {
        return reads.reportEntities(classContains, radius);
    }

    /**
     * Right-clicks whatever the CROSSHAIR is on, by calling {@code Minecraft.rightClickMouse} directly.
     *
     * <p>The difference from {@link #interactBlock} is that vanilla decides what is hit, from
     * {@code mc.objectMouseOver}. The difference from a real use key is that it skips the key's press edge
     * and its delay timer — {@link PlayerInput#setKey} with the use key (-99) is the player's form.</p>
     *
     * <p>Reports what the crosshair was on AT THE MOMENT OF THE CLICK — {@code aimedAtBlock},
     * {@code blockX}/{@code blockY}/{@code blockZ} and {@code blockBefore} (the registry id there
     * before the click) — because reading that in a separate call is a second frame, and the
     * question is what the outline and the click saw on ONE frame. On a physics-mod ship the
     * position is the ship's own subspace one, as with {@link #reportMouseOver}.</p>
     */
    public JsonObject useMouseOver() throws IOException {
        return channel.call(command("use_mouse_over"));
    }

    /**
     * Right-clicks a block named by position: routes through
     * {@code PlayerControllerMP.processRightClickBlock} on the client thread,
     * which sends the real {@code CPacketPlayerTryUseItemOnBlock} — so the
     * server runs its production interaction path (reach checks,
     * {@code Block.onBlockActivated}, bed {@code trySleep}, …) with the real
     * player. Returns the client-side {@code EnumActionResult} name under
     * {@code result}.
     */
    public JsonObject interactBlock(int x, int y, int z) throws IOException {
        JsonObject command = command("interact_block");
        command.addProperty("x", x);
        command.addProperty("y", y);
        command.addProperty("z", z);
        return channel.call(command);
    }

    /**
     * What the client's crosshair is currently pointing at — {@code mc.objectMouseOver},
     * the very field vanilla's {@code rightClickMouse()} reads to decide what a
     * right-click hits. Reports {@code present}, {@code typeOfHit}
     * ({@code MISS}/{@code BLOCK}/{@code ENTITY}), and when a block position is
     * carried: {@code hasBlockPos}, {@code blockX}/{@code blockY}/{@code blockZ},
     * {@code block} (registry id at that position in the client world),
     * {@code sideHit} and {@code hitX}/{@code hitY}/{@code hitZ}; for an entity hit,
     * {@code entityClass} / {@code entityId}.
     *
     * <p>The honest observation for "the bot is actually AIMED at the thing" before it
     * presses use — without it, an aim that missed and a click the server refused are
     * indistinguishable. Note the raytrace runs in whatever coordinate space the world's
     * collision hooks provide, so on a physics-mod ship the reported position is the
     * ship's own block position, not the rendered world one.</p>
     */
    @Override
    public JsonObject reportMouseOver() throws IOException {
        return reads.reportMouseOver();
    }

    /**
     * Forge mod registry as the CLIENT sees it: {@code loadedCount} /
     * {@code activeCount} (the two numbers the vanilla main menu renders as
     * "N mods loaded, M mods active" via {@code FMLCommonHandler.getBrandings})
     * plus {@code loadedModIds}. Lets a test pin loaded/active parity and the
     * presence/absence of specific containers at the layer the player reads.
     */
    @Override
    public JsonObject reportMods() throws IOException {
        return reads.reportMods();
    }

    /**
     * Sends one chat line exactly as if the player typed it — leading-{@code /}
     * commands included. Routes through {@code EntityPlayerSP.sendChatMessage}
     * (the real {@code CPacketChatMessage} path), so the server handles it with
     * a PLAYER sender: permission checks, the sender's world/dimension, and
     * {@code CommandEvent} hooks all run their production path. This is the
     * canonical way to e2e a command whose behaviour depends on where the
     * player stands — console-driven commands can't reproduce that.
     */
    public void sendChat(String message) throws IOException {
        JsonObject command = command("send_chat");
        command.addProperty("message", message);
        channel.call(command);
    }

    /**
     * Client-side view of vanilla weather state for whatever dim the player is
     * currently in. Reports {@code dim}, {@code worldInfoClass}, {@code isRaining},
     * {@code isThundering}, {@code rainTime}, {@code thunderTime},
     * {@code rainStrength} (post-SPacketChangeGameState lerp), {@code thunderStrength}.
     * If the client world isn't ready yet, only {@code worldReady=false} is set.
     */
    @Override
    public JsonObject reportWeather() throws IOException {
        return reads.reportWeather();
    }

    /**
     * Client-side view of the spawn point for the dim the client is currently
     * in: {@code dim}, {@code worldInfoClass}, {@code spawnX/Y/Z} (what
     * {@code NetHandlerPlayClient.handleSpawnPosition} writes into
     * {@code WorldInfo}), {@code worldSpawnX/Y/Z} (the provider + world-border
     * view), {@code hasBedLocation} and {@code bedX/Y/Z} (the player's own
     * spawn, dimension-aware). If the client world isn't ready only
     * {@code worldReady=false} is set; if the player isn't spawned yet,
     * {@code playerReady=false}.
     *
     * <p>A fresh {@code WorldClient} seeds the placeholder {@code (8,64,8)},
     * so reading that value back means no {@code SPacketSpawnPosition} was
     * applied for the dim the client is in.</p>
     */
    @Override
    public JsonObject reportSpawn() throws IOException {
        return reads.reportSpawn();
    }

    /**
     * Sound locations the client {@code SoundManager} was asked to play since
     * the last {@link #clearSounds()} — recorded via the client-side
     * {@code PlaySoundEvent}. The event fires BEFORE asset resolution, so this
     * observes the play request reaching the SoundManager, not asset
     * existence / audibility. Returns {@code sounds} (array of
     * {@code namespace:path} strings, oldest first, capped), {@code total}
     * (monotonic count since client start) and {@code managerLoaded}
     * ({@code false} = the sound system never initialised, e.g. no audio
     * device — nothing will ever be recorded; tests should
     * {@code Assume.assumeTrue(managerLoaded)} instead of misdiagnosing).
     * Includes vanilla ambience/music — filter on the caller side.
     */
    @Override
    public JsonObject reportSounds() throws IOException {
        return reads.reportSounds();
    }

    /**
     * The client event log's current sequence, taken BEFORE the action under test.
     *
     * <p>The client half of the ordered event log. Its first citizen is
     * {@code chunk_data_applied} — the instant the client can actually SEE a chunk's blocks, which
     * no Forge event reports (the load event fires on an empty chunk, before the data is applied).
     * A test that teleports a player onto freshly-sent terrain has to wait for THAT, not for a
     * number of ticks: movement is client-driven, so a client without the blocks simulates a fall
     * and carries the server's player down with it.</p>
     */
    @Override
    public JsonObject eventMark() throws IOException {
        return reads.eventMark();
    }

    /** Client events recorded at or after {@code seq}, in order; {@code type} filters, or null. */
    @Override
    public JsonObject eventsSince(long seq, String type) throws IOException {
        return reads.eventsSince(seq, type);
    }

    /** Resets the played-sound log consumed by {@link #reportSounds()}. */
    @Override
    public void clearSounds() throws IOException {
        reads.clearSounds();
    }

    @Override
    public JsonObject blockState(int x, int y, int z) throws IOException {
        return reads.blockState(x, y, z);
    }

    /**
     * Invokes the tile's libVulpes {@code getModules(int, EntityPlayer)} on the
     * client thread and reports {@code threw} — whether building its modular GUI
     * throws. Pins a client-side GUI-build crash without needing the multiblock
     * assembled (which the GUI-open path would otherwise require).
     */
    public JsonObject tileModulesThrows(int x, int y, int z) throws IOException {
        JsonObject command = command("tile_modules_throws");
        command.addProperty("x", x);
        command.addProperty("y", y);
        command.addProperty("z", z);
        return channel.call(command);
    }

    /**
     * Evaluates a no-arg reflective chain on the client (first method static on
     * {@code className}, each next called on the prior result). Reports
     * {@code result} and, when the final value is an array/Collection/Map, its
     * {@code size}. It calls any public chain it is handed, which no package rule bounds, so a client in
     * e2e mode refuses it outright.
     */
    public JsonObject invokeStaticChain(String className, String methods) throws IOException {
        JsonObject command = command("invoke_static_chain");
        command.addProperty("class", className);
        command.addProperty("methods", methods);
        return channel.call(command);
    }

    public void closeScreen() throws IOException {
        channel.call(command("close_screen"));
    }

    /**
     * Return the CLIENT-owned state a scenario can dirty to what a freshly-booted client holds:
     * closes any open screen, clears the chat backlog, clears the action-bar overlay and its
     * countdown, and releases every held key.
     *
     * <p>Only needed when one harness carries more than one scenario. Measured 2026-08-06: on the
     * second scenario of a shared run, ALL FOUR of those channels were still carrying the first
     * scenario's state. The chat backlog is the dangerous one — a scenario that asserts "the player
     * was told X" by searching the last N lines passes on the PREVIOUS scenario's identical line,
     * with no stimulus behind it.</p>
     *
     * <p>Position and inventory are NOT reset here — they are the server's, and a caller resets
     * them through the command channel (a teleport, a clear). The response reports what was found
     * dirty ({@code clearedScreen}, {@code clearedChatLines}, {@code clearedOverlay},
     * {@code clearedOverlayTicks}) so a caller can assert on the reset rather than trust it.</p>
     */
    public JsonObject resetClientState() throws IOException {
        return channel.call(command("reset_client_state"));
    }

    /**
     * Clear the chat backlog and the action-bar overlay, leaving the open screen and held keys
     * ALONE. Returns the same {@code clearedChatLines} / {@code clearedOverlay} /
     * {@code clearedOverlayTicks} fields {@link #resetClientState()} does.
     *
     * <p>Use this to arm a chat observation immediately before a stimulus that is a click on an
     * OPEN GUI: {@link #resetClientState()} closes the screen, so calling it there would destroy
     * the arrangement it was called to protect.</p>
     */
    @Override
    public JsonObject clearChat() throws IOException {
        return reads.clearChat();
    }

    /**
     * Is the client still answering? Answers within {@code timeoutMillis} whatever the client does.
     *
     * <p>This exists because the command channel's own read timeout is <b>two minutes</b>
     * ({@link #READ_TIMEOUT_MILLIS}). That is right for a command: a starved client thread queue
     * genuinely takes a long time. It is wrong for a
     * liveness question asked on a failure path, and the two death modes are not alike:</p>
     *
     * <ul>
     *   <li>the client process <b>exited</b> — the socket is closed, {@code readLine} returns null
     *       and the ordinary channel already throws instantly;</li>
     *   <li>the client process <b>hung</b> — the socket is still open and nothing ever answers, so
     *       the ordinary channel blocks for the full six minutes, once per caller.</li>
     * </ul>
     *
     * <p>A caller deciding "is the rest of this class worth running" must get the same fast answer
     * in both. So this borrows the socket with its own short timeout and restores the original.</p>
     *
     * <p><b>Trade, stated:</b> a merely SLOW client can be reported dead. A normal round trip is
     * milliseconds, so pass something generous (seconds) — never a value
     * close to a real round trip, or a loaded box starts declaring corpses.</p>
     *
     * @return true if the client answered a trivial command in time; false on ANY failure
     */
    @Override
    public boolean isAlive(int timeoutMillis) {
        return reads.isAlive(timeoutMillis);
    }

    public void shutdown() throws IOException {
        channel.call(command("shutdown"));
    }

    @Override
    public void close() throws IOException {
        try {
            if (channel.isOpen()) {
                try {
                    shutdown();
                } catch (IOException ignored) {
                    // The client may already be gone.
                }
            }
        } finally {
            channel.close();
        }
    }
}
