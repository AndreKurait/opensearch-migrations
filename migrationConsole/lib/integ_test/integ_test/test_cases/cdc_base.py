"""CDC test base: shared constants, K8s helpers, and proxy utilities.

Test IDs 0031-0039 are reserved for CDC variants.
"""
import logging
import subprocess
import time
from typing import Optional

from kubernetes import client, config as k8s_config, watch

from console_link.models.cluster import Cluster

from ..cluster_version import CDC_MIGRATION_COMBINATIONS
from .ma_argo_test_base import MATestBase, MigrationType, MATestUserArguments  # noqa: F401 (re-exported)

logger = logging.getLogger(__name__)

# --- Constants shared across all CDC tests ---
PROXY_DEPLOYMENT_NAME = "capture-proxy"
REPLAYER_LABEL_SELECTOR = "app=replayer"
PROXY_LABEL_SELECTOR = "migrations/proxy=capture-proxy"
PROXY_ENDPOINT = "https://capture-proxy:9201"
CDC_SOURCE_TARGET_COMBINATIONS = CDC_MIGRATION_COMBINATIONS


# --- Shared helpers ---

def load_k8s_config():
    """Load K8s config once. Safe to call multiple times (idempotent)."""
    try:
        k8s_config.load_incluster_config()
    except k8s_config.ConfigException:
        k8s_config.load_kube_config()


def is_pod_ready(pod) -> bool:
    """Check if a pod has phase=Running and condition Ready=True."""
    if pod.status.phase != "Running":
        return False
    for condition in (pod.status.conditions or []):
        if condition.type == "Ready" and condition.status == "True":
            return True
    return False


def wait_for_pod_ready(namespace: str, label_selector: str, timeout_seconds: int = 1200):
    """Wait until a pod matching the label selector is Running and Ready.

    Uses the K8s Watch API for event-driven detection.
    Equivalent to: kubectl wait --for=condition=Ready pod -l <label> -n <ns>
    """
    load_k8s_config()
    v1 = client.CoreV1Api()

    logger.info("Waiting for pod with label '%s' to be Ready (timeout=%ds)...",
                label_selector, timeout_seconds)

    pods = v1.list_namespaced_pod(namespace, label_selector=label_selector)
    for pod in pods.items:
        if is_pod_ready(pod):
            logger.info("Pod %s is already Running and Ready", pod.metadata.name)
            return

    # Resume watch from where the list left off
    w = watch.Watch()
    for event in w.stream(v1.list_namespaced_pod,
                          namespace=namespace,
                          label_selector=label_selector,
                          resource_version=pods.metadata.resource_version,
                          timeout_seconds=timeout_seconds):
        pod = event["object"]
        if is_pod_ready(pod):
            logger.info("Pod %s is Running and Ready", pod.metadata.name)
            w.stop()
            return
        logger.debug("Pod event %s pod=%s phase=%s",
                     event["type"], pod.metadata.name, pod.status.phase or "Unknown")

    raise TimeoutError(f"No pod with label '{label_selector}' reached Ready within {timeout_seconds}s")


def wait_for_replayer_consuming(namespace: str, timeout_seconds: int = 120, interval: int = 5):
    """Wait until the replayer has joined the Kafka consumer group."""
    start = time.time()
    while time.time() - start < timeout_seconds:
        try:
            result = subprocess.run(
                ["kubectl", "logs", "-l", REPLAYER_LABEL_SELECTOR, "-n", namespace, "--tail=100"],
                capture_output=True, text=True, timeout=15
            )
            for line in result.stdout.split("\n"):
                if "KafkaHeartbeat" in line and "partitions=" in line:
                    logger.info("Replayer is actively consuming from Kafka")
                    return
        except Exception as e:
            logger.debug("Replayer log check failed: %s", e)
        time.sleep(interval)
    log_replayer_diagnostics(namespace)
    raise TimeoutError(
        f"Replayer did not join Kafka consumer group within {timeout_seconds}s. "
        f"CDC docs sent after this point will not be replayed to target."
    )


def log_replayer_diagnostics(namespace: str):
    """Log replayer pod state and recent logs before a CDC wait times out."""
    commands = [
        ["get", "pods", "-l", REPLAYER_LABEL_SELECTOR, "-n", namespace, "-o", "wide"],
        ["describe", "pods", "-l", REPLAYER_LABEL_SELECTOR, "-n", namespace],
        ["logs", "-l", REPLAYER_LABEL_SELECTOR, "-n", namespace, "--tail=200"],
        ["logs", "-l", REPLAYER_LABEL_SELECTOR, "-n", namespace, "--previous", "--tail=200"],
    ]
    for args in commands:
        try:
            result = subprocess.run(["kubectl", *args], capture_output=True, text=True, timeout=30)
            command = "kubectl " + " ".join(args)
            if result.stdout.strip():
                logger.info("%s stdout:\n%s", command, result.stdout.strip())
            if result.stderr.strip():
                logger.info("%s stderr:\n%s", command, result.stderr.strip())
        except Exception as e:
            logger.info("Failed to collect replayer diagnostics for kubectl %s: %s", " ".join(args), e)


def make_proxy_cluster(source_cluster):
    """Create a Cluster pointing at the capture-proxy endpoint, inheriting source auth."""
    return Cluster(config={**source_cluster.config, "endpoint": PROXY_ENDPOINT,
                           "allow_insecure": True})


def run_generate_data(cluster: str, index_name: str, num_docs: int):
    """Run 'console clusters generate-data' CLI inside the migration console pod."""
    cmd = [
        "console", "clusters", "generate-data",
        "--cluster", cluster,
        "--index-name", index_name,
        "--num-docs", str(num_docs),
    ]
    logger.info("Running: %s", " ".join(cmd))
    result = subprocess.run(cmd, capture_output=True, text=True, timeout=300)
    if result.returncode != 0:
        raise RuntimeError(f"generate-data failed (exit {result.returncode}): {result.stderr}")
    logger.info("generate-data output: %s", result.stdout.strip())


def _run_describe_consumer_group(group_name: Optional[str],
                                 probe_timeout_seconds: int = 15) -> Optional[str]:
    """Run `console kafka describe-consumer-group` and return its stdout, or
    None if the group does not exist / the command failed / timed out.

    Caller decides what counts as a successful describe — this helper only
    handles transport-level failures.
    """
    cmd = ["console", "kafka", "describe-consumer-group"]
    if group_name is not None:
        cmd.append(group_name)
    try:
        result = subprocess.run(cmd, capture_output=True, text=True, timeout=probe_timeout_seconds)
    except (subprocess.TimeoutExpired, FileNotFoundError):
        return None
    if result.returncode != 0:
        return None
    out = (result.stdout or "")
    if "does not exist" in out:
        return None
    return out


def _iter_native_describe_rows(describe_output: str):
    """Yield column-name -> token dicts for each data row of the native
    `kafka-consumer-groups.sh --describe` table embedded in describe_output.

    The augmented `PARTITION TIME LAG` section is skipped because it has its
    own header. We anchor on the native header row (`GROUP TOPIC PARTITION
    CURRENT-OFFSET LOG-END-OFFSET LAG ...`) and split subsequent non-blank
    rows by whitespace; rows shorter than the header are ignored.
    """
    header_indices = None
    for raw in describe_output.splitlines():
        line = raw.strip()
        if not line:
            header_indices = None
            continue
        tokens = line.split()
        if header_indices is None:
            if tokens[0] == "GROUP" and "PARTITION" in tokens and "CURRENT-OFFSET" in tokens:
                header_indices = {tok: i for i, tok in enumerate(tokens)}
            continue
        # Skip the augmented "PARTITION TIME LAG" sub-table: its header starts
        # with TOPIC, not GROUP, so we drop the native header on a blank line
        # (above) and refuse to parse rows under a non-native header here.
        if "GROUP" not in header_indices:
            continue
        if len(tokens) <= max(header_indices.values()):
            continue
        yield {col: tokens[idx] for col, idx in header_indices.items()}


def _consumer_group_max_lag(group_name: Optional[str] = None,
                            probe_timeout_seconds: int = 15) -> Optional[int]:
    """Return the maximum LAG across all partitions in the consumer-group
    describe table, or None if the group is missing / the describe failed /
    no parseable LAG values are present.

    A row whose LAG is `-` (no committed offset yet) is treated as unbounded
    lag and forces the function to return None — callers will keep polling
    until at least one commit lands.
    """
    out = _run_describe_consumer_group(group_name, probe_timeout_seconds)
    if out is None:
        return None
    saw_row = False
    max_lag: Optional[int] = None
    for row in _iter_native_describe_rows(out):
        saw_row = True
        token = row.get("LAG", "-")
        if token in ("-", ""):
            return None
        try:
            value = int(token)
        except ValueError:
            return None
        if max_lag is None or value > max_lag:
            max_lag = value
    return max_lag if saw_row else None


class ReplayLagDrainTimeout(AssertionError):
    """Raised when the replayer consumer-group fails to drain to the requested
    max-lag threshold within the configured timeout.

    Inherits from AssertionError so it surfaces as a test failure in CI
    reports, not an infrastructure error.
    """


def _wait_for_consumer_group_caught_up(group_name: Optional[str], label: str,
                                       max_allowed_lag: int,
                                       timeout_seconds: int,
                                       interval_seconds: float = 3.0) -> tuple:
    """Bounded poll until the group's max per-partition LAG drops to <=
    `max_allowed_lag`. Returns (succeeded: bool, last_observed: Optional[int]).

    LAG=`-` on any partition (no commit yet) keeps the wait polling — the
    time-lag table is uninformative until at least one auto-commit lands.

    The replayer's in-order commit constraint (OffsetLifecycleTracker) can
    keep LAG=1 around well after every response landed on the target because
    the head-of-line TrafficStream may still be in flight. Callers usually
    pass `max_allowed_lag=1` so a fully-replayed run isn't reported as still
    behind.
    """
    deadline = time.monotonic() + timeout_seconds
    attempt = 0
    last_observed: Optional[int] = None
    display_name = group_name if group_name is not None else "<workflow-resolved>"
    while time.monotonic() < deadline:
        attempt += 1
        observed = _consumer_group_max_lag(group_name)
        last_observed = observed
        if observed is not None and observed <= max_allowed_lag:
            logger.info("[%s] Consumer group '%s' max LAG=%d (<=%d) after %d probe(s)",
                        label, display_name, observed, max_allowed_lag, attempt)
            return True, observed
        time.sleep(interval_seconds)
    logger.warning(
        "[%s] Consumer group '%s' did not reach LAG<=%d within %ds (last observed=%s); "
        "the snapshot below shows the still-behind state.",
        label, display_name, max_allowed_lag, timeout_seconds,
        "<unparsed>" if last_observed is None else last_observed,
    )
    return False, last_observed


def _emit_describe_snapshot(label: str, group_name: Optional[str],
                            timeout_seconds: int) -> None:
    """Run `console kafka describe-consumer-group` once and log the output.

    Infrastructure-level failures (timeout, missing CLI, non-zero exit) are
    logged but never raised — those would mask real test failures.
    """
    cmd = ["console", "kafka", "describe-consumer-group"]
    if group_name is not None:
        cmd.append(group_name)
    logger.info("[%s] Running: %s", label, " ".join(cmd))
    try:
        result = subprocess.run(cmd, capture_output=True, text=True, timeout=timeout_seconds)
    except subprocess.TimeoutExpired:
        logger.warning("[%s] '%s' timed out after %ds; continuing.",
                       label, " ".join(cmd), timeout_seconds)
        return
    except FileNotFoundError:
        logger.warning("[%s] 'console' CLI not found on PATH; skipping consumer-group describe.",
                       label)
        return

    stdout = (result.stdout or "").rstrip()
    stderr = (result.stderr or "").rstrip()
    if result.returncode != 0:
        logger.warning("[%s] describe-consumer-group exited with %d. stdout:\n%s\nstderr:\n%s",
                       label, result.returncode, stdout, stderr)
        return
    if stderr:
        logger.info("[%s] describe-consumer-group stderr:\n%s", label, stderr)
    logger.info("[%s] describe-consumer-group output:\n%s", label, stdout)


def log_kafka_consumer_group_state(label: str, group_name: Optional[str] = None,
                                   timeout_seconds: int = 120) -> None:
    """Pure snapshot: run `console kafka describe-consumer-group` and log
    its output (including the per-partition TIME LAG section). Used for
    in-progress checkpoints like [replay-start] where the replayer hasn't
    drained yet — no waits, no asserts.

    `group_name=None` (the default) defers selection to the console CLI,
    which resolves the workflow-managed group (`replayer-<targetLabel>` in
    EKS) from the workflow config. Pass an explicit name to override.
    """
    _emit_describe_snapshot(label, group_name, timeout_seconds)


def assert_replay_drained(label: str = "replay-end",
                          group_name: Optional[str] = None,
                          max_lag: int = 1,
                          timeout_seconds: int = 600,
                          describe_timeout_seconds: int = 120) -> None:
    """Wait for the replayer consumer-group to drain to max per-partition
    LAG <=`max_lag`, log the describe snapshot, and raise on timeout.

    Use at the post-verification checkpoint ([replay-end]). Without this
    assertion, `verify_clusters` happily passes when the target has the
    expected docs even though the replayer never committed past offset 0
    — a real failure mode we hit on EKS where a single in-flight
    TrafficStream stalled the in-order commit chain forever.

    `max_lag=1` (default) absorbs the in-order commit residue from
    OffsetLifecycleTracker: a head-of-line in-flight TrafficStream can
    keep LAG=1 indefinitely even after every response landed on the
    target. Set higher only if you've consciously decided the test
    tolerates more drift.

    `timeout_seconds=600` (10min) covers production-shaped runs where
    drain takes minutes after `check_doc_counts_match` returns. Override
    if your test is bounded smaller.

    Raises `ReplayLagDrainTimeout` (an AssertionError) on drain stall.
    Infrastructure-level describe failures are logged but never raised.
    """
    drain_succeeded, last_lag = _wait_for_consumer_group_caught_up(
        group_name, label,
        max_allowed_lag=max_lag,
        timeout_seconds=timeout_seconds,
    )
    _emit_describe_snapshot(label, group_name, describe_timeout_seconds)
    if not drain_succeeded:
        raise ReplayLagDrainTimeout(
            f"[{label}] Replayer consumer-group did not drain to LAG<={max_lag} "
            f"within {timeout_seconds}s "
            f"(last observed max LAG={last_lag if last_lag is not None else '<unparsed>'}). "
            f"See the snapshot logged above for per-partition state."
        )


def send_bulk(cluster, index_name: str, start: int, count: int):
    """Send a batch of docs with sequential IDs via _bulk API.

    Doc IDs are doc_{start} through doc_{start+count-1}, each with
    title, value, and category fields.
    """
    import json
    from console_link.models.cluster import HttpMethod
    from ..common_utils import execute_api_call

    lines = []
    for i in range(start, start + count):
        action = json.dumps({"index": {"_index": index_name, "_id": f"doc_{i}"}})
        doc = json.dumps({"title": f"Bulk doc {i}", "value": i, "category": "A" if i % 2 == 0 else "B"})
        lines.append(action)
        lines.append(doc)
    body = "\n".join(lines) + "\n"
    headers = {'Content-Type': 'application/x-ndjson'}
    resp = execute_api_call(cluster=cluster, method=HttpMethod.POST, path="/_bulk",
                            data=body, headers=headers)
    bulk_result = resp.json()
    assert not bulk_result.get("errors"), f"Bulk indexing had errors: {bulk_result}"
