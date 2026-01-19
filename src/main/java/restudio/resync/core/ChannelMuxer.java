package restudio.resync.core;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

public class ChannelMuxer {
    private final ConcurrentHashMap<String, Channel> channels;
    private final AtomicInteger channelIdCounter;

    public ChannelMuxer() {
        this.channels = new ConcurrentHashMap<>();
        this.channelIdCounter = new AtomicInteger(1000);
    }

    public Channel createChannel(String channelId) {
        int numericId = channelIdCounter.getAndIncrement();
        Channel channel = new Channel(channelId, numericId);
        channels.put(channelId, channel);
        return channel;
    }

    public Channel getChannel(String channelId) {
        return channels.get(channelId);
    }

    public Channel getChannelByNumericId(int id) {
        for (Channel channel : channels.values()) {
            if (channel.getNumericId() == id) {
                return channel;
            }
        }
        return null;
    }

    public void removeChannel(String channelId) {
        channels.remove(channelId);
    }

    public int getChannelCount() {
        return channels.size();
    }

    public static class Channel {
        private final String id;
        private final int numericId;
        private final AtomicInteger sequence;
        private final AtomicInteger subscriberCount;
        private volatile int flowControlWindow;

        public Channel(String id, int numericId) {
            this.id = id;
            this.numericId = numericId;
            this.sequence = new AtomicInteger(0);
            this.subscriberCount = new AtomicInteger(0);
            this.flowControlWindow = 1000;
        }

        public String getId() {
            return id;
        }

        public int getNumericId() {
            return numericId;
        }

        public int getNextSequence() {
            return sequence.getAndIncrement();
        }

        public int getSubscriberCount() {
            return subscriberCount.get();
        }

        public void incrementSubscribers() {
            subscriberCount.incrementAndGet();
        }

        public void decrementSubscribers() {
            subscriberCount.decrementAndGet();
        }

        public int getFlowControlWindow() {
            return flowControlWindow;
        }

        public void setFlowControlWindow(int flowControlWindow) {
            this.flowControlWindow = flowControlWindow;
        }

        public boolean canSend() {
            return flowControlWindow > 0;
        }

        public void decrementWindow(int amount) {
            flowControlWindow -= amount;
        }

        public void resetWindow() {
            flowControlWindow = 1000;
        }
    }
}
