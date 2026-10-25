package dev.stannismod.stellurgy.world;

import net.minecraft.command.CommandTime;
import net.minecraft.command.ICommandSender;
import net.minecraft.server.MinecraftServer;
import net.minecraft.util.text.TextComponentTranslation;
import net.minecraft.world.World;
import net.minecraft.world.WorldServer;
import net.minecraftforge.event.CommandEvent;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;

import java.util.Locale;
import java.util.OptionalLong;

/**
 * {@code /time set} and {@code /time add} move the universe (C16 CLOCK-7): an operator's time skip.
 *
 * <p>A body world's time of day is its body's turn at the space clock, so no world can be set alone: the
 * command moves the space clock and every body follows. {@code add N} moves it N ticks; {@code set T} moves it
 * to the next moment the sender's world — the overworld when the sender's world is no body — reads T. Never
 * backwards. Worlds that are not bodies keep their own vanilla counter and get vanilla's write. The skip locks
 * of {@link TimeSkipPolicy} govern a SLEEP, not this command.</p>
 *
 * <p>{@code /time query} is read-only and is never intercepted; neither is an argument this guard cannot
 * read, which vanilla answers with its own usage error.</p>
 */
public final class TimeCommandGuard {

    @SubscribeEvent
    public void onCommand(CommandEvent event) {
        if (!(event.getCommand() instanceof CommandTime)) {
            return;
        }
        String[] args = event.getParameters();
        if (args.length < 2) {
            return; // usage error, or `query` - vanilla's business
        }
        String verb = args[0].toLowerCase(Locale.ROOT);
        boolean isSet = "set".equals(verb);
        if (!isSet && !"add".equals(verb)) {
            return; // `query` and anything unrecognised go to vanilla untouched
        }
        ICommandSender sender = event.getSender();
        MinecraftServer server = sender == null ? null : sender.getServer();
        if (server == null) {
            return;
        }
        Integer amount = parseAmount(isSet, args[1]);
        if (amount == null) {
            return; // let vanilla produce its own usage error rather than invent one
        }

        event.setCanceled(true);
        long ticks;
        if (isSet) {
            World here = sender.getEntityWorld();
            World reference = TimeOfDay.isBody(here) ? here : server.getWorld(0);
            OptionalLong until = TimeOfDay.ticksUntil(reference, amount);
            ticks = until.isPresent() ? until.getAsLong() : 0L;
        } else {
            ticks = amount;
        }
        TimeSkip.skip(server, ticks);
        for (WorldServer world : server.worlds) {
            if (world != null && !TimeOfDay.isBody(world)) {
                world.setWorldTime(isSet ? (long) amount : world.getWorldTime() + (long) amount);
            }
        }
        sender.sendMessage(new TextComponentTranslation("msg.timeskip.command.universe", ticks));
    }

    /**
     * The value {@code /time set|add} is being given, or {@code null} when it is not one this guard
     * can apply — in which case the command is left to vanilla, error message and all.
     *
     * <p>The four named times are vanilla's own ({@code CommandTime}); they are duplicated here
     * because the guard replaces the write loop and therefore has to know what was asked for. A
     * negative or unparseable value is deliberately NOT handled: vanilla's own bounds check produces
     * the message a player expects, and inventing a second one would make the two disagree.</p>
     */
    private static Integer parseAmount(boolean isSet, String arg) {
        if (isSet) {
            String named = arg.toLowerCase(Locale.ROOT);
            if ("day".equals(named)) {
                return 1000;
            }
            if ("night".equals(named)) {
                return 13000;
            }
            if ("noon".equals(named)) {
                return 6000;
            }
            if ("midnight".equals(named)) {
                return 18000;
            }
        }
        try {
            int value = Integer.parseInt(arg);
            return value < 0 ? null : value;
        } catch (NumberFormatException notANumber) {
            return null;
        }
    }
}
