package restudio.resync.worldgen.evaluator;

import restudio.resync.worldgen.noise.FastNoiseLite;

public final class NoiseEvaluator {
    private NoiseEvaluator() {
    }

    public static float evaluateSimplex(float x, float y, float z, int seed, float frequency) {
        FastNoiseLite noise = noise(seed, frequency, FastNoiseLite.NoiseType.OpenSimplex2);
        return noise.GetNoise(x, y, z);
    }

    public static float evaluatePerlin(float x, float y, float z, int seed, float frequency) {
        FastNoiseLite noise = noise(seed, frequency, FastNoiseLite.NoiseType.Perlin);
        return noise.GetNoise(x, y, z);
    }

    public static float evaluateValue(float x, float y, float z, int seed, float frequency) {
        FastNoiseLite noise = noise(seed, frequency, FastNoiseLite.NoiseType.Value);
        return noise.GetNoise(x, y, z);
    }

    public static float evaluateCellular(float x, float y, float z, int seed, float frequency, String distanceFunction) {
        FastNoiseLite noise = noise(seed, frequency, FastNoiseLite.NoiseType.Cellular);
        if ("manhattan".equalsIgnoreCase(distanceFunction)) {
            noise.SetCellularDistanceFunction(FastNoiseLite.CellularDistanceFunction.Manhattan);
        } else if ("hybrid".equalsIgnoreCase(distanceFunction)) {
            noise.SetCellularDistanceFunction(FastNoiseLite.CellularDistanceFunction.Hybrid);
        } else {
            noise.SetCellularDistanceFunction(FastNoiseLite.CellularDistanceFunction.Euclidean);
        }
        return noise.GetNoise(x, y, z);
    }

    public static float evaluateWhite(float x, float y, float z, int seed) {
        int hash = seed;
        hash = hash * 31 + Float.floatToIntBits(x);
        hash = hash * 31 + Float.floatToIntBits(y);
        hash = hash * 31 + Float.floatToIntBits(z);
        hash ^= hash >>> 16;
        hash *= 0x7feb352d;
        hash ^= hash >>> 15;
        hash *= 0x846ca68b;
        hash ^= hash >>> 16;
        return ((hash & 0x7fffffff) / (float) Integer.MAX_VALUE) * 2f - 1f;
    }

    private static FastNoiseLite noise(int seed, float frequency, FastNoiseLite.NoiseType noiseType) {
        FastNoiseLite noise = new FastNoiseLite(seed);
        noise.SetFrequency(frequency);
        noise.SetNoiseType(noiseType);
        return noise;
    }
}
