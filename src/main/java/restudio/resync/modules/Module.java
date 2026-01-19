package restudio.resync.modules;

import restudio.resync.core.Session;
import restudio.resync.protocol.messages.DataMessage;
import restudio.resync.protocol.messages.SubscribeRequest;
import restudio.resync.protocol.messages.UnsubscribeRequest;

public interface Module {
    String getChannelId();

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
