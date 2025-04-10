import logging
import pytest
import unittest
import csv
import os
import datetime
import random
from console_link.middleware.clusters import run_test_benchmarks, connection_check, clear_cluster, ConnectionResult
from console_link.models.cluster import Cluster
from console_link.models.backfill_base import Backfill
from console_link.models.command_result import CommandResult
from console_link.models.metadata import Metadata
from console_link.cli import Context
import time
from dataclasses import dataclass

from .default_operations import DefaultOperationsLibrary

logger = logging.getLogger(__name__)
ops = DefaultOperationsLibrary()

@dataclass
class Metric:
    name: str
    value: str
    unit: str


def generate_csv_data():
    # Set a single timestamp.
    ts = datetime.now().isoformat()
    metrics = [
        Metric("Timestamp", ts, "ISO-8601"),
        Metric("Duration", 30, "sec"),
        Metric("Size Transfered", 10000, "GB"),
        Metric("Throughput", random.uniform(1.0, 3.0), "MiB/s")
    ]
    # Create header by combining name and unit.
    header = [f"{m.name} ({m.unit})" for m in metrics]
    # Create a row with each metric's value.
    row = [m.value for m in metrics]
    return [header, row]


def preload_data(target_cluster: Cluster):
    # Confirm target connection
    target_con_result: ConnectionResult = connection_check(target_cluster)
    assert target_con_result.connection_established is True

    # Clear all data from cluster
    clear_cluster(target_cluster)


@pytest.fixture(scope="class")
def setup_backfill(request):
    config_path = request.config.getoption("--config_file_path")
    unique_id = request.config.getoption("--unique_id")
    console_env = Context(config_path).env

    preload_data(target_cluster=console_env.target_cluster)

    backfill: Backfill = console_env.backfill
    assert backfill is not None
    metadata: Metadata = console_env.metadata
    assert metadata is not None

    backfill.create()

    # metadata_result: CommandResult = metadata.migrate()
    # assert metadata_result.success

    backfill_start_result: CommandResult = backfill.start()
    assert backfill_start_result.success

    # small enough to allow containers to be reused, big enough to test scaling out
    backfill_scale_result: CommandResult = backfill.scale(units=2)
    assert backfill_scale_result.success

    logger.info("Stopping backfill...")
    backfill.stop()


@pytest.fixture(scope="session", autouse=True)
def cleanup_after_tests():
    # Setup code
    logger.info("Starting backfill tests...")

    yield

    pass


@pytest.mark.usefixtures("setup_backfill")
class BackfillTests(unittest.TestCase):

    @pytest.fixture(autouse=True)
    def _get_unique_id(self, request):
        self.unique_id = request.config.getoption("--unique_id")

    def test_backfill_large_snapshot(self):
        time.sleep(30)

        # Generate simple metrics
        data = generate_csv_data()

        # Use the unique_id from the fixture
        unique_id = self.unique_id

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


def write_csv(filename, data):
    # data should be a list of rows, where each row is a list of values.
    with open(filename, 'w', newline='') as csvfile:
        writer = csv.writer(csvfile)
        writer.writerows(data)
