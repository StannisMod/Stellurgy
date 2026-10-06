package dev.stannismod.stellurgy.test.mixin;

import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.World;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import dev.stannismod.stellurgy.api.damage.Contact;
import dev.stannismod.stellurgy.api.damage.ContactResult;
import dev.stannismod.stellurgy.api.damage.IContactResponder;
import dev.stannismod.stellurgy.api.damage.TravellingBody;
import dev.stannismod.stellurgy.projectile.ContactResolver;
import dev.stannismod.stellurgy.test.trace.TestTrace;

/**
 * The contact seam's two decisions about a body meeting a block, as events. Both are where
 * {@code ContactResolver.resolve} — the one door a thrown round AND a held beam go through — asks
 * something about the meeting, so they cover both weapon families.
 *
 * <ul>
 *   <li><b>{@code contact_answered}</b> — the call to {@code IContactResponder.onContact} inside
 *       {@code resolve}, observed by a redirect that makes the original call exactly once and
 *       records its answer: a block that answers for itself (mirror, reactive plating). Carries the
 *       block {@code pos}, the body's {@code kind} and {@code energy}, the {@code answer}
 *       ({@code NO_OPINION}, {@code STOPPED}, {@code DEFLECTED}, {@code PASSED}, or {@code NULL}),
 *       the {@code residual} it let through, and for a deflection the outgoing velocity
 *       {@code outVx/outVy/outVz}. SILENT about a block with no responder.</li>
 *   <li><b>{@code contact_ricochet_decided}</b> — the RETURN of {@code ricochet}, asked of every
 *       meeting a responder did not answer (and of a responder's declined one): {@code skipped} is
 *       whether the body glanced off, with the {@code pos}, {@code kind} and the contact's
 *       {@code incidence} in degrees. SILENT about a meeting a responder answered for itself.</li>
 * </ul>
 *
 * <p>Server log, filed against the world the meeting happened in. Read by
 * {@code ArmourAnswersByKindAndAngleE2ETest}, {@code ArmourBlocksAnswerForThemselvesE2ETest} and
 * {@code SpacedArmourIsAskedTwiceE2ETest}.</p>
 */
@Mixin(ContactResolver.class)
public abstract class MixinContactResolverEvents {

    private static final String INSTRUMENT = "contact_events";

    @Redirect(method = "resolve",
            at = @At(value = "INVOKE",
                    target = "Ldev/stannismod/stellurgy/api/damage/IContactResponder;"
                            + "onContact(Lnet/minecraft/world/World;Ldev/stannismod/stellurgy/api/damage/Contact;)"
                            + "Ldev/stannismod/stellurgy/api/damage/ContactResult;"),
            require = 1)
    private static ContactResult stellurgyTest$answered(IContactResponder responder, World world,
                                                        Contact contact) {
        // The original call, first and once: this hook observes the block's answer and must not
        // become a second asking of the question.
        ContactResult answer = responder.onContact(world, contact);
        if (world != null && !world.isRemote) {
            TestTrace.instrument(world, INSTRUMENT);
            String kind = answer == null ? "NULL" : answer.isNoOpinion() ? "NO_OPINION"
                    : answer.isStopped() ? "STOPPED" : answer.isDeflected() ? "DEFLECTED" : "PASSED";
            Vec3d out = answer == null || !answer.isDeflected() ? null : answer.getDeflectedVelocity();
            TestTrace.record(world, "contact_answered", stellurgyTest$pos(contact.getPos())
                    + ",\"kind\":\"" + contact.getKind() + "\""
                    + ",\"energy\":" + contact.getEnergy()
                    + ",\"answer\":\"" + kind + "\""
                    + ",\"residual\":" + (answer == null ? 0 : answer.getResidualEnergy())
                    + (out == null ? "" : ",\"outVx\":" + out.x + ",\"outVy\":" + out.y + ",\"outVz\":" + out.z));
        }
        return answer;
    }

    @Inject(method = "ricochet", at = @At("RETURN"), require = 1)
    private static void stellurgyTest$ricochet(World world, Contact contact, TravellingBody body,
                                               CallbackInfoReturnable<ContactResult> cir) {
        if (world == null || world.isRemote || contact == null) {
            return;
        }
        TestTrace.instrument(world, INSTRUMENT);
        TestTrace.record(world, "contact_ricochet_decided", stellurgyTest$pos(contact.getPos())
                + ",\"kind\":\"" + contact.getKind() + "\""
                + ",\"incidence\":" + contact.getIncidenceDegrees()
                + ",\"skipped\":" + (cir.getReturnValue() != null));
    }

    @Unique
    private static String stellurgyTest$pos(BlockPos pos) {
        return "\"pos\":\"" + (pos == null ? "null" : pos.getX() + "," + pos.getY() + "," + pos.getZ())
                + "\"";
    }
}
