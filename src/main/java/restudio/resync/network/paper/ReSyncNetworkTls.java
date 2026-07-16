package restudio.resync.network.paper;

import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManagerFactory;
import java.io.InputStream;
import java.nio.file.Files;
import java.security.KeyStore;

public final class ReSyncNetworkTls {
    private ReSyncNetworkTls() {
    }

    public static SSLContext create(ReSyncNetworkAgentConfig.Tls config) throws Exception {
        KeyStore trustStore = KeyStore.getInstance("PKCS12");
        try (InputStream input = Files.newInputStream(config.trustStore())) {
            trustStore.load(input, config.trustStorePassword().toCharArray());
        }
        TrustManagerFactory trust = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
        trust.init(trustStore);
        SSLContext context = SSLContext.getInstance("TLSv1.3");
        context.init(null, trust.getTrustManagers(), null);
        return context;
    }
}
