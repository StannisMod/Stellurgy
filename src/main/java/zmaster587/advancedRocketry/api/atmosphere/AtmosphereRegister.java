package zmaster587.advancedRocketry.api.atmosphere;

import net.minecraftforge.fluids.Fluid;
import zmaster587.advancedRocketry.api.IAtmosphere;

import java.util.HashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;

public class AtmosphereRegister {
    private static final AtmosphereRegister instance = new AtmosphereRegister();
    private Map<String, IAtmosphere> atmosphereRegistration;
    private List<Fluid> harvestableAtmosphere;
    private List<IAtmosphere> atmosphereList;
    private AtmosphereRegister() {
        atmosphereRegistration = new HashMap<>();
        atmosphereList = new LinkedList<>();
        harvestableAtmosphere = new LinkedList<>();
    }

    public static AtmosphereRegister getInstance() {
        return instance;
    }

    /**
     * Registers the atmosphere with the mod
     *
     * @param atmosphere atmosphere to register
     */
    public void registerAtmosphere(IAtmosphere atmosphere) {
        atmosphereRegistration.put(atmosphere.getUnlocalizedName(), atmosphere);
        atmosphereList.add(atmosphere);
    }

    /**
     * You should be using unlocalized names for the atmosphere here!
     *
     * @param identifier registered name of the atmosphere
     * @return atmosphere  or AIR if not in the list
     */
    public IAtmosphere getAtmosphere(String identifier) {
        // IF YOU ARRIVED HERE FROM A StackOverflowError, THE REGISTRY IS EMPTY AND THE MOD NEVER
        // FINISHED LOADING. The miss branch asks for "air" — which is put into this very map by a
        // static block in AtmosphereType during normal loading — so on an empty map it calls itself
        // with the same argument until the stack goes, about a thousand frames of this one line.
        //
        // That is left as it is, deliberately. Seen 2026-09-22 in a test client that had died at
        // boot (Forge failed to read a library jar, so no mod init ran), and the crash is a
        // SYMPTOM of that, not a fault here: a process whose initialisation failed has no correct
        // behaviour left for this method to have. Guarding the recursion would replace a loud crash
        // with a quiet wrong answer in a game that is already dead, and would hide the real fault
        // one layer further from where it happened.
        //
        // So the only thing worth having is this note: a stack overflow on this line means "look at
        // why mod loading did not complete", never "the atmosphere lookup is broken".
        IAtmosphere atm = atmosphereRegistration.get(identifier);
        return atm == null ? getAtmosphere("air") : atm;
    }

    public void registerHarvestableFluid(Fluid fluid) {
        harvestableAtmosphere.add(fluid);
    }

    public List<Fluid> getHarvestableGasses() {
        return harvestableAtmosphere;
    }

    /**
     * @return list of all registered atmospheres
     */
    public List<IAtmosphere> getAtmosphereList() {
        return atmosphereList;
    }
}
