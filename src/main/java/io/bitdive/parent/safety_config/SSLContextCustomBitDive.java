package io.bitdive.parent.safety_config;

import lombok.Getter;
import org.bouncycastle.asn1.pkcs.PrivateKeyInfo;
import org.bouncycastle.asn1.pkcs.RSAPrivateKey;
import org.bouncycastle.jce.provider.BouncyCastleProvider;
import org.bouncycastle.openssl.PEMEncryptedKeyPair;
import org.bouncycastle.openssl.PEMKeyPair;
import org.bouncycastle.openssl.PEMParser;
import org.bouncycastle.openssl.jcajce.JcaPEMKeyConverter;
import org.bouncycastle.openssl.jcajce.JcePEMDecryptorProviderBuilder;
import org.bouncycastle.operator.OperatorCreationException;

import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManagerFactory;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.Reader;
import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.security.KeyStore;
import java.security.PrivateKey;
import java.security.Security;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.util.ArrayList;
import java.util.List;

public class SSLContextCustomBitDive {
    static {
        Security.addProvider(new BouncyCastleProvider());
    }

    @Getter
    private static SSLContext sslContext;
    private static X509Certificate clientCertificate;

    private static void loadCertificatesAndInitializeSSLContext() throws Exception {
        // Load the client certificate from PEM
        VaultGettingConfig.VaultConfigRet vaultConfigSer = VaultGettingConfig.retrieveCertificatesFromVault();
        clientCertificate = loadCertificate(vaultConfigSer.getCertificate());

        // Load the client's private key from PEM
        PrivateKey clientPrivateKey = loadPrivateKey(vaultConfigSer.getPrivateKey());

        // Load the CA certificates from PEM
        List<X509Certificate> caCertificates = loadCACertificates(vaultConfigSer.getCaChain());

        // Create a KeyStore containing the client certificate and private key
        KeyStore keyStore = KeyStore.getInstance("PKCS12");
        keyStore.load(null, null);

        // Create a certificate chain with the client certificate followed by CA certificates
        X509Certificate[] certificateChain = new X509Certificate[caCertificates.size() + 1];
        certificateChain[0] = clientCertificate;
        for (int i = 0; i < caCertificates.size(); i++) {
            certificateChain[i + 1] = caCertificates.get(i);
        }

        // Add the private key and certificate chain to the KeyStore
        keyStore.setKeyEntry("client", clientPrivateKey, "changeit".toCharArray(), certificateChain);

        // Create a TrustStore containing the CA certificates
        KeyStore trustStore = KeyStore.getInstance(KeyStore.getDefaultType());
        trustStore.load(null, null);

        // Add each CA certificate to the TrustStore
        int index = 0;
        for (X509Certificate caCert : caCertificates) {
            trustStore.setCertificateEntry("ca-cert-" + index, caCert);
            index++;
        }

        // Initialize KeyManagerFactory with the KeyStore
        KeyManagerFactory kmf = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
        kmf.init(keyStore, "changeit".toCharArray());

        // Initialize TrustManagerFactory with the TrustStore
        TrustManagerFactory tmf = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
        tmf.init(trustStore);

        // Initialize SSLContext with KeyManagers and TrustManagers
        sslContext = SSLContext.getInstance("TLS");
        sslContext.init(kmf.getKeyManagers(), tmf.getTrustManagers(), null);
    }

    /**
     * Ensures that the certificate is valid. If not, retrieves a new one from Vault.
     */
    public static void ensureValidCertificate() throws Exception {
        if (!isCertificateValid()) {
            VaultGettingConfig.initVaultConnect();
            loadCertificatesAndInitializeSSLContext();
        }
    }

    /**
     * Checks if the current certificate is still valid.
     *
     * @return true if valid, false otherwise
     */
    private static boolean isCertificateValid() {
        try {
            clientCertificate.checkValidity(); // Throws exception if not valid
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * Loads a private key from a PEM-formatted string.
     *
     * @param pem the PEM-formatted private key
     * @return the PrivateKey object
     */
    public static PrivateKey loadPrivateKey(String pem) throws IOException, OperatorCreationException {
        Reader reader = new StringReader(pem);
        PEMParser pemParser = new PEMParser(reader);
        Object object = pemParser.readObject();
        pemParser.close();

        JcaPEMKeyConverter converter = new JcaPEMKeyConverter().setProvider("BC");

        PrivateKey privateKey;

        if (object instanceof PEMEncryptedKeyPair) {
            // If the key is encrypted, decrypt it with the provided password
            PEMEncryptedKeyPair encryptedKeyPair = (PEMEncryptedKeyPair) object;
            PEMKeyPair keyPair = encryptedKeyPair.decryptKeyPair(new JcePEMDecryptorProviderBuilder().build("password".toCharArray())); // Replace "password" with your actual password
            privateKey = converter.getPrivateKey(keyPair.getPrivateKeyInfo());
        } else if (object instanceof PEMKeyPair) {
            // If the key is not encrypted
            PEMKeyPair keyPair = (PEMKeyPair) object;
            privateKey = converter.getPrivateKey(keyPair.getPrivateKeyInfo());
        } else if (object instanceof PrivateKeyInfo) {
            // For PKCS#8 format
            PrivateKeyInfo privateKeyInfo = (PrivateKeyInfo) object;
            privateKey = converter.getPrivateKey(privateKeyInfo);
        } else if (object instanceof RSAPrivateKey) {
            // For PKCS#1 format, convert to PKCS#8
            RSAPrivateKey rsaPrivateKey = (RSAPrivateKey) object;
            PrivateKeyInfo privateKeyInfo = new PrivateKeyInfo(
                    new org.bouncycastle.asn1.x509.AlgorithmIdentifier(org.bouncycastle.asn1.pkcs.PKCSObjectIdentifiers.rsaEncryption, org.bouncycastle.asn1.DERNull.INSTANCE),
                    rsaPrivateKey
            );
            privateKey = converter.getPrivateKey(privateKeyInfo);
        } else {
            throw new IllegalArgumentException("Invalid key format");
        }

        return privateKey;
    }


    /**
     * Loads a single X509 certificate from a PEM-formatted string.
     *
     * @param certificatePEM the PEM-formatted certificate
     * @return the X509Certificate object
     */
    private static X509Certificate loadCertificate(String certificatePEM) throws Exception {
        // Remove any whitespace
        String certificateContent = certificatePEM.trim();

        // Create CertificateFactory
        CertificateFactory certificateFactory = CertificateFactory.getInstance("X.509");

        // Convert PEM to InputStream
        ByteArrayInputStream inputStream = new ByteArrayInputStream(certificateContent.getBytes(StandardCharsets.UTF_8));

        // Generate certificate
        return (X509Certificate) certificateFactory.generateCertificate(inputStream);
    }

    /**
     * Loads a list of CA certificates from a PEM-formatted string.
     *
     * @param caChainPEM the PEM-formatted CA chain
     * @return a list of X509Certificate objects
     */
    private static List<X509Certificate> loadCACertificates(String caChainPEM) throws Exception {
        List<X509Certificate> caCertificates = new ArrayList<>();
        CertificateFactory certificateFactory = CertificateFactory.getInstance("X.509");

        // Split the CA chain into individual certificates
        String[] certificates = caChainPEM.split("(?=-----BEGIN CERTIFICATE-----)");

        for (String cert : certificates) {
            cert = cert.trim();
            if (!cert.isEmpty()) {
                ByteArrayInputStream inputStream = new ByteArrayInputStream(cert.getBytes(StandardCharsets.UTF_8));
                X509Certificate caCert = (X509Certificate) certificateFactory.generateCertificate(inputStream);
                caCertificates.add(caCert);
            }
        }
        return caCertificates;
    }
}
