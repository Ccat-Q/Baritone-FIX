package baritone.launch.guard;

public final class RecalcGuard {
    private RecalcGuard() {}

    public static final ThreadLocal<Boolean> RECALC_IN_PROGRESS = ThreadLocal.withInitial(() -> false);
}