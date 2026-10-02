package org.valkyrienskies.mod.common.piloting;

public enum ControllerInputType {

    CaptainsChair(true), ShipHelm(true), Zepplin(false), Telegraph(true), LiftLever(true);

    /** Effectively final, process lifetime: set once when the object is built. */
    private final boolean lockPlayerMovement;

    private ControllerInputType(boolean lockPlayerMovement) {
        this.lockPlayerMovement = lockPlayerMovement;
    }

    public boolean shouldLockPlayerMovement() {
        return lockPlayerMovement;
    }

}
