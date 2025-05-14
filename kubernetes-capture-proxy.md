# Kubernetes Deployment with Capture Proxy

This document provides a comprehensive overview of how Kubernetes deployment works with the capture proxy in the OpenSearch Migration Assistant.

## Table of Contents

1. [Overview](#overview)
2. [Capture Proxy Architecture](#capture-proxy-architecture)
3. [Kubernetes Deployment Components](#kubernetes-deployment-components)
4. [Configuration Management](#configuration-management)
5. [Integration with Migration Assistant](#integration-with-migration-assistant)
6. [Traffic Flow in Kubernetes](#traffic-flow-in-kubernetes)
7. [Scaling Considerations](#scaling-considerations)
8. [Deployment Process](#deployment-process)
9. [Monitoring and Observability](#monitoring-and-observability)
10. [Client Traffic Routing](#client-traffic-routing)

## Overview

The Capture Proxy is a key component of the OpenSearch Migration Assistant that facilitates migrations between different versions of Elasticsearch and OpenSearch. It's part of the "Live Traffic Capture with Capture-and-Replay" feature, which allows for capturing live traffic from a source cluster and replaying it on a target cluster for validation.

The main steps to synchronize a target cluster from a source cluster using the capture proxy are:

1. Traffic is directed to the existing cluster, reaching each coordinator node.
2. A Capture Proxy is added in front of the coordinator nodes in the cluster, allowing for traffic capture and storage.
3. A historical backfill is triggered to synchronize the documents in the target from the source.
4. Following the backfill, the Traffic Replayer begins replaying the captured traffic to the target cluster.
5. The user evaluates the differences between source and target responses.
6. After confirming that the new cluster's functionality meets expectations, the target server is ready to become the new cluster.

## Capture Proxy Architecture

The Capture Proxy:

- Terminates TLS and replicates the decrypted read/writes streams as they arrive to Kafka
- Is designed to offload data efficiently to minimize impact on overall performance
- Parses its TLS configuration the same way as OpenSearch, from a yaml config
- Can be deployed alongside the source cluster's coordinating nodes or on standalone hardware
- Handles GET traffic differently from mutating requests (PUT, POST, DELETE, PATCH)
- Uses netty for asynchronous network activity
- Organizes captured data into TrafficObservations within TrafficStream objects

### Protocol

Captured data is organized into TrafficObservations (Read, Write, Close, etc.) that have timestamps and are organized into larger "TrafficStream" objects which are written as records to Kafka. These observations are serialized as Protobuf wrappers to the raw bytes that were received or sent by the Proxy sans TLS.

## Kubernetes Deployment Components

In the Kubernetes deployment, the capture proxy consists of:

### 1. Deployment Resource

```yaml
apiVersion: apps/v1
kind: Deployment
metadata:
  name: {{ include "generic.fullname" . }}
spec:
  replicas: 1
  selector:
    matchLabels:
      app: {{ include "generic.fullname" . }}
  template:
    metadata:
      labels:
        app: {{ include "generic.fullname" . }}
        env: v1
    spec:
      initContainers:
        # Setup environment variables
        # Wait for Kafka to be ready
        - name: wait-for-kafka
          image: bitnami/kubectl:latest
          command: [ 'sh', '-c',
                     'until kubectl wait --for=condition=Ready kafka/captured-traffic -n {{.Release.Namespace }} --timeout=10s; do echo waiting for kafka cluster is ready; sleep 1; done' ]
      containers:
        - name: captureproxy
          image: migrations/capture_proxy:latest
          imagePullPolicy: IfNotPresent
          # Command to run the proxy
          ports:
            - containerPort: 9200
          volumeMounts:
            - name: env-vars
              mountPath: /shared
      volumes:
        - name: env-vars
          emptyDir: {}
```

The deployment includes:
- An init container that waits for Kafka to be ready
- The main container running the capture proxy application
- A shared volume for environment variables

### 2. Service Resource

```yaml
apiVersion: v1
kind: Service
metadata:
  name: {{ include "generic.fullname" . }}
spec:
  selector:
    app: {{ include "generic.fullname" . }}
    env: v1
  ports:
    - protocol: TCP
      port: 9200
      targetPort: 9200
  type: ClusterIP
```

This exposes the capture proxy within the Kubernetes cluster on port 9200.

### 3. ConfigMaps

```yaml
{{- include "generic.createConfigMaps" (dict
    "Parameters" .Values.parameters
    "PackageName" (include "generic.fullname" .)
    "include" .Template.Include
    "Template" .Template
) | indent 0 -}}
```

ConfigMaps are used to store configuration parameters for the capture proxy.

## Configuration Management

The capture proxy is configured through parameters defined in the values.yaml file:

```yaml
parameters:
  destinationUri:
    source: parameterConfig
    value: "http://sourcecluster.example.com:9200"
    allowRuntimeOverride: false
  listenPort:
    source: parameterConfig
    value: 9200
    allowRuntimeOverride: false
  insecureDestination:
    source: parameterConfig
    parameterType: booleanFlag
    value: true
    allowRuntimeOverride: false
```

Key configuration parameters include:
- `destinationUri`: The endpoint of the source cluster
- `listenPort`: The port on which the proxy listens
- `insecureDestination`: Whether to allow insecure connections to the destination
- `kafkaConnection`: The Kafka broker connection string (when integrated with Migration Assistant)

Configuration values can be:
- Hardwired for a given deployment (`allowRuntimeOverride: false`)
- Updated by somebody with access to the migration console (`allowRuntimeOverride: true`)

## Integration with Migration Assistant

In the full Migration Assistant deployment:

```yaml
# From migrationAssistant/values.yaml
capture-proxy:
  parameters:
    destinationUri:
      source: otherConfig
      configMapName: "source-cluster"
      configMapKey: "directEndpoint"
    insecureDestination:
      source: otherConfig
      configMapName: "source-cluster"
      configMapKey: "allowInsecure"
    kafkaConnection:
      source: otherConfig
      configMapName: "kafka-brokers"
      configMapKey: "brokers"
```

The capture proxy is configured to use shared configurations for common settings like source cluster endpoint. It's part of a larger deployment that includes:

- Migration Console
- Capture Proxy
- Traffic Replayer
- Document Backfill ("RFS")
- Observability services (Prometheus, Jaeger, and Grafana)

The Migration Assistant chart includes the capture proxy as a dependency:

```yaml
dependencies:
  # Other dependencies...
  - name: capture-proxy
    condition: conditionalPackageInstalls.proxy
    version: "0.1.0"
    repository: "file://../../components/captureProxy"
  - name: kafka-cluster
    alias: captured-traffic-kafka-cluster
    condition: conditionalPackageInstalls.kafka
    version: "0.1.0"
    repository: "file://../../sharedResources/baseKafkaCluster"
  # Other dependencies...
```

## Traffic Flow in Kubernetes

1. **Client Traffic Routing**: 
   - Traffic is directed to the capture proxy service (typically through an Application Load Balancer)
   - The ALB can route traffic on port 9201 to the capture proxy

2. **Capture Process**:
   - The proxy terminates TLS (if configured)
   - It forwards requests to the source cluster
   - It captures both requests and responses
   - It serializes this traffic as Protobuf objects
   - It sends these objects to Kafka

3. **Kafka Storage**:
   - Captured traffic is stored in Kafka topics
   - Traffic is organized into TrafficObservations within TrafficStream objects
   - Each connection has a unique connectionId

4. **Traffic Replay**:
   - The Traffic Replayer reads from Kafka
   - It reconstructs HTTP requests from the captured traffic
   - It applies any necessary transformations
   - It sends the requests to the target cluster
   - It records responses for comparison

## Scaling Considerations

### Scaling the Capture Proxy

The capture proxy can be scaled horizontally to handle more traffic. Since the proxy is handling data on the critical path for the source cluster, it's designed to offload data as efficiently as possible to minimize impact on overall performance.

### Scaling Kafka

Kafka can be scaled by increasing the number of partitions. However, there are considerations when scaling Kafka:

- Once the number of partitions is set, the partition assignments for recorded items will also be set
- If the partitioning scheme changes, data with the same partition key could be assigned to a different partition
- Since connectionIds are the partition key and connections can be long-lived, changing the scale of the Kafka topic can break the parallelization strategy

### Scaling the Replayer

The Traffic Replayer can also be scaled horizontally to handle more traffic. Considerations for scaling the replayer include:

- Backpressure is necessary so that the replayer doesn't overwhelm its memory footprint
- The replayer throttles consuming more messages by time
- For customers that need to achieve higher throughputs, they can scale the solution horizontally

## Deployment Process

To deploy the capture proxy in Kubernetes:

1. Install the full Migration Assistant:
   ```shell
   helm install ma -n ma charts/aggregates/migrationAssistant --create-namespace
   ```

2. Or deploy just the capture proxy component:
   ```shell
   helm install proxy -n ma charts/components/captureProxy
   ```

3. Deploy test clusters (optional):
   ```shell
   helm install tc -n ma charts/aggregates/testClusters
   ```

4. Alternatively, deploy specific test cluster configurations:
   ```shell
   helm install tc-source -n ma charts/components/elasticsearchCluster -f charts/components/elasticsearchCluster/environments/es-5-6-single-node-cluster.yaml
   helm install tc-target -n ma charts/components/opensearchCluster -f charts/components/opensearchCluster/environments/os-2-x-single-node-cluster.yaml
   ```

## Monitoring and Observability

The capture proxy publishes metrics and traces through OpenTelemetry, which can be:
- Collected by an OTEL collector
- Visualized in Grafana
- Stored in Prometheus
- Traced in Jaeger

The full Migration Assistant deployment includes observability services:
```yaml
conditionalPackageInstalls:
  grafana: false
  jaeger: false
  prometheus: false
  # Other components...
```

## Client Traffic Routing

An Application Load Balancer (ALB) can be used to route traffic between the source and target clusters during migration:

1. **Capture Proxy** (Port 9201): Records incoming traffic and forwards requests to the source cluster.
2. **Target Cluster Proxy** (Port 9202): Forwards requests to the new cluster being migrated to.
3. **Weighted Proxy** (Port 443): Forwards requests to either the Capture Proxy or Target Cluster Proxy based on configuration.

The weighted routing feature allows for:
- Testing the new system with actual traffic while maintaining the old system's operation
- Incrementally increasing traffic to the target cluster
- Quickly modifying traffic distribution or reverting to the source cluster if issues arise

### Architecture Diagram

```
graph TD
    subgraph ExistingClientNetworking
        Client[Client]
        NLB[Existing Network Load Balancer]
    end

    subgraph MigrationAssistantInfrastructure
        ALB[Application Load Balancer]
        subgraph ALB
            L1[Weighted Listener :443]
            L2[Source Listener :9201]
            L3[Target Listener :9202]
        end
        CP[Capture Proxy]
        TCP[Target Cluster Proxy]
    end

    SC[Source Cluster]
    TC[Target Cluster]

    Client --> NLB
    NLB --> L1
    Client -.->|Optional Direct Routing| L1

    L1 -.->|Weight X%| CP
    L1 ==>|Weight Y%| TCP
    L2 --> CP
    L3 --> TCP

    CP --> SC
    TCP --> TC
```

This setup offers several advantages:
- Flexibility in adjusting traffic routing
- Safety through the ability to split traffic
- Monitoring of both systems during migration
- Seamless transition for users and applications
- Smoke testing capabilities before shifting client traffic
