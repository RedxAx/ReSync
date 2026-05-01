package restudio.resync.flow.diagnostics;

public interface FlowTraceSink {
    void onTraceRecord(FlowTraceRecord record);
}
