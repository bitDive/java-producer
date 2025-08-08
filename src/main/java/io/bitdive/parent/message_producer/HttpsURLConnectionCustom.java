package io.bitdive.parent.message_producer;

import io.bitdive.parent.safety_config.SSLContextCustomBitDive;
import io.bitdive.parent.utils.Pair;

import javax.net.ssl.HostnameVerifier;
import javax.net.ssl.HttpsURLConnection;
import javax.net.ssl.SSLContext;
import java.io.*;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

public class HttpsURLConnectionCustom {

    private static final String BOUNDARY = "*****" + System.currentTimeMillis() + "*****";

    private static volatile boolean TRUST_ALL_MODE = false;

    public static int sentToServer(HttpsURLConnection connection,
                                   File file,
                                   Pair<Integer, String> encryptedData,
                                   Pair<Integer, String> signature) throws IOException {

        try {
            return send(connection, file, encryptedData, signature, !TRUST_ALL_MODE);
        } catch (Exception sslEx) {
            TRUST_ALL_MODE = true;
            URL url = connection.getURL();
            HttpsURLConnection retryConn = (HttpsURLConnection) url.openConnection();
            return send(retryConn, file, encryptedData, signature, false);
        }
    }

    private static int send(HttpsURLConnection connection, File file, Pair<Integer, String> encryptedData, Pair<Integer, String> signature, boolean strictTls) throws IOException {

        SSLContext ctx = strictTls ? SSLContextCustomBitDive.strict()
                : SSLContextCustomBitDive.trustAll();
        HostnameVerifier hv = strictTls ? HttpsURLConnection.getDefaultHostnameVerifier()
                : (h, s) -> true;

        connection.setSSLSocketFactory(ctx.getSocketFactory());
        connection.setHostnameVerifier(hv);

        connection.setRequestMethod("POST");
        connection.setDoOutput(true);
        connection.setDoInput(true);
        connection.setRequestProperty("Content-Type", "multipart/form-data; boundary=" + BOUNDARY);
        connection.setRequestProperty("User-Agent", "Java client");
        connection.setRequestProperty("Accept", "*/*");


        try (OutputStream outputStream = connection.getOutputStream();
             PrintWriter writer = new PrintWriter(
                     new OutputStreamWriter(outputStream, StandardCharsets.UTF_8), true)) {

            final String LF = "\r\n";


            writer.append("--").append(BOUNDARY).append(LF);
            writer.append("Content-Disposition: form-data; name=\"encryptedData\"; filename=\"")
                    .append(file.getName()).append("\"").append(LF);
            writer.append("Content-Type: ")
                    .append(Files.probeContentType(file.toPath())).append(LF);
            writer.append(LF).flush();
            outputStream.write(encryptedData.getVal().getBytes(StandardCharsets.UTF_8));
            outputStream.flush();
            writer.append(LF).flush();


            writer.append("--").append(BOUNDARY).append(LF);
            writer.append("Content-Disposition: form-data; name=\"signature\"").append(LF);
            writer.append("Content-Type: text/plain").append(LF).append(LF).flush();
            writer.append(signature.getVal()).append(LF).flush();


            writer.append("--").append(BOUNDARY).append(LF);
            writer.append("Content-Disposition: form-data; name=\"encrypteKeyId\"").append(LF);
            writer.append("Content-Type: text/plain").append(LF).append(LF).flush();
            writer.append(encryptedData.getKey().toString()).append(LF).flush();


            writer.append("--").append(BOUNDARY).append(LF);
            writer.append("Content-Disposition: form-data; name=\"signatureKeyId\"").append(LF);
            writer.append("Content-Type: text/plain").append(LF).append(LF).flush();
            writer.append(signature.getKey().toString()).append(LF).flush();

            writer.append("--").append(BOUNDARY).append("--").append(LF).flush();
        }

        return connection.getResponseCode();
    }
}
