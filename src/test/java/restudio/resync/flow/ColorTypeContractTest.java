package restudio.resync.flow;

import org.bukkit.Color;
import org.junit.jupiter.api.Test;
import restudio.flow.data.FlowDataType;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;

class ColorTypeContractTest {
    @Test
    void rgbColorUsesSameRuntimeTypeAsColorHandlers() {
        TypeAdapterRegistry adapters = new TypeAdapterRegistry();
        Color color = adapters.adapt("#1A80FF", Color.class);

        assertSame(Color.class, FlowDataType.RGB_COLOR.getJavaType());
        assertEquals(0x1A80FF, color.asRGB());
        assertEquals("#1A80FF", adapters.adapt(color, String.class));
    }
}
