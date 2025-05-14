#!/bin/bash

# Script to test the capture-proxy-gateway deployment

set -e

# Function to display usage information
usage() {
  echo "Usage: $0 [options]"
  echo ""
  echo "Options:"
  echo "  -n, --namespace <namespace>       Kubernetes namespace (default: default)"
  echo "  -u, --url <url>                   URL to test (default: auto-detect from service)"
  echo "  -p, --path <path>                 Path to test (default: /)"
  echo "  -h, --help                        Display this help message"
  exit 1
}

# Default values
NAMESPACE="default"
URL=""
PATH="/"

# Parse command line arguments
while [[ $# -gt 0 ]]; do
  key="$1"
  case $key in
    -n|--namespace)
      NAMESPACE="$2"
      shift
      shift
      ;;
    -u|--url)
      URL="$2"
      shift
      shift
      ;;
    -p|--path)
      PATH="$2"
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

echo "Testing capture-proxy-gateway deployment in namespace $NAMESPACE"

# Check if the Gateway resource exists
if ! kubectl get gateway capture-proxy-gateway -n $NAMESPACE &> /dev/null; then
  echo "Error: Gateway 'capture-proxy-gateway' not found in namespace $NAMESPACE"
  exit 1
fi

echo "✅ Gateway resource found"

# Check if the HTTPRoute resource exists
if ! kubectl get httproute capture-proxy-route -n $NAMESPACE &> /dev/null; then
  echo "Error: HTTPRoute 'capture-proxy-route' not found in namespace $NAMESPACE"
  exit 1
fi

echo "✅ HTTPRoute resource found"

# Check if the NLB service exists
if ! kubectl get service capture-proxy-nlb -n $NAMESPACE &> /dev/null; then
  echo "Error: Service 'capture-proxy-nlb' not found in namespace $NAMESPACE"
  exit 1
fi

echo "✅ NLB service found"

# Get the NLB URL if not provided
if [[ -z "$URL" ]]; then
  echo "Auto-detecting NLB URL..."
  
  # Try to get the external IP/hostname
  EXTERNAL_IP=$(kubectl get service capture-proxy-nlb -n $NAMESPACE -o jsonpath='{.status.loadBalancer.ingress[0].hostname}')
  
  # If hostname is empty, try IP
  if [[ -z "$EXTERNAL_IP" ]]; then
    EXTERNAL_IP=$(kubectl get service capture-proxy-nlb -n $NAMESPACE -o jsonpath='{.status.loadBalancer.ingress[0].ip}')
  fi
  
  # If still empty, check if running in Minikube
  if [[ -z "$EXTERNAL_IP" ]]; then
    echo "No external IP/hostname found. Checking if running in Minikube..."
    if command -v minikube &> /dev/null; then
      EXTERNAL_IP=$(minikube ip)
      echo "Using Minikube IP: $EXTERNAL_IP"
    else
      echo "Error: Could not determine external IP/hostname for the NLB service"
      exit 1
    fi
  fi
  
  # Get the port
  PORT=$(kubectl get service capture-proxy-nlb -n $NAMESPACE -o jsonpath='{.spec.ports[0].port}')
  
  URL="http://$EXTERNAL_IP:$PORT"
fi

echo "Testing URL: $URL$PATH"

# Test the endpoint
echo "Sending test request..."
RESPONSE=$(curl -s -o /dev/null -w "%{http_code}" "$URL$PATH")

if [[ "$RESPONSE" == "200" ]]; then
  echo "✅ Test successful! Received HTTP 200 response"
else
  echo "⚠️ Test received HTTP $RESPONSE response"
fi

# Check if Envoy Gateway is running
if kubectl get deployment -n envoy-gateway-system envoy-gateway-controller &> /dev/null; then
  echo "✅ Envoy Gateway is running"
  
  # Get Envoy Gateway version
  EG_VERSION=$(kubectl get deployment -n envoy-gateway-system envoy-gateway-controller -o jsonpath='{.spec.template.spec.containers[0].image}' | grep -oE '[0-9]+\.[0-9]+\.[0-9]+')
  echo "   Version: $EG_VERSION"
  
  # Check Envoy Gateway pods
  EG_PODS=$(kubectl get pods -n envoy-gateway-system -l app.kubernetes.io/name=envoy-gateway -o jsonpath='{.items[*].status.phase}')
  if [[ "$EG_PODS" == *"Running"* ]]; then
    echo "   Pods: Running"
  else
    echo "   Pods: Not all running"
  fi
  
  # Check if Gateway class is available
  if kubectl get gatewayclass envoy-gateway &> /dev/null; then
    echo "   Gateway Class: Available"
  else
    echo "   Gateway Class: Not available"
  fi
else
  echo "⚠️ Envoy Gateway is not running"
fi

# Check if capture proxy pods are running
CP_PODS=$(kubectl get pods -n $NAMESPACE -l app=capture-proxy -o jsonpath='{.items[*].status.phase}')
if [[ "$CP_PODS" == *"Running"* ]]; then
  echo "✅ Capture Proxy pods are running"
  
  # Count running pods
  RUNNING_PODS=$(kubectl get pods -n $NAMESPACE -l app=capture-proxy --field-selector status.phase=Running | grep -c capture-proxy || echo 0)
  echo "   Running pods: $RUNNING_PODS"
else
  echo "⚠️ Capture Proxy pods are not running"
fi

echo ""
echo "Test completed!"
