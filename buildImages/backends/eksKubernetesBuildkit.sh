#!/usr/bin/env bash

# Build backend for cloud Kubernetes contexts (EKS / GKE / AKS / migration-eks-).
# Uses docker buildx with the kubernetes driver to spin up amd64 + arm64 buildkit
# Pods directly on the cluster's build-nodepool. Local kind go through
# buildImages/backends/dockerHostedBuildkit.sh instead.

set -euo pipefail

if [[ -z "${MIGRATIONS_REPO_ROOT_DIR:-}" ]]; then
  MIGRATIONS_REPO_ROOT_DIR="$(git rev-parse --show-toplevel)"
fi

EKS_CONTEXT_PATTERN='(eks:|gke_|aks-|migration-eks-)'

get_k8s_build_context() {
  if [[ -n "${KUBE_CONTEXT:-}" ]]; then
    echo "${KUBE_CONTEXT}"
  else
    kubectl config current-context 2>/dev/null
  fi
}

set_k8s_context_args() {
  CONTEXT_ARGS=()
  HELM_CONTEXT_ARGS=()
  if [[ -n "${KUBE_CONTEXT:-}" ]]; then
    CONTEXT_ARGS=("--context=${KUBE_CONTEXT}")
    HELM_CONTEXT_ARGS=("--kube-context=${KUBE_CONTEXT}")
  fi
}

require_eks_context() {
  local context
  context="$(get_k8s_build_context)"
  if [[ ! "${context}" =~ ${EKS_CONTEXT_PATTERN} ]]; then
    echo "ERROR: eksKubernetesBuildkit.sh requires a cloud K8s context matching ${EKS_CONTEXT_PATTERN}." >&2
    echo "Current context: '${context}'. Otherwise, use buildImages/backends/dockerHostedBuildkit.sh." >&2
    return 1
  fi
}

# A buildkit pod that never becomes Ready almost always means no node was provisioned for it, and
# the reason lives on the Karpenter NodeClaim rather than on the pod. On an EKS Auto Mode cluster the
# usual causes are a NodePool pointing at a NodeClass that does not exist, or the launch itself being
# rejected -- e.g. an SCP or IAM policy that requires tags on ec2:RunInstances. Both show up here as
# a NodeClaim stuck without a providerID and an AccessDenied in its events, so dump enough to name
# the failing action instead of leaving "0 replicas ready" as the only evidence.
dump_node_provisioning_state() {
  echo "=== node provisioning state (why no node?) ===" >&2
  kubectl ${CONTEXT_ARGS[@]+"${CONTEXT_ARGS[@]}"} get nodes -o wide >&2 || true
  kubectl ${CONTEXT_ARGS[@]+"${CONTEXT_ARGS[@]}"} get nodepools,nodeclasses -o wide >&2 || true
  kubectl ${CONTEXT_ARGS[@]+"${CONTEXT_ARGS[@]}"} get nodeclaims -o wide >&2 || true
  # The launch error text is in the NodeClaim's events/conditions.
  kubectl ${CONTEXT_ARGS[@]+"${CONTEXT_ARGS[@]}"} describe nodeclaims >&2 || true
  kubectl ${CONTEXT_ARGS[@]+"${CONTEXT_ARGS[@]}"} get events -A \
    --sort-by=.lastTimestamp >&2 2>/dev/null | tail -60 >&2 || true
  echo "=== end node provisioning state ===" >&2
}

ensure_eks_buildkit_release() {
  set_k8s_context_args
  require_eks_context

  local -a reuse_values_args=()
  if helm ${HELM_CONTEXT_ARGS[@]+"${HELM_CONTEXT_ARGS[@]}"} status buildkit -n buildkit >/dev/null 2>&1; then
    echo "Reconciling existing buildImages helm release while preserving its values..."
    reuse_values_args=(--reuse-values)
  else
    echo "Installing buildImages helm chart for nodepool..."
  fi

  # shellcheck disable=SC2086
  helm upgrade --install buildkit "${MIGRATIONS_REPO_ROOT_DIR}/deployment/k8s/charts/components/buildImages" \
    ${HELM_CONTEXT_ARGS[@]+"${HELM_CONTEXT_ARGS[@]}"} \
    ${reuse_values_args[@]+"${reuse_values_args[@]}"} \
    --create-namespace \
    -n buildkit \
    --set skipBuildJob=true \
    --set namespace=buildkit \
    --set awsEKSEnabled=true \
    --set multiArchNative=true \
    --set deployBuildkitPods=false \
    ${BUILDKIT_IMAGE:+--set buildkitImage="$BUILDKIT_IMAGE"} \
    ${BUILDKIT_HELM_ARGS:-}
}

run_with_optional_timeout() {
  local timeout_value="$1"
  shift

  if command -v gtimeout >/dev/null 2>&1; then
    gtimeout "${timeout_value}" "$@"
  elif command -v timeout >/dev/null 2>&1; then
    timeout "${timeout_value}" "$@"
  else
    echo "No timeout command found; running without an external ${timeout_value} timeout." >&2
    "$@"
  fi
}

duration_to_seconds() {
  local value="$1"
  if [[ "${value}" =~ ^([0-9]+)$ ]]; then
    echo "${BASH_REMATCH[1]}"
    return 0
  fi
  if [[ "${value}" =~ ^([0-9]+)([smh])$ ]]; then
    local amount="${BASH_REMATCH[1]}"
    local unit="${BASH_REMATCH[2]}"
    case "${unit}" in
      s) echo "${amount}" ;;
      m) echo $((amount * 60)) ;;
      h) echo $((amount * 3600)) ;;
    esac
    return 0
  fi
  echo "ERROR: Unsupported duration '${value}'. Use seconds, or an s/m/h suffix such as 900s, 15m, or 1h." >&2
  return 1
}

# Older buildx versions return success from inspect even when a node failed to
# bootstrap. Require both native builders to be running before starting a bake.
buildx_nodes_are_ready() {
  awk '
    /^Name:/ { name = $2 }
    /^Status:/ { status[name] = $2 }
    END {
      exit !(status["builder-amd64"] == "running" && status["builder-arm64"] == "running")
    }
  '
}

ensure_eks_buildx_builder() {
  local context builder_name namespace
  set_k8s_context_args
  require_eks_context

  context="$(get_k8s_build_context)"
  # -pinned: builders from before pinning follow the ambient context; never reuse or rm one.
  builder_name="builder-${context//[^a-zA-Z0-9_-]/-}${KUBE_CONTEXT:+-pinned}"
  namespace="${BUILDKIT_NAMESPACE:-buildkit}"
  local build_timeout="${BUILDKIT_BOOTSTRAP_TIMEOUT:-900s}"
  local timeout_seconds
  timeout_seconds="$(duration_to_seconds "${build_timeout}")"
  local max_attempts="${BUILDKIT_BOOTSTRAP_ATTEMPTS:-0}"
  if [[ ! "${max_attempts}" =~ ^[0-9]+$ ]]; then
    echo "ERROR: BUILDKIT_BOOTSTRAP_ATTEMPTS must be a non-negative integer." >&2
    return 1
  fi

  # The kubernetes driver has no context option. It records $KUBECONFIG at create time and always
  # uses that file's current-context, so give the builder a kubeconfig holding only KUBE_CONTEXT.
  local builder_kubeconfig="${KUBECONFIG:-}"
  if [[ -n "${KUBE_CONTEXT:-}" ]]; then
    builder_kubeconfig="${BUILDKIT_KUBECONFIG_DIR:-${HOME}/.kube/buildx}/${builder_name}.kubeconfig"
    (umask 077 && mkdir -p "$(dirname "${builder_kubeconfig}")" &&
      kubectl config view --minify --flatten --context="${KUBE_CONTEXT}" > "${builder_kubeconfig}") || return 1
  fi

  docker buildx rm "${builder_name}" 2>/dev/null || true

  echo "Creating buildx builder with the kubernetes driver for context ${context}"
  BUILDKIT_RESOURCE_OPTS=(
    --driver-opt="requests.cpu=${BUILDKIT_REQUESTS_CPU:-4}"
    --driver-opt="requests.memory=${BUILDKIT_REQUESTS_MEMORY:-8Gi}"
    --driver-opt="limits.cpu=${BUILDKIT_LIMITS_CPU:-8}"
    --driver-opt="limits.memory=${BUILDKIT_LIMITS_MEMORY:-16Gi}"
  )

  if [[ "${CI_AWS_DOCKERFILE_NETWORK:-}" == none ]]; then
    BUILDKIT_RESOURCE_OPTS+=(--buildkitd-flags=--oci-worker-net=bridge)
  fi

  KUBECONFIG="${builder_kubeconfig}" docker buildx create \
    --name="${builder_name}" \
    --driver=kubernetes \
    --platform=linux/amd64 \
    --node=builder-amd64 \
    --driver-opt="namespace=${namespace}" \
    --driver-opt="nodeselector=kubernetes.io/arch=amd64" \
    --driver-opt='"tolerations=key=build-nodepool,value=true,effect=NoSchedule"' \
    "${BUILDKIT_RESOURCE_OPTS[@]}" \
    ${BUILDKIT_IMAGE:+--driver-opt="image=${BUILDKIT_IMAGE}"}

  KUBECONFIG="${builder_kubeconfig}" docker buildx create \
    --append \
    --name="${builder_name}" \
    --driver=kubernetes \
    --platform=linux/arm64 \
    --node=builder-arm64 \
    --driver-opt="namespace=${namespace}" \
    --driver-opt="nodeselector=kubernetes.io/arch=arm64" \
    --driver-opt='"tolerations=key=build-nodepool,value=true,effect=NoSchedule"' \
    "${BUILDKIT_RESOURCE_OPTS[@]}" \
    ${BUILDKIT_IMAGE:+--driver-opt="image=${BUILDKIT_IMAGE}"}

  export BUILDX_BUILDER="${builder_name}"  # scoped to this shell; `docker buildx use` is global
  echo "BUILDX_BUILDER=${builder_name}"
  echo "Bootstrapping builder..."

  # The kubernetes driver creates its builder Deployments as part of buildx
  # bootstrap. Retry within the overall deadline to allow for nodepool cold
  # starts; older buildx versions reject the Kubernetes driver's timeout option.
  # A kubectl pre-wait cannot help before buildx has created anything.
  local deadline=$(( $(date +%s) + timeout_seconds ))
  local attempt=1
  while true; do
    local now remaining attempt_label builder_status
    now="$(date +%s)"
    remaining=$((deadline - now))
    if [[ "${remaining}" -le 0 ]]; then
      echo "ERROR: buildx bootstrap did not complete within ${build_timeout}; dumping buildkit namespace state" >&2
      kubectl ${CONTEXT_ARGS[@]+"${CONTEXT_ARGS[@]}"} \
        get all -n "${namespace}" >&2 || true
      kubectl ${CONTEXT_ARGS[@]+"${CONTEXT_ARGS[@]}"} \
        describe pods -n "${namespace}" >&2 || true
      dump_node_provisioning_state
      return 1
    fi

    if [[ "${max_attempts}" -gt 0 ]]; then
      attempt_label="attempt ${attempt}/${max_attempts}"
    else
      attempt_label="attempt ${attempt}"
    fi
    echo "Bootstrapping buildx builder (${attempt_label}, ${remaining}s remaining of ${build_timeout})..."
    if builder_status="$(run_with_optional_timeout "${remaining}s" docker buildx inspect "${builder_name}" --bootstrap 2>&1)"; then
      printf '%s\n' "${builder_status}"
      if buildx_nodes_are_ready <<< "${builder_status}"; then
        break
      fi
      echo "buildx inspect returned success but both native builders are not running." >&2
    else
      printf '%s\n' "${builder_status}" >&2
    fi
    echo "buildx bootstrap failed on ${attempt_label}; current buildkit namespace state:" >&2
    kubectl ${CONTEXT_ARGS[@]+"${CONTEXT_ARGS[@]}"} \
      get deployments,replicasets,pods -n "${namespace}" -o wide >&2 || true

    if [[ "${max_attempts}" -gt 0 && "${attempt}" -ge "${max_attempts}" ]]; then
      echo "ERROR: buildx bootstrap exhausted ${max_attempts} attempt(s) before ${build_timeout}; dumping buildkit namespace state" >&2
      kubectl ${CONTEXT_ARGS[@]+"${CONTEXT_ARGS[@]}"} \
        get all -n "${namespace}" >&2 || true
      kubectl ${CONTEXT_ARGS[@]+"${CONTEXT_ARGS[@]}"} \
        describe pods -n "${namespace}" >&2 || true
      dump_node_provisioning_state
      return 1
    fi
    attempt=$((attempt + 1))
    now="$(date +%s)"
    remaining=$((deadline - now))
    if [[ "${remaining}" -le 0 ]]; then
      continue
    fi
    if [[ "${remaining}" -lt 10 ]]; then
      sleep "${remaining}"
    else
      sleep 10
    fi
  done
}

setup_build_backend() {
  export BUILDKIT_NAMESPACE="${BUILDKIT_NAMESPACE:-buildkit}"
  ensure_eks_buildkit_release
  ensure_eks_buildx_builder
}

# Install the packet filter before the BuildKit daemon starts. Init containers run
# again on replacement pods, sharing the daemon's network namespace. Keeping the
# default Dockerfile network mode preserves cache keys from the recording build.
restrict_eks_buildkit_egress() {
  [[ "${CI_AWS_DOCKERFILE_NETWORK:-}" == none ]] || return 0
  local namespace=${BUILDKIT_NAMESPACE:-buildkit} name deployment image patch
  local -a kube=(kubectl "${CONTEXT_ARGS[@]}" -n "$namespace")
  local rules='set -eu
    iptables -t raw -I PREROUTING -i buildkit0 -j DROP
    iptables -t raw -C PREROUTING -i buildkit0 -j DROP
    if test -e /proc/net/if_inet6; then
      ip6tables -t raw -I PREROUTING -i buildkit0 -j DROP
      ip6tables -t raw -C PREROUTING -i buildkit0 -j DROP
    fi'
  if [[ -n ${CI_BUILDKIT_PACKAGE_ENDPOINT:-} ]]; then
    [[ $CI_BUILDKIT_PACKAGE_ENDPOINT =~ ^([0-9]{1,3}\.){3}[0-9]{1,3}:8080$ ]] || return 1
    rules+=$'\n'"iptables -t raw -I PREROUTING -i buildkit0 -p tcp -d ${CI_BUILDKIT_PACKAGE_ENDPOINT%:*}/32 --dport 8080 -j ACCEPT"
  fi
  for name in builder-amd64 builder-arm64; do
    deployment=$("${kube[@]}" get deployment "$name" -o json)
    jq -e '.spec.template.spec.containers[] | select(.name == "buildkitd") | .args | index("--oci-worker-net=bridge") != null' <<<"$deployment" >/dev/null || return 1
    image=$(jq -er '.spec.template.spec.containers[] | select(.name == "buildkitd") | .image' <<<"$deployment")
    patch=$(jq -nc --arg image "$image" --arg rules "$rules" '{spec:{template:{spec:{initContainers:[{name:"block-build-step-egress",image:$image,command:["/bin/sh","-ec",$rules],securityContext:{privileged:true}}]}}}}')
    "${kube[@]}" patch deployment "$name" --type=strategic --patch "$patch" >/dev/null || return 1
    "${kube[@]}" rollout status deployment/"$name" --timeout=10m || return 1
  done
}

start_eks_package_relay() {
  [[ -n ${CI_DEPENDENCY_PROXY_URL:-} && -n ${CODEBUILD_BUILD_ID:-} ]] || return 0
  export CI_EKS_PACKAGE_BRIDGE="${CI_EKS_PACKAGE_BRIDGE:-${CI_DEPENDENCY_REGISTRY_ENDPOINT:?Missing worker package bridge}}"
  local namespace=${BUILDKIT_NAMESPACE:-buildkit} name=ci-package-relay
  local -a kube=(kubectl "${CONTEXT_ARGS[@]}" -n "$namespace")
  local source="$base_dir/buildImages/backends/eksPackageRelay.py" image service_ip selectors port i
  image="${ECR_HOST:?}/$(mirror_ecr_repo_name mirror.gcr.io/library/amazoncorretto):21-al2023-headless"
  service_ip=$(kubectl "${CONTEXT_ARGS[@]}" -n default get svc kubernetes -o jsonpath='{.spec.clusterIP}')
  service_ip="${service_ip%.*}.9"
  selectors=$("${kube[@]}" get deployment builder-amd64 builder-arm64 -o json | jq -ce '[.items[].spec.selector | {podSelector:.}] | select(length==2)')
  "${kube[@]}" create configmap "$name" --from-file="relay.py=$source" --dry-run=client -o yaml | "${kube[@]}" apply -f - || return 1
  jq -n --argjson from "$selectors" --arg ip "$service_ip" --arg image "$image" --arg name "$name" '
    {app:$name} as $labels | {name:$name} as $meta | {kind:"List",apiVersion:"v1",items:[
      {apiVersion:"networking.k8s.io/v1",kind:"NetworkPolicy",metadata:$meta,spec:{podSelector:{matchLabels:$labels},policyTypes:["Ingress","Egress"],egress:[],ingress:[{from:$from,ports:[{port:8080,protocol:"TCP"}]}]}},
      {apiVersion:"v1",kind:"Service",metadata:$meta,spec:{selector:$labels,clusterIP:$ip,ports:[{port:8080}]}},
      {apiVersion:"v1",kind:"Pod",metadata:($meta+{labels:$labels}),spec:{automountServiceAccountToken:false,volumes:[{name:"code",configMap:{name:$name}}],containers:[{name:"relay",image:$image,command:["/usr/bin/python3","/app/relay.py","pod","8080","8081"],readinessProbe:{tcpSocket:{port:8080},periodSeconds:1},volumeMounts:[{name:"code",mountPath:"/app",readOnly:true}],securityContext:{runAsUser:65534,allowPrivilegeEscalation:false,readOnlyRootFilesystem:true,capabilities:{drop:["ALL"]}}}]}}]}' | "${kube[@]}" apply -f - || return 1
  "${kube[@]}" wait --for=condition=Ready pod/"$name" --timeout=5m || return 1
  CI_PACKAGE_FORWARD_LOG=$(mktemp)
  # A new EKS node can report its pod ready before the API server can open a kubelet tunnel.
  local deadline=$((SECONDS + 180))
  while ((SECONDS < deadline)); do
    "${kube[@]}" port-forward "pod/$name" :8081 --address 127.0.0.1 >"$CI_PACKAGE_FORWARD_LOG" 2>&1 &
    CI_PACKAGE_FORWARD_PID=$!
    for ((i=0; i<100; i++)); do
      port=$(sed -nE 's/^Forwarding from 127\.0\.0\.1:([0-9]+) -> .*/\1/p' "$CI_PACKAGE_FORWARD_LOG")
      [[ -z $port ]] || break
      kill -0 "$CI_PACKAGE_FORWARD_PID" 2>/dev/null || break
      sleep 0.1
    done
    [[ -z $port ]] || break
    kill "$CI_PACKAGE_FORWARD_PID" 2>/dev/null || true
    wait "$CI_PACKAGE_FORWARD_PID" 2>/dev/null || true
    unset CI_PACKAGE_FORWARD_PID
    cat "$CI_PACKAGE_FORWARD_LOG" >&2
    sleep 3
  done
  [[ -n $port ]] || return 1
  # Keep the worker's selected loopback port stable if the API server drops an established tunnel.
  (
    forward_pid=
    trap 'if [[ -n $forward_pid ]]; then kill "$forward_pid" 2>/dev/null || true; fi; exit 0' TERM INT
    while kill -0 "$CI_PACKAGE_FORWARD_PID" 2>/dev/null; do sleep 1; done
    while true; do
      echo "Reconnecting EKS package relay on port $port" >&2
      "${kube[@]}" port-forward "pod/$name" "$port:8081" --address 127.0.0.1 >"$CI_PACKAGE_FORWARD_LOG" 2>&1 &
      forward_pid=$!
      wait "$forward_pid" || true
      forward_pid=
      cat "$CI_PACKAGE_FORWARD_LOG" >&2
      sleep 2
    done
  ) &
  CI_PACKAGE_RECONNECT_PID=$!
  python3 "$source" worker "$port" &
  CI_PACKAGE_WORKER_PID=$!
  local builder
  for builder in builder-amd64 builder-arm64; do
    "${kube[@]}" exec "deployment/$builder" -- sh -ec '
      for attempt in $(seq 1 30); do
        wget -T 3 -O /dev/null "http://$1:8080/healthz" 2>/dev/null && exit 0
        sleep 1
      done
      echo "BuildKit cannot reach the package relay at $1:8080" >&2
      exit 1' sh "$service_ip" || return 1
  done
  export CI_BUILDKIT_PACKAGE_ENDPOINT="$service_ip:8080"
}
