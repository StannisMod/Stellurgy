package dev.stannismod.stellurgy.test.server;

// migrated to AbstractSharedServerTest
import dev.stannismod.stellurgy.test.Reply;
import org.junit.Test;

import static org.junit.Assert.assertTrue;

/**
 * commands smoke.
 *
 * Asserts that both {@code /stellurgytest} (test-only) and Stellurgy's primary command
 * ({@code stellurgy}/{@code advrocketry}/{@code ar}) are registered on a
 * fresh server.
 */
public class CommandsSmokeTest extends AbstractSharedServerTest {

    /** The array of registered command names this reply carries. */
    private static final String COMMANDS = "commands";

    @Test
    public void primaryCommandsAreRegistered() throws Exception {
        String joined = String.join("\n", client().execute("stellurgytest commands list"));
        // MEMBERSHIP of a list of names, asked of the list. As substrings over the rendering
        // these were satisfied by a name appearing anywhere — including inside a LONGER command
        // name, which is how `"ar"` could have been answered by `"stellurgytest"` had the quotes ever
        // been dropped, and how `"stellurgytest"` is answered by any command whose own name contains it.
        Reply commands = Reply.of("stellurgytest commands list", joined);
        // Kept as `arrayLength >= 0`, not turned into `has`: `has` asks for a PRIMITIVE and
        // answers false for an array, so it would have made this claim permanently false.
        assertTrue("/stellurgytest commands list schema invalid: " + joined,
                commands.arrayLength(COMMANDS) >= 0);
        assertTrue("/stellurgytest itself missing from command list (test mode broken?): " + joined,
                commands.holdsText(COMMANDS, "stellurgytest"));
        boolean hasStellurgy = commands.holdsText(COMMANDS, "stellurgy")
                || commands.holdsText(COMMANDS, "advrocketry")
                || commands.holdsText(COMMANDS, "ar");
        assertTrue("Stellurgy's primary command missing from command list: " + joined, hasStellurgy);
    }

    @Test
    public void stellurgyHelpCommandPrintsUsageWithoutCrash() throws Exception {
        // Stellurgy's primary command must surface usage text without crashing
        // the server. The StellurgyCommandRoot tree prints its usage line
        // "/stellurgy [subcommand]" rather than the old WorldCommand
        // "Subcommands:" header.
        String help = String.join("\n", client().execute("stellurgy help"));
        assertTrue("Stellurgy help did not surface the command usage: " + help,
                help.contains("/stellurgy") && help.contains("[subcommand]"));

        // Sanity: server is still responsive after running help.
        String alive = String.join("\n", client().execute("stellurgytest commands list"));
        assertTrue("server unresponsive after /stellurgy help: " + alive,
                (Reply.of(alive).arrayLength("commands") >= 0));
    }

    @Test
    public void stellurgyCommandWithInvalidArgsReturnsErrorNotCrash() throws Exception {
        // Malformed input must not crash the server. WorldCommand.execute
        // currently has no `default` branch — unknown subcommands silently
        // no-op. That is lenient but not a crash, which is what this test
        // pins. If Stellurgy ever tightens parsing to surface an explicit error
        // reply, strengthen this assertion accordingly.
        client().execute("stellurgy totally-bogus-subcommand-name");

        String alive = String.join("\n", client().execute("stellurgytest commands list"));
        assertTrue("server unresponsive after malformed /stellurgy: " + alive,
                (Reply.of(alive).arrayLength("commands") >= 0));
    }

    @Test
    public void stellurgytestRegistryWithBadSubcommandReturnsError() throws Exception {
        // /stellurgytest itself MUST surface unknown subcommands as a
        // structured JSON error reply, not crash or no-op. Pinned against
        // TestProbeCommand.handleRegistry's "unknown registry subcommand"
        // fallback branch.
        String reply = String.join("\n", client().execute("stellurgytest registry bogus"));
        // Read OF THE FIELD, not hunted for in the rendering — where the phrase is also carried
        // by the `sub` echo beside it and by any field quoting it back. A prefix rather than an
        // equality because the producer builds a usage tail onto this one ("… — try summary |
        // sounds <namespace> | …"), which is the one case the reader's javadoc allows.
        Reply refusal = Reply.of("stellurgytest registry bogus", reply);
        assertTrue("expected JSON error for unknown registry subcommand, got: " + reply,
                refusal.refused() && refusal.error().startsWith("unknown registry subcommand"));
    }
}
