package com.github.stannismod.forge.testing.client;

import org.junit.Test;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;

import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/**
 * A client bridge that stops answering must say WHAT it was asked and how long it waited.
 *
 * <p><b>The contract this pins, and why it is worth a test.</b> A bare
 * {@code SocketTimeoutException} out of the bot's channel names {@code SocketInputStream.read} and
 * nothing else — the identical stack is produced by a loaded box, by a client that died mid-command
 * and by a client wedged by the very behaviour under test, and a reader of the red cannot tell them
 * apart. So the timeout is turned into a diagnosis, and this test is what keeps it one: it fails if
 * the outstanding command stops being named, if the budget stops being printed, or if the warning
 * that the channel is now out of step disappears.</p>
 *
 * <p>This class lives in the harness's own package because the seam it exercises —
 * {@code ClientBot(Socket)} — is package-private, and reaching it through a public factory would
 * mean starting a real client to test a message.</p>
 *
 * <p><b>It is a real socket, not a mock.</b> The point is that the CATCH is wired into the read
 * path; a unit test of the message builder alone would go green with the catch deleted.</p>
 */
public class ClientBotTimeoutDiagnosisTest {

    /** Short, because the test's whole job is to reach the timeout. */
    private static final int READ_BUDGET_MILLIS = 400;

    @Test
    public void aBridgeThatStopsAnsweringNamesTheCommandItWasAsked() throws Exception {
        try (ServerSocket listener = new ServerSocket(0)) {
            listener.setSoTimeout(10_000);
            Thread deafBridge = new Thread(() -> {
                try (Socket accepted = listener.accept()) {
                    OutputStream out = accepted.getOutputStream();
                    out.write("READY\n".getBytes(StandardCharsets.UTF_8));
                    out.flush();
                    // Read the command and answer NOTHING. That is the condition under test.
                    new BufferedReader(new InputStreamReader(accepted.getInputStream(),
                            StandardCharsets.UTF_8)).readLine();
                    Thread.sleep(READ_BUDGET_MILLIS * 6L);
                } catch (IOException | InterruptedException ignored) {
                    // The test's assertions own the verdict; this thread only has to be deaf.
                }
            }, "deaf-client-bridge");
            deafBridge.setDaemon(true);
            deafBridge.start();

            try (Socket socket = new Socket("127.0.0.1", listener.getLocalPort())) {
                ClientBot bot = new ClientBot(socket);
                socket.setSoTimeout(READ_BUDGET_MILLIS);
                try {
                    bot.waitForWorld();
                    fail("a bridge that never answers must not let a command return");
                } catch (IOException diagnosed) {
                    String said = String.valueOf(diagnosed.getMessage());
                    assertTrue("the diagnosis must NAME the outstanding command, or a reader cannot"
                                    + " tell a wedged client from a busy box: " + said,
                            said.contains("wait_world"));
                    assertTrue("the diagnosis must print the budget that expired: " + said,
                            said.contains(String.valueOf(READ_BUDGET_MILLIS)));
                    assertTrue("the diagnosis must say the channel is out of step, because a late"
                                    + " reply would be read as the next command's answer: " + said,
                            said.contains("OUT OF STEP"));
                    assertTrue("the timeout itself must be kept as the cause: " + said,
                            diagnosed.getCause() instanceof java.net.SocketTimeoutException);
                }
            }
        }
    }
}
