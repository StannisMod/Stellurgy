package zmaster587.advancedRocketry.test.trace;

import java.util.HashMap;
import java.util.Map;

import zmaster587.advancedRocketry.space.CrewTransfer;

/**
 * What the crew-transfer recorders last wrote, so they write only on a CHANGE — the server's, kept in
 * its {@link SideTrace}. Both are asked every tick while a re-seat is refused or a rebind is pending,
 * and a record per tick would bury the edge a chain reads.
 *
 * <p>Test source set: absent from a released jar.</p>
 */
public final class CrewTransferMemory {

    /** {@code dim:ship:vsShip} → the last {@code block} recorded for a refused re-seat. */
    public final Map<String, String> lastReseatBlock = new HashMap<>();

    /** {@code who:staleDummy:anchor} → the last outcome recorded for a pending rebind. */
    public final Map<String, CrewTransfer.RebindOutcome> lastRebindOutcome = new HashMap<>();

    /** The memory of the server this thread runs. */
    public static CrewTransferMemory here() {
        return SideTrace.here().memory(CrewTransferMemory.class, CrewTransferMemory::new);
    }
}
