# Flow F2 — Free-flight control loop

Scenario: a pilot rides an `EntityRocket` already in `FREE_FLIGHT` and in flight, holds movement keys,
and steers with the mouse. Trace: **input → physics → NBT persist → client render/camera**. The value is
the seams between subsystems (rocket-entity, client-render/input-hud, mixins-asm-coremod). Design axiom:
*input is intent, the server is source of truth*; the free-flight attitude is a body→world quaternion,
and Euler is derived only for legacy readers (INV-RKT-07).

Mechanics live in the owning subsystem docs; this flow names the anchor and adds the order and the
hand-off.

## Ordered steps

1. **Client samples held keys (per tick).** `KeyBindings.onClientTick` (END phase), only while
   `rocket.isFreeFlight() && isInFlight()`, builds a `FreeFlightInput(fwd,vert,strafe,yaw,pitch,roll,
   brake,cut)` from the live keybinds and the mouse "flight cursor" (X→roll, Y→pitch; yaw is keyboard)
   — MECH-CLR-11 / MECH-CLR-12. [V]
2. **Bandwidth-gated send + local apply.** `PacketEntity(rocket, FREE_FLIGHT_INPUT)` is sent **only
   when the sampled input differs from the last sent** (INV-CLR-09), and the same input is applied
   locally via `rocket.applyFreeFlightInput` so the pilot predicts (MECH-CLR-11). `applyFreeFlightInput`
   has no side guard: it seeds `currentFreeFlightInput` on both sides. [V]
3. **Wire codec (C2).** `FreeFlightInput` = 7 big-endian floats + 1 cut flag byte (`WIRE_SIZE = 29`);
   `read` clamps every channel to [-1,1] and collapses NaN/Inf to 0, so hostile floats are neutralised
   at the boundary — MECH-RKT-19 / INV-RKT-08. [T]
4. **Server authority gate.** `useNetworkData` accepts `FREE_FLIGHT_INPUT` only from a passenger of
   *this* rocket, only in FF mode, and silently drops otherwise; `applyFreeFlightInput` re-checks the
   mode (MECH-RKT-19, INV-RKT-16). Client prediction is advisory; this is the truth boundary. [V]
5. **Server physics tick (attitude).** `tickFreeFlight` returns on the client and on non-FF /
   not-in-flight (INV-RKT-13). Rates = input × `MAX_*_RATE`; `integrateBodyRates` post-multiplies a
   body-frame delta and renormalises (MECH-RKT-12). [V]
6. **Server physics tick (translation).** FA-on (default): translation keys ramp a body-frame velocity
   setpoint and `faStep` tracks it plus gravity compensation within the thrust budget (MECH-RKT-13).
   FA-off: `translateNewtonian` direct body-frame thrust with coast / brake (MECH-RKT-14). The pre-input
   engine-start hover eases to `launchY+1` (MECH-RKT-15). **No path caps velocity** — the bound is on
   acceleration alone (INV-RKT-23); the assist's ceiling applies to the SETPOINT the pilot dials, not to
   the craft. Thrust authority = the classic TWR gate (MECH-RKT-16); fuel is drained server-side when
   `rocketRequireFuel`. [V]/[T]
7. **Commit + derive Euler + replicate.** The new quaternion is committed to fields and to
   DataParameters `FF_QW/QX/QY/QZ`, `prevFfQuat` is snapshotted, `faSetpoint*` → `FA_SP_*`, engine power
   → `FF_ENGINE_POWER`. Euler is derived (`eulerFromQuat`) into `rotationYaw/Pitch` +
   `freeFlightPitch/Roll` for legacy readers only, never re-integrated (INV-RKT-07).
   `velocityChanged = true` forces a vanilla velocity packet so the client can dead-reckon. [V]
8. **NBT persist.** `flightMode` (string, unconditional — INV-RKT-17), `ffQuatW/X/Y/Z` (the source of
   truth; Euler is NOT persisted), `flightAssistOn`, `ffLiftoffTargetY` (only if not NaN),
   `ffHasLeftGround`, `faSetpointFwd/Right/Up` (MECH-RKT-20; C1). Missing keys degrade safely: no
   `flightMode` → CLASSIC (`RocketFlightMode.readFromNBT`), no quaternion → identity + renormalise, no
   FA → true, no target → NaN (assist off), no left-ground → true (armed). [V]
9. **Client receive + predict-then-correct.** The client dead-reckons position from synced `motion*`,
   then pulls the *residual* `(ffServerPos − predicted)` over `FF_CLIENT_CORRECT_TICKS` (MECH-RKT-18);
   `ffServerPos` is recorded by `setPositionAndRotationDirect`, which in FF stores only the server
   position. Attitude: predict from the local input rates, then `slerp` a fraction toward the replicated
   `FF_Q*`. The pilot's prediction barely moves; an observer has zero input, so the slerp carries the
   server attitude. The client re-derives Euler into `rotationYaw/Pitch` from the slerped quaternion. [V]
10. **Camera pin vs riding echo (mixin seam).** While riding, the server echoes each position report
    with a ~1-RTT-stale `SPacketPlayerPosLook` that would snap the hard-locked view.
    `MixinNetHandlerFFCameraRepin` (C6) calls `captureMouseBeforeTeleport()` at HEAD and
    `repinCameraAfterTeleport()` at RETURN; both delegate to `KeyBindings`, which guards on
    `pinnedFlightCraft()` and re-pins `player.rotationYaw/Pitch` to **`rocket.rotationYaw/Pitch`** plus
    the recaptured mouse delta (MECH-CLR-13). Outside FF it is a no-op. [V]
11. **HUD readout.** `KeyBindings.freeFlightHudLines` builds localised lines from the player's live
    bound keys and the replicated state — mode, FA, per-axis body-frame setpoint-vs-actual and speed
    (MECH-CLR-15). Consumed by `RocketEventHandler`. [V]

## Float-in-persistence & authority notes

- The attitude crosses **three** float boundaries (NBT disk, DataParameter wire, save reload) as the
  quaternion only; Euler is regenerated on each side, so there is one source of truth and no
  drift-accumulating float round trip (INV-RKT-07); quaternion precision loss is bounded and healed by
  renormalise-on-load. [V]
- The setpoint (`FA_SP_*`) and engine power (`FF_ENGINE_POWER`) replicate as floats for HUD / sound
  only — never fed back into physics, so their precision is cosmetic. [A]
- The server is authoritative for position (`ffServerPos`), attitude (`FF_Q*`), fuel and the FA
  setpoint; the client owns only *prediction*. `flightMode` and `flightAssistOn` are plain fields synced
  by their sub-packet echo + `writeNetworkableNBT`, NOT by a DataParameter (MECH-RKT-10). [V]

## Gaps & mismatches

- **Camera consumes derived Euler, physics is quaternion (representational seam).** The renderer
  sweeps the quaternion smoothly through loops and inversions (INV-RKT-07), but the hard-locked camera
  pins to `rocket.rotationYaw/Pitch` derived by `eulerFromQuat`. Near pitch ±90° (a loop) it hits the
  gimbal singularity: the camera yaw can flip ~180° and roll is dropped, so the pilot's view can snap
  while the ship rotates smoothly. The quaternion's "no gimbal lock" guarantee does **not** reach the
  camera (med, rocket-entity ↔ client-render); the loop failure is not bounded anywhere. [A]
- **Client smoothness depends on the vanilla velocity packet.** MECH-RKT-18's dead reckoning reads
  client `motion*`, which is refreshed only when the server sets `velocityChanged`. If the FF driver
  dropped that flag on a tick, the client would fall back to raw `ffServerPos` correction (jitter), with
  no compile-time link between producer and consumer. A watch item, not a current defect. [A]
- **`flightMode` sync has no refusal echo.** The M-key toggle is client-gated on `!isInFlight()`
  matching the server gate, and `INFLIGHT` is a replicated DataParameter, so the sides normally agree;
  but `SET_FLIGHT_MODE` is refused server-side while in flight with **no corrective echo**, so a client
  that toggled optimistically under a rare gate disagreement would stay desynced until the next
  `REQUESTNBT`. Low likelihood given the shared gate. [A]
- The four FF sub-packet ordinals are append-only (INV-RKT-15) and every FF mutation re-verifies
  passenger authority (INV-RKT-16); no producer / consumer id or packet collision exists in this flow.
