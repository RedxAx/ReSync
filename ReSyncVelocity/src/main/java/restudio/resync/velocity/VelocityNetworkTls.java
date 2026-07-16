package restudio.resync.velocity;

import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManager;
import javax.net.ssl.TrustManagerFactory;
import java.io.InputStream;
import java.nio.file.Files;
import java.security.KeyStore;
import java.security.SecureRandom;

public final class VelocityNetworkTls {
    private VelocityNetworkTls() {
    }

    public static SSLContext create(VelocityNetworkConfig.Tls config) throws Exception {
        KeyStore keyStore = KeyStore.getInstance("PKCS12");
        char[] keyPassword = config.keyStorePassword().toCharArray();
        try (InputStream input = Files.newInputStream(config.keyStore())) {
            keyStore.load(input, keyPassword);
        }
        KeyManagerFactory keys = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
        keys.init(keyStore, keyPassword);
        TrustManager[] trustManagers = null;
        if (config.trustStore() != null) {
            KeyStore trustStore = KeyStore.getInstance("PKCS12");
            char[] trustPassword = config.trustStorePassword().toCharArray();
            try (InputStream input = Files.newInputStream(config.trustStore())) {
                trustStore.load(input, trustPassword);
            }
            TrustManagerFactory trust = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
            trust.init(trustStore);
            trustManagers = trust.getTrustManagers();
        }
        SSLContext context = SSLContext.getInstance("TLSv1.3");
        context.init(keys.getKeyManagers(), trustManagers, new SecureRandom());
        return context;
    }
}
