package vhdeckopt.search;

/**
 * Turns a layout's attribute totals into one score: each total, cut down to
 * its cap if it has one, times its weight, divided by its norm, summed.
 * Plain Java, no game types.
 */
public final class Scoring {
    private final double[] perUnit;
    private final double[] caps;

    /**
     * @param weights weight of each attribute
     * @param norms   what one unit of score is worth in each attribute (above 0)
     * @param caps    largest total that still counts, or NaN for no cap
     */
    public Scoring(double[] weights, double[] norms, double[] caps) {
        if (weights.length != norms.length || weights.length != caps.length) {
            throw new IllegalArgumentException("weights, norms and caps must be the same length");
        }
        this.perUnit = new double[weights.length];
        for (int i = 0; i < weights.length; i++) {
            this.perUnit[i] = weights[i] / norms[i];
        }
        this.caps = caps.clone();
    }

    /** What a total counts for: the total itself, or the cap if it is above it. */
    public double counted(int attribute, double total) {
        double cap = this.caps[attribute];
        return Double.isNaN(cap) ? total : Math.min(total, cap);
    }

    /** True when this total is above its attribute's cap. */
    public boolean overCap(int attribute, double total) {
        double cap = this.caps[attribute];
        return !Double.isNaN(cap) && total > cap + 1.0e-9;
    }

    public double score(double[] totals) {
        double sum = 0.0;
        for (int i = 0; i < totals.length; i++) {
            sum += this.perUnit[i] * this.counted(i, totals[i]);
        }
        return sum;
    }
}
