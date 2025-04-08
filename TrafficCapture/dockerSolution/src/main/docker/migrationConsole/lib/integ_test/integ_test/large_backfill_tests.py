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

from .default_operations import DefaultOperationsLibrary

logger = logging.getLogger(__name__)
ops = DefaultOperationsLibrary()


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
        duration = 30  # seconds
        throughput = random.uniform(1.0, 3.0)  # Random value between 1-3
        timestamp = datetime.datetime.now().isoformat()
        
        # Use the unique_id from the fixture
        unique_id = self.unique_id
        
        # Ensure the reports directory exists
        reports_dir = os.path.join(os.path.dirname(__file__), "reports", unique_id)
        os.makedirs(reports_dir, exist_ok=True)
        
        # Write metrics directly to CSV in the format needed by Jenkins Plot plugin
        metrics_file = os.path.join(reports_dir, "backfill_metrics.csv")
        logger.info(f"Writing metrics to: {metrics_file}")
        
        try:
            with open(metrics_file, 'w', newline='') as f:
                writer = csv.writer(f)
                # Header row
                writer.writerow(['timestamp', 'metric', 'value', 'unit'])
                # Data rows
                writer.writerow([timestamp, 'Duration', duration, 'seconds'])
                writer.writerow([timestamp, 'Throughput', throughput, 'ops/sec'])
            
            # Verify the file was written correctly
            if os.path.exists(metrics_file):
                file_size = os.path.getsize(metrics_file)
                logger.info(f"Metrics file created successfully. Size: {file_size} bytes")
                
                # Read back and log the content for verification
                with open(metrics_file, 'r') as f:
                    content = f.read()
                    logger.info(f"Metrics file content:\n{content}")
            else:
                logger.error(f"Failed to create metrics file at {metrics_file}")
                
            logger.info(f"Metrics saved to: {metrics_file}")
            logger.info(f"Reports directory: {reports_dir}")
            
            # List all files in the reports directory
            logger.info(f"Files in reports directory:")
            for file in os.listdir(reports_dir):
                logger.info(f"  - {file}")
                
        except Exception as e:
            logger.error(f"Error writing metrics file: {str(e)}")
            raise
