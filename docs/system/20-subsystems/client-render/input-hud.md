---
id: client-render/input-hud
parent: client-render
files: [client/KeyBindings.java, client/StellurgyKeyConflictContext.java, client/TooltipInjector.java]
sloc: ~1050
contracts: [C2, C5, C7]
confidence: high
---

## Purpose

Translate the keyboard into rocket / hovercraft control — classic edge-driven turning plus the
Free Flight per-tick input pipeline, camera-nose lock, and engine-start ritual — and build the
Free Flight HUD text. Also inject the shift/alt tooltip blocks on Stellurgy (and select libVulpes) items.

## Key types

| class | role |
|-------|------|
| `KeyBindings` | registers keybinds; `onKeyInput` (edge) + `onClientTick` (per-tick FF sampling); HUD lines; camera pin |
| `StellurgyKeyConflictContext` | `IKeyConflictContext` enum (`PILOTING`/`NOT_PILOTING`) scoping steering keys to the cockpit |
| `TooltipInjector` | `ItemTooltipEvent` handler mapping registry-id → tooltip base key, rendering base/shift/alt blocks |

## Mechanics

**MECH-CLR-08 — Keybind registration & cockpit scoping.** `init()` registers the mod's keybinds (the
list is `init()` itself; an inventory here had gone stale by four) then
`scopeSteeringKeysToCockpit()` assigns `StellurgyKeyConflictContext`. `KeyBindings.java:180-236`. C7 lang
`key.*` / `key.controls.stellurgy`.

**MECH-CLR-09 — Mutually-exclusive key-conflict contexts.** Steering keys get `PILOTING`; the
vanilla/Stellurgy keys they share a default with (`keyBindInventory` E, `keyBindDrop` Q, `keyBindLeft` A,
`keyBindRight` D, `keyBindSwapHands` F, `keyBindChat` T — the helm's ship designation,
`weapons` MECH-GUN-40 — plus `toggleJetpack` X, `toggleRCS` R) get the complement
`NOT_PILOTING`. `PILOTING.isActive()` ⇔ the player rides an `EntityRocketBase`/`EntityHoverCraft`, or
sits in the pilot seat of a named tier-2 ship (`TilePilotSeat#forShipPilot`). Exactly one binding per
shared key is active, so no runtime double-fire and no Controls-screen conflict.
`StellurgyKeyConflictContext.java:40-80`, `KeyBindings#scopeSteeringKeysToCockpit` [V]

**MECH-CLR-34 — From the helm the cursor acts on NOTHING (ruling 2026-10-05).** Maintainer, verbatim:
*"давай кстати сразу уберём любое взаимодействие курсора игрока с чем-либо в контекте PILOTING …
надо перестать подсвечивать блок под курсором как тот, что ты будешь ломать, и взаимодействие по
мыши. Но и все остальное тоже убрать"* (no cursor interaction while PILOTING: no outline of the block
under it, no mouse interaction, nothing else). `keyBindAttack`, `keyBindUseItem` and `keyBindPickBlock`
carry `NOT_PILOTING`, so while piloting they get no press (`KeyBinding#onTick` credits only
`lookupActive`) and never read down (`isKeyDown` checks the context) — which is all vanilla calls
`clickMouse`, `rightClickMouse`, `middleClickMouse` and the continued dig by. `DrawBlockHighlightEvent` is
cancelled while `PILOTING` is active. `KeyBindings#onHelmBlockHighlight` [V]. Silent about the
hotbar's mouse wheel (selection, not an act on the world) and about overlays other mods draw from
`objectMouseOver`.

**MECH-CLR-10 — Classic (edge-driven) control input.** `onKeyInput` (`KeyInputEvent`, fires only
on transitions) handles: Space → `prepareLaunch` (classic mode only); M → toggle flight mode +
`SET_FLIGHT_MODE` packet; N → toggle flight assist + `SET_FLIGHT_ASSIST`; legacy turn keys →
`rocket.onTurnLeft/Right/Up/Down` (only when NOT FF-in-flight); hovercraft up/down; jetpack toggle
→ `PacketChangeKeyState`; open-UI → `OPENGUI`; RCS → `TOGGLE_RCS`; space-bar edge →
`PacketChangeKeyState`. Suppressed while a GUI is open. `KeyBindings.java:470-579`. C2 (packets out).

**MECH-CLR-11 — Free Flight per-tick input sampling.** `onClientTick` (END phase) samples held
keys every tick while `rocket.isFreeFlight() && isInFlight()` — this is why a key held *before*
`isInFlight` replicates still thrusts. Builds a `FreeFlightInput(fwd,vert,strafe,yaw,pitch,roll,brake,cut)`
and sends `FREE_FLIGHT_INPUT` **only when the input changes** vs `lastSentInput`; also applies it
locally. Guarded against open GUI. `KeyBindings.java:318-457`. Strafe polarity is intentionally
flipped (E = −right) so motion matches the nose-out view; `tunable` sensitivities.

**MECH-CLR-12 — Mouse-as-rate steering + hard camera-nose lock.** Mouse movement since the last
pin drives an absolute "flight cursor" in [-1,1]² (X→roll, Y→pitch) with a centre deadzone; yaw is
keyboard. After sending input, the player rotation (and prevRotation, for per-frame interp) is
mirrored from the craft so view and nose can never diverge. On FF-inactive the pin/cursor reset.
`KeyBindings.java:405-467`, fields `:61-112`.

**MECH-CLR-13 — PosLook re-pin (mixin seam).** While riding, the server echoes each position report
with a ~1-RTT-stale `SPacketPlayerPosLook` that would snap the locked camera. `MixinNetHandlerFFCameraRepin`
(owned by mixins-asm-coremod) calls `captureMouseBeforeTeleport()` at HEAD and `repinCameraAfterTeleport()`
at RETURN: capture pending mouse delta, re-pin camera to the craft, re-apply the delta, keep the
baseline at the pin. `KeyBindings.java:122-171`. Static `cameraPinValid` gates HUD frame-lock telemetry.

**MECH-CLR-14 — Engine-start hold ritual.** In FF pre-flight, holding Jump for
`ENGINE_START_HOLD_TICKS` (contractual constant = 60) sends one `ENGINE_START` packet; releasing
early cancels. Progress (`engineStartHoldTicks`) and a 60-tick engine-state flash
(`engineFlash*`) are published statics for the HUD and e2e readback. `KeyBindings.java:98-112,360-385`.

**MECH-CLR-15 — Free Flight HUD text.** `freeFlightHudLines(rocket, inFlight)` builds localised
lines from the player's *actual* bound keys (`GameSettings` + `I18n`): pre-launch = engine/launch
hints; in-flight = mode + FA state + move/strafe/vert/yaw/pitch/cut/brake/assist legend + per-axis
body-frame setpoint-vs-actual vector (`FreeFlightPhysics.worldToBody`) + speed. Consumed by
`RocketEventHandler` (event-handlers). `KeyBindings.java:257-310`. C7 `msg.ff.*`.

**MECH-CLR-16 — Shift/alt tooltip injection.** `TooltipInjector.onTooltip` (`ItemTooltipEvent`)
resolves an item to a base lang key via `KEY_RESOLVER_BY_ID` (meta-aware) → `KEY_BY_ID` →
`KEY_BY_SUFFIX` (unlocalized-name tail), then `renderShiftAlt` inserts a base block (`key`, `.1..8`),
a shift block (`.shift.1..8`, else a `hold_shift` prompt), and an alt block (`.alt.1..8`, else
`hold_alt`), each `I18n`-gated. Optional `ARGS_BY_BASEKEY` supply format args (e.g. oxygen-vent
size from config; fuel-tank fluid lists from `FuelRegistry`). `TooltipInjector.java:45-330`. C7.

## State & persistence

No NBT. Client-only statics: FF input/cursor/pin/engine-hold flags in `KeyBindings`
(`:52-117`); tooltip maps built once in the `TooltipInjector` static block (`:45-240`).
Config read: `oxygenVentSize` (C4). Constants: `ENGINE_START_HOLD_TICKS=60` (contractual);
`FF_CURSOR_SENS`/`FF_CURSOR_DEADZONE`/flash length = `tunable`.

## Integration seams

- **Packets out (C2)**: `PacketEntity(rocket, PacketType.{ENGINE_START, FREE_FLIGHT_INPUT,
  SET_FLIGHT_MODE, SET_FLIGHT_ASSIST, OPENGUI, TOGGLE_RCS}.ordinal())`, `PacketChangeKeyState`.
- **Mixin (C6, owned elsewhere)**: `MixinNetHandlerFFCameraRepin` → the two static PosLook hooks.
- **Events (C5)**: `ClientTickEvent`, `KeyInputEvent`; `TooltipInjector` = `@Mod.EventBusSubscriber(CLIENT)`
  on `ItemTooltipEvent` (+ no-op `ModelRegistryEvent`).

## Invariants

- **INV-CLR-07 [V][BEH]** Steering input is suppressed whenever `mc.currentScreen != null` (both the
  edge and per-tick paths). `KeyBindings.java:327,477`.
- **INV-CLR-08 [A][BEH]** `PILOTING`/`NOT_PILOTING` conflict only with themselves and never with vanilla
  `KeyConflictContext.IN_GAME`/`GUI`. `StellurgyKeyConflictContextTest.java:27-46`. Pinned by `StellurgyKeyConflictContextTest#eachContextConflictsWithItself`, `StellurgyKeyConflictContextTest#pilotingAndNotPilotingNeverConflict`, `StellurgyKeyConflictContextTest#doesNotClaimConflictWithForgeBuiltInContexts`.
- **INV-CLR-09 [V]** A `FREE_FLIGHT_INPUT` packet is sent only when the sampled input differs from
  the last sent one (bandwidth guard). `KeyBindings.java:451-457`.
- **INV-CLR-10 [V][BEH]** Exactly one binding is active per shared key: each steering key and its
  overridden partner carry complementary contexts. `KeyBindings.java:206-235`.
- **INV-CLR-11 [V][BEH]** `ENGINE_START_HOLD_TICKS` (60) is the sole gate for the one-shot engine-start
  send (`engineStartSent` guards the single dispatch). `KeyBindings.java:100,367-372`.
- **INV-CLR-12 [V][BEH]** HUD lines read the player's live bound keys, so a rebind reflows the legend.
  `KeyBindings.java:242-243,280-287`.

## Failure modes & edge cases

- On the first FF-active tick after takeoff/remount the stale look-offset is discarded (else a
  phantom swipe would kick the nose). `KeyBindings.java:411-420`.
- `captureMouseBeforeTeleport`/`repin` early-return off the MC thread (netty) and when not in a
  pinned FF flight — the netty→MC race is handled. `KeyBindings.java:123-146`.
- Classic Space-launch is disabled in FF mode; FF uses the hold ritual instead. `KeyBindings.java:501-507`.

## Test coverage

- INV-CLR-08 ← `test/unit/StellurgyKeyConflictContextTest.java:23-46`.
- FF input / engine-start / HUD readback ← `test/client/FreeFlightModeTest.java` (client e2e,
  reads the published `KeyBindings` statics and HUD lines).

## Open questions

- Whether `flightCursor*` deadzone/sens want to be config-exposed (currently private constants).
