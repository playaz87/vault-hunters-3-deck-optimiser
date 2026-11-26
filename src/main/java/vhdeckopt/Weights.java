package vhdeckopt;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import java.io.Reader;
import java.io.Writer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.TreeMap;

/** The user's settings, kept as plain JSON in config/vhdeckoptimizer.json. */
public final class Weights {
    /** Counts cards in the layout that give no stat of their own (resource and task cards). */
    public static final String NON_STAT_CARDS = "vhdeckoptimizer:non_stat_cards";
    /** Counts the socketed cores whose condition the layout meets (for example a Bounty core's resource cards). */
    public static final String CORE_CONDITIONS = "vhdeckoptimizer:core_conditions";

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    /** Weight for any attribute not listed in "weights". */
    public double defaultWeight = 1.0;
    /** Attribute id (for example "the_vault:armor") to weight. */
    public Map<String, Double> weights = new TreeMap<>();
    /**
     * Attribute id to the largest total that still counts towards the score,
     * in the same units as the totals the panel shows. Anything above the cap
     * is ignored, so the search stops stacking a stat that cannot go higher.
     */
    public Map<String, Double> caps = new TreeMap<>();
    /** Annealing steps per restart. */
    public int iterations = 20000;
    /** Independent runs; each can contribute one layout to the list. */
    public int restarts = 6;

    public double get(String attribute) {
        Double weight = this.weights.get(attribute);
        if (weight != null) {
            return weight;
        }
        return NON_STAT_CARDS.equals(attribute) ? 0.0 : this.defaultWeight;
    }

    public void set(String attribute, double weight) {
        this.weights.put(attribute, Math.round(weight * 100.0) / 100.0);
    }

    /** The cap for this attribute, or null if it has none. */
    public Double cap(String attribute) {
        return this.caps.get(attribute);
    }

    /** Sets the cap, or removes it when cap is null. */
    public void setCap(String attribute, Double cap) {
        if (cap == null) {
            this.caps.remove(attribute);
        } else {
            this.caps.put(attribute, cap);
        }
    }

    public static Weights load(Path file) {
        if (Files.exists(file)) {
            try (Reader reader = Files.newBufferedReader(file)) {
                Weights loaded = GSON.fromJson(reader, Weights.class);
                if (loaded != null) {
                    if (loaded.weights == null) {
                        loaded.weights = new TreeMap<>();
                    }
                    if (loaded.caps == null) {
                        loaded.caps = new TreeMap<>();
                    }
                    loaded.iterations = Math.max(100, loaded.iterations);
                    loaded.restarts = Math.max(1, loaded.restarts);
                    return loaded;
                }
            } catch (Exception e) {
                // Unreadable file: fall back to defaults and leave the file alone.
            }
        }
        return new Weights();
    }

    public void save(Path file) {
        try (Writer writer = Files.newBufferedWriter(file)) {
            GSON.toJson(this, writer);
        } catch (Exception e) {
            // Not being able to save settings should never break the game.
        }
    }
}
