package dev.stannismod.stellurgy.test.client;

import com.github.stannismod.forge.testing.junit.AbstractClientE2ETest;
import com.google.gson.JsonObject;
import net.minecraft.util.EnumFacing;
import net.minecraft.util.EnumHand;
import org.junit.Test;

import dev.stannismod.stellurgy.test.Events;
import dev.stannismod.stellurgy.test.GameTicks;
import dev.stannismod.stellurgy.test.Reply;
import dev.stannismod.stellurgy.test.Weapons;

import static dev.stannismod.stellurgy.test.ArrangementFailure.requireArranged;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/**
 * The two repairs a PLAYER makes by hand, through the paths only a player's hand reaches.
 *
 * <p>Every other damage test changes blocks through probe verbs, which fire no block event and call
 * the welder's logic directly. That leaves exactly the two doors a player uses untested: the
 * placement a player makes into a hole a weapon left (the damage map hears it through a
 * {@code BlockEvent.PlaceEvent}, and a block put back by hand is a real repair rather than one still
 * recorded as destroyed), and the repair welder used on a block (the item's own right-click, which is
 * reached only after the protection hooks have had their say). A real client does both here, through
 * its own right-click packet.</p>
 *
 * <p>The third door is the one a player uses to take a damaged block away: his own attack key. It
 * is here because it is the other half of the same bookkeeping — the break is what empties a
 * position's record before anything is built there again.</p>
 *
 * <p>What is NOT pinned here is the welder's price and its refusals — those are the static
 * {@code weld}'s, and {@code RepairWelderE2ETest} holds them. This is the wiring from a hand to it.</p>
 *
 * <p>Gated by {@code forge.test.client.enabled=true}; auto-skips on headless CI.</p>
 */
public class APlayersHandRepairsE2ETest extends AbstractClientE2ETest {

    /** The harness's single client always joins under this name. */
    private static final String PLAYER = "ForgeTestClient";

    private static final int DIM = 0;
    /** This class's own site, clear of the other client scenarios. */
    private static final int X = 560, Y = 84, Z = 560;

    /**
     * A block a player PLACES into a hole a weapon left is a new block, not a destroyed one.
     *
     * <p>The control is the half that makes it evidence: the same hole filled by a probe — which
     * fires no event — still reads as destroyed. So the map does keep a hole's record through a
     * placement that nobody announced, and what clears the subject's is the player's placement and
     * nothing else. The placement is linked on the map's own hearing of it
     * ({@code damage_invalidation_heard}), and the verdict is then read off the map once.</p>
     *
     * <p>red-witnessed: with {@code DamageInvalidationHandler#onBlockPlaced} at {@code forget(event.getWorld(), event.getPos());} (the placement's
     * {@code forget}) skipped, this fails with "a block a player placed into a hole still reads as
     * DESTROYED ... {...block:minecraft:stone,wasDestroyed:true...}". 2026-09-30.</p>
     */
    @Test
    public void aBlockAPlayerPutsIntoAHoleIsNotRecordedAsDestroyed() throws Exception {
        Events server = new Events(this::exec, bot()::waitTicks);
        prepareSite();
        int subjectX = X + 2, controlX = X + 4;
        post(subjectX);
        post(controlX);
        destroyPost(subjectX, 88101);
        destroyPost(controlX, 88102);

        // The control: a hole filled by something that announces nothing keeps its record. The
        // `place` verb sets the state and nothing else; `fill` is no use here, because it clears the
        // damage map itself as the harness's "this region is fresh".
        Reply controlPlaced = ask("stellurgytest place " + DIM + " " + controlX + " " + Y + " " + Z
                + " minecraft:stone");
        requireArranged("the control hole would not take a stone: " + controlPlaced,
                controlPlaced.bool("placed"));
        Reply control = stage(controlX);
        requireArranged("the control hole did not take the probe's stone: " + control,
                "minecraft:stone".equals(control.text("block")));
        assertTrue("a hole filled by a probe no longer reads as destroyed, so the map drops a hole's"
                + " record on ANY placement and the player's below proves nothing: " + control,
                control.bool("wasDestroyed"));

        // The subject: the player puts a stone into the other hole, by hand.
        standBeside();
        exec("clear " + PLAYER);
        exec("give " + PLAYER + " minecraft:stone 1");
        bot().selectHotbar(0);
        long placed = server.markInstrumented();
        bot().rightClickBlock(subjectX, Y - 1, Z, EnumFacing.UP, EnumHand.MAIN_HAND);
        server.awaitRecordWithFields(placed, "damage_invalidation_heard",
                "the player's placement into the hole never reached the damage map",
                Weapons.SUBJECT_TICKS, "cause", "place", "dim", String.valueOf(DIM),
                "pos", Weapons.at(subjectX, Y, Z));

        Reply subject = stage(subjectX);
        requireArranged("the player's stone is not in the hole it was placed into: " + subject,
                "minecraft:stone".equals(subject.text("block")));
        assertTrue("a block a player placed into a hole still reads as DESTROYED: the placement was a"
                + " real repair paid for with the block itself, and the map charges it against the hull"
                + " anyway: " + subject, !subject.bool("wasDestroyed") && subject.integer("stage") == 0);
    }

    /**
     * A charged welder in a player's hand, used on a damaged block with its materials in the
     * inventory, takes exactly one stage off — through the item's own use, reached by a real
     * right-click. The link is the block's own stage write ({@code block_stage_set}), which carries
     * the stage it came from and the one it went to.
     *
     * <p>red-witnessed: with {@code ItemRepairWelder#onItemUse} at {@code if (world.isRemote)}'s server branch returning before
     * {@code weld}, this fails with "a charged welder in a player's hand ... repaired nothing: the
     * item's own use never reached the repair — no `block_stage_set` carrying dim = 0 and pos =
     * 566,84,560 was recorded within 600 ticks". 2026-09-30.</p>
     *
     * <p>red-witnessed, one inversion per verdict, 2026-09-30: with a write of one stage MORE inserted
     * before {@code ItemRepairWelder.java:115}'s own, this fails at "the stage write the welder made did
     * not start from the block's damage: {...from:3,to:1...} expected:&lt;2&gt; but was:&lt;3&gt;"; with
     * that line taking two stages off, at "one use of the welder must take exactly one stage off:
     * {...from:2,to:0...} expected:&lt;1&gt; but was:&lt;0&gt;".</p>
     */
    @Test
    public void aWelderInAPlayersHandTakesOneStageOff() throws Exception {
        Events server = new Events(this::exec, bot()::waitTicks);
        prepareSite();
        int blockX = X + 6;
        ask("stellurgytest fill " + DIM + " " + blockX + " " + Y + " " + Z + " " + blockX + " " + Y + " " + Z
                + " minecraft:iron_block").requireOk("place the subject");
        Reply priced = stage(blockX);
        int twoStages = priced.integer("stageCost") * 2;
        requireArranged("the subject has no price, so it cannot be damaged by a known amount: " + priced,
                twoStages > 0);
        ask("stellurgytest damage clear-impacts").requireOk("forget earlier impacts");
        Reply shot = ask("stellurgytest damage impact " + DIM + " " + (blockX + 0.5D) + " " + (Y + 3.5D) + " "
                + (Z + 0.5D) + " 0 -1 0 " + twoStages + " KINETIC 88103").requireOk("damage the subject");
        int damaged = stage(blockX).integer("stage");
        requireArranged("the subject must be damaged and standing, or there is nothing to weld: stage "
                + damaged + " after " + shot, damaged >= 1 && damaged < priced.integer("maxStage"));

        // Survival: in creative the welder repairs for nothing, which is a different path.
        exec("gamemode 0 " + PLAYER);
        standBeside();
        exec("clear " + PLAYER);
        exec("give " + PLAYER + " stellurgy:repairWelder 1 0 {energy:100000}");
        exec("give " + PLAYER + " minecraft:iron_ingot 64");
        bot().selectHotbar(0);

        long used = server.markInstrumented();
        bot().rightClickBlock(blockX, Y, Z, EnumFacing.UP, EnumHand.MAIN_HAND);
        String welded = server.awaitRecordWithFields(used, "block_stage_set",
                "a charged welder in a player's hand, used on a damaged block with its materials in the"
                        + " inventory, repaired nothing: the item's own use never reached the repair",
                Weapons.SUBJECT_TICKS, "dim", String.valueOf(DIM), "pos", Weapons.at(blockX, Y, Z));
        assertEquals("the stage write the welder made did not start from the block's damage: " + welded,
                damaged, (int) Events.number(welded, "from"));
        assertEquals("one use of the welder must take exactly one stage off: " + welded,
                damaged - 1, (int) Events.number(welded, "to"));
    }

    /**
     * A damaged block a player BREAKS takes its damage record with it: the position is empty, and
     * nothing is left there to be charged against whatever is built in it next.
     *
     * <p>The control is the same damage on a twin block removed by something that announces nothing
     * — a probe setting the block to air — which keeps its record; so the map does not drop a
     * record merely because the block under it went away, and what clears the subject's is the
     * player's break and nothing else. The break is a real attack-key press, aimed from the client's
     * own eye and confirmed on the client's crosshair before it is pressed, so it travels the path a
     * player's does: the client's click, its dig packet, the server's harvest and its
     * {@code BlockEvent.BreakEvent}. It is linked on the map's own hearing of it
     * ({@code damage_invalidation_heard} with {@code cause} {@code break}), and the verdict is then
     * read off the map once.</p>
     *
     * <p>red-witnessed: with {@code DamageInvalidationHandler#onBlockBroken} at {@code forget(event.getWorld(), event.getPos());} (the break's {@code forget})
     * skipped, this fails with "a damaged block a player broke still carries its damage (2 stages
     * before the break) ... {...stage:2,maxStage:4,...block:minecraft:air,wasDestroyed:false...}".
     * 2026-09-30.</p>
     */
    @Test
    public void aDamagedBlockAPlayerBreaksLeavesNoDamageBehind() throws Exception {
        // The break and its record happen in the server's world, so its log is stepped by that
        // world's own clock.
        Events server = new Events(this::exec, ticks -> GameTicks.advanceWorld(serverClient(), DIM, ticks));
        prepareSite();
        int subjectX = X + 2, controlX = X + 6;
        post(subjectX);
        post(controlX);
        int subjectStage = damagePost(subjectX, 88104);
        int controlStage = damagePost(controlX, 88105);

        // The control: the damaged twin taken away by something that fires no event keeps its record.
        Reply controlRemoved = ask("stellurgytest place " + DIM + " " + controlX + " " + Y + " " + Z
                + " minecraft:air");
        requireArranged("the control post would not be removed: " + controlRemoved,
                controlRemoved.bool("placed"));
        Reply control = stage(controlX);
        requireArranged("the control post is still standing: " + control,
                "minecraft:air".equals(control.text("block")));
        assertEquals("a damaged block removed by a probe no longer carries its damage, so the map drops"
                + " a record whenever the block under it goes, and the player's break below proves"
                + " nothing: " + control, controlStage, control.integer("stage"));

        // The subject: the player breaks the other one by hand. Creative, so ONE press is a whole
        // break (PlayerControllerMP.clickBlock sends the dig and the server's onBlockClicked harvests
        // at once, firing the BreakEvent from tryHarvestBlock) rather than a dig held for as long as
        // the block's hardness asks. The mode change travels to the client ahead of the teleport
        // below on the same connection, so the placement link below also means the client has it.
        exec("gamemode 1 " + PLAYER);
        exec("clear " + PLAYER);
        bot().selectHotbar(0);
        // Stand three blocks north of the post, level with it, and face it: the rotation travels in
        // the placement itself, so the look the client applies is the one the teleport carried and
        // no later packet can overwrite it.
        double standX = subjectX + 0.5D, standY = Y, standZ = Z - 2.5D;
        double dy = (Y + 0.5D) - (standY + EYE_HEIGHT), dz = (Z + 0.5D) - standZ;
        float pitch = (float) -Math.toDegrees(Math.atan2(dy, dz));
        ClientEvents.placeOntoGroundItHolds(bot(), ClientEvents.of(bot()), this::exec,
                "tp " + PLAYER + " " + standX + " " + standY + " " + standZ + " 0 " + pitch,
                standX, standY, standZ, "the player must stand in front of the damaged post",
                ROUND_TRIP_DEADLINE_TICKS);
        // STIMULUS: one client tick, the one whose start recomputes the crosshair from the rotation
        // the placement applied (Minecraft.runTick recomputes it before it reads any key).
        bot().waitTicks(1);
        JsonObject pick = bot().reportMouseOver();
        requireArranged("the client's crosshair is not on the damaged post, so a press would break"
                + " something else: " + pick,
                pick.has("typeOfHit") && "BLOCK".equals(pick.get("typeOfHit").getAsString())
                        && pick.has("blockX") && pick.get("blockX").getAsInt() == subjectX
                        && pick.get("blockY").getAsInt() == Y && pick.get("blockZ").getAsInt() == Z);

        long broken = server.markInstrumented();
        bot().setKey(KEY_ATTACK, true);
        // STIMULUS: the attack key held across one client tick, as a mouse button is — long enough
        // for the press to be read (Minecraft.processKeyBinds), shorter than the five ticks a
        // creative break waits before it will take the next block (PlayerControllerMP.blockHitDelay).
        bot().waitTicks(1);
        bot().setKey(KEY_ATTACK, false);
        server.awaitRecordWithFields(broken, "damage_invalidation_heard",
                "the player's break of the damaged post never reached the damage map",
                ROUND_TRIP_DEADLINE_TICKS, "cause", "break", "dim", String.valueOf(DIM),
                "pos", subjectX + "," + Y + "," + Z);

        Reply subject = stage(subjectX);
        requireArranged("the player's press did not take the post away: " + subject,
                "minecraft:air".equals(subject.text("block")));
        assertTrue("a damaged block a player broke still carries its damage (" + subjectStage + " stages"
                + " before the break): the next block built there would start cracked, and the"
                + " hull is charged for a crack that was mined out: " + subject,
                subject.integer("stage") == 0 && !subject.bool("wasDestroyed"));
    }

    // ---- arrangement

    /** The attack key's code: mouse buttons enter {@code KeyBinding} as {@code -100 + button}, LMB 0. */
    private static final int KEY_ATTACK = -100;

    /**
     * The deadline for something one client-server round trip away — a placement the client applies,
     * a dig packet the server harvests on. A round trip is a few ticks; this is a deadline far past
     * one, never an estimate of it.
     */
    private static final int ROUND_TRIP_DEADLINE_TICKS = 200;

    /** A standing player's eye height (EntityPlayer.getDefaultEyeHeight) — the crosshair's ray starts here. */
    private static final double EYE_HEIGHT = 1.62D;

    /**
     * Damage the post at {@code x} by two stages' worth — the {@code damage stage} verb reports what
     * one stage of it costs the engine — from above, and answer its stage, which is read back and
     * must leave it damaged and standing.
     */
    private int damagePost(int x, long impactId) throws Exception {
        Reply priced = stage(x);
        int twoStages = priced.integer("stageCost") * 2;
        requireArranged("the post has no price, so it cannot be damaged by a known amount: " + priced,
                twoStages > 0);
        ask("stellurgytest damage clear-impacts").requireOk("forget earlier impacts");
        Reply shot = ask("stellurgytest damage impact " + DIM + " " + (x + 0.5D) + " " + (Y + 3.5D) + " "
                + (Z + 0.5D) + " 0 -1 0 " + twoStages + " KINETIC " + impactId).requireOk("damage the post");
        Reply damaged = stage(x);
        requireArranged("the post at " + x + " must be damaged and standing: " + damaged + " after " + shot,
                damaged.integer("stage") >= 1 && damaged.integer("stage") < damaged.integer("maxStage")
                        && "minecraft:stone".equals(damaged.text("block")));
        return damaged.integer("stage");
    }

    /** Air round the site, a stone floor to stand on, and the player's column held. */
    private void prepareSite() throws Exception {
        ask("stellurgytest chunk warmup " + DIM + " " + ((X - 16) >> 4) + " " + ((Z - 16) >> 4) + " "
                + ((X + 16) >> 4) + " " + ((Z + 16) >> 4)).requireOk("warm the site's chunks");
        // Warmed is loaded NOW, not held: the posts are shot before the player stands here, and a
        // chunk nobody holds can be unloaded in between — the damage engine then refuses the blow
        // with TARGET_UNLOADED (StructureDamageEngine, the isBlockLoaded check) instead of landing it.
        for (int cx = (X - 3) >> 4; cx <= (X + 9) >> 4; cx++) {
            for (int cz = (Z - 4) >> 4; cz <= (Z + 3) >> 4; cz++) {
                ask("stellurgytest chunk forceload " + DIM + " " + cx + " " + cz).requireOk("hold the site's chunk");
            }
        }
        ask("stellurgytest fill " + DIM + " " + (X - 3) + " " + Y + " " + (Z - 4) + " " + (X + 9) + " " + (Y + 5)
                + " " + (Z + 3) + " minecraft:air").requireOk("clear the site");
        Reply floor = ask("stellurgytest fill " + DIM + " " + (X - 3) + " " + (Y - 1) + " " + (Z - 4) + " "
                + (X + 9) + " " + (Y - 1) + " " + (Z + 3) + " minecraft:stone").requireOk("lay the floor");
        requireArranged("the floor placed nothing: " + floor, floor.integer("placed") > 0);
    }

    /** One stone block on the floor, the thing a weapon will empty. */
    private void post(int x) throws Exception {
        ask("stellurgytest fill " + DIM + " " + x + " " + Y + " " + Z + " " + x + " " + Y + " " + Z
                + " minecraft:stone").requireOk("place a post");
    }

    /** Destroy the post at {@code x} with exactly one block's worth, straight down from above. */
    private void destroyPost(int x, long impactId) throws Exception {
        Reply priced = stage(x);
        int oneBlock = priced.integer("stageCost") * Math.max(1, priced.integer("maxStage"));
        ask("stellurgytest damage clear-impacts").requireOk("forget earlier impacts");
        Reply shot = ask("stellurgytest damage impact " + DIM + " " + (x + 0.5D) + " " + (Y + 3.5D) + " "
                + (Z + 0.5D) + " 0 -1 0 " + oneBlock + " KINETIC " + impactId).requireOk("destroy the post");
        Reply hole = stage(x);
        requireArranged("the post at " + x + " was not destroyed into a recorded hole, so there is no hole"
                + " to fill: " + hole + " after " + shot,
                hole.bool("wasDestroyed") && "minecraft:air".equals(hole.text("block")));
    }

    /** Where the player stands: on the floor, two blocks in front of the posts, within reach. */
    private void standBeside() throws Exception {
        exec("tp @a " + (X + 4.5D) + " " + Y + " " + (Z - 2.5D) + " 0 30");
    }

    private Reply stage(int x) throws Exception {
        return ask("stellurgytest damage stage " + DIM + " " + x + " " + Y + " " + Z).requireOk("read a stage");
    }

    /** A vanilla command, whose reply is chat rather than JSON and is not read. */
    private String exec(String command) throws Exception {
        return String.join("\n", serverClient().execute(command));
    }

    private Reply ask(String command) throws Exception {
        return Reply.of(command, exec(command));
    }
}
