package restudio.resync.worldgen.datapack;

import restudio.resync.migration.MigrationFence;

import java.io.IOException;

public interface WorldGenInstalledDatapackCapability extends AutoCloseable {
    String OWNER = "resync.worldgen.installed-datapack";

    WorldGenDatapackInstaller.InstallResult install(WorldGenDatapackBuild build, String worldName);

    WorldGenDatapackInstaller.InstallResult installPreview(WorldGenDatapackBuild build, String worldName);

    WorldGenDatapackInstaller.InstallResult installWithHandoff(WorldGenDatapackBuild build, String worldName,
                                                               MigrationFence.MutationLease mutation);

    WorldGenDatapackInstaller.InstallResult installPreviewWithHandoff(WorldGenDatapackBuild build, String worldName,
                                                                      MigrationFence.MutationLease mutation);

    State state();

    boolean available();

    String failureReason();

    boolean admissionOpen();

    int activeOperationCount();

    void flush() throws IOException;

    void quiesce() throws IOException;

    void resume() throws IOException;

    void healthCheck() throws IOException;

    @Override
    void close() throws IOException;

    enum State {
        OPEN,
        QUIESCING,
        QUIESCED,
        FAILED,
        CLOSED
    }
}
