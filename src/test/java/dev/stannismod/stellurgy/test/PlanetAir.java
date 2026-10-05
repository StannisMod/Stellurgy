package dev.stannismod.stellurgy.test;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.util.Map;

/**
 * A planet's outdoor air, taken and put back by its COMPOSITION.
 *
 * <p>A planet's air is a set of gases, and its pressure is their sum. Setting the pressure to zero
 * removes every gas, and setting it back cannot say which gases to return — the planet refuses that,
 * because scaling an empty mix has nothing to scale. So a test that empties a world's air restores it
 * here: the snapshot records each gas and its amount, and the restore empties the air and puts each one
 * back through the planet's own gas exchange, exactly.</p>
 */
public final class PlanetAir {

    /** Whatever runs a probe command and answers its reply lines — each harness has its own. */
    public interface Probe {
        String run(String command) throws Exception;
    }

    private final int dim;
    private final JsonObject gases;

    private PlanetAir(int dim, JsonObject gases) {
        this.dim = dim;
        this.gases = gases;
    }

    /** The composition {@code dim} holds now, from {@code planet info}; refuses when it reports none. */
    public static PlanetAir snapshot(Probe probe, int dim) throws Exception {
        String command = "stellurgytest planet info " + dim;
        Reply info = Reply.of(command, probe.run(command));
        String composition = info.object("gases");
        if (composition == null) {
            throw new AssertionError(command + " reports no composition: " + info);
        }
        return new PlanetAir(dim, new JsonParser().parse(composition).getAsJsonObject());
    }

    /**
     * Put the snapshot back: empty the air, then add each recorded gas, refusing on the first step the
     * planet declines — a restore that silently failed would hand the next test a vacuum.
     */
    public void restore(Probe probe) throws Exception {
        require(probe, "stellurgytest atmosphere set-density " + dim + " 0");
        for (Map.Entry<String, JsonElement> gas : gases.entrySet()) {
            require(probe, "stellurgytest planet add-gas " + dim + " " + gas.getKey() + " "
                    + gas.getValue().getAsLong());
        }
    }

    private static void require(Probe probe, String command) throws Exception {
        Reply.of(command, probe.run(command)).requireOk(command);
    }
}
