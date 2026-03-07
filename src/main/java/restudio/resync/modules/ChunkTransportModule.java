package restudio.resync.modules;

import restudio.resync.core.Session;
import restudio.resync.protocol.messages.DataMessage;
import restudio.resync.protocol.messages.SubscribeRequest;
import restudio.resync.protocol.messages.UnsubscribeRequest;

public class ChunkTransportModule implements Module {
    private static final ModuleMetadata METADATA = ModuleMetadata.of("chunks", "Chunks", "chunks");
    private ChunkModule delegate;

    @Override
    public ModuleMetadata getMetadata() {
        return METADATA;
    }

    @Override
    public void initialize(ModuleContext context) {
        delegate = new ChunkModule(
            context.getCodec(),
            context.getConfig().getMemory().getMaxCacheSize(),
            context.getConfig().getMemory().getCacheTtlMinutes()
        );
        context.registerService(ChunkModule.class, delegate);
    }

    @Override
    public void onSubscribe(Session session, SubscribeRequest req) {
        delegate.onSubscribe(session, req);
    }

    @Override
    public void onUnsubscribe(Session session, UnsubscribeRequest req) {
        delegate.onUnsubscribe(session, req);
    }

    @Override
    public void onData(Session session, DataMessage req) {
        delegate.onData(session, req);
    }

    @Override
    public void onTick() {
        delegate.onTick();
    }

    @Override
    public void cleanup(Session session) {
        delegate.cleanup(session);
    }
}
