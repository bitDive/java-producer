package io.bitdive.parent.message_producer;

import io.bitdive.parent.utils.Pair;

import java.io.*;
import java.net.HttpURLConnection;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

public class HttpURLConnectionCustom {
    private static final String boundary = "*****" + System.currentTimeMillis() + "*****";

    public static int sentToServer(HttpURLConnection connection, File file, Pair<Integer, String> encryptedData, Pair<Integer, String> signature) throws IOException {
        connection.setRequestMethod("POST");
        connection.setDoOutput(true);
        connection.setDoInput(true);
        connection.setRequestProperty("Content-Type", "multipart/form-data; boundary=" + boundary);
        connection.setRequestProperty("User-Agent", "Java client");
        connection.setRequestProperty("Accept", "*/*");

        try (OutputStream outputStream = connection.getOutputStream();
             PrintWriter writer = new PrintWriter(new OutputStreamWriter(outputStream, StandardCharsets.UTF_8), true)) {

            String LINE_FEED = "\r\n";
            writer.append("--").append(boundary).append(LINE_FEED);
            writer.append("Content-Disposition: form-data; name=\"encryptedData\"; filename=\"").append(file.getName()).append("\"").append(LINE_FEED);
            writer.append("Content-Type: ").append(Files.probeContentType(file.toPath())).append(LINE_FEED);
            writer.append(LINE_FEED).flush();

            outputStream.write(encryptedData.getVal().getBytes(StandardCharsets.UTF_8));
            outputStream.flush();
            writer.append(LINE_FEED).flush();


            writer.append("--").append(boundary).append(LINE_FEED);
            writer.append("Content-Disposition: form-data; name=\"signature\"").append(LINE_FEED);
            writer.append("Content-Type: text/plain").append(LINE_FEED);
            writer.append(LINE_FEED).flush();
            writer.append(signature.getVal()).append(LINE_FEED).flush();

            writer.append("--").append(boundary).append(LINE_FEED);
            writer.append("Content-Disposition: form-data; name=\"encrypteKeyId\"").append(LINE_FEED);
            writer.append("Content-Type: text/plain").append(LINE_FEED);
            writer.append(LINE_FEED).flush();
            writer.append(encryptedData.getKey().toString()).append(LINE_FEED).flush();

            writer.append("--").append(boundary).append(LINE_FEED);
            writer.append("Content-Disposition: form-data; name=\"signatureKeyId\"").append(LINE_FEED);
            writer.append("Content-Type: text/plain").append(LINE_FEED);
            writer.append(LINE_FEED).flush();
            writer.append(signature.getKey().toString()).append(LINE_FEED).flush();

            writer.append("--").append(boundary).append("--").append(LINE_FEED).flush();

        }
        return connection.getResponseCode();
    }
}
