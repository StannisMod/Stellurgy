package dev.stannismod.stellurgy.command.test;

import java.util.EnumSet;
import java.util.Locale;
import java.util.Set;

/**
 * What a {@code /stellurgytest} verb IS — the table beside {@link TestProbeCommand}'s dispatch, and the only
 * place a verb's kind is declared. Every verb the probe dispatches has a row; a server of either mode judges
 * a verb by it.
 *
 * <p>An e2e walks a player's path, so its probes may only READ, or BUILD what the player's start would
 * already hold — a fixture is a fast-forward of blocks he would have placed one by one, and nothing a
 * fixture lays then acts on its own — or call an instrument of the test's own, named in
 * {@link E2EInstrument}. Everything else a probe can do ARRANGES mechanic state the player never reached,
 * TRIGGERS a production entry in his place, or DECIDES an outcome production would have decided. In e2e
 * mode {@link E2EModeGate} refuses those, at the call. A mechanics test may arrange, trigger and decide;
 * what it may not do is send a verb nobody has classified.</p>
 *
 * <p><b>Default deny.</b> A verb that is not in {@link #of}'s table is {@link #UNCLASSIFIED} and refused in
 * BOTH modes: a new probe verb is unusable until somebody has said what it is, here. A row is a CLAIM about
 * the handler it names and every resolver that handler calls, checked when it is added; the whole table
 * was traced handler by handler on 2026-10-13. What the kinds rest on, where a reading was needed:</p>
 * <ul>
 *   <li>A READ writes nothing a player could not. What every READ row still does, as vanilla's own reads
 *       do: a dimension asked about by id is loaded if it is not, and a block read loads its chunk. A
 *       universe read that reaches a body of an AUTHORED star records that body's durable cell name on the
 *       first ask ({@code UniverseRegistry#durableName}) — deterministic and idempotent, the very record the
 *       first production reader would make — and stays a read. A read whose only write is the probe's own
 *       state (its recorders, its private weight tables, its chat tap) is a read.</li>
 *   <li>A read that writes is not a read, whatever its name: {@code drive info} resolves the drive the way
 *       production does, which ADOPTS an unlinked machine in the ship's claim and mints the computer's
 *       durable id, so it is an ARRANGE and {@code drive observe} is its read; {@code nav gate} mints that
 *       id too; {@code shield group list} creates the domain's config; {@code player held-air} writes the
 *       air tag of an untagged stack. A verb that holds a dimension loaded ({@code dim load},
 *       {@code dim time}, {@code weather get}) takes a standing ticket, as {@code chunk forceload} does —
 *       ARRANGE: a ticket keeps ground loaded that nobody stands near, and a warm-up generates terrain
 *       nobody walked to.</li>
 *   <li>A fixture that lays the creative power plug is no BUILD — the plug pushes energy into every
 *       neighbour each tick, so what it lays acts on its own: {@code fixture rocket} is a BUILD only for
 *       the one variant {@link E2EModeGate} names ({@code E2EModeGate#CREATIVE_PLUG_FIXTURE}), and the
 *       structure fixtures whose pattern maps a power input to the plug are ARRANGE.</li>
 *   <li>TRIGGER calls the production entry a player, a tick or a packet would reach — a block's
 *       activation, a machine's own step, a packet's server handler, a service whose job is to decide (the
 *       damage service an impact reaches) — and leaves the outcome to production. A plain setter a GUI also
 *       calls is an ARRANGE. DECIDE writes the outcome itself: a mission completed now, a rocket put in
 *       flight or in orbit, a pad freed, a zone sealed, a wear stage set.</li>
 *   <li>The test server's hold on every ship's loading ({@code vs permaload}) is an ARRANGE: a server
 *       serving an e2e carries no hold at all — the mixins behind it are not applied in e2e mode — so
 *       production alone keeps a ship loaded, and there is nothing to release.</li>
 * </ul>
 *
 * <p>A verb is matched on its LEADING WORDS, the longest row first, never on a suffix and never on an
 * argument: {@code planet orbit-line} is a read and {@code planet state-orbit-line} is not, and
 * {@code drive charge status} is a charge whatever its last word says. Where a later word or an argument
 * changes what a verb does ({@code rocket launch}'s mode, {@code energy inject}'s simulate flag,
 * {@code shield priority} with or without a value), the row carries the kind of its most writing form, which is
 * what an e2e is judged by, and {@link #formOf} reads the call's own words for the form it takes, so the
 * {@link CommandRole} a test sends it in is checked against that form alone.
 * {@code player exec-as-player} and {@code exec-as-named} run a command through the command manager, which
 * posts that command's own event, so the inner command is judged on its own row.</p>
 */
enum ProbeVerbKind {

    /** Observes; writes nothing a player could not. */
    READ(true),
    /** Lays blocks the player's start would hold: a fast-forward of his own building. */
    BUILD(true),
    /** A static call: admitted only on a named instrument, judged at the call. */
    STATIC_CALL(true),
    /**
     * Hands the player an item of his start that no admitted {@code give} can carry — a named exception
     * of {@link E2EModeGate} only ({@link E2EModeGate#FILLED_SPACE_CHEST}); admitted as a build is, before
     * the walk and to the one player.
     */
    START_ITEM(true),
    /**
     * The run's own record that the player's walk has begun (his first input): it writes only the e2e
     * gate's state, after which the gate admits no BUILD.
     */
    WALK(true),
    /** Writes mechanic state the player never reached, and no outcome of a decision. */
    ARRANGE(false),
    /** Calls the production entry a player, a tick or a packet would reach; production decides what follows. */
    TRIGGER(false),
    /** Writes the outcome of a decision production would otherwise make itself. */
    DECIDE(false),
    /** Nobody has said what it is. */
    UNCLASSIFIED(false);

    /**
     * Whether a server serving an e2e runs a verb of this kind at all. Effectively final — a constant:
     * written once by the constructor at class initialisation, for the JVM's life.
     */
    private final boolean admittedInE2E;

    ProbeVerbKind(boolean admittedInE2E) {
        this.admittedInE2E = admittedInE2E;
    }

    boolean admittedInE2E() {
        return admittedInE2E;
    }

    /**
     * The kind of the verb {@code args} names (the words after {@code stellurgytest}): its row's, which for a
     * SPLIT row is the kind of its most writing form.
     */
    static ProbeVerbKind of(String[] args) {
        String verb = matched(args);
        return verb == null ? UNCLASSIFIED : row(verb);
    }

    /**
     * The kind of THIS call: its row's, except that for a SPLIT row the words and arguments after the verb
     * choose the form, read as the verb's handler reads them. One concrete call has one kind, so the role
     * it is sent in is checked against the form it takes.
     */
    static ProbeVerbKind formOf(String[] args) {
        String verb = matched(args);
        if (verb == null) {
            return UNCLASSIFIED;
        }
        ProbeVerbKind form = splitForm(verb, args);
        return form == null ? row(verb) : form;
    }

    /** The leading words of {@code args} that have a row, the longest first; {@code null} when none has. */
    private static String matched(String[] args) {
        for (int words = Math.min(3, args.length); words >= 1; words--) {
            StringBuilder verb = new StringBuilder();
            for (int i = 0; i < words; i++) {
                if (i > 0) {
                    verb.append(' ');
                }
                verb.append(args[i].toLowerCase(Locale.ROOT));
            }
            if (row(verb.toString()) != null) {
                return verb.toString();
            }
        }
        return null;
    }

    /**
     * The kind of the form a call of a SPLIT row takes — a verb a later word or an argument turns into one of
     * two forms — or {@code null} when the call takes the row's own, more writing form (and for every verb
     * that is no SPLIT row). {@code a} is the whole call, the verb's words included, so each index here is one
     * more than the handler's own, which reads the call without its first word.
     */
    private static ProbeVerbKind splitForm(String verb, String[] a) {
        switch (verb) {
            // energy inject <dim> <x> <y> <z> <amount> [simulate]: a simulated inject only asks what would fit.
            case "energy inject":
                return a.length >= 8 && Boolean.parseBoolean(a[7]) ? READ : null;
            // heat dump <dim> <x> <y> <z> [load <item>]: without `load` it reports the dump's state.
            case "heat dump":
                return a.length >= 8 && "load".equalsIgnoreCase(a[6]) ? null : READ;
            // shield priority / vent priority <dim> <x> <y> <z> [value]: without a value it reads the priority.
            case "shield priority":
            case "vent priority":
                return a.length >= 7 ? null : READ;
            // space auto-takeoff <dim> [id <ship>] [status]: `status` reads; any other call toggles.
            case "space auto-takeoff": {
                int modeAt = a.length >= 5 && "id".equalsIgnoreCase(a[3]) ? 5 : 3;
                return a.length > modeAt && "status".equalsIgnoreCase(a[modeAt]) ? READ : null;
            }
            // station dock <id> [commit]: the commit defaults to true; `false` only names the pad it would take.
            case "station dock":
                return a.length >= 4 && !Boolean.parseBoolean(a[3]) ? READ : null;
            // vs shiplocal <mode> [entity]: `status` reports the mode.
            case "vs shiplocal":
                return a.length >= 3 && "status".equalsIgnoreCase(a[2]) ? READ : null;
            // rocket launch <id> [fill] [mode]: `force` puts the rocket in flight; every other mode, the
            // default `prepare` included, calls production's own launch.
            case "rocket launch":
                return a.length >= 5 && "force".equalsIgnoreCase(a[4]) ? null : TRIGGER;
            // rocket set-state <id> key=value…: `orbit` and `flight` write outcomes; the other keys are plain writes.
            case "rocket set-state":
                for (int i = 3; i < a.length; i++) {
                    if (a[i].startsWith("orbit=") || a[i].startsWith("flight=")) {
                        return null;
                    }
                }
                return ARRANGE;
            // entity spawn <dim> <x> <y> <z> <name> [state] [ticks]: with a positive tick count the entity's own
            // update runs; without one it is a spawn.
            case "entity spawn":
                return a.length >= 9 && positive(a[8]) ? null : ARRANGE;
            default:
                return null;
        }
    }

    /** Whether {@code word} is an integer above zero, as the handler's own parse reads a tick count. */
    private static boolean positive(String word) {
        try {
            return Integer.parseInt(word) > 0;
        } catch (NumberFormatException notANumber) {
            return false;
        }
    }

    /** The table. {@code null} for a verb with no row of exactly these words. */
    private static ProbeVerbKind row(String verb) {
        switch (verb) {
            case "invoke-static":
                return STATIC_CALL;
            case "e2e walk-begins":
                return WALK;
            case E2EModeGate.FILLED_SPACE_CHEST:
                return START_ITEM;
            case "assembler max-y":
            case "assembler nbt-roundtrip":
            case "assembler pad-bounds":
            case "atmosphere detector-output":
            case "atmosphere for-player":
            case "atmosphere get":
            case "beacon list":
            case "block at":
            case "block biome-at":
            case "chunk list":
            case "chunk loaded":
            case "clock":
            case "commands list":
            case "config get":
            case "damage impact-memory":
            case "damage records":
            case "damage stage":
            case "dim celestial-angle":
            case "dim here":
            case "dim info":
            case "dim list":
            case "dim planetdefs-path":
            case "docking-port info":
            case "docking-port nbt-roundtrip":
            case "docking-port packet-roundtrip":
            case "drive observe":
            case "enchant check":
            case "enchant validates-as-airsuit":
            case "energy stored":
            case "entity capsule-nbt-roundtrip":
            case "entity capsule-state":
            case "entity info":
            case "entity near":
            case "entity registry":
            case "entity scan-items":
            case "event handlers":
            case "event tick-counter":
            case "event transitions":
            case "fluid stored":
            case "gamerule get":
            case "hatch read":
            case "heat emitter":
            case "heat item":
            case "heat material":
            case "heat read":
            case "heat signature":
            case "infra astrobody-chip-data":
            case "infra comparator-override":
            case "infra info":
            case "infra item-armor-slot":
            case "infra monitor-info":
            case "infra service-state":
            case "infra unloader-debug":
            case "item check":
            case "jettison info":
            case "machine controller-state":
            case "machine info":
            case "machine recipe-info":
            case "machine recipe-info-block":
            case "machine recipes-summary":
            case "machine tick-until":
            case "mission infra-state":
            case "mission rocket-cargo":
            case "mission state":
            case "nav status":
            case "oredict empty":
            case "oredict get":
            case "oxygen player":
            case "pipe wireless-info":
            case "pipe wireless-role-on-network":
            case "planet authored":
            case "planet info":
            case "planet knowledge":
            case "planet named":
            case "planet orbit-line":
            case "player advancement":
            case "player bindings":
            case "player chat-clear":
            case "player get-fall-distance":
            case "player health":
            case "player held-air-component-route":
            case "player inv-bypass status":
            case "player inventory-contains":
            case "player last-chat":
            case "player open-container":
            case "player position-of":
            case "player riding-entity":
            case "player riding-of":
            case "player sleep-state":
            case "player sleeping":
            case "player suit-diag":
            case "player world-check":
            case "registry lookup":
            case "registry sounds":
            case "registry summary":
            case "crafts census":
            case "rocket event-counts":
            case "rocket event-counts-full":
            case "rocket event-payloads":
            case "rocket find-by-uuid":
            case "rocket fuel":
            case "rocket info":
            case "rocket planet-data-read-empty":
            case "rocket prelaunch-cancel-counts":
            case "rocket stats-from-nbt":
            case "rocket storage-fluid":
            case "rocket storage-inventory":
            case "rocket wire-symmetry":
            case "satellite battery":
            case "satellite biome-list-size":
            case "satellite can-tick":
            case "satellite create-from-nbt-unknown":
            case "satellite data":
            case "satellite info":
            case "satellite list":
            case "satellite markers":
            case "satellite poslist-size":
            case "satellite terminal-info":
            case "satellite ticking-list":
            case "satellite types":
            case "satellite weather-info":
            case "satellite weather-list-size":
            case "satellite-terminal info":
            case "seal-detector check":
            case "selector info":
            case "sensor read":
            case "sensor sees":
            case "separator info":
            case "server tick-count":
            case "server wait":
            case "shield console-info":
            case "shield emitters":
            case "shield read":
            case "shield zone":
            case "shot crossing":
            case "shot list":
            case "shot read":
            case "sound play":
            case "space aboard-tag":
            case "space anchor":
            case "space bodies":
            case "space cargo-stash":
            case "space cell-info":
            case "space cell-slot":
            case "space clock":
            case "space derived":
            case "space descent-status":
            case "space entry-gate":
            case "space entry-status":
            case "space extinction":
            case "space find-afc":
            case "space find-giant":
            case "space find-moon":
            case "space find-procedural":
            case "space frame":
            case "space gen-config":
            case "space get-block":
            case "space launch-cell":
            case "space ledger-get":
            case "space loose-body-count":
            case "space loose-body-find":
            case "space nebula-find":
            case "space nebulae":
            case "space subsystem-status":
            case "space transit-export":
            case "space zone-membership":
            case "space zone-planets":
            case "space zone-sphere":
            case "star get":
            case "station at":
            case "station info":
            case "station list":
            case "station pads":
            case "subnet info":
            case "telescope entries":
            case "telescope info":
            case "telescope sees":
            case "telescope starter":
            case "terraforming info":
            case "terraforming terminal-info":
            case "tile multiblock-state":
            case "tile nbt-id":
            case "tile warp-state":
            case "tile warp-trigger-debug":
            case "turret read":
            case "vent info":
            case "vs actuators":
            case "vs arrival-trace":
            case "vs deck-capture":
            case "vs deck-support":
            case "vs ff-cruise-read-by-id":
            case "vs find-seat":
            case "vs flight-model":
            case "vs flight-model-by-id":
            case "vs managed-by":
            case "vs mount-tracking":
            case "vs phys-diag":
            case "vs player-ship-data":
            case "vs seat-status":
            case "vs seat-yard":
            case "vs ship-contains":
            case "vs ship-registered":
            case "vs ship-frame-check":
            case "vs ship-info":
            case "vs ship-name":
            case "vs ship-uuid":
            case "vs subspace-census":
            case "vs to-world":
            case "vs would-take-over":
            case "vs yard-census":
            case "weaponconsole read":
            case "wear get":
            case "wear rocket-status":
            case "weight fluid":
            case "weight item":
            case "weight set":
            case "weight set-regex":
            case "weight te":
            case "worldgen ore-stats":
            case "worldgen sample":
            case "worldgen survey":
                return READ;
            case "drive extend":
            case "fill":
            case "fixture machine cutting":
            case "fixture machine plate-press":
            case "fixture multiblock beacon":
            case "fixture multiblock blackhole-gen":
            case "fixture multiblock gravity-controller":
            case "fixture multiblock microwave-receiver":
            case "fixture multiblock observatory":
            case "fixture multiblock planet-analyser":
            case "fixture multiblock railgun":
            case "fixture multiblock solar-array":
            case "fixture multiblock space-elevator":
            case "fixture rocket":
            case "fixture uv-rocket":
            case "nav place":
            case "place":
            case "space set-block":
            case "telescope place":
                return BUILD;
            case "arrange charged-item":
            case "arrange coded-item":
            case "arrange core-burnup":
            case "arrange held-target":
            case "arrange wheel-momentum":
            case "atmosphere detector-set-mode":
            case "atmosphere set-density":
            case "atmosphere torch-block-add":
            case "atmosphere torch-block-clear":
            case "chunk cycle":
            case "chunk forceload":
            case "chunk hold":
            case "chunk release":
            case "chunk release-all":
            case "chunk warmup":
            case "config set":
            case "damage clear-impacts":
            case "damage guard":
            case "damage unguard-all":
            case "dim load":
            case "dim set-spawn":
            case "dim time":
            case "docking-port set-ids":
            case "drive build":
            case "drive charge":
            case "drive info":
            case "drive push":
            case "energy inject":
            case "entity capsule-set-dst":
            case "entity capsule-set-motion":
            case "entity capsule-set-src":
            case "entity rocket-nbt-roundtrip":
            case "entity set-fall-distance":
            case "entity set-motion":
            case "entity set-no-gravity":
            case "entity set-pos":
            case "event dim-side-effects":
            case "fixture machine arc-furnace":
            case "fixture machine centrifuge":
            case "fixture machine chemical-reactor":
            case "fixture machine crystallizer":
            case "fixture machine electrolyser":
            case "fixture machine lathe":
            case "fixture machine precision-assembler":
            case "fixture machine precision-laser-etcher":
            case "fixture machine rolling-machine":
            case "fixture multiblock orbital-laser-drill":
            case "fixture multiblock terraformer":
            case "fluid inject":
            case "gascharge fill-suit":
            case "guidance launch-seq":
            case "hatch fill":
            case "heat dump":
            case "heat set":
            case "heat silent":
            case "infra astrobody-load-chip":
            case "infra databus-set-data":
            case "infra link":
            case "infra unlink":
            case "jettison load":
            case "machine clear-batteries":
            case "machine set-enabled":
            case "mission advance":
            case "mission link-infra":
            case "mission start-gas":
            case "mission start-ore":
            case "mission strip-guidance":
            case "nav cleartarget":
            case "nav crystal":
            case "nav gate":
            case "nav link":
            case "nav target":
            case "nav target-body":
            case "pipe wireless-pair":
            case "pipe wireless-set-enabled":
            case "pipe wireless-set-mode":
            case "planet add-gas":
            case "planet set-temp":
            case "planet state-orbit-line":
            case "player advancement reset":
            case "player bind-aboard":
            case "player bind-grace":
            case "player clear-armor":
            case "player deop-self":
            case "player dismount":
            case "player equip-airsuit":
            case "player equip-biomechanger":
            case "player equip-orescanner":
            case "player equip-space-chest":
            case "player equip-stationchip":
            case "player far-tp":
            case "player give-held":
            case "player give-suit-chest":
            case "player held-air":
            case "player inv-bypass add":
            case "player inv-bypass remove":
            case "player mount-entity":
            case "player op-named":
            case "player op-self":
            case "player set-fall-distance":
            case "player set-health":
            case "player set-move-forward":
            case "rocket arm-prelaunch-cancel":
            case "rocket remove":
            case "rocket disarm-prelaunch-cancel":
            case "rocket drain-fuel":
            case "rocket fill-fuel":
            case "rocket force-dest-dim":
            case "rocket set-destination":
            case "rocket set-flight-assist":
            case "rocket set-flight-mode":
            case "rocket storage-fluid-fill":
            case "rocket storage-item-fill":
            case "rocket strip-guidance":
            case "satellite biome-add-pos":
            case "satellite biome-null":
            case "satellite biome-set":
            case "satellite create":
            case "satellite create-spy-telescope":
            case "satellite force-charge":
            case "satellite imprint-terminal":
            case "satellite set-dead":
            case "satellite weather-add-pos":
            case "satellite weather-mode":
            case "satellite-builder build":
            case "satellite-terminal load-chip":
            case "seal-detector add-block-ban":
            case "seal-detector remove-block-ban":
            case "selector simulate-click":
            case "sensor charge":
            case "sensor code":
            case "sensor mode":
            case "server stall":
            case "shield charge":
            case "shield console-bias":
            case "shield group":
            case "shield priority":
            case "shield rotate-code":
            case "shot clear":
            case "space add-poi":
            case "space auto-takeoff":
            case "space enter":
            case "space entry-clear":
            case "space entry-setup":
            case "space forget-name":
            case "space gen-empty":
            case "space gen-install":
            case "space gen-reset":
            case "space ledger-forget":
            case "space load":
            case "space loose-body":
            case "space manager":
            case "space occupy":
            case "space pool-register":
            case "space release":
            case "space reload":
            case "space roundtrip":
            case "space save-fault-once":
            case "space set-clock":
            case "space set-world-clock":
            case "space transit-claim":
            case "space transit-name":
            case "space transit-restore":
            case "space transit-setup-empty":
            case "space transit-setup-piloted":
            case "space transit-status":
            case "space unload":
            case "space vs-assemble":
            case "space vs-cap":
            case "star set-blackhole":
            case "station add-pad":
            case "station controller-set-redstone":
            case "station create":
            case "station fuel":
            case "station remove-pad":
            case "station set-anchor":
            case "station set-autoland":
            case "station set-dest":
            case "station set-parent":
            case "telescope crystal":
            case "telescope handover":
            case "telescope system":
            case "terraforming set-density":
            case "terraforming terminal-load-chip":
            case "tp":
            case "turret bearing":
            case "turret charge":
            case "turret cleartarget":
            case "turret code":
            case "turret manual":
            case "turret target":
            case "turret target-player":
            case "vent setair":
            case "vs afc-clear":
            case "vs destroy-ship":
            case "vs drop-item":
            case "vs drop-living":
            case "vs drop-stand":
            case "vs fa-by-id":
            case "vs ff-cruise-at":
            case "vs ff-cruise-by-id":
            case "vs ff-input-by-id":
            case "vs force-clear-by-id":
            case "vs force-rot-by-id":
            case "vs force-vel-at":
            case "vs force-vel-by-id":
            case "vs load-ship":
            case "vs permaload":
            case "vs point-at":
            case "vs point-by-id":
            case "vs push-ship-by-id":
            case "vs seat-input-by-id":
            case "vs seat-link":
            case "vs seat-mount":
            case "vs seat-mount-at":
            case "vs seat-occupy":
            case "vs shiplocal":
            case "vs spin-ship-by-id":
            case "vs stow":
            case "vs strand-blockless-record":
            case "vs teleport-ship-by-id":
            case "vs unpark-by-id":
            case "weaponconsole allegiance":
            case "weaponconsole cleartarget":
            case "weaponconsole code":
            case "weaponconsole holdfire":
            case "weaponconsole ship":
            case "weaponconsole target":
            case "weaponconsole target-player":
            case "wear station-load":
            case "weather get":
            case "weather set":
            case "weather set-marker":
            case "entity stamp-fluid":
            case "weight content-scale":
            case "weight fuel-scale":
            case "weight reset":
            case "worldgen crater-gate":
            case "worldgen create-asteroid-dim":
            case "worldgen create-terrain-dim":
                return ARRANGE;
            case "block activate":
            case "block harvest":
            case "block place":
            case "damage impact":
            case "damage weld":
            case "drive arm":
            case "drive press":
            case "entity interact":
            case "entity spawn":
            case "entity tick":
            case "heat cycle":
            case "infra astrobody-set-research":
            case "infra laserdrill-mine":
            case "infra railgun-fire":
            case "infra railgun-receive-cargo":
            case "infra service-perform-function":
            case "infra service-relink":
            case "infra service-scan-assemblers":
            case "machine deconstruct":
            case "machine try-complete":
            case "nav copy":
            case "nav erase":
            case "nav modules":
            case "nav refresh":
            case "nav sync":
            case "player advancement-trigger-direct":
            case "player drive-ridden-entity":
            case "player ensure-fake":
            case "player exec-as-named":
            case "player exec-as-player":
            case "player load-from-file":
            case "player open-chest":
            case "player release":
            case "player tick-living":
            case "player try-atm-analyze":
            case "player try-biomechanger-rclick":
            case "player try-fall":
            case "player try-hovercraft":
            case "player try-ignite":
            case "player try-orescanner-rclick":
            case "player try-seal-detect":
            case "player try-sleep":
            case "rocket assemble":
            case "rocket dismantle":
            case "rocket ff-prepare-launch":
            case "rocket free-flight-input":
            case "rocket free-flight-tick":
            case "rocket override-landing":
            case "rocket send-planet-data":
            case "rocket tick":
            case "rocket toggle-rcs":
            case "satellite announce-unknown":
            case "satellite deploy-unresolved":
            case "satellite force-tick-dim":
            case "satellite tick":
            case "satellite weather-apply":
            case "satellite weather-discard-test":
            case "satellite weather-tick-unloaded":
            case "satellite-builder assemble-unregistered-direct":
            case "satellite-builder press-build":
            case "satellite-builder press-build-unregistered":
            case "satellite-terminal press-erase":
            case "scrubber consume":
            case "server save-dimensions":
            case "shield explode":
            case "shield strike":
            case "shield tick":
            case "shot fire":
            case "space descent-begin":
            case "space jump":
            case "space jump-key":
            case "space pool-idempotence":
            case "space realize":
            case "space save-now":
            case "space seam-carry":
            case "space transit-begin":
            case "space transit-refresh":
            case "space transit-tick":
            case "station controller-set-target":
            case "station dock":
            case "station warp-collision":
            case "subnet solve":
            case "telescope abort":
            case "telescope deposit":
            case "telescope passive":
            case "telescope scan":
            case "telescope whole-system":
            case "tile force-tick":
            case "tile force-tick-clock":
            case "tile init-modules":
            case "tile warp-trigger":
            case "turret fire":
            case "vent priority":
            case "vs ship-repack":
            case "wear damage-parts":
            case "weather tick-provider":
                return TRIGGER;
            case "atmosphere detector-force-sample":
            case "atmosphere extinguish-at":
            case "infra inject-broken-part":
            case "mission complete-now":
            case "rocket explode":
            case "rocket force-orbit-reached":
            case "rocket launch":
            case "rocket set-state":
            case "rocket start-free-flight":
            case "space ledger-settle":
            case "space ledger-transit":
            case "station undock":
            case "turret drive":
            case "vent reseal":
            case "wear set":
                return DECIDE;
            default:
                return null;
        }
    }
}
