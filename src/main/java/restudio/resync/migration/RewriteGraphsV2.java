package restudio.resync.migration;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import restudio.resync.flow.CoreGraphStorageBoundary;
import restudio.resync.flow.FlowStorage;
import restudio.resync.flow.RewrittenGraphConverter;
import restudio.resync.flow.catalog.CatalogStartupIndex;
import restudio.resync.flow.identity.CatalogBinding;
import restudio.resync.flow.identity.ContractRef;
import restudio.resync.flow.identity.OwnerId;
import restudio.resync.flow.identity.ResourceTypeId;
import restudio.resync.flow.identity.ServerId;
import restudio.resync.flow.identity.ServerResourceLocator;
import restudio.resync.flow.migration.LegacyRuntimeActivationGate;
import restudio.resync.server.ConfigurationPersistenceParticipant;
import restudio.resync.server.ServerIdentityStore;
import restudio.resync.storage.AssetPersistenceGate;
import restudio.resync.storage.AssetTransactionCoordinator;
import restudio.resync.storage.AssetTransactionCoordinator.AssetKey;
import restudio.resync.storage.AssetTransactionCoordinator.Live;
import restudio.resync.storage.StorageSafety;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

public final class RewriteGraphsV2 implements ReSyncDataFix {
    public static final String ID = "rewrite-graphs-v2";
    public static final int MAX_GRAPHS = 16384;
    public static final long MAX_BYTES = 128L * 1024L * 1024L;
    private static final Set<String> GRAPH_TYPES = Set.of("flow", "command", "function");
    private static final Gson GSON = new Gson();

    @Override
    public String id() {
        return ID;
    }

    @Override
    public int sourceVersion() {
        return 1;
    }

    @Override
    public void apply(Context context) throws IOException {
        Path root = context.root();
        Path assets = root.resolve("assets");
        if (!Files.exists(assets, LinkOption.NOFOLLOW_LINKS)) {
            return;
        }
        MigrationPaths.requireDirectory(assets, "Staged graph assets");
        if (!Files.exists(assets.resolve(".asset-coordinator"), LinkOption.NOFOLLOW_LINKS)) {
            try (var entries = Files.list(assets)) {
                if (entries.findAny().isEmpty()) {
                    return;
                }
            }
            throw new IOException("Rewritten graph data fix requires committed asset history");
        }
        try (AssetTransactionCoordinator coordinator = AssetTransactionCoordinator.open(assets, GSON)) {
            List<AssetKey> keys = coordinator.committedSnapshot().states().entrySet().stream()
                .filter(entry -> GRAPH_TYPES.contains(entry.getKey().type()) && entry.getValue() instanceof Live)
                .map(Map.Entry::getKey).sorted(Comparator.comparing(AssetKey::canonical))
                .limit(MAX_GRAPHS + 1L).toList();
            if (keys.size() > MAX_GRAPHS) {
                throw new IOException("Rewritten graph inventory exceeds its recovery bound");
            }
            List<RawAsset> raw = readRawAssets(coordinator, keys);
            if (raw.isEmpty()) {
                return;
            }
            Path identityPath = root.resolve(ServerIdentityStore.FILE_NAME);
            MigrationPaths.requireNoSymlinkTraversal(root, identityPath);
            if (!Files.isRegularFile(identityPath, LinkOption.NOFOLLOW_LINKS)) {
                throw new IOException("Rewritten graph data fix requires its existing server identity");
            }
            ServerId serverId = ServerIdentityStore.open(identityPath).serverId();
            ReplacementActivationRecord.Values activation = ReplacementActivationRecord.read(root);
            CatalogBinding binding = new CatalogBinding(activation.catalogGeneration(), activation.catalogChecksum(),
                activation.runtimeBindingManifestHash());
            CatalogStartupIndex.CommittedSnapshot catalog = CatalogStartupIndex.readCommitted(
                root.resolve(CatalogStartupIndex.DIRECTORY), binding);
            FlowStorage storage = new FlowStorage(root.toFile(), LegacyRuntimeActivationGate.runtime(root),
                new AssetPersistenceGate(root), new ConfigurationPersistenceParticipant(root), serverId, coordinator);
            List<Map<String, Object>> report = new ArrayList<>();
            for (RawAsset asset : raw) {
                ServerResourceLocator resource = new ServerResourceLocator(serverId,
                    ContractRef.of(OwnerId.of("restudio.resync"), ResourceTypeId.of(asset.key().type())), asset.key().id());
                FlowStorage.LegacyCoreRecoverySource source = storage.coordinatedRawGraphSource(resource)
                    .orElseThrow(() -> new IOException("Rewritten graph has no exact committed source: " + resource.canonicalText()));
                if (!source.assetHash().canonicalText().equals(StorageSafety.sha256(asset.bytes()))) {
                    throw new IOException("Rewritten graph source changed during staging: " + resource.canonicalText());
                }
                requireHistory(coordinator, asset, source);
                UUID mutationId = UUID.nameUUIDFromBytes((ID + "\n" + resource.canonicalText() + "\n"
                    + source.revision() + "\n" + source.mutationId() + "\n" + source.assetHash().canonicalText())
                    .getBytes(StandardCharsets.UTF_8));
                CoreGraphStorageBoundary.Decoded converted = RewrittenGraphConverter.convert(asset.value(), resource,
                    Math.addExact(source.revision(), 1L), mutationId, catalog);
                Path retained = sourcePath(root, mutationId);
                MigrationPaths.requireNoSymlinkTraversal(root, retained);
                if (Files.exists(retained, LinkOption.NOFOLLOW_LINKS)) {
                    if (!Files.isRegularFile(retained, LinkOption.NOFOLLOW_LINKS)
                        || !Arrays.equals(asset.bytes(), Files.readAllBytes(retained))) {
                        throw new IOException("Rewritten graph source evidence differs from its committed source");
                    }
                } else {
                    AtomicFiles.writeNew(retained, asset.bytes());
                }
                CoreGraphStorageBoundary.Decoded committed = storage.convertCoordinatedRawGraph(context, resource,
                    source, converted, mutationId);
                report.add(Map.of("resource", resource.canonicalText(), "sourceRevision", source.revision(),
                    "sourceMutationId", source.mutationId().toString(), "sourceAssetHash", source.assetHash().canonicalText(),
                    "sourcePayloadHash", source.payloadHash().canonicalText(), "mutationId", mutationId.toString(),
                    "targetRevision", committed.envelope().assetRevision(),
                    "targetAssetHash", committed.envelope().assetHash().canonicalText()));
            }
            MigrationReportsPersistenceParticipant.writeRewriteGraphReport(context, binding, report);
            coordinator.flush();
        } catch (RuntimeException exception) {
            throw new IOException("Rewritten graph data fix could not preserve and convert its committed sources", exception);
        }
    }

    public static Path sourcePath(Path root, UUID mutationId) {
        return root.resolve(ReSyncDataFixer.VERSION_DIRECTORY).resolve(ID).resolve(mutationId + ".source.json");
    }

    private static List<RawAsset> readRawAssets(AssetTransactionCoordinator coordinator, List<AssetKey> keys)
        throws IOException {
        List<RawAsset> assets = new ArrayList<>();
        long total = 0L;
        for (AssetKey key : keys) {
            Path path = coordinator.committedSnapshot().path(key)
                .orElseThrow(() -> new IOException("Committed graph path is missing"));
            MigrationPaths.requireNoSymlinkTraversal(coordinator.canonicalRoot(), path);
            if (!Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) {
                throw new IOException("Committed graph path is not a regular file");
            }
            long size = Files.size(path);
            total = Math.addExact(total, size);
            if (total > MAX_BYTES) {
                throw new IOException("Rewritten graph source bytes exceed their recovery bound");
            }
            byte[] bytes = Files.readAllBytes(path);
            JsonObject value = JsonParser.parseString(new String(bytes, StandardCharsets.UTF_8)).getAsJsonObject();
            if (!value.has(CoreGraphStorageBoundary.CORE_PAYLOAD_KIND)
                && !value.has(CoreGraphStorageBoundary.CORE_PAYLOAD_VERSION)) {
                assets.add(new RawAsset(key, path, bytes, value));
            }
        }
        return assets;
    }

    private static void requireHistory(AssetTransactionCoordinator coordinator, RawAsset asset,
                                       FlowStorage.LegacyCoreRecoverySource source) throws IOException {
        AssetTransactionCoordinator.MutationView mutation = coordinator.mutation(source.mutationId())
            .orElseThrow(() -> new IOException("Rewritten graph source has no committed mutation"));
        if (!(mutation.result().states().get(asset.key()) instanceof Live live)
            || live.revision() != source.revision() || !live.hash().equals(source.assetHash().canonicalText())) {
            throw new IOException("Rewritten graph source differs from committed mutation history");
        }
        String relative = coordinator.canonicalRoot().relativize(asset.path()).toString().replace('\\', '/');
        byte[] historical = coordinator.inspectMutation(source.mutationId())
            .orElseThrow(() -> new IOException("Rewritten graph source has no committed transaction evidence"))
            .stagedWrites().get(relative);
        if (historical == null || !Arrays.equals(historical, asset.bytes())) {
            throw new IOException("Rewritten graph source bytes differ from committed transaction evidence");
        }
    }

    private record RawAsset(AssetKey key, Path path, byte[] bytes, JsonObject value) {
    }
}
