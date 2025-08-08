package io.bitdive.parent.safety_config;

import com.bettercloud.vault.SslConfig;
import com.bettercloud.vault.Vault;
import com.bettercloud.vault.VaultConfig;
import com.bettercloud.vault.VaultException;
import com.bettercloud.vault.response.LogicalResponse;
import io.bitdive.parent.parserConfig.YamlParserConfig;
import io.bitdive.parent.trasirovka.agent.utils.LoggerStatusContent;

import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

import static io.bitdive.parent.message_producer.LocalCryptoService.addKeyPrivateKey;
import static io.bitdive.parent.message_producer.LocalCryptoService.addKeySecretKey;

public class VaultGettingConfig {
    private static final String ENCRYPTION_KEY_PATH = "transit/export/encryption-key/encryption-key";
    private static final String SIGNING_KEY_PATH = "transit/export/signing-key/signing-key";


    private static final ScheduledExecutorService scheduler = Executors.newScheduledThreadPool(1);

    public static Vault initVault() {
        Vault vault = null;
        try {
            vault = new Vault(new VaultConfig()
                    .address(YamlParserConfig.getProfilingConfig().getMonitoring().getSendFiles().getVault().getUrl())
                    .token(YamlParserConfig.getProfilingConfig().getMonitoring().getSendFiles().getVault().getToken())
                    .sslConfig(new SslConfig().verify(false).build())
                    .engineVersion(1)
                    .build());
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
        return vault;
    }

    public static void initVaultConnect() {
        try {
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

    public static void updateAESKey() throws VaultException {
        LogicalResponse response = initVault().logical().read(ENCRYPTION_KEY_PATH);
        Integer maxKeyId = response.getDataObject().get("keys").asObject().names().stream()
                .map(Integer::parseInt)
                .max(Integer::compareTo)
                .orElse(null);
        addKeySecretKey(maxKeyId, response.getDataObject().get("keys").asObject().get(String.valueOf(maxKeyId)).asString());
    }

    public static void updateRSAPrivateKey() throws Exception {
        LogicalResponse response = initVault().logical().read(SIGNING_KEY_PATH);
        Integer maxKeyId = response.getDataObject().get("keys").asObject().names().stream()
                .map(Integer::parseInt)
                .max(Integer::compareTo)
                .orElse(null);
        addKeyPrivateKey(maxKeyId, response.getDataObject().get("keys").asObject().get(String.valueOf(maxKeyId)).asString());
    }

}
