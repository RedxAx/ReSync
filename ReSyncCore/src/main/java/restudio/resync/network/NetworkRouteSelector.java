package restudio.resync.network;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

public final class NetworkRouteSelector {
    private NetworkRouteSelector() {
    }

    public static Optional<NetworkRoutingCandidate> select(UUID playerId, NetworkRoutingGroup group, List<NetworkRoutingCandidate> candidates) {
        List<NetworkRoutingCandidate> available = candidates == null ? List.of() : candidates.stream().filter(NetworkRoutingCandidate::available).toList();
        if (available.isEmpty()) {
            return Optional.empty();
        }
        return switch (group.strategy()) {
            case ORDERED -> available.stream().findFirst();
            case LEAST_PLAYERS -> available.stream().min(Comparator.comparingInt(NetworkRoutingCandidate::players).thenComparingDouble(NetworkRouteSelector::utilization).thenComparing(NetworkRoutingCandidate::routeName, String.CASE_INSENSITIVE_ORDER));
            case WEIGHTED -> available.stream().min(Comparator.<NetworkRoutingCandidate>comparingDouble(candidate -> weightedScore(playerId, group.id(), candidate, group.weights())).thenComparing(NetworkRoutingCandidate::routeName, String.CASE_INSENSITIVE_ORDER));
        };
    }

    private static double utilization(NetworkRoutingCandidate candidate) {
        return candidate.capacity() <= 0 ? candidate.players() : (double) candidate.players() / candidate.capacity();
    }

    private static double weightedScore(UUID playerId, String groupId, NetworkRoutingCandidate candidate, Map<String, Integer> weights) {
        int weight = weights.getOrDefault(candidate.nodeId(), 1);
        byte[] digest;
        try {
            MessageDigest messageDigest = MessageDigest.getInstance("SHA-256");
            messageDigest.update(playerId.toString().getBytes(StandardCharsets.UTF_8));
            messageDigest.update((byte) 0);
            messageDigest.update(groupId.getBytes(StandardCharsets.UTF_8));
            messageDigest.update((byte) 0);
            digest = messageDigest.digest(candidate.nodeId().getBytes(StandardCharsets.UTF_8));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 Is Not Available", exception);
        }
        long bits = ByteBuffer.wrap(digest).getLong() >>> 11;
        double uniform = (bits + 1.0) / ((1L << 53) + 1.0);
        return -Math.log(uniform) / weight;
    }
}
