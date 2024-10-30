package io.bitdive.parent.message_producer;

import io.bitdive.parent.parserConfig.ProfilingConfig;
import io.bitdive.parent.parserConfig.YamlParserConfig;
import org.apache.logging.log4j.Level;
import org.apache.logging.log4j.Logger;
import org.apache.logging.log4j.core.LoggerContext;
import org.apache.logging.log4j.core.config.Configuration;
import org.apache.logging.log4j.core.config.builder.api.*;
import org.apache.logging.log4j.core.config.builder.impl.BuiltConfiguration;

import java.io.File;
import java.util.Optional;


public class LibraryLoggerConfig {
    private static LoggerContext loggerContext;


    public static void init() {
        ConfigurationBuilder<BuiltConfiguration> builder = ConfigurationBuilderFactory.newConfigurationBuilder();

        builder.setStatusLevel(Level.WARN);


        AppenderComponentBuilder rollingFileAppender = builder.newAppender("MonitoringCustomConfig", "RollingRandomAccessFile")
                .addAttribute("fileName", YamlParserConfig.getProfilingConfig().getMonitoring().getDataFile().getPath() + File.separator + "monitoringFile.data")
                .addAttribute("filePattern",
                        YamlParserConfig.getProfilingConfig().getMonitoring().getDataFile().getPath() + File.separator +
                                "toSend" + File.separator +
                                "data-%d{yyyy-MM-dd-HH-mm-ss}_" + YamlParserConfig.getProfilingConfig().getApplication().getServiceName() + ".data.gz");

        // Добавляем PatternLayout к аппендеру
        LayoutComponentBuilder layoutBuilder = builder.newLayout("PatternLayout")
                .addAttribute("pattern", "%m --- %n");
        rollingFileAppender.add(layoutBuilder);

        ComponentBuilder<?> policies = builder.newComponent("Policies")
                .addComponent(
                        builder.newComponent("CronTriggeringPolicy")
                                .addAttribute("schedule", "*/" + YamlParserConfig.getProfilingConfig().getMonitoring().getDataFile().getTimerConvertForSend() + " * * * * ?")
                );
        rollingFileAppender.addComponent(policies);

        builder.add(rollingFileAppender);

        AppenderComponentBuilder asyncAppender = builder.newAppender("AsyncAppender", "Async")
                .addAttribute("bufferSize", 8096)
                .addComponent(builder.newAppenderRef("MonitoringCustomConfig"));
        builder.add(asyncAppender);

        AppenderComponentBuilder customHttpAppender = builder.newAppender("CustomHttpAppender", "CustomHttpAppender")
                .addAttribute("url", YamlParserConfig.getProfilingConfig().getMonitoring().getSendFiles().getServerConsumer().getUrl())
                .addAttribute("proxyHost", Optional.ofNullable(YamlParserConfig.getProfilingConfig().getMonitoring().getSendFiles().getServerConsumer())
                        .map(ProfilingConfig.MonitoringConfig.MonitoringSendFilesConfig.ServerConsumerConfig::getProxy)
                        .map(ProfilingConfig.MonitoringConfig.MonitoringSendFilesConfig.ServerConsumerConfig.ProxyConfig::getHost)
                        .orElse(""))
                .addAttribute("proxyPort", Optional.ofNullable(YamlParserConfig.getProfilingConfig().getMonitoring().getSendFiles().getServerConsumer())
                        .map(ProfilingConfig.MonitoringConfig.MonitoringSendFilesConfig.ServerConsumerConfig::getProxy)
                        .map(ProfilingConfig.MonitoringConfig.MonitoringSendFilesConfig.ServerConsumerConfig.ProxyConfig::getPort)
                        .map(Object::toString)
                        .orElse(null))
                .addAttribute("proxyUserName", Optional.ofNullable(YamlParserConfig.getProfilingConfig().getMonitoring().getSendFiles().getServerConsumer())
                        .map(ProfilingConfig.MonitoringConfig.MonitoringSendFilesConfig.ServerConsumerConfig::getProxy)
                        .map(ProfilingConfig.MonitoringConfig.MonitoringSendFilesConfig.ServerConsumerConfig.ProxyConfig::getUsername)
                        .orElse(null))
                .addAttribute("proxyPassword", Optional.ofNullable(YamlParserConfig.getProfilingConfig().getMonitoring().getSendFiles().getServerConsumer())
                        .map(ProfilingConfig.MonitoringConfig.MonitoringSendFilesConfig.ServerConsumerConfig::getProxy)
                        .map(ProfilingConfig.MonitoringConfig.MonitoringSendFilesConfig.ServerConsumerConfig.ProxyConfig::getPassword)
                        .orElse(null))
                .addAttribute("filePath", YamlParserConfig.getProfilingConfig().getMonitoring().getDataFile().getPath() + File.separator + "toSend")
                .addAttribute("fileStorageTime", YamlParserConfig.getProfilingConfig().getMonitoring().getDataFile().getFileStorageTime());

        builder.add(customHttpAppender);

        builder.add(builder.newRootLogger(Level.INFO)
                .add(builder.newAppenderRef("AsyncAppender"))
                .add(builder.newAppenderRef("CustomHttpAppender")));


        Configuration configuration = builder.build();

        LoggerContext context = new LoggerContext("IsolatedContext");
        context.start(configuration);

        loggerContext = context;
    }


    public static Logger getLogger(Class<?> clazz) {
        return loggerContext.getLogger(clazz.getName());
    }
}
