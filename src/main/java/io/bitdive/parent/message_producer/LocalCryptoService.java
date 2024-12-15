package io.bitdive.parent.message_producer;

import io.bitdive.parent.utils.Pair;

import javax.crypto.Cipher;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.PrivateKey;
import java.security.Signature;
import java.util.Base64;

public class LocalCryptoService {

    private static final String ENCRYPTION_ALGORITHM = "AES/GCM/NoPadding";
    private static final int GCM_IV_LENGTH = 12; // 96 бит
    private static final int GCM_TAG_LENGTH = 128; // 128 бит

    private static Pair<Integer, SecretKey> pairSecretKey = new Pair<>(-1, null);
    private static Pair<Integer, PrivateKey> pairPrivateKey = new Pair<>(-1, null);

    public static void addKeySecretKey(Integer keyId, String keyBase64) {
        if (!pairSecretKey.getKey().equals(keyId)) {
            byte[] decodedKey = Base64.getDecoder().decode(keyBase64);
            pairSecretKey = Pair.createPair(keyId, new SecretKeySpec(decodedKey, 0, decodedKey.length, "AES"));
        }
    }

    public static void addKeyPrivateKey(Integer keyId, String rsaPrivateKey) {
        if (!pairPrivateKey.getKey().equals(keyId)) {
            pairPrivateKey = Pair.createPair(keyId, PemUtils.getPrivateKeyFromPEM(rsaPrivateKey));
        }
    }

    public static Pair<Integer, String> encrypt(String plainText) throws Exception {
        Cipher cipher = Cipher.getInstance(ENCRYPTION_ALGORITHM);
        byte[] iv = new byte[GCM_IV_LENGTH];
        new java.security.SecureRandom().nextBytes(iv);
        GCMParameterSpec spec = new GCMParameterSpec(GCM_TAG_LENGTH, iv);
        cipher.init(Cipher.ENCRYPT_MODE, pairSecretKey.getVal(), spec);
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
