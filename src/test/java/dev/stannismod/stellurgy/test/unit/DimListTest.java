package dev.stannismod.stellurgy.test.unit;

import dev.stannismod.stellurgy.test.DimList;
import org.junit.Test;

import static org.junit.Assert.assertArrayEquals;

/**
 * The {@code dim list} reader's one DERIVED answer: which dimensions a command registered between
 * two readings. Tests that mint a world through {@code planet generate} find it this way, so a
 * reader that answered the whole registry, or nothing, would hand them the wrong world or none.
 */
public class DimListTest {

    private static DimList reading(String dims) {
        return DimList.of("{\"stellurgyDimensions\":[" + dims + "],\"forgeDimensions\":[0,-1,1]}");
    }

    /**
     * What was added is what the later reading holds and the earlier did not — in the later
     * reading's order, and nothing the earlier already held, including a world that has since gone.
     *
     * <p>Acceptance, stated before the code: {@code [0,2,5]} then {@code [0,5,9,7]} answers
     * {@code [9,7]}.</p>
     *
     * <p>red-witnessed: 2026-09-30, with {@code DimList#addedSince} at {@code return java.util.Arrays.stream(registered()).filter(dim -> !earlier.holds(dim)).toArray()} — its filter — inverted
     * ({@code earlier.holds(dim)}): "arrays first differed at element [0]; expected:&lt;9&gt; but
     * was:&lt;0&gt;".</p>
     */
    @Test
    public void addedSinceAnswersOnlyTheDimensionsTheLaterReadingGained() {
        int[] added = reading("0,5,9,7").addedSince(reading("0,2,5"));
        assertArrayEquals(new int[]{9, 7}, added);
    }
}
