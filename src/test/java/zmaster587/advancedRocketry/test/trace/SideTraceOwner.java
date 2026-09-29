package zmaster587.advancedRocketry.test.trace;

/**
 * Implemented on {@code Minecraft} and {@code MinecraftServer} by the two test mixins that give each
 * side its {@link SideTrace}. A duck interface: nothing implements it in source, and a cast to it is
 * how an instrument reaches the side it runs on.
 */
public interface SideTraceOwner {

    SideTrace arTest$sideTrace();
}
