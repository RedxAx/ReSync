package restudio.resync.worldgen.evaluator;

public final class FractalEvaluator {
    private FractalEvaluator() {
    }

    public static float evaluateFBM(float source, int octaves, float lacunarity, float gain) {
        float value = 0f;
        float amplitude = 1f;
        float frequency = 1f;
        float normalizer = 0f;
        for (int i = 0; i < Math.max(1, octaves); i++) {
            value += source * frequency * amplitude;
            normalizer += amplitude;
            frequency *= lacunarity;
            amplitude *= gain;
        }
        return normalizer == 0f ? value : value / normalizer;
    }

    public static float evaluateRidged(float source, int octaves, float lacunarity, float gain) {
        return 1f - Math.abs(evaluateFBM(source, octaves, lacunarity, gain));
    }

    public static float evaluatePingPong(float source, int octaves, float lacunarity, float gain, float strength) {
        float value = evaluateFBM(source, octaves, lacunarity, gain) * strength;
        return Math.abs((value % 2f + 2f) % 2f - 1f);
    }
}
