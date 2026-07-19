package restudio.resync.flow;

import org.junit.jupiter.api.Test;
import restudio.flow.data.FlowDataType;
import restudio.flow.data.FlowTypeRef;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ExtensionTypeContractTest {
    @Test
    void extensionTypeIsExecutableBoundarySafeAndUnloadable() {
        FlowValueCodecRegistry codecs = new FlowValueCodecRegistry();
        FlowDataType definition = new FlowDataType("fixture:quest", FlowDataType.STRING, String.class, null, 0x46B48A);
        try {
            FlowDataType registered = FlowDataType.registerExtensionType("fixture", definition);
            codecs.registerAlias(registered.getId(), registered.getParent().getId());

            assertTrue(FlowDataType.fromString("fixture:quest").isResolved());
            assertEquals("fixture", FlowDataType.fromString("fixture:quest").getOwner());
            assertTrue(codecs.hasCodec(FlowTypeRef.simple("fixture:quest")));
            assertEquals("starter", codecs.decode(FlowTypeRef.simple("fixture:quest"), codecs.encode(FlowTypeRef.simple("fixture:quest"), "starter")));
        } finally {
            codecs.unregister("fixture:quest");
            FlowDataType.unregisterExtensionType("fixture", "fixture:quest");
        }

        assertFalse(FlowDataType.fromString("fixture:quest").isResolved());
    }
}
