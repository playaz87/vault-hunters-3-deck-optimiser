package vhdeckopt.search;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;

class AnnealerTest {
    /**
     * A 4x4 grid. Kind 0 is a "scaler" worth 1 per plain card (kind 1) in its
     * row or column; kind 1 is worth 1; kind 2 is junk worth -5. Kind 3 may only
     * go in slot 15 and is worth 3 there.
     * Pool: one scaler, 20 plain, 3 junk, 1 special.
     * Optimum: special in slot 15, scaler anywhere else, plain everywhere else.
     * Scaler not sharing a line with slot 15: 6 plain neighbours -> 6 + 14 + 3 = 23.
     */
    private static final class Grid implements Problem {
        final int[] start = new int[16];

        Grid() {
            java.util.Arrays.fill(this.start, -1);
            this.start[0] = 2;
            this.start[1] = 2;
            this.start[5] = 1;
        }

        public int slotCount() {
            return 16;
        }

        public int kindCount() {
            return 4;
        }

        public int available(int kind) {
            return new int[]{1, 20, 3, 1}[kind];
        }

        public boolean allowed(int slot, int kind) {
            return (kind == 3) == (slot == 15);
        }

        public int[] current() {
            return this.start;
        }

        public double score(int[] layout) {
            double total = 0.0;
            for (int slot = 0; slot < 16; slot++) {
                switch (layout[slot]) {
                    case 1 -> total += 1.0;
                    case 2 -> total -= 5.0;
                    case 3 -> total += 3.0;
                    case 0 -> {
                        for (int other = 0; other < 16; other++) {
                            boolean line = other / 4 == slot / 4 || other % 4 == slot % 4;
                            if (other != slot && line && layout[other] == 1) {
                                total += 1.0;
                            }
                        }
                    }
                    default -> {
                    }
                }
            }
            return total;
        }
    }

    @Test
    void findsKnownOptimum() {
        Grid grid = new Grid();
        List<Annealer.Result> results = Annealer.run(grid, 4000, 4, 42L, () -> false, p -> {
        });
        assertFalse(results.isEmpty());
        Annealer.Result best = results.get(0);
        assertEquals(23.0, best.score(), 1.0e-9);
        assertEquals(3, best.layout()[15]);
        for (int i = 1; i < results.size(); i++) {
            assertTrue(results.get(i - 1).score() >= results.get(i).score() - 1.0e-3);
        }
    }

    @Test
    void respectsCountsAndSlotRules() {
        Grid grid = new Grid();
        for (Annealer.Result result : Annealer.run(grid, 2000, 3, 7L, () -> false, p -> {
        })) {
            int[] used = new int[4];
            for (int slot = 0; slot < 16; slot++) {
                int kind = result.layout()[slot];
                if (kind >= 0) {
                    used[kind]++;
                    assertTrue(grid.allowed(slot, kind));
                }
            }
            for (int kind = 0; kind < 4; kind++) {
                assertTrue(used[kind] <= grid.available(kind));
            }
        }
    }

    @Test
    void keepsAnAlreadyOptimalLayout() {
        Problem flat = new Problem() {
            final int[] start = {0, 1, -1};

            public int slotCount() {
                return 3;
            }

            public int kindCount() {
                return 2;
            }

            public int available(int kind) {
                return 1;
            }

            public boolean allowed(int slot, int kind) {
                return true;
            }

            public int[] current() {
                return this.start;
            }

            public double score(int[] layout) {
                double total = 0.0;
                for (int kind : layout) {
                    total += kind >= 0 ? 1.0 : 0.0;
                }
                return total;
            }
        };
        List<Annealer.Result> results = Annealer.run(flat, 500, 1, 1L, () -> false, p -> {
        });
        assertArrayEquals(new int[]{0, 1, -1}, results.get(0).layout());
    }

    @Test
    void stopsWhenCancelled() {
        List<Annealer.Result> results = Annealer.run(new Grid(), 1_000_000, 50, 3L, () -> true, p -> {
        });
        assertTrue(results.isEmpty());
    }
}
