package restudio.resync.flow.handler.generic;

import org.junit.jupiter.api.Test;

import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PlayerActionHandlerTest {
    @Test
    void randomStackDefaultsRespectItemsWithSmallerStacks() {
        assertTrue(IntStream.range(0, 200).map(ignored -> PlayerActionHandler.randomStackAmount(1, 99, 16)).allMatch(amount -> amount >= 1 && amount <= 16));
        assertTrue(IntStream.range(0, 200).map(ignored -> PlayerActionHandler.randomStackAmount(1, 99, 1)).allMatch(amount -> amount == 1));
    }

    @Test
    void randomStackRejectsRangesOutsideTheItemLimit() {
        assertThrows(IllegalArgumentException.class, () -> PlayerActionHandler.randomStackAmount(17, 99, 16));
        assertThrows(IllegalArgumentException.class, () -> PlayerActionHandler.randomStackAmount(1, 0, 16));
    }
}
