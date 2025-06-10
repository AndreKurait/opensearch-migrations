import logging
import pytest
import unittest
import csv
import os
from datetime import datetime
from console_link.middleware.clusters import connection_check, clear_cluster, clear_indices, ConnectionResult
from console_link.models.cluster import Cluster
from console_link.models.backfill_base import Backfill
from console_link.models.command_result import CommandResult
from console_link.models.metadata import Metadata
from console_link.cli import Context
import time

from .default_operations import DefaultOperationsLibrary

logger = logging.getLogger(__name__)
ops = DefaultOperationsLibrary()

class Metric:
    def __init__(self, name, value, unit):
        self.name = name
        self.value = value
        self.unit = unit

BACKFILL_SCALE = 80

def generate_csv_data(start_timestamp: datetime, size_in_tib: float):
    global BACKFILL_SCALE
    # Current time as the end timestamp.
    end_timestamp = datetime.now()
    
    # Calculate elapsed duration in seconds.
    duration_seconds = (end_timestamp - start_timestamp).total_seconds()
    duration_minutes = duration_seconds / 60.0
    
    # Convert data sizes:
    # 1 TiB = 1024 GiB; 1 GiB = 1024 MiB.
    size_in_mib = size_in_tib * 1024 * 1024
    size_in_gb = size_in_tib * 1024

    # Calculate throughput (MiB/s). Avoid division by zero.
    throughput_mib_s = size_in_mib / duration_seconds if duration_seconds > 0 else 0
    throughput_mib_s_per_worker = throughput_mib_s / BACKFILL_SCALE
    # Define the metrics.
    metrics = [
        Metric("End Timestamp", end_timestamp.isoformat(), "ISO-8601"),
        Metric("Duration", round(duration_minutes, 2), "min"),
        Metric("Size Transferred", size_in_gb, "GB"),
        Metric("Reindexing Throughput Total", round(throughput_mib_s, 4), "MiB/s"),
        Metric("Reindexing Throughput Per Worker", round(throughput_mib_s_per_worker, 4), "MiB/s")
    ]

    # Prepare the CSV header and row.
    header = [f"{m.name} ({m.unit})" for m in metrics]
    row = [m.value for m in metrics]
    return [header, row]


def preload_data(target_cluster: Cluster):
    # Confirm target connection
    target_con_result: ConnectionResult = connection_check(target_cluster)
    assert target_con_result.connection_established is True

    # Clear all data from cluster
    clear_cluster(target_cluster)
    clear_output = clear_indices(target_cluster)
    if isinstance(clear_output, str) and "Error" in clear_output:
        raise Exception(f"Cluster Clear Indices Failed: {clear_output}")

@pytest.fixture(scope="class")
def setup_backfill(request):
    global BACKFILL_SCALE
    config_path = request.config.getoption("--config_file_path")
    console_env = Context(config_path).env

    preload_data(target_cluster=console_env.target_cluster)


    start_timestamp = datetime.now()

    target: Cluster = console_env.target_cluster
    assert target is not None

    backfill: Backfill = console_env.backfill
    assert backfill is not None
    metadata: Metadata = console_env.metadata
    assert metadata is not None

    metadata_result: CommandResult = metadata.migrate()
    assert metadata_result.success, f"Metadata failed with {metadata_result.value}"

    backfill.create()

    backfill_start_result: CommandResult = backfill.start()
    assert backfill_start_result.success

    # small enough to allow containers to be reused, big enough to test scaling out
    backfill_scale_result: CommandResult = backfill.scale(units=BACKFILL_SCALE)
    assert backfill_scale_result.success

    while True:
        time.sleep(10)
        status_result = backfill.get_status(deep_check=True)
        logger.info("Backfill has status: %s", status_result)
        getTotalClusterSize(target)
        if status_result.value and not isinstance(status_result.value, Exception) and is_backfill_done(status_result.value[1]):
            break
    target.call_api("/_refresh")

    data = generate_csv_data(start_timestamp, getTotalClusterSize(target))

    # Use the unique_id from the fixture
    unique_id = request.config.getoption("--unique_id")

    try:
        # Ensure the reports directory exists
        reports_dir = os.path.join(os.path.dirname(__file__), "reports", unique_id)
        os.makedirs(reports_dir, exist_ok=True)
        
        # Write metrics directly to CSV in the format needed by Jenkins Plot plugin
        metrics_file = os.path.join(reports_dir, "backfill_metrics.csv")
        logger.info(f"Writing metrics to: {metrics_file}")

        write_csv(metrics_file, data)
        logger.info(f"Wrote metrics to: {metrics_file}")              
    except Exception as e:
        logger.error(f"Error writing metrics file: {str(e)}")
        raise


def is_backfill_done(message: str) -> bool:
    return "incomplete: 0" in message and "in progress: 0" in message and "unclaimed: 0" in message


def getTotalClusterSize(cluster: Cluster) -> float:
    response = cluster.call_api("/_stats/store?level=cluster", raise_error=False)
    data = response.json()
    primary_size_bytes = data['_all']['primaries']['store']['size_in_bytes']

    # Convert bytes to tebibytes (TiB)
    primary_size_tib = float(primary_size_bytes) / (1024**4)

    logger.info("Cluster primary store size (TiB): %s", primary_size_tib)
    return primary_size_tib


@pytest.fixture(scope="session", autouse=True)
def cleanup_after_tests(request):
    # Setup code
    logger.info("Starting backfill tests...")

    yield

    config_path = request.config.getoption("--config_file_path")
    console_env = Context(config_path).env

    backfill: Backfill = console_env.backfill
    assert backfill is not None
    backfill.stop()

    target: Cluster = console_env.target_cluster
    assert target is not None
    clear_cluster(target)

    pass

@pytest.mark.usefixtures("setup_backfill")
class BackfillTests(unittest.TestCase):

    @pytest.fixture(autouse=True)
    def _get_unique_id(self, request):
        self.unique_id = request.config.getoption("--unique_id")

    def test_backfill_large_snapshot(self):
        time.sleep(30)


def write_csv(filename, data):
    # data should be a list of rows, where each row is a list of values.
    with open(filename, 'w', newline='') as csvfile:
        writer = csv.writer(csvfile)
        writer.writerows(data)
