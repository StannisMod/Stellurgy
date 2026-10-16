package dev.stannismod.stellurgy.test.client;

import dev.stannismod.stellurgy.test.contract.RedWitness;

import dev.stannismod.stellurgy.test.contract.Pins;

import java.util.ArrayList;
import java.util.List;

import net.minecraft.util.text.TextFormatting;

import org.junit.FixMethodOrder;
import org.junit.Test;
import org.junit.runners.MethodSorters;

import dev.stannismod.stellurgy.test.FixtureSite;
import dev.stannismod.stellurgy.test.Reply;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/**
 * What a player is told about a block's structural damage when he looks at it with TheOneProbe.
 *
 * <p>NEW-GROUP: the probe line — what TheOneProbe's server sends a client about the block that
 * client is looking at. No existing client group reads the probe at all; every scenario here stands
 * the bot in front of one block, changes what the server holds about it, and reads the reply the
 * client receives.</p>
 *
 * <p>Read where the client RECEIVES it ({@code top_block_info}, the reply packet's lines before they
 * are drawn), so a line is something the player's client was told. Silent about the drawing itself:
 * a line that arrives is assumed rendered, and how it looks is not asserted. Every reading is
 * FRESH by construction: it is taken from a mark set after a chat round trip that follows the last
 * change, and a reply the client receives after its own chat came back was answered after the
 * change ({@link #probeLinesNowFor}).</p>
 *
 * <p>What this group does not see: the damage records themselves are arranged by the server's own
 * impact path (a probe declares the impact, the engine spends it); no player weapon fires here.</p>
 */
@FixMethodOrder(MethodSorters.NAME_ASCENDING)
public class BlockProbeLineClientGroupTest extends AbstractSharedClientTest {

    @Override
    protected String subsystem() {
        return "block-probe-line";
    }

    /** The marker a probe line uses around a key the client translates, as the probe defines it. */
    private static final String KEY_OPEN = "{*";
    private static final String KEY_CLOSE = "*}";

    /** Every damage line this mod writes shares this key prefix — the needle for "no damage line". */
    private static final String DAMAGE_KEY_PREFIX = KEY_OPEN + "msg.top.stellurgy.damage.";

    /**
     * The DEADLINE for one probe reply, in ticks. A client looking at a block re-asks the server
     * every 300 ms of its own clock while its overlay is drawn ({@code Config.timeout} in the probe
     * mod), so this many ticks is some thirty requests; it only runs out on a client that is not
     * asking, or a server that is not answering.
     */
    private static final int PROBE_REPLY_DEADLINE_TICKS = 200;

    /** The impacts here are KINETIC, the column {@code damage stage}'s {@code stageCost} prices. */
    private static final String IMPACT_KIND = "KINETIC";

    /**
     * An undamaged block is told about with no damage line at all; once struck it is told its stage
     * out of its maximum, with no "cannot be welded" because something crafts it.
     *
     * <p>Not asserted: the line on a block taken out and put back by something that is not a player's
     * hand. The provider knows only a position, so that line is this same decision read again, and no
     * break of the provider can redden it without reddening the struck verdict first; what keeps the
     * record through such a replacement is the damage map's, not this line's.</p>
     */
    @RedWitness(verdict = "no line when untouched",
            member = "DamageProbeProvider#addProbeInfo",
            at = "if (recorded == null && stage <= 0)",
            replacedBy = "if (false)",
            fails = "an undamaged block must be told about with NO damage line",
            on = "2026-10-07")
    @RedWitness(verdict = "the stage",
            member = "DamageProbeProvider#addProbeInfo",
            at = "stage + \"/\" + DamageState.getMaxStage(world, pos)",
            replacedBy = "DamageState.getMaxStage(world, pos) + \"/\" + DamageState.getMaxStage(world, pos)",
            fails = "a staged block must be told exactly its stage out of its maximum",
            on = "2026-10-07")
    @RedWitness(verdict = "nothing else",
            member = "DamageProbeProvider#addProbeInfo",
            at = "if (RepairCost.perStage(world, pos) == null)",
            replacedBy = "if (RepairCost.perStage(world, pos) != null)",
            fails = "a staged block must be told exactly its stage out of its maximum",
            on = "2026-10-07")
    @Pins({"INV-IPW-07", "INV-IPW-08"})
    @Test
    public void anUntouchedBlockHasNoDamageLineAndAStruckOneIsToldItsStage() throws Exception {
        int[] block = standBeforeOneBlock("minecraft:iron_block");

        scenario().asserting("an untouched block carries no damage line");
        List<String> untouched = probeLinesNowFor(block, "the iron block, untouched");
        assertTrue("an undamaged block must be told about with NO damage line: " + untouched,
                damageLines(untouched).isEmpty());

        int[] staged = strikeOneStage(block, "the iron block");
        scenario().asserting("the struck block is told its stage out of its maximum");
        List<String> struck = probeLinesNowFor(block, "the iron block, struck");
        assertEquals("a staged block must be told exactly its stage out of its maximum, and nothing"
                        + " else about its damage — a block something crafts can be welded: " + struck,
                lines(KEY_OPEN + "msg.top.stellurgy.damage.stage" + KEY_CLOSE + " " + staged[0] + "/" + staged[1]),
                damageLines(struck));
    }

    /**
     * A struck block that nothing crafts is told so — the welder refuses it outright, and the player
     * should not learn that by failing.
     */
    @RedWitness(member = "DamageProbeProvider#addProbeInfo",
            at = "if (RepairCost.perStage(world, pos) == null)",
            replacedBy = "if (RepairCost.perStage(world, pos) != null)",
            fails = "a staged block the welder refuses for want of a recipe must be told so",
            on = "2026-10-07")
    @Pins("INV-IPW-08")
    @Test
    public void aStruckBlockNothingCraftsIsToldItCannotBeWelded() throws Exception {
        int[] block = standBeforeOneBlock("minecraft:cobblestone");
        int[] staged = strikeOneStage(block, "the cobblestone block");
        // An empty tool and nothing to pay with, held by the probe's own survival-mode welder: the
        // welder asks for a recipe before it asks for charge or materials, so a NO_RECIPE that comes
        // back is about the block, and no refusal changes the stage.
        Reply weld = act("stellurgytest damage weld " + at(block) + " 0 none");
        scenario().requireArranged("the welder itself must refuse this block for want of a recipe, or"
                + " the line below has nothing to be true about: " + weld,
                "NO_RECIPE".equals(weld.text("outcome")));

        scenario().asserting("the struck uncraftable block is told its stage and that it cannot be welded");
        List<String> struck = probeLinesNowFor(block, "the cobblestone block, struck");
        assertEquals("a staged block the welder refuses for want of a recipe must be told so, beside"
                        + " its stage: " + struck,
                lines(KEY_OPEN + "msg.top.stellurgy.damage.stage" + KEY_CLOSE + " " + staged[0] + "/" + staged[1],
                        KEY_OPEN + "msg.top.stellurgy.damage.noRecipe" + KEY_CLOSE),
                damageLines(struck));
    }

    /**
     * A block standing where the record says a block was DESTROYED is told that, and what the
     * record names — not a stage, because a destroyed position is rebuilt from the record rather
     * than welded.
     */
    @RedWitness(member = "DamageProbeProvider#addProbeInfo",
            at = "if (recorded != null)",
            replacedBy = "if (false)",
            fails = "a block standing on a recorded destruction must be told the position was",
            on = "2026-10-07")
    @Pins("INV-IPW-08")
    @Test
    public void aBlockStandingOnARecordedDestructionIsToldSo() throws Exception {
        int[] block = standBeforeOneBlock("minecraft:iron_block");
        scenario().arranging("destroy the block with one impact, then stand a block of the same kind there");
        // The largest budget the verb takes: more than any stage ladder costs, and what is left over
        // leaves upward into empty sky.
        Reply impact = act("stellurgytest damage impact " + fromBelow(block) + " "
                + Integer.MAX_VALUE + " " + IMPACT_KIND);
        Reply hole = stageAt(block);
        scenario().requireArranged("the impact must DESTROY the block and the world must record what"
                        + " stood there: impact=" + impact + " position=" + hole,
                hole.bool("wasDestroyed") && "minecraft:air".equals(hole.text("block")));
        place(block, "minecraft:iron_block");
        Reply refilled = stageAt(block);
        scenario().requireArranged("the record must outlive the block standing there again: " + refilled,
                refilled.bool("wasDestroyed") && "minecraft:iron_block".equals(refilled.text("block")));

        scenario().asserting("the block standing on the record is told the position was destroyed");
        List<String> told = probeLinesNowFor(block, "the iron block on a recorded destruction");
        assertEquals("a block standing on a recorded destruction must be told the position was"
                        + " destroyed, naming what the record holds, and no stage: " + told,
                lines(KEY_OPEN + "msg.top.stellurgy.damage.destroyed" + KEY_CLOSE + " "
                        + KEY_OPEN + "tile.blockIron.name" + KEY_CLOSE),
                damageLines(told));
    }

    // ---- the scenario's own instruments ------------------------------------------------------

    /**
     * Build a platform in this scenario's air, stand the bot on it looking SOUTH at one block of
     * {@code blockId} three blocks away at eye height, and answer that block's position.
     *
     * <p>The geometry: the bot's feet at the platform's centre column ({@code site.x+0.5},
     * {@code site.y+1}, {@code site.z+0.5}), his eye 1.62 above them, the block's centre at
     * {@code (site.x+0.5, site.y+2.5, site.z+3.5)} — three blocks along +Z (yaw 0) and 0.12 below
     * the eye, so a pitch of 2 degrees down. Nothing stands between them, and the probe reaches six
     * blocks.</p>
     */
    private int[] standBeforeOneBlock(String blockId) throws Exception {
        scenario().arranging("a platform, and one " + blockId + " in front of the bot");
        FixtureSite site = site();
        // HEIGHT 4: the block's own course (site.y+2) and the bot's head (site.y+2.62) are the top of
        // what this scenario uses; halo 0, the platform and the block sit inside the pad footprint.
        site.requireClear(c -> arrange(c).raw(), 0, 4, "the platform, the bot standing on it and the block he looks at");
        Reply floor = arrange("stellurgytest fill " + site.dim + " " + (site.x - 1) + " " + site.y + " "
                + (site.z - 1) + " " + (site.x + 1) + " " + site.y + " " + (site.z + 1) + " minecraft:stone");
        scenario().requireArranged("the platform must be laid: " + floor, floor.ok());
        int[] block = {site.x, site.y + 2, site.z + 3};
        place(block, blockId);
        Reply fresh = stageAt(block);
        scenario().requireArranged("the block must stand there with no damage record: " + fresh,
                blockId.equals(fresh.text("block")) && fresh.integer("stage") == 0
                        && !fresh.bool("wasDestroyed"));
        standOnFloorTheClientHolds(site.x + 0.5, site.y + 1, site.z + 0.5, 0.0F, 2.0F,
                "the bot must stand on the platform, looking at the block");
        return block;
    }

    /**
     * Spend exactly one stage's price into the block from below — the server's own impact path —
     * and answer {@code {stage, maxStage}} as the server then reads the position.
     */
    private int[] strikeOneStage(int[] block, String what) throws Exception {
        scenario().arranging("strike " + what + " for one stage's price");
        Reply before = stageAt(block);
        Reply impact = act("stellurgytest damage impact " + fromBelow(block) + " "
                + before.integer("stageCost") + " " + IMPACT_KIND);
        Reply after = stageAt(block);
        int stage = after.integer("stage");
        int max = after.integer("maxStage");
        scenario().requireArranged(what + " must be STAGED — damaged and still standing — or there is no"
                        + " stage to be told: impact=" + impact + " position=" + after,
                stage > 0 && stage < max && !after.bool("wasDestroyed"));
        return new int[]{stage, max};
    }

    /**
     * The probe's lines for {@code block} as a reply the client received AFTER every change made so
     * far — fenced by a chat round trip, then the next reply for that position.
     *
     * <p>Why the fence makes it fresh: the server answers a probe request and echoes a chat line on
     * its one main thread, onto one connection, in order. A mark taken once the client has its own
     * line back is later than every reply the server sent before it handled the chat, and the chat
     * is handled after the change, which the probe command had already finished. Formatting codes
     * are stripped: what is asserted is what the line SAYS.</p>
     */
    private List<String> probeLinesNowFor(int[] block, String what) throws Exception {
        fenceWhatTheClientSent("every change to " + what + " must be behind the server before its"
                + " probe reply is read");
        long mark = clientEvents().mark();
        // A link: the probe mod asks and answers; what the reply SAYS is the verdict.
        String record = dev.stannismod.stellurgy.test.ArrangementFailure.arranged(() -> clientEvents().awaitRecordWithFields(mark,
                "top_block_info", "the client looking at " + what + " must be sent the probe's reply for it",
                PROBE_REPLY_DEADLINE_TICKS,
                "x", String.valueOf(block[0]), "y", String.valueOf(block[1]), "z", String.valueOf(block[2])));
        Reply reply = Reply.of(record);
        scenario().requireArranged("the probe must have answered with info for " + what + ": " + record,
                reply.bool("info"));
        List<String> out = new ArrayList<>();
        for (String line : reply.textArray("lines")) {
            out.add(TextFormatting.getTextWithoutFormattingCodes(line));
        }
        return out;
    }

    /** The lines that speak about damage — this mod's damage keys and nothing the probe adds itself. */
    private static List<String> damageLines(List<String> lines) {
        List<String> out = new ArrayList<>();
        for (String line : lines) {
            if (line.startsWith(DAMAGE_KEY_PREFIX)) {
                out.add(line);
            }
        }
        return out;
    }

    private static List<String> lines(String... lines) {
        List<String> out = new ArrayList<>();
        java.util.Collections.addAll(out, lines);
        return out;
    }

    private void place(int[] block, String blockId) throws Exception {
        Reply placed = arrange("stellurgytest place " + at(block) + " " + blockId);
        scenario().requireArranged("placing " + blockId + " must be accepted: " + placed, placed.ok());
    }

    private Reply stageAt(int[] block) throws Exception {
        return read("stellurgytest damage stage " + at(block));
    }

    /** {@code <dim> <x> <y> <z>} of a block in this scenario's world. */
    private String at(int[] block) {
        return site().dim + " " + block[0] + " " + block[1] + " " + block[2];
    }

    /** {@code <dim> <x> <y> <z> 0 1 0}: an impact's start half a block under {@code block}, travelling up
     *  into it — the sky above this scenario's air is where whatever it does not spend goes. */
    private String fromBelow(int[] block) {
        return site().dim + " " + (block[0] + 0.5) + " " + (block[1] - 0.5) + " " + (block[2] + 0.5) + " 0 1 0";
    }
}
