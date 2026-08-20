package zmaster587.advancedRocketry.api.atmosphere;

import net.minecraftforge.fluids.Fluid;

import java.util.LinkedList;
import java.util.List;

/**
 * What gas can be taken out of an atmosphere.
 * <p>
 * <b>It used to register ATMOSPHERES as well, by name.</b> That half is gone, because nothing looked
 * one up any more: the detector watches statements about the air rather than named air, the sync
 * packet carries a readout rather than a name, and effects come from a table keyed by the atmosphere
 * object itself. A lookup nobody performs is a place for a second answer to hide.
 * <p>
 * With it goes the extension point that invited a dependent mod to register an atmosphere of its own,
 * and that is deliberate. Air is a composition now, so what a pack adds is a GAS — a registry row and
 * a threshold — and what the air then IS follows from the gas rather than from a name somebody
 * claimed for it.
 * <p>
 * The harvestable-fluid list is a different question that has always lived in this class, and it is
 * what the class is now entirely about. It answers "what can be extracted here", which a composition
 * will answer directly once planets carry one.
 */
public class AtmosphereRegister {
    private static final AtmosphereRegister instance = new AtmosphereRegister();
    private List<Fluid> harvestableAtmosphere;
    private AtmosphereRegister() {
        harvestableAtmosphere = new LinkedList<>();
    }

    public static AtmosphereRegister getInstance() {
        return instance;
    }

    public void registerHarvestableFluid(Fluid fluid) {
        harvestableAtmosphere.add(fluid);
    }

    public List<Fluid> getHarvestableGasses() {
        return harvestableAtmosphere;
    }
}
