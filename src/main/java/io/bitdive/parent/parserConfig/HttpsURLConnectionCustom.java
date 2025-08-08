package io.bitdive.parent.parserConfig;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.bitdive.parent.safety_config.SSLContextCustomBitDive;

import javax.net.ssl.HostnameVerifier;
import javax.net.ssl.HttpsURLConnection;
import javax.net.ssl.SSLContext;
import java.io.*;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.security.AccessControlException;
import java.time.Duration;
import java.util.stream.Collectors;

public class HttpsURLConnectionCustom {

    private static boolean TRUST_ALL_MODE = false;

    private static final ObjectMapper MAPPER = new ObjectMapper()
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
    ;


    public static ProfilingConfig requestConfigForService(ConfigForServiceDTO dto) {
        String endpoint = dto.getServerUrl().replaceAll("/+$", "") + "/monitoring-api/service/serviceConfiguration/getConfigForService";
        try {
            URL url = new URL(endpoint);
            try {
                HttpURLConnection conn =
                        endpoint.contains("https://") ?
                                (HttpsURLConnection) url.openConnection() :
                                (HttpURLConnection) url.openConnection();

                return doRequest(conn, dto, !TRUST_ALL_MODE);
            } catch (Exception sslEx) {
                TRUST_ALL_MODE = true;
                HttpsURLConnection retry = (HttpsURLConnection) url.openConnection();
                return doRequest(retry, dto, false);
            }
        } catch (AccessControlException e) {
            System.out.println("Error of read config from server bitDive " + endpoint + " Access Control Exception");
            throw new RuntimeException(e);
        } catch (Exception e) {
            throw new RuntimeException("Error of read config from server bitDive " + endpoint, e);
        }
    }

    private static ProfilingConfig doRequest(HttpURLConnection httpURLConnection, ConfigForServiceDTO dto, boolean strictTls) throws Exception {


        byte[] body = MAPPER.writeValueAsBytes(dto);

        if (httpURLConnection instanceof HttpsURLConnection) {
            HttpsURLConnection conn = (HttpsURLConnection) httpURLConnection;
            SSLContext ctx = strictTls ? SSLContextCustomBitDive.strict() : SSLContextCustomBitDive.trustAll();
            HostnameVerifier hv = strictTls ? HttpsURLConnection.getDefaultHostnameVerifier() : (h, s) -> true;

            conn.setSSLSocketFactory(ctx.getSocketFactory());
            conn.setHostnameVerifier(hv);
            return getProfilingConfig(body, conn);
        } else {
            return getProfilingConfig(body, httpURLConnection);
        }


    }

    private static ProfilingConfig getProfilingConfig(byte[] body, HttpURLConnection conn) throws IOException {
        int code;
        conn.setConnectTimeout((int) Duration.ofSeconds(10).toMillis());
        conn.setReadTimeout((int) Duration.ofSeconds(20).toMillis());
        conn.setRequestMethod("POST");
        conn.setDoOutput(true);
        conn.setRequestProperty("Content-Type", "application/json; charset=UTF-8");
        conn.setRequestProperty("Accept", "application/json");

        try (OutputStream os = conn.getOutputStream()) {
            os.write(body);
        }
        code = conn.getResponseCode();
        try (InputStream is = code < 400 ? conn.getInputStream() : conn.getErrorStream()) {
            if (code >= 200 && code < 300) {
                return MAPPER.readValue(is, new TypeReference<ProfilingConfig>() {
                });
            } else if (code == 401) {
                throw new AccessControlException("Unauthorized failed");
            } else {
                String error;
                try (BufferedReader br = new BufferedReader(new InputStreamReader(is, StandardCharsets.UTF_8))) {
                    error = br.lines().collect(Collectors.joining("\n"));
                }
                throw new IllegalStateException("BitDive server returned " + code + ": " + error);
            }
        }
    }
}