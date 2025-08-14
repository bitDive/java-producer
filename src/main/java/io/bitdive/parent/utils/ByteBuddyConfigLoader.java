package io.bitdive.parent.utils;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.bitdive.parent.parserConfig.ConfigForServiceDTO;
import org.yaml.snakeyaml.Yaml;

import java.io.InputStream;
import java.util.Map;

public class ByteBuddyConfigLoader {

    public static ConfigForServiceDTO load() {
        try {
            return readYaml();
        } catch (Exception e) {
            return null;
        }
    }

    private static ConfigForServiceDTO readYaml() {
        try (InputStream in = ByteBuddyConfigLoader.class
                .getClassLoader()
                .getResourceAsStream("config-profiling-api.yml")) {

            if (in == null) {
                throw new IllegalStateException("Файл config-profiling-api.yml не найден");
            }

            Yaml yaml = new Yaml();
            Map<String, Object> root = yaml.load(in);

            Map<String, Object> monitoring = (Map<String, Object>) ((Map<String, Object>) root.get("bitdive")).get("monitoring");

            ObjectMapper mapper = new ObjectMapper();
            return mapper.convertValue(monitoring, ConfigForServiceDTO.class);

        } catch (Exception e) {
            throw new RuntimeException("Ошибка чтения YAML", e);
        }
    }
}
