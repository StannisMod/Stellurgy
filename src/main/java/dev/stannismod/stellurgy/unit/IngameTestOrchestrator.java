package dev.stannismod.stellurgy.unit;

import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.util.text.TextComponentString;
import net.minecraft.world.World;
import dev.stannismod.stellurgy.Stellurgy;

import java.lang.reflect.Method;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.Map.Entry;

/**
 * The steps of {@code /ar dev runtests} still due, and who started the run. One per server
 * ({@code ServerState}), so a step left pending when a server stops never fires in the next one.
 */
public class IngameTestOrchestrator {

    private final Map<Long, PlayerMapping> eventScheduler = new HashMap<>();
    /** The player who started the run, so a scheduled step can find him in whichever world he is in. */
    private String name;

    public boolean runTests(World world, EntityPlayer player) {
        name = player.getName();
        BuildRocketTest buildRocketTest = new BuildRocketTest();
        try {
            scheduleEvent(world, 1, BuildRocketTest.class.getDeclaredMethod("Phase1", World.class, EntityPlayer.class), buildRocketTest);
        } catch (Exception e) {
            e.printStackTrace();
        }

        return true;
    }

    public void scheduleEvent(World world, long numTicks, Method function, BaseTest test) {
        eventScheduler.put(world.getTotalWorldTime() + numTicks, new PlayerMapping(world, function, test));
    }

    private EntityPlayer getPlayerFromAnywhere() {
        EntityPlayer player = null;
        for (World world : net.minecraftforge.common.DimensionManager.getWorlds()) {
            player = world.getPlayerEntityByName(name);
            if (player != null) break;
        }

        return player;
    }

    /** One tick of a server world: run every step that has come due by its clock. */
    public void onWorldTick(World tickingWorld) {
        if (eventScheduler.isEmpty()) {
            return;
        }
        Iterator<Entry<Long, PlayerMapping>> itr = eventScheduler.entrySet().iterator();
        while (itr.hasNext()) {
            Entry<Long, PlayerMapping> e = itr.next();
            if (tickingWorld.getTotalWorldTime() >= e.getKey()) {
                itr.remove();
                BaseTest test = e.getValue().test;
                try {
                    e.getValue().func.invoke(test, e.getValue().world, getPlayerFromAnywhere());
                } catch (AssertionError e1) {
                    Stellurgy.logger.error("Test Failed!!!");
                    Stellurgy.logger.catching(e1);
                    getPlayerFromAnywhere().sendMessage(new TextComponentString(test.getName() + " Failed!"));
                } catch (Exception e2) {
                    e2.printStackTrace();
                }

                if (test.passed()) {
                    getPlayerFromAnywhere().sendMessage(new TextComponentString(test.getName() + " Passed!"));
                }
            }
        }
    }

    private static class PlayerMapping {
        public Method func;
        public World world;
        public BaseTest test;
        PlayerMapping(World world, Method func, BaseTest test) {
            this.func = func;
            this.world = world;
            this.test = test;
        }
    }
}
