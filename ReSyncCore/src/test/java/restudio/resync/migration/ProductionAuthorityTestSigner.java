package restudio.resync.migration;

import restudio.resync.flow.identity.ServerId;

import java.io.IOException;
import java.nio.file.Path;
import java.security.GeneralSecurityException;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.Signature;
import java.util.Base64;
import java.util.Objects;

final class ProductionAuthorityTestSigner implements ProductionAuthoritySigner {
    private final ServerId serverId;
    private final Path dataRoot;
    private final KeyPair keyPair;

    ProductionAuthorityTestSigner(ServerId serverId, Path dataRoot) throws GeneralSecurityException {
        this.serverId = Objects.requireNonNull(serverId, "serverId");
        this.dataRoot = Objects.requireNonNull(dataRoot, "dataRoot");
        this.keyPair = KeyPairGenerator.getInstance(ProductionAuthorityBundle.SIGNATURE_ALGORITHM).generateKeyPair();
    }

    @Override
    public ServerId serverId() {
        return serverId;
    }

    @Override
    public String installAuthorityHash() throws IOException {
        return ProductionAuthorityBundle.installAuthorityDigest(dataRoot);
    }

    @Override
    public String signingPublicKey() {
        return Base64.getEncoder().encodeToString(keyPair.getPublic().getEncoded());
    }

    @Override
    public String sign(byte[] canonicalPayload) throws IOException {
        try {
            Signature signature = Signature.getInstance(ProductionAuthorityBundle.SIGNATURE_ALGORITHM);
            signature.initSign(keyPair.getPrivate());
            signature.update(canonicalPayload);
            return Base64.getEncoder().encodeToString(signature.sign());
        } catch (GeneralSecurityException exception) {
            throw new IOException("Test Authority Signature Is Unavailable", exception);
        }
    }
}
