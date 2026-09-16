package restudio.resync.migration;

import restudio.resync.flow.identity.ServerId;

import java.io.IOException;

public interface ProductionAuthoritySigner {
    ServerId serverId();

    String installAuthorityHash() throws IOException;

    String signingPublicKey() throws IOException;

    String sign(byte[] canonicalPayload) throws IOException;
}
