package dev.stannismod.stellurgy.command.test;

import java.util.Locale;

/**
 * What a {@code /stellurgytest} verb IS, for a run that serves an e2e — the table beside
 * {@link TestProbeCommand}'s dispatch, and the only place a verb's kind is declared.
 *
 * <p>An e2e walks a player's path, so its probes may only READ, or BUILD what the player's start would
 * already hold — a fixture is a fast-forward of blocks he would have placed one by one, and nothing a
 * fixture lays then acts on its own — or call an instrument of the test's own, named in
 * {@link E2EInstrument}. Everything else a probe can do ARRANGES mechanic state the player never
 * reached, or ACTS in his place. In e2e mode {@link E2EModeGate} refuses those, at the call.</p>
 *
 * <p><b>Default deny.</b> A verb that is not in {@link #of}'s table is {@link #UNCLASSIFIED} and refused
 * like an act: a new probe verb is unusable in an e2e until somebody has said what it is, here. The table
 * holds the verbs an e2e reads or builds with; a verb a mechanics test alone uses needs no row. A row is a
 * CLAIM — that the verb writes nothing a player could not — checked against the handler it names when it
 * is added. Traced 2026-10-09 for every READ row below: none writes mod state. Two did and moved:
 * {@code drive info} resolves the drive the way production does, which ADOPTS an unlinked machine in the
 * ship's claim and mints the computer's durable id, so it is an ARRANGE and {@code drive observe} is its
 * read; {@code vs ship-name} minted the id and now answers {@code found:false} instead. What every row
 * still does, as vanilla's own reads do: a dimension asked about by id is loaded if it is not, and a block
 * read loads its chunk.</p>
 *
 * <p>A verb is matched on its LEADING WORDS, the longest row first, never on a suffix and never on an
 * argument: {@code planet orbit-line} is a read and {@code planet state-orbit-line} is not, and
 * {@code drive charge status} is a charge whatever its last word says.</p>
 */
enum ProbeVerbKind {

    /** Observes; writes nothing a player could not. */
    READ(true),
    /** Lays blocks the player's start would hold: a fast-forward of his own building. */
    BUILD(true),
    /** A static call: admitted only on an instrument {@link E2EInstrument} names, judged at the call. */
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
    /** Writes mechanic state the player never reached. */
    ARRANGE(false),
    /** Performs an act in the player's place. */
    ACT(false),
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

    /** The kind of the verb {@code args} names (the words after {@code stellurgytest}). */
    static ProbeVerbKind of(String[] args) {
        for (int words = Math.min(3, args.length); words >= 1; words--) {
            StringBuilder verb = new StringBuilder();
            for (int i = 0; i < words; i++) {
                if (i > 0) {
                    verb.append(' ');
                }
                verb.append(args[i].toLowerCase(Locale.ROOT));
            }
            ProbeVerbKind kind = row(verb.toString());
            if (kind != null) {
                return kind;
            }
        }
        return UNCLASSIFIED;
    }

    /** The table. {@code null} for a verb with no row of exactly these words. */
    private static ProbeVerbKind row(String verb) {
        switch (verb) {
            case "clock":
            case "server tick-count":
            case "config get":
            case "nav status":
            case "drive observe":
            case "energy stored":
            case "player riding-entity":
            case "planet orbit-line":
            case "space bodies":
            case "space cell-info":
            case "space descent-status":
            case "space find-afc":
            case "space frame":
            case "space ledger-get":
            case "space subsystem-status":
            case "telescope info":
            case "telescope starter":
            case "vs find-seat":
            case "vs ship-info":
            case "vs ship-name":
            case "vs ship-uuid":
            case "vs to-world":
                return READ;
            case "place":
            case "fill":
            case "fixture rocket":
            case "fixture multiblock observatory":
                return BUILD;
            case "invoke-static":
                return STATIC_CALL;
            case "e2e walk-begins":
                return WALK;
            case E2EModeGate.FILLED_SPACE_CHEST:
                return START_ITEM;
            // The arrangements and acts an e2e has been caught reaching for, or that were once misfiled as
            // a read or a build, named so a refusal says what they are; any verb missing here is refused
            // as UNCLASSIFIED all the same.
            case "tp":
            case "config set":
            case "drive charge":
            // Resolves the drive through production's own resolver, which links an unlinked machine in the
            // ship's claim to this computer, and mints the computer's durable id: `drive observe` reads.
            case "drive info":
            case "turret charge":
            case "turret target":
            case "planet state-orbit-line":
            // A chunk ticket keeps ground loaded that nobody stands near, and a warm-up generates terrain
            // nobody walked to: neither is a block of the player's build.
            case "chunk forceload":
            case "chunk warmup":
            // The test server's hold on every ship's loading. A server serving an e2e carries no hold at
            // all — the mixins behind it are not applied in e2e mode — so production alone keeps a ship
            // loaded, and there is nothing to release.
            case "vs permaload":
                return ARRANGE;
            case "mission complete-now":
                return ACT;
            default:
                return null;
        }
    }
}
