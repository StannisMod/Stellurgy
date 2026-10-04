package dev.stannismod.stellurgy.inventory.modules;

/**
 * Where a scrolling list in a machine's GUI was left, so reopening the GUI shows the same place.
 * Owned by the client copy of the machine whose list it is: a GUI is rebuilt on every update, the
 * machine is not, and another machine's list has nothing to do with this one's.
 */
public final class ScrollMemory {

    /** Nothing remembered: the list opens at its top. */
    static final int NONE = Integer.MIN_VALUE;

    int offset = NONE;

    /** Forget the position — the list's contents changed, so the old place means nothing. */
    public void clear() {
        offset = NONE;
    }
}
