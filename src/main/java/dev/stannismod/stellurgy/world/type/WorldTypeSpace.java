package dev.stannismod.stellurgy.world.type;

import net.minecraft.world.WorldType;

public class WorldTypeSpace extends WorldType {

    public WorldTypeSpace(String string) {
        super(string);
    }


    @Override
    public boolean canBeCreated() {
        return false;
    }
}
