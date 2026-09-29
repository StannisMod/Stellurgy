package zmaster587.advancedRocketry.player;

import java.util.List;

import zmaster587.advancedRocketry.space.ShipAboardTag;

/**
 * The DURABLE facts this mod holds about one player: what he is aboard, and the one window he is
 * inside. One object, attached to the player, written with him and carried across his death.
 *
 * <h2>Why one home</h2>
 *
 * <p>These lived in three unrelated places — a hand-rolled NBT compound, a private static map and an
 * instance field on a mod-lifetime event handler. Nothing could answer "what is this player bound
 * to", nothing could let him go, and none of the lifetimes was the SERVER's or the CLIENT's, the
 * only two that own state in this game. A record survived into a scenario that asserted its absence
 * precisely because no place existed for the question.</p>
 *
 * <h2>Why only TWO things are here</h2>
 *
 * <p>Maintainer ruling 2026-09-14: a binding is a fact about the PLAYER and survives what he
 * survives — a logout and a death alike. Applying that criterion is what selects the contents, and
 * it EXCLUDES three of the five things the mod holds per player:</p>
 *
 * <ul>
 *   <li>the <b>deck hold</b> is the running form of "I am standing on that deck", whose durable form
 *       IS the aboard record below. Storing both would be two records of one truth, which
 *       {@code C13/PRES-6} forbids and which this mod has already paid for once;</li>
 *   <li>the <b>cell claim</b> is a refcount HANDLE on a slot world. Slot dimension ids are re-minted
 *       every boot, so a restored claim would name nothing and giving it back would decrement a
 *       count nobody took;</li>
 *   <li>the <b>hyperspace adrift run</b>, ruled out separately on 2026-09-15 — see the note below
 *       the accessors.</li>
 * </ul>
 *
 * <p>All three remain live state owned by their own subsystems, and a release still lets go of them
 * — being live is not being unimportant.</p>
 *
 * <p>Server side: all of it is server state.</p>
 */
public interface IPlayerBindings {

    /** The record of which ship he is aboard and where on it, or {@code null} when he is not. */
    ShipAboardTag.Aboard aboard();

    void setAboard(ShipAboardTag.Aboard aboard);

    /**
     * World time at which the post-transfer suit-check window expires, or {@code 0} for none.
     *
     * <p>Stored as an absolute deadline rather than a countdown so that it expires by itself while
     * he is offline, which is what makes persisting it harmless.</p>
     */
    long graceUntil();

    void setGraceUntil(long worldTime);

    // THE HYPERSPACE ADRIFT RUN IS DELIBERATELY NOT HERE. Maintainer ruling 2026-09-15, after the
    // question was put with both arguments: it is NOT persisted and stays a live map in
    // HyperspaceVoid. The case for moving it was that losing the count on a relog lets a crew member
    // the void is counting down to killing reset it by disconnecting — but the countdown is 200
    // ticks, so a relog buys ten seconds of falling in the same void, which postpones rather than
    // saves. Against it stands the reason already written at HyperspaceVoid.pruneDeparted: a
    // returning player is placed by the login restore, *a fresh judgement, not a continuation*, so
    // resuming his countdown asserts something about a situation he may no longer be in.
    //
    // No accessors are left behind for it. A pair of unused methods "so the decision has somewhere
    // to land" is a public surface carrying a maybe, and the decision has landed.

    /** What of THIS object is set, named as each subsystem names it. Reading changes nothing. */
    List<String> boundTo(long worldTime);

    /** Clear all of it, answering with what was actually found and dropped. */
    List<String> releaseAll(long worldTime);
}
