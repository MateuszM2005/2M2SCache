package test;

import java.util.Arrays;
import java.util.concurrent.ThreadLocalRandom;

/**
 * 2M2S doc
 * Draws keys from a Zipf distribution over {@code [0, size)}, where rank 0 is the hottest
 * key. Used to approximate the skewed access patterns real caches see, as opposed to the
 * uniform distribution that makes every eviction policy look alike.
 *
 * Keys are pre-drawn into a table at construction, so a draw during a benchmark costs one
 * random index and one array read. A generator that evaluated {@link Math#pow} per draw
 * would cost more than the cache operation being measured.
 */
final class ZipfKeys {
    private final int[] keys;

    /**
     * @param size     number of distinct keys, ranked 0 (hottest) to {@code size - 1}
     * @param exponent skew; 0 is uniform, and around 1 matches commonly cited web workloads
     * @param samples  size of the pre-drawn table
     */
    ZipfKeys(int size, double exponent, int samples) {
        double[] cumulative = new double[size];
        double harmonic = 0.0;
        for (int rank = 1; rank <= size; rank++) {
            harmonic += 1.0 / Math.pow(rank, exponent);
            cumulative[rank - 1] = harmonic;
        }
        for (int i = 0; i < size; i++) {
            cumulative[i] /= harmonic;
        }

        ThreadLocalRandom random = ThreadLocalRandom.current();
        this.keys = new int[samples];
        for (int i = 0; i < samples; i++) {
            int found = Arrays.binarySearch(cumulative, random.nextDouble());
            int rank = (found >= 0) ? found : -(found + 1);
            this.keys[i] = Math.min(rank, size - 1);
        }
    }

    /**
     * @return the next key, drawn independently of previous calls
     */
    int next() {
        return keys[ThreadLocalRandom.current().nextInt(keys.length)];
    }

    /**
     * @return how many distinct keys the pre-drawn table actually contains, so a test can
     *         fail loudly if the distribution ever collapses onto a single key
     */
    int distinctKeys() {
        int[] sorted = keys.clone();
        Arrays.sort(sorted);
        int distinct = sorted.length == 0 ? 0 : 1;
        for (int i = 1; i < sorted.length; i++) {
            if (sorted[i] != sorted[i - 1]) {
                distinct++;
            }
        }
        return distinct;
    }
}
