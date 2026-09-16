package restudio.resync.modules;

import restudio.resync.core.Session;
import restudio.resync.protocol.messages.DataMessage;
import restudio.resync.protocol.messages.SubscribeRequest;
import restudio.resync.protocol.messages.UnsubscribeRequest;

import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;

public interface Module {
    ModuleMetadata getMetadata();

    default String getModuleId() {
        return getMetadata().id();
    }

    default String getChannelId() {
        return getMetadata().primaryChannel();
    }

    default Set<String> getChannels() {
        return getMetadata().channels();
    }

    default boolean isEnabledByDefault() {
        return true;
    }

    default void initialize(ModuleContext context) {
    }

    default void start(ModuleContext context) {
    }

    default void stop(ModuleContext context) {
    }

    default void prepareStop(ModuleContext context) {
        stop(context);
    }

    default CompletionStage<Void> finishStopAsync(ModuleContext context) {
        return CompletableFuture.completedFuture(null);
    }

    default CompletionStage<Void> stopAsync(ModuleContext context) {
        prepareStop(context);
        return finishStopAsync(context);
    }

    default void onSubscribe(Session session, SubscribeRequest req) {
    }

    default void onUnsubscribe(Session session, UnsubscribeRequest req) {
    }

    default void onData(Session session, DataMessage req) {
    }

    default void onTick() {
    }

    default void cleanup(Session session) {
    }
}
