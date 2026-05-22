package restudio.resync.flow;

import org.bukkit.Bukkit;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.util.Vector;
import restudio.resync.ReSync;

public final class FlowMutations {
    private FlowMutations() {
    }

    public static void afterDamageEvent(FlowContext ctx, Entity entity, Runnable action) {
        if (action == null) {
            return;
        }
        if (ctx != null && ctx.getEvent() instanceof EntityDamageEvent) {
            ctx.runLaterBeforeContinuation(() -> {
                if (entity == null || entity.isValid()) {
                    action.run();
                }
            }, 1);
            return;
        }
        runSync(ctx, action);
    }

    public static void applyVelocity(FlowContext ctx, Entity entity, Vector velocity) {
        if (entity == null || velocity == null) {
            return;
        }
        Vector applied = finiteVelocity(velocity);
        afterDamageEvent(ctx, entity, () -> entity.setVelocity(applied));
    }

    public static void heal(FlowContext ctx, LivingEntity living, double amount) {
        if (living == null) {
            return;
        }
        afterDamageEvent(ctx, living, () -> living.setHealth(Math.min(living.getMaxHealth(), living.getHealth() + amount)));
    }

    public static void setHealth(FlowContext ctx, LivingEntity living, double health) {
        if (living == null) {
            return;
        }
        afterDamageEvent(ctx, living, () -> living.setHealth(Math.max(0.0, Math.min(living.getMaxHealth(), health))));
    }

    public static void shield(FlowContext ctx, LivingEntity living, double amount, int duration) {
        if (living == null) {
            return;
        }
        afterDamageEvent(ctx, living, () -> {
            double previous = living.getAbsorptionAmount();
            living.setAbsorptionAmount(Math.max(previous, amount));
            if (ctx != null) {
                ctx.runLater(() -> {
                    if (living.isValid()) {
                        living.setAbsorptionAmount(Math.min(living.getAbsorptionAmount(), previous));
                    }
                }, Math.max(1, duration));
            }
        });
    }

    public static void setAbsorption(FlowContext ctx, LivingEntity living, double absorption) {
        if (living == null) {
            return;
        }
        afterDamageEvent(ctx, living, () -> living.setAbsorptionAmount(Math.max(0.0, absorption)));
    }

    public static void noDamageTicks(FlowContext ctx, LivingEntity living, int ticks) {
        if (living == null) {
            return;
        }
        afterDamageEvent(ctx, living, () -> living.setNoDamageTicks(ticks));
    }

    public static Vector finiteVelocity(Vector velocity) {
        return new Vector(
            Double.isFinite(velocity.getX()) ? velocity.getX() : 0.0,
            Double.isFinite(velocity.getY()) ? velocity.getY() : 0.0,
            Double.isFinite(velocity.getZ()) ? velocity.getZ() : 0.0
        );
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
        Bukkit.getScheduler().runTask(ReSync.getInstance(), action);
    }
}
