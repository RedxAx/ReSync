package restudio.resync.server;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import restudio.resync.core.ConnectionInfo;
import restudio.resync.core.ConnectionState;
import restudio.resync.core.Session;
import restudio.resync.flow.catalog.CatalogVersion;
import restudio.resync.flow.identity.CapabilityId;
import restudio.resync.flow.identity.ContractRef;
import restudio.resync.flow.identity.OperationId;
import restudio.resync.flow.identity.OwnerId;
import restudio.resync.flow.identity.ResourceTypeId;
import restudio.resync.flow.identity.ServerId;
import restudio.resync.flow.identity.ServerResourceLocator;
import restudio.resync.flow.protocol.CanonicalPayload;
import restudio.resync.flow.protocol.ProtocolBody;
import restudio.resync.flow.protocol.ProtocolEnvelope;
import restudio.resync.flow.protocol.ProtocolEnvelopeCodec;
import restudio.resync.flow.protocol.ProtocolRejectionCode;
import restudio.resync.flow.protocol.ResourceCreateRequest;
import restudio.resync.flow.protocol.ResourceCreateResult;
import restudio.resync.flow.protocol.ResourceDeleteRequest;
import restudio.resync.flow.protocol.ResourceDuplicateRequest;
import restudio.resync.flow.protocol.ResourceDocument;
import restudio.resync.flow.protocol.ResourceActivateRequest;
import restudio.resync.flow.protocol.ResourceActivationState;
import restudio.resync.flow.protocol.ResourceOperation;
import restudio.resync.flow.protocol.ResourceOperationKind;
import restudio.resync.flow.protocol.ResourcePresentationIntent;
import restudio.resync.flow.protocol.ResourceMoveRequest;
import restudio.resync.flow.protocol.ResourceRenameRequest;
import restudio.resync.flow.protocol.ResourceSaveRequest;
import restudio.resync.flow.protocol.ResourceSubscribeRequest;
import restudio.resync.flow.resource.ResourcePayloadCodecs;
import restudio.resync.modules.flow.FlowResourceAdapter;
import restudio.resync.modules.flow.FlowResourceAuditRecord;
import restudio.resync.modules.flow.FlowResourceKey;
import restudio.resync.modules.flow.FlowResourceMutationAdmission;
import restudio.resync.modules.flow.FlowResourceMutationLease;
import restudio.resync.modules.flow.FlowResourceMutationContext;
import restudio.resync.modules.flow.FlowResourceMutationStamp;
import restudio.resync.modules.flow.FlowResourceRegistry;
import restudio.resync.protocol.FrameSender;
import restudio.resync.protocol.ReSyncProtocolContract;
import restudio.resync.resources.ReSyncManagedResource;
import restudio.resync.resources.ReSyncResourceCatalog;
import restudio.resync.security.ClientIdentity;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.Statement;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SqliteProtocolResourceMutationAuthorityTest {
    private static final ServerId SERVER = new ServerId(UUID.fromString("11111111-1111-4111-8111-111111111111"));
    private static final ContractRef<ResourceTypeId> TYPE = ContractRef.of(new OwnerId("restudio.resync"), new ResourceTypeId(ReSyncResourceCatalog.GUI));
    private static final ContractRef<ResourceTypeId> CUSTOM_TYPE = ContractRef.of(new OwnerId("restudio.resync"),
        new ResourceTypeId(ReSyncResourceCatalog.CUSTOM_CONTENT));
    private static final ContractRef<ResourceTypeId> FUNCTION_TYPE = ContractRef.of(new OwnerId("restudio.resync"),
        new ResourceTypeId(ReSyncResourceCatalog.FUNCTION));

    @Test
    void aggregateCreateReplaysExactPrimaryAndMetadataResultAfterMetadataAdvanceAndRestart(@TempDir Path directory) throws Exception {
        Map<String, JsonObject> values = new ConcurrentHashMap<>();
        CountingAdapter adapter = new CountingAdapter(values);
        FlowResourceRegistry registry = registry(adapter);
        ServerResourceLocator resource = resource("aggregate");
        UUID mutationId = UUID.randomUUID();
        ResourcePresentationIntent presentation = new ResourcePresentationIntent("Aggregate", "Content/GUI/aggregate.json", 3);
        ResourcePresentationIntent canonicalPresentation = new ResourcePresentationIntent("Aggregate", "GUIs/aggregate.json", 3);
        Map<String, Object> payload = Map.of("id", resource.id(), "name", "Aggregate");
        CanonicalPayload<Map<String, Object>> canonical = ResourcePayloadCodecs.json().canonicalize(payload);
        ResourceCreateRequest<Map<String, Object>> operation = new ResourceCreateRequest<>(resource, canonical, mutationId, presentation);
        ProtocolEnvelope<Map<String, Object>> request = envelope(resource, operation,
            ReSyncProtocolContract.GENERIC_RESOURCE_CONTRACT_VERSION);
        AggregateStorage storage = new AggregateStorage();

        Path database = directory.resolve("resource.db");
        try (SqliteProtocolResourceMutationAuthority authority = new SqliteProtocolResourceMutationAuthority(registry, SERVER,
            database, CoreGraphResourceAuthority.unavailable(), ProtocolResourceAuthorizer.serverGranted(),
            AuthorityEpoch.fixed(1L), storage)) {
            ProtocolEnvelopeDispatchResult first = mutate(authority, request);
            assertTrue(first.handled(), first.code() + ": " + first.message());
        }
        Map<String, Object> advancedMetadata = Map.of("serverId", SERVER.canonicalText(), "resources", List.of());
        CanonicalPayload<Map<String, Object>> advancedCanonical = ResourcePayloadCodecs.json().canonicalize(advancedMetadata);
        try (Connection connection = DriverManager.getConnection("jdbc:sqlite:" + database);
             PreparedStatement statement = connection.prepareStatement("""
                 UPDATE resource_mutation_state SET revision = ?, mutation_id = ?, payload_hash = ?, payload = ?
                 WHERE resource LIKE '%/project_metadata/%'
                 """)) {
            statement.setLong(1, 2L);
            statement.setString(2, UUID.randomUUID().toString());
            statement.setString(3, advancedCanonical.checksum().canonicalText());
            statement.setString(4, ResourcePayloadCodecs.json().canonicalInput(advancedCanonical.value()));
            assertEquals(1, statement.executeUpdate());
        }
        try (SqliteProtocolResourceMutationAuthority authority = new SqliteProtocolResourceMutationAuthority(registry, SERVER,
            database, CoreGraphResourceAuthority.unavailable(), ProtocolResourceAuthorizer.serverGranted(),
            AuthorityEpoch.fixed(1L), storage)) {
            ProtocolEnvelopeDispatchResult replay = mutate(authority, request);
            assertTrue(replay.handled(), replay.code() + ": " + replay.message());
            ProtocolBody.ResourceCreateResponse body = assertInstanceOf(ProtocolBody.ResourceCreateResponse.class,
                replay.response().body());
            ResourceCreateResult result = body.result();
            assertEquals(resource, result.resource().resource());
            assertEquals(mutationId, result.resource().mutationId());
            assertEquals(mutationId, result.projectMetadata().mutationId());
            assertEquals(1L, result.projectMetadata().revision());
            assertEquals(canonicalPresentation, result.presentation());
            assertEquals(1, storage.creates);
            ResourcePresentationIntent changedPresentation = new ResourcePresentationIntent("Changed",
                "Content/GUI/changed.json", 4);
            ProtocolEnvelopeDispatchResult conflict = mutate(authority, envelope(resource,
                new ResourceCreateRequest<>(resource, canonical, mutationId, changedPresentation),
                ReSyncProtocolContract.GENERIC_RESOURCE_CONTRACT_VERSION));
            assertTrue(conflict.handled());
            assertEquals(ProtocolEnvelope.Kind.CONFLICT, conflict.response().kind());
            assertEquals(1, storage.creates);
        }
    }

    @Test
    void aggregateCreateRetriesPublicationWithoutRepeatingStorage(@TempDir Path directory) {
        CountingAdapter adapter = new CountingAdapter(new ConcurrentHashMap<>());
        FlowResourceRegistry registry = registry(adapter);
        ServerResourceLocator resource = resource("publish-retry");
        UUID mutationId = UUID.randomUUID();
        ResourcePresentationIntent presentation = new ResourcePresentationIntent("Publish Retry",
            "Content/GUI/publish-retry.json", 1);
        CanonicalPayload<Map<String, Object>> canonical = ResourcePayloadCodecs.json().canonicalize(
            Map.of("id", resource.id(), "name", "Publish Retry"));
        ProtocolEnvelope<Map<String, Object>> request = envelope(resource,
            new ResourceCreateRequest<>(resource, canonical, mutationId, presentation),
            ReSyncProtocolContract.GENERIC_RESOURCE_CONTRACT_VERSION);
        AggregateStorage storage = new AggregateStorage();
        storage.publicationFailures = 1;

        try (SqliteProtocolResourceMutationAuthority authority = new SqliteProtocolResourceMutationAuthority(registry, SERVER,
            directory.resolve("resource.db"), CoreGraphResourceAuthority.unavailable(), ProtocolResourceAuthorizer.serverGranted(),
            AuthorityEpoch.fixed(1L), storage)) {
            ProtocolEnvelopeDispatchResult first = mutate(authority, request);
            ProtocolEnvelopeDispatchResult replay = mutate(authority, request);

            assertFalse(first.handled());
            assertEquals(ProtocolRejectionCode.RESOURCE_MUTATION_PENDING.legacyValue(), first.code());
            assertTrue(replay.handled());
            assertEquals(1, storage.creates);
            assertEquals(2, storage.publications);
        }
    }

    @Test
    void aggregateCreateRejectsUnsupportedLocatorBeforeReceiptAdmission(@TempDir Path directory) throws Exception {
        FlowResourceRegistry registry = registry(new CountingAdapter(new ConcurrentHashMap<>()));
        ServerResourceLocator resource = resource("unsupported-aggregate");
        UUID mutationId = UUID.randomUUID();
        CanonicalPayload<Map<String, Object>> canonical = ResourcePayloadCodecs.json().canonicalize(
            Map.of("id", resource.id(), "name", "Unsupported"));
        ProtocolEnvelope<Map<String, Object>> request = envelope(resource, new ResourceCreateRequest<>(resource, canonical,
            mutationId, new ResourcePresentationIntent("Unsupported", "Content/GUI/unsupported-aggregate.json", 1)),
            ReSyncProtocolContract.GENERIC_RESOURCE_CONTRACT_VERSION);
        AggregateStorage storage = new AggregateStorage();
        storage.locatorAvailable = false;
        Path database = directory.resolve("resource.db");

        try (SqliteProtocolResourceMutationAuthority authority = new SqliteProtocolResourceMutationAuthority(registry, SERVER,
            database, CoreGraphResourceAuthority.unavailable(), ProtocolResourceAuthorizer.serverGranted(),
            AuthorityEpoch.fixed(1L), storage)) {
            ProtocolEnvelopeDispatchResult result = mutate(authority, request);
            assertFalse(result.handled());
            assertEquals(ProtocolRejectionCode.RESOURCE_DURABILITY_UNAVAILABLE.legacyValue(), result.code());
            assertEquals(0, storage.creates);
        }
        try (Connection connection = DriverManager.getConnection("jdbc:sqlite:" + database);
             PreparedStatement statement = connection.prepareStatement(
                 "SELECT COUNT(*) FROM resource_mutation_receipt WHERE mutation_id = ?")) {
            statement.setString(1, mutationId.toString());
            try (var result = statement.executeQuery()) {
                assertTrue(result.next());
                assertEquals(0, result.getInt(1));
            }
        }
    }

    @Test
    void aggregateCreateRecoversCommittedStorageFromDurablePendingReceipt(@TempDir Path directory) throws Exception {
        FlowResourceRegistry registry = registry(new CountingAdapter(new ConcurrentHashMap<>()));
        ServerResourceLocator resource = resource("crash-window");
        UUID mutationId = UUID.randomUUID();
        CanonicalPayload<Map<String, Object>> canonical = ResourcePayloadCodecs.json().canonicalize(
            Map.of("id", resource.id(), "name", "Crash Window"));
        ResourcePresentationIntent presentation = new ResourcePresentationIntent("Crash Window",
            "Content/GUI/crash-window.json", 5);
        ResourcePresentationIntent canonicalPresentation = new ResourcePresentationIntent("Crash Window",
            "GUIs/crash-window.json", 5);
        ProtocolEnvelope<Map<String, Object>> request = envelope(resource,
            new ResourceCreateRequest<>(resource, canonical, mutationId, presentation),
            ReSyncProtocolContract.GENERIC_RESOURCE_CONTRACT_VERSION);
        AggregateStorage storage = new AggregateStorage();
        Path database = directory.resolve("resource.db");

        try (SqliteProtocolResourceMutationAuthority authority = new SqliteProtocolResourceMutationAuthority(registry, SERVER,
            database, CoreGraphResourceAuthority.unavailable(), ProtocolResourceAuthorizer.serverGranted(),
            AuthorityEpoch.fixed(1L), storage)) {
            try (Connection connection = DriverManager.getConnection("jdbc:sqlite:" + database);
                 Statement statement = connection.createStatement()) {
                statement.execute("""
                    CREATE TRIGGER fail_aggregate_authority_commit BEFORE INSERT ON resource_mutation_state
                    BEGIN SELECT RAISE(ABORT, 'simulated authority crash window'); END
                    """);
            }
            ProtocolEnvelopeDispatchResult pending = mutate(authority, request);
            assertFalse(pending.handled());
            assertEquals(ProtocolRejectionCode.RESOURCE_MUTATION_PENDING.legacyValue(), pending.code());
            assertEquals(1, storage.creates);
            assertEquals(0, storage.publications);
        }
        try (Connection connection = DriverManager.getConnection("jdbc:sqlite:" + database);
             Statement statement = connection.createStatement()) {
            statement.execute("DROP TRIGGER fail_aggregate_authority_commit");
        }
        try (SqliteProtocolResourceMutationAuthority authority = new SqliteProtocolResourceMutationAuthority(registry, SERVER,
            database, CoreGraphResourceAuthority.unavailable(), ProtocolResourceAuthorizer.serverGranted(),
            AuthorityEpoch.fixed(1L), storage)) {
            ProtocolEnvelopeDispatchResult replay = mutate(authority, request);
            assertTrue(replay.handled(), replay.code() + ": " + replay.message());
            ProtocolBody.ResourceCreateResponse body = assertInstanceOf(ProtocolBody.ResourceCreateResponse.class,
                replay.response().body());
            assertEquals(resource, body.result().resource().resource());
            assertEquals(mutationId, body.result().resource().mutationId());
            assertEquals(mutationId, body.result().projectMetadata().mutationId());
            assertEquals(storage.firstResult.primary().canonicalPayload(), body.result().resource().payload());
            assertEquals(ResourcePayloadCodecs.json().canonicalInput(storage.firstResult.projectMetadata().canonicalPayload()),
                ResourcePayloadCodecs.json().canonicalInput(jsonPayload(body.result().projectMetadata().payload())));
            assertEquals(canonicalPresentation, body.result().presentation());
            assertEquals(2, storage.creates);
            assertEquals(1, storage.publications);
            assertEquals(storage.firstResult, storage.lastResult);
        }
    }

    @Test
    void aggregateCreatePersistsDeterministicValidationRejectionAndDoesNotPoisonRestart(@TempDir Path directory) throws Exception {
        CountingAdapter adapter = new CountingAdapter(new ConcurrentHashMap<>());
        adapter.validationFailure = "Malformed aggregate payload";
        FlowResourceRegistry registry = registry(adapter);
        ServerResourceLocator resource = resource("malformed-aggregate");
        UUID mutationId = UUID.randomUUID();
        CanonicalPayload<Map<String, Object>> canonical = ResourcePayloadCodecs.json().canonicalize(
            Map.of("id", resource.id(), "name", "Malformed"));
        ProtocolEnvelope<Map<String, Object>> request = envelope(resource,
            new ResourceCreateRequest<>(resource, canonical, mutationId, new ResourcePresentationIntent("Malformed",
                "Content/GUI/malformed-aggregate.json", 1)),
            ReSyncProtocolContract.GENERIC_RESOURCE_CONTRACT_VERSION);
        AggregateStorage storage = new AggregateStorage();
        Path database = directory.resolve("resource.db");
        FlowResourceKey key = new FlowResourceKey(TYPE.id().value(), resource.id());
        FlowResourceKey metadataKey = new FlowResourceKey(ReSyncResourceCatalog.PROJECT_METADATA, SERVER.canonicalText());

        try (SqliteProtocolResourceMutationAuthority authority = new SqliteProtocolResourceMutationAuthority(registry, SERVER,
            database, CoreGraphResourceAuthority.unavailable(), ProtocolResourceAuthorizer.serverGranted(),
            AuthorityEpoch.fixed(1L), storage)) {
            ProtocolEnvelopeDispatchResult rejected = mutate(authority, request);

            assertFalse(rejected.handled());
            assertEquals(422, rejected.transportCode());
            assertEquals(ProtocolRejectionCode.RESOURCE_OPERATION_FAILED.wireValue(), rejected.code());
            assertEquals("Malformed aggregate payload", rejected.message());
            assertFalse(registry.mutationAdmission().isAdmitted(key));
            assertFalse(registry.mutationAdmission().isAdmitted(metadataKey));
            assertEquals(0, storage.creates);
        }

        try (Connection connection = DriverManager.getConnection("jdbc:sqlite:" + database);
             PreparedStatement statement = connection.prepareStatement(
                 "SELECT status, error_code, error_message FROM resource_mutation_receipt WHERE mutation_id = ?")) {
            statement.setString(1, mutationId.toString());
            try (var result = statement.executeQuery()) {
                assertTrue(result.next());
                assertEquals("REJECTED", result.getString(1));
                assertEquals(ProtocolRejectionCode.RESOURCE_OPERATION_FAILED.legacyValue(), result.getString(2));
                assertEquals("Malformed aggregate payload", result.getString(3));
            }
        }

        try (SqliteProtocolResourceMutationAuthority authority = new SqliteProtocolResourceMutationAuthority(registry, SERVER,
            database, CoreGraphResourceAuthority.unavailable(), ProtocolResourceAuthorizer.serverGranted(),
            AuthorityEpoch.fixed(1L), storage)) {
            ProtocolEnvelopeDispatchResult replay = mutate(authority, request);

            assertFalse(replay.handled());
            assertEquals(422, replay.transportCode());
            assertEquals("Malformed aggregate payload", replay.message());
            assertTrue(authority.durable());
            assertFalse(registry.mutationAdmission().isAdmitted(key));
            assertFalse(registry.mutationAdmission().isAdmitted(metadataKey));
            assertEquals(0, storage.creates);
        }
    }

    @Test
    void aggregateCreateTerminalizesExistingMalformedPendingDuringRestart(@TempDir Path directory) throws Exception {
        CountingAdapter adapter = new CountingAdapter(new ConcurrentHashMap<>());
        FlowResourceRegistry registry = registry(adapter);
        ServerResourceLocator resource = resource("malformed-pending");
        UUID mutationId = UUID.randomUUID();
        CanonicalPayload<Map<String, Object>> canonical = ResourcePayloadCodecs.json().canonicalize(
            Map.of("id", resource.id(), "name", "Malformed Pending"));
        ProtocolEnvelope<Map<String, Object>> request = envelope(resource,
            new ResourceCreateRequest<>(resource, canonical, mutationId, new ResourcePresentationIntent("Malformed Pending",
                "Content/GUI/malformed-pending.json", 1)),
            ReSyncProtocolContract.GENERIC_RESOURCE_CONTRACT_VERSION);
        AggregateStorage storage = new AggregateStorage();
        storage.failuresBeforeCreate = 1;
        Path database = directory.resolve("resource.db");

        try (SqliteProtocolResourceMutationAuthority authority = new SqliteProtocolResourceMutationAuthority(registry, SERVER,
            database, CoreGraphResourceAuthority.unavailable(), ProtocolResourceAuthorizer.serverGranted(),
            AuthorityEpoch.fixed(1L), storage)) {
            ProtocolEnvelopeDispatchResult pending = mutate(authority, request);

            assertFalse(pending.handled());
            assertEquals(ProtocolRejectionCode.RESOURCE_MUTATION_PENDING.legacyValue(), pending.code());
        }

        adapter.validationFailure = "Malformed pending aggregate payload";
        try (SqliteProtocolResourceMutationAuthority authority = new SqliteProtocolResourceMutationAuthority(registry, SERVER,
            database, CoreGraphResourceAuthority.unavailable(), ProtocolResourceAuthorizer.serverGranted(),
            AuthorityEpoch.fixed(1L), storage)) {
            ProtocolEnvelopeDispatchResult replay = mutate(authority, request);

            assertTrue(authority.durable());
            assertFalse(replay.handled());
            assertEquals(422, replay.transportCode());
            assertEquals("Malformed pending aggregate payload", replay.message());
            assertEquals(1, storage.creates);
            assertEquals(0, storage.publications);
        }

        try (Connection connection = DriverManager.getConnection("jdbc:sqlite:" + database);
             PreparedStatement statement = connection.prepareStatement(
                 "SELECT status, error_code, error_message FROM resource_mutation_receipt WHERE mutation_id = ?")) {
            statement.setString(1, mutationId.toString());
            try (var result = statement.executeQuery()) {
                assertTrue(result.next());
                assertEquals("REJECTED", result.getString(1));
                assertEquals(ProtocolRejectionCode.RESOURCE_OPERATION_FAILED.legacyValue(), result.getString(2));
                assertEquals("Malformed pending aggregate payload", result.getString(3));
            }
        }
    }

    @Test
    void aggregateCreateRecoversPendingPreCommitFailureAfterRestart(@TempDir Path directory) {
        CountingAdapter adapter = new CountingAdapter(new ConcurrentHashMap<>());
        FlowResourceRegistry registry = registry(adapter);
        ServerResourceLocator resource = resource("pre-commit-recovery");
        UUID mutationId = UUID.randomUUID();
        CanonicalPayload<Map<String, Object>> canonical = ResourcePayloadCodecs.json().canonicalize(
            Map.of("id", resource.id(), "name", "Recovered"));
        ProtocolEnvelope<Map<String, Object>> request = envelope(resource,
            new ResourceCreateRequest<>(resource, canonical, mutationId, new ResourcePresentationIntent("Recovered",
                "Content/GUI/pre-commit-recovery.json", 1)),
            ReSyncProtocolContract.GENERIC_RESOURCE_CONTRACT_VERSION);
        AggregateStorage storage = new AggregateStorage();
        storage.failuresBeforeCreate = 1;
        Path database = directory.resolve("resource.db");

        try (SqliteProtocolResourceMutationAuthority authority = new SqliteProtocolResourceMutationAuthority(registry, SERVER,
            database, CoreGraphResourceAuthority.unavailable(), ProtocolResourceAuthorizer.serverGranted(),
            AuthorityEpoch.fixed(1L), storage)) {
            ProtocolEnvelopeDispatchResult pending = mutate(authority, request);

            assertFalse(pending.handled());
            assertEquals(ProtocolRejectionCode.RESOURCE_MUTATION_PENDING.legacyValue(), pending.code());
            assertEquals(1, storage.creates);
        }

        try (SqliteProtocolResourceMutationAuthority authority = new SqliteProtocolResourceMutationAuthority(registry, SERVER,
            database, CoreGraphResourceAuthority.unavailable(), ProtocolResourceAuthorizer.serverGranted(),
            AuthorityEpoch.fixed(1L), storage)) {
            ProtocolEnvelopeDispatchResult replay = mutate(authority, request);

            assertTrue(replay.handled(), replay.code() + ": " + replay.message());
            assertTrue(authority.durable());
            assertEquals(2, storage.creates);
            assertEquals(1, storage.publications);
        }
    }

    @Test
    void rejectedStorageRecoveryAllowsACorrectedCreate(@TempDir Path directory) throws Exception {
        CountingAdapter adapter = new CountingAdapter(new ConcurrentHashMap<>());
        FlowResourceRegistry registry = registry(adapter);
        ServerResourceLocator resource = resource("storage-validation");
        UUID mutationId = UUID.randomUUID();
        CanonicalPayload<Map<String, Object>> canonical = ResourcePayloadCodecs.json().canonicalize(
            Map.of("id", resource.id(), "name", "Storage Validation"));
        ResourcePresentationIntent presentation = new ResourcePresentationIntent("Storage Validation",
            "Content/GUI/storage-validation.json", 1);
        ProtocolEnvelope<Map<String, Object>> request = envelope(resource,
            new ResourceCreateRequest<>(resource, canonical, mutationId, presentation),
            ReSyncProtocolContract.GENERIC_RESOURCE_CONTRACT_VERSION);
        AggregateStorage storage = new AggregateStorage();
        storage.failuresBeforeCreate = 1;
        Path database = directory.resolve("resource.db");
        try (SqliteProtocolResourceMutationAuthority authority = new SqliteProtocolResourceMutationAuthority(registry, SERVER,
            database, CoreGraphResourceAuthority.unavailable(), ProtocolResourceAuthorizer.serverGranted(),
            AuthorityEpoch.fixed(1L), storage)) {
            assertEquals(ProtocolRejectionCode.RESOURCE_MUTATION_PENDING.legacyValue(), mutate(authority, request).code());
        }

        storage.rejectionBeforeCreate = "Invalid Resource Hook";
        try (SqliteProtocolResourceMutationAuthority authority = new SqliteProtocolResourceMutationAuthority(registry, SERVER,
            database, CoreGraphResourceAuthority.unavailable(), ProtocolResourceAuthorizer.serverGranted(),
            AuthorityEpoch.fixed(1L), storage)) {
            assertTrue(authority.durable());
            assertEquals("Invalid Resource Hook", mutate(authority, request).message());
            storage.rejectionBeforeCreate = null;
            ProtocolEnvelopeDispatchResult corrected = mutate(authority, envelope(resource,
                new ResourceCreateRequest<>(resource, canonical, UUID.randomUUID(), presentation),
                ReSyncProtocolContract.GENERIC_RESOURCE_CONTRACT_VERSION));
            assertTrue(corrected.handled(), corrected.code() + ": " + corrected.message());
            assertTrue(authority.durable());
        }
        try (Connection connection = DriverManager.getConnection("jdbc:sqlite:" + database);
             PreparedStatement statement = connection.prepareStatement("SELECT COUNT(*) FROM resource_mutation_receipt WHERE status = 'PENDING'");
             var result = statement.executeQuery()) {
            assertTrue(result.next());
            assertEquals(0, result.getInt(1));
        }
    }

    @Test
    void customContentAggregateCreateMigratesLegacyFolderPresentationBeforeRecovery(@TempDir Path directory) throws Exception {
        CountingAdapter adapter = new CountingAdapter(new ConcurrentHashMap<>(), ReSyncResourceCatalog.CUSTOM_CONTENT);
        FlowResourceRegistry registry = registry(adapter);
        ServerResourceLocator resource = new ServerResourceLocator(SERVER, CUSTOM_TYPE, "legacy-item");
        UUID mutationId = UUID.randomUUID();
        CanonicalPayload<Map<String, Object>> canonical = ResourcePayloadCodecs.json().canonicalize(
            Map.of("id", resource.id(), "name", "Legacy Item"));
        ResourcePresentationIntent legacyPresentation = new ResourcePresentationIntent("Legacy Item", "Content/Items", 4);
        ResourcePresentationIntent canonicalPresentation = new ResourcePresentationIntent("Legacy Item",
            "Content/Items/legacy-item.json", 4);
        ProtocolEnvelope<Map<String, Object>> request = envelope(resource,
            new ResourceCreateRequest<>(resource, canonical, mutationId, legacyPresentation),
            ReSyncProtocolContract.GENERIC_RESOURCE_CONTRACT_VERSION);
        AggregateStorage storage = new AggregateStorage();
        storage.failuresBeforeCreate = 1;
        Path database = directory.resolve("resource.db");

        try (SqliteProtocolResourceMutationAuthority authority = new SqliteProtocolResourceMutationAuthority(registry,
            SERVER, database, CoreGraphResourceAuthority.unavailable(), ProtocolResourceAuthorizer.serverGranted(),
            AuthorityEpoch.fixed(1L), storage)) {
            ProtocolEnvelopeDispatchResult pending = mutate(authority, request);

            assertFalse(pending.handled());
            assertEquals(ProtocolRejectionCode.RESOURCE_MUTATION_PENDING.legacyValue(), pending.code());
            assertEquals(canonicalPresentation, storage.lastPresentation);
        }

        try (Connection connection = DriverManager.getConnection("jdbc:sqlite:" + database);
             PreparedStatement statement = connection.prepareStatement("""
                 UPDATE resource_create_aggregate_receipt SET resource_path = ? WHERE mutation_id = ?
                 """)) {
            statement.setString(1, legacyPresentation.path());
            statement.setString(2, mutationId.toString());
            assertEquals(1, statement.executeUpdate());
        }

        try (SqliteProtocolResourceMutationAuthority authority = new SqliteProtocolResourceMutationAuthority(registry,
            SERVER, database, CoreGraphResourceAuthority.unavailable(), ProtocolResourceAuthorizer.serverGranted(),
            AuthorityEpoch.fixed(1L), storage)) {
            assertTrue(authority.durable());
            ProtocolEnvelopeDispatchResult replay = mutate(authority, request);

            assertTrue(replay.handled(), replay.code() + ": " + replay.message());
            ResourceCreateResult result = assertInstanceOf(ProtocolBody.ResourceCreateResponse.class,
                replay.response().body()).result();
            assertEquals(canonicalPresentation, result.presentation());
            assertEquals(canonicalPresentation, storage.lastPresentation);
            assertEquals(2, storage.creates);
            assertEquals(1, storage.publications);
        }

        try (Connection connection = DriverManager.getConnection("jdbc:sqlite:" + database);
             PreparedStatement statement = connection.prepareStatement("""
                 SELECT r.status, a.resource_path FROM resource_mutation_receipt r
                 JOIN resource_create_aggregate_receipt a ON a.mutation_id = r.mutation_id
                 WHERE r.mutation_id = ?
                 """)) {
            statement.setString(1, mutationId.toString());
            try (var result = statement.executeQuery()) {
                assertTrue(result.next());
                assertEquals("APPLIED", result.getString(1));
                assertEquals(canonicalPresentation.path(), result.getString(2));
            }
        }
    }

    @Test
    void aggregateCreateCanonicalizesFunctionPresentationToItsTypedRoot() {
        ServerResourceLocator misplaced = new ServerResourceLocator(SERVER, FUNCTION_TYPE, "dasha");
        ResourcePresentationIntent canonical = SqliteProtocolResourceMutationAuthority.canonicalCreatePresentation(
            misplaced, new ResourcePresentationIntent("Dasha", "Blueprints/Flows/dasha.json", 2));

        assertEquals("Blueprints/Functions/dasha.json", canonical.path());

        ServerResourceLocator nested = new ServerResourceLocator(SERVER, FUNCTION_TYPE, "nested");
        ResourcePresentationIntent nestedCanonical = SqliteProtocolResourceMutationAuthority.canonicalCreatePresentation(
            nested, new ResourcePresentationIntent("Nested", "Blueprints/Functions/Shared/old.json", 3));

        assertEquals("Blueprints/Functions/Shared/nested.json", nestedCanonical.path());
    }

    @Test
    void aggregateCreateFencesFreshMutationIdsAndTerminalizesRecoveredTargetConflicts(@TempDir Path directory) throws Exception {
        Map<String, JsonObject> values = new ConcurrentHashMap<>();
        CountingAdapter adapter = new CountingAdapter(values);
        FlowResourceRegistry registry = registry(adapter);
        ServerResourceLocator resource = resource("pending-target");
        CanonicalPayload<Map<String, Object>> requested = ResourcePayloadCodecs.json().canonicalize(
            Map.of("id", resource.id(), "name", "Requested"));
        UUID firstMutation = UUID.randomUUID();
        UUID secondMutation = UUID.randomUUID();
        ResourcePresentationIntent presentation = new ResourcePresentationIntent("Requested",
            "Content/GUI/pending-target.json", 1);
        ProtocolEnvelope<Map<String, Object>> first = envelope(resource,
            new ResourceCreateRequest<>(resource, requested, firstMutation, presentation),
            ReSyncProtocolContract.GENERIC_RESOURCE_CONTRACT_VERSION);
        ProtocolEnvelope<Map<String, Object>> second = envelope(resource,
            new ResourceCreateRequest<>(resource, requested, secondMutation, presentation),
            ReSyncProtocolContract.GENERIC_RESOURCE_CONTRACT_VERSION);
        AggregateStorage storage = new AggregateStorage();
        storage.failuresBeforeCreate = 1;
        Path database = directory.resolve("resource.db");

        try (SqliteProtocolResourceMutationAuthority authority = new SqliteProtocolResourceMutationAuthority(registry,
            SERVER, database, CoreGraphResourceAuthority.unavailable(), ProtocolResourceAuthorizer.serverGranted(),
            AuthorityEpoch.fixed(1L), storage)) {
            ProtocolEnvelopeDispatchResult pending = mutate(authority, first);
            ProtocolEnvelopeDispatchResult fenced = mutate(authority, second);

            assertFalse(pending.handled());
            assertEquals(ProtocolRejectionCode.RESOURCE_MUTATION_PENDING.legacyValue(), pending.code());
            assertFalse(fenced.handled());
            assertEquals(ProtocolRejectionCode.RESOURCE_MUTATION_PENDING.legacyValue(), fenced.code());
            assertEquals(1, storage.creates);
        }

        JsonObject authoritative = new JsonObject();
        authoritative.addProperty("id", resource.id());
        authoritative.addProperty("name", "Authoritative");
        UUID authoritativeMutation = UUID.randomUUID();
        adapter.seed(authoritative, authoritativeMutation, 1L);
        CanonicalPayload<Map<String, Object>> canonical = ResourcePayloadCodecs.json().canonicalize(
            new Gson().fromJson(authoritative, Map.class));
        try (Connection connection = DriverManager.getConnection("jdbc:sqlite:" + database);
             PreparedStatement count = connection.prepareStatement(
                 "SELECT COUNT(*) FROM resource_mutation_receipt WHERE status = 'PENDING'");
             PreparedStatement state = connection.prepareStatement("""
                 INSERT INTO resource_mutation_state(resource, revision, mutation_id, payload_hash, deleted, payload,
                     activation_state, updated_at)
                 VALUES(?, ?, ?, ?, 0, ?, ?, ?)
                 """)) {
            try (var result = count.executeQuery()) {
                assertTrue(result.next());
                assertEquals(1, result.getInt(1));
            }
            state.setString(1, resource.canonicalText());
            state.setLong(2, 1L);
            state.setString(3, authoritativeMutation.toString());
            state.setString(4, canonical.checksum().canonicalText());
            state.setString(5, ResourcePayloadCodecs.json().canonicalInput(canonical.value()));
            state.setString(6, ResourceActivationState.ACTIVE.wireName());
            state.setLong(7, System.currentTimeMillis());
            state.executeUpdate();
        }

        try (SqliteProtocolResourceMutationAuthority authority = new SqliteProtocolResourceMutationAuthority(registry,
            SERVER, database, CoreGraphResourceAuthority.unavailable(), ProtocolResourceAuthorizer.serverGranted(),
            AuthorityEpoch.fixed(1L), storage)) {
            assertTrue(authority.durable());
            ProtocolEnvelopeDispatchResult replay = mutate(authority, first);
            assertTrue(replay.handled());
            assertEquals(ProtocolEnvelope.Kind.CONFLICT, replay.response().kind());
            assertEquals(1, storage.creates);
            assertEquals(0, storage.publications);
        }

        try (Connection connection = DriverManager.getConnection("jdbc:sqlite:" + database);
             PreparedStatement statement = connection.prepareStatement(
                 "SELECT status, result_mutation_id FROM resource_mutation_receipt WHERE mutation_id = ?")) {
            statement.setString(1, firstMutation.toString());
            try (var result = statement.executeQuery()) {
                assertTrue(result.next());
                assertEquals("CONFLICT", result.getString(1));
                assertEquals(authoritativeMutation.toString(), result.getString(2));
            }
        }
    }

    @Test
    void replaysCommittedMutationAfterAuthorityRestart(@TempDir Path directory) {
        Map<String, JsonObject> values = new ConcurrentHashMap<>();
        CountingAdapter adapter = new CountingAdapter(values);
        FlowResourceRegistry registry = registry(adapter);
        ServerResourceLocator resource = resource("restart");
        ProtocolEnvelope<Map<String, Object>> request = create(resource, "Draft");
        ProtocolEnvelopeDispatchResult first;
        try (SqliteProtocolResourceMutationAuthority authority = authority(registry, directory)) {
            first = mutate(authority, request);
            assertTrue(first.handled(), first.code() + ": " + first.message());
        }
        ProtocolEnvelopeDispatchResult replay;
        try (SqliteProtocolResourceMutationAuthority authority = authority(registry, directory)) {
            replay = mutate(authority, request);
        }
        assertTrue(replay.handled());
        assertEquals(ProtocolEnvelope.Kind.ACK, replay.response().kind());
        ProtocolBody.ResourceDocumentResponse body = assertInstanceOf(ProtocolBody.ResourceDocumentResponse.class, replay.response().body());
        assertEquals(request.mutationId(), body.document().mutationId());
        assertEquals(1, body.document().revision());
        assertEquals(1, adapter.saves);
    }

    @Test
    void replaysCommittedMutationBeforeCompetingKeyAdmission(@TempDir Path directory) {
        Map<String, JsonObject> values = new ConcurrentHashMap<>();
        CountingAdapter adapter = new CountingAdapter(values);
        FlowResourceRegistry registry = registry(adapter);
        ServerResourceLocator resource = resource("terminal-replay-admission");
        ProtocolEnvelope<Map<String, Object>> request = create(resource, "Draft");
        FlowResourceKey key = new FlowResourceKey(TYPE.id().value(), resource.id());

        try (SqliteProtocolResourceMutationAuthority authority = authority(registry, directory)) {
            ProtocolEnvelopeDispatchResult first = mutate(authority, request);
            assertTrue(first.handled(), first.code() + ": " + first.message());
            FlowResourceMutationLease held = registry.mutationAdmission()
                .tryAcquire(List.of(key), "competing-terminal-replay", FlowResourceMutationAdmission.AdmissionKind.NEW)
                .orElseThrow();
            try {
                ProtocolEnvelopeDispatchResult replay = mutate(authority, request);
                assertTrue(replay.handled(), replay.code() + ": " + replay.message());
                assertEquals(ProtocolEnvelope.Kind.ACK, replay.response().kind());
                ProtocolBody.ResourceDocumentResponse body = assertInstanceOf(ProtocolBody.ResourceDocumentResponse.class,
                    replay.response().body());
                assertEquals(request.mutationId(), body.document().mutationId());
                assertEquals(1, body.document().revision());
            } finally {
                held.close();
            }
        }
        assertEquals(1, adapter.saves);
    }

    @Test
    void rejectsFingerprintConflictBeforeCompetingKeyAdmission(@TempDir Path directory) {
        Map<String, JsonObject> values = new ConcurrentHashMap<>();
        CountingAdapter adapter = new CountingAdapter(values);
        FlowResourceRegistry registry = registry(adapter);
        ServerResourceLocator resource = resource("fingerprint-conflict-admission");
        ProtocolEnvelope<Map<String, Object>> first = create(resource, "First");
        ProtocolEnvelope<Map<String, Object>> conflicting = create(resource, "Second", first.mutationId());
        FlowResourceKey key = new FlowResourceKey(TYPE.id().value(), resource.id());

        try (SqliteProtocolResourceMutationAuthority authority = authority(registry, directory)) {
            assertTrue(mutate(authority, first).handled());
            FlowResourceMutationLease held = registry.mutationAdmission()
                .tryAcquire(List.of(key), "competing-fingerprint-conflict", FlowResourceMutationAdmission.AdmissionKind.NEW)
                .orElseThrow();
            try {
                ProtocolEnvelopeDispatchResult result = mutate(authority, conflicting);
                assertTrue(result.handled(), result.code() + ": " + result.message());
                assertEquals(ProtocolEnvelope.Kind.CONFLICT, result.response().kind());
                assertTrue(registry.mutationAdmission().isAdmitted(key));
            } finally {
                held.close();
            }
        }
        assertEquals(1, adapter.saves);
    }

    @Test
    void generatesCanonicalNullCurrentConflictForMismatchedMutationOnMissingResource(@TempDir Path directory) {
        Map<String, JsonObject> values = new ConcurrentHashMap<>();
        CountingAdapter adapter = new CountingAdapter(values);
        FlowResourceRegistry registry = registry(adapter);
        ServerResourceLocator resource = resource("missing-fingerprint-conflict");
        ProtocolEnvelope<Map<String, Object>> first = delete(resource, 0);
        ProtocolEnvelope<Map<String, Object>> conflicting = envelope(resource,
            new ResourceDeleteRequest(resource, 1, first.mutationId()));

        try (SqliteProtocolResourceMutationAuthority authority = authority(registry, directory)) {
            ProtocolEnvelopeDispatchResult missing = mutate(authority, first);
            assertFalse(missing.handled());
            assertEquals("RESOURCE_NOT_FOUND", missing.code());

            ProtocolEnvelopeDispatchResult result = mutate(authority, conflicting);
            assertTrue(result.handled(), result.code() + ": " + result.message());
            ProtocolEnvelope<Map<String, Object>> response = result.response();
            assertEquals(ProtocolEnvelope.Kind.CONFLICT, response.kind());
            assertEquals(conflicting.requestId(), response.requestId());
            assertEquals(conflicting.correlationId(), response.correlationId());
            assertEquals(conflicting.traceId(), response.traceId());
            assertEquals(resource, response.resource());
            assertEquals(0, response.revision());
            assertNull(response.mutationId());
            assertNull(response.payloadHash());
            assertFalse(response.deleted());

            ProtocolBody.ConflictResponse body = assertInstanceOf(ProtocolBody.ConflictResponse.class, response.body());
            assertEquals(resource, body.requestedResource());
            assertNull(body.current());

            ProtocolEnvelopeCodec<Map<String, Object>> codec = new ProtocolEnvelopeCodec<>(ResourcePayloadCodecs.json());
            assertEquals(response, codec.decodeText(codec.encodeText(response)));
        }
    }

    @Test
    void activatesDurablyWithNoOpReplayAndTargetConflict(@TempDir Path directory) throws Exception {
        Map<String, JsonObject> values = new ConcurrentHashMap<>();
        CountingAdapter adapter = new CountingAdapter(values);
        FlowResourceRegistry registry = registry(adapter);
        ServerResourceLocator resource = resource("activation");
        ProtocolEnvelope<Map<String, Object>> create = create(resource, "Draft");
        UUID activationMutation = UUID.randomUUID();
        ProtocolEnvelope<Map<String, Object>> deactivate = activate(resource, 1, ResourceActivationState.INACTIVE, activationMutation);

        try (SqliteProtocolResourceMutationAuthority authority = authority(registry, directory)) {
            assertTrue(mutate(authority, create).handled());
            ProtocolEnvelopeDispatchResult deactivated = mutate(authority, deactivate);
            assertTrue(deactivated.handled(), deactivated.code() + ": " + deactivated.message());
            ProtocolBody.ResourceDocumentResponse deactivatedBody = assertInstanceOf(ProtocolBody.ResourceDocumentResponse.class,
                deactivated.response().body());
            assertEquals(2, deactivatedBody.document().revision());
            assertEquals(ResourceActivationState.INACTIVE, deactivatedBody.document().activationState());
            assertEquals(false, ((Map<?, ?>) deactivatedBody.document().payload()).get("enabled"));

            ProtocolEnvelopeDispatchResult noOp = mutate(authority,
                activate(resource, 2, ResourceActivationState.INACTIVE, UUID.randomUUID()));
            ProtocolBody.ResourceDocumentResponse noOpBody = assertInstanceOf(ProtocolBody.ResourceDocumentResponse.class, noOp.response().body());
            assertEquals(2, noOpBody.document().revision());
            assertEquals(ResourceActivationState.INACTIVE, noOpBody.document().activationState());

            ProtocolEnvelopeDispatchResult targetConflict = mutate(authority,
                activate(resource, 2, ResourceActivationState.ACTIVE, activationMutation));
            assertTrue(targetConflict.handled());
            assertEquals(ProtocolEnvelope.Kind.CONFLICT, targetConflict.response().kind());
            ProtocolBody.ConflictResponse conflict = assertInstanceOf(ProtocolBody.ConflictResponse.class, targetConflict.response().body());
            assertEquals(2, conflict.current().revision());
            assertEquals(ResourceActivationState.INACTIVE, conflict.current().activationState());
            assertEquals(2, adapter.saves);
        }

        try (Connection connection = DriverManager.getConnection("jdbc:sqlite:" + directory.resolve("resource.db"));
             PreparedStatement state = connection.prepareStatement("SELECT activation_state FROM resource_mutation_state WHERE resource = ?");
             PreparedStatement receipt = connection.prepareStatement("SELECT target_activation_state, result_activation_state FROM resource_mutation_receipt WHERE mutation_id = ?")) {
            state.setString(1, resource.canonicalText());
            try (var result = state.executeQuery()) {
                assertTrue(result.next());
                assertEquals(ResourceActivationState.INACTIVE.wireName(), result.getString(1));
            }
            receipt.setString(1, activationMutation.toString());
            try (var result = receipt.executeQuery()) {
                assertTrue(result.next());
                assertEquals(ResourceActivationState.INACTIVE.wireName(), result.getString(1));
                assertEquals(ResourceActivationState.INACTIVE.wireName(), result.getString(2));
            }
        }

        try (SqliteProtocolResourceMutationAuthority authority = authority(registry, directory)) {
            ProtocolEnvelopeDispatchResult replay = mutate(authority, deactivate);
            assertTrue(replay.handled());
            ProtocolBody.ResourceDocumentResponse body = assertInstanceOf(ProtocolBody.ResourceDocumentResponse.class, replay.response().body());
            assertEquals(2, body.document().revision());
            assertEquals(ResourceActivationState.INACTIVE, body.document().activationState());
        }
        assertEquals(2, adapter.saves);
    }

    @Test
    void savesWithTheExactRevisionAndMutationIdentity(@TempDir Path directory) {
        Map<String, JsonObject> values = new ConcurrentHashMap<>();
        CountingAdapter adapter = new CountingAdapter(values);
        FlowResourceRegistry registry = registry(adapter);
        ServerResourceLocator resource = resource("exact-save");

        try (SqliteProtocolResourceMutationAuthority authority = authority(registry, directory)) {
            assertTrue(mutate(authority, create(resource, "Draft")).handled());
            ProtocolEnvelopeDispatchResult saved = mutate(authority, save(resource, 1, "Updated"));
            assertTrue(saved.handled(), saved.code() + ": " + saved.message());
            ProtocolBody.ResourceDocumentResponse body = assertInstanceOf(ProtocolBody.ResourceDocumentResponse.class, saved.response().body());
            assertEquals(2, body.document().revision());
            assertEquals("Updated", ((Map<?, ?>) body.document().payload()).get("name"));
        }
        assertEquals(2, adapter.saves);
    }

    @Test
    void recoversACommittedSaveWithServerPreservedDefaults(@TempDir Path directory) {
        Map<String, JsonObject> values = new ConcurrentHashMap<>();
        CountingAdapter adapter = new CountingAdapter(values);
        FlowResourceRegistry registry = registry(adapter);
        ServerResourceLocator resource = resource("normalized-save");
        ProtocolEnvelope<Map<String, Object>> save;

        try (SqliteProtocolResourceMutationAuthority authority = authority(registry, directory)) {
            assertTrue(mutate(authority, create(resource, "Draft")).handled());
            adapter.addServerDefault = true;
            save = save(resource, 1, "Updated");
            ProtocolEnvelopeDispatchResult pending = mutate(authority, save);
            assertFalse(pending.handled());
            assertEquals("RESOURCE_MUTATION_PENDING", pending.code());
        }

        try (SqliteProtocolResourceMutationAuthority authority = authority(registry, directory)) {
            assertDoesNotThrow(authority::healthCheckPersistence);
            assertTrue(authority.durable());
            ResourceDocument<Map<String, Object>> recovered = authority.load(resource);
            assertEquals(2L, recovered.revision());
            assertEquals(save.mutationId(), recovered.mutationId());
            assertEquals("Updated", recovered.payload().get("name"));
            assertEquals(true, recovered.payload().get("serverDefault"));
            ProtocolEnvelopeDispatchResult replay = mutate(authority, save);
            assertTrue(replay.handled(), replay.code() + ": " + replay.message());
        }
        assertEquals(2, adapter.saves);
    }

    @Test
    void acceptsCanonicalGenericResourceVersionForEveryMutation(@TempDir Path directory) {
        Map<String, JsonObject> values = new ConcurrentHashMap<>();
        CountingAdapter adapter = new CountingAdapter(values);
        FlowResourceRegistry registry = registry(adapter);
        ServerResourceLocator source = resource("canonical-source");
        ServerResourceLocator target = resource("canonical-target");

        try (SqliteProtocolResourceMutationAuthority authority = authority(registry, directory)) {
            ProtocolEnvelopeDispatchResult created = mutate(authority,
                envelope(source, new ResourceCreateRequest<>(source,
                    ResourcePayloadCodecs.json().canonicalize(Map.of("id", source.id(), "name", "Draft")), UUID.randomUUID()),
                    ReSyncProtocolContract.GENERIC_RESOURCE_CONTRACT_VERSION));
            assertTrue(created.handled(), created.code() + ": " + created.message());
            assertInstanceOf(ProtocolBody.ResourceDocumentResponse.class, created.response().body());

            ProtocolEnvelopeDispatchResult saved = mutate(authority,
                envelope(source, new ResourceSaveRequest<>(source, 1,
                    ResourcePayloadCodecs.json().canonicalize(Map.of("id", source.id(), "name", "Updated")), UUID.randomUUID()),
                    ReSyncProtocolContract.GENERIC_RESOURCE_CONTRACT_VERSION));
            assertTrue(saved.handled(), saved.code() + ": " + saved.message());

            ProtocolEnvelopeDispatchResult duplicated = mutate(authority,
                envelope(target, new ResourceDuplicateRequest(source, target, 2, UUID.randomUUID()),
                    ReSyncProtocolContract.GENERIC_RESOURCE_CONTRACT_VERSION));
            assertTrue(duplicated.handled(), duplicated.code() + ": " + duplicated.message());

            ProtocolEnvelopeDispatchResult deleted = mutate(authority,
                envelope(target, new ResourceDeleteRequest(target, 1, UUID.randomUUID()),
                    ReSyncProtocolContract.GENERIC_RESOURCE_CONTRACT_VERSION));
            assertTrue(deleted.handled(), deleted.code() + ": " + deleted.message());
        }
        assertEquals(3, adapter.saves);
    }

    @Test
    void rejectsAdaptersWithoutAuthoritativeIdentityBeforeMutation(@TempDir Path directory) {
        Map<String, JsonObject> values = new ConcurrentHashMap<>();
        CountingAdapter adapter = new CountingAdapter(values);
        adapter.exactIdentity = false;
        FlowResourceRegistry registry = registry(adapter);
        ProtocolEnvelope<Map<String, Object>> request = create(resource("unsupported-exact"), "Draft");

        try (SqliteProtocolResourceMutationAuthority authority = authority(registry, directory)) {
            ProtocolEnvelopeDispatchResult result = mutate(authority, request);
            assertFalse(result.handled());
            assertEquals(503, result.transportCode());
            assertEquals("RESOURCE_DURABILITY_UNAVAILABLE", result.code());
            assertEquals(0, adapter.saves);
            assertTrue(values.isEmpty());
        }
    }

    @Test
    void blocksEveryDurableStampMismatchWithoutAcknowledgingMutation(@TempDir Path directory) {
        for (StampMismatch mismatch : StampMismatch.values()) {
            Map<String, JsonObject> values = new ConcurrentHashMap<>();
            CountingAdapter adapter = new CountingAdapter(values);
            adapter.stampMismatch = mismatch;
            FlowResourceRegistry registry = registry(adapter);
            ServerResourceLocator resource = resource("stamp-" + mismatch.name().toLowerCase());
            ProtocolEnvelope<Map<String, Object>> request = create(resource, "Draft");

            try (SqliteProtocolResourceMutationAuthority authority = authority(registry, directory.resolve(mismatch.name()))) {
                ProtocolEnvelopeDispatchResult first = mutate(authority, request);
                assertFalse(first.handled(), mismatch.name());
                assertEquals("RESOURCE_MUTATION_PENDING", first.code(), mismatch.name());
                ProtocolEnvelopeDispatchResult retry = mutate(authority, request);
                assertFalse(retry.handled(), mismatch.name());
                assertEquals("RESOURCE_RECOVERY_BLOCKED", retry.code(), mismatch.name());
                assertFalse(authority.durable(), mismatch.name());
            }
        }
    }

    @Test
    void duplicateAdmissionClaimsSourceAndTargetAtomically(@TempDir Path directory) {
        Map<String, JsonObject> values = new ConcurrentHashMap<>();
        CountingAdapter adapter = new CountingAdapter(values);
        FlowResourceRegistry registry = registry(adapter);
        ServerResourceLocator source = resource("admission-source");
        ServerResourceLocator target = resource("admission-target");
        JsonObject original = new JsonObject();
        original.addProperty("id", source.id());
        original.addProperty("name", "Draft");
        adapter.seed(original, UUID.randomUUID(), 1L);
        FlowResourceKey sourceKey = new FlowResourceKey(TYPE.id().value(), source.id());
        FlowResourceKey targetKey = new FlowResourceKey(TYPE.id().value(), target.id());
        FlowResourceMutationAdmission admission = registry.mutationAdmission();
        var held = admission.tryAcquire(List.of(sourceKey), "other-mutation", FlowResourceMutationAdmission.AdmissionKind.NEW).orElseThrow();
        ProtocolEnvelope<Map<String, Object>> request = envelope(target,
            new ResourceDuplicateRequest(source, target, 1, UUID.randomUUID()));

        try (SqliteProtocolResourceMutationAuthority authority = authority(registry, directory)) {
            ProtocolEnvelopeDispatchResult result = mutate(authority, request);
            assertFalse(result.handled());
            assertEquals("RESOURCE_MUTATION_PENDING", result.code());
            assertTrue(admission.isAdmitted(sourceKey));
            assertFalse(admission.isAdmitted(targetKey));
            assertEquals(0, adapter.saves);
        } finally {
            held.close();
        }
    }

    @Test
    void retainsTheMutationLeaseThroughAdapterStampVerification(@TempDir Path directory) {
        Map<String, JsonObject> values = new ConcurrentHashMap<>();
        CountingAdapter adapter = new CountingAdapter(values);
        FlowResourceRegistry registry = registry(adapter);
        ServerResourceLocator resource = resource("lease-through-commit");
        adapter.observedAdmission = registry.mutationAdmission();
        adapter.observedKey = new FlowResourceKey(TYPE.id().value(), resource.id());

        try (SqliteProtocolResourceMutationAuthority authority = authority(registry, directory)) {
            ProtocolEnvelopeDispatchResult result = mutate(authority, create(resource, "Draft"));
            assertTrue(result.handled(), result.code() + ": " + result.message());
            assertTrue(adapter.admittedDuringSave);
            assertTrue(adapter.admittedDuringStamp);
            assertFalse(registry.mutationAdmission().isAdmitted(adapter.observedKey));
        }
    }

    @Test
    void recoversAnUnappliedPendingMutationAfterAuthorityReopen(@TempDir Path directory) {
        Map<String, JsonObject> values = new ConcurrentHashMap<>();
        CountingAdapter adapter = new CountingAdapter(values);
        FlowResourceRegistry registry = registry(adapter);
        ServerResourceLocator resource = resource("reopen-pending");
        ProtocolEnvelope<Map<String, Object>> create = create(resource, "Draft");
        ProtocolEnvelope<Map<String, Object>> activate = activate(resource, 1, ResourceActivationState.INACTIVE, UUID.randomUUID());

        try (SqliteProtocolResourceMutationAuthority authority = authority(registry, directory)) {
            assertTrue(mutate(authority, create).handled());
            adapter.failuresBeforeSave = 1;
            ProtocolEnvelopeDispatchResult pending = mutate(authority, activate);
            assertFalse(pending.handled());
            assertEquals("RESOURCE_MUTATION_PENDING", pending.code());
        }

        try (SqliteProtocolResourceMutationAuthority authority = authority(registry, directory)) {
            ResourceDocument<Map<String, Object>> recovered = authority.load(resource);
            assertEquals(2, recovered.revision());
            assertEquals(ResourceActivationState.INACTIVE, recovered.activationState());
            assertEquals(false, ((Map<?, ?>) recovered.payload()).get("enabled"));
        }
        assertEquals(2, adapter.saves);
    }

    @Test
    void rejectsAnUnappliedPendingMutationWhenItsPayloadCanNoLongerBeDecoded(@TempDir Path directory) {
        Map<String, JsonObject> values = new ConcurrentHashMap<>();
        CountingAdapter adapter = new CountingAdapter(values);
        FlowResourceRegistry registry = registry(adapter);
        ServerResourceLocator resource = resource("invalid-pending");
        ProtocolEnvelope<Map<String, Object>> request = create(resource, "Invalid Pending");

        adapter.failuresBeforeSave = 1;
        try (SqliteProtocolResourceMutationAuthority authority = authority(registry, directory)) {
            ProtocolEnvelopeDispatchResult pending = mutate(authority, request);
            assertFalse(pending.handled());
            assertEquals("RESOURCE_MUTATION_PENDING", pending.code());
        }

        adapter.deserializationFailure = "The graph reported opaque unavailable";
        try (SqliteProtocolResourceMutationAuthority authority = authority(registry, directory)) {
            assertTrue(authority.durable());
            adapter.deserializationFailure = null;
            ProtocolEnvelopeDispatchResult rejected = mutate(authority, request);
            assertFalse(rejected.handled());
            assertEquals(422, rejected.transportCode());
            assertEquals("The graph reported opaque unavailable", rejected.message());
        }
        assertFalse(values.containsKey(resource.id()));
    }

    @Test
    void rejectsAnUnappliedCreateWhenRecoveryFailsBeforeTheExternalWrite(@TempDir Path directory) {
        Map<String, JsonObject> values = new ConcurrentHashMap<>();
        CountingAdapter adapter = new CountingAdapter(values);
        FlowResourceRegistry registry = registry(adapter);
        ServerResourceLocator resource = resource("recovery-prewrite-failure");
        ProtocolEnvelope<Map<String, Object>> request = create(resource, "Recovery Prewrite Failure");

        adapter.failuresBeforeSave = 1;
        try (SqliteProtocolResourceMutationAuthority authority = authority(registry, directory)) {
            ProtocolEnvelopeDispatchResult pending = mutate(authority, request);
            assertFalse(pending.handled());
            assertEquals("RESOURCE_MUTATION_PENDING", pending.code());
        }

        adapter.failuresBeforeGet = 1;
        try (SqliteProtocolResourceMutationAuthority authority = authority(registry, directory)) {
            assertTrue(authority.durable());
            ProtocolEnvelopeDispatchResult rejected = mutate(authority, request);
            assertFalse(rejected.handled());
            assertEquals(422, rejected.transportCode());
            assertEquals("Injected get failure", rejected.message());
        }
        assertFalse(values.containsKey(resource.id()));
    }

    @Test
    void activationRevisionConflictReturnsAuthoritativeState(@TempDir Path directory) {
        Map<String, JsonObject> values = new ConcurrentHashMap<>();
        CountingAdapter adapter = new CountingAdapter(values);
        FlowResourceRegistry registry = registry(adapter);
        ServerResourceLocator resource = resource("activation-conflict");
        try (SqliteProtocolResourceMutationAuthority authority = authority(registry, directory)) {
            assertTrue(mutate(authority, create(resource, "Draft")).handled());
            assertTrue(mutate(authority, activate(resource, 1, ResourceActivationState.INACTIVE, UUID.randomUUID())).handled());
            ProtocolEnvelopeDispatchResult stale = mutate(authority,
                activate(resource, 1, ResourceActivationState.ACTIVE, UUID.randomUUID()));
            assertTrue(stale.handled());
            assertEquals(ProtocolEnvelope.Kind.CONFLICT, stale.response().kind());
            ProtocolBody.ConflictResponse conflict = assertInstanceOf(ProtocolBody.ConflictResponse.class, stale.response().body());
            assertEquals(2, conflict.current().revision());
            assertEquals(ResourceActivationState.INACTIVE, conflict.current().activationState());
            assertEquals(false, ((Map<?, ?>) conflict.current().payload()).get("enabled"));
        }
    }

    @Test
    void retriesPendingActivationInProcess(@TempDir Path directory) {
        Map<String, JsonObject> values = new ConcurrentHashMap<>();
        CountingAdapter adapter = new CountingAdapter(values);
        FlowResourceRegistry registry = registry(adapter);
        ServerResourceLocator resource = resource("activation-pending-retry");
        ProtocolEnvelope<Map<String, Object>> create = create(resource, "Draft");
        ProtocolEnvelope<Map<String, Object>> activate = activate(resource, 1, ResourceActivationState.INACTIVE, UUID.randomUUID());

        try (SqliteProtocolResourceMutationAuthority authority = authority(registry, directory)) {
            assertTrue(mutate(authority, create).handled());
            adapter.failuresBeforeSave = 1;
            ProtocolEnvelopeDispatchResult first = mutate(authority, activate);
            assertFalse(first.handled());
            assertEquals(503, first.transportCode());
            assertEquals("RESOURCE_MUTATION_PENDING", first.code());

            ProtocolEnvelopeDispatchResult retry = mutate(authority, activate);
            assertTrue(retry.handled(), retry.code() + ": " + retry.message());
            ProtocolBody.ResourceDocumentResponse body = assertInstanceOf(ProtocolBody.ResourceDocumentResponse.class, retry.response().body());
            assertEquals(2, body.document().revision());
            assertEquals(ResourceActivationState.INACTIVE, body.document().activationState());
            assertEquals(false, ((Map<?, ?>) body.document().payload()).get("enabled"));
        }
        assertEquals(2, adapter.saves);
    }

    @Test
    void fencesPendingActivationAfterUnrelatedSameTargetToggle(@TempDir Path directory) throws Exception {
        Map<String, JsonObject> values = new ConcurrentHashMap<>();
        CountingAdapter adapter = new CountingAdapter(values);
        FlowResourceRegistry registry = registry(adapter);
        ServerResourceLocator resource = resource("activation-unrelated");
        ProtocolEnvelope<Map<String, Object>> create = create(resource, "Draft");
        ProtocolEnvelope<Map<String, Object>> activate = activate(resource, 1, ResourceActivationState.INACTIVE, UUID.randomUUID());

        try (SqliteProtocolResourceMutationAuthority authority = authority(registry, directory)) {
            assertTrue(mutate(authority, create).handled());
            adapter.failuresBeforeSave = 1;
            ProtocolEnvelopeDispatchResult first = mutate(authority, activate);
            assertFalse(first.handled());
            try (Connection connection = DriverManager.getConnection("jdbc:sqlite:" + directory.resolve("resource.db"));
                 PreparedStatement statement = connection.prepareStatement(
                     "UPDATE resource_mutation_receipt SET result_hash = '', result_payload = NULL WHERE mutation_id = ?")) {
                statement.setString(1, activate.mutationId().toString());
                statement.executeUpdate();
            }
            values.get(resource.id()).addProperty("name", "Unrelated");
            registry.setEnabledAuthoritative(ReSyncResourceCatalog.GUI, resource.id(), false);

            ProtocolEnvelopeDispatchResult retry = mutate(authority, activate);
            assertFalse(retry.handled());
            assertEquals(503, retry.transportCode());
            assertEquals("RESOURCE_RECOVERY_BLOCKED", retry.code());
            assertFalse(authority.durable());
            assertEquals(2, adapter.saves);
        }

        try (Connection connection = DriverManager.getConnection("jdbc:sqlite:" + directory.resolve("resource.db"));
             PreparedStatement state = connection.prepareStatement("SELECT revision, activation_state FROM resource_mutation_state WHERE resource = ?");
             PreparedStatement receipt = connection.prepareStatement("SELECT status FROM resource_mutation_receipt WHERE mutation_id = ?")) {
            state.setString(1, resource.canonicalText());
            try (var result = state.executeQuery()) {
                assertTrue(result.next());
                assertEquals(1, result.getLong(1));
                assertEquals(ResourceActivationState.ACTIVE.wireName(), result.getString(2));
            }
            receipt.setString(1, activate.mutationId().toString());
            try (var result = receipt.executeQuery()) {
                assertTrue(result.next());
                assertEquals("PENDING", result.getString(1));
            }
        }
    }

    @Test
    void fencesConcurrentPayloadChangeBetweenActivationPreflightAndApply(@TempDir Path directory) throws Exception {
        Map<String, JsonObject> values = new ConcurrentHashMap<>();
        CountingAdapter adapter = new CountingAdapter(values);
        FlowResourceRegistry registry = registry(adapter);
        ServerResourceLocator resource = resource("activation-concurrent");
        ProtocolEnvelope<Map<String, Object>> create = create(resource, "Draft");
        ProtocolEnvelope<Map<String, Object>> activate = activate(resource, 1, ResourceActivationState.INACTIVE, UUID.randomUUID());

        try (SqliteProtocolResourceMutationAuthority authority = authority(registry, directory)) {
            assertTrue(mutate(authority, create).handled());
            adapter.afterSaveMutation = () -> values.get(resource.id()).addProperty("resourceRevision", 7);

            ProtocolEnvelopeDispatchResult result = mutate(authority, activate);
            assertFalse(result.handled());
            assertEquals(503, result.transportCode());
            assertEquals("RESOURCE_RECOVERY_BLOCKED", result.code());
            assertFalse(authority.durable());
            assertEquals(2, adapter.saves);
            assertEquals(false, values.get(resource.id()).get("enabled").getAsBoolean());
            assertEquals(7, values.get(resource.id()).get("resourceRevision").getAsInt());
        }

        try (Connection connection = DriverManager.getConnection("jdbc:sqlite:" + directory.resolve("resource.db"));
             PreparedStatement state = connection.prepareStatement("SELECT revision, activation_state FROM resource_mutation_state WHERE resource = ?");
             PreparedStatement receipt = connection.prepareStatement("SELECT status FROM resource_mutation_receipt WHERE mutation_id = ?")) {
            state.setString(1, resource.canonicalText());
            try (var result = state.executeQuery()) {
                assertTrue(result.next());
                assertEquals(1, result.getLong(1));
                assertEquals(ResourceActivationState.ACTIVE.wireName(), result.getString(2));
            }
            receipt.setString(1, activate.mutationId().toString());
            try (var result = receipt.executeQuery()) {
                assertTrue(result.next());
                assertEquals("PENDING", result.getString(1));
            }
        }
    }

    @Test
    void mutationIdReplayIsBoundToTheTrustedActorWithoutLeakingTheDocument(@TempDir Path directory) {
        Map<String, JsonObject> values = new ConcurrentHashMap<>();
        CountingAdapter adapter = new CountingAdapter(values);
        FlowResourceRegistry registry = registry(adapter);
        ProtocolEnvelope<Map<String, Object>> request = create(resource("actor-bound"), "Private");
        try (SqliteProtocolResourceMutationAuthority authority = authority(registry, directory)) {
            ConnectionInfo clientA = authenticatedConnection("client-a");
            ProtocolEnvelopeDispatchResult first = authority.mutate(clientA, session(clientA, "client-a"), request, requestOperation(request));
            assertTrue(first.handled(), first.code() + ": " + first.message());
            values.get("actor-bound").addProperty("name", "ExternallyChanged");
            ProtocolEnvelopeDispatchResult sameActorReplay = authority.mutate(clientA, session(clientA, "client-a"), request, requestOperation(request));
            assertTrue(sameActorReplay.handled(), sameActorReplay.code() + ": " + sameActorReplay.message());
            ProtocolBody.ResourceDocumentResponse sameActorDocument = assertInstanceOf(ProtocolBody.ResourceDocumentResponse.class,
                sameActorReplay.response().body());
            assertEquals("Private", ((Map<?, ?>) sameActorDocument.document().payload()).get("name"));

            ConnectionInfo clientB = authenticatedConnection("client-b");
            ProtocolEnvelopeDispatchResult replay = authority.mutate(clientB, session(clientB, "client-b"), request, requestOperation(request));
            assertFalse(replay.handled());
            assertEquals(409, replay.transportCode());
            assertEquals(SqliteProtocolResourceMutationAuthority.ACTOR_CONFLICT_CODE, replay.code());
            assertNull(replay.response());
            assertFalse(replay.message().contains("Private"));
            assertEquals(1, adapter.saves);
        }
    }

    @Test
    void migratesLegacyReceiptsToAReplayDisabledActorAndRecoversPendingMutation(@TempDir Path directory) throws Exception {
        Map<String, JsonObject> values = new ConcurrentHashMap<>();
        CountingAdapter adapter = new CountingAdapter(values);
        FlowResourceRegistry registry = registry(adapter);
        ServerResourceLocator resource = resource("legacy-recovery");
        UUID mutationId = UUID.randomUUID();
        Map<String, Object> payload = Map.of("id", resource.id(), "name", "Recovered");
        CanonicalPayload<Map<String, Object>> canonical = ResourcePayloadCodecs.json().canonicalize(payload);
        Path database = directory.resolve("resource.db");
        createLegacyPendingReceipt(database, resource, mutationId, canonical);

        try (SqliteProtocolResourceMutationAuthority authority = authority(registry, directory)) {
            assertEquals("Recovered", values.get(resource.id()).get("name").getAsString());
            ProtocolEnvelope<Map<String, Object>> request = envelope(resource, new ResourceCreateRequest<>(resource, canonical, mutationId));
            ConnectionInfo client = authenticatedConnection("new-client");
            ProtocolEnvelopeDispatchResult replay = authority.mutate(client, session(client, "new-client"), request, requestOperation(request));
            assertFalse(replay.handled());
            assertEquals(409, replay.transportCode());
            assertEquals(SqliteProtocolResourceMutationAuthority.ACTOR_CONFLICT_CODE, replay.code());
            assertNull(replay.response());
            assertEquals(1, adapter.saves);
        }

        try (Connection connection = DriverManager.getConnection("jdbc:sqlite:" + database);
             PreparedStatement statement = connection.prepareStatement("SELECT actor_id FROM resource_mutation_receipt WHERE mutation_id = ?")) {
            statement.setString(1, mutationId.toString());
            try (var result = statement.executeQuery()) {
                assertTrue(result.next());
                assertEquals(SqliteProtocolResourceMutationAuthority.LEGACY_ACTOR, result.getString(1));
            }
        }
    }

    @Test
    void persistsTombstoneAndRejectsRevisionConflict(@TempDir Path directory) {
        Map<String, JsonObject> values = new ConcurrentHashMap<>();
        FlowResourceRegistry registry = registry(new CountingAdapter(values));
        ServerResourceLocator resource = resource("tombstone");
        ProtocolEnvelope<Map<String, Object>> create = create(resource, "Draft");
        try (SqliteProtocolResourceMutationAuthority authority = authority(registry, directory)) {
            ProtocolEnvelopeDispatchResult created = mutate(authority, create);
            assertTrue(created.handled(), created.code() + ": " + created.message());
            ProtocolEnvelope<Map<String, Object>> delete = delete(resource, 1);
            ProtocolEnvelopeDispatchResult deleted = mutate(authority, delete);
            assertTrue(deleted.handled());
            ProtocolBody.ResourceDocumentResponse body = assertInstanceOf(ProtocolBody.ResourceDocumentResponse.class, deleted.response().body());
            assertTrue(body.document().deleted());
            ProtocolEnvelope<Map<String, Object>> stale = save(resource, 1, "Stale");
            ProtocolEnvelopeDispatchResult notFound = mutate(authority, stale);
            assertFalse(notFound.handled());
            assertEquals(404, notFound.transportCode());
        }
    }

    @Test
    void rejectsMutationIdPayloadMismatch(@TempDir Path directory) {
        Map<String, JsonObject> values = new ConcurrentHashMap<>();
        FlowResourceRegistry registry = registry(new CountingAdapter(values));
        ServerResourceLocator resource = resource("mismatch");
        ProtocolEnvelope<Map<String, Object>> first = create(resource, "First");
        ProtocolEnvelope<Map<String, Object>> different = create(resource, "Second", first.mutationId());
        try (SqliteProtocolResourceMutationAuthority authority = authority(registry, directory)) {
            ProtocolEnvelopeDispatchResult created = mutate(authority, first);
            assertTrue(created.handled(), created.code() + ": " + created.message());
            ProtocolEnvelopeDispatchResult result = mutate(authority, different);
            assertTrue(result.handled());
            assertEquals(ProtocolEnvelope.Kind.CONFLICT, result.response().kind());
        }
    }

    @Test
    void rejectsCreatePayloadWithForeignIdBeforeRegistryMutation(@TempDir Path directory) {
        Map<String, JsonObject> values = new ConcurrentHashMap<>();
        CountingAdapter adapter = new CountingAdapter(values);
        FlowResourceRegistry registry = registry(adapter);
        ServerResourceLocator resource = resource("typed-id");
        Map<String, Object> payload = Map.of("id", "foreign-id", "name", "Rejected");
        CanonicalPayload<Map<String, Object>> canonical = ResourcePayloadCodecs.json().canonicalize(payload);
        ProtocolEnvelope<Map<String, Object>> request = envelope(resource,
            new ResourceCreateRequest<>(resource, canonical, UUID.randomUUID()));

        try (SqliteProtocolResourceMutationAuthority authority = authority(registry, directory)) {
            ProtocolEnvelopeDispatchResult result = mutate(authority, request);
            assertFalse(result.handled());
            assertEquals(503, result.transportCode());
            assertEquals("RESOURCE_MUTATION_FAILED", result.code());
            assertTrue(values.isEmpty());
            assertEquals(0, adapter.saves);
            assertTrue(registry.auditSnapshot().isEmpty());
        }
    }

    @Test
    void bootstrapsExternalResourceWithServerOwnedMetadata(@TempDir Path directory) {
        Map<String, JsonObject> values = new ConcurrentHashMap<>();
        CountingAdapter adapter = new CountingAdapter(values);
        JsonObject external = new JsonObject();
        external.addProperty("id", "bootstrap");
        external.addProperty("name", "Existing");
        external.addProperty("resourceRevision", 9001);
        external.addProperty("resourceMutationId", "aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa");
        UUID mutationId = UUID.fromString("aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa");
        adapter.seed(external, mutationId, 4L);
        FlowResourceRegistry registry = registry(adapter);
        ServerResourceLocator resource = resource("bootstrap");

        try (SqliteProtocolResourceMutationAuthority authority = authority(registry, directory)) {
            ResourceDocument<Map<String, Object>> document = authority.load(resource);
            assertEquals(4, document.revision());
            assertEquals(mutationId, document.mutationId());
        }
    }

    @Test
    void quiescedReadsDoNotBootstrapOrFallBackToExternalState(@TempDir Path directory) {
        Map<String, JsonObject> values = new ConcurrentHashMap<>();
        CountingAdapter adapter = new CountingAdapter(values);
        JsonObject external = new JsonObject();
        external.addProperty("id", "quiesced");
        external.addProperty("name", "Existing");
        adapter.seed(external, UUID.randomUUID(), 1L);
        FlowResourceRegistry registry = registry(adapter);
        ServerResourceLocator resource = resource("quiesced");

        try (SqliteProtocolResourceMutationAuthority authority = authority(registry, directory)) {
            try {
                authority.quiescePersistence();
            } catch (Exception exception) {
                throw new AssertionError(exception);
            }
            assertFalse(authority.authoritativeReads());
            assertFalse(authority.allowLegacyReads());
            assertThrows(IllegalStateException.class, () -> authority.load(resource));
        }
    }

    @Test
    void duplicatesWithTargetPayloadAndReplaysAfterRestart(@TempDir Path directory) {
        Map<String, JsonObject> values = new ConcurrentHashMap<>();
        CountingAdapter adapter = new CountingAdapter(values);
        FlowResourceRegistry registry = registry(adapter);
        ServerResourceLocator source = resource("source");
        ServerResourceLocator target = resource("target");
        JsonObject original = new JsonObject();
        original.addProperty("id", source.id());
        original.addProperty("name", "Draft");
        adapter.seed(original, UUID.randomUUID(), 1L);
        ProtocolEnvelope<Map<String, Object>> request = envelope(target,
            new ResourceDuplicateRequest(source, target, 1, UUID.randomUUID()));
        try (SqliteProtocolResourceMutationAuthority authority = authority(registry, directory)) {
            ProtocolEnvelopeDispatchResult result = mutate(authority, request);
            assertTrue(result.handled(), result.code() + ": " + result.message());
            ProtocolBody.ResourceDocumentResponse body = assertInstanceOf(ProtocolBody.ResourceDocumentResponse.class, result.response().body());
            assertEquals(target.id(), body.document().resource().id());
            assertEquals(target.id(), ((Map<?, ?>) body.document().payload()).get("id"));
        }
        try (SqliteProtocolResourceMutationAuthority authority = authority(registry, directory)) {
            ProtocolEnvelopeDispatchResult replay = mutate(authority, request);
            assertTrue(replay.handled());
            ProtocolBody.ResourceDocumentResponse body = assertInstanceOf(ProtocolBody.ResourceDocumentResponse.class, replay.response().body());
            assertEquals(target.id(), body.document().resource().id());
        }
        assertEquals(1, adapter.saves);
    }

    @Test
    void rejectsUnsupportedPresentationAndSubscriptionBoundaries(@TempDir Path directory) {
        Map<String, JsonObject> values = new ConcurrentHashMap<>();
        FlowResourceRegistry registry = registry(new CountingAdapter(values));
        ServerResourceLocator resource = resource("unsupported");
        ProtocolEnvelope<Map<String, Object>> rename = envelope(resource,
            new ResourceRenameRequest(resource, 1, "Renamed", UUID.randomUUID()));
        ProtocolEnvelope<Map<String, Object>> move = envelope(resource,
            new ResourceMoveRequest(resource, 1, "Folder", UUID.randomUUID()));
        ProtocolEnvelope<Map<String, Object>> subscribe = envelope(resource,
            new ResourceSubscribeRequest(resource, 0, true));

        try (SqliteProtocolResourceMutationAuthority authority = authority(registry, directory)) {
            ProtocolEnvelopeDispatchResult renameResult = mutate(authority, rename);
            ProtocolEnvelopeDispatchResult moveResult = mutate(authority, move);
            ProtocolEnvelopeDispatchResult subscribeResult = mutate(authority, subscribe);
            assertUnsupported(renameResult, "presentation rename");
            assertUnsupported(moveResult, "presentation move");
            assertUnsupported(subscribeResult, "session-bound durable resource subscription");
            assertFalse(authority.supports(requestOperation(rename)));
            assertFalse(authority.supports(requestOperation(move)));
            assertFalse(authority.supports(requestOperation(subscribe)));
        }
    }

    @Test
    void requiresLocalServerAndTrustedClientAndAuditsProtocolActor(@TempDir Path directory) {
        Map<String, JsonObject> values = new ConcurrentHashMap<>();
        FlowResourceRegistry registry = registry(new CountingAdapter(values));
        ProtocolEnvelope<Map<String, Object>> request = create(resource("trusted"), "Draft");
        try (SqliteProtocolResourceMutationAuthority authority = authority(registry, directory)) {
            ConnectionInfo mismatchedConnection = authenticatedConnection("connection-client");
            ProtocolEnvelopeDispatchResult mismatched = authority.mutate(mismatchedConnection,
                session(mismatchedConnection, "session-client"), request, requestOperation(request));
            assertFalse(mismatched.handled());
            assertEquals(403, mismatched.transportCode());

            ServerId foreignServer = new ServerId(UUID.fromString("aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa"));
            ServerResourceLocator foreignResource = new ServerResourceLocator(foreignServer, TYPE, "foreign");
            ProtocolEnvelope<Map<String, Object>> foreignRequest = create(foreignResource, "Draft");
            ProtocolEnvelopeDispatchResult foreign = mutate(authority, foreignRequest);
            assertFalse(foreign.handled());
            assertEquals(403, foreign.transportCode());

            ProtocolEnvelopeDispatchResult result = mutate(authority, request);
            assertTrue(result.handled(), result.code() + ": " + result.message());
            assertEquals(SERVER, result.response().serverId());
            assertEquals(SERVER, result.response().resource().serverId());
            FlowResourceAuditRecord audit = registry.auditSnapshot().getLast();
            assertEquals("protocol", audit.source());
            assertEquals("client", audit.actor());
        }
    }

    @Test
    void appliesInjectedResourcePolicyAtTheDurableBoundary(@TempDir Path directory) {
        Map<String, JsonObject> values = new ConcurrentHashMap<>();
        CountingAdapter adapter = new CountingAdapter(values);
        FlowResourceRegistry registry = registry(adapter);
        ProtocolEnvelope<Map<String, Object>> request = create(resource("policy"), "Denied");
        ProtocolResourceAuthorizer deny = (connection, session, envelope, operation) -> false;
        try (SqliteProtocolResourceMutationAuthority authority = new SqliteProtocolResourceMutationAuthority(
            registry, SERVER, directory.resolve("resource.db"), CoreGraphResourceAuthority.unavailable(), deny,
            AuthorityEpoch.fixed(1L))) {
            ConnectionInfo connection = authenticatedConnection("client");
            ProtocolEnvelopeDispatchResult result = authority.mutate(connection, session(connection, "client"), request,
                requestOperation(request));
            assertFalse(result.handled());
            assertEquals(403, result.transportCode());
            assertEquals(0, adapter.saves);
            assertTrue(values.isEmpty());
        }
    }

    @Test
    void durableBoundaryRejectsForeignResourceOwnerWithoutLeakingState(@TempDir Path directory) {
        Map<String, JsonObject> values = new ConcurrentHashMap<>();
        CountingAdapter adapter = new CountingAdapter(values);
        FlowResourceRegistry registry = registry(adapter);
        ServerResourceLocator resource = new ServerResourceLocator(SERVER,
            ContractRef.of(new OwnerId("foreign.owner"), new ResourceTypeId(ReSyncResourceCatalog.GUI)), "foreign-owner");
        ProtocolEnvelope<Map<String, Object>> request = create(resource, "Should Not Persist");

        try (SqliteProtocolResourceMutationAuthority authority = authority(registry, directory)) {
            ProtocolEnvelopeDispatchResult result = mutate(authority, request);

            assertFalse(result.handled());
            assertEquals(403, result.transportCode());
            assertEquals(0, adapter.saves);
            assertTrue(values.isEmpty());
            assertNull(authority.load(resource));
            assertTrue(authority.list(SERVER, resource.type(), "").isEmpty());
        }
    }

    private static void assertUnsupported(ProtocolEnvelopeDispatchResult result, String boundary) {
        assertFalse(result.handled());
        assertEquals(405, result.transportCode());
        assertEquals("RESOURCE_OPERATION_UNSUPPORTED", result.code());
        assertTrue(result.message().contains(boundary));
    }

    private static FlowResourceRegistry registry(CountingAdapter adapter) {
        FlowResourceRegistry registry = new FlowResourceRegistry();
        registry.register(adapter);
        return registry;
    }

    private static Map<String, Object> jsonPayload(Object payload) {
        Map<?, ?> values = assertInstanceOf(Map.class, payload);
        LinkedHashMap<String, Object> typed = new LinkedHashMap<>();
        values.forEach((key, value) -> typed.put(assertInstanceOf(String.class, key), value));
        return ResourcePayloadCodecs.json().normalize(typed);
    }

    private static ServerResourceLocator resource(String id) {
        return new ServerResourceLocator(SERVER, TYPE, id);
    }

    private static ProtocolEnvelope<Map<String, Object>> create(ServerResourceLocator resource, String name) {
        return create(resource, name, UUID.randomUUID());
    }

    private static ProtocolEnvelope<Map<String, Object>> create(ServerResourceLocator resource, String name, UUID mutationId) {
        Map<String, Object> payload = Map.of("id", resource.id(), "name", name);
        CanonicalPayload<Map<String, Object>> canonical = ResourcePayloadCodecs.json().canonicalize(payload);
        return envelope(resource, new ResourceCreateRequest<>(resource, canonical, mutationId));
    }

    private static ProtocolEnvelope<Map<String, Object>> save(ServerResourceLocator resource, long revision, String name) {
        Map<String, Object> payload = Map.of("id", resource.id(), "name", name);
        CanonicalPayload<Map<String, Object>> canonical = ResourcePayloadCodecs.json().canonicalize(payload);
        return envelope(resource, new ResourceSaveRequest<>(resource, revision, canonical, UUID.randomUUID()));
    }

    private static ProtocolEnvelope<Map<String, Object>> delete(ServerResourceLocator resource, long revision) {
        return envelope(resource, new ResourceDeleteRequest(resource, revision, UUID.randomUUID()));
    }

    private static ProtocolEnvelope<Map<String, Object>> activate(ServerResourceLocator resource, long revision,
                                                                   ResourceActivationState targetState, UUID mutationId) {
        return envelope(resource, new ResourceActivateRequest(resource, revision, targetState, mutationId));
    }

    private static ProtocolEnvelope<Map<String, Object>> envelope(ServerResourceLocator resource, ResourceOperation operation) {
        return envelope(resource, operation,
            operation instanceof ResourceActivateRequest ? ReSyncProtocolContract.GENERIC_RESOURCE_CONTRACT_VERSION
                : ReSyncProtocolContract.GENERIC_RESOURCE_CONTRACT_MINIMUM_VERSION);
    }

    private static ProtocolEnvelope<Map<String, Object>> envelope(ServerResourceLocator resource, ResourceOperation operation,
                                                                  CatalogVersion contractVersion) {
        Set<ContractRef<CapabilityId>> capabilities = operation instanceof ResourceCreateRequest<?> create && create.presentation() != null
            ? Set.of(ContractRef.of(new OwnerId("restudio.resync"), new CapabilityId("resources")),
                ReSyncProtocolContract.RESOURCE_CREATE_PRESENTATION_CAPABILITY)
            : operation instanceof ResourceActivateRequest
            ? Set.of(ContractRef.of(new OwnerId("restudio.resync"), new CapabilityId("resources")), ReSyncProtocolContract.RESOURCE_ACTIVATION_CAPABILITY)
            : Set.of(ContractRef.of(new OwnerId("restudio.resync"), new CapabilityId("resources")));
        long authorityEpoch = switch (operation.kind()) {
            case CREATE, SAVE, DELETE, DUPLICATE, RENAME, MOVE, ACTIVATE -> 1L;
            default -> 0L;
        };
        return new ProtocolEnvelope<>(ProtocolEnvelope.Kind.REQUEST, contractVersion, UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
            resource.serverId(), resource, operation instanceof ResourceCreateRequest<?> create ? 0 : operation instanceof ResourceSaveRequest<?> save ? save.expectedRevision() :
                operation instanceof ResourceRenameRequest rename ? rename.expectedRevision() : operation instanceof ResourceMoveRequest move ? move.expectedRevision() :
                operation instanceof ResourceDeleteRequest delete ? delete.expectedRevision() : operation instanceof ResourceDuplicateRequest duplicate ? duplicate.expectedRevision() :
                operation instanceof ResourceActivateRequest activate ? activate.expectedRevision() : 0,
            authorityEpoch, mutation(operation), ContractRef.of(new OwnerId("restudio.resync"), new OperationId("resource." + operation.kind().name().toLowerCase())),
            capabilities,
            ContractRef.of(new OwnerId("restudio.resync"), new ResourceTypeId("resource.document")), null,
            operation instanceof ResourceCreateRequest<?> create ? create.payloadHash() : operation instanceof ResourceSaveRequest<?> save ? save.payloadHash() : null,
            false, null, null, null, null, null, 0, ProtocolEnvelope.Status.ACCEPTED, List.of(), Map.of(), new ProtocolBody.ResourceRequest(operation));
    }

    private static UUID mutation(ResourceOperation operation) {
        return switch (operation) {
            case ResourceCreateRequest<?> create -> create.mutationId();
            case ResourceSaveRequest<?> save -> save.mutationId();
            case ResourceRenameRequest rename -> rename.mutationId();
            case ResourceMoveRequest move -> move.mutationId();
            case ResourceDeleteRequest delete -> delete.mutationId();
            case ResourceDuplicateRequest duplicate -> duplicate.mutationId();
            case ResourceActivateRequest activate -> activate.mutationId();
            case ResourceSubscribeRequest ignored -> null;
            default -> throw new IllegalArgumentException();
        };
    }

    private static ResourceOperation requestOperation(ProtocolEnvelope<Map<String, Object>> request) {
        return ((ProtocolBody.ResourceRequest) request.body()).operation();
    }

    private static SqliteProtocolResourceMutationAuthority authority(FlowResourceRegistry registry, Path directory) {
        return new SqliteProtocolResourceMutationAuthority(registry, SERVER, directory.resolve("resource.db"),
            CoreGraphResourceAuthority.unavailable(), AuthorityEpoch.fixed(1L));
    }

    private static void createLegacyPendingReceipt(Path database, ServerResourceLocator resource, UUID mutationId,
                                                   CanonicalPayload<Map<String, Object>> payload) throws Exception {
        try (Connection connection = DriverManager.getConnection("jdbc:sqlite:" + database);
             Statement statement = connection.createStatement()) {
            statement.execute("CREATE TABLE resource_mutation_authority_meta(key TEXT PRIMARY KEY, value TEXT NOT NULL)");
            statement.execute("CREATE TABLE resource_mutation_state(resource TEXT PRIMARY KEY, revision INTEGER NOT NULL, mutation_id TEXT NOT NULL, payload_hash TEXT NOT NULL, deleted INTEGER NOT NULL, payload TEXT, updated_at INTEGER NOT NULL)");
            statement.execute("CREATE TABLE resource_mutation_receipt(mutation_id TEXT PRIMARY KEY, fingerprint TEXT NOT NULL, operation TEXT NOT NULL, requested_resource TEXT NOT NULL, response_resource TEXT NOT NULL, source_resource TEXT, target_resource TEXT, expected_revision INTEGER NOT NULL, precondition_hash TEXT NOT NULL, status TEXT NOT NULL, result_revision INTEGER NOT NULL, result_mutation_id TEXT NOT NULL, result_hash TEXT NOT NULL, result_deleted INTEGER NOT NULL, result_payload TEXT, sequence INTEGER NOT NULL, error_code TEXT NOT NULL, error_message TEXT NOT NULL, created_at INTEGER NOT NULL, updated_at INTEGER NOT NULL)");
            try (PreparedStatement meta = connection.prepareStatement("INSERT INTO resource_mutation_authority_meta(key, value) VALUES('schema', 'resource-mutation-authority-v1')");
                 PreparedStatement receipt = connection.prepareStatement("INSERT INTO resource_mutation_receipt(mutation_id, fingerprint, operation, requested_resource, response_resource, source_resource, target_resource, expected_revision, precondition_hash, status, result_revision, result_mutation_id, result_hash, result_deleted, result_payload, sequence, error_code, error_message, created_at, updated_at) VALUES(?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)");) {
                meta.executeUpdate();
                receipt.setString(1, mutationId.toString());
                receipt.setString(2, "legacy-fingerprint");
                receipt.setString(3, "CREATE");
                receipt.setString(4, resource.canonicalText());
                receipt.setString(5, resource.canonicalText());
                receipt.setString(6, null);
                receipt.setString(7, resource.canonicalText());
                receipt.setLong(8, 0);
                receipt.setString(9, "");
                receipt.setString(10, "PENDING");
                receipt.setLong(11, 1);
                receipt.setString(12, mutationId.toString());
                receipt.setString(13, payload.checksum().canonicalText());
                receipt.setInt(14, 0);
                receipt.setString(15, ResourcePayloadCodecs.json().canonicalInput(payload.value()));
                receipt.setLong(16, 0);
                receipt.setString(17, "");
                receipt.setString(18, "");
                receipt.setLong(19, 1);
                receipt.setLong(20, 1);
                receipt.executeUpdate();
            }
        }
    }

    private static ProtocolEnvelopeDispatchResult mutate(SqliteProtocolResourceMutationAuthority authority,
                                                          ProtocolEnvelope<Map<String, Object>> request) {
        ConnectionInfo connection = authenticatedConnection("client");
        return authority.mutate(connection, session(connection, "client"), request, requestOperation(request));
    }

    private static ConnectionInfo authenticatedConnection(String clientId) {
        ConnectionInfo connection = new ConnectionInfo(null, new FrameSender() {
            @Override
            public void send(byte[] frame) {
            }

            @Override
            public void close(int code, String reason) {
            }
        }, 1);
        connection.setClientId(clientId);
        connection.setClientVersion("2.1.0");
        connection.setState(ConnectionState.AUTHENTICATED);
        connection.setProtocolResourceAccess(true);
        return connection;
    }

    private static Session session(ConnectionInfo connection, String clientId) {
        return new Session("session", clientId, connection, new ClientIdentity(clientId, "2.1.0"));
    }

    private enum StampMismatch {
        UUID,
        REVISION,
        HASH,
        DELETED
    }

    private static final class CountingAdapter implements FlowResourceAdapter<JsonObject> {
        private final Map<String, JsonObject> values;
        private final Map<String, FlowResourceMutationStamp> stamps = new ConcurrentHashMap<>();
        private final String type;
        private int saves;
        private int failuresBeforeSave;
        private int failuresBeforeGet;
        private Runnable afterSaveMutation;
        private boolean exactIdentity = true;
        private StampMismatch stampMismatch;
        private FlowResourceMutationAdmission observedAdmission;
        private FlowResourceKey observedKey;
        private boolean admittedDuringSave;
        private boolean admittedDuringStamp;
        private String validationFailure;
        private String deserializationFailure;
        private boolean addServerDefault;

        private CountingAdapter(Map<String, JsonObject> values) {
            this(values, ReSyncResourceCatalog.GUI);
        }

        private CountingAdapter(Map<String, JsonObject> values, String type) {
            this.values = values;
            this.type = type;
        }

        @Override
        public ReSyncManagedResource descriptor() {
            return ReSyncResourceCatalog.byType(type);
        }

        @Override
        public JsonObject get(String id) {
            if (failuresBeforeGet > 0) {
                failuresBeforeGet--;
                throw new IllegalStateException("Injected get failure");
            }
            JsonObject value = values.get(id);
            return value == null ? null : value.deepCopy();
        }

        @Override
        public List<String> listIds() {
            return List.copyOf(values.keySet());
        }

        @Override
        public JsonObject deserialize(String json) {
            if (deserializationFailure != null) {
                throw new IllegalArgumentException(deserializationFailure);
            }
            return new Gson().fromJson(json, JsonObject.class);
        }

        @Override
        public String serialize(JsonObject value) {
            return value.toString();
        }

        @Override
        public String id(JsonObject value) {
            return value.get("id").getAsString();
        }

        @Override
        public void validate(JsonObject value) {
            if (validationFailure != null) {
                throw new IllegalArgumentException(validationFailure);
            }
        }

        @Override
        public void save(JsonObject value) {
            save(value, UUID.randomUUID(), currentRevision(id(value)));
        }

        @Override
        public boolean supportsAuthoritativeMutationIdentity() {
            return exactIdentity;
        }

        @Override
        public void save(JsonObject value, UUID mutationId, long expectedRevision) {
            if (failuresBeforeSave > 0) {
                failuresBeforeSave--;
                throw new IllegalStateException("Injected save failure");
            }
            String id = id(value);
            admittedDuringSave |= observedAdmission != null && observedAdmission.isAdmitted(observedKey);
            long currentRevision = currentRevision(id);
            if (currentRevision != expectedRevision) {
                throw new IllegalStateException("Unexpected expected revision");
            }
            saves++;
            JsonObject copy = value.deepCopy();
            if (addServerDefault) {
                copy.addProperty("serverDefault", true);
            }
            values.put(id, copy);
            FlowResourceMutationStamp stamp = new FlowResourceMutationStamp(type, id, expectedRevision + 1L, mutationId,
                payloadHash(copy), false);
            if (stampMismatch != null) {
                stamp = switch (stampMismatch) {
                    case UUID -> new FlowResourceMutationStamp(stamp.type(), stamp.id(), stamp.revision(), UUID.randomUUID(),
                        stamp.payloadHash(), stamp.deleted());
                    case REVISION -> new FlowResourceMutationStamp(stamp.type(), stamp.id(), stamp.revision() + 1L, stamp.mutationId(),
                        stamp.payloadHash(), stamp.deleted());
                    case HASH -> new FlowResourceMutationStamp(stamp.type(), stamp.id(), stamp.revision(), stamp.mutationId(),
                        "f".repeat(64), stamp.deleted());
                    case DELETED -> new FlowResourceMutationStamp(stamp.type(), stamp.id(), stamp.revision(), stamp.mutationId(),
                        stamp.payloadHash(), true);
                };
            }
            stamps.put(id, stamp);
        }

        @Override
        public void afterSave(JsonObject value) {
            if (afterSaveMutation != null) {
                Runnable mutation = afterSaveMutation;
                afterSaveMutation = null;
                mutation.run();
            }
        }

        @Override
        public void delete(String id) {
            values.remove(id);
        }

        @Override
        public void delete(String id, UUID mutationId, long expectedRevision) {
            long currentRevision = currentRevision(id);
            if (currentRevision != expectedRevision || !values.containsKey(id)) {
                throw new IllegalStateException("Unexpected expected revision");
            }
            FlowResourceMutationStamp previous = stamps.get(id);
            values.remove(id);
            stamps.put(id, new FlowResourceMutationStamp(type, id, expectedRevision + 1L, mutationId,
                previous.payloadHash(), true));
        }

        @Override
        public FlowResourceMutationStamp readMutationStamp(String id) {
            admittedDuringStamp |= observedAdmission != null && observedAdmission.isAdmitted(observedKey);
            return stamps.get(id);
        }

        @Override
        public boolean matchesCommittedPayloadRecovery(JsonObject previous, JsonObject requested, JsonObject actual) {
            if (!addServerDefault || requested == null || actual == null) {
                return false;
            }
            JsonObject normalized = requested.deepCopy();
            normalized.addProperty("serverDefault", true);
            return normalized.equals(actual);
        }

        private void seed(JsonObject value, UUID mutationId, long revision) {
            JsonObject copy = value.deepCopy();
            String id = id(copy);
            values.put(id, copy);
            stamps.put(id, new FlowResourceMutationStamp(type, id, revision, mutationId,
                payloadHash(copy), false));
        }

        private long currentRevision(String id) {
            FlowResourceMutationStamp stamp = stamps.get(id);
            return stamp == null ? 0L : stamp.revision();
        }

        private String payloadHash(JsonObject value) {
            Map<String, Object> payload = new Gson().fromJson(value.toString(), Map.class);
            return ResourcePayloadCodecs.json().canonicalize(payload).checksum().canonicalText();
        }

        @Override
        public JsonObject duplicate(JsonObject value, String targetId) {
            JsonObject copy = value.deepCopy();
            copy.addProperty("id", targetId);
            return copy;
        }

        @Override
        public Set<String> supportedOperations() {
            return Set.of("discover", "query", "get", "create", "validate", "save", "update", "duplicate", "delete");
        }
    }

    private static final class AggregateStorage implements AggregateResourceCreateStorage {
        private int creates;
        private int publications;
        private int publicationFailures;
        private int failuresBeforeCreate;
        private String rejectionBeforeCreate;
        private boolean locatorAvailable = true;
        private Result firstResult;
        private Result lastResult;
        private ResourcePresentationIntent lastPresentation;

        @Override
        public boolean available() {
            return true;
        }

        @Override
        public boolean available(ServerResourceLocator resource) {
            return locatorAvailable;
        }

        @Override
        public Result create(ServerResourceLocator resource, Object value, FlowResourceMutationContext context,
                             ResourcePresentationIntent presentation) {
            creates++;
            lastPresentation = presentation;
            if (failuresBeforeCreate-- > 0) {
                throw new IllegalStateException("Injected pre-commit failure");
            }
            if (rejectionBeforeCreate != null) {
                throw AggregateResourceCreateStorage.rejectBeforeCommit("RESOURCE_OPERATION_FAILED", rejectionBeforeCreate,
                    new IllegalArgumentException(rejectionBeforeCreate));
            }
            Map<String, Object> primary = new Gson().fromJson(((JsonObject) value).toString(), Map.class);
            Map<String, Object> metadata = Map.of("serverId", SERVER.canonicalText(), "resources", List.of(Map.of(
                "type", resource.resourceType().value(), "id", resource.id(), "displayName", presentation.displayName(),
                "path", presentation.path(), "sortOrder", presentation.sortOrder())));
            String primaryHash = ResourcePayloadCodecs.json().canonicalize(primary).checksum().canonicalText();
            String metadataHash = ResourcePayloadCodecs.json().canonicalize(metadata).checksum().canonicalText();
            ServerResourceLocator metadataResource = new ServerResourceLocator(SERVER,
                ContractRef.of(new OwnerId("restudio.resync"), new ResourceTypeId("project_metadata")), SERVER.canonicalText());
            lastResult = new Result(
                new ResourceState(resource, new FlowResourceMutationStamp(resource.resourceType().value(), resource.id(), 1,
                    context.exactMutationId(), primaryHash, false), primary),
                new ResourceState(metadataResource, new FlowResourceMutationStamp("project_metadata", SERVER.canonicalText(), 1,
                    context.exactMutationId(), metadataHash, false), metadata));
            if (firstResult == null) {
                firstResult = lastResult;
            }
            return lastResult;
        }

        @Override
        public void publishCommitted(ServerResourceLocator resource, UUID mutationId) {
            publications++;
            if (publicationFailures-- > 0) {
                throw new IllegalStateException("Publication failed");
            }
        }
    }
}
