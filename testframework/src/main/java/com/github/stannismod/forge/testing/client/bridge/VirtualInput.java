package com.github.stannismod.forge.testing.client.bridge;

import org.lwjgl.input.Keyboard;
import org.lwjgl.input.Mouse;

import java.lang.reflect.*;
import java.nio.ByteBuffer;
import java.nio.IntBuffer;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;

/**
 * The client's keyboard and mouse, as far as the game can tell: the ONE way a player's input is put into
 * this client.
 *
 * <p><b>What it is.</b> LWJGL reads its devices through one {@code InputImplementation}: {@code Mouse.poll}
 * asks it for the cursor and the button states and then for the queued events, {@code Keyboard.poll} for
 * the key states and the queued key events. This object stands in that place, in front of the real one.
 * An event queued here comes out of {@code Mouse.next()} / {@code Keyboard.next()} inside the game's own
 * input loop — {@code GuiScreen.handleInput} into the screen's {@code handleMouseInput} /
 * {@code handleKeyboardInput} when a screen is open, {@code Minecraft.runTickMouse} /
 * {@code runTickKeyboard} into the key bindings, their press edge and Forge's input events when none is —
 * so what the input hits, and whether it counts, is decided by the code a real device feeds, including
 * the use key's edge and its delay timer, and a slot taking a held stack on RELEASE.</p>
 *
 * <p><b>The harness is the only device.</b> The real implementation is still asked, so its own queues
 * drain, but what it reports is dropped: the window is parked off the desktop and the developer's pointer
 * and keystrokes are not this client's input. The cursor, the button states and the key states this
 * reports are the ones the queued events leave behind.</p>
 *
 * <p><b>One cursor position per poll.</b> A screen may ask the cursor ({@code Mouse.getX()}) instead of
 * the event, so the events handed over in one poll all lie at one point, and the cursor reports that
 * point; the next point waits until the game has read every event of this one. A key batch waits the same
 * way, so the key states a handler reads belong to the events it is handling.</p>
 *
 * <p><b>Delivery.</b> Events reach the game's buffer between ticks ({@code Display.update}), and the next
 * client tick reads that buffer to the end. So an event is DELIVERED when a client tick has ended that
 * began after it was handed over; {@link #awaitDelivered} waits for that record.</p>
 *
 * <p>Owned by the client bridge, which the client owns, so it lives exactly as long as the client. It is
 * installed in LWJGL's own two {@code implementation} slots — static fields of LWJGL, not of this
 * harness — the same way the bridge already replaces {@code System.out} and the client's mouse helper.</p>
 */
final class VirtualInput {

    /** LWJGL's mouse event layout: button, state, x, y, wheel, nanos. */
    private static final int MOUSE_EVENT_SIZE = 1 + 1 + 4 + 4 + 4 + 8;
    /** LWJGL's key event layout: key, state, character, nanos, repeat. */
    private static final int KEY_EVENT_SIZE = 4 + 1 + 4 + 8 + 1;
    /** Mouse and Keyboard both allocate their read buffer for this many events. */
    static final int LWJGL_BUFFER_EVENTS = 50;
    /** LWJGL's {@code Keyboard.KEYBOARD_SIZE}: key codes are {@code 0..255}. */
    static final int KEY_COUNT = 256;
    static final int BUTTON_COUNT = 16;

    /** One input event, as LWJGL would have read it from the device. */
    static final class Event {
        final boolean mouse;
        /** Mouse: the button, or -1 for a move. Keyboard: the key code. */
        final int code;
        final boolean down;
        /** Mouse: display pixels, origin bottom-left. Unused for a key. */
        final int x;
        final int y;
        /** Keyboard: the typed character. */
        final int character;
        long seq;

        private Event(boolean mouse, int code, boolean down, int x, int y, int character) {
            this.mouse = mouse;
            this.code = code;
            this.down = down;
            this.x = x;
            this.y = y;
            this.character = character;
        }

        static Event mouse(int button, boolean down, int displayX, int displayY) {
            return new Event(true, button, down, displayX, displayY, 0);
        }

        static Event key(int key, boolean down, int character) {
            if (key < 0 || key >= KEY_COUNT) {
                throw new IllegalArgumentException("key code " + key + " is outside LWJGL's 0.." + (KEY_COUNT - 1));
            }
            return new Event(false, key, down, 0, 0, character);
        }

        String describe() {
            return mouse
                    ? (code < 0 ? "cursor to " : "mouse button " + code + (down ? " down" : " up") + " at ")
                    + x + "," + y
                    : "key " + code + (down ? " down" : " up") + (character != 0 ? " char " + character : "");
        }
    }

    private final Object lock = new Object();
    private final ArrayDeque<Event> mouseQueue = new ArrayDeque<>();
    private final ArrayDeque<Event> keyQueue = new ArrayDeque<>();
    /** The events chosen by the last poll, handed over by the read that follows it. */
    private final List<Event> mouseBatch = new ArrayList<>();
    private final List<Event> keyBatch = new ArrayList<>();
    private final boolean[] buttonsDown = new boolean[BUTTON_COUNT];
    private final boolean[] keysDown = new boolean[KEY_COUNT];
    private int cursorX;
    private int cursorY;
    private long nextSeq = 1L;
    /** The last event handed to LWJGL's buffer. */
    private long handedOver;
    /** {@link #handedOver} as it stood when the current client tick began. */
    private long handedOverAtTickStart;
    /** Every event up to this one has been read by a client tick. */
    private long delivered;
    /** Why the device could not deliver, once it could not; reported by every later wait. */
    private String fault;
    private boolean installed;

    /** The real devices' reflective handles, resolved once at install. */
    private Field mouseReadBuffer;
    private Field keyboardReadBuffer;
    private Object realImplementation;

    /**
     * Stand in front of LWJGL's input implementation. Called on the client thread once the display
     * exists; refuses loudly when the devices do not, because a client whose input cannot be injected is
     * a client an e2e cannot drive at all.
     */
    void install() {
        synchronized (lock) {
            if (installed) {
                return;
            }
            if (!Mouse.isCreated() || !Keyboard.isCreated()) {
                throw new IllegalStateException("the LWJGL mouse or keyboard was never created (mouse="
                        + Mouse.isCreated() + ", keyboard=" + Keyboard.isCreated()
                        + "): the client has no input device to stand in for");
            }
            try {
                Field mouseImpl = Mouse.class.getDeclaredField("implementation");
                Field keyboardImpl = Keyboard.class.getDeclaredField("implementation");
                mouseImpl.setAccessible(true);
                keyboardImpl.setAccessible(true);
                Object real = mouseImpl.get(null);
                if (real == null || real != keyboardImpl.get(null)) {
                    throw new IllegalStateException("LWJGL's mouse and keyboard do not share one input"
                            + " implementation (mouse=" + real + ", keyboard=" + keyboardImpl.get(null) + ")");
                }
                mouseReadBuffer = Mouse.class.getDeclaredField("readBuffer");
                keyboardReadBuffer = Keyboard.class.getDeclaredField("readBuffer");
                mouseReadBuffer.setAccessible(true);
                keyboardReadBuffer.setAccessible(true);
                Class<?> contract = Class.forName("org.lwjgl.opengl.InputImplementation", false,
                        Mouse.class.getClassLoader());
                realImplementation = real;
                Object standIn = Proxy.newProxyInstance(contract.getClassLoader(), new Class<?>[] {contract},
                        new StandIn());
                cursorX = Mouse.getX();
                cursorY = Mouse.getY();
                mouseImpl.set(null, standIn);
                keyboardImpl.set(null, standIn);
                installed = true;
            } catch (ReflectiveOperationException e) {
                throw new IllegalStateException("could not stand in for LWJGL's input implementation: " + e, e);
            }
        }
    }

    /** Queue {@code events} in order and answer the sequence of the last one. */
    long enqueue(List<Event> events) {
        synchronized (lock) {
            if (!installed) {
                throw new IllegalStateException("the virtual input device is not installed yet: the client has"
                        + " not finished its first tick");
            }
            if (fault != null) {
                throw new IllegalStateException("the virtual input device is broken: " + fault);
            }
            long last = delivered;
            for (Event event : events) {
                event.seq = nextSeq++;
                (event.mouse ? mouseQueue : keyQueue).addLast(event);
                last = event.seq;
            }
            return last;
        }
    }

    /** The cursor as the game currently reads it, display pixels from the bottom-left. */
    int[] cursor() {
        synchronized (lock) {
            return new int[] {cursorX, cursorY};
        }
    }

    /** Called on the client thread as a client tick begins. */
    void tickStarted() {
        synchronized (lock) {
            handedOverAtTickStart = handedOver;
        }
    }

    /** Called on the client thread as a client tick ends: everything handed over before it began is read. */
    void tickEnded() {
        synchronized (lock) {
            if (handedOverAtTickStart > delivered) {
                delivered = handedOverAtTickStart;
                lock.notifyAll();
            }
        }
    }

    /**
     * Wait until the event of sequence {@code seq} has been read by a client tick — a link on that record,
     * not a poll. Its expiry names how far the device got, which tells a client that stopped ticking from
     * one whose game never polled its devices.
     */
    void awaitDelivered(long seq, long budgetMillis) {
        long deadline = System.nanoTime() + budgetMillis * 1_000_000L;
        synchronized (lock) {
            while (delivered < seq) {
                if (fault != null) {
                    throw new IllegalStateException("input event " + seq + " was not delivered: " + fault);
                }
                long remaining = deadline - System.nanoTime();
                if (remaining <= 0L) {
                    throw new IllegalStateException("input event " + seq + " was not read by the game within "
                            + budgetMillis + " ms: handed to LWJGL up to " + handedOver + ", read by a tick up to "
                            + delivered + ", still queued: " + mouseQueue.size() + " mouse and " + keyQueue.size()
                            + " key event(s)" + (handedOver < seq
                            ? " — the game never polled its devices for it (is the client rendering frames?)"
                            : " — it is in LWJGL's buffer and no client tick has read it"));
                }
                try {
                    lock.wait(Math.max(1L, remaining / 1_000_000L));
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException("interrupted while waiting for input event " + seq);
                }
            }
        }
    }

    // --- the stand-in, called by LWJGL on the client thread inside Mouse.poll / Keyboard.poll ---

    private void pollMouse(IntBuffer coords, ByteBuffer buttons) {
        synchronized (lock) {
            if (mouseBatch.isEmpty() && !mouseQueue.isEmpty() && !hasUnread(mouseReadBuffer)) {
                if (Mouse.isGrabbed()) {
                    fault = "the mouse is GRABBED, so LWJGL reads its events as deltas; this device writes"
                            + " absolute positions (the harness runs with -Dfml.noGrab=true)";
                    lock.notifyAll();
                } else {
                    Event head = mouseQueue.peekFirst();
                    while (!mouseQueue.isEmpty() && mouseBatch.size() < LWJGL_BUFFER_EVENTS
                            && mouseQueue.peekFirst().x == head.x && mouseQueue.peekFirst().y == head.y) {
                        Event event = mouseQueue.pollFirst();
                        mouseBatch.add(event);
                        if (event.code >= 0 && event.code < BUTTON_COUNT) {
                            buttonsDown[event.code] = event.down;
                        }
                    }
                    cursorX = head.x;
                    cursorY = head.y;
                }
            }
            coords.put(0, cursorX);
            coords.put(1, cursorY);
            coords.put(2, 0);
            for (int i = 0; i < buttons.remaining() && i < BUTTON_COUNT; i++) {
                buttons.put(buttons.position() + i, (byte) (buttonsDown[i] ? 1 : 0));
            }
        }
    }

    private void readMouse(ByteBuffer buffer) {
        synchronized (lock) {
            for (Event event : mouseBatch) {
                if (buffer.remaining() < MOUSE_EVENT_SIZE) {
                    fault = "LWJGL's mouse buffer had no room for a batch it had emptied";
                    lock.notifyAll();
                    break;
                }
                buffer.put((byte) event.code);
                buffer.put((byte) (event.down ? 1 : 0));
                buffer.putInt(event.x);
                buffer.putInt(event.y);
                buffer.putInt(0);
                buffer.putLong(System.nanoTime());
                handedOver = Math.max(handedOver, event.seq);
            }
            mouseBatch.clear();
        }
    }

    private void pollKeyboard(ByteBuffer keyDown) {
        synchronized (lock) {
            if (keyBatch.isEmpty() && !keyQueue.isEmpty() && !hasUnread(keyboardReadBuffer)) {
                while (!keyQueue.isEmpty() && keyBatch.size() < LWJGL_BUFFER_EVENTS) {
                    Event event = keyQueue.pollFirst();
                    keyBatch.add(event);
                    keysDown[event.code] = event.down;
                }
            }
            for (int i = 0; i < KEY_COUNT && i < keyDown.remaining(); i++) {
                keyDown.put(keyDown.position() + i, (byte) (keysDown[i] ? 1 : 0));
            }
        }
    }

    private void readKeyboard(ByteBuffer buffer) {
        synchronized (lock) {
            for (Event event : keyBatch) {
                if (buffer.remaining() < KEY_EVENT_SIZE) {
                    fault = "LWJGL's keyboard buffer had no room for a batch it had emptied";
                    lock.notifyAll();
                    break;
                }
                buffer.putInt(event.code);
                buffer.put((byte) (event.down ? 1 : 0));
                buffer.putInt(event.character);
                buffer.putLong(System.nanoTime());
                buffer.put((byte) 0);
                handedOver = Math.max(handedOver, event.seq);
            }
            keyBatch.clear();
        }
    }

    /** Whether the game has events of one device it has not read yet. */
    private boolean hasUnread(Field readBuffer) {
        try {
            ByteBuffer buffer = (ByteBuffer) readBuffer.get(null);
            return buffer != null && buffer.hasRemaining();
        } catch (IllegalAccessException e) {
            throw new IllegalStateException("could not read LWJGL's " + readBuffer + ": " + e, e);
        }
    }

    /**
     * LWJGL's calls, answered: the four device reads by this device, everything else by the real
     * implementation. The real reads still run — into a scratch buffer for events — so the real device's
     * own queues drain as they always have.
     */
    private final class StandIn implements InvocationHandler {
        private final ByteBuffer scratchMouse = ByteBuffer.allocate(MOUSE_EVENT_SIZE * LWJGL_BUFFER_EVENTS);
        private final ByteBuffer scratchKeys = ByteBuffer.allocate(KEY_EVENT_SIZE * LWJGL_BUFFER_EVENTS);

        @Override
        public Object invoke(Object proxy, Method method, Object[] args) throws Throwable {
            String name = method.getName();
            if ("readMouse".equals(name)) {
                scratchMouse.clear();
                real(method, new Object[] {scratchMouse});
                readMouse((ByteBuffer) args[0]);
                return null;
            }
            if ("readKeyboard".equals(name)) {
                scratchKeys.clear();
                real(method, new Object[] {scratchKeys});
                readKeyboard((ByteBuffer) args[0]);
                return null;
            }
            Object result = real(method, args);
            if ("pollMouse".equals(name)) {
                pollMouse((IntBuffer) args[0], (ByteBuffer) args[1]);
            } else if ("pollKeyboard".equals(name)) {
                pollKeyboard((ByteBuffer) args[0]);
            }
            return result;
        }

        private Object real(Method method, Object[] args) throws Throwable {
            try {
                method.setAccessible(true);
                return method.invoke(realImplementation, args);
            } catch (InvocationTargetException e) {
                throw e.getCause() == null ? e : e.getCause();
            }
        }
    }
}
