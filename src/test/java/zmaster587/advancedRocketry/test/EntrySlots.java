package zmaster587.advancedRocketry.test;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.Assert.assertTrue;

/**
 * Keeping the ships of an entry arrangement's slot worlds load-queued.
 *
 * <p>A headless server has no player standing next to a ship, and an unattended ship unloads. A
 * scenario that waits for one to settle therefore has to keep asking for the load while it waits —
 * work the wait does, not part of what is being waited for.</p>
 *
 * <p><b>Why this is shared rather than copied.</b> Six server-tier classes carried a byte-identical
 * private copy that read exactly TWO dimension ids out of the {@code entry-setup} reply and pumped
 * those. It was correct only while that reply named exactly two, and it failed in the quietest way
 * possible when it did not: the body was {@code if (m.find()) { … }} with no else, so a reply the
 * pattern did not match turned the pump into a no-op that reported nothing. A wait then ran its
 * whole budget against a ship nobody was loading, and the red said "the ship never settled".</p>
 *
 * <p>So this reads EVERY dim the reply names, and refuses when it names none.</p>
 */
public final class EntrySlots {

    private EntrySlots() {
    }

    /** How this helper reaches the probe. The callers sit under three different bases. */
    @FunctionalInterface
    public interface Probe {
        String exec(String command) throws Exception;
    }

    private static final Pattern DIMS = Pattern.compile("\"dims\":\\[([^\\]]*)]");

    /**
     * Queue a ship load in every slot world the {@code entry-setup} reply named.
     *
     * <p>Fails when the reply names no slots at all: that is an arrangement that cannot do what the
     * caller is about to wait for, and it is worth one loud line here instead of a budget spent
     * silently somewhere downstream.</p>
     */
    public static void loadAll(Probe probe, String entrySetupReply) throws Exception {
        for (String dim : dimsOf(entrySetupReply)) {
            probe.exec("artest vs load-ships " + dim);
        }
    }

    /** The slot dimension ids an {@code entry-setup} reply reported, in the order it reported them. */
    public static List<String> dimsOf(String entrySetupReply) {
        Matcher m = DIMS.matcher(String.valueOf(entrySetupReply));
        assertTrue("the entry arrangement named no slot worlds, so nothing can be kept loaded and"
                + " every wait below would run its whole budget against a ship nobody is loading: "
                + entrySetupReply, m.find());
        List<String> dims = new ArrayList<>();
        for (String raw : m.group(1).split(",")) {
            String dim = raw.trim();
            if (!dim.isEmpty()) {
                dims.add(dim);
            }
        }
        assertTrue("the entry arrangement reported an EMPTY slot list: " + entrySetupReply,
                !dims.isEmpty());
        return dims;
    }
}
