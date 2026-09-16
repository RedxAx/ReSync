package restudio.resync.server;

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
import restudio.resync.flow.protocol.ProtocolBody;
import restudio.resync.flow.protocol.ProtocolEnvelope;
import restudio.resync.flow.protocol.ResourceOperation;
import restudio.resync.flow.protocol.ResourceSubscribeRequest;
import restudio.resync.migration.MigrationException;
import restudio.resync.migration.MigrationPreflight;
import restudio.resync.migration.PersistenceOwnershipContext;
import restudio.resync.migration.PersistenceOwnershipIndex;
import restudio.resync.migration.PersistenceParticipant;
import restudio.resync.migration.PersistenceParticipantRegistry;
import restudio.resync.migration.ProductionPersistenceOwners;
import restudio.resync.migration.PreflightResult;
import restudio.resync.migration.SnapshotManifest;
import restudio.resync.migration.SnapshotMetadata;
import restudio.resync.modules.flow.FlowResourceRegistry;
import restudio.resync.protocol.FrameSender;
import restudio.resync.security.ClientIdentity;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SqliteProtocolResourceMutationPersistenceParticipantTest {
    private static final ServerId SERVER = new ServerId(UUID.fromString("11111111-1111-4111-8111-111111111111"));
    private static final ContractRef<ResourceTypeId> TYPE = ContractRef.of(new OwnerId("restudio.resync"), new ResourceTypeId("gui"));

    @TempDir
    Path temporary;

    @Test
    void fencesWritesFlushesAndRebindsTheDatabase() throws Exception {
        Path source = Files.createDirectories(Files.createDirectories(temporary.resolve("source")).resolve("runtime"));
        Path target = Files.createDirectories(Files.createDirectories(temporary.resolve("target")).resolve("runtime"));
        SqliteProtocolResourceMutationAuthority authority = new SqliteProtocolResourceMutationAuthority(
            new FlowResourceRegistry(), SERVER, source.resolve("resource-mutations.db"), CoreGraphResourceAuthority.unavailable(),
            AuthorityEpoch.fixed(1L));
        SqliteProtocolResourceMutationPersistenceParticipant participant = new SqliteProtocolResourceMutationPersistenceParticipant(source.getParent(), authority);

        participant.healthCheck();
        participant.flush();
        participant.quiesce();

        ProtocolEnvelope<Map<String, Object>> request = envelope(new ResourceSubscribeRequest(resource(), 0, true));
        ConnectionInfo connection = authenticatedConnection();
        ProtocolEnvelopeDispatchResult result = authority.mutate(connection, session(connection), request, operation(request));
        assertFalse(result.handled());
        assertEquals("RESOURCE_MUTATION_QUIESCED", result.code());
        assertTrue(participant.root().toFile().isFile());

        participant.rebind(target.getParent());
        assertEquals(target.resolve("resource-mutations.db"), participant.root());
        participant.healthCheck();
        participant.resume();
        assertEquals(SqliteProtocolResourceMutationPersistenceParticipant.OWNER, participant.owner());
        assertEquals(source.getParent(), participant.rebindScope());
        authority.close();
    }

    @Test
    void failedRebindLeavesTheQuiescedSourceActive() throws Exception {
        Path source = Files.createDirectories(Files.createDirectories(temporary.resolve("source")).resolve("runtime"));
        Path target = Files.createDirectories(Files.createDirectories(temporary.resolve("target")).resolve("runtime"));
        Path invalidDatabase = Files.createDirectory(target.resolve("resource-mutations.db"));
        SqliteProtocolResourceMutationAuthority authority = new SqliteProtocolResourceMutationAuthority(
            new FlowResourceRegistry(), SERVER, source.resolve("resource-mutations.db"), CoreGraphResourceAuthority.unavailable(),
            AuthorityEpoch.fixed(1L));
        SqliteProtocolResourceMutationPersistenceParticipant participant = new SqliteProtocolResourceMutationPersistenceParticipant(source.getParent(), authority);

        participant.quiesce();
        assertThrows(IOException.class, () -> participant.rebind(target.getParent()));
        assertEquals(source.resolve("resource-mutations.db"), authority.databasePath());
        assertEquals(source.resolve("resource-mutations.db"), participant.root());
        assertTrue(Files.isDirectory(invalidDatabase));
        participant.resume();
        authority.close();
    }

    @Test
    void closesMutationAdmissionBeforeTheDatabaseIsQuiesced() throws Exception {
        Path runtime = Files.createDirectories(temporary.resolve("source").resolve("runtime"));
        SqliteProtocolResourceMutationAuthority authority = new SqliteProtocolResourceMutationAuthority(
            new FlowResourceRegistry(), SERVER, runtime.resolve("resource-mutations.db"), CoreGraphResourceAuthority.unavailable(),
            AuthorityEpoch.fixed(1L));
        SqliteProtocolResourceMutationPersistenceParticipant participant =
            new SqliteProtocolResourceMutationPersistenceParticipant(runtime.getParent(), authority);

        authority.closeMutationAdmission();
        ProtocolEnvelope<Map<String, Object>> request = envelope(new ResourceSubscribeRequest(resource(), 0, true));
        ConnectionInfo connection = authenticatedConnection();
        ProtocolEnvelopeDispatchResult result = authority.mutate(
            connection, session(connection), request, operation(request));

        assertFalse(result.handled());
        assertEquals("RESOURCE_MUTATION_QUIESCED", result.code());
        assertFalse(authority.durable());
        participant.quiesce();
        participant.resume();
        ProtocolEnvelopeDispatchResult resumed = authority.mutate(connection, session(connection), request, operation(request));
        assertFalse(resumed.handled());
        assertEquals("RESOURCE_MUTATION_QUIESCED", resumed.code());
        authority.close();
        authority.close();
    }

    @Test
    void failedSchemaRebindLeavesTheQuiescedSourceActive() throws Exception {
        Path source = Files.createDirectories(temporary.resolve("source").resolve("runtime"));
        Path target = Files.createDirectories(temporary.resolve("target").resolve("runtime"));
        Path candidate = target.resolve("resource-mutations.db");
        try (Connection connection = DriverManager.getConnection("jdbc:sqlite:" + candidate);
             Statement statement = connection.createStatement()) {
            statement.execute("CREATE TABLE resource_mutation_authority_meta(key TEXT PRIMARY KEY, value TEXT NOT NULL)");
            statement.execute("INSERT INTO resource_mutation_authority_meta(key, value) VALUES('schema', 'unsupported-schema')");
        }
        SqliteProtocolResourceMutationAuthority authority = new SqliteProtocolResourceMutationAuthority(
            new FlowResourceRegistry(), SERVER, source.resolve("resource-mutations.db"), CoreGraphResourceAuthority.unavailable(),
            AuthorityEpoch.fixed(1L));
        SqliteProtocolResourceMutationPersistenceParticipant participant =
            new SqliteProtocolResourceMutationPersistenceParticipant(source.getParent(), authority);

        participant.quiesce();
        assertThrows(IOException.class, () -> participant.rebind(target.getParent()));
        assertEquals(source.resolve("resource-mutations.db"), authority.databasePath());
        assertEquals(source.resolve("resource-mutations.db"), participant.root());
        participant.resume();
        authority.close();
    }

    @Test
    void rejectsAnAuthorityOutsideTheRuntimeDatabase(@TempDir Path temporary) throws Exception {
        Path scope = Files.createDirectories(temporary.resolve("scope"));
        Path database = Files.createDirectories(scope.resolve("other")).resolve("resource-mutations.db");
        SqliteProtocolResourceMutationAuthority authority = new SqliteProtocolResourceMutationAuthority(new FlowResourceRegistry(), SERVER,
            database, CoreGraphResourceAuthority.unavailable(), AuthorityEpoch.fixed(1L));

        assertThrows(IllegalArgumentException.class,
            () -> new SqliteProtocolResourceMutationPersistenceParticipant(scope, authority));
        authority.close();
    }

    @Test
    void declaresSharedAssetsAsItsResumeFoundation() throws Exception {
        Path runtime = Files.createDirectories(temporary.resolve("dependency").resolve("runtime"));
        SqliteProtocolResourceMutationAuthority authority = new SqliteProtocolResourceMutationAuthority(
            new FlowResourceRegistry(), SERVER, runtime.resolve("resource-mutations.db"),
            CoreGraphResourceAuthority.unavailable(), AuthorityEpoch.fixed(1L));
        SqliteProtocolResourceMutationPersistenceParticipant participant =
            new SqliteProtocolResourceMutationPersistenceParticipant(runtime.getParent(), authority);

        assertEquals(Set.of(ProductionPersistenceOwners.FLOW_ASSETS), participant.resumeDependencies());
        authority.close();
    }

    @Test
    void ownsOnlyTheBoundedDatabaseFamilyAndIncludesWalSidecarsInSnapshots() throws Exception {
        Path dataRoot = Files.createDirectory(temporary.resolve("data"));
        Path runtime = Files.createDirectory(dataRoot.resolve("runtime"));
        Path database = runtime.resolve("resource-mutations.db");
        Path automationTasks = runtime.resolve("automation-tasks.json");
        SqliteProtocolResourceMutationAuthority authority = new SqliteProtocolResourceMutationAuthority(
            new FlowResourceRegistry(), SERVER, database, CoreGraphResourceAuthority.unavailable(), AuthorityEpoch.fixed(1L));
        SqliteProtocolResourceMutationPersistenceParticipant participant =
            new SqliteProtocolResourceMutationPersistenceParticipant(dataRoot, authority);
        participant.quiesce();
        authority.close();

        Path wal = database.resolveSibling("resource-mutations.db-wal");
        Path shm = database.resolveSibling("resource-mutations.db-shm");
        Files.writeString(wal, "wal-sidecar");
        Files.writeString(shm, "shm-sidecar");
        Files.writeString(automationTasks, "[]\n");

        PersistenceOwnershipContext context = new PersistenceOwnershipContext(dataRoot, participant.root());
        PersistenceOwnershipIndex index = participant.ownershipIndex(context);
        assertEquals(participant.owns(database), index.owns(context.participantRootRelative()));
        assertEquals(participant.owns(wal), index.owns(context.relativeToSource(wal)));
        assertEquals(participant.owns(shm), index.owns(context.relativeToSource(shm)));
        Path malformed = database.resolveSibling("resource-mutations.db-journal");
        assertEquals(participant.owns(malformed), index.owns(context.relativeToSource(malformed)));

        PersistenceParticipant automation = new PersistenceParticipant() {
            @Override
            public String owner() {
                return "resync.jobs.automation-tasks";
            }

            @Override
            public Path root() {
                return automationTasks;
            }
        };
        PersistenceParticipantRegistry participants = new PersistenceParticipantRegistry(dataRoot);
        participants.register(participant);
        participants.register(automation);

        assertEquals(SqliteProtocolResourceMutationPersistenceParticipant.OWNER,
            participants.ownerFor(dataRoot, wal));
        assertEquals(SqliteProtocolResourceMutationPersistenceParticipant.OWNER,
            participants.ownerFor(dataRoot, shm));
        assertEquals("resync.jobs.automation-tasks", participants.ownerFor(dataRoot, automationTasks));

        PreflightResult preflight = new MigrationPreflight().inspect(
            dataRoot, temporary.resolve("staging"), participants, 0);
        assertTrue(preflight.passed(), () -> preflight.checks().toString());

        SnapshotManifest manifest = SnapshotManifest.scan(dataRoot, SnapshotMetadata.preflight(), participants);
        assertEquals(SqliteProtocolResourceMutationPersistenceParticipant.OWNER,
            manifest.entries().stream().filter(entry -> entry.relativePath().endsWith("resource-mutations.db-wal"))
                .findFirst().orElseThrow().owner());
        assertEquals(SqliteProtocolResourceMutationPersistenceParticipant.OWNER,
            manifest.entries().stream().filter(entry -> entry.relativePath().endsWith("resource-mutations.db-shm"))
                .findFirst().orElseThrow().owner());
    }

    @Test
    void rejectsAnUnexpectedDatabaseSidecarInsteadOfClaimingTheRuntimeDirectory() throws Exception {
        Path dataRoot = Files.createDirectory(temporary.resolve("data-unexpected-sidecar"));
        Path runtime = Files.createDirectory(dataRoot.resolve("runtime"));
        Path database = runtime.resolve("resource-mutations.db");
        SqliteProtocolResourceMutationAuthority authority = new SqliteProtocolResourceMutationAuthority(
            new FlowResourceRegistry(), SERVER, database, CoreGraphResourceAuthority.unavailable(), AuthorityEpoch.fixed(1L));
        SqliteProtocolResourceMutationPersistenceParticipant participant =
            new SqliteProtocolResourceMutationPersistenceParticipant(dataRoot, authority);
        participant.quiesce();
        authority.close();
        Files.writeString(database.resolveSibling("resource-mutations.db-journal"), "unexpected-sidecar");

        PersistenceParticipantRegistry participants = new PersistenceParticipantRegistry(dataRoot);
        participants.register(participant);

        assertThrows(MigrationException.class, () -> participants.validateForRoot(dataRoot));
    }

    private static ServerResourceLocator resource() {
        return new ServerResourceLocator(SERVER, TYPE, "lifecycle");
    }

    private static ProtocolEnvelope<Map<String, Object>> envelope(ResourceOperation operation) {
        ServerResourceLocator resource = switch (operation) {
            case ResourceSubscribeRequest subscribe -> subscribe.resource();
            default -> throw new IllegalArgumentException("Unsupported test operation");
        };
        return new ProtocolEnvelope<>(ProtocolEnvelope.Kind.REQUEST, new CatalogVersion(1, 0), UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
            SERVER, resource, 0, null, ContractRef.of(new OwnerId("restudio.resync"), new OperationId("resource.subscribe")),
            Set.of(ContractRef.of(new OwnerId("restudio.resync"), new CapabilityId("resources"))),
            ContractRef.of(new OwnerId("restudio.resync"), new ResourceTypeId("resource.document")), null, null, false, null, null, null, null, null, 0,
            ProtocolEnvelope.Status.ACCEPTED, List.of(), Map.of(), new ProtocolBody.ResourceRequest(operation));
    }

    private static ResourceOperation operation(ProtocolEnvelope<Map<String, Object>> envelope) {
        return ((ProtocolBody.ResourceRequest) envelope.body()).operation();
    }

    private static ConnectionInfo authenticatedConnection() {
        ConnectionInfo connection = new ConnectionInfo(null, new FrameSender() {
            @Override
            public void send(byte[] frame) {
            }

            @Override
            public void close(int code, String reason) {
            }
        }, 1);
        connection.setClientId("client");
        connection.setClientVersion("2.1.0");
        connection.setState(ConnectionState.AUTHENTICATED);
        connection.setProtocolResourceAccess(true);
        return connection;
    }

    private static Session session(ConnectionInfo connection) {
        return new Session("session", "client", connection, new ClientIdentity("client", "2.1.0"));
    }
}
