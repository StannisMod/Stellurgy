package dev.stannismod.stellurgy.command.test;

import dev.stannismod.stellurgy.Stellurgy;
import dev.stannismod.stellurgy.command.sub.AddSealantCommand;
import dev.stannismod.stellurgy.command.sub.AddTorchCommand;
import dev.stannismod.stellurgy.command.sub.FillDataCommand;
import dev.stannismod.stellurgy.command.sub.ReloadRecipesCommand;
import dev.stannismod.stellurgy.command.sub.SetGravityCommand;
import dev.stannismod.stellurgy.command.sub.dev.DumpBiomesCommand;
import dev.stannismod.stellurgy.command.sub.dev.RunTestsCommand;
import dev.stannismod.stellurgy.command.sub.planet.PlanetDeleteCommand;
import dev.stannismod.stellurgy.command.sub.planet.PlanetGenerateCommand;
import dev.stannismod.stellurgy.command.sub.planet.PlanetGetCommand;
import dev.stannismod.stellurgy.command.sub.planet.PlanetListCommand;
import dev.stannismod.stellurgy.command.sub.planet.PlanetResetCommand;
import dev.stannismod.stellurgy.command.sub.planet.PlanetSetCommand;
import dev.stannismod.stellurgy.command.sub.planet.PlanetWeatherCommand;
import dev.stannismod.stellurgy.command.sub.redirect.WeatherCommand;
import dev.stannismod.stellurgy.command.sub.star.StarGenerateCommand;
import dev.stannismod.stellurgy.command.sub.star.StarGetCommand;
import dev.stannismod.stellurgy.command.sub.star.StarListCommand;
import dev.stannismod.stellurgy.command.sub.star.StarSetCommand;
import dev.stannismod.stellurgy.command.sub.station.CreateStationCommand;
import dev.stannismod.stellurgy.command.sub.station.GiveStationCommand;
import dev.stannismod.stellurgy.command.sub.teleport.FetchCommand;
import dev.stannismod.stellurgy.command.sub.teleport.GoToDimensionCommand;
import dev.stannismod.stellurgy.command.sub.teleport.GoToStationCommand;
import dev.stannismod.stellurgy.command.sub.universe.UniverseStatusCommand;
import dev.stannismod.stellurgy.command.sub.universe.UniverseUpgradeCommand;
import net.minecraft.block.Block;
import net.minecraft.command.ICommand;
import net.minecraft.command.ICommandSender;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.item.Item;
import net.minecraft.item.ItemBlock;
import net.minecraft.server.MinecraftServer;
import net.minecraft.util.ResourceLocation;
import net.minecraft.util.text.TextComponentString;
import net.minecraftforge.event.CommandEvent;
import net.minecraftforge.fml.common.eventhandler.EventPriority;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;
import net.minecraftforge.server.command.CommandTreeBase;
import net.minecraftforge.server.command.CommandTreeHelp;

import java.lang.management.ManagementFactory;
import java.util.*;

/**
 * The server half of an e2e run's MODE: every command the server is asked to run, judged before it runs,
 * and refused at the call when an e2e may not send it.
 *
 * <p>The harness starts every server child with its MODE as a JVM argument, {@code -D}{@value #MODE_PROPERTY}
 * {@code =e2e} or {@code =mechanics}. The gate reads the server's own LAUNCH ARGUMENTS once, when it is made
 * at the server's start, refuses to be made at all when a harness child carries no mode or the system
 * property disagrees with them (something rewrote the mode before the game started), and holds the answer
 * for the server's life ({@link ServerScoped}). A server that is no harness child (one a developer launched
 * with the probe on) is judged by nothing. A MECHANICS server is judged by {@link #mechanicsRefusalOf} and
 * tallies every command it is asked ({@link Tally}). In e2e mode a command runs only when it is:</p>
 * <ul>
 *   <li>a {@code /stellurgytest} verb whose {@link ProbeVerbKind} is admitted — a READ, a BUILD, the walk's
 *       own record, or a static call on an instrument {@link E2EInstrument} names (class AND method,
 *       exactly);</li>
 *   <li>one of the vanilla commands that hand the player what his start holds, in their shapes WITHOUT a
 *       data tag — {@code give} (no item {@link #UNOBTAINABLE} names), {@code clear}, {@code fill} (no such
 *       block, and no tile-entity tag) — so no command writes item or tile NBT a player never made;</li>
 *   <li>{@code stop}, by which the harness ends the server.</li>
 * </ul>
 * <p>Everything else — {@code tp}, {@code summon}, {@code execute}, Forge's and the mod's own roots — is
 * refused, whatever sent it and however its text was put together: this sees the command the server's
 * own handler resolved, not the test's source.</p>
 *
 * <p><b>A build has a time and a place.</b> What lays blocks or hands items — the probe's BUILD verbs and
 * vanilla {@code give}, {@code clear} and {@code fill} — lays the player's START: so it runs only BEFORE his
 * walk begins (the harness tells this gate, with {@code e2e walk-begins}, before his first input reaches
 * the client), and a block lands only in the dimension the player is in and within {@value #BUILD_REACH}
 * blocks of him on every axis. After the first input every block he stands among is one he placed. A
 * BUILD that is not a fixture lays no block {@link #UNOBTAINABLE} names; a fixture rocket is admitted
 * only as the named exception {@value #CREATIVE_PLUG_FIXTURE}, below. The one item of his start that
 * carries state a {@code give} cannot — the suit's air — is the second named exception,
 * {@value #FILLED_SPACE_CHEST}, admitted at the same time and only to the one player.</p>
 *
 * <p><b>The refusal is loud, and it is data.</b> To the harness's command bridge it is an
 * {@link E2EModeRefusal} thrown out of the command handler, which the bridge returns as a failed command —
 * so the test fails at the line that sent it, and the refusal's message is a JSON object a caller reads.
 * Every other sender — a player's chat, the console's stdin, a command block — is not thrown at, because
 * a throw there would land in the network handler or the server's own tick and crash it: the command is
 * cancelled, the refusal object is told to the sender, and it is logged as an error. A command typed into
 * the CONSOLE is also answered with one line, {@value #CONSOLE_JUDGED}{@code admitted|refused …}, which the
 * harness's console channel waits for, so a refusal there reaches the test too.</p>
 */
final class E2EModeGate {

    /** The prefix of the console's verdict line. The harness's own text, restated: a protocol. */
    static final String CONSOLE_JUDGED = "[e2e mode] console judged: ";

    /**
     * The sender the harness's command bridge runs every command as. The harness's own class, named here
     * because the mod does not compile against the test framework: a protocol, like {@link #MODE_PROPERTY}.
     */
    static final String HARNESS_BRIDGE_SENDER =
            "com.github.stannismod.forge.testing.server.bridge.ForgeTestServerBootstrap$CollectingCommandSender";

    /**
     * What separates the bridge sender's name from the {@link CommandRole} its command was sent in
     * ({@code ForgeTestBridge/act}). The harness's own spelling, restated: a protocol.
     */
    static final char HARNESS_BRIDGE_ROLE_SEPARATOR = '/';

    /**
     * How far from the player a build may lay a block, on each axis, in blocks: 32.
     *
     * <p>Measured from what the one e2e there is lays around its player: its home site clears the air 32
     * blocks above the floor he spawned on, and reaches 19 east and 15 south of him; its fixtures' origins
     * lie within that box. 32 is that site's largest extent, so the start M1 states fits and nothing farther
     * does. A FIXTURE is judged by its ORIGIN: the blocks it lays around that origin (a rocket's tower, a
     * multiblock's frame) reach a few blocks beyond it, as the fixture's own code lays them.</p>
     */
    static final int BUILD_REACH = 32;

    /**
     * THE NAMED EXCEPTION: the one rocket fixture an e2e may lay, which carries libVulpes' CREATIVE power
     * plug twice — on the jump capacitor, so the ship's own grid fills its bank, and above the assembler,
     * so the assembler can build. No player obtains that plug; it stands in for generation the player
     * would have built.
     *
     * <p>Why it is admitted (maintainer, 2026-10-09: "пока нет реактора, видимо, придётся ставить
     * креативную батарею. Больше тупо нечего.") — no craftable reactor or generator exists yet that can
     * charge a jump drive or run the assembler. <b>Its trigger: when a craftable reactor or generator
     * exists that can charge the drive, this exception is removed</b> and the fixture lays that machine
     * instead. Every other fixture rocket variant is refused in e2e mode, and {@code give} or {@code place}
     * of the plug stays refused ({@link #UNOBTAINABLE}).</p>
     */
    static final String CREATIVE_PLUG_FIXTURE = "with-powered-jump-drive";

    /**
     * THE SECOND NAMED EXCEPTION: the probe verb that hands the e2e's player one space chestplate whose
     * pressure tank is full of oxygen — the one item of his start no admitted {@code give} can carry. The
     * suit's air is the oxygen in a tank held in the chest's own inventory, which is item NBT on two levels
     * (the tank inside the chest, the fluid inside the tank), and a tagged {@code give} is refused here as
     * item state no player made. A survival player flown to an airless body without that air dies, so the
     * suit is part of his start (maintainer, 2026-10-12: "а, скафандр выдаётся на старте"). The other three
     * pieces carry no tag and are plain {@code give}s; he puts all four on himself.
     *
     * <p>Admitted like a build: before his walk begins, and only with exactly one player online. The verb
     * makes the chest through production's own component and filling paths. <b>Its trigger: when an e2e
     * walks the oxygen-filling path — a tank fitted into the chest at the suit workstation and filled on a
     * gas charge pad, by the player's own clicks — this exception is removed</b> and that path hands him
     * his air instead.</p>
     */
    static final String FILLED_SPACE_CHEST = "e2e filled-space-chest";

    /**
     * Blocks and items no player obtains in survival — the creative-only, the technical, the world's own
     * bedrock. Effectively final, the JVM's life — a constant: an unmodifiable set of strings over a
     * backing set nothing else references, written once at class initialisation.
     *
     * <p>Deny-listed by registry name, and the name compared is the one the text RESOLVES to, so
     * {@code bedrock}, {@code minecraft:bedrock} and a numeric id are one block — in lower case, which is
     * how a 1.12 {@code ResourceLocation} holds every name ({@code creativePowerBattery} is registered as
     * {@code libvulpes:creativepowerbattery}). It is a floor, not the
     * whole of "what a player could have": a block missing here is admitted, and the reviewer of a test's
     * start still reads what it lays.</p>
     */
    static final Set<String> UNOBTAINABLE = Collections.unmodifiableSet(new HashSet<>(Arrays.asList(
            "minecraft:bedrock", "minecraft:barrier", "minecraft:command_block",
            "minecraft:chain_command_block", "minecraft:repeating_command_block", "minecraft:structure_block",
            "minecraft:structure_void", "minecraft:end_portal_frame", "minecraft:end_portal",
            "minecraft:portal", "minecraft:end_gateway", "minecraft:mob_spawner", "minecraft:monster_egg",
            "minecraft:spawn_egg", "minecraft:command_block_minecart", "minecraft:knowledge_book",
            "libvulpes:creativepowerbattery", "libvulpes:phantom", "libvulpes:placeholder",
            "affs:admin_energy_source")));

    /** The first argument of a vanilla {@code fill} at which a data tag can begin. */
    private static final int FILL_TAG_FROM = 9;
    /** A vanilla {@code give} / {@code clear} carries its data tag from this argument on. */
    private static final int ITEM_TAG_FROM = 4;

    /** The property a harness child's mode travels in. The harness's own name for it, restated: a protocol. */
    static final String MODE_PROPERTY = "forge.test.mode";
    /** Where a MECHANICS child writes its tally. The harness's own name for it, restated: a protocol. */
    static final String COUNTING_DIR_PROPERTY = "forge.test.counting.dir";
    /** The launch argument every harness server child carries. The harness's own, restated: a protocol. */
    static final String HARNESS_CHILD_ARGUMENT = "-Dforge.test.server=true";

    /** Which mode this server was launched in. */
    enum Mode {
        /** A harness child that serves an e2e: refuses at the call what a player could not do. */
        E2E,
        /** A harness child of a mechanics test: judged, and counted. */
        MECHANICS,
        /** No harness child at all — a server a developer launched with the probe on: judges nothing. */
        NONE
    }

    /** The mode THIS server was launched in — read once, when the gate is made at the server's start. */
    private final Mode mode;

    /** Whether THIS server serves an e2e — read once, when the gate is made at the server's start. */
    private final boolean servesAnE2E;

    /** What a MECHANICS server was asked, by key; {@code null} on any other. Lives with the gate. */
    private final Tally tally;

    /**
     * Whether the e2e's player has made his first input — told once by the harness ({@code e2e walk-begins})
     * and never untold: from then on no build is admitted. Lives with the gate, which lives with the server.
     */
    private boolean walkBegan;

    /**
     * The commands admitted at HIGHEST and not yet judged at LOWEST, by the identity of their event: one
     * entry per command between the two listeners (a listener that runs a command of its own nests one
     * more). Lives with the gate; an entry leaves at LOWEST, which every posted event reaches.
     */
    private final Map<CommandEvent, Admitted> inFlight = new IdentityHashMap<>();

    E2EModeGate() {
        this.mode = launchedMode();
        this.servesAnE2E = mode == Mode.E2E;
        this.tally = mode == Mode.MECHANICS ? new Tally() : null;
        Stellurgy.logger.info("[harness mode] this server was launched in mode " + mode);
    }

    /**
     * The mode this JVM was LAUNCHED in, read off its own launch arguments, which nothing running in it can
     * change. Throws — the server does not start — when a harness child carries no mode (an ungoverned
     * child does not exist), names none the gate knows, or its system property disagrees.
     */
    private static Mode launchedMode() {
        String prefix = "-D" + MODE_PROPERTY + "=";
        List<String> launch = ManagementFactory.getRuntimeMXBean().getInputArguments();
        String word = null;
        for (String argument : launch) {
            if (argument.startsWith(prefix)) {
                if (word != null && !word.equals(argument.substring(prefix.length()))) {
                    throw new IllegalStateException("REFUSED to start the mode gate: this server was launched with"
                            + " two modes, " + word + " and " + argument);
                }
                word = argument.substring(prefix.length());
            }
        }
        if (word == null) {
            if (launch.contains(HARNESS_CHILD_ARGUMENT)) {
                throw new IllegalStateException("REFUSED to start the mode gate: this harness server child was"
                        + " launched with no " + prefix + "e2e|mechanics -- a child that does not know which mode"
                        + " it serves serves none, and an ungoverned child is not started");
            }
            return Mode.NONE;
        }
        if (!word.equals(System.getProperty(MODE_PROPERTY))) {
            throw new IllegalStateException("REFUSED to start the mode gate: this server was launched in mode " + word
                    + " and its property " + MODE_PROPERTY + " is now " + System.getProperty(MODE_PROPERTY)
                    + " -- something rewrote the mode before the server started");
        }
        if ("e2e".equals(word)) {
            return Mode.E2E;
        }
        if ("mechanics".equals(word)) {
            return Mode.MECHANICS;
        }
        throw new IllegalStateException("REFUSED to start the mode gate: " + prefix + word + " names no mode");
    }

    boolean servesAnE2E() {
        return servesAnE2E;
    }

    /** The harness's word that the player's walk has begun. */
    void walkBegins() {
        if (servesAnE2E && !walkBegan) {
            Stellurgy.logger.info("[e2e mode] the player's walk has begun: no build is admitted from here on");
        }
        walkBegan = true;
    }

    // HIGHEST: decided before any listener that redirects or rewrites a command acts on it.
    @SubscribeEvent(priority = EventPriority.HIGHEST)
    public void onCommand(CommandEvent event) {
        if (mode == Mode.NONE) {
            return;
        }
        ICommandSender sender = event.getSender();
        if (mode == Mode.MECHANICS) {
            tally.saw(Tally.keyOf(event.getCommand(), event.getParameters()));
        }
        String why = judged(event.getCommand(), event.getParameters(), sender);
        if (why == null) {
            inFlight.put(event, new Admitted(event.getCommand(), event.getParameters()));
            return;
        }
        refuse(event, sender, event.getCommand(), event.getParameters(), why);
    }

    /**
     * The LAST word on a command the HIGHEST listener admitted: it is judged again, as it will RUN. A
     * listener between the two may have written into the arguments (the handler runs the very array the
     * event carries, so a store into it changes what runs) or handed the event a new array; the handler
     * runs the COMMAND it resolved before the event, whatever a listener sets, so that is the command
     * judged. On a cancelled event too, which runs nothing: its console verdict is still owed.
     *
     * <p>A listener at LOWEST registered after this gate still acts after it; the build refuses a test
     * subscriber the command's argument array (its {@code getParameters}), so none of those can write it.</p>
     */
    @SubscribeEvent(priority = EventPriority.LOWEST, receiveCanceled = true)
    public void onCommandAsItWillRun(CommandEvent event) {
        Admitted admitted = inFlight.remove(event);
        if (admitted == null) {
            return;
        }
        ICommandSender sender = event.getSender();
        String[] args = event.getParameters() != null ? event.getParameters() : admitted.args;
        String why = null;
        if (!event.isCanceled()) {
            why = judged(admitted.command, args, sender);
        }
        if (why == null) {
            // As before the second judgement: a console verdict for an admitted command is an e2e server's.
            if (mode == Mode.E2E && sender instanceof MinecraftServer) {
                Stellurgy.logger.info(CONSOLE_JUDGED + "admitted " + commandLine(admitted.command, args));
            }
            return;
        }
        // The same refusal object, marked as the second judgement: what was admitted is not what runs.
        refuse(event, sender, admitted.command, args, "{\"judgedAsItWillRun\":true," + why.substring(1));
    }

    /**
     * Why {@code command} with {@code args} from {@code sender} is refused on this server, as a JSON object;
     * {@code null} when it is admitted: first the role a harness command was sent in, then this mode's own
     * judgement.
     */
    private String judged(ICommand command, String[] args, ICommandSender sender) {
        String why = roleRefusalOf(command, args, sender);
        if (why != null) {
            return why;
        }
        return mode == Mode.MECHANICS ? mechanicsRefusalOf(command, args) : refusalOf(command, args, sender);
    }

    /**
     * Why the role a harness bridge command was sent in does not admit what the command is, as a JSON
     * object; {@code null} when it does, or when the sender is not the harness's bridge (a player, the
     * console, a command a command runs). The role travels in the bridge sender's name, after
     * {@link #HARNESS_BRIDGE_ROLE_SEPARATOR}. A probe call has the one kind {@link ProbeVerbKind#formOf} gives
     * its form; a command of any other root has the one kind {@link #rootKind} gives its form. An unclassified probe verb is left
     * to the mode's judgement, which refuses it naming why; so is a root with no kind on an e2e server, which
     * refuses every root it does not name. On a mechanics server a bridge command of a root with no kind is
     * refused here — only the bridge's: the console's {@code stop} and a player's own commands are not sent
     * in a role.
     */
    String roleRefusalOf(ICommand command, String[] args, ICommandSender sender) {
        if (!HARNESS_BRIDGE_SENDER.equals(sender.getClass().getName())) {
            return null;
        }
        String name = command.getName().toLowerCase(Locale.ROOT);
        boolean probe = "stellurgytest".equals(name);
        String verb = probe ? verbOf(args) : name;
        String sent = sender.getName();
        int separator = sent.indexOf(HARNESS_BRIDGE_ROLE_SEPARATOR);
        CommandRole role = separator < 0 ? null : CommandRole.named(sent.substring(separator + 1));
        if (role == null) {
            return refusal(name, verb, "NO_ROLE", "a harness command is sent in a role -- read, arrange or act --"
                    + " and this one names none (the bridge sender is " + sent + ")");
        }
        ProbeVerbKind kind = probe ? ProbeVerbKind.formOf(args) : rootKind(command, args);
        if (kind == ProbeVerbKind.UNCLASSIFIED) {
            return probe || servesAnE2E ? null : refusal(name, verb, ProbeVerbKind.UNCLASSIFIED.name(),
                    "no kind is declared for this command root, so no role admits it");
        }
        if (role.admits(kind)) {
            return null;
        }
        return refusal(name, verb, "ROLE", "sent to " + role.name().toLowerCase(Locale.ROOT) + ", but it is "
                + kind + ": read admits READ and a named instrument, arrange admits ARRANGE, BUILD and DECIDE,"
                + " act admits TRIGGER");
    }

    /**
     * The kind of a command that is not the probe: what the vanilla and the mod's own command a test sends
     * IS, read as {@link ProbeVerbKind} reads a verb. One concrete command has ONE kind: a root whose
     * arguments choose between a read and a write ({@code time}, {@code gamerule}, the mod's own tree) is
     * judged on those arguments, so the role a test sends it in is checked against the form it is. A root,
     * or a form of one, with no row is {@link ProbeVerbKind#UNCLASSIFIED}.
     */
    static ProbeVerbKind rootKind(ICommand command, String[] args) {
        switch (command.getName().toLowerCase(Locale.ROOT)) {
            case "testforblock":
            case "testfor":
            case "list":
            case "help":
                return ProbeVerbKind.READ;
            case "time":
                return timeKind(args);
            // Vanilla's CommandGameRule#execute: no word lists the rules and one word reads a rule; a name and
            // a value set it (every word after the name is the value).
            case "gamerule":
                return args.length < 2 ? ProbeVerbKind.READ : ProbeVerbKind.ARRANGE;
            // Registered as `stellurgy`, with `ar` and `advrocketry` as aliases: the handler resolves every one
            // of them to this command, whose name is the one seen here.
            case "stellurgy":
                return modCommandKind(command, args);
            // The save path every autosave takes, with the persistence hooks it reaches.
            case "save-all":
                return ProbeVerbKind.TRIGGER;
            case "tp":
            case "teleport":
            case "give":
            case "clear":
            case "kill":
            case "summon":
            case "setblock":
            case "fill":
            case "replaceitem":
            case "setworldspawn":
            case "spawnpoint":
            case "weather":
            case "gamemode":
            case "difficulty":
            case "effect":
            case "xp":
            case "op":
            case "deop":
            case "kick":
            case "tellraw":
            case "say":
            case "save-on":
            case "save-off":
            case "blockdata":
            case "entitydata":
                return ProbeVerbKind.ARRANGE;
            default:
                return ProbeVerbKind.UNCLASSIFIED;
        }
    }

    /**
     * Vanilla's CommandTime#execute, word for word: with a second word, {@code query} reads a world's clock,
     * {@code set} and {@code add} write every world's. Every other shape only throws its usage, and has no row.
     */
    private static ProbeVerbKind timeKind(String[] args) {
        if (args.length < 2) {
            return ProbeVerbKind.UNCLASSIFIED;
        }
        switch (args[0]) {
            case "query":
                return ProbeVerbKind.READ;
            case "set":
            case "add":
                return ProbeVerbKind.ARRANGE;
            default:
                return ProbeVerbKind.UNCLASSIFIED;
        }
    }

    /**
     * The kind of a command of the mod's own tree, resolved word by word the way Forge's
     * {@code CommandTreeBase#execute} resolves it — {@code getSubCommand}, a name or an alias, case as typed —
     * down to the leaf that will run, and judged by that leaf's row ({@link #modLeafKind}). A tree given no
     * further word only lists its subcommands, and the help tree only prints usage: both READ. A word no tree
     * knows has no row: nobody has said what a command that is not there is.
     */
    private static ProbeVerbKind modCommandKind(ICommand root, String[] args) {
        ICommand node = root;
        int depth = 0;
        while (node instanceof CommandTreeBase) {
            if (node instanceof CommandTreeHelp || depth == args.length) {
                return ProbeVerbKind.READ;
            }
            node = ((CommandTreeBase) node).getSubCommand(args[depth]);
            depth++;
            if (node == null) {
                return ProbeVerbKind.UNCLASSIFIED;
            }
        }
        return modLeafKind(node, Arrays.copyOfRange(args, depth, args.length));
    }

    /**
     * The table of the mod's own command leaves, each row a claim about its handler's {@code execute}, traced
     * when it was written (2026-10-14). A leaf with no row is {@link ProbeVerbKind#UNCLASSIFIED}.
     */
    private static ProbeVerbKind modLeafKind(ICommand leaf, String[] args) {
        // Lookups that only message the sender. `planet weather` creates the dimension's weather record if it
        // has none — the empty record the dimension's own load creates — as a READ resolves its dimension.
        if (leaf instanceof PlanetListCommand || leaf instanceof PlanetGetCommand
                || leaf instanceof PlanetWeatherCommand || leaf instanceof StarListCommand
                || leaf instanceof StarGetCommand || leaf instanceof UniverseStatusCommand) {
            return ProbeVerbKind.READ;
        }
        // Without a word it previews the upgrade; with one it pins every carried crystal's systems, adopts the
        // pack's schema and arms the upgrade (or throws its usage).
        if (leaf instanceof UniverseUpgradeCommand) {
            return args.length == 0 ? ProbeVerbKind.READ : ProbeVerbKind.ARRANGE;
        }
        // Operator writes of world, player and config state: a planet's or a star's properties, a planet or a
        // star registered or deleted, a station registered with its platform and chip, an item handed or
        // filled, the weather, an entity's gravity, the sealant and torch lists, the machine recipe tables,
        // a player moved, and a file written into the server's directory (`dev dumpBiomes`).
        if (leaf instanceof PlanetSetCommand || leaf instanceof PlanetResetCommand
                || leaf instanceof PlanetGenerateCommand || leaf instanceof PlanetDeleteCommand
                || leaf instanceof StarSetCommand || leaf instanceof StarGenerateCommand
                || leaf instanceof CreateStationCommand || leaf instanceof GiveStationCommand
                || leaf instanceof FillDataCommand || leaf instanceof WeatherCommand
                || leaf instanceof SetGravityCommand || leaf instanceof AddSealantCommand
                || leaf instanceof AddTorchCommand || leaf instanceof ReloadRecipesCommand
                || leaf instanceof FetchCommand || leaf instanceof GoToDimensionCommand
                || leaf instanceof GoToStationCommand || leaf instanceof DumpBiomesCommand) {
            return ProbeVerbKind.ARRANGE;
        }
        // Lays a launch pad and a rocket around the sender, then calls the rocket's own launch.
        if (leaf instanceof RunTestsCommand) {
            return ProbeVerbKind.TRIGGER;
        }
        return ProbeVerbKind.UNCLASSIFIED;
    }

    /** What the HIGHEST listener admitted: the command the handler will run, and the array it judged. */
    private static final class Admitted {
        final ICommand command;
        final String[] args;

        Admitted(ICommand command, String[] args) {
            this.command = command;
            this.args = args;
        }
    }

    private void refuse(CommandEvent event, ICommandSender sender, ICommand command, String[] args, String why) {
        if (sender instanceof MinecraftServer) {
            Stellurgy.logger.info(CONSOLE_JUDGED + "refused " + commandLine(command, args) + " " + why);
        }
        event.setCanceled(true);
        if (HARNESS_BRIDGE_SENDER.equals(sender.getClass().getName())) {
            event.setException(new E2EModeRefusal(why));
            return;
        }
        String label = mode == Mode.E2E ? "[e2e mode]" : "[mechanics mode]";
        Stellurgy.logger.error(label + " refused a command from " + sender.getName() + ": " + why);
        sender.sendMessage(new TextComponentString(label + " refused: " + why));
    }

    private static String commandLine(ICommand command, String[] args) {
        StringBuilder out = new StringBuilder(command.getName());
        for (String arg : args) {
            out.append(' ').append(arg);
        }
        return out.toString();
    }

    /**
     * Why {@code command} with {@code args}, sent by {@code sender}, is refused in e2e mode, as a JSON object;
     * {@code null} when it is admitted.
     */
    String refusalOf(ICommand command, String[] args, ICommandSender sender) {
        String name = command.getName().toLowerCase(Locale.ROOT);
        if ("stellurgytest".equals(name)) {
            ProbeVerbKind kind = ProbeVerbKind.of(args);
            String verb = verbOf(args);
            if (!kind.admittedInE2E()) {
                return refusal(name, verb, kind.name(), kind == ProbeVerbKind.UNCLASSIFIED
                        ? "no kind is declared for this verb, and an unclassified verb is refused"
                        : "an e2e's probes only READ, BUILD, or call a named instrument");
            }
            if (kind == ProbeVerbKind.STATIC_CALL) {
                return staticCallRefusal(name, verb, args);
            }
            if (kind == ProbeVerbKind.BUILD) {
                return buildRefusal(name, verb, args, sender);
            }
            if (kind == ProbeVerbKind.START_ITEM) {
                return startItemRefusal(name, verb, sender);
            }
            return null;
        }
        switch (name) {
            case "give":
            case "clear":
            case "fill": {
                String late = afterTheWalk(name, name);
                if (late != null) {
                    return late;
                }
                break;
            }
            default:
                break;
        }
        switch (name) {
            case "give":
                if (args.length > ITEM_TAG_FROM) {
                    return refusal(name, name, "COMMAND", "a give with a data tag hands item state no player made");
                }
                return args.length > 1 ? unobtainableItem(name, args[1]) : null;
            case "clear":
                return args.length > ITEM_TAG_FROM
                        ? refusal(name, name, "COMMAND", "a clear with a data tag is not a shape an e2e needs")
                        : null;
            case "fill":
                for (int i = FILL_TAG_FROM; i < args.length; i++) {
                    if (args[i].indexOf('{') >= 0) {
                        return refusal(name, name, "COMMAND",
                                "a fill with a data tag writes tile state no player made");
                    }
                }
                if (args.length > 6) {
                    String unobtainable = unobtainableBlock(name, name, args[6]);
                    if (unobtainable != null) {
                        return unobtainable;
                    }
                }
                return vanillaFillPlace(name, args, sender);
            case "stop":
                return null;
            case "time":
                // The operator's skip is the PLAYER's act: typed in his own chat, as the owner of the server he
                // plays on (his start makes him its operator). From the console or the harness's bridge it would
                // be the test moving the universe, so there it stays refused.
                return sender instanceof net.minecraft.entity.player.EntityPlayerMP ? null
                        : refusal(name, name, "COMMAND", "the time skip is a player's act, typed in his own chat");
            default:
                return refusal(name, name, "COMMAND",
                        "an e2e sends only the probe's admitted verbs, give, clear and fill, and the player's own time skip");
        }
    }

    /**
     * Why {@code command} with {@code args} is refused on a MECHANICS server, as a JSON object; {@code null}
     * when it is admitted. A mechanics test may arrange, trigger and decide, so this refuses only three
     * channels:
     * <ul>
     *   <li>a probe verb with no row in {@link ProbeVerbKind} — {@link ProbeVerbKind#UNCLASSIFIED}: nobody has
     *       said what it is;</li>
     *   <li>a static call ({@code invoke-static}) on anything but a row of {@link MechanicsInstrument} — a
     *       static that is no named instrument calls a decision of the mod from the test's side;</li>
     *   <li>tile, entity or item NBT written by command — {@code blockdata}, {@code entitydata}, and
     *       {@code give} / {@code summon} / {@code fill} / {@code clear} / {@code replaceitem} carrying a data
     *       tag: an arrangement sets state through a typed probe verb ({@code arrange …}) that goes through
     *       production's own setter, never through a tag nothing checks.</li>
     * </ul>
     * Everything else is admitted (and tallied by {@link Tally}).
     */
    String mechanicsRefusalOf(ICommand command, String[] args) {
        String name = command.getName().toLowerCase(Locale.ROOT);
        if ("stellurgytest".equals(name)) {
            if (ProbeVerbKind.of(args) == ProbeVerbKind.UNCLASSIFIED) {
                return refusal(name, verbOf(args), ProbeVerbKind.UNCLASSIFIED.name(),
                        "no kind is declared for this verb, and an unclassified verb is refused");
            }
            if (args.length > 0 && "invoke-static".equalsIgnoreCase(args[0])) {
                String className = args.length > 1 ? args[1] : "";
                String methodName = args.length > 2 ? args[2] : "";
                if (MechanicsInstrument.named(className, methodName) == null) {
                    return refusal(name, "invoke-static", ProbeVerbKind.STATIC_CALL.name(), className + "#" + methodName
                            + " is no mechanics instrument " + MechanicsInstrument.listing() + " — a static call that"
                            + " is not a named instrument calls a decision of the mod from the test's side");
                }
            }
            return null;
        }
        switch (name) {
            case "blockdata":
            case "entitydata":
                return refusal(name, name, "COMMAND", "writes tile or entity NBT by command; arrange that state"
                        + " through a typed probe verb that goes through production's own setter");
            case "give":
            case "summon":
            case "fill":
            case "clear":
            case "replaceitem":
                for (String arg : args) {
                    if (arg.indexOf('{') >= 0) {
                        return refusal(name, name, "COMMAND", "a " + name + " with a data tag writes item, tile or"
                                + " entity state by command; arrange it through a typed probe verb");
                    }
                }
                return null;
            default:
                return null;
        }
    }

    /** A static call is admitted on a named instrument only, matched on the strings it carries. */
    private static String staticCallRefusal(String name, String verb, String[] args) {
        String className = args.length > 1 ? args[1] : "";
        String methodName = args.length > 2 ? args[2] : "";
        if (E2EInstrument.named(className, methodName) != null) {
            return null;
        }
        return refusal(name, verb, ProbeVerbKind.STATIC_CALL.name(),
                className + "#" + methodName + " is no e2e instrument " + E2EInstrument.listing()
                        + " — a static call that is not a named instrument calls a decision the player never made");
    }

    /**
     * A named start item ({@link #FILLED_SPACE_CHEST}) is handed as a build is laid: before the walk, to the
     * one player online.
     */
    private String startItemRefusal(String name, String verb, ICommandSender sender) {
        String late = afterTheWalk(name, verb);
        if (late != null) {
            return late;
        }
        MinecraftServer server = sender.getServer();
        int online = server == null ? 0 : server.getPlayerList().getPlayers().size();
        return online == 1 ? null : refusal(name, verb, "NO_ONE_PLAYER", "a start item is handed to THE player,"
                + " and " + online + " players are online");
    }

    /** Refused once the walk has begun: a build lays the player's start, and only the start. */
    private String afterTheWalk(String name, String verb) {
        return walkBegan ? refusal(name, verb, "AFTER_THE_WALK_BEGAN", "a build lays the player's START, before"
                + " his first input; after it every block and item he has is one he placed or picked up") : null;
    }

    /**
     * A BUILD lays the player's start: before his walk, in his dimension, within {@link #BUILD_REACH} of
     * him. A BUILD that is not a fixture lays the block it names, which must be one a player can have; a
     * fixture rocket is admitted only as {@link #CREATIVE_PLUG_FIXTURE}.
     */
    private String buildRefusal(String name, String verb, String[] args, ICommandSender sender) {
        String late = afterTheWalk(name, verb);
        if (late != null) {
            return late;
        }
        String first = args.length > 0 ? args[0].toLowerCase(Locale.ROOT) : "";
        int dimAt;
        int boxAt;
        int corners;
        if ("place".equals(first)) {
            dimAt = 1;
            boxAt = 2;
            corners = 1;
            if (args.length > 5) {
                String unobtainable = unobtainableBlock(name, verb, args[5]);
                if (unobtainable != null) {
                    return unobtainable;
                }
            }
        } else if ("fill".equals(first)) {
            dimAt = 1;
            boxAt = 2;
            corners = 2;
            if (args.length > 8) {
                String unobtainable = unobtainableBlock(name, verb, args[8]);
                if (unobtainable != null) {
                    return unobtainable;
                }
            }
        } else if ("fixture".equals(first) && args.length > 1 && "rocket".equalsIgnoreCase(args[1])) {
            String variant = args.length > 6 ? args[6].toLowerCase(Locale.ROOT) : "simple";
            if (!CREATIVE_PLUG_FIXTURE.equals(variant)) {
                return refusal(name, verb, "NOT_THE_NAMED_FIXTURE", "the fixture rocket '" + variant + "' is not "
                        + CREATIVE_PLUG_FIXTURE + ", the one an e2e may lay (the named creative-plug exception)");
            }
            dimAt = 2;
            boxAt = 3;
            corners = 1;
        } else if ("fixture".equals(first)) {
            dimAt = 3;
            boxAt = 4;
            corners = 1;
        } else {
            return refusal(name, verb, "BUILD", "a build this gate cannot place in the world it lays into");
        }
        int[] numbers = numbers(args, dimAt, 1 + 3 * corners);
        if (numbers == null) {
            return refusal(name, verb, "ARGUMENTS", "a build names its dimension and coordinates as whole numbers");
        }
        int[] box = Arrays.copyOfRange(numbers, 1, numbers.length);
        return placeRefusal(name, verb, sender, numbers[0], box);
    }

    /** A vanilla {@code fill}: in the sender's world, at absolute coordinates, around the player. */
    private String vanillaFillPlace(String name, String[] args, ICommandSender sender) {
        int[] box = numbers(args, 0, 6);
        if (box == null) {
            return refusal(name, name, "ARGUMENTS", "an e2e's fill states its six coordinates as whole numbers;"
                    + " a relative one (~) is relative to a sender that is not the player");
        }
        int dim = sender.getEntityWorld() == null ? Integer.MIN_VALUE
                : sender.getEntityWorld().provider.getDimension();
        return placeRefusal(name, name, sender, dim, box);
    }

    /** {@code count} integers of {@code args} from {@code from}, or {@code null} when any is not one. */
    private static int[] numbers(String[] args, int from, int count) {
        if (args.length < from + count) {
            return null;
        }
        int[] out = new int[count];
        for (int i = 0; i < count; i++) {
            try {
                out[i] = Integer.parseInt(args[from + i]);
            } catch (NumberFormatException notANumber) {
                return null;
            }
        }
        return out;
    }

    /** The box (one corner or two) lies in the player's dimension, within {@link #BUILD_REACH} of him. */
    private static String placeRefusal(String name, String verb, ICommandSender sender, int dim, int[] box) {
        MinecraftServer server = sender.getServer();
        List<EntityPlayerMP> players = server == null ? Collections.<EntityPlayerMP>emptyList()
                : server.getPlayerList().getPlayers();
        if (players.size() != 1) {
            return refusal(name, verb, "NO_ONE_PLAYER", "a build lays THE player's start around him, and "
                    + players.size() + " players are online");
        }
        EntityPlayerMP player = players.get(0);
        int playerDim = player.dimension;
        if (dim != playerDim) {
            return refusal(name, verb, "NOT_THE_PLAYERS_DIMENSION", "the build is in dimension " + dim
                    + " and the player is in " + playerDim);
        }
        int px = (int) Math.floor(player.posX);
        int py = (int) Math.floor(player.posY);
        int pz = (int) Math.floor(player.posZ);
        for (int corner = 0; corner + 2 < box.length; corner += 3) {
            int dx = Math.abs(box[corner] - px);
            int dy = Math.abs(box[corner + 1] - py);
            int dz = Math.abs(box[corner + 2] - pz);
            if (dx > BUILD_REACH || dy > BUILD_REACH || dz > BUILD_REACH) {
                return refusal(name, verb, "OUT_OF_REACH", "the build reaches (" + box[corner] + "," + box[corner + 1]
                        + "," + box[corner + 2] + "), and the player stands at (" + px + "," + py + "," + pz
                        + "): more than " + BUILD_REACH + " blocks on an axis");
            }
        }
        return null;
    }

    private static String unobtainableBlock(String name, String verb, String text) {
        Block block = Block.getBlockFromName(text);
        if (block == null || block.getRegistryName() == null) {
            return null;
        }
        String id = block.getRegistryName().toString();
        return UNOBTAINABLE.contains(id) ? refusal(name, verb, "UNOBTAINABLE",
                "the block " + id + " is none a player obtains in survival") : null;
    }

    private static String unobtainableItem(String name, String text) {
        Item item = Item.getByNameOrId(text);
        if (item == null) {
            return null;
        }
        ResourceLocation id = item.getRegistryName();
        if (id != null && UNOBTAINABLE.contains(id.toString())) {
            return refusal(name, name, "UNOBTAINABLE", "the item " + id + " is none a player obtains in survival");
        }
        if (item instanceof ItemBlock) {
            ResourceLocation block = ((ItemBlock) item).getBlock().getRegistryName();
            if (block != null && UNOBTAINABLE.contains(block.toString())) {
                return refusal(name, name, "UNOBTAINABLE",
                        "the item places " + block + ", which no player obtains in survival");
            }
        }
        return null;
    }

    /** The verb's words, as matched — at most three, never its arguments past them. */
    private static String verbOf(String[] args) {
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < args.length && i < 3; i++) {
            if (i > 0) {
                out.append(' ');
            }
            out.append(args[i]);
        }
        return out.toString();
    }

    private static String refusal(String command, String verb, String kind, String why) {
        return "{\"refused\":true,\"command\":\"" + escape(command) + "\",\"verb\":\"" + escape(verb)
                + "\",\"kind\":\"" + kind + "\",\"why\":\"" + escape(why) + "\"}";
    }

    private static String escape(String text) {
        return text.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    /**
     * What a MECHANICS server was asked: every distinct command key with how often, written to
     * {@code <counting dir>/server-<pid>.txt} when a key is first seen, every {@value #REWRITE_EVERY} calls,
     * and when the JVM exits. One per gate, so one per server; a server launched without a counting directory
     * says so once in its log and writes nothing.
     */
    static final class Tally {
        /** How many calls may pass between two writes of the file. */
        private static final int REWRITE_EVERY = 100;

        private final java.util.Map<String, Integer> seen = new java.util.TreeMap<>();
        private final java.nio.file.Path file;
        private int calls;

        Tally() {
            String dir = System.getProperty(COUNTING_DIR_PROPERTY);
            if (dir == null || dir.isEmpty()) {
                file = null;
                Stellurgy.logger.warn("[mechanics mode] no " + COUNTING_DIR_PROPERTY
                        + ": what this server is asked is NOT counted");
                return;
            }
            String pid = ManagementFactory.getRuntimeMXBean().getName().split("@")[0];
            file = java.nio.file.Paths.get(dir).resolve("server-" + pid + ".txt");
            Runtime.getRuntime().addShutdownHook(new Thread(this::write, "mechanics-tally-server"));
        }

        /**
         * A command's key: {@code probe <verb words>} for the mod's probe (its leading words that read as a verb,
         * at most three; a static call adds the {@code class#method} it names), {@code vanilla <root>} for any
         * other command, with {@code {tag}} when an argument carries a data tag.
         */
        static String keyOf(ICommand command, String[] args) {
            String name = command.getName().toLowerCase(Locale.ROOT);
            boolean tagged = false;
            for (String arg : args) {
                tagged |= arg.indexOf('{') >= 0;
            }
            if (!"stellurgytest".equals(name)) {
                return "vanilla " + name + (tagged ? " {tag}" : "");
            }
            StringBuilder key = new StringBuilder("probe");
            int words = 0;
            while (words < args.length && words < 3 && args[words].matches("[A-Za-z][A-Za-z-]*")) {
                key.append(' ').append(args[words].toLowerCase(Locale.ROOT));
                words++;
            }
            if (words >= 1 && "invoke-static".equalsIgnoreCase(args[0]) && args.length > 2) {
                key.setLength(0);
                key.append("probe invoke-static ").append(args[1]).append('#').append(args[2]);
            }
            return key.append(tagged ? " {tag}" : "").toString();
        }

        void saw(String key) {
            boolean first;
            boolean due;
            synchronized (this) {
                first = seen.merge(key, 1, Integer::sum) == 1;
                due = ++calls % REWRITE_EVERY == 0;
            }
            if (first || due) {
                write();
            }
        }

        private synchronized void write() {
            if (file == null) {
                return;
            }
            StringBuilder text = new StringBuilder();
            for (java.util.Map.Entry<String, Integer> e : seen.entrySet()) {
                text.append(e.getValue()).append('\t').append(e.getKey()).append('\n');
            }
            try {
                java.nio.file.Files.createDirectories(file.getParent());
                java.nio.file.Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
                java.nio.file.Files.write(tmp, text.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8));
                java.nio.file.Files.move(tmp, file, java.nio.file.StandardCopyOption.REPLACE_EXISTING,
                        java.nio.file.StandardCopyOption.ATOMIC_MOVE);
            } catch (java.io.IOException e) {
                Stellurgy.logger.warn("[mechanics mode] the tally could not be written to " + file + ": " + e);
            }
        }
    }

    /**
     * A command refused because the server serves an e2e. Its message is the refusal as a JSON object:
     * {@code refused}, {@code command}, {@code verb}, {@code kind}, {@code why}.
     */
    static final class E2EModeRefusal extends RuntimeException {
        private static final long serialVersionUID = 1L;

        E2EModeRefusal(String refusalJson) {
            super(refusalJson);
        }
    }
}
