package restudio.resync.migration;

import java.util.Objects;

public final class ManagedFlowFileStoreContract {
    public static final String OWNER = "resync.flow.files";
    public static final String SCHEMA = "resync-managed-flow-files";
    public static final int FORMAT_VERSION = 1;
    public static final int CONTRACT_VERSION = 1;
    public static final int WRITER_VERSION = 1;
    public static final String ROOT_DIRECTORY = "flow-files";
    public static final String DATABASE_FILE = "managed-files.db";
    public static final String METADATA_TABLE = "managed_flow_file_store_metadata";
    public static final String METADATA_SCHEMA_COLUMN = "schema_id";
    public static final String METADATA_FORMAT_COLUMN = "format_version";
    public static final String METADATA_WRITER_COLUMN = "writer_id";
    public static final String METADATA_WRITER_VERSION_COLUMN = "writer_version";
    public static final String METADATA_ORIGIN_COLUMN = "origin";
    public static final String METADATA_CONTRACT_VERSION_COLUMN = "contract_version";
    public static final String METADATA_INSTALL_IDENTITY_COLUMN = "install_identity";
    public static final String METADATA_SOURCE_SNAPSHOT_COLUMN = "source_snapshot_identity";
    public static final String METADATA_SOURCE_INSTALL_COLUMN = "source_install_identity";
    public static final String METADATA_SOURCE_ARCHIVE_COLUMN = "source_archive_identity";
    public static final String METADATA_MIGRATION_ID_COLUMN = "migration_id";
    public static final String METADATA_COMPLETION_AUTHORITY_HASH_COLUMN = "completion_authority_hash";
    public static final String METADATA_COMPLETION_HASH_COLUMN = "completion_hash";
    public static final String FILES_TABLE = "managed_files";
    public static final String FILES_PATH_COLUMN = "path";
    public static final String FILES_KIND_COLUMN = "kind";
    public static final String FILES_CONTENT_COLUMN = "content";

    private ManagedFlowFileStoreContract() {
    }

    public static void requireCompatible(String schema, int formatVersion, String writer) {
        if (!SCHEMA.equals(Objects.requireNonNull(schema, "schema"))
            || formatVersion != FORMAT_VERSION
            || !OWNER.equals(Objects.requireNonNull(writer, "writer"))) {
            throw new IllegalArgumentException("Managed Flow File Store Format Is Not Supported");
        }
    }

    public static boolean compatible(String schema, int formatVersion, String writer) {
        return SCHEMA.equals(schema) && formatVersion == FORMAT_VERSION && OWNER.equals(writer);
    }

    public static String createFilesTableSql() {
        return "CREATE TABLE IF NOT EXISTS " + FILES_TABLE + " ("
            + "path TEXT PRIMARY KEY NOT NULL,"
            + "kind TEXT NOT NULL CHECK(kind IN ('FILE', 'DIRECTORY')),"
            + "content BLOB NOT NULL"
            + ")";
    }

    public static String createMetadataTableSql() {
        return "CREATE TABLE IF NOT EXISTS " + METADATA_TABLE + " ("
            + "id INTEGER PRIMARY KEY CHECK(id = 1),"
            + "schema_id TEXT NOT NULL,"
            + "format_version INTEGER NOT NULL,"
            + "writer_id TEXT NOT NULL,"
            + "writer_version INTEGER NOT NULL,"
            + "origin TEXT NOT NULL,"
            + "contract_version INTEGER NOT NULL,"
            + "install_identity TEXT NOT NULL,"
            + "source_snapshot_identity TEXT,"
            + "source_install_identity TEXT,"
            + "source_archive_identity TEXT,"
            + "migration_id TEXT,"
            + "completion_authority_hash TEXT,"
            + "completion_hash TEXT"
            + ")";
    }
}
