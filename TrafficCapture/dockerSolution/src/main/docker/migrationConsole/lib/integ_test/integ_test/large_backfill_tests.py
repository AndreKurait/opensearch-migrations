import logging
import pytest
import unittest
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
def setup_large_backfill(request):
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

    def test_backfill_large_snapshot(self):
        time.sleep(30)

        ## Publish sample metrics
        Duration = 30
        Throughput = math.rand(1, 3)