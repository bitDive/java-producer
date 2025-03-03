package io.bitdive.parent.parserConfig;

public class DefaultMonitoringValues {

    public static ProfilingConfig.MonitoringConfig create() {
        ProfilingConfig.MonitoringConfig config = new ProfilingConfig.MonitoringConfig();
        config.setLogLevel(LogLevelEnum.INFO);
        config.setMonitoringArgumentMethod(false);
        config.setMonitoringReturnMethod(false);
        config.setMonitoringStaticMethod(false);
        config.setMonitoringOnlySpringComponent(true);

        // dataFile
        ProfilingConfig.MonitoringConfig.MonitoringDataFile dataFile = new ProfilingConfig.MonitoringConfig.MonitoringDataFile();
        dataFile.setPath("monitoringData");
        dataFile.setTimerConvertForSend(10);
        dataFile.setFileStorageTime(30);
        config.setDataFile(dataFile);

        // sendFiles
        ProfilingConfig.MonitoringConfig.MonitoringSendFilesConfig sendFiles = new ProfilingConfig.MonitoringConfig.MonitoringSendFilesConfig();
        sendFiles.setSchedulerTimer(1000L);
        ProfilingConfig.MonitoringConfig.MonitoringSendFilesConfig.ServerConsumerConfig serverConsumer =
                new ProfilingConfig.MonitoringConfig.MonitoringSendFilesConfig.ServerConsumerConfig();
        serverConsumer.setUrl("http://localhost:8080");
        sendFiles.setServerConsumer(serverConsumer);
        config.setSendFiles(sendFiles);

        // serialization
        ProfilingConfig.MonitoringConfig.Serialization serialization = new ProfilingConfig.MonitoringConfig.Serialization();
        serialization.setExcludedPackages(new String[]{
                "com.sun.", "sun.", "org.apache.", "org.springframework.", "com.zaxxer.", "HttpServletResponse"
        });
        serialization.setMaxElementCollection(500);
        config.setSerialization(serialization);

        return config;
    }
}
