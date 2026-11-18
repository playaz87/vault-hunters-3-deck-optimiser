package vhdeckopt.search;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Random;
import java.util.function.BooleanSupplier;
import java.util.function.DoubleConsumer;

/**
 * Simulated annealing with restarts, each finished by a greedy pass that
 * stops when no single move improves the layout. Returns the distinct local
 * optima found, best first.
 */
public final class Annealer {
    public record Result(int[] layout, double score) {
    }

    /** Tiny preference for layouts that need fewer cards moved, to break ties. */
    private static final double CHANGE_PENALTY = 1.0e-6;

    private final Problem problem;
    private final int slots;
    private final int kinds;
    private final int[] current;
    private final Random random;
    private final BooleanSupplier cancelled;

    private int[] layout;
    private int[] left;
    private double value;

    private Annealer(Problem problem, long seed, BooleanSupplier cancelled) {
        this.problem = problem;
        this.slots = problem.slotCount();
        this.kinds = problem.kindCount();
        this.current = problem.current().clone();
        this.random = new Random(seed);
        this.cancelled = cancelled;
    }

    public static List<Result> run(Problem problem, int iterations, int restarts, long seed,
                                   BooleanSupplier cancelled, DoubleConsumer progress) {
        Annealer annealer = new Annealer(problem, seed, cancelled);
        List<Result> found = new ArrayList<>();
        if (annealer.slots == 0) {
            return found;
        }
        for (int restart = 0; restart < restarts && !cancelled.getAsBoolean(); restart++) {
            final int r = restart;
            annealer.start(restart == 0 ? annealer.current.clone() : annealer.randomLayout());
            int[] best = annealer.anneal(iterations, done -> progress.accept((r + 0.9 * done) / restarts));
            annealer.start(best);
            annealer.greedy();
            progress.accept((r + 1.0) / restarts);
            int[] result = annealer.layout.clone();
            if (found.stream().noneMatch(other -> Arrays.equals(other.layout, result))) {
                found.add(new Result(result, annealer.value));
            }
        }
        found.sort((a, b) -> Double.compare(b.score, a.score));
        List<Result> out = new ArrayList<>();
        for (Result result : found) {
            out.add(new Result(result.layout, problem.score(result.layout)));
        }
        return out;
    }

    private void start(int[] from) {
        this.layout = from;
        this.left = new int[this.kinds];
        for (int kind = 0; kind < this.kinds; kind++) {
            this.left[kind] = this.problem.available(kind);
        }
        for (int kind : from) {
            if (kind >= 0) {
                this.left[kind]--;
            }
        }
        this.value = this.objective();
    }

    private double objective() {
        int changed = 0;
        for (int slot = 0; slot < this.slots; slot++) {
            if (this.layout[slot] != this.current[slot]) {
                changed++;
            }
        }
        return this.problem.score(this.layout) - CHANGE_PENALTY * changed;
    }

    private int[] randomLayout() {
        int[] out = new int[this.slots];
        Arrays.fill(out, -1);
        int[] remaining = new int[this.kinds];
        for (int kind = 0; kind < this.kinds; kind++) {
            remaining[kind] = this.problem.available(kind);
        }
        for (int slot = 0; slot < this.slots; slot++) {
            for (int attempt = 0; attempt < 8 && this.kinds > 0; attempt++) {
                int kind = this.random.nextInt(this.kinds);
                if (remaining[kind] > 0 && this.problem.allowed(slot, kind)) {
                    out[slot] = kind;
                    remaining[kind]--;
                    break;
                }
            }
        }
        return out;
    }

    /** One random move. Returns {slotA, oldA, slotB, oldB} or null if the pick was not legal. */
    private int[] randomMove() {
        int roll = this.random.nextInt(100);
        int a = this.random.nextInt(this.slots);
        if (roll < 45 && this.slots > 1) {
            int b = this.random.nextInt(this.slots);
            return this.swap(a, b) ? new int[]{a, this.layout[b], b, this.layout[a]} : null;
        } else if (roll < 90 && this.kinds > 0) {
            int old = this.layout[a];
            return this.replace(a, this.random.nextInt(this.kinds)) ? new int[]{a, old, -1, -1} : null;
        } else {
            int old = this.layout[a];
            return this.replace(a, -1) ? new int[]{a, old, -1, -1} : null;
        }
    }

    private boolean swap(int a, int b) {
        int ka = this.layout[a];
        int kb = this.layout[b];
        if (a == b || ka == kb) {
            return false;
        }
        if (ka >= 0 && !this.problem.allowed(b, ka) || kb >= 0 && !this.problem.allowed(a, kb)) {
            return false;
        }
        this.layout[a] = kb;
        this.layout[b] = ka;
        return true;
    }

    /** Puts kind (or -1 for empty) into the slot, returning the old card to the pool. */
    private boolean replace(int slot, int kind) {
        int old = this.layout[slot];
        if (old == kind) {
            return false;
        }
        if (kind >= 0 && (this.left[kind] <= 0 || !this.problem.allowed(slot, kind))) {
            return false;
        }
        if (old >= 0) {
            this.left[old]++;
        }
        if (kind >= 0) {
            this.left[kind]--;
        }
        this.layout[slot] = kind;
        return true;
    }

    private void undo(int[] move) {
        if (move[2] >= 0) {
            this.layout[move[0]] = move[1];
            this.layout[move[2]] = move[3];
        } else {
            this.replaceUnchecked(move[0], move[1]);
        }
    }

    private void replaceUnchecked(int slot, int kind) {
        int old = this.layout[slot];
        if (old >= 0) {
            this.left[old]++;
        }
        if (kind >= 0) {
            this.left[kind]--;
        }
        this.layout[slot] = kind;
    }

    private int[] anneal(int iterations, DoubleConsumer progress) {
        // Starting temperature: the typical size of a score change from this layout.
        double sum = 0.0;
        int samples = 0;
        for (int i = 0; i < 60; i++) {
            int[] move = this.randomMove();
            if (move != null) {
                double delta = Math.abs(this.objective() - this.value);
                this.undo(move);
                if (delta > 1.0e-9) {
                    sum += delta;
                    samples++;
                }
            }
        }
        double hot = samples == 0 ? 1.0 : sum / samples;
        double cold = hot * 1.0e-3;

        int[] best = this.layout.clone();
        double bestValue = this.value;
        for (int i = 0; i < iterations; i++) {
            if ((i & 255) == 0) {
                if (this.cancelled.getAsBoolean()) {
                    break;
                }
                progress.accept((double) i / iterations);
            }
            int[] move = this.randomMove();
            if (move == null) {
                continue;
            }
            double temperature = hot * Math.pow(cold / hot, (double) i / iterations);
            double next = this.objective();
            double delta = next - this.value;
            if (delta >= 0.0 || this.random.nextDouble() < Math.exp(delta / temperature)) {
                this.value = next;
                if (next > bestValue) {
                    bestValue = next;
                    best = this.layout.clone();
                }
            } else {
                this.undo(move);
            }
        }
        return best;
    }

    /** Takes any single improving move until none is left. */
    private void greedy() {
        boolean improved = true;
        while (improved && !this.cancelled.getAsBoolean()) {
            improved = false;
            for (int a = 0; a < this.slots; a++) {
                for (int b = a + 1; b < this.slots; b++) {
                    if (this.swap(a, b)) {
                        improved |= this.keepIfBetter(new int[]{a, this.layout[b], b, this.layout[a]});
                    }
                }
                for (int kind = -1; kind < this.kinds; kind++) {
                    int old = this.layout[a];
                    if (this.replace(a, kind)) {
                        improved |= this.keepIfBetter(new int[]{a, old, -1, -1});
                    }
                }
                if (this.cancelled.getAsBoolean()) {
                    return;
                }
            }
        }
    }

    private boolean keepIfBetter(int[] move) {
        double next = this.objective();
        if (next > this.value + 1.0e-12) {
            this.value = next;
            return true;
        }
        this.undo(move);
        return false;
    }
}
