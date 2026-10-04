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
    }
}
