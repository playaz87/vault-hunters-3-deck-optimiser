package vhdeckopt.search;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class ScoringTest {
    private static final double NONE = Double.NaN;

    @Test
    void addsWeightedTotalsOverTheirNorms() {
        Scoring scoring = new Scoring(new double[]{1.0, 2.0}, new double[]{4.0, 0.5}, new double[]{NONE, NONE});
        assertEquals(8.0 / 4.0 + 2.0 * 1.5 / 0.5, scoring.score(new double[]{8.0, 1.5}), 1.0e-9);
    }

    @Test
    void ignoresEverythingAboveACap() {
        Scoring scoring = new Scoring(new double[]{1.0, 1.0}, new double[]{1.0, 1.0}, new double[]{0.3, NONE});
        assertEquals(0.3 + 2.0, scoring.score(new double[]{0.5, 2.0}), 1.0e-9);
        assertEquals(0.2 + 2.0, scoring.score(new double[]{0.2, 2.0}), 1.0e-9);
        assertTrue(scoring.overCap(0, 0.5));
        assertFalse(scoring.overCap(0, 0.3));
        assertFalse(scoring.overCap(1, 99.0));
    }

    @Test
    void aCappedStatStopsPullingCardsOnceFull() {
        // Kind 0 adds 0.2 to a stat capped at 0.4; kind 1 adds 1 to an uncapped stat worth half as much.
        Scoring scoring = new Scoring(new double[]{1.0, 0.5}, new double[]{0.2, 1.0}, new double[]{0.4, NONE});
        Problem problem = new Problem() {
            public int slotCount() {
                return 4;
            }

            public int kindCount() {
                return 2;
            }

            public int available(int kind) {
                return 4;
            }

            public boolean allowed(int slot, int kind) {
                return true;
            }

            public int[] current() {
                return new int[]{-1, -1, -1, -1};
            }

            public double score(int[] layout) {
                double[] totals = new double[2];
                for (int kind : layout) {
                    if (kind == 0) {
                        totals[0] += 0.2;
                    } else if (kind == 1) {
                        totals[1] += 1.0;
                    }
                }
                return scoring.score(totals);
            }
        };
        int[] best = Annealer.run(problem, 2000, 3, 23L, () -> false, p -> {
        }).get(0).layout();
        int capped = 0;
        for (int kind : best) {
            if (kind == 0) {
                capped++;
            }
        }
        assertEquals(2, capped, "two cards fill the cap; the rest go to the uncapped stat");
    }
}
