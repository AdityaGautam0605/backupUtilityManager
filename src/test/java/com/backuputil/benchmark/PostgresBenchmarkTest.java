package com.backuputil.benchmark;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
class PostgresBenchmarkTest {
    @Test void medianDoesNotSelectBestRunOrMutateSamples() {
        double[] values = {100, 2, 3};
        assertEquals(3, PostgresBenchmark.median(values));
        assertArrayEquals(new double[]{100, 2, 3}, values);
        assertEquals(2.5, PostgresBenchmark.median(new double[]{1, 4, 2, 3}));
    }
    @Test void reductionUsesRawDumpAsBaselineAndAllowsExpansion() {
        assertEquals(75, PostgresBenchmark.reduction(1000, 250));
        assertEquals(-10, PostgresBenchmark.reduction(1000, 1100), 0.00001);
    }
}
