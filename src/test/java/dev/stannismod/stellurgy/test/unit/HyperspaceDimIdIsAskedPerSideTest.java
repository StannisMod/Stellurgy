package dev.stannismod.stellurgy.test.unit;

import static org.junit.Assert.assertFalse;

import org.junit.Test;

import dev.stannismod.stellurgy.space.HyperspaceWorld;

/**
 * <b>Which dimension hyperspace is has two answers, and they are not interchangeable.</b> The server
 * knows because it registered the world; a client only ever knows because it was told, and the told
 * value lives in the client's view of the server it is connected to — a different object from the
 * server's registration, so one can no longer be served as the other.
 *
 * <p>The client's half — that a reader on a remote world picks the told value — is observable ONLY
 * through a world that reports itself remote, so it belongs to a client test and is deliberately not
 * faked here.</p>
 */
public class HyperspaceDimIdIsAskedPerSideTest {

    @Test
    public void nothingIsHyperspaceWhenThereIsNoWorldToAskAbout() {
        assertFalse("a question about no world has one honest answer, and it is not a crash",
                HyperspaceWorld.isHyperspace(null));
    }
}
