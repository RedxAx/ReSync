package restudio.resync.contract.canonical;

public final class CanonicalArrays {
    private CanonicalArrays() {
    }

    public static Object[] boxed(Object value) {
        if (value instanceof Object[] array) {
            return array;
        }
        if (value instanceof boolean[] array) {
            Object[] boxed = new Object[array.length];
            for (int index = 0; index < array.length; index++) {
                boxed[index] = array[index];
            }
            return boxed;
        }
        if (value instanceof byte[] array) {
            Object[] boxed = new Object[array.length];
            for (int index = 0; index < array.length; index++) {
                boxed[index] = array[index];
            }
            return boxed;
        }
        if (value instanceof short[] array) {
            Object[] boxed = new Object[array.length];
            for (int index = 0; index < array.length; index++) {
                boxed[index] = array[index];
            }
            return boxed;
        }
        if (value instanceof char[] array) {
            Object[] boxed = new Object[array.length];
            for (int index = 0; index < array.length; index++) {
                boxed[index] = array[index];
            }
            return boxed;
        }
        if (value instanceof int[] array) {
            Object[] boxed = new Object[array.length];
            for (int index = 0; index < array.length; index++) {
                boxed[index] = array[index];
            }
            return boxed;
        }
        if (value instanceof long[] array) {
            Object[] boxed = new Object[array.length];
            for (int index = 0; index < array.length; index++) {
                boxed[index] = array[index];
            }
            return boxed;
        }
        if (value instanceof float[] array) {
            Object[] boxed = new Object[array.length];
            for (int index = 0; index < array.length; index++) {
                boxed[index] = array[index];
            }
            return boxed;
        }
        if (value instanceof double[] array) {
            Object[] boxed = new Object[array.length];
            for (int index = 0; index < array.length; index++) {
                boxed[index] = array[index];
            }
            return boxed;
        }
        return null;
    }
}
