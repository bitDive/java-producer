package io.bitdive.parent.message_producer;

import io.bitdive.parent.trasirovka.agent.utils.LoggerStatusContent;
import org.apache.logging.log4j.core.Appender;
import org.apache.logging.log4j.core.Filter;
import org.apache.logging.log4j.core.Layout;
import org.apache.logging.log4j.core.LogEvent;
import org.apache.logging.log4j.core.appender.AbstractAppender;
import org.apache.logging.log4j.core.config.Configuration;
import org.apache.logging.log4j.core.config.plugins.*;
import org.apache.logging.log4j.core.layout.PatternLayout;

import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.FileTime;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.zip.GZIPInputStream;

@Plugin(name = "CustomHttpAppender", category = "Core", elementType = Appender.ELEMENT_TYPE)
public class CustomHttpAppender extends AbstractAppender {

    private final String url;

    private final String filePath;

    private final ScheduledExecutorService scheduler;
    private final AtomicBoolean isSending;
    private final Proxy proxy;
    private final Integer fileStorageTime;

    protected CustomHttpAppender(String name, Filter filter, Layout<? extends Serializable> layout,
                                 boolean ignoreExceptions, String url, Proxy proxy, long scanIntervalSeconds, Configuration configuration, String filePath, Integer fileStorageTime) {
        super(name, filter, layout, ignoreExceptions);
        this.url = url;
        this.fileStorageTime = fileStorageTime;
        this.isSending = new AtomicBoolean(false);
        this.proxy = proxy;
        this.filePath = filePath;
        // Запуск планировщика для сканирования и отправки файлов
        this.scheduler = Executors.newSingleThreadScheduledExecutor();
        this.scheduler.scheduleAtFixedRate(this::scanAndSendFiles, 0, scanIntervalSeconds, TimeUnit.SECONDS);
    }

    @PluginFactory
    public static CustomHttpAppender createAppender(
            @PluginAttribute("name") String name,
            @PluginElement("Layout") Layout<? extends Serializable> layout,
            @PluginElement("Filters") Filter filter,
            @PluginAttribute("url") String url,
            @PluginAttribute("proxyHost") String proxyHost,
            @PluginAttribute("proxyPort") String proxyPort,
            @PluginAttribute("proxyUserName") String proxyUserName,
            @PluginAttribute("proxyPassword") String proxyPassword,
            @PluginAttribute("filePath") String filePath,
            @PluginAttribute("scanIntervalSeconds") String scanIntervalSecondsStr,
            @PluginAttribute("fileStorageTime") Integer fileStorageTime,
            @PluginConfiguration Configuration configuration) {


        Proxy proxy = Proxy.NO_PROXY;
        if (proxyHost != null && proxyPort != null) {
            proxy = new Proxy(Proxy.Type.HTTP, new InetSocketAddress(proxyHost, Integer.parseInt(proxyPort)));
            if (proxyUserName != null && proxyPassword != null && !proxyUserName.isEmpty() && !proxyPassword.isEmpty()) {
                Authenticator.setDefault(new Authenticator() {
                    @Override
                    protected PasswordAuthentication getPasswordAuthentication() {
                        if (getRequestorType() == RequestorType.PROXY) {
                            return new PasswordAuthentication(proxyUserName, proxyPassword.toCharArray());
                        }
                        return super.getPasswordAuthentication();
                    }
                });
            }
        }


        if (layout == null) {
            layout = PatternLayout.createDefaultLayout();
        }

        long scanIntervalSeconds = 10; // по умолчанию 10 секунд
        if (scanIntervalSecondsStr != null) {
            scanIntervalSeconds = Long.parseLong(scanIntervalSecondsStr);
        }

        return new CustomHttpAppender(
                name, filter, layout, true,
                url, proxy, scanIntervalSeconds, configuration,
                filePath, fileStorageTime);
    }

    @Override
    public void append(LogEvent event) {
    }

    private boolean isGzipValid(Path file) {
        try (GZIPInputStream gis = new GZIPInputStream(Files.newInputStream(file.toFile().toPath()))) {
            byte[] buffer = new byte[1024];
            while (gis.read(buffer) != -1) {
                // Читаем файл до конца
            }
            return true;
        } catch (IOException e) {
            if (LoggerStatusContent.isDebug()) {
                System.out.println("Проверка целостности не удалась для файла " + file.toString() + ": " + e.getMessage());
            }
            return false;
        }
    }

    private void scanAndSendFiles() {
        if (isSending.compareAndSet(false, true)) {
            try {
                Path dir = Paths.get(filePath);

                List<Path> filesToSend = new ArrayList<>();
                try (DirectoryStream<Path> stream = Files.newDirectoryStream(dir, "*.gz")) {
                    for (Path entry : stream) {
                        filesToSend.add(entry);
                    }
                }

                for (Path file : filesToSend) {
                    if (isGzipValid(file)) {
                        boolean success = sendFile(file.toFile());
                        if (success) {
                            Files.delete(file);
                        } else {
                            BasicFileAttributes attrs = Files.readAttributes(file, BasicFileAttributes.class);
                            FileTime creationTime = attrs.creationTime();
                            long ageInMinutes = (System.currentTimeMillis() - creationTime.toMillis()) / (60 * 1000);

                            // Check if the file is older than 30 minutes
                            if (ageInMinutes > fileStorageTime) {
                                Files.delete(file);
                            }
                        }
                    }
                }

            } catch (Exception e) {
                if (LoggerStatusContent.isDebug()) System.out.println("error scan file " + e);
            } finally {
                isSending.set(false);
            }
        }
    }

    public boolean sendFile(File file) {
        String boundary = "*****" + System.currentTimeMillis() + "*****";
        String LINE_FEED = "\r\n";

        try {
            URL serverUrl = new URL(url);
            HttpURLConnection connection = (HttpURLConnection) serverUrl.openConnection(proxy);

            connection.setRequestMethod("POST");
            connection.setDoOutput(true);
            connection.setDoInput(true);
            connection.setRequestProperty("Content-Type", "multipart/form-data; boundary=" + boundary);
            connection.setRequestProperty("User-Agent", "Java client");
            connection.setRequestProperty("Accept", "*/*");

            try (OutputStream outputStream = connection.getOutputStream();
                 PrintWriter writer = new PrintWriter(new OutputStreamWriter(outputStream, StandardCharsets.UTF_8), true)) {

                writer.append("--").append(boundary).append(LINE_FEED);
                writer.append("Content-Disposition: form-data; name=\"file\"; filename=\"")
                        .append(file.getName()).append("\"").append(LINE_FEED);
                writer.append("Content-Type: ").append("application/octet-stream").append(LINE_FEED);
                writer.append(LINE_FEED).flush();

                try (FileInputStream inputStream = new FileInputStream(file)) {
                    byte[] buffer = new byte[4096];
                    int bytesRead;
                    while ((bytesRead = inputStream.read(buffer)) != -1) {
                        outputStream.write(buffer, 0, bytesRead);
                    }
                    outputStream.flush();
                }

                writer.append(LINE_FEED).flush();
                writer.append("--").append(boundary).append("--").append(LINE_FEED).flush();
            }

            int responseCode = connection.getResponseCode();
            if (responseCode == HttpURLConnection.HTTP_OK) {
                if (LoggerStatusContent.isDebug())
                    System.out.println("File uploaded successfully: " + file.getName());
                return true;
            } else {
                if (LoggerStatusContent.isErrorsOrDebug())
                    System.err.println("Failed to upload file. Response code: " + responseCode);
                return false;
            }
        } catch (IOException e) {
            if (LoggerStatusContent.isErrorsOrDebug())
                System.err.println("Failed to upload file: " + e);
            return false;
        }
    }

    @Override
    public void stop() {
        super.stop();
        try {
            scheduler.shutdown();
            scheduler.awaitTermination(5, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            LOGGER.warn("Прерывание при остановке планировщика", e);
            Thread.currentThread().interrupt();
        }
    }
}
