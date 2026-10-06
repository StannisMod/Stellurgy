package dev.stannismod.stellurgy.test.client;

import java.io.IOException;

import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.ItemModelMesher;
import net.minecraft.item.ItemStack;

import com.github.stannismod.forge.testing.client.ClientBot;

import dev.stannismod.stellurgy.api.StellurgyItems;

/**
 * What the client would draw a Stellurgy item with: its baked inventory model, compared against the
 * model manager's own "missing" model — the purple-and-black cube a player sees when an item has no
 * model mapping.
 *
 * <p>Read in the client JVM, on its client thread, from the same mesher the item renderer asks.</p>
 *
 * <p>Test source set: absent from a released jar.</p>
 */
public final class ItemModelReader {

    private ItemModelReader() {
    }

    /** Whether {@code bot}'s client would draw the repair welder (meta 0) with the missing model. */
    public static boolean repairWelderDrawsMissing(ClientBot bot) throws IOException {
        return Boolean.parseBoolean(bot.invokeStaticInt(ItemModelReader.class.getName(),
                "repairWelderDrawsMissingOnClient").get("returned").getAsString());
    }

    /**
     * Whether {@code bot}'s client would draw AIR with the missing model — the reader's own control:
     * nothing maps a model to air, so a reader that cannot answer {@code true} here proves nothing by
     * answering {@code false} for the welder.
     */
    public static boolean airDrawsMissing(ClientBot bot) throws IOException {
        return Boolean.parseBoolean(bot.invokeStaticInt(ItemModelReader.class.getName(),
                "airDrawsMissingOnClient").get("returned").getAsString());
    }

    /** Runs in the client JVM — reached only through {@link #airDrawsMissing}. */
    static boolean airDrawsMissingOnClient() {
        ItemModelMesher mesher = Minecraft.getMinecraft().getRenderItem().getItemModelMesher();
        return mesher.getItemModel(new ItemStack(net.minecraft.init.Items.AIR))
                == mesher.getModelManager().getMissingModel();
    }

    /** Runs in the client JVM — reached only through {@link #repairWelderDrawsMissing}. */
    static boolean repairWelderDrawsMissingOnClient() {
        ItemModelMesher mesher = Minecraft.getMinecraft().getRenderItem().getItemModelMesher();
        return mesher.getItemModel(new ItemStack(StellurgyItems.itemRepairWelder))
                == mesher.getModelManager().getMissingModel();
    }
}
