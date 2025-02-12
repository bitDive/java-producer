package io.bitdive.parent.message_producer;

import io.bitdive.parent.utils.Pair;

import javax.crypto.Cipher;
import javax.crypto.SecretKey;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.PrivateKey;
import java.security.Signature;
import java.util.Base64;

public class LocalCryptoService {

    private static final String ENCRYPTION_ALGORITHM = "AES/CBC/PKCS5Padding";
    private static final int IV_LENGTH = 16;

    private static volatile Pair<Integer, SecretKey> pairSecretKey = new Pair<>(-1, null);
    private static volatile Pair<Integer, PrivateKey> pairPrivateKey = new Pair<>(-1, null);

    private static final java.security.SecureRandom secureRandom = new java.security.SecureRandom();

    public static void addKeySecretKey(Integer keyId, String keyBase64) {
        if (!keyId.equals(pairSecretKey.getKey())) {
            byte[] decodedKey = Base64.getDecoder().decode(keyBase64);
            pairSecretKey = Pair.createPair(keyId, new SecretKeySpec(decodedKey, 0, decodedKey.length, "AES"));
        }
    }

    public static void addKeyPrivateKey(Integer keyId, String rsaPrivateKey) {
        if (!keyId.equals(pairPrivateKey.getKey())) {
            pairPrivateKey = Pair.createPair(keyId, PemUtils.getPrivateKeyFromPEM(rsaPrivateKey));
        }
    }

    public static Pair<Integer, String> encrypt(String plainText) throws Exception {
        Cipher cipher = Cipher.getInstance(ENCRYPTION_ALGORITHM);
        byte[] iv = new byte[IV_LENGTH];
        secureRandom.nextBytes(iv);

        IvParameterSpec ivSpec = new IvParameterSpec(iv);
        cipher.init(Cipher.ENCRYPT_MODE, pairSecretKey.getVal(), ivSpec);
        
        byte[] encryptedBytes = cipher.doFinal(plainText.getBytes(StandardCharsets.UTF_8));
        byte[] combined = new byte[iv.length + encryptedBytes.length];
        System.arraycopy(iv, 0, combined, 0, iv.length);
        System.arraycopy(encryptedBytes, 0, combined, iv.length, encryptedBytes.length);

        return Pair.createPair(pairSecretKey.getKey(), Base64.getEncoder().encodeToString(combined));
    }

    public static Pair<Integer, String> sign(String encryptedData) throws Exception {
        Signature signature = Signature.getInstance("SHA256withECDSA");
        signature.initSign(pairPrivateKey.getVal());
        signature.update(encryptedData.getBytes(StandardCharsets.UTF_8));
        byte[] signatureBytes = signature.sign();
        return Pair.createPair(pairPrivateKey.getKey(), Base64.getEncoder().encodeToString(signatureBytes));
    }
}
