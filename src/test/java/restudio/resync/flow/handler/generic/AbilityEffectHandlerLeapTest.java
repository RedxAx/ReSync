package restudio.resync.flow.handler.generic;

import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.util.Vector;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.MockBukkit;

import static org.junit.jupiter.api.Assertions.assertEquals;

class AbilityEffectHandlerLeapTest {
    private World world;

    @BeforeEach
    void setUp() {
        MockBukkit.mock();
        world = MockBukkit.getMock().addSimpleWorld("leap-world");
    }

    @AfterEach
    void tearDown() {
        MockBukkit.unmock();
    }

    @Test
    void appliesOnlyThePlannedPathStep() {
        Location previous = new Location(world, 0, 64, 0);
        Location point = new Location(world, 0.25, 64.5, -0.75);
        Vector velocity = AbilityEffectHandler.leapPlayerVelocity(previous, point);
        assertEquals(0.25, velocity.getX(), 0.0001);
        assertEquals(0.5, velocity.getY(), 0.0001);
        assertEquals(-0.75, velocity.getZ(), 0.0001);
    }

    @Test
    void playerPositionErrorCannotAmplifyThePlannedStep() {
        Location previous = new Location(world, 20, 64, 0);
        Location point = new Location(world, 21, 64, 0);
        Vector velocity = AbilityEffectHandler.leapPlayerVelocity(previous, point);
        assertEquals(1.0, velocity.getX(), 0.0001);
        assertEquals(0.0, velocity.getY(), 0.0001);
        assertEquals(0.0, velocity.getZ(), 0.0001);
    }
}
