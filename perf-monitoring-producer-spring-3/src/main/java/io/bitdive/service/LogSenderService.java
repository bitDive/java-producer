package io.bitdive.service;

import io.bitdive.parent.parserConfig.YamlParserConfig;
import io.bitdive.parent.trasirovka.agent.utils.LoggerStatusContent;
import org.springframework.core.io.FileSystemResource;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.client.MultipartBodyBuilder;
import org.springframework.stereotype.Service;
import org.springframework.util.MultiValueMap;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.io.File;
import java.io.IOException;
import java.nio.file.*;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

@Service
public class LogSenderService {


    private final WebClient webClient;

    public LogSenderService(WebClient webClient) {
        this.webClient = webClient;
        startFileMonitoring();
    }

    private void startFileMonitoring() {
        Flux.interval(Duration.ZERO, Duration.ofSeconds(10)) // Adjusted interval for readability
                .flatMap(tick -> scanAndSendFiles())
                .onErrorContinue((throwable, obj) -> {
                    if (LoggerStatusContent.isErrorsOrDebug()) {
                        System.out.println("Error in file monitoring: " + throwable.getMessage());
                    }
                })
                .subscribe();
    }

    private Flux<Void> scanAndSendFiles() {
        String dirMonitoringFiles = System.getProperty("user.dir") + File.separator + "monitoringData" + File.separator + "toSend";
        Path dirPath = Paths.get(dirMonitoringFiles);

        if (Files.exists(dirPath) && Files.isDirectory(dirPath)) {
            try (DirectoryStream<Path> stream =  Files.newDirectoryStream(dirPath, "*.data.gz")) {
                // Collect all file paths into a list
                List<Path> paths = new ArrayList<>();
                for (Path path : stream) {
                    paths.add(path);
                }

                // Create Flux from the list of paths
                Flux<Path> filesFlux = Flux.fromIterable(paths);

                return filesFlux
                        .filter(Files::isRegularFile)
                        .flatMap(this::sendFileAsync);
            } catch (Exception e) {
                return Flux.error(new RuntimeException("Failed to scan directory: " + e.getMessage(), e));
            }
        } else {
            return Flux.empty();
        }
    }

    private Mono<Void> sendFileAsync(Path filePath) {
        return Mono.fromCallable(filePath::toFile)
                .flatMap(file -> {
                    MultipartBodyBuilder builder = new MultipartBodyBuilder();
                    builder.part("file", new FileSystemResource(filePath))
                            .header(HttpHeaders.CONTENT_DISPOSITION, "form-data; name=file; filename=" + filePath.getFileName().toString())
                            .contentType(MediaType.APPLICATION_OCTET_STREAM);

                    MultiValueMap<String, HttpEntity<?>> multipartData = builder.build();

                    String targetUrl = YamlParserConfig.getProfilingConfig()
                            .getMonitoring()
                            .getSendMonitoringFiles()
                            .getServerConsumer()
                            .getUrl();

                    return webClient.post()
                            .uri(targetUrl)
                            .contentType(MediaType.MULTIPART_FORM_DATA)
                            .bodyValue(multipartData)
                            .retrieve()
                            .toBodilessEntity()
                            .flatMap(response -> {
                                if (response.getStatusCode() == HttpStatus.OK) {
                                    return Mono.fromRunnable(() -> {
                                        try {
                                            Files.delete(filePath);
                                            if (LoggerStatusContent.isErrorsOrDebug()) {
                                                System.out.println("Файл успешно отправлен и удалён: " + file.getName());
                                            }
                                        } catch (IOException e) {
                                            if (LoggerStatusContent.isErrorsOrDebug()) {
                                                System.out.println("Ошибка при удалении файла:" + file.getName() + ". Ошибка: " + e.getMessage());
                                            }
                                            throw new RuntimeException(e);
                                        }
                                    }).subscribeOn(Schedulers.boundedElastic());
                                } else {
                                    if (LoggerStatusContent.isErrorsOrDebug()) {
                                        System.out.println("Не удалось отправить файл: " + file.getName() + ". Статус:" + response.getStatusCode());
                                    }
                                    return Mono.error(new RuntimeException("Не удалось отправить файл: " + file.getName()));
                                }
                            });
                })
                .onErrorResume(e -> {
                    if (LoggerStatusContent.isErrorsOrDebug()) {
                        System.out.println("Ошибка при отправке файла: " + filePath.getFileName() + ". Сообщение: " + e.getMessage());
                    }
                    return Mono.empty();
                })
                .then();
    }
}
