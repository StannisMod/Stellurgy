package dev.stannismod.stellurgy.test.unit;

import dev.stannismod.stellurgy.network.PacketSystemBodiesSync;
import dev.stannismod.stellurgy.network.PacketSystemBodiesSync.RenderBody;
import org.junit.Test;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.Assert.*;

/**
 * {@link PacketSystemBodiesSync}'s own decisions about the per-slot-dim render body payload. No MC
 * server/client boot &mdash; {@code executeClient} is never called (it is {@code @SideOnly(Side.CLIENT)} and
 * touches client statics).
 */
public class PacketSystemBodiesSyncTest {

    @Test
    public void dimWithNoBodiesRoundTrip() {
        // A slot dim keyed with an empty body list is a present-but-empty entry, not nothing to send.
        Map<Integer, List<RenderBody>> sent = new LinkedHashMap<>();
        sent.put(9, new ArrayList<RenderBody>());

        PacketSystemBodiesSync packet = PacketSystemBodiesSync.forDims(sent);
        assertFalse("a keyed dim, even empty-listed, is not an empty payload", packet.isEmpty());
    }
}
