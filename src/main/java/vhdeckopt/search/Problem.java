package vhdeckopt.search;

/**
 * A deck layout problem with no Minecraft or Vault types in it.
 * A layout is an int[] indexed by slot, holding a card kind or -1 for empty.
 * Identical cards are one "kind" with a count, so the search never wastes
 * moves swapping two copies of the same card.
 */
public interface Problem {
    int slotCount();

    int kindCount();

    /** How many copies of this kind exist in total (deck included). */
    int available(int kind);

    boolean allowed(int slot, int kind);

    /**
     * The fewest copies of this kind every layout must hold (0 = optional).
     * The search fits required cards in even when they score nothing; if there
     * is no slot they may go in, it keeps as many as fit.
     */
    default int required(int kind) {
        return 0;
    }

    /** The layout as it is now. Must respect available() and allowed(); it need not hold the required cards. */
    int[] current();

    /** Higher is better. Called from the search thread only. */
    double score(int[] layout);
}
