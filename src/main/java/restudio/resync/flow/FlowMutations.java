package restudio.resync.flow;

import org.bukkit.Bukkit;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.util.Vector;

public final class FlowMutations {
    private FlowMutations() {
    }

    public static void afterDamageEvent(FlowContext ctx, Entity entity, Runnable action) {
        if (entity == null) throw new IllegalArgumentException("Mutation target is required");
        if (action == null) throw new IllegalArgumentException("Mutation action is required");
        if (ctx != null && ctx.getEvent() instanceof EntityDamageEvent) {
            ctx.runLaterBeforeContinuation(() -> {
                if (!entity.isValid()) throw new IllegalStateException("Mutation target is no longer valid");
                action.run();
            }, 1);
            return;
        }
        runSync(ctx, () -> {
            if (!entity.isValid()) throw new IllegalStateException("Mutation target is no longer valid");
            action.run();
        });
    }

    public static void applyVelocity(FlowContext ctx, Entity entity, Vector velocity) {
        if (entity == null) throw new IllegalArgumentException("Velocity target is required");
        if (velocity == null) throw new IllegalArgumentException("Velocity is required");
        Vector applied = finiteVelocity(velocity);
        afterDamageEvent(ctx, entity, () -> entity.setVelocity(applied));
    }

    public static void heal(FlowContext ctx, LivingEntity living, double amount) {
        if (living == null) throw new IllegalArgumentException("Healing target is required");
        if (!Double.isFinite(amount) || amount < 0) throw new IllegalArgumentException("Healing amount must be a finite non-negative number");
        afterDamageEvent(ctx, living, () -> living.setHealth(Math.min(living.getMaxHealth(), living.getHealth() + amount)));
    }

    public static void setHealth(FlowContext ctx, LivingEntity living, double health) {
        if (living == null) throw new IllegalArgumentException("Health target is required");
        if (!Double.isFinite(health)) throw new IllegalArgumentException("Health must be finite");
        afterDamageEvent(ctx, living, () -> living.setHealth(Math.max(0.0, Math.min(living.getMaxHealth(), health))));
    }

    public static void shield(FlowContext ctx, LivingEntity living, double amount, int duration) {
        if (living == null) throw new IllegalArgumentException("Shield target is required");
        if (ctx == null) throw new IllegalArgumentException("Shield mutations require an active Flow context");
        if (!Double.isFinite(amount) || amount < 0) throw new IllegalArgumentException("Shield amount must be a finite non-negative number");
        if (duration < 1) throw new IllegalArgumentException("Shield duration must be positive");
        afterDamageEvent(ctx, living, () -> {
            double previous = living.getAbsorptionAmount();
            living.setAbsorptionAmount(Math.max(previous, amount));
            ctx.runLater(() -> {
                if (living.isValid()) living.setAbsorptionAmount(Math.min(living.getAbsorptionAmount(), previous));
            }, duration);
        });
    }

    public static void setAbsorption(FlowContext ctx, LivingEntity living, double absorption) {
        if (living == null) throw new IllegalArgumentException("Absorption target is required");
        if (!Double.isFinite(absorption) || absorption < 0) throw new IllegalArgumentException("Absorption must be a finite non-negative number");
        afterDamageEvent(ctx, living, () -> living.setAbsorptionAmount(absorption));
    }

    public static void noDamageTicks(FlowContext ctx, LivingEntity living, int ticks) {
        if (living == null) throw new IllegalArgumentException("Invulnerability target is required");
        if (ticks < 0) throw new IllegalArgumentException("Invulnerability ticks cannot be negative");
        afterDamageEvent(ctx, living, () -> living.setNoDamageTicks(ticks));
    }

    public static Vector finiteVelocity(Vector velocity) {
        if (velocity == null) throw new IllegalArgumentException("Velocity is required");
        if (!Double.isFinite(velocity.getX()) || !Double.isFinite(velocity.getY()) || !Double.isFinite(velocity.getZ())) {
            throw new IllegalArgumentException("Velocity components must be finite");
        }
        return velocity.clone();
    }

    private static void runSync(FlowContext ctx, Runnable action) {
        if (Bukkit.isPrimaryThread()) {
            action.run();
            return;
        }
        if (ctx != null) {
            ctx.runSyncBeforeContinuation(action);
            return;
        }
        throw new IllegalStateException("Off-thread mutations require an active Flow context");
    }
}
