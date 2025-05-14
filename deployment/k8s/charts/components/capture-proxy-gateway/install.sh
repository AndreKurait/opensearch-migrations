#!/bin/bash

# Script to install the capture-proxy-gateway chart in EKS or Minikube

set -e

# Function to display usage information
usage() {
  echo "Usage: $0 [options]"
  echo ""
  echo "Options:"
  echo "  -e, --environment <eks|minikube>  Target environment (required)"
  echo "  -n, --namespace <namespace>       Kubernetes namespace (default: default)"
  echo "  -r, --release-name <name>         Helm release name (default: capture-proxy-gateway)"
  echo "  -s, --source-cluster <url>        Source cluster URL (default: http://elasticsearch:9200)"
  echo "  -k, --kafka <url>                 Kafka bootstrap URL (default: kafka-bootstrap:9092)"
  echo "  -h, --help                        Display this help message"
  exit 1
}

# Default values
ENVIRONMENT=""
NAMESPACE="default"
RELEASE_NAME="capture-proxy-gateway"
SOURCE_CLUSTER="http://elasticsearch:9200"
KAFKA="kafka-bootstrap:9092"

# Parse command line arguments
while [[ $# -gt 0 ]]; do
  key="$1"
  case $key in
    -e|--environment)
      ENVIRONMENT="$2"
      shift
      shift
      ;;
    -n|--namespace)
      NAMESPACE="$2"
      shift
      shift
      ;;
    -r|--release-name)
      RELEASE_NAME="$2"
      shift
      shift
      ;;
    -s|--source-cluster)
      SOURCE_CLUSTER="$2"
      shift
      shift
      ;;
    -k|--kafka)
      KAFKA="$2"
      shift
      shift
      ;;
    -h|--help)
      usage
      ;;
    *)
      echo "Unknown option: $1"
      usage
      ;;
  esac
done

# Check if environment is specified
if [[ -z "$ENVIRONMENT" ]]; then
  echo "Error: Environment is required"
  usage
fi

# Check if environment is valid
if [[ "$ENVIRONMENT" != "eks" && "$ENVIRONMENT" != "minikube" ]]; then
  echo "Error: Environment must be either 'eks' or 'minikube'"
  usage
fi

# Create temporary values file
TEMP_VALUES_FILE=$(mktemp)
trap 'rm -f $TEMP_VALUES_FILE' EXIT

# Generate values file based on environment
if [[ "$ENVIRONMENT" == "eks" ]]; then
  cat > $TEMP_VALUES_FILE << EOF
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
  namespace: "$NAMESPACE"
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
      value: "$SOURCE_CLUSTER"
    kafkaConnection:
      value: "$KAFKA"
EOF
else
  cat > $TEMP_VALUES_FILE << EOF
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
  namespace: "$NAMESPACE"
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
      value: "$SOURCE_CLUSTER"
    kafkaConnection:
      value: "$KAFKA"
EOF
fi

# Check if Gateway API CRDs are installed
echo "Checking if Gateway API CRDs are installed..."
if ! kubectl get crd gateways.gateway.networking.k8s.io &> /dev/null; then
  echo "Gateway API CRDs not found. Installing..."
  kubectl apply -f https://github.com/kubernetes-sigs/gateway-api/releases/download/v1.2.0/standard-install.yaml
else
  echo "Gateway API CRDs already installed."
fi

# Create namespace if it doesn't exist
echo "Creating namespace $NAMESPACE if it doesn't exist..."
kubectl create namespace $NAMESPACE --dry-run=client -o yaml | kubectl apply -f -

# Install the chart
echo "Installing $RELEASE_NAME in namespace $NAMESPACE..."
helm upgrade --install $RELEASE_NAME ./deployment/k8s/charts/components/capture-proxy-gateway \
  --namespace $NAMESPACE \
  -f $TEMP_VALUES_FILE

echo "Installation complete!"
echo ""
echo "To access the capture proxy, run:"
echo "kubectl get service capture-proxy-nlb -n $NAMESPACE"
echo ""
echo "For troubleshooting, see the README.md file."
