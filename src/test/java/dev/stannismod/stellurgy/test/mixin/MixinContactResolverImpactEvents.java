package dev.stannismod.stellurgy.test.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

import net.minecraft.world.World;

import dev.stannismod.stellurgy.api.damage.Contact;
import dev.stannismod.stellurgy.api.damage.DamageReport;
import dev.stannismod.stellurgy.api.damage.ImpactRequest;
import dev.stannismod.stellurgy.api.damage.TravellingBody;
import dev.stannismod.stellurgy.damage.ShipDamageService;
import dev.stannismod.stellurgy.projectile.ContactResolver;
import dev.stannismod.stellurgy.test.trace.TestTrace;

/**
 * What the damage service ANSWERED a body that met an ordinary block — the second half of the
 * question {@link MixinShotSubstrateCrossingEvents} records the first half of.
 *
 * <p>A wall a round met and left unmarked was either never found (the crossing) or found and
 * refused (this). {@code ContactResolver#defaultLaw} is where a body's contact with a block that has
 * no answer of its own becomes an impact request, and {@code ShipDamageService.apply} is where that
 * request is decided; this records every such decision beside the request it answered.</p>
 *
 * <ul>
 *   <li>{@code shot_impact_answered} — every return of {@code ShipDamageService.apply} inside
 *       {@code defaultLaw}. Payload: {@code impactId} (the body's contact identity), {@code x}/
 *       {@code y}/{@code z} (the contact's block), {@code budget} (the energy offered),
 *       {@code reach} (the path granted inside the material, blocks), {@code resuming} (a bore begun
 *       on an earlier tick), {@code outcome}, {@code stop} (the stop reason, or {@code null}),
 *       {@code spent}, {@code left}, {@code walked}, {@code staged}, {@code destroyed},
 *       {@code rememberedAt} (the tick the service first saw this impact identity, or {@code null}),
 *       {@code now} (the world's tick).</li>
 * </ul>
 *
 * <p>The instrument {@code shot_impact_answers} is declared on every call. SILENT about blocks that
 * answer a contact themselves ({@code IContactResponder}) — those never reach {@code defaultLaw} —
 * and about ricochets, which are decided before it.</p>
 *
 * <p>The target is a class the project compiles, with no vanilla member in the redirected call, so
 * nothing here is SRG-remapped.</p>
 */
@Mixin(value = ContactResolver.class, remap = false)
public abstract class MixinContactResolverImpactEvents {

    private static final String INSTRUMENT = "shot_impact_answers";

    @Redirect(method = "defaultLaw",
            at = @At(value = "INVOKE",
                    target = "Ldev/stannismod/stellurgy/damage/ShipDamageService;apply("
                            + "Lnet/minecraft/world/World;Ldev/stannismod/stellurgy/api/damage/ImpactRequest;)"
                            + "Ldev/stannismod/stellurgy/api/damage/DamageReport;",
                    remap = false),
            require = 1, remap = false)
    private static DamageReport stellurgyTest$impactAnswered(World world, ImpactRequest request,
                                                             World lawWorld, TravellingBody body,
                                                             Contact contact, double reach,
                                                             boolean resuming) {
        // The original decision, taken once: this observes it and must not decide a second time.
        DamageReport report = ShipDamageService.apply(world, request);
        if (world == null || world.isRemote) {
            return report;
        }
        TestTrace.instrument(world, INSTRUMENT);
        Long rememberedAt = ShipDamageService.rememberedTickOf(world, body.getImpactId());
        TestTrace.record(world, "shot_impact_answered", "\"impactId\":" + body.getImpactId()
                + ",\"x\":" + contact.getPos().getX() + ",\"y\":" + contact.getPos().getY()
                + ",\"z\":" + contact.getPos().getZ()
                + ",\"budget\":" + contact.getEnergy()
                + ",\"reach\":" + reach
                + ",\"resuming\":" + resuming
                + ",\"outcome\":\"" + report.getOutcome().name() + "\""
                + ",\"stop\":" + (report.getStopReason() == null ? "null"
                        : "\"" + report.getStopReason().name() + "\"")
                + ",\"spent\":" + report.getBudgetSpent()
                + ",\"left\":" + report.getBudgetLeft()
                + ",\"walked\":" + report.getDistanceWalked()
                + ",\"staged\":" + report.getBlocksStaged()
                + ",\"destroyed\":" + report.getBlocksDestroyed()
                + ",\"rememberedAt\":" + (rememberedAt == null ? "null" : rememberedAt.toString())
                + ",\"now\":" + world.getTotalWorldTime());
        return report;
    }
}
