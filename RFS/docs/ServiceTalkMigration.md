# ServiceTalk Migration

This document describes the migration from Netty Reactor to ServiceTalk in the RFS module.

## Overview

The RFS module previously used Netty Reactor for HTTP client functionality. This migration adds support for ServiceTalk as an alternative HTTP client implementation, allowing users to choose between Netty Reactor and ServiceTalk.

## Architecture

The migration introduces an abstraction layer for HTTP clients, allowing different implementations to be used interchangeably. The following components were added or modified:

### Abstraction Layer

- `HttpClientAdapter`: Interface for HTTP client adapters
- `AbstractRestClient`: Abstract base class for RestClient implementations
- `RestClientFactory`: Factory for creating RestClient instances

### Implementations

- `ReactorNettyAdapter`: Implementation of HttpClientAdapter using Reactor Netty
- `ServiceTalkAdapter`: Implementation of HttpClientAdapter using ServiceTalk
- `ReactorNettyRestClient`: Implementation of AbstractRestClient using ReactorNettyAdapter
- `ServiceTalkRestClient`: Implementation of AbstractRestClient using ServiceTalkAdapter

### Configuration

- `ConnectionContext`: Updated to support specifying the HTTP client implementation
- `OpenSearchClientFactory`: Updated to use the new abstraction layer

## Usage

### Command Line

To use ServiceTalk instead of Reactor Netty, use the `--target-http-client` option:

```bash
java -jar RFS.jar --target-http-client SERVICE_TALK ...
```

Valid values for `--target-http-client` are:

- `REACTOR_NETTY` (default): Use Reactor Netty for HTTP client implementation
- `SERVICE_TALK`: Use ServiceTalk for HTTP client implementation

### Programmatic

To create a RestClient with a specific implementation:

```java
// Create a ConnectionContext
ConnectionContext connectionContext = ...;

// Create a RestClient with Reactor Netty
AbstractRestClient reactorNettyClient = RestClientFactory.createRestClient(
    connectionContext, 
    RestClientFactory.HttpClientImplementation.REACTOR_NETTY
);

// Create a RestClient with ServiceTalk
AbstractRestClient serviceTalkClient = RestClientFactory.createRestClient(
    connectionContext, 
    RestClientFactory.HttpClientImplementation.SERVICE_TALK
);
```

## Benefits of ServiceTalk

ServiceTalk offers several benefits over Reactor Netty:

- Better performance in some scenarios
- More consistent API
- Better error handling
- More control over connection pooling
- Better support for HTTP/2

## Implementation Details

### ReactorNettyAdapter

The ReactorNettyAdapter wraps the existing Reactor Netty HTTP client functionality, providing a consistent interface for the abstraction layer.

### ServiceTalkAdapter

The ServiceTalkAdapter implements the HttpClientAdapter interface using ServiceTalk's HTTP client. It provides the same functionality as the ReactorNettyAdapter, but uses ServiceTalk instead of Reactor Netty.

### AbstractRestClient

The AbstractRestClient provides common functionality for RestClient implementations, such as header preparation, request execution, and response handling.

### RestClientFactory

The RestClientFactory creates the appropriate RestClient implementation based on the specified HTTP client implementation.

## Testing

Both implementations are tested to ensure they provide the same functionality and behavior.

## Future Work

- Add more metrics and monitoring for both implementations
- Add support for HTTP/2
- Add support for WebSockets
- Add support for server-sent events
