package dev.stannismod.stellurgy.api.damage;

/**
 * A block that makes the repair bay it touches bigger.
 *
 * <p>A bay's size is the connected run of these touching its controller, and a bigger bay works
 * faster — with shrinking gains and a ceiling. Implemented by the BLOCK, and empty on purpose: a
 * frame is a frame wherever it stands, so an addon adds one by implementing this on a plain block,
 * with nothing to register and no state to keep.</p>
 */
public interface IRepairBayPart {
}
