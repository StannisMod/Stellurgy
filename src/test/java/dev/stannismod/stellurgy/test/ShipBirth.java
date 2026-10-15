package dev.stannismod.stellurgy.test;

import java.util.Collections;
import java.util.List;

/**
 * The ship ONE assembler press made, read off production's own records of that press: the durable
 * name the press minted (its reply's {@code shipId}), and the physics id the substrate announced when
 * it named the craft ({@code ship_lifecycle}, cause {@code ASSEMBLED}).
 *
 * <p>Beside them, the {@code rocket_born} records the same assembler wrote in the window — an
 * AFC-bearing build routes to a ship and NOT to a rocket entity, and that is read off the press's own
 * records, never off a count of what stands in the world. Whether that list must be empty is the
 * caller's to assert or to require. The physics id is what {@link Disposal#ship} takes, so a scenario
 * holding this disposes of its own craft by its own id.</p>
 */
public final class ShipBirth {

    /** The world the press built in, and the craft's physics registration lives in. */
    public final int dim;
    /** The durable name the press minted — the one identity a crossing preserves. */
    public final String durable;
    /** The substrate's id for the craft as its {@code ASSEMBLED} naming announced it. */
    public final String physicsId;
    /** The press's reply, for the fields a scenario reads beyond the identity. */
    public final Reply press;
    /** Every {@code rocket_born} the press's assembler wrote since the mark. */
    public final List<String> rocketsBorn;

    private ShipBirth(int dim, String durable, String physicsId, Reply press, List<String> rocketsBorn) {
        this.dim = dim;
        this.durable = durable;
        this.physicsId = physicsId;
        this.press = press;
        this.rocketsBorn = Collections.unmodifiableList(rocketsBorn);
    }

    /**
     * The ship the press at {@code builderPos} in {@code dim} made since {@code mark} — a mark on the
     * server log taken BEFORE the press.
     *
     * <p>Fails when the reply names no single flight computer, and when no {@code ASSEMBLED} naming of
     * the minted name in {@code dim} arrives in {@code linkTicks}.</p>
     *
     * @param what the scenario's sentence for what the ship is for, printed in every refusal
     */
    public static ShipBirth ofPress(Events events, long mark, int dim, int[] builderPos, String pressReply,
                                    int linkTicks, String what) throws Exception {
        List<String> rockets = RocketFixture.rocketsBornAt(events, mark, builderPos);
        Reply press = Reply.of("stellurgytest rocket assemble", String.valueOf(pressReply));
        String durable = ShipIdentity.nameFromAssembly(pressReply);
        String named = events.awaitRecordWithFields(mark, "ship_lifecycle",
                what + " — the press must name the ship it assembled (" + durable + "); rockets its"
                        + " assembler made in the window: " + rockets, linkTicks,
                "durable", durable, "cause", "ASSEMBLED", "dim", String.valueOf(dim));
        return new ShipBirth(dim, durable, Events.text(named, "ship"), press, rockets);
    }

    /**
     * Return once this craft is USABLE in its world — linked on its own {@code ship_usable}
     * ({@link ShipIdentity#awaitPhysicsIdOf}) — answering its physics id as that read resolves it.
     */
    public String awaitUsable(ShipIdentity.Probe probe, Events events, int linkTicks) throws Exception {
        return ShipIdentity.awaitPhysicsIdOf(probe, events, dim, durable, linkTicks);
    }

    @Override
    public String toString() {
        return "ShipBirth[" + durable + " physics=" + physicsId + " dim=" + dim
                + " rocketsBorn=" + rocketsBorn + "]";
    }
}
