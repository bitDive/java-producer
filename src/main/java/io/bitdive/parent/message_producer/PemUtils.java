package io.bitdive.parent.message_producer;

import lombok.SneakyThrows;
import org.bouncycastle.asn1.pkcs.PrivateKeyInfo;
import org.bouncycastle.asn1.sec.ECPrivateKey;
import org.bouncycastle.jce.provider.BouncyCastleProvider;
import org.bouncycastle.openssl.PEMKeyPair;
import org.bouncycastle.openssl.PEMParser;

import java.io.StringReader;
import java.security.KeyFactory;
import java.security.PrivateKey;
import java.security.Security;
import java.security.spec.PKCS8EncodedKeySpec;

public class PemUtils {
    @SneakyThrows
    public static PrivateKey getPrivateKeyFromPEM(String pem) {
        Security.addProvider(new BouncyCastleProvider());
        PEMParser pemParser = new PEMParser(new StringReader(pem));
        Object object = pemParser.readObject();
        pemParser.close();
        PrivateKeyInfo pkInfo;
        if (object instanceof PEMKeyPair) {
            pkInfo = ((PEMKeyPair) object).getPrivateKeyInfo();
        } else if (object instanceof PrivateKeyInfo) {
            pkInfo = (PrivateKeyInfo) object;
        } else if (object instanceof ECPrivateKey) {
            ECPrivateKey ecPrivateKey = (ECPrivateKey) object;
            pkInfo = new PrivateKeyInfo(new org.bouncycastle.asn1.x509.AlgorithmIdentifier(
                    org.bouncycastle.asn1.x9.X9ObjectIdentifiers.id_ecPublicKey), ecPrivateKey);
        } else {
            throw new IllegalArgumentException("Неподдерживаемый тип PEM-объекта: " + object.getClass().getName());
        }

        byte[] pkcs8Bytes = pkInfo.getEncoded();
        PKCS8EncodedKeySpec keySpec = new PKCS8EncodedKeySpec(pkcs8Bytes);
        KeyFactory kf = KeyFactory.getInstance("EC", "BC");
        return kf.generatePrivate(keySpec);
    }
}

