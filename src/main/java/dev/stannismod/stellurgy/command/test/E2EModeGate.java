package dev.stannismod.stellurgy.command.test;

import dev.stannismod.stellurgy.Stellurgy;
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

import java.lang.management.ManagementFactory;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * The server half of an e2e run's MODE: every command the server is asked to run, judged before it runs,
 * and refused at the call when an e2e may not send it.
 *
 * <p>The harness starts a server that serves an e2e with the JVM argument {@code -D}{@value #E2E_PROPERTY}
 * {@code =true}. The gate reads the server's own LAUNCH ARGUMENTS once, when it is made at the server's
 * start, refuses to be made at all when the system property disagrees with them (something rewrote the mode
 * before the game started), and holds the answer for the server's life ({@link ServerScoped}). On every
 * other server it answers nothing. In e2e mode a command runs only when it is:</p>
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
 * only as the named exception {@value #CREATIVE_PLUG_FIXTURE}, below.</p>
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

    /** Set on a server child that serves an e2e. The harness's own name for it, restated: a protocol. */
    static final String E2E_PROPERTY = "forge.test.e2e";

    /** The prefix of the console's verdict line. The harness's own text, restated: a protocol. */
    static final String CONSOLE_JUDGED = "[e2e mode] console judged: ";

    /**
     * The sender the harness's command bridge runs every command as. The harness's own class, named here
     * because the mod does not compile against the test framework: a protocol, like {@link #E2E_PROPERTY}.
     */
    static final String HARNESS_BRIDGE_SENDER =
            "com.github.stannismod.forge.testing.server.bridge.ForgeTestServerBootstrap$CollectingCommandSender";

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

    /** Whether THIS server serves an e2e — read once, when the gate is made at the server's start. */
    private final boolean servesAnE2E;

    /**
     * Whether the e2e's player has made his first input — told once by the harness ({@code e2e walk-begins})
     * and never untold: from then on no build is admitted. Lives with the gate, which lives with the server.
     */
    private boolean walkBegan;

    E2EModeGate() {
        this.servesAnE2E = launchedForAnE2E();
    }

    /**
     * Whether this JVM was LAUNCHED to serve an e2e, read off its own launch arguments, which nothing
     * running in it can change. Throws — the server does not start — when the system property disagrees.
     */
    private static boolean launchedForAnE2E() {
        String argument = "-D" + E2E_PROPERTY + "=true";
        List<String> launch = ManagementFactory.getRuntimeMXBean().getInputArguments();
        boolean launched = launch.contains(argument);
        boolean property = Boolean.getBoolean(E2E_PROPERTY);
        if (launched != property) {
            throw new IllegalStateException("REFUSED to start the e2e gate: this server was launched "
                    + (launched ? "WITH" : "WITHOUT") + " " + argument + " and its property " + E2E_PROPERTY
                    + " is now " + System.getProperty(E2E_PROPERTY) + " -- something rewrote the mode before"
                    + " the server started, and a server that cannot tell which mode it serves serves none");
        }
        return launched;
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
        if (!servesAnE2E) {
            return;
        }
        ICommandSender sender = event.getSender();
        String why = refusalOf(event.getCommand(), event.getParameters(), sender);
        if (sender instanceof MinecraftServer) {
            Stellurgy.logger.info(CONSOLE_JUDGED + (why == null ? "admitted " : "refused ")
                    + commandLine(event.getCommand(), event.getParameters()) + (why == null ? "" : " " + why));
        }
        if (why == null) {
            return;
        }
        event.setCanceled(true);
        if (HARNESS_BRIDGE_SENDER.equals(sender.getClass().getName())) {
            event.setException(new E2EModeRefusal(why));
            return;
        }
        Stellurgy.logger.error("[e2e mode] refused a command from " + sender.getName() + ": " + why);
        sender.sendMessage(new TextComponentString("[e2e mode] refused: " + why));
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
            default:
                return refusal(name, name, "COMMAND",
                        "an e2e sends only the probe's admitted verbs and give, clear and fill");
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
