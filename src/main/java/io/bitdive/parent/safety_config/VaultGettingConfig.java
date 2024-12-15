package io.bitdive.parent.safety_config;

import com.bettercloud.vault.SslConfig;
import com.bettercloud.vault.Vault;
import com.bettercloud.vault.VaultConfig;
import com.bettercloud.vault.VaultException;
import com.bettercloud.vault.json.JsonValue;
import com.bettercloud.vault.response.AuthResponse;
import com.bettercloud.vault.response.LogicalResponse;
import io.bitdive.parent.parserConfig.YamlParserConfig;
import io.bitdive.parent.trasirovka.agent.utils.LoggerStatusContent;
import lombok.Builder;
import lombok.Getter;

import java.net.URL;
import java.util.HashMap;
import java.util.Iterator;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;
import java.util.stream.StreamSupport;

import static io.bitdive.parent.message_producer.LocalCryptoService.addKeyPrivateKey;
import static io.bitdive.parent.message_producer.LocalCryptoService.addKeySecretKey;

public class VaultGettingConfig {
    private static final String ENCRYPTION_KEY_PATH = "transit/export/encryption-key/encryption-key";
    private static final String SIGNING_KEY_PATH = "transit/export/signing-key/signing-key";

    private static Vault vault;

    private static final ScheduledExecutorService scheduler = Executors.newScheduledThreadPool(1);


    public static void initVaultConnect() {
        try {
            // Configure Vault
            VaultConfig config = null;
            try {
                config = new VaultConfig()
                        .address(YamlParserConfig.getProfilingConfig().getMonitoring().getSendFiles().getServerConsumer().getVault().getUrl())
                        .sslConfig(new SslConfig().verify(false).build())
                        .engineVersion(1)
                        .build();
            } catch (VaultException e) {
                throw new RuntimeException(e);
            }

            vault = new Vault(config);

            // Authenticate with Vault using username and password
            AuthResponse authResponse = vault.auth().loginByUserPass(
                    YamlParserConfig.getProfilingConfig().getMonitoring().getSendFiles().getServerConsumer().getVault().getLogin(),
                    YamlParserConfig.getProfilingConfig().getMonitoring().getSendFiles().getServerConsumer().getVault().getPassword()
            );
            String clientToken = authResponse.getAuthClientToken();

            config.token(clientToken).build();
            updateAESKey();
            updateRSAPrivateKey();
            startKeyUpdates();
        } catch (Exception e) {
            throw new RuntimeException(e);
        }

    }

    public static void startKeyUpdates() {
        Runnable updateTask = new Runnable() {
            @Override
            public void run() {
                try {
                    updateAESKey();
                    updateRSAPrivateKey();
                    if (LoggerStatusContent.isDebug())
                        System.out.println("keys were successfully updated in " + java.time.LocalDateTime.now());
                } catch (Exception e) {
                    if (LoggerStatusContent.isDebug())
                        System.out.println("error successfully updated :" + e.getMessage());
                }
            }
        };

        scheduler.scheduleAtFixedRate(updateTask, 0, 1, TimeUnit.HOURS);
    }

    public static VaultConfigRet retrieveCertificatesFromVault() throws Exception {
        URL url = new URL(YamlParserConfig.getProfilingConfig().getMonitoring().getSendFiles().getServerConsumer().getUrl());
        // Request a new certificate from Vault's PKI engine
        LogicalResponse response = vault.withRetries(5, 1000).logical()
                .write("pki/issue/bitdive", new HashMap<String, Object>() {{
                    put("common_name", "file-acceptor.bitdive");
                    put("alt_names", url.getHost());
                    put("ttl", "24h");
                }});

        // Extract certificate details from Vault's response
        return VaultConfigRet.builder()
                .certificate(response.getData().get("certificate"))
                .privateKey(response.getData().get("private_key"))
                .caChain(response.getData().get("issuing_ca"))
                .build();
    }

    @Builder
    @Getter
    public static class VaultConfigRet {
        private String certificate;
        private String privateKey;
        private String caChain;
    }

    public static Stream<JsonValue> iteratorToStream(Iterator<JsonValue> iterator) {
        return StreamSupport.stream(
                ((Iterable<JsonValue>) () -> iterator).spliterator(),
                false
        );
    }

    private static void updateAESKey() throws VaultException {
        LogicalResponse response = vault.logical().read(ENCRYPTION_KEY_PATH);
        Integer maxKeyId = response.getDataObject().get("keys").asObject().names().stream()
                .map(Integer::parseInt)
                .max(Integer::compareTo)
                .orElse(null);
        addKeySecretKey(maxKeyId, response.getDataObject().get("keys").asObject().get(String.valueOf(maxKeyId)).asString());
    }

    private static void updateRSAPrivateKey() throws Exception {
        LogicalResponse response = vault.logical().read(SIGNING_KEY_PATH);
        Integer maxKeyId = response.getDataObject().get("keys").asObject().names().stream()
                .map(Integer::parseInt)
                .max(Integer::compareTo)
                .orElse(null);
        addKeyPrivateKey(maxKeyId, response.getDataObject().get("keys").asObject().get(String.valueOf(maxKeyId)).asString());
    }

}
