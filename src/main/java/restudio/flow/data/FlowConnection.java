package restudio.flow.data;

import com.google.gson.JsonElement;

import java.util.LinkedHashMap;
import java.util.Map;

public class FlowConnection {
    private String sourceNodeId;
    private String sourcePin;
    private String sourcePinId;
    private String sourcePinDisplayName;
    private String targetNodeId;
    private String targetPin;
    private String targetPinId;
    private String targetPinDisplayName;
    private String editorSourceNodeId;
    private String editorSourcePin;
    private String editorSourcePinId;
    private String editorSourcePinDisplayName;
    private transient Map<String, JsonElement> opaqueProperties;

    public FlowConnection() {
    }

    public FlowConnection(String sourceNodeId, String sourcePin, String targetNodeId, String targetPin) {
        this.sourceNodeId = sourceNodeId;
        this.sourcePin = sourcePin;
        this.sourcePinId = sourcePin;
        this.targetNodeId = targetNodeId;
        this.targetPin = targetPin;
        this.targetPinId = targetPin;
    }

    public String getSourceNodeId() {
        return sourceNodeId;
    }

    public void setSourceNodeId(String sourceNodeId) {
        this.sourceNodeId = sourceNodeId;
    }

    public String getSourcePin() {
        return preferredPin(sourcePinId, sourcePin);
    }

    public void setSourcePin(String sourcePin) {
        this.sourcePin = sourcePin;
        if (!hasText(sourcePinId)) {
            sourcePinId = sourcePin;
        }
    }

    public String getSourcePinId() {
        return preferredPin(sourcePinId, sourcePin);
    }

    public void setSourcePinId(String sourcePinId) {
        this.sourcePinId = sourcePinId;
    }

    public String getSourcePinDisplayName() {
        return sourcePinDisplayName != null ? sourcePinDisplayName : getSourcePin();
    }

    public void setSourcePinDisplayName(String sourcePinDisplayName) {
        this.sourcePinDisplayName = sourcePinDisplayName;
    }

    public String getTargetNodeId() {
        return targetNodeId;
    }

    public void setTargetNodeId(String targetNodeId) {
        this.targetNodeId = targetNodeId;
    }

    public String getTargetPin() {
        return preferredPin(targetPinId, targetPin);
    }

    public void setTargetPin(String targetPin) {
        this.targetPin = targetPin;
        if (!hasText(targetPinId)) {
            targetPinId = targetPin;
        }
    }

    public String getTargetPinId() {
        return preferredPin(targetPinId, targetPin);
    }

    public void setTargetPinId(String targetPinId) {
        this.targetPinId = targetPinId;
    }

    public String getTargetPinDisplayName() {
        return targetPinDisplayName != null ? targetPinDisplayName : getTargetPin();
    }

    public void setTargetPinDisplayName(String targetPinDisplayName) {
        this.targetPinDisplayName = targetPinDisplayName;
    }

    public String getEditorSourceNodeId() {
        return editorSourceNodeId;
    }

    public void setEditorSourceNodeId(String editorSourceNodeId) {
        this.editorSourceNodeId = editorSourceNodeId;
    }

    public String getEditorSourcePin() {
        return preferredPin(editorSourcePinId, editorSourcePin);
    }

    public void setEditorSourcePin(String editorSourcePin) {
        this.editorSourcePin = editorSourcePin;
        if (!hasText(editorSourcePinId)) {
            editorSourcePinId = editorSourcePin;
        }
    }

    public String getEditorSourcePinId() {
        return preferredPin(editorSourcePinId, editorSourcePin);
    }

    public void setEditorSourcePinId(String editorSourcePinId) {
        this.editorSourcePinId = editorSourcePinId;
    }

    public String getEditorSourcePinDisplayName() {
        return editorSourcePinDisplayName != null ? editorSourcePinDisplayName : getEditorSourcePin();
    }

    public void setEditorSourcePinDisplayName(String editorSourcePinDisplayName) {
        this.editorSourcePinDisplayName = editorSourcePinDisplayName;
    }

    void adaptLegacyIdentity() {
        if (!hasText(sourcePinId)) {
            sourcePinId = sourcePin;
        }
        if (!hasText(targetPinId)) {
            targetPinId = targetPin;
        }
        if (!hasText(editorSourcePinId)) {
            editorSourcePinId = editorSourcePin;
        }
    }

    public Map<String, JsonElement> getOpaqueProperties() {
        if (opaqueProperties == null) {
            opaqueProperties = new LinkedHashMap<>();
        }
        return opaqueProperties;
    }

    Map<String, JsonElement> peekOpaqueProperties() {
        return opaqueProperties;
    }

    public void setOpaqueProperties(Map<String, JsonElement> opaqueProperties) {
        this.opaqueProperties = opaqueProperties != null ? new LinkedHashMap<>(opaqueProperties) : new LinkedHashMap<>();
    }

    private static String preferredPin(String stablePin, String legacyPin) {
        return hasText(stablePin) ? stablePin : legacyPin;
    }

    private static boolean hasText(String value) {
        return value != null && !value.isBlank();
    }
}
