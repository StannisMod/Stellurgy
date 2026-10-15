package dev.stannismod.stellurgy.command.test;

import java.util.Locale;

/**
 * The role a harness test sends a command in — {@code read}, {@code arrange} or {@code act} — and the kinds
 * each role admits. The harness's command bridge carries the role with every command (there is no role-less
 * send), and {@link E2EModeGate} refuses at the call a command whose kind its role does not admit, so the role
 * a test wrote beside a command is a claim the server checks, not a comment.
 *
 * <p>What the roles buy is a listing, not a judgement: every {@code act(…)} in a test method is a production
 * entry it triggers, and every DECIDE it sends is under {@code arrange(…)}, so a reviewer asking whether a probe
 * performed the act under test reads those sites instead of hunting through every command. Whether the act a
 * test labels {@code arrange} is really an arrangement and not the subject stays a reviewer's question.</p>
 */
enum CommandRole {

    /** Observes: admits a READ verb and a static call on a named instrument (each reads or records). */
    READ,
    /** Sets the scene: admits an ARRANGE, a BUILD, a DECIDE, and the e2e's walk and start item. */
    ARRANGE,
    /** Calls a production entry a player, a tick or a packet would reach: admits a TRIGGER. */
    ACT;

    /** Whether a command of {@code kind} may be sent in this role. */
    boolean admits(ProbeVerbKind kind) {
        switch (kind) {
            case READ:
            case STATIC_CALL:
                return this == READ;
            case ARRANGE:
            case BUILD:
            case DECIDE:
            case WALK:
            case START_ITEM:
                return this == ARRANGE;
            case TRIGGER:
                return this == ACT;
            default:
                return false;
        }
    }

    /** The role {@code word} names, or {@code null} when it names none. */
    static CommandRole named(String word) {
        for (CommandRole role : values()) {
            if (role.name().equals(word.toUpperCase(Locale.ROOT))) {
                return role;
            }
        }
        return null;
    }
}
