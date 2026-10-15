package dev.stannismod.stellurgy.test.trace;

import org.spongepowered.asm.mixin.extensibility.IMixinConfigPlugin;
import org.spongepowered.asm.mixin.extensibility.IMixinInfo;

import java.util.*;

/**
 * The plugin of {@code mixins.stellurgy.test.json}, and the one place each of its mixins is declared an
 * OBSERVATION or a BEHAVIOUR.
 *
 * <p>"Our test mixins are installed" is a checkable fact without anything here: the configuration is
 * {@code required}, and the server's event log hangs on {@code MinecraftServer} by one of its mixins,
 * so a log that answers at all is the proof the configuration was applied ({@link ServerEventLog}).</p>
 *
 * <p><b>In a child that serves an e2e only the OBSERVATIONS are applied</b> — default deny: a mixin in
 * neither list is not applied there either. An e2e walks a player's path through the game as it ships, so
 * nothing may change what that game does: a mixin that keeps every world ticking with no player near, holds
 * every ship loaded, or shoves a body is a mechanics test's arrangement and is absent from an e2e's child.
 * The mode is read ONCE, when mixin makes this plugin at the child's launch, and held here; every other
 * child applies both lists.</p>
 *
 * <p>A mixin is an OBSERVATION when it records and changes nothing the game decides: an {@code @Inject}
 * that only reads and records, a {@code @Redirect} or {@code @ModifyArg} that performs the original call
 * with the original arguments and returns what it returned, an accessor or invoker that only reads. That
 * is a claim about its body, checked when it is listed. Classified 2026-10-09 by reading every redirect,
 * argument modifier, accessor and invoker body and scanning every injection for a write to the game's
 * state. The build's {@code checkTestBindings} fails on a mixin of the configuration that is in neither
 * list, in both, or in a list and not in the configuration.</p>
 */
public class StellurgyTestMixinPlugin implements IMixinConfigPlugin {

    /** The package of the configuration's mixins. */
    private static final String MIXIN_PACKAGE = "dev.stannismod.stellurgy.test.mixin.";

    /**
     * Mixins that only record, applied in every child. A constant: an unmodifiable set of strings over a
     * backing set nothing else references.
     */
    public static final Set<String> OBSERVATION =Collections.unmodifiableSet(new HashSet<>(Arrays.asList(
            "MixinChunkLoadCount", "MixinWorldEntityRemoval", "MixinWorldFlightComputerTickCensus",
            "MixinEntityPositionWriters", "MixinEntityShipTouchEvents", "MixinAboardBodiesEvents",
            "MixinSpaceSubsystemSaveFaultEvents", "MixinPlanetWeatherCycleEvents", "MixinEntityVelocityWriters",
            "MixinShipTransformEntityWrites", "MixinHullSweepInputs", "MixinShipVelocityAtPoint",
            "MixinDeckMovementBoundInputs", "MixinFlightComputerMotionSample",
            "MixinPhysicsCalculationsMotionSample", "MixinPlayerPacketRubberBand", "MixinShipTransformUpdateDiag",
            "MixinTilePilotSeatDiag", "MixinWorldServerShipManagerDiag", "MixinShipTransitManagerEvents",
            "MixinVSShipCrosserEvents", "MixinLoginRestoreEvents", "MixinShipFrameTravelEvents",
            "MixinShipAboardTagEvents", "MixinShipEntryControllerEvents", "MixinDescentControllerEvents",
            "MixinCellCrossingControllerEvents", "MixinShipCrossingServiceEvents", "MixinVSShipCrossingOpsEvents",
            "MixinShipLedgerEvents", "MixinShipMassTriggerEvents", "MixinFlightComputerUnmannedMode",
            "MixinShipInertiaWriterEvents", "MixinBeaconRegistryEvents", "MixinBeaconRegistryWrites",
            "MixinSpaceCellClaims", "MixinTimeSkipPolicyEvents", "MixinJumpTriggerEvents",
            "MixinQueryableShipDataEvents", "MixinTileAdvancedFlightComputerEvents",
            "MixinFlightComputerPhysicsStepWindow", "MixinWorldPhysicsColliderContacts",
            "MixinPhysicsObjectEvents", "MixinPhysicsObjectTeleportAdoption", "MixinSpaceEventHandlerEvents",
            "MixinCrewTransferEvents", "MixinAssemblyCrewRebindEvents", "MixinDeckHoldEvents",
            "MixinGuiHandlerEvents", "MixinTileRocketAssemblingMachineEvents", "MixinTileObservatoryEvents",
            "MixinTilePlanetSelectorEvents", "MixinTileNavigationComputerEvents", "MixinTileTurretEvents",
            "MixinBeamReplicationEvents", "MixinWeaponOrdersSeedEvents", "MixinLinkerDesignationEvents",
            "MixinServerPlayerPoseEvents", "MixinShotEvents", "MixinShotRegistryEvents", "MixinDamageStateEvents",
            "MixinTileFireControlSensorEvents", "MixinSubsystemNetworkRebuildEvents",
            "MixinSubsystemNodeReleaseEvents", "MixinTurretMuzzleEvents", "MixinShipDesignationEvents",
            "MixinContactResolverEvents", "MixinContactResolverImpactEvents", "MixinShotSubstrateCrossingEvents",
            "StructureHitAccessor", "MixinDamageInvalidationHandlerEvents", "MixinItemStationChipEvents",
            "MixinRocketInventoryHelperEvents", "MixinPlanetEventHandlerEvents", "MixinServerDisconnectEvents",
            "MixinSatelliteBiomeChangerEvents", "MixinSatelliteRegistryEvents", "MixinVSBridgeEvents",
            "MixinBasicTeleporterEvents", "MixinEntityRocketEvents", "MixinEntityRocketLaunchGate",
            "MixinRocketBirthEvents", "MixinShipBackgroundLoadEvents",
            "MixinItemAtmosphereAnalzerEvents", "MixinAtmosphereNeedsSuitEvents", "MixinPoisoningEvents",
            "MixinAtmosphereChangeEvents", "MixinItemSpaceChestEvents", "MixinItemAirUtilsEvents",
            "MixinDelayedActionBarEvents", "MixinEntityPlayerMPEvents", "MixinTileEntityFieldGeneratorEvents",
            "MixinTileEntityFieldGeneratorMembraneEvents", "MixinForceFieldExplosionHandlerEvents",
            "MixinTilePilotSeatEvents", "MixinBlockPilotSeatEvents", "MixinMinecraftServerSideTrace",
            "MixinEntityTraceMemory", "MixinClientCursorActs", "MixinClientBlockOutline",
            "MixinShipFrameTravelWrites", "MixinClientDeckPoseTrace", "MixinClientDeckPoseTickTrace",
            "MixinEntityDummyCarryEvents", "MixinBoundarySkyDiag", "MixinHyperspaceTunnelDiag",
            "MixinKeyBindingsMotionSample", "MixinKeyBindingsSeatGate", "MixinKeyBindingsFlightCursor",
            "MixinRocketEventHandlerMotionSample", "MixinRocketEventHandlerViewState",
            "MixinShipFrameCameraEvents", "MixinDeckLookEvents", "PilotInputAccessor",
            "MixinRocketEventHandlerCameraFrame", "MixinEntityRendererEyeProbe", "MixinNetHandlerPosLookCount",
            "MixinShipFrameReseatStep", "MixinPacketSystemBodiesSyncEvents", "MixinBoundarySkyEvents",
            "MixinDimensionManagerEvents", "MixinSpaceClockSyncEvents", "MixinKeyBindingsEvents",
            "MixinClientProxyEvents", "MixinPacketShipReadoutEvents", "MixinClientBeamTrackerEvents",
            "MixinClientShotTrackerEvents", "MixinTileWeaponConsoleReadoutEvents",
            "MixinPacketThreadUtilRespawnWindow", "MixinTestClientMuteEvents", "MixinMinecraftSideTrace",
            "MixinEntityRocketClientGateEvents", "MixinPacketSenderReachEvents",
            "MixinTileMultiBlockFormationEvents", "MixinTileOxygenVentScrubEvents")));

    /**
     * Mixins that change what the game does — the declared ENVIRONMENT of a mechanics child, each with the
     * reason a mechanics test needs it, applied in MECHANICS children only, never where an e2e is served.
     * The build refuses a row whose reason is blank. A constant: an unmodifiable map of strings over a
     * backing map nothing else references.
     */
    public static final Map<String, String> BEHAVIOUR = Collections.unmodifiableMap(behaviourRows());

    private static Map<String, String> behaviourRows() {
        Map<String, String> rows = new HashMap<>();
        rows.put("MixinWorldServerAlwaysTicks", "a headless server has no player near the worlds a mechanics test"
                + " arranges in, and vanilla's idle rule would stop them ticking; this keeps them ticking");
        rows.put("MixinMinecraftServerShipLoadHold", "the test server's hold on every ship's loading: a shared"
                + " headless server has no observer aboard to keep a ship loaded between scenarios");
        rows.put("MixinShipLoadingHold", "the switch the ship-load hold is read from, set by the probe's"
                + " permaload verb");
        rows.put("MixinShipFrameTravelShove", "moves a body when a test arms it: the disturbance a frame-travel"
                + " scenario needs and no player input produces on a headless server");
        rows.put("MixinArrivalSpawnMountRefusal", "refuses a seat mount's spawn while a test arms it: the refusal a"
                + " world can give a mount and a test server never gives on its own, which the rebind and the"
                + " arrival must answer");
        return rows;
    }

    /**
     * Whether this child serves an e2e — read once, when mixin makes this plugin at the child's launch, off
     * the JVM's own launch arguments (a child whose property disagrees with them does not start).
     */
    private final boolean servesAnE2E =
            com.github.stannismod.forge.testing.ChildMode.launched() == com.github.stannismod.forge.testing.ChildMode.E2E;

    @Override
    public void onLoad(String mixinPackage) {
        System.out.println("[stellurgytest] test-only mixin config prepared for " + mixinPackage
                + (servesAnE2E ? " -- e2e mode: only the " + OBSERVATION.size() + " observations are applied,"
                        + " not " + BEHAVIOUR.keySet() + " nor any mixin in neither list" : ""));
    }

    @Override
    public String getRefMapperConfig() {
        return null;
    }

    @Override
    public boolean shouldApplyMixin(String targetClassName, String mixinClassName) {
        if (!servesAnE2E) {
            return true;
        }
        String simple = mixinClassName.startsWith(MIXIN_PACKAGE)
                ? mixinClassName.substring(MIXIN_PACKAGE.length()) : mixinClassName;
        boolean applied = OBSERVATION.contains(simple);
        if (!applied) {
            System.out.println("[stellurgytest] e2e mode: NOT applying " + mixinClassName + " to " + targetClassName
                    + (BEHAVIOUR.containsKey(simple) ? " (it changes what the game does)" : " (declared in neither list)"));
        }
        return applied;
    }

    @Override
    public void acceptTargets(Set<String> myTargets, Set<String> otherTargets) {
    }

    @Override
    public List<String> getMixins() {
        return null;
    }

    @Override
    public void preApply(String targetClassName, org.objectweb.asm.tree.ClassNode targetClass,
                         String mixinClassName, IMixinInfo mixinInfo) {
    }

    @Override
    public void postApply(String targetClassName, org.objectweb.asm.tree.ClassNode targetClass,
                          String mixinClassName, IMixinInfo mixinInfo) {
    }
}
