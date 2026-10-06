package dev.stannismod.stellurgy.util;

import net.minecraftforge.fml.relauncher.Side;

/**
 * Something that exists on one logical side was asked for from the other.
 *
 * <p>Thrown rather than answered with an empty value, because on an integrated server the other
 * side's object is usually REACHABLE — the client thread can read the server's tables by dimension —
 * and an answer obtained that way looks exactly like a correct one while reading state across threads
 * that nothing synchronises. A null would be no better: it is indistinguishable from "there is
 * nothing here", which is a real answer on the right side.</p>
 */
public class WrongSideException extends IllegalStateException {

    /**
     * @param what     what was asked for, as the message should name it
     * @param expected the side it lives on
     * @param found    the side it was asked from
     */
    public WrongSideException(String what, Side expected, Side found) {
        super(what + " exists on the " + expected + " side and was asked for from the " + found + " side");
    }
}
