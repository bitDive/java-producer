# BitDive Java Producer

BitDive Java Producer is a Java agent and Spring integration library that collects runtime execution data for BitDive. It instruments Java applications at method level, captures HTTP and DB calls, builds distributed traces, and sends the collected data to the BitDive backend.

## What It Does

- Instruments code with ByteBuddy at runtime  
- Captures method calls, parameters, results, exceptions  
- Captures HTTP client and server calls  
- Captures JDBC queries and timings  
- Propagates trace id and span id across services  
- Buffers data locally and uploads it in batches  
- Integrates with Spring Boot through auto configuration  
- Collects JVM metrics  
- Allows selective monitoring through annotations

## Repository Structure

```
java-producer-parent
├─ perf-monitoring-producer         core agent and instrumentation
├─ perf-monitoring-producer-spring-2.7   Spring Boot 2.7 support
├─ perf-monitoring-producer-spring-3     Spring Boot 3 support
└─ examples                           sample apps
```

## Key Features

### Bytecode Instrumentation
- Automatic method tracing  
- HTTP client and server hooks  
- JDBC hooks for queries and timings  
- Kafka producer and consumer hooks  
- Thread context propagation with trace id and span id  

### Spring Boot Support
- Auto configuration for Spring Boot  
- AOP aspects for repositories, Feign, Kafka, schedulers  
- Full integration with Spring context lifecycle  

### Data Collection Pipeline

1. Instrumented code records events  
2. Events are formatted into trace segments  
3. Segments are written to local files  
4. Upload service sends batches to BitDive backend  

## Installation

Add the dependency from Maven:

```xml
<dependency>
  <groupId>io.bitdive</groupId>
  <artifactId>perf-monitoring-producer</artifactId>
  <version>YOUR_VERSION</version>
</dependency>
```

For Spring Boot:

```xml
<dependency>
  <groupId>io.bitdive</groupId>
  <artifactId>perf-monitoring-producer-spring-3</artifactId>
  <version>YOUR_VERSION</version>
</dependency>
```

## Configuration

Create a file `bitdive-config.yml` in your resources:

```yaml
service:
  name: my-service
  module: user-api

monitoring:
  captureArguments: true
  captureResults: true

upload:
  endpoint: https://bitdive.io/api/ingest
  batchSize: 2000
```

## Annotations

- `@MonitoringClass` enables monitoring for a class  
- `@NotMonitoring` disables monitoring for specific methods  

These are optional. The agent works automatically without them.

## Supported Technologies

- Spring Boot 2.7 and 3  
- Servlet API  
- RestTemplate and Feign  
- JDBC  
- Kafka  
- Thread pools and executors  

## Building

```
mvn clean package
```

## Documentation

See full BitDive docs:  
[https://bitdive.io/docs](https://bitdive.io/docs/getting-started/)
