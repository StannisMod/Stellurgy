package dev.stannismod.stellurgy.test.mixin;

import dev.stannismod.stellurgy.entity.EntityRocket;
import dev.stannismod.stellurgy.mission.MissionGasCollection;
import dev.stannismod.stellurgy.mission.MissionOreMining;
import dev.stannismod.stellurgy.mission.MissionResourceCollection;
import dev.stannismod.stellurgy.test.trace.TestTrace;
import dev.stannismod.stellurgy.tile.TileRocketAssemblingMachine;
import dev.stannismod.stellurgy.tile.TileUnmannedVehicleAssembler;
import net.minecraft.entity.Entity;
import net.minecraft.util.math.BlockPos;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyArg;

/**
 * {@code rocket_born} — a rocket entity was put into a world by the production code that MADE it.
 *
 * <p>Taken at the one {@code World.spawnEntity} call of each maker, as the argument passes: the rocket
 * assembler's and the unmanned assembler's {@code assembleRocket()}, and a resource mission's
 * {@code onMissionComplete()}, which returns its craft to the launch site. A maker is the only place a
 * rocket's identity and its cause are both in hand, so this is how a test learns the id of the rocket
 * its own press or its own mission produced — keyed on the maker it built ({@code pos}) or the mission
 * it started ({@code mission}) — and how many rockets one act produced: the records, never a count of
 * entities in the world. {@code e} is the entity id, {@code uuid} the persistent one, {@code by} the
 * maker's class.</p>
 *
 * <p>Recorded before the spawn decides: a spawn the world declines (an unloaded chunk) still records,
 * and the record then names an entity the world never took. Every maker here spawns into the chunk of
 * its own block or launch site, which its caller is standing in.</p>
 *
 * <p>SILENT about every other way a rocket enters a world: a chunk or a save loading one (not a birth),
 * a dimension change (the travelling copy), the station assembler (its own {@code assembleRocket()}
 * makes a station), and the probe's fixtures, which answer the id they spawned in their own reply.</p>
 */
@Mixin({TileRocketAssemblingMachine.class, TileUnmannedVehicleAssembler.class,
        MissionGasCollection.class, MissionOreMining.class})
public abstract class MixinRocketBirthEvents {

    private static final String INSTRUMENT = "rocket_birth";

    @ModifyArg(method = {"assembleRocket", "onMissionComplete"},
            at = @At(value = "INVOKE",
                    target = "Lnet/minecraft/world/World;spawnEntity(Lnet/minecraft/entity/Entity;)Z"),
            index = 0,
            require = 1)
    private Entity stellurgyTest$rocketBorn(Entity spawned) {
        TestTrace.instrument(spawned, INSTRUMENT);
        if (!(spawned instanceof EntityRocket) || spawned.world == null) {
            return spawned;
        }
        Object maker = this;
        String origin;
        if (maker instanceof TileRocketAssemblingMachine) {
            BlockPos at = ((TileRocketAssemblingMachine) maker).getPos();
            origin = ",\"pos\":\"" + at.getX() + "," + at.getY() + "," + at.getZ() + "\"";
        } else {
            origin = ",\"mission\":" + ((MissionResourceCollection) maker).getMissionId();
        }
        TestTrace.record(spawned, "rocket_born", "\"e\":" + spawned.getEntityId()
                + ",\"uuid\":\"" + spawned.getUniqueID() + "\""
                + ",\"dim\":" + spawned.world.provider.getDimension()
                + ",\"by\":\"" + maker.getClass().getSimpleName() + "\""
                + origin);
        return spawned;
    }
}
