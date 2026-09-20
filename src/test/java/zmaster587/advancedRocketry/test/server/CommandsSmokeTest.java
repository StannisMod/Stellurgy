package zmaster587.advancedRocketry.test.server;

// migrated to AbstractSharedServerTest
import zmaster587.advancedRocketry.test.Reply;
import org.junit.Test;

import static org.junit.Assert.assertTrue;

/**
 * commands smoke.
 *
 * Asserts that both {@code /artest} (test-only) and AR's primary command
 * ({@code advancedrocketry}/{@code advrocketry}/{@code ar}) are registered on a
 * fresh server.
 */
public class CommandsSmokeTest extends AbstractSharedServerTest {

    /** The array of registered command names this reply carries. */
    private static final String COMMANDS = "commands";

    @Test
    public void primaryCommandsAreRegistered() throws Exception {
        String joined = String.join("\n", client().execute("artest commands list"));
        // MEMBERSHIP of a list of names, asked of the list. As substrings over the rendering
        // these were satisfied by a name appearing anywhere — including inside a LONGER command
        // name, which is how `"ar"` could have been answered by `"artest"` had the quotes ever
        // been dropped, and how `"artest"` is answered by any command whose own name contains it.
        Reply commands = Reply.of("artest commands list", joined);
        // Kept as `arrayLength >= 0`, not turned into `has`: `has` asks for a PRIMITIVE and
        // answers false for an array, so it would have made this claim permanently false.
        assertTrue("/artest commands list schema invalid: " + joined,
                commands.arrayLength(COMMANDS) >= 0);
        assertTrue("/artest itself missing from command list (test mode broken?): " + joined,
                commands.holdsText(COMMANDS, "artest"));
        boolean hasAR = commands.holdsText(COMMANDS, "advancedrocketry")
                || commands.holdsText(COMMANDS, "advrocketry")
                || commands.holdsText(COMMANDS, "ar");
        assertTrue("AR's primary command missing from command list: " + joined, hasAR);
    }

    @Test
    public void arHelpCommandPrintsUsageWithoutCrash() throws Exception {
        // AR's primary command must surface usage text without crashing
        // the server. The ARCommandRoot tree prints its usage line
        // "/advancedrocketry [subcommand]" rather than the old WorldCommand
        // "Subcommands:" header.
        String help = String.join("\n", client().execute("advancedrocketry help"));
        assertTrue("AR help did not surface the command usage: " + help,
                help.contains("/advancedrocketry") && help.contains("[subcommand]"));

        // Sanity: server is still responsive after running help.
        String alive = String.join("\n", client().execute("artest commands list"));
        assertTrue("server unresponsive after /advancedrocketry help: " + alive,
                (Reply.of(alive).arrayLength("commands") >= 0));
    }

    @Test
    public void arCommandWithInvalidArgsReturnsErrorNotCrash() throws Exception {
        // Malformed input must not crash the server. WorldCommand.execute
        // currently has no `default` branch — unknown subcommands silently
        // no-op. That is lenient but not a crash, which is what this test
        // pins. If AR ever tightens parsing to surface an explicit error
        // reply, strengthen this assertion accordingly.
        client().execute("advancedrocketry totally-bogus-subcommand-name");

        String alive = String.join("\n", client().execute("artest commands list"));
        assertTrue("server unresponsive after malformed /advancedrocketry: " + alive,
                (Reply.of(alive).arrayLength("commands") >= 0));
    }

    @Test
    public void artestRegistryWithBadSubcommandReturnsError() throws Exception {
        // /artest itself MUST surface unknown subcommands as a
        // structured JSON error reply, not crash or no-op. Pinned against
        // TestProbeCommand.handleRegistry's "unknown registry subcommand"
        // fallback branch.
        String reply = String.join("\n", client().execute("artest registry bogus"));
        // Read OF THE FIELD, not hunted for in the rendering — where the phrase is also carried
        // by the `sub` echo beside it and by any field quoting it back. A prefix rather than an
        // equality because the producer builds a usage tail onto this one ("… — try summary |
        // sounds <namespace> | …"), which is the one case the reader's javadoc allows.
        Reply refusal = Reply.of("artest registry bogus", reply);
        assertTrue("expected JSON error for unknown registry subcommand, got: " + reply,
                refusal.refused() && refusal.error().startsWith("unknown registry subcommand"));
    }
}
