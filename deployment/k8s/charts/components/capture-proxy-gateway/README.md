# Capture Proxy Gateway

This Helm chart deploys the OpenSearch Migration Assistant Capture Proxy with Gateway API and NLB integration. It provides a scalable and secure way to capture traffic from a source cluster and forward it to Kafka for later replay.

## Prerequisites

- Kubernetes 1.22+
- Helm 3.2.0+
- Gateway API CRDs installed in the cluster

## Installing the Gateway API CRDs

Before installing this chart, you need to install the Gateway API CRDs:

```bash
kubectl apply -f https://github.com/kubernetes-sigs/gateway-api/releases/download/v1.2.0/standard-install.yaml
```

## Installing the Chart

### In AWS EKS

1. Create a values file for your EKS deployment:

```bash
cat > eks-values.yaml << EOF
# EKS-specific configuration
service:
  enabled: true
  type: LoadBalancer
  annotations:
    service.beta.kubernetes.io/aws-load-balancer-type: "nlb"
    service.beta.kubernetes.io/aws-load-balancer-nlb-target-type: "ip"
    service.beta.kubernetes.io/aws-load-balancer-scheme: "internet-facing"

# Gateway API configuration
gatewayApi:
  enabled: true
  gatewayClassName: "envoy-gateway"
  name: "capture-proxy-gateway"
  namespace: "default"
  listeners:
    - name: http
      port: 80
      protocol: HTTP
      allowedRoutes:
        namespaces:
          from: Same

# Envoy Gateway configuration
envoyGateway:
  install: true
  version: "1.1.0"
  namespace: "envoy-gateway-system"

# Capture Proxy configuration
capture-proxy:
  parameters:
    destinationUri:
      value: "http://your-source-cluster:9200"
    kafkaConnection:
      value: "your-kafka-bootstrap:9092"
EOF
```

2. Install the chart:

```bash
helm install capture-proxy-gateway ./deployment/k8s/charts/components/capture-proxy-gateway -f eks-values.yaml
```

### In Minikube

1. Start Minikube with enough resources:

```bash
minikube start --cpus 4 --memory 8192 --driver=docker
```

2. Enable the Minikube tunnel in a separate terminal:

```bash
minikube tunnel
```

3. Create a values file for your Minikube deployment:

```bash
cat > minikube-values.yaml << EOF
# Minikube-specific configuration
minikube:
  enabled: true
  tunnel: true

service:
  enabled: true
  type: LoadBalancer

# Gateway API configuration
gatewayApi:
  enabled: true
  gatewayClassName: "envoy-gateway"
  name: "capture-proxy-gateway"
  namespace: "default"
  listeners:
    - name: http
      port: 80
      protocol: HTTP
      allowedRoutes:
        namespaces:
          from: Same

# Envoy Gateway configuration
envoyGateway:
  install: true
  version: "1.1.0"
  namespace: "envoy-gateway-system"

# Capture Proxy configuration
capture-proxy:
  parameters:
    destinationUri:
      value: "http://elasticsearch:9200"
    kafkaConnection:
      value: "kafka-bootstrap:9092"
EOF
```

4. Install the chart:

```bash
helm install capture-proxy-gateway ./deployment/k8s/charts/components/capture-proxy-gateway -f minikube-values.yaml
```

## Configuration

The following table lists the configurable parameters of the chart and their default values.

### Capture Proxy Configuration

| Parameter | Description | Default |
|-----------|-------------|---------|
| `capture-proxy.parameters.destinationUri.value` | URI of the source cluster | `"http://sourcecluster.example.com:9200"` |
| `capture-proxy.parameters.listenPort.value` | Port on which the proxy listens | `9200` |
| `capture-proxy.parameters.insecureDestination.value` | Whether to allow insecure connections | `true` |
| `capture-proxy.parameters.kafkaConnection.value` | Kafka broker connection string | `"kafka-bootstrap:9092"` |

### Gateway API Configuration

| Parameter | Description | Default |
|-----------|-------------|---------|
| `gatewayApi.enabled` | Enable Gateway API resources | `true` |
| `gatewayApi.gatewayClassName` | Gateway class name | `"envoy-gateway"` |
| `gatewayApi.name` | Gateway name | `"capture-proxy-gateway"` |
| `gatewayApi.namespace` | Gateway namespace | `"default"` |
| `gatewayApi.listeners` | Gateway listeners configuration | See `values.yaml` |
| `gatewayApi.httpRoutes` | HTTP routes configuration | See `values.yaml` |

### NLB Configuration

| Parameter | Description | Default |
|-----------|-------------|---------|
| `service.enabled` | Enable NLB service | `true` |
| `service.type` | Service type | `"LoadBalancer"` |
| `service.port` | Service port | `9200` |
| `service.targetPort` | Target port | `9200` |
| `service.annotations` | Service annotations | See `values.yaml` |

### TLS Configuration

| Parameter | Description | Default |
|-----------|-------------|---------|
| `tls.enabled` | Enable TLS | `false` |
| `tls.certManager.enabled` | Use cert-manager | `false` |
| `tls.certManager.issuerName` | Cert-manager issuer name | `"letsencrypt-prod"` |
| `tls.certManager.issuerKind` | Cert-manager issuer kind | `"ClusterIssuer"` |
| `tls.certificate.name` | Certificate name | `"capture-proxy-cert"` |
| `tls.certificate.secretName` | Certificate secret name | `"capture-proxy-tls"` |
| `tls.certificate.cert` | Base64 encoded certificate | `""` |
| `tls.certificate.key` | Base64 encoded key | `""` |

### Envoy Gateway Configuration

| Parameter | Description | Default |
|-----------|-------------|---------|
| `envoyGateway.install` | Install Envoy Gateway | `true` |
| `envoyGateway.version` | Envoy Gateway version | `"1.1.0"` |
| `envoyGateway.namespace` | Envoy Gateway namespace | `"envoy-gateway-system"` |
| `envoyGateway.repository` | Envoy Gateway Helm chart repository | `"oci://docker.io/envoyproxy/gateway-helm"` |
| `envoyGateway.chartName` | Envoy Gateway Helm chart name | `"gateway-helm"` |
| `envoyGateway.values` | Envoy Gateway Helm chart values | `{}` |

### Minikube Configuration

| Parameter | Description | Default |
|-----------|-------------|---------|
| `minikube.enabled` | Enable Minikube-specific configuration | `false` |
| `minikube.ip` | Minikube IP address | `"192.168.49.2"` |
| `minikube.tunnel` | Use Minikube tunnel | `true` |

## Accessing the Capture Proxy

After deploying the chart, you can access the capture proxy through the NLB:

```bash
# Get the NLB address
kubectl get service capture-proxy-nlb

# Example output
NAME              TYPE           CLUSTER-IP      EXTERNAL-IP                                                               PORT(S)          AGE
capture-proxy-nlb LoadBalancer   10.100.158.123  a1234567890abcdef.elb.us-west-2.amazonaws.com   9200:32123/TCP   5m
```

You can then use the EXTERNAL-IP to access the capture proxy:

```bash
curl http://a1234567890abcdef.elb.us-west-2.amazonaws.com:9200
```

## Troubleshooting

### Gateway API Resources Not Created

If the Gateway API resources are not created, make sure the Gateway API CRDs are installed:

```bash
kubectl get crd gateways.gateway.networking.k8s.io
```

### Envoy Gateway Not Installed

If Envoy Gateway is not installed, you can install it manually:

```bash
helm install envoy-gateway oci://docker.io/envoyproxy/gateway-helm --version 1.1.0 -n envoy-gateway-system --create-namespace
```

### NLB Not Provisioned

If the NLB is not provisioned in EKS, check the service annotations:

```bash
kubectl describe service capture-proxy-nlb
```

Make sure the AWS Load Balancer Controller is installed in your cluster.

### Upgrade Fails with "Hook Failed" Error

If you encounter an error like `UPGRADE FAILED: post-upgrade hooks failed: warning: Hook post-upgrade capture-proxy-gateway/templates/envoy-gateway.yaml failed: 1 error occurred: * jobs.batch "install-envoy-gateway" already exists` during an upgrade, try the following:

1. Delete the existing job:

```bash
kubectl delete job install-envoy-gateway -n <namespace>
```

2. Try the upgrade again:

```bash
helm upgrade capture-proxy-gateway ./deployment/k8s/charts/components/capture-proxy-gateway -f your-values.yaml
```

Note: The chart has been updated to include unique job names for each revision to prevent this issue in future upgrades.

## Uninstalling the Chart

To uninstall/delete the `capture-proxy-gateway` deployment:

```bash
helm delete capture-proxy-gateway
```

This will remove all the Kubernetes resources associated with the chart and clean up the release.
