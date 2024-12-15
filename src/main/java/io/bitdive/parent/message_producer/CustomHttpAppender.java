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

import java.io.IOException;
import java.io.Serializable;
import java.net.Proxy;
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

import static io.bitdive.parent.utils.UtilsDataConvert.initilizationProxy;
import static io.bitdive.parent.utils.UtilsDataConvert.sendFile;

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
        this.scheduler = Executors.newScheduledThreadPool(1);
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


        Proxy proxy = initilizationProxy(proxyHost, proxyPort, proxyUserName, proxyPassword);


        if (layout == null) {
            layout = PatternLayout.createDefaultLayout();
        }

        long scanIntervalSeconds = 10;
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
            }
            return true;
        } catch (IOException e) {
            if (LoggerStatusContent.isDebug()) {
                System.out.println("Integrity check failed for file " + file.toString() + ": " + e.getMessage());
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
                        boolean success = sendFile(file.toFile(), proxy, "uploadFileData");
                        if (success) {
                            Files.delete(file);
                        } else {
                            BasicFileAttributes attrs = Files.readAttributes(file, BasicFileAttributes.class);
                            FileTime creationTime = attrs.creationTime();
                            long ageInMinutes = (System.currentTimeMillis() - creationTime.toMillis()) / (60 * 1000);

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


    @Override
    public void stop() {
        super.stop();
        try {
            scheduler.shutdown();
            scheduler.awaitTermination(5, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
        }
    }
}
