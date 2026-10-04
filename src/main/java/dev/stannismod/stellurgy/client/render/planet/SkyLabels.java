package dev.stannismod.stellurgy.client.render.planet;

import net.minecraft.world.World;

import dev.stannismod.stellurgy.api.StellurgyConfiguration;
import dev.stannismod.stellurgy.world.WorldRuntime;

/**
 * Whether the cell sky writes a body's name and distance beside it.
 *
 * <p>The label is a diagnostic first and a player affordance second: it is how a human confirms that
 * a body really is receding, without a probe. It defaults ON for that reason.</p>
 *
 * <p>Two switches, and they are not the same switch:</p>
 * <ul>
 *   <li>the {@code skyBodyLabels} CONFIG flag — a hard disable. Off means no label is drawn anywhere,
 *       ever, by anyone: a flag has to REMOVE the thing it names rather than dim it, or a player who
 *       turned it off is still looking at what he turned off.</li>
 *   <li>the per-console toggle — a pilot's preference, carried on the navigation computer's own
 *       synced state and applied CLIENT-side. Nothing new goes on the wire for it: the console
 *       already ships its state to the clients that can see it.</li>
 * </ul>
 *
 * <p>The toggle is held by the client WORLD the console synced into ({@link WorldRuntime}), so it
 * ends with that world: a disconnect, a dimension change or a new server starts from the default
 * rather than from whatever the last console of some other world said.</p>
 *
 * <p><b>The known limit, stated rather than hidden:</b> the render decision is per-world while the
 * console is per-SHIP, so in a cell holding two ships the last console to update wins for everyone in
 * that world. And the sky belongs to the cell rather than to a ship, so its audience is wider than any
 * one console's crew — a passenger, a crew member who walked off the hull, a tier-1 craft — and those
 * own no navigation computer at all, so for them the default is the only setting there is. That cost
 * is accepted deliberately; a per-player channel is a bigger change than the affordance is worth
 * today.</p>
 */
public final class SkyLabels {

    /** The console toggle as last synced into one client world. */
    private static final class ConsoleToggle {
        boolean enabled = true;
    }

    private SkyLabels() {
    }

    /** Whether a label may be drawn at all in {@code world} — the config flag AND the console toggle. */
    public static boolean enabled(World world) {
        StellurgyConfiguration cfg = StellurgyConfiguration.getCurrentConfig();
        return (cfg == null || cfg.skyBodyLabels) && toggleOf(world).enabled;
    }

    /** Apply a navigation computer's toggle to the client world it synced into. */
    public static void setConsoleEnabled(World world, boolean enabled) {
        toggleOf(world).enabled = enabled;
    }

    private static ConsoleToggle toggleOf(World world) {
        return WorldRuntime.of(world, ConsoleToggle.class, ConsoleToggle::new);
    }
}
