package dev.stannismod.stellurgy.test.server;

import dev.stannismod.stellurgy.test.ArrangementFailure;
import dev.stannismod.stellurgy.test.FixtureSite;
import dev.stannismod.stellurgy.test.Reply;
import org.junit.Test;


import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/**
 * Contract coverage for {@link dev.stannismod.stellurgy.util.WeightEngine}
 * exercised against real (registered) blocks and fluids in a booted server.
 *
 * <p>These tests pin the <em>contracts</em> of the mass resolution chain, not
 * the exact kilogram constants in the default material table:</p>
 *
 * <ul>
 *   <li>denser materials resolve to a larger mass than lighter ones;</li>
 *   <li>stack count multiplies the per-item mass;</li>
 *   <li>resolution precedence: individual override &gt; regex &gt; material;</li>
 *   <li>fluid mass uses the fallback per-mB rate and {@code fuelMassScale}.</li>
 * </ul>
 *
 * <p>Every method calls {@code /stellurgytest weight reset} first so the shared
 * WeightEngine singleton + the fuel mass scale start from defaults
 * (see {@link AbstractSharedServerTest} state-leak contract).</p>
 */
public class WeightSystemTest extends AbstractSharedServerTest {

    /** The sentinel the override test installs. The baseline must DIFFER from it, or the override
     *  leg would pass without overriding anything. */
    private static final double OVERRIDE_SENTINEL = 99.0;

    private static final String WEIGHT = "weight";

    private void reset() throws Exception {
        String r = String.join("\n", client().execute("stellurgytest weight reset"));
        assertTrue("weight reset failed: " + r, Reply.of(r).ok());
    }

    private double itemWeight(String id, int count) throws Exception {
        String r = String.join("\n", client().execute("stellurgytest weight item " + id + " " + count));
        assertTrue("item " + id + " not registered: " + r, Reply.of(r).bool("registered"));
        Reply mReply = Reply.of(r);
        assertTrue("no weight field for " + id + ": " + r, mReply.has(WEIGHT));
        return Double.parseDouble(mReply.text(WEIGHT));
    }

    private double fluidWeight(String name, int amount) throws Exception {
        String r = String.join("\n", client().execute("stellurgytest weight fluid " + name + " " + amount));
        assertTrue("fluid " + name + " not registered: " + r, Reply.of(r).bool("registered"));
        Reply mReply = Reply.of(r);
        assertTrue("no weight field for fluid " + name + ": " + r, mReply.has(WEIGHT));
        return Double.parseDouble(mReply.text(WEIGHT));
    }

    @Test
    public void heavierMaterialsWeighMore() throws Exception {
        reset();
        double iron = itemWeight("minecraft:iron_block", 1);   // Material.IRON
        double stone = itemWeight("minecraft:stone", 1);       // Material.ROCK
        double glass = itemWeight("minecraft:glass", 1);       // Material.GLASS
        double wool = itemWeight("minecraft:wool", 1);         // Material.CLOTH

        assertTrue("all material weights must be positive", iron > 0 && stone > 0 && glass > 0 && wool > 0);
        assertTrue("iron must be heavier than stone (" + iron + " vs " + stone + ")", iron > stone);
        assertTrue("stone must be heavier than glass (" + stone + " vs " + glass + ")", stone > glass);
        assertTrue("glass must be at least as heavy as wool (" + glass + " vs " + wool + ")", glass >= wool);
    }

    @Test
    public void stackCountMultipliesWeight() throws Exception {
        reset();
        double one = itemWeight("minecraft:iron_block", 1);
        double four = itemWeight("minecraft:iron_block", 4);
        assertEquals("weight must scale linearly with stack count", 4 * one, four, 1e-4);
    }

    @Test
    public void individualOverrideBeatsMaterial() throws Exception {
        reset();
        double material = itemWeight("minecraft:stone", 1);
        assertTrue("baseline material weight must differ from the override sentinel", material != OVERRIDE_SENTINEL);

        String set = String.join("\n", client().execute("stellurgytest weight set minecraft:stone 99.0"));
        assertTrue("weight set failed: " + set, Reply.of(set).ok());

        assertEquals("explicit individual override must win over the material table",
                99.0, itemWeight("minecraft:stone", 1), 1e-4);
    }

    @Test
    public void regexBeatsMaterialButIndividualBeatsRegex() throws Exception {
        reset();
        String reg = String.join("\n", client().execute("stellurgytest weight set-regex minecraft:gla.* 3.0"));
        assertTrue("set-regex failed: " + reg, Reply.of(reg).ok());
        assertEquals("regex rule must win over the material table",
                3.0, itemWeight("minecraft:glass", 1), 1e-4);

        String set = String.join("\n", client().execute("stellurgytest weight set minecraft:glass 50.0"));
        assertTrue("weight set failed: " + set, Reply.of(set).ok());
        assertEquals("individual override must win over a matching regex rule",
                50.0, itemWeight("minecraft:glass", 1), 1e-4);
    }

    @Test
    public void fluidWeightUsesFallbackAndFuelScale() throws Exception {
        reset();
        double base = fluidWeight("water", 1000);
        assertTrue("fluid weight must be positive: " + base, base > 0);

        String sc = String.join("\n", client().execute("stellurgytest weight fuel-scale 2.0"));
        assertTrue("fuel-scale failed: " + sc, Reply.of(sc).ok());

        assertEquals("fluid weight must scale by fuelMassScale",
                2 * base, fluidWeight("water", 1000), 1e-4);
    }

    /**
     * {@code contentMassScale} reaches what a block HOLDS and nothing else: a chest of iron weighs
     * more than the empty chest at the default scale, exactly what the empty chest weighs at
     * {@code 0}, and three times its content at {@code 3} — while the chest's own block weight is
     * the same at every scale.
     *
     * <p>Fails when the scale is not applied to held content (the scale-0 leg still finds the iron),
     * when held content is not weighed at all (the default leg finds nothing in a full chest), or when
     * the scale reaches the block itself (the block part moves between legs).</p>
     *
     * <p>red-witnessed: with {@code WeightEngine#getTEWeight} at {@code heldWeight(te) *
     * StellurgyConfiguration.getCurrentConfig().contentMassScale} reduced to {@code heldWeight(te)},
     * this fails on the scale-0 leg — "expected:&lt;750.0&gt; but was:&lt;320750.0&gt;"; and with
     * {@code WeightEngine#getWeight} at {@code return weight + getTEWeight(te)} scaling {@code weight}
     * as well, it fails on the same leg — "expected:&lt;750.0&gt; but was:&lt;0.0&gt;" (2026-10-03).</p>
     */
    @Test
    public void contentMassScaleReachesHeldContentAndNothingElse() throws Exception {
        reset();
        FixtureSite at = site();
        String where = at.dim + " " + at.x + " " + at.y + " " + at.z;
        try {
            String placed = String.join("\n", client().execute("stellurgytest place " + where + " minecraft:chest"));
            ArrangementFailure.requireArranged("the chest must be placed: " + placed,
                    Reply.of(placed).ok() && Reply.of(placed).bool("placed"));
            Reply empty = chestAt(at);
            ArrangementFailure.requireArranged("a chest with its tile must stand at the site: " + empty,
                    empty.bool("hasTile") && "minecraft:chest".equals(empty.text("block")));
            double emptyTotal = empty.number("total");
            double block = emptyTotal - empty.number("content");

            String stowed = String.join("\n", client().execute("stellurgytest vs stow " + where
                    + " minecraft:iron_block 64"));
            ArrangementFailure.requireArranged("the iron must go into the chest: " + stowed,
                    Reply.of(stowed).ok() && Reply.of(stowed).integer("stowed") == 64);
            Reply full = chestAt(at);
            double held = full.number("content");
            assertTrue("a chest of iron must hold some mass at the default scale: " + full, held > 0.0);
            assertEquals("the chest's own block weight must not change when it is filled: " + full,
                    block, full.number("total") - held, 1e-3);

            setContentScale(0.0);
            Reply weightless = chestAt(at);
            assertEquals("at scale 0 the full chest must weigh exactly what the empty one did: " + weightless,
                    emptyTotal, weightless.number("total"), 1e-6);

            setContentScale(3.0);
            Reply tripled = chestAt(at);
            assertEquals("at scale 3 the content must weigh three times what it did at 1: " + tripled,
                    3.0 * held, tripled.number("content"), 1e-3 * held);
            assertEquals("and the block part must still be the empty chest's: " + tripled,
                    block, tripled.number("total") - tripled.number("content"), 1e-3);
        } finally {
            reset();
            client().execute("stellurgytest place " + where + " minecraft:air");
        }
    }

    private Reply chestAt(FixtureSite at) throws Exception {
        String r = String.join("\n", client().execute(
                "stellurgytest weight te " + at.dim + " " + at.x + " " + at.y + " " + at.z));
        Reply reply = Reply.of(r);
        assertTrue("weight te failed: " + r, reply.ok());
        return reply;
    }

    private void setContentScale(double k) throws Exception {
        String r = String.join("\n", client().execute("stellurgytest weight content-scale " + k));
        assertTrue("content-scale failed: " + r, Reply.of(r).ok());
    }
}
