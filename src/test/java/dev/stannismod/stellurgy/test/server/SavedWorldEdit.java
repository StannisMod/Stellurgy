package dev.stannismod.stellurgy.test.server;

import com.github.stannismod.forge.testing.server.BetweenBootsEdit;
import com.github.stannismod.forge.testing.server.ServerRoot;
import dev.stannismod.stellurgy.dimension.DimensionManager;

import java.util.function.UnaryOperator;

/**
 * A change to a file a Stellurgy world SAVED, made between two boots of one game directory — the typed
 * arrangement of a test whose subject is what the next boot makes of a save it did not write itself (a
 * hand-edited catalogue, a restored backup).
 *
 * <p>The harness applies it while no server runs ({@code ServerRoot#editWhileDown}); this type only names
 * the file and carries the edit. Nothing else writes into a game directory between boots.</p>
 */
public final class SavedWorldEdit implements BetweenBootsEdit {

    /**
     * The world's own planet catalogue, relative to the game directory — the file
     * {@code DimensionManager#saveDimensions} writes, and which the next boot reads in preference to the
     * config folder's ({@code DimensionManager#createAndLoadDimensions}, "Check advRocketry folder first").
     */
    public static final String PLANET_DEFS =
            ServerRoot.LEVEL_NAME + "/" + DimensionManager.workingPath + DimensionManager.worldXML;

    private final String file;
    private final UnaryOperator<String> edit;

    private SavedWorldEdit(String file, UnaryOperator<String> edit) {
        this.file = file;
        this.edit = edit;
    }

    /** {@code edit} applied to the world's own planet catalogue ({@link #PLANET_DEFS}), as the last boot saved it. */
    public static SavedWorldEdit planetDefs(UnaryOperator<String> edit) {
        return new SavedWorldEdit(PLANET_DEFS, edit);
    }

    @Override
    public String file() {
        return file;
    }

    @Override
    public String edit(String saved) {
        return edit.apply(saved);
    }
}
