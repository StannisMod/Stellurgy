package dev.stannismod.stellurgy.util;

import dev.stannismod.stellurgy.api.satellite.IDataHandler;
import net.minecraft.inventory.IInventory;

public interface IDataInventory extends IInventory, IDataHandler {

    /**
     * stores from external into this
     */
    void loadData(int id);


    /**
     * Stores in external
     *
     * @param id IDataInventory to store data to
     */
    void storeData(int id);
}
