package de.cloudnetwork.tls;

import de.cloudnetwork.console.ConsoleOutput;
import de.cloudnetwork.database.DatabaseManager;
import org.bouncycastle.asn1.x509.BasicConstraints;
import org.bouncycastle.asn1.x509.Extension;
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter;
import org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder;
import org.bouncycastle.operator.ContentSigner;
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder;

import javax.net.ssl.*;
import java.io.ByteArrayInputStream;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.security.*;
import java.security.cert.Certificate;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.security.spec.PKCS8EncodedKeySpec;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Base64;
import java.util.Date;

/**
 * Manages mTLS certificates for the CloudNetwork internal protocol.
 *
 * <p>On the first gateway start a self-signed CA is generated together with a
 * server certificate (used by the Gateway) and a shared client certificate
 * (used by Workers and ProxyGateways).  All material is stored as DB config
 * values so that every component can fetch its certificates from the database
 * at runtime without needing local files.</p>
 *
 * <p>DB keys used:</p>
 * <ul>
 *   <li>{@value #KEY_CA_CERT} – CA certificate (PEM)</li>
 *   <li>{@value #KEY_CA_KEY}  – CA private key (PKCS#8 DER, Base64)</li>
 *   <li>{@value #KEY_SERVER_CERT} – Gateway server certificate (PEM)</li>
 *   <li>{@value #KEY_SERVER_KEY}  – Gateway server key (PKCS#8 DER, Base64)</li>
 *   <li>{@value #KEY_CLIENT_CERT} – Shared client certificate (PEM)</li>
 *   <li>{@value #KEY_CLIENT_KEY}  – Shared client key (PKCS#8 DER, Base64)</li>
 * </ul>
 */
public class TlsManager {

    public static final String KEY_CA_CERT     = "tls_ca_cert";
    public static final String KEY_CA_KEY      = "tls_ca_key";
    public static final String KEY_SERVER_CERT = "tls_server_cert";
    public static final String KEY_SERVER_KEY  = "tls_server_key";
    public static final String KEY_CLIENT_CERT = "tls_client_cert";
    public static final String KEY_CLIENT_KEY  = "tls_client_key";

    /** Validity period of the self-signed CA certificate. */
    private static final int CA_VALIDITY_DAYS = 3650; // 10 years
    /** Validity period of server and client leaf certificates. */
    private static final int LEAF_VALIDITY_DAYS = 1825; // 5 years

    /**
     * In-memory KeyStore / TrustStore password.
     * This only protects the transient, heap-resident KeyStore object.
     * The actual security boundary is the database access control under which
     * the raw key material is stored.
     */
    private static final char[] KS_PASS = "cloudnetwork-tls-internal".toCharArray();

    private TlsManager() {
    }

    // ── Public API ────────────────────────────────────────────────────────────

    /**
     * Called by the Master (Gateway) on startup.
     * Generates a CA, server certificate, and shared client certificate the
     * first time, storing everything in the database.
     * Subsequent calls are no-ops when the CA cert already exists.
     */
    public static void ensureCertificatesExist(DatabaseManager db) throws Exception {
        String existing = db.getConfigValue(KEY_CA_CERT);
        if (existing != null && !existing.isBlank()) {
            ConsoleOutput.info("[TLS] TLS-Zertifikate bereits vorhanden.");
            return;
        }
        ConsoleOutput.info("[TLS] Generiere TLS-Zertifikate (CA, Server, Client)...");

        KeyPair caKp     = generateKeyPair();
        X509Certificate caCert = buildCACert(caKp);

        KeyPair serverKp = generateKeyPair();
        X509Certificate serverCert = buildLeafCert(serverKp.getPublic(), caKp.getPrivate(),
                caCert, "CN=cloudnetwork-gateway, O=CloudNetwork");

        KeyPair clientKp = generateKeyPair();
        X509Certificate clientCert = buildLeafCert(clientKp.getPublic(), caKp.getPrivate(),
                caCert, "CN=cloudnetwork-client, O=CloudNetwork");

        db.setConfigValue(KEY_CA_CERT,     certToPem(caCert));
        db.setConfigValue(KEY_CA_KEY,      keyToBase64(caKp.getPrivate()));
        db.setConfigValue(KEY_SERVER_CERT, certToPem(serverCert));
        db.setConfigValue(KEY_SERVER_KEY,  keyToBase64(serverKp.getPrivate()));
        db.setConfigValue(KEY_CLIENT_CERT, certToPem(clientCert));
        db.setConfigValue(KEY_CLIENT_KEY,  keyToBase64(clientKp.getPrivate()));

        ConsoleOutput.info("[TLS] TLS-Zertifikate erfolgreich generiert und in DB gespeichert.");
    }

    /**
     * Deletes all TLS certificate DB entries so they are regenerated on the
     * next gateway start.  All workers / proxy-gateways must be restarted
     * after a renew so they pick up the new client certificate.
     */
    public static void renewCertificates(DatabaseManager db) throws Exception {
        for (String key : new String[]{KEY_CA_CERT, KEY_CA_KEY,
                KEY_SERVER_CERT, KEY_SERVER_KEY,
                KEY_CLIENT_CERT, KEY_CLIENT_KEY}) {
            db.setConfigValue(key, "");
        }
        ConsoleOutput.info("[TLS] TLS-Zertifikate gelöscht. Starte den Gateway neu, um neue zu generieren.");
    }

    /**
     * Builds an {@link SSLContext} for the Gateway (server side).
     * The server presents its certificate and <em>requires</em> a valid
     * client certificate signed by the same CA (mTLS).
     */
    public static SSLContext createServerSSLContext(DatabaseManager db) throws Exception {
        X509Certificate caCert     = loadCert(db.getConfigValue(KEY_CA_CERT));
        X509Certificate serverCert = loadCert(db.getConfigValue(KEY_SERVER_CERT));
        PrivateKey      serverKey  = loadKey(db.getConfigValue(KEY_SERVER_KEY));

        KeyStore ks = buildKeyStore(serverKey, serverCert, caCert);
        KeyStore ts = buildTrustStore(caCert);
        return buildSSLContext(ks, ts);
    }

    /**
     * Builds an {@link SSLContext} for Workers and ProxyGateways (client side).
     * The client presents the shared client certificate and trusts only the
     * internal CloudNetwork CA.
     */
    public static SSLContext createClientSSLContext(DatabaseManager db) throws Exception {
        X509Certificate caCert     = loadCert(db.getConfigValue(KEY_CA_CERT));
        X509Certificate clientCert = loadCert(db.getConfigValue(KEY_CLIENT_CERT));
        PrivateKey      clientKey  = loadKey(db.getConfigValue(KEY_CLIENT_KEY));

        KeyStore ks = buildKeyStore(clientKey, clientCert, caCert);
        KeyStore ts = buildTrustStore(caCert);
        return buildSSLContext(ks, ts);
    }

    /**
     * Returns a human-readable summary of the stored TLS certificates,
     * including validity periods.
     */
    public static String getCertificateStatus(DatabaseManager db) throws Exception {
        StringBuilder sb = new StringBuilder();
        appendCertInfo(sb, "CA-Zertifikat",     db.getConfigValue(KEY_CA_CERT));
        appendCertInfo(sb, "Server-Zertifikat", db.getConfigValue(KEY_SERVER_CERT));
        appendCertInfo(sb, "Client-Zertifikat", db.getConfigValue(KEY_CLIENT_CERT));
        return sb.toString().stripTrailing();
    }

    // ── Certificate generation ────────────────────────────────────────────────

    private static KeyPair generateKeyPair() throws Exception {
        KeyPairGenerator kpg = KeyPairGenerator.getInstance("RSA");
        kpg.initialize(2048, new SecureRandom());
        return kpg.generateKeyPair();
    }

    private static X509Certificate buildCACert(KeyPair caKp) throws Exception {
        javax.security.auth.x500.X500Principal caName =
                new javax.security.auth.x500.X500Principal("CN=CloudNetwork CA, O=CloudNetwork");
        BigInteger serial   = BigInteger.valueOf(1L);
        Instant now = Instant.now();
        Date notBefore = Date.from(now);
        Date notAfter  = Date.from(now.plus(CA_VALIDITY_DAYS, ChronoUnit.DAYS));

        JcaX509v3CertificateBuilder builder = new JcaX509v3CertificateBuilder(
                caName, serial, notBefore, notAfter, caName, caKp.getPublic());
        builder.addExtension(Extension.basicConstraints, true, new BasicConstraints(true));

        ContentSigner signer = new JcaContentSignerBuilder("SHA256WithRSAEncryption")
                .build(caKp.getPrivate());
        return new JcaX509CertificateConverter().getCertificate(builder.build(signer));
    }

    private static X509Certificate buildLeafCert(PublicKey publicKey,
                                                   PrivateKey caKey,
                                                   X509Certificate caCert,
                                                   String subjectDN) throws Exception {
        javax.security.auth.x500.X500Principal subject =
                new javax.security.auth.x500.X500Principal(subjectDN);
        BigInteger serial   = BigInteger.valueOf(System.currentTimeMillis());
        Instant now = Instant.now();
        Date notBefore = Date.from(now);
        Date notAfter  = Date.from(now.plus(LEAF_VALIDITY_DAYS, ChronoUnit.DAYS));

        JcaX509v3CertificateBuilder builder = new JcaX509v3CertificateBuilder(
                caCert, serial, notBefore, notAfter, subject, publicKey);
        builder.addExtension(Extension.basicConstraints, false, new BasicConstraints(false));

        ContentSigner signer = new JcaContentSignerBuilder("SHA256WithRSAEncryption")
                .build(caKey);
        return new JcaX509CertificateConverter().getCertificate(builder.build(signer));
    }

    // ── Serialisation helpers ─────────────────────────────────────────────────

    private static String certToPem(X509Certificate cert) throws Exception {
        String b64 = Base64.getMimeEncoder(64, new byte[]{'\n'})
                .encodeToString(cert.getEncoded());
        return "-----BEGIN CERTIFICATE-----\n" + b64 + "\n-----END CERTIFICATE-----\n";
    }

    private static String keyToBase64(PrivateKey key) {
        return Base64.getEncoder().encodeToString(key.getEncoded());
    }

    private static X509Certificate loadCert(String pem) throws Exception {
        if (pem == null || pem.isBlank()) {
            throw new IllegalStateException(
                    "TLS-Zertifikat fehlt in der DB. Starte zuerst den Gateway-Master.");
        }
        CertificateFactory cf = CertificateFactory.getInstance("X.509");
        return (X509Certificate) cf.generateCertificate(
                new ByteArrayInputStream(pem.getBytes(StandardCharsets.UTF_8)));
    }

    private static PrivateKey loadKey(String base64) throws Exception {
        if (base64 == null || base64.isBlank()) {
            throw new IllegalStateException(
                    "TLS-Schlüssel fehlt in der DB. Starte zuerst den Gateway-Master.");
        }
        byte[] keyBytes = Base64.getDecoder().decode(base64.trim());
        PKCS8EncodedKeySpec spec = new PKCS8EncodedKeySpec(keyBytes);
        return KeyFactory.getInstance("RSA").generatePrivate(spec);
    }

    // ── KeyStore / SSLContext helpers ─────────────────────────────────────────

    private static KeyStore buildKeyStore(PrivateKey key, X509Certificate cert,
                                           X509Certificate caCert) throws Exception {
        KeyStore ks = KeyStore.getInstance("PKCS12");
        ks.load(null, null);
        ks.setKeyEntry("cloudnetwork", key, KS_PASS, new Certificate[]{cert, caCert});
        return ks;
    }

    private static KeyStore buildTrustStore(X509Certificate caCert) throws Exception {
        KeyStore ts = KeyStore.getInstance("PKCS12");
        ts.load(null, null);
        ts.setCertificateEntry("cloudnetwork-ca", caCert);
        return ts;
    }

    private static SSLContext buildSSLContext(KeyStore ks, KeyStore ts) throws Exception {
        KeyManagerFactory kmf = KeyManagerFactory.getInstance(
                KeyManagerFactory.getDefaultAlgorithm());
        kmf.init(ks, KS_PASS);

        TrustManagerFactory tmf = TrustManagerFactory.getInstance(
                TrustManagerFactory.getDefaultAlgorithm());
        tmf.init(ts);

        SSLContext ctx = SSLContext.getInstance("TLS");
        ctx.init(kmf.getKeyManagers(), tmf.getTrustManagers(), new SecureRandom());
        return ctx;
    }

    // ── Status helper ─────────────────────────────────────────────────────────

    private static void appendCertInfo(StringBuilder sb, String label, String pem) {
        sb.append("  ").append(label).append(": ");
        if (pem == null || pem.isBlank()) {
            sb.append("nicht vorhanden\n");
            return;
        }
        try {
            X509Certificate cert = loadCert(pem);
            sb.append("CN=").append(cert.getSubjectX500Principal().getName())
              .append(", gültig bis ").append(cert.getNotAfter()).append('\n');
        } catch (Exception e) {
            sb.append("Fehler beim Lesen: ").append(e.getMessage()).append('\n');
        }
    }
}
