package io.bitdive.parent.parserConfig;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.bitdive.parent.dto.ApplicationEnvironment;
import io.bitdive.parent.safety_config.SSLContextCustomBitDive;

import javax.net.ssl.HostnameVerifier;
import javax.net.ssl.HttpsURLConnection;
import javax.net.ssl.SSLContext;
import java.io.BufferedReader;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.security.AccessControlException;
import java.time.Duration;
import java.util.stream.Collectors;

public final class HttpsURLConnectionCustom {

    private static final ObjectMapper MAPPER = new ObjectMapper()
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

    private static final int TIMEOUT_MS = (int) Duration.ofSeconds(10).toMillis();

    private HttpsURLConnectionCustom() {}

    private static String normalizeUrl(String url) {
        URI u = URI.create(url);

        String protocol = u.getScheme(); // "https" (это и есть протокол/схема)
        String host = u.getHost();       // "localhost"
        int port = u.getPort();          // 8443 (-1 если не указан)

        return protocol + "://" + host + (port == -1 ? "" : ":" + port);
    }


    public static ProfilingConfig requestConfigForService(ConfigForServiceDTO configForServiceDTO) {
        String endpoint = trimTrailingSlash(configForServiceDTO.getServerUrl())
                + "/monitoring-api/service/serviceConfiguration/getConfigForService";

        try {
            return postJson(endpoint, configForServiceDTO,
                    ResponseHandlers.json(new TypeReference<ProfilingConfig>() {
                    }),
                    configForServiceDTO.getToken(),
                    configForServiceDTO.getModuleName()
            );
        } catch (AccessControlException e) {
            System.out.println("Error of read config from server bitDive " + endpoint + " Access Control Exception");
            throw new RuntimeException(e);
        } catch (Exception e) {
            throw new RuntimeException("Error of read config from server bitDive " + endpoint, e);
        }
    }

    private static <T> T postJson(String endpoint, Object requestDto, ResponseHandler<T> onSuccess, String token , String moduleName) throws IOException {
        HttpURLConnection conn = null;
        try {
            conn = open(endpoint);
            configure(conn,token,moduleName);

            byte[] body = MAPPER.writeValueAsBytes(requestDto);

            OutputStream os = null;
            try {
                os = conn.getOutputStream();
                os.write(body);
            } finally {
                if (os != null) {
                    try { os.close(); } catch (IOException ignore) {}
                }
            }

            int code = conn.getResponseCode();

            InputStream is = null;
            try {
                is = getResponseStream(conn, code);

                if (code >= 200 && code < 300) {
                    return onSuccess.handle(is, code);
                }
                if (code == 401) {
                    throw new AccessControlException("Unauthorized failed");
                }

                String error = readAll(is);
                throw new IllegalStateException("BitDive server returned " + code + ": " + error);

            } finally {
                if (is != null) {
                    try { is.close(); } catch (IOException ignore) {}
                }
            }
        } finally {
            if (conn != null) {
                conn.disconnect();
            }
        }
    }

    private static HttpURLConnection open(String endpoint) throws IOException {
        URL url = new URL(endpoint);
        java.net.URLConnection uc = url.openConnection();
        if (!(uc instanceof HttpURLConnection)) {
            throw new IllegalStateException("Unsupported connection type: " + uc.getClass());
        }
        return (HttpURLConnection) uc;
    }

    private static void configure(HttpURLConnection conn , String token , String moduleName) throws IOException {
        conn.setConnectTimeout(TIMEOUT_MS);
        conn.setReadTimeout(TIMEOUT_MS);
        conn.setRequestMethod("POST");
        conn.setDoOutput(true);
        conn.setRequestProperty("Content-Type", "application/json; charset=UTF-8");
        conn.setRequestProperty("Accept", "application/json");

        conn.setRequestProperty("X-BitDive-Access-Token", token);
        conn.setRequestProperty("X-BitDive-Method-Module-Name", moduleName);

        if (conn instanceof HttpsURLConnection) {
            HttpsURLConnection https = (HttpsURLConnection) conn;

            SSLContext ctx = SSLContextCustomBitDive.trustAll();
            HostnameVerifier hv = new HostnameVerifier() {
                @Override
                public boolean verify(String hostname, javax.net.ssl.SSLSession session) {
                    return true;
                }
            };

            https.setSSLSocketFactory(ctx.getSocketFactory());
            https.setHostnameVerifier(hv);
        }
    }

    private static InputStream getResponseStream(HttpURLConnection conn, int code) throws IOException {
        InputStream is = (code < 400) ? conn.getInputStream() : conn.getErrorStream();
        return (is != null) ? is : new ByteArrayInputStream(new byte[0]);
    }

    private static String readAll(InputStream is) {
        if (is == null) return "";
        BufferedReader br = new BufferedReader(new InputStreamReader(is, StandardCharsets.UTF_8));
        try {
            return br.lines().collect(Collectors.joining("\n"));
        } finally {
            try { br.close(); } catch (IOException ignore) {}
        }
    }

    private static String trimTrailingSlash(String url) {
        return url == null ? null : url.replaceAll("/+$", "");
    }

    @FunctionalInterface
    private interface ResponseHandler<T> {
        T handle(InputStream bodyStream, int httpCode) throws IOException;
    }

    private static final class ResponseHandlers {
        private ResponseHandlers() {}

        static ResponseHandler<Void> noBody() {
            return new ResponseHandler<Void>() {
                @Override
                public Void handle(InputStream bodyStream, int httpCode) {
                    return null;
                }
            };
        }

        static <T> ResponseHandler<T> json(final TypeReference<T> type) {
            return new ResponseHandler<T>() {
                @Override
                public T handle(InputStream bodyStream, int httpCode) throws IOException {
                    return MAPPER.readValue(bodyStream, type);
                }
            };
        }
    }
}
