package dev.stannismod.stellurgy.test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.Assert.assertTrue;

/**
 * The slot worlds an entry arrangement's reply names — every one of them, refusing when it names none.
 *
 * <p>Six server-tier classes once carried a private copy that read exactly TWO dimension ids out of
 * the {@code entry-setup} reply, behind an {@code if (m.find())} with no else: a reply the pattern did
 * not match read as no slots at all and said nothing.</p>
 *
 * <p>It used to load every ship of those worlds as well. That is gone with every other world-wide
 * ship operation (2026-10-14): a scenario loads its own ship by its id ({@code vs load-ship}).</p>
 */
public final class EntrySlots {

    private EntrySlots() {
    }

    /** How this helper reaches the probe. The callers sit under three different bases. */
    @FunctionalInterface
    public interface Probe {
        String exec(String command) throws Exception;
    }

    private static final String DIMS = "dims";

    /** The slot dimension ids an {@code entry-setup} reply reported, in the order it reported them. */
    public static List<String> dimsOf(String entrySetupReply) {
        Reply setup = Reply.of("stellurgytest space entry-setup", String.valueOf(entrySetupReply));
        assertTrue("the entry arrangement named no slot worlds, so nothing can be kept loaded and"
                + " every wait below would run its whole budget against a ship nobody is loading: "
                + entrySetupReply, setup.has(DIMS));
        List<String> dims = new ArrayList<>();
        for (int dim : setup.intArray(DIMS)) {
            dims.add(String.valueOf(dim));
        }
        assertTrue("the entry arrangement reported an EMPTY slot list: " + entrySetupReply,
                !dims.isEmpty());
        return dims;
    }
}
