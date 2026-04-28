package restudio.flow.data;

import restudio.resync.flow.handler.HandlerConfig;

import java.util.HashMap;
import java.util.Map;

public class FlowNode {
    private String type;
    private double x;
    private double y;
    private Map<String, Object> inputValues;
    private Map<String, Object> handlerConfig = new HashMap<>();

    public FlowNode() {
        this.inputValues = new HashMap<>();
    }

    public FlowNode(String type, double x, double y, Map<String, Object> inputValues) {
        this.type = type;
        this.x = x;
        this.y = y;
        this.inputValues = inputValues != null ? inputValues : new HashMap<>();
    }

    public String getType() {
        return type;
    }

    public void setType(String type) {
        this.type = type;
    }

    public double getX() {
        return x;
    }

    public void setX(double x) {
        this.x = x;
    }

    public double getY() {
        return y;
    }

    public void setY(double y) {
        this.y = y;
    }

    public Map<String, Object> getInputValues() {
        return inputValues;
    }

    public void setInputValues(Map<String, Object> inputValues) {
        this.inputValues = inputValues;
    }

    public HandlerConfig getHandlerConfig() {
        return new HandlerConfig(handlerConfig);
    }

    public void setHandlerConfig(Map<String, Object> handlerConfig) {
        this.handlerConfig = handlerConfig != null ? new HashMap<>(handlerConfig) : new HashMap<>();
    }
}
