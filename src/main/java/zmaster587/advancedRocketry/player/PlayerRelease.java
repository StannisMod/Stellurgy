package zmaster587.advancedRocketry.player;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import net.minecraft.entity.player.EntityPlayer;

import zmaster587.advancedRocketry.atmosphere.AtmosphereHandler;
import zmaster587.advancedRocketry.atmosphere.RocketTransferGrace;
import zmaster587.advancedRocketry.integration.vs.DeckHold;
import zmaster587.advancedRocketry.space.HyperspaceVoid;
import zmaster587.advancedRocketry.space.ShipAboardTag;
import zmaster587.advancedRocketry.space.SpaceEventHandler;

/**
 * What a player is bound to, and how he is returned to the plain world.
 *
 * <h2>Why this is a direct call and not an event</h2>
 *
 * <p>The first version was a Forge event posted on the bus, and it was the wrong primitive. A Forge
 * event is a STATEMENT ABOUT THE WORLD — either "this happened" or "this is about to happen and you
 * may veto it". This operation is neither: it asks handlers to do work the caller then depends on.
 * Three things follow, none cosmetic: bus dispatch gives no ORDER over the mutations; nothing makes
 * a binding owner subscribe, so a forgotten one is silent in the very mechanism built to end
 * silence; and a handler that throws leaves the operation half done with nobody to decide what that
 * means. A bus is also OPEN — any mod could subscribe to an internal invariant of this one.</p>
 *
 * <h2>Asking and releasing are ONE list</h2>
 *
 * <p>{@link #boundTo} and {@link #toTheWorld} walk the same {@link #bindings} in the same order.
 * That is deliberate and is the whole reason this class has any structure at all: two methods each
 * with their own enumeration of the bindings would be two lists that can disagree, and "what he is
 * bound to" disagreeing with "what was released" is precisely the bug class this exists to close.</p>
 *
 * <h2>THIS IS NOT LOGOUT, and the difference is the point</h2>
 *
 * <p>A logout PRESERVES a player's bindings: the aboard record is durable precisely so login restore
 * can put him back on his ship. A release DESTROYS them. Opposite intent, so the logout handlers
 * stay where they are and none of them routes here.</p>
 *
 * <h2>What it does NOT do</h2>
 *
 * <p>It does not move him and it does not dismount him. Where the body should END UP differs by
 * situation — a spawn point, a bed, a rescue beacon — and a ridden mount is the player's own field
 * rather than state a subsystem is holding for him. This undoes what the MOD is holding; the caller
 * places the body.</p>
 */
public final class PlayerRelease {

    /**
     * One thing the mod can be holding on a player: what it is called, how to ask, how to let go.
     *
     * <p>Private, and it stays private: this is the shape of the LIST, not a registration point. A
     * binding owner is wired in below, in code, where the compiler sees it — the alternative is a
     * runtime registry whose completeness nothing checks, which is what a bus already was.</p>
     */
    private interface Binding {
        /** Named in the terms its own subsystem uses, because that is where a reader will go next. */
        String name();

        boolean isBound(EntityPlayer player);

        /** @return whether anything was actually let go */
        boolean release(EntityPlayer player);
    }

    private final List<Binding> bindings;

    /**
     * @param space the owner of the login cell claim and of queued seatings
     * @param hyperspace the owner of the per-player adrift run
     */
    public PlayerRelease(SpaceEventHandler space, HyperspaceVoid hyperspace) {
        if (space == null || hyperspace == null) {
            throw new IllegalArgumentException("a release with a missing owner would report a SHORTER"
                    + " list rather than fail, which is the one outcome this mechanism exists to"
                    + " prevent");
        }
        this.bindings = Collections.unmodifiableList(Arrays.<Binding>asList(
                // The deck hold first: it is what makes a body move with a hull, so anything below
                // that takes time is time he is no longer being dragged for.
                new Binding() {
                    public String name() {
                        return "deck hold";
                    }

                    public boolean isBound(EntityPlayer player) {
                        return DeckHold.isHeld(player);
                    }

                    public boolean release(EntityPlayer player) {
                        return DeckHold.releaseHold(player);
                    }
                },
                new Binding() {
                    public String name() {
                        return "aboard record";
                    }

                    public boolean isBound(EntityPlayer player) {
                        return ShipAboardTag.of(player) != null;
                    }

                    public boolean release(EntityPlayer player) {
                        if (ShipAboardTag.of(player) == null) {
                            return false;
                        }
                        ShipAboardTag.clear(player);
                        return true;
                    }
                },
                new Binding() {
                    public String name() {
                        return "cell claim";
                    }

                    public boolean isBound(EntityPlayer player) {
                        return space.holdsCellClaimFor(player);
                    }

                    public boolean release(EntityPlayer player) {
                        return space.releaseCellClaim(player);
                    }
                },
                new Binding() {
                    public String name() {
                        return "queued seating";
                    }

                    public boolean isBound(EntityPlayer player) {
                        return space.hasQueuedSeating(player);
                    }

                    public boolean release(EntityPlayer player) {
                        return space.releaseQueuedSeating(player);
                    }
                },
                new Binding() {
                    public String name() {
                        return "hyperspace drift";
                    }

                    public boolean isBound(EntityPlayer player) {
                        return hyperspace.isDrifting(player);
                    }

                    public boolean release(EntityPlayer player) {
                        return hyperspace.releaseDrift(player);
                    }
                },
                new Binding() {
                    public String name() {
                        return "rocket transfer grace";
                    }

                    public boolean isBound(EntityPlayer player) {
                        return RocketTransferGrace.isActive(player, player.world.getTotalWorldTime());
                    }

                    public boolean release(EntityPlayer player) {
                        return AtmosphereHandler.releasePlayer(player);
                    }
                }));
    }

    /**
     * WHAT IS THIS PLAYER BOUND TO — the question that had no answer anywhere in the mod before this
     * class, which is how a record survived into a scenario that asserted its absence.
     *
     * <p>An empty list means he is bound to nothing and is a legitimate answer. Reading it changes
     * nothing: a witness with side effects would be measuring its own footprint.</p>
     */
    public List<String> boundTo(EntityPlayer player) {
        requirePlayer(player);
        List<String> bound = new ArrayList<>();
        for (Binding binding : bindings) {
            if (binding.isBound(player)) {
                bound.add(binding.name());
            }
        }
        return bound;
    }

    /**
     * Let go of everything the mod is holding on {@code player}, and answer with what was let go.
     *
     * <p>The answer is what each owner REPORTED releasing, not what {@link #boundTo} said a moment
     * earlier — so an owner that claims to hold something and then releases nothing shows up as a
     * disagreement between the two rather than being papered over by one of them.</p>
     *
     * <p><b>The order is fixed but is not load-bearing, and that is a measurement rather than a
     * promise</b>: no dependency between these was found when this was written. It is fixed anyway,
     * because an unordered sequence of mutations is a thing nobody can reason about afterwards, and
     * because the day one of them does depend on another the order will be here to change rather
     * than to discover.</p>
     */
    public List<String> toTheWorld(EntityPlayer player) {
        requirePlayer(player);
        List<String> released = new ArrayList<>();
        for (Binding binding : bindings) {
            if (binding.release(player)) {
                released.add(binding.name());
            }
        }
        return released;
    }

    private static void requirePlayer(EntityPlayer player) {
        if (player == null) {
            throw new IllegalArgumentException("a release needs a player; a null one would make the"
                    + " empty report mean 'bound to nothing' instead of 'nobody was asked'");
        }
    }
}
