package dev.stannismod.stellurgy.satellite;

import dev.stannismod.stellurgy.api.DataStorage;
import dev.stannismod.stellurgy.api.satellite.InstrumentSubject;
import dev.stannismod.stellurgy.api.satellite.SatelliteBase;
import dev.stannismod.stellurgy.libvulpes.LibVulpes;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;

import java.util.List;
import java.util.Optional;

/**
 * A satellite whose primary function is a dosimeter: it reads the dose rate at its point once per segment
 * of its orbit, with the tick and the place along the orbit, while its electronics work and its power is
 * covered. Over a body with no field it reads "absent here".
 */
public class SatelliteDosimeter extends SatelliteBase {

    /** What the dosimeter draws per tick while it reads: half a data instrument's, being a single detector. */
    public static final double DRAW_PER_TICK = 1d;

    private final DoseReadings readings = new DoseReadings();

    @Override
    public SatelliteAccrual.Payload payload() {
        return new SatelliteAccrual.Payload(false, true, 0d, DRAW_PER_TICK);
    }

    @Override
    public DoseReadings doseReadings() {
        return readings;
    }

    @Override
    public Optional<InstrumentSubject> instrumentSubject() {
        return DataStorage.DataType.RADIATION_DOSE_RATE.subject();
    }

    @Override
    public String getInfo(World world) {
        bringUpToNow();
        List<DoseReadings.Reading> held = readings.readings();
        if (held.isEmpty()) {
            return "Readings: 0";
        }
        DoseReadings.Reading last = held.get(held.size() - 1);
        return "Readings: " + held.size() + "\nLast: "
                + (last.gyPerS().isPresent() ? String.format("%.3g Gy/s", last.gyPerS().getAsDouble()) : "absent here");
    }

    @Override
    public String getName() {
        return LibVulpes.proxy.getLocalizedString("item.satellite.dosimeter");
    }

    @Override
    public boolean performAction(EntityPlayer player, World world, BlockPos pos) {
        return false;
    }

    @Override
    public void writeToNBT(NBTTagCompound nbt) {
        super.writeToNBT(nbt);
        NBTTagCompound readingsNBT = new NBTTagCompound();
        readings.writeToNBT(readingsNBT);
        nbt.setTag("readings", readingsNBT);
    }

    @Override
    public void readFromNBT(NBTTagCompound nbt) {
        super.readFromNBT(nbt);
        readings.readFromNBT(nbt.getCompoundTag("readings"));
    }
}
