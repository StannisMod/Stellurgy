package dev.stannismod.stellurgy.client;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import net.minecraft.client.resources.I18n;
import net.minecraftforge.fml.relauncher.Side;
import net.minecraftforge.fml.relauncher.SideOnly;

import dev.stannismod.stellurgy.ship.control.ControlDirection;
import dev.stannismod.stellurgy.ship.control.Endurance;
import dev.stannismod.stellurgy.ship.control.ShipReadout;
import dev.stannismod.stellurgy.ship.control.ShipReadout.View;

/**
 * The words a ship's readout is drawn in: the full console, and the flight slice the pilot's HUD
 * carries. Every figure comes from {@link ShipReadout}; this only formats.
 */
@SideOnly(Side.CLIENT)
public final class ShipReadoutText {

    private ShipReadoutText() {}

    /** The console: masses, thrust to weight, every direction DESIGN beside LIVE, then the warnings. */
    public static List<String> console(ShipReadout r) {
        List<String> lines = new ArrayList<>();
        if (r == null) {
            lines.add(I18n.format("msg.ship.readout.none"));
            return lines;
        }
        lines.add(I18n.format("msg.ship.readout.mass", tonnes(r.totalMass()), tonnes(r.structuralMass()),
                tonnes(r.contentMass()), tonnes(r.crewMass())));
        lines.add(twrLine(r, View.LIVE));
        lines.add(I18n.format("msg.ship.readout.header"));
        for (ControlDirection d : ControlDirection.values()) {
            lines.add(I18n.format("msg.ship.readout.direction", directionName(d),
                    figure(r, View.DESIGN, d), figure(r, View.LIVE, d)));
        }
        for (ControlDirection d : ControlDirection.values()) {
            ShipReadout.Warning w = r.warningFor(View.LIVE, d);
            if (w == ShipReadout.Warning.NO_AUTHORITY) {
                lines.add(I18n.format("msg.ship.warn.no_authority", directionName(d)));
            } else if (w == ShipReadout.Warning.BURST_ONLY) {
                lines.add(I18n.format("msg.ship.warn.burst_only", directionName(d),
                        num(r.burstSeconds(View.LIVE, d))));
            }
        }
        if (!r.canHover(View.LIVE)) {
            lines.add(I18n.format("msg.ship.warn.cannot_hover", num(r.thrustToWeight(View.LIVE))));
        }
        return lines;
    }

    /** The pilot's slice: mass and thrust to weight, forward and reverse acceleration, the limits. */
    public static List<String> hud(ShipReadout r, boolean saturated, double wheelFill) {
        List<String> lines = new ArrayList<>();
        if (r == null) {
            return lines;
        }
        lines.add(I18n.format("msg.ship.hud.mass", tonnes(r.totalMass()), twr(r, View.LIVE)));
        lines.add(I18n.format("msg.ship.hud.accel",
                num(r.acceleration(View.LIVE, ControlDirection.SURGE_POSITIVE, Endurance.SUSTAINED)),
                num(r.acceleration(View.LIVE, ControlDirection.SURGE_NEGATIVE, Endurance.SUSTAINED))));
        if (saturated) {
            lines.add(I18n.format("msg.ship.hud.saturated"));
        }
        if (wheelFill > 0.0D) {
            lines.add(I18n.format("msg.ship.hud.wheel", String.format(Locale.ROOT, "%.0f", wheelFill * 100.0D)));
        }
        return lines;
    }

    private static String twrLine(ShipReadout r, View view) {
        if (!(r.gravity() > 0.0D)) {
            return I18n.format("msg.ship.readout.nofield");
        }
        return I18n.format("msg.ship.readout.twr", twr(r, view), num(r.gravity()));
    }

    private static String twr(ShipReadout r, View view) {
        double t = r.thrustToWeight(view);
        return Double.isInfinite(t) ? "∞" : num(t);
    }

    private static String figure(ShipReadout r, View view, ControlDirection d) {
        double sustained = r.authority(view, d, Endurance.SUSTAINED);
        double burst = r.authority(view, d, Endurance.BURST);
        String shown;
        if (d.axis().isRotation()) {
            shown = num(sustained > 0.0D ? sustained : burst) + " rad/s²";
        } else {
            shown = String.format(Locale.ROOT, "%.0f kN", (sustained > 0.0D ? sustained : burst) / 1000.0D)
                    + " / " + num(r.acceleration(view, d, sustained > 0.0D ? Endurance.SUSTAINED
                    : Endurance.BURST)) + " m/s²";
        }
        if (burst > sustained && !(sustained > 0.0D)) {
            shown += " (" + num(r.burstSeconds(view, d)) + " s)";
        }
        return shown;
    }

    private static String directionName(ControlDirection d) {
        return I18n.format("msg.ship.dir." + d.name().toLowerCase(Locale.ROOT));
    }

    private static String tonnes(double kg) {
        return String.format(Locale.ROOT, "%.1f", kg / 1000.0D);
    }

    private static String num(double v) {
        return String.format(Locale.ROOT, "%.2f", v);
    }
}
