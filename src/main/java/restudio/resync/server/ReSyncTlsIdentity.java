package restudio.resync.server;

import org.bouncycastle.asn1.x500.X500Name;
import org.bouncycastle.asn1.x500.X500NameBuilder;
import org.bouncycastle.asn1.x500.style.BCStyle;
import org.bouncycastle.asn1.x509.BasicConstraints;
import org.bouncycastle.asn1.x509.ExtendedKeyUsage;
import org.bouncycastle.asn1.x509.Extension;
import org.bouncycastle.asn1.x509.GeneralName;
import org.bouncycastle.asn1.x509.GeneralNames;
import org.bouncycastle.asn1.x509.KeyPurposeId;
import org.bouncycastle.asn1.x509.KeyUsage;
import org.bouncycastle.cert.X509CertificateHolder;
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter;
import org.bouncycastle.cert.jcajce.JcaX509ExtensionUtils;
import org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder;
import org.bouncycastle.operator.ContentSigner;
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder;

import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.math.BigInteger;
import java.net.InetAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.PosixFilePermission;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.KeyStore;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.security.cert.X509Certificate;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.Collection;
import java.util.Date;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Properties;
import java.util.Set;

public final class ReSyncTlsIdentity {
    private static final String ALIAS = "resync-server";
    private static final String KEY_STORE_FILE = "resync-server.p12";
    private static final String PASSWORD_FILE = "resync-server.password";
    private static final String ROTATION_FILE = "resync-server.rotation.properties";
    private static final SecureRandom RANDOM = new SecureRandom();
    private static final Set<PosixFilePermission> PRIVATE_PERMISSIONS = Set.of(
            PosixFilePermission.OWNER_READ,
            PosixFilePermission.OWNER_WRITE
    );

    private ReSyncTlsIdentity() {
    }

    public static Prepared prepare(Path dataDirectory, ReSyncConfig.TlsConfig config) throws Exception {
        if (config == null || !config.isEnabled()) {
            throw new IllegalArgumentException("ReSync TLS Is Not Enabled");
        }
        Path root = dataDirectory.toAbsolutePath().normalize();
        Files.createDirectories(root);
        Path metadataFile = resolveInside(root, config.getRuntimeMetadataFile());
        Path identityDirectory = metadataFile.getParent();
        if (identityDirectory == null) {
            throw new IllegalArgumentException("ReSync TLS Runtime Metadata Path Is Invalid");
        }
        Files.createDirectories(identityDirectory);
        Path realRoot = root.toRealPath();
        if (!identityDirectory.toRealPath().startsWith(realRoot)) {
            throw new IllegalArgumentException("ReSync TLS Runtime Metadata Must Stay Inside The Plugin Directory");
        }
        Files.deleteIfExists(metadataFile);

        List<SubjectAlternativeName> subjectAlternativeNames = normalizedNames(config.getSubjectAlternativeNames());
        if (subjectAlternativeNames.isEmpty()) {
            throw new IllegalArgumentException("ReSync TLS Subject Alternative Names Are Required");
        }

        Path keyStoreFile = identityDirectory.resolve(KEY_STORE_FILE);
        Path passwordFile = identityDirectory.resolve(PASSWORD_FILE);
        Path rotationFile = identityDirectory.resolve(ROTATION_FILE);
        String configuredFingerprint = normalizeFingerprint(config.getSpkiFingerprint());
        Identity identity = null;
        boolean pendingRotation = false;
        if (Files.isRegularFile(keyStoreFile) && Files.isRegularFile(passwordFile)) {
            Identity existing = null;
            try {
                existing = load(keyStoreFile, passwordFile);
                if (validForReuse(existing.certificate())
                        && Set.copyOf(subjectAlternativeNames).equals(Set.copyOf(certificateNames(existing.certificate())))) {
                    String currentFingerprint = spkiFingerprint(existing.certificate());
                    if (configuredFingerprint == null || configuredFingerprint.equals(currentFingerprint)) {
                        identity = existing;
                    } else if (rotationMatches(rotationFile, configuredFingerprint, currentFingerprint, sha256(existing.keyStoreBytes()))) {
                        identity = existing;
                        pendingRotation = true;
                    }
                }
            } catch (Exception ignored) {
                identity = null;
            } finally {
                if (existing != null && identity != existing) {
                    clear(existing.password());
                    clear(existing.passwordBytes());
                    clear(existing.keyStoreBytes());
                }
            }
        }
        boolean created = identity == null;
        if (identity == null) {
            identity = create(subjectAlternativeNames);
        }

        try {
            Metadata metadata = metadata(identity.certificate(), identity.keyStoreBytes());
            if (created) {
                writePrivate(passwordFile, Base64.getEncoder().encode(identity.passwordBytes()));
                writePrivate(keyStoreFile, identity.keyStoreBytes());
                if (configuredFingerprint == null) {
                    Files.deleteIfExists(rotationFile);
                } else {
                    writeRotation(rotationFile, configuredFingerprint, metadata);
                }
            } else if (!pendingRotation) {
                Files.deleteIfExists(rotationFile);
            }
            return new Prepared(sslContext(identity.keyStore(), identity.password()), identity.certificate(), metadata, metadataFile);
        } finally {
            clear(identity.password());
            clear(identity.passwordBytes());
            clear(identity.keyStoreBytes());
        }
    }

    public static void publish(Prepared prepared) throws Exception {
        if (prepared == null) {
            throw new IllegalArgumentException("ReSync TLS Identity Is Required");
        }
        writeMetadata(prepared.metadataFile(), prepared.metadata());
    }

    public static void withdraw(Prepared prepared) throws Exception {
        if (prepared != null) {
            Files.deleteIfExists(prepared.metadataFile());
        }
    }

    static String normalizeFingerprint(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        String normalized = value.strip();
        if (normalized.regionMatches(true, 0, "sha256/", 0, "sha256/".length())) {
            normalized = normalized.substring("sha256/".length());
        } else if (normalized.regionMatches(true, 0, "sha-256/", 0, "sha-256/".length())) {
            normalized = normalized.substring("sha-256/".length());
        }
        String compactHex = normalized.replace(":", "").replace("-", "").replace(" ", "");
        if (compactHex.matches("[0-9a-fA-F]{64}")) {
            return compactHex.toLowerCase(Locale.ROOT);
        }
        try {
            byte[] decoded = Base64.getDecoder().decode(normalized);
            if (decoded.length == 32) {
                return HexFormat.of().formatHex(decoded);
            }
        } catch (IllegalArgumentException ignored) {
        }
        throw new IllegalArgumentException("ReSync TLS SPKI Fingerprint Is Invalid");
    }

    private static Path resolveInside(Path root, String configuredPath) {
        if (configuredPath == null || configuredPath.isBlank()) {
            throw new IllegalArgumentException("ReSync TLS Runtime Metadata Path Is Required");
        }
        Path relative = Path.of(configuredPath.strip());
        if (relative.isAbsolute()) {
            throw new IllegalArgumentException("ReSync TLS Runtime Metadata Must Use A Relative Path");
        }
        Path resolved = root.resolve(relative).normalize();
        if (!resolved.startsWith(root)) {
            throw new IllegalArgumentException("ReSync TLS Runtime Metadata Must Stay Inside The Plugin Directory");
        }
        return resolved;
    }

    private static Identity load(Path keyStoreFile, Path passwordFile) throws Exception {
        byte[] passwordBytes = Base64.getDecoder().decode(Files.readAllBytes(passwordFile));
        if (passwordBytes.length != 32) {
            clear(passwordBytes);
            throw new IllegalArgumentException("ReSync TLS Identity Password Is Invalid");
        }
        char[] password = Base64.getEncoder().encodeToString(passwordBytes).toCharArray();
        byte[] keyStoreBytes = null;
        try {
            keyStoreBytes = Files.readAllBytes(keyStoreFile);
            KeyStore keyStore = KeyStore.getInstance("PKCS12");
            keyStore.load(new ByteArrayInputStream(keyStoreBytes), password);
            if (!keyStore.isKeyEntry(ALIAS) || !(keyStore.getCertificate(ALIAS) instanceof X509Certificate certificate)) {
                throw new IllegalArgumentException("ReSync TLS Identity Is Invalid");
            }
            return new Identity(keyStore, certificate, keyStoreBytes, password, passwordBytes);
        } catch (Exception exception) {
            clear(password);
            clear(passwordBytes);
            clear(keyStoreBytes);
            throw exception;
        }
    }

    private static Identity create(List<SubjectAlternativeName> subjectAlternativeNames) throws Exception {
        KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
        generator.initialize(2048, RANDOM);
        KeyPair keyPair = generator.generateKeyPair();
        X509Certificate certificate = certificate(keyPair, subjectAlternativeNames);
        byte[] passwordBytes = new byte[32];
        RANDOM.nextBytes(passwordBytes);
        char[] password = Base64.getEncoder().encodeToString(passwordBytes).toCharArray();
        KeyStore keyStore = KeyStore.getInstance("PKCS12");
        keyStore.load(null, password);
        keyStore.setKeyEntry(ALIAS, keyPair.getPrivate(), password, new X509Certificate[]{certificate});
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        keyStore.store(output, password);
        return new Identity(keyStore, certificate, output.toByteArray(), password, passwordBytes);
    }

    private static boolean rotationMatches(Path path, String sourceFingerprint, String candidateFingerprint, String revision) {
        if (!Files.isRegularFile(path)) {
            return false;
        }
        try {
            Properties properties = new Properties();
            try (var input = Files.newInputStream(path)) {
                properties.load(input);
            }
            return sourceFingerprint.equals(normalizeFingerprint(properties.getProperty("sourceFingerprint")))
                    && candidateFingerprint.equals(normalizeFingerprint(properties.getProperty("candidateFingerprint")))
                    && revision.equals(properties.getProperty("keyStoreRevision", "").strip().toLowerCase(Locale.ROOT));
        } catch (Exception ignored) {
            return false;
        }
    }

    private static void writeRotation(Path path, String sourceFingerprint, Metadata metadata) throws Exception {
        String state = "sourceFingerprint=%s%ncandidateFingerprint=%s%nkeyStoreRevision=%s%n"
                .formatted(sourceFingerprint, metadata.spkiFingerprint(), metadata.keyStoreRevision());
        writePrivate(path, state.getBytes(StandardCharsets.UTF_8));
    }

    private static X509Certificate certificate(KeyPair keyPair, List<SubjectAlternativeName> names) throws Exception {
        Instant now = Instant.now();
        X500Name subject = new X500NameBuilder(BCStyle.INSTANCE)
                .addRDN(BCStyle.CN, names.getFirst().value())
                .build();
        JcaX509v3CertificateBuilder builder = new JcaX509v3CertificateBuilder(
                subject,
                new BigInteger(160, RANDOM).abs().add(BigInteger.ONE),
                Date.from(now.minus(Duration.ofMinutes(5))),
                Date.from(now.plus(Duration.ofDays(365))),
                subject,
                keyPair.getPublic()
        );
        JcaX509ExtensionUtils extensions = new JcaX509ExtensionUtils();
        builder.addExtension(Extension.basicConstraints, true, new BasicConstraints(false));
        builder.addExtension(Extension.keyUsage, true, new KeyUsage(KeyUsage.digitalSignature | KeyUsage.keyEncipherment));
        builder.addExtension(Extension.extendedKeyUsage, false, new ExtendedKeyUsage(KeyPurposeId.id_kp_serverAuth));
        builder.addExtension(Extension.subjectKeyIdentifier, false, extensions.createSubjectKeyIdentifier(keyPair.getPublic()));
        builder.addExtension(Extension.authorityKeyIdentifier, false, extensions.createAuthorityKeyIdentifier(keyPair.getPublic()));
        GeneralName[] alternativeNames = names.stream().map(ReSyncTlsIdentity::generalName).toArray(GeneralName[]::new);
        builder.addExtension(Extension.subjectAlternativeName, false, new GeneralNames(alternativeNames));
        ContentSigner signer = new JcaContentSignerBuilder("SHA256withRSA").build(keyPair.getPrivate());
        X509CertificateHolder holder = builder.build(signer);
        X509Certificate certificate = new JcaX509CertificateConverter().getCertificate(holder);
        certificate.verify(keyPair.getPublic());
        certificate.checkValidity();
        return certificate;
    }

    private static GeneralName generalName(SubjectAlternativeName name) {
        return new GeneralName(name.type().generalNameType(), name.value());
    }

    private static List<SubjectAlternativeName> normalizedNames(List<String> values) {
        if (values == null) {
            return List.of();
        }
        List<SubjectAlternativeName> names = new ArrayList<>();
        for (String raw : values) {
            if (raw == null || raw.isBlank()) {
                continue;
            }
            SubjectAlternativeName name = parseName(raw.strip());
            if (!names.contains(name)) {
                names.add(name);
            }
        }
        return List.copyOf(names);
    }

    private static SubjectAlternativeName parseName(String value) {
        if (value.regionMatches(true, 0, "DNS:", 0, "DNS:".length())) {
            return new SubjectAlternativeName(SubjectAlternativeNameType.DNS, normalizeDns(value.substring("DNS:".length())));
        }
        if (value.regionMatches(true, 0, "IP:", 0, "IP:".length())) {
            return new SubjectAlternativeName(SubjectAlternativeNameType.IP, normalizeIp(value.substring("IP:".length())));
        }
        if (looksLikeIp(value)) {
            return new SubjectAlternativeName(SubjectAlternativeNameType.IP, normalizeIp(value));
        }
        return new SubjectAlternativeName(SubjectAlternativeNameType.DNS, normalizeDns(value));
    }

    private static String normalizeDns(String raw) {
        String value = raw == null ? "" : raw.strip().toLowerCase(Locale.ROOT);
        if (value.isBlank() || value.length() > 253 || value.startsWith(".") || value.endsWith(".") || value.contains("*")
                || looksLikeIp(value)) {
            throw new IllegalArgumentException("ReSync TLS DNS Subject Alternative Name Is Invalid: " + raw);
        }
        String[] labels = value.split("\\.", -1);
        for (String label : labels) {
            if (label.isBlank() || label.length() > 63 || !label.matches("[a-z0-9](?:[a-z0-9-]*[a-z0-9])?")) {
                throw new IllegalArgumentException("ReSync TLS DNS Subject Alternative Name Is Invalid: " + raw);
            }
        }
        return value;
    }

    private static String normalizeIp(String raw) {
        String value = raw == null ? "" : raw.strip();
        if (!looksLikeIp(value) || value.contains("%") || value.startsWith("[") || value.endsWith("]")) {
            throw new IllegalArgumentException("ReSync TLS IP Subject Alternative Name Is Invalid: " + raw);
        }
        if (value.indexOf(':') < 0) {
            String[] octets = value.split("\\.", -1);
            if (octets.length != 4) {
                throw new IllegalArgumentException("ReSync TLS IP Subject Alternative Name Is Invalid: " + raw);
            }
            List<String> normalized = new ArrayList<>();
            for (String octet : octets) {
                if (!octet.matches("[0-9]{1,3}")) {
                    throw new IllegalArgumentException("ReSync TLS IP Subject Alternative Name Is Invalid: " + raw);
                }
                int number = Integer.parseInt(octet);
                if (number > 255) {
                    throw new IllegalArgumentException("ReSync TLS IP Subject Alternative Name Is Invalid: " + raw);
                }
                normalized.add(Integer.toString(number));
            }
            return String.join(".", normalized);
        }
        try {
            InetAddress address = InetAddress.getByName(value);
            if (address.getAddress().length != 16) {
                throw new IllegalArgumentException("ReSync TLS IP Subject Alternative Name Is Invalid: " + raw);
            }
            return address.getHostAddress();
        } catch (Exception exception) {
            throw new IllegalArgumentException("ReSync TLS IP Subject Alternative Name Is Invalid: " + raw, exception);
        }
    }

    private static boolean looksLikeIp(String value) {
        return value != null && (value.indexOf(':') >= 0 || value.matches("[0-9]{1,3}(\\.[0-9]{1,3}){3}"));
    }

    private static List<SubjectAlternativeName> certificateNames(X509Certificate certificate) throws Exception {
        Collection<List<?>> values = certificate.getSubjectAlternativeNames();
        if (values == null) {
            return List.of();
        }
        List<SubjectAlternativeName> names = new ArrayList<>();
        for (List<?> value : values) {
            if (value.size() < 2 || !(value.getFirst() instanceof Integer type) || !(value.get(1) instanceof String name)) {
                continue;
            }
            if (type == GeneralName.dNSName) {
                SubjectAlternativeName normalized = new SubjectAlternativeName(SubjectAlternativeNameType.DNS, normalizeDns(name));
                if (!names.contains(normalized)) {
                    names.add(normalized);
                }
            } else if (type == GeneralName.iPAddress) {
                SubjectAlternativeName normalized = new SubjectAlternativeName(SubjectAlternativeNameType.IP, normalizeIp(name));
                if (!names.contains(normalized)) {
                    names.add(normalized);
                }
            }
        }
        return List.copyOf(names);
    }

    private static boolean validForReuse(X509Certificate certificate) {
        try {
            certificate.checkValidity(Date.from(Instant.now().plus(Duration.ofDays(7))));
            return true;
        } catch (Exception exception) {
            return false;
        }
    }

    private static SSLContext sslContext(KeyStore keyStore, char[] password) throws Exception {
        KeyManagerFactory keyManagers = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
        keyManagers.init(keyStore, password);
        SSLContext context = SSLContext.getInstance("TLS");
        context.init(keyManagers.getKeyManagers(), null, RANDOM);
        return context;
    }

    private static Metadata metadata(X509Certificate certificate, byte[] keyStoreBytes) throws Exception {
        String certificateFingerprint = sha256(certificate.getEncoded());
        byte[] spkiDigest = MessageDigest.getInstance("SHA-256").digest(certificate.getPublicKey().getEncoded());
        String spkiFingerprint = HexFormat.of().formatHex(spkiDigest);
        String spkiPin = "sha256/" + Base64.getEncoder().encodeToString(spkiDigest);
        return new Metadata(certificateFingerprint, spkiFingerprint, spkiPin, sha256(keyStoreBytes));
    }

    private static String spkiFingerprint(X509Certificate certificate) throws Exception {
        return sha256(certificate.getPublicKey().getEncoded());
    }

    private static String sha256(byte[] value) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value));
    }

    private static void writeMetadata(Path path, Metadata metadata) throws Exception {
        String json = "{\"format\":\"resync.tls.runtime\",\"version\":1,\"certificateFingerprint\":\"%s\",\"spkiFingerprint\":\"%s\",\"spkiPin\":\"%s\",\"keyStoreRevision\":\"%s\"}%n"
                .formatted(metadata.certificateFingerprint(), metadata.spkiFingerprint(), metadata.spkiPin(), metadata.keyStoreRevision());
        writePrivate(path, json.getBytes(StandardCharsets.UTF_8));
    }

    private static void writePrivate(Path path, byte[] content) throws Exception {
        Files.createDirectories(path.getParent());
        Path temporary = Files.createTempFile(path.getParent(), path.getFileName().toString() + ".", ".tmp");
        try {
            Files.write(temporary, content, StandardOpenOption.TRUNCATE_EXISTING);
            restrict(temporary);
            try {
                Files.move(temporary, path, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException exception) {
                Files.move(temporary, path, StandardCopyOption.REPLACE_EXISTING);
            }
            restrict(path);
        } finally {
            Files.deleteIfExists(temporary);
        }
    }

    private static void restrict(Path path) throws Exception {
        try {
            Files.setPosixFilePermissions(path, PRIVATE_PERMISSIONS);
        } catch (UnsupportedOperationException ignored) {
        }
    }

    private static void clear(char[] value) {
        if (value != null) {
            Arrays.fill(value, '\0');
        }
    }

    private static void clear(byte[] value) {
        if (value != null) {
            Arrays.fill(value, (byte) 0);
        }
    }

    private record Identity(KeyStore keyStore, X509Certificate certificate, byte[] keyStoreBytes, char[] password, byte[] passwordBytes) {
    }

    private enum SubjectAlternativeNameType {
        DNS(GeneralName.dNSName),
        IP(GeneralName.iPAddress);

        private final int generalNameType;

        SubjectAlternativeNameType(int generalNameType) {
            this.generalNameType = generalNameType;
        }

        private int generalNameType() {
            return generalNameType;
        }
    }

    private record SubjectAlternativeName(SubjectAlternativeNameType type, String value) {
    }

    public record Metadata(String certificateFingerprint, String spkiFingerprint, String spkiPin, String keyStoreRevision) {
    }

    public record Prepared(SSLContext sslContext, X509Certificate certificate, Metadata metadata, Path metadataFile) {
    }
}
