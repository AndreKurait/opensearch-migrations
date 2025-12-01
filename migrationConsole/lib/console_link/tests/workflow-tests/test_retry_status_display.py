"""Tests for retry status display functionality in workflow CLI."""

import pytest
from console_link.workflow.services.workflow_service import WorkflowService


class TestRetryInfoExtraction:
    """Test retry information extraction from Argo node messages."""

    def test_extract_retry_info_pattern1(self):
        """Test extraction with 'Retrying in X seconds... (Y/Z)' pattern."""
        service = WorkflowService()
        
        node = {
            "message": "Retrying in 30 seconds... (3/200)",
            "phase": "Running"
        }
        
        attempt, limit = service._extract_retry_info(node)
        
        assert attempt == 3
        assert limit == 200

    def test_extract_retry_info_pattern2(self):
        """Test extraction with 'Retry (X/Y)' pattern."""
        service = WorkflowService()
        
        node = {
            "message": "Retry (15/200)",
            "phase": "Failed"
        }
        
        attempt, limit = service._extract_retry_info(node)
        
        assert attempt == 15
        assert limit == 200

    def test_extract_retry_info_no_retry(self):
        """Test extraction when node has no retry information."""
        service = WorkflowService()
        
        node = {
            "message": "Step completed successfully",
            "phase": "Succeeded"
        }
        
        attempt, limit = service._extract_retry_info(node)
        
        assert attempt is None
        assert limit is None

    def test_extract_retry_info_empty_message(self):
        """Test extraction when node has empty message."""
        service = WorkflowService()
        
        node = {
            "message": "",
            "phase": "Running"
        }
        
        attempt, limit = service._extract_retry_info(node)
        
        assert attempt is None
        assert limit is None

    def test_extract_retry_info_no_message(self):
        """Test extraction when node has no message field."""
        service = WorkflowService()
        
        node = {
            "phase": "Running"
        }
        
        attempt, limit = service._extract_retry_info(node)
        
        assert attempt is None
        assert limit is None

    def test_extract_retry_info_high_attempt_numbers(self):
        """Test extraction with high attempt numbers."""
        service = WorkflowService()
        
        node = {
            "message": "Retrying in 60 seconds... (150/200)",
            "phase": "Running"
        }
        
        attempt, limit = service._extract_retry_info(node)
        
        assert attempt == 150
        assert limit == 200


class TestIsRetryingStep:
    """Test detection of retrying steps."""

    def test_is_retrying_step_with_retry_message(self):
        """Test detection when node is retrying."""
        service = WorkflowService()
        
        node = {
            "phase": "Running",
            "message": "Retrying in 30 seconds... (3/200)"
        }
        
        assert service._is_retrying_step(node) is True

    def test_is_retrying_step_failed_with_retry(self):
        """Test detection when node failed but is retrying."""
        service = WorkflowService()
        
        node = {
            "phase": "Failed",
            "message": "Retry (5/200)"
        }
        
        assert service._is_retrying_step(node) is True

    def test_is_retrying_step_succeeded(self):
        """Test detection when node succeeded (not retrying)."""
        service = WorkflowService()
        
        node = {
            "phase": "Succeeded",
            "message": "Step completed"
        }
        
        assert service._is_retrying_step(node) is False

    def test_is_retrying_step_running_no_retry_message(self):
        """Test detection when node is running but not retrying."""
        service = WorkflowService()
        
        node = {
            "phase": "Running",
            "message": "Executing step"
        }
        
        assert service._is_retrying_step(node) is False

    def test_is_retrying_step_no_message(self):
        """Test detection when node has no message."""
        service = WorkflowService()
        
        node = {
            "phase": "Running"
        }
        
        assert service._is_retrying_step(node) is False


class TestBuildWorkflowTreeWithRetry:
    """Test workflow tree building with retry information."""

    def test_build_tree_with_retry_node(self):
        """Test tree building includes retry information."""
        service = WorkflowService()
        
        nodes = {
            "node-1": {
                "id": "node-1",
                "name": "waitforcompletion",
                "displayName": "Wait for Completion",
                "type": "Pod",
                "phase": "Running",
                "message": "Retrying in 30 seconds... (3/200)",
                "startedAt": "2024-01-01T10:00:00Z",
                "boundaryID": None,
                "children": []
            }
        }
        
        tree = service._build_workflow_tree(nodes)
        
        assert len(tree) == 1
        node = tree[0]
        assert node['retry_attempt'] == 3
        assert node['retry_limit'] == 200
        assert node['message'] == "Retrying in 30 seconds... (3/200)"

    def test_build_tree_with_non_retry_node(self):
        """Test tree building with non-retry node."""
        service = WorkflowService()
        
        nodes = {
            "node-1": {
                "id": "node-1",
                "name": "simple-step",
                "displayName": "Simple Step",
                "type": "Pod",
                "phase": "Succeeded",
                "message": "Step completed",
                "startedAt": "2024-01-01T10:00:00Z",
                "boundaryID": None,
                "children": []
            }
        }
        
        tree = service._build_workflow_tree(nodes)
        
        assert len(tree) == 1
        node = tree[0]
        assert node['retry_attempt'] is None
        assert node['retry_limit'] is None

    def test_build_tree_with_mixed_nodes(self):
        """Test tree building with both retry and non-retry nodes."""
        service = WorkflowService()
        
        nodes = {
            "node-1": {
                "id": "node-1",
                "name": "step1",
                "displayName": "Step 1",
                "type": "Pod",
                "phase": "Succeeded",
                "message": "Completed",
                "startedAt": "2024-01-01T10:00:00Z",
                "boundaryID": None,
                "children": []
            },
            "node-2": {
                "id": "node-2",
                "name": "step2",
                "displayName": "Step 2",
                "type": "Pod",
                "phase": "Running",
                "message": "Retry (10/200)",
                "startedAt": "2024-01-01T10:05:00Z",
                "boundaryID": None,
                "children": []
            }
        }
        
        tree = service._build_workflow_tree(nodes)
        
        assert len(tree) == 2
        
        # Find the retry node
        retry_node = next(n for n in tree if n['name'] == 'step2')
        assert retry_node['retry_attempt'] == 10
        assert retry_node['retry_limit'] == 200
        
        # Find the non-retry node
        normal_node = next(n for n in tree if n['name'] == 'step1')
        assert normal_node['retry_attempt'] is None
        assert normal_node['retry_limit'] is None


class TestStatusDisplayWithRetry:
    """Test status display formatting with retry information."""

    def test_get_step_rich_label_with_retry(self):
        """Test label formatting for retrying step."""
        from console_link.workflow.commands.status import _get_step_rich_label
        
        node = {
            'display_name': 'Wait for Completion',
            'phase': 'Running',
            'type': 'Pod',
            'retry_attempt': 3,
            'retry_limit': 200
        }
        
        label = _get_step_rich_label(node)
        
        assert 'Wait for Completion' in label
        assert 'Attempt 3/200' in label
        assert 'yellow' in label  # Running color

    def test_get_step_rich_label_without_retry(self):
        """Test label formatting for non-retrying step."""
        from console_link.workflow.commands.status import _get_step_rich_label
        
        node = {
            'display_name': 'Simple Step',
            'phase': 'Succeeded',
            'type': 'Pod',
            'retry_attempt': None,
            'retry_limit': None
        }
        
        label = _get_step_rich_label(node)
        
        assert 'Simple Step' in label
        assert 'Attempt' not in label
        assert 'green' in label  # Succeeded color

    def test_get_step_rich_label_retry_failed_phase(self):
        """Test label formatting for retrying step in Failed phase."""
        from console_link.workflow.commands.status import _get_step_rich_label
        
        node = {
            'display_name': 'Retrying Task',
            'phase': 'Failed',
            'type': 'Pod',
            'retry_attempt': 5,
            'retry_limit': 200
        }
        
        label = _get_step_rich_label(node)
        
        assert 'Retrying Task' in label
        assert 'Attempt 5/200' in label
        # Should show as Running (yellow) not Failed (red) when retrying
        assert 'yellow' in label

    def test_get_step_rich_label_suspend_step(self):
        """Test label formatting for Suspend step (should not show retry)."""
        from console_link.workflow.commands.status import _get_step_rich_label
        
        node = {
            'display_name': 'Approval Gate',
            'phase': 'Running',
            'type': 'Suspend',
            'retry_attempt': None,
            'retry_limit': None
        }
        
        label = _get_step_rich_label(node)
        
        assert 'Approval Gate' in label
        assert 'WAITING FOR APPROVAL' in label
        assert 'Attempt' not in label


class TestMultipleFailedRetries:
    """Test handling of multiple failed retry attempts."""

    def test_multiple_failed_retries_shows_only_latest(self):
        """Test that only the latest of multiple failed retries is shown."""
        service = WorkflowService()
        
        # Create 7 failed retry attempts with same display name
        nodes = {}
        for i in range(1, 8):
            node_id = f"node-{i}"
            nodes[node_id] = {
                "id": node_id,
                "name": "checkrfscompletion",
                "displayName": "checkRfsCompletion",
                "type": "Pod",
                "phase": "Failed",
                "message": f"Retry ({i}/200)",
                "startedAt": f"2024-01-01T10:0{i}:00Z",
                "boundaryID": None,
                "children": []
            }
        
        # Add a running step to simulate real workflow
        nodes["node-wait"] = {
            "id": "node-wait",
            "name": "waitforcompletion",
            "displayName": "waitForCompletion",
            "type": "Pod",
            "phase": "Running",
            "message": "Waiting...",
            "startedAt": "2024-01-01T10:08:00Z",
            "boundaryID": None,
            "children": []
        }
        
        tree = service._build_workflow_tree(nodes)
        
        # Should only have 2 nodes: the latest checkRfsCompletion and waitForCompletion
        assert len(tree) == 2
        
        # Find the checkRfsCompletion node
        check_node = next((n for n in tree if n['display_name'] == 'checkRfsCompletion'), None)
        assert check_node is not None
        
        # Verify it's the latest attempt (attempt 7)
        assert check_node['retry_attempt'] == 7
        assert check_node['retry_limit'] == 200
        assert check_node['phase'] == 'Failed'

    def test_multiple_failed_retries_with_retry_limit(self):
        """Test retry limit extraction when multiple retries have failed."""
        service = WorkflowService()
        
        # Create multiple failed attempts
        nodes = {}
        for i in range(1, 5):
            node_id = f"node-{i}"
            nodes[node_id] = {
                "id": node_id,
                "name": "retrystep",
                "displayName": "retryStep",
                "type": "Pod",
                "phase": "Failed",
                "message": f"Retry ({i}/50)",
                "startedAt": f"2024-01-01T10:0{i}:00Z",
                "boundaryID": None,
                "children": []
            }
        
        tree = service._build_workflow_tree(nodes)
        
        # Should only have 1 node (the latest)
        assert len(tree) == 1
        
        node = tree[0]
        assert node['retry_attempt'] == 4
        assert node['retry_limit'] == 50
        assert node['display_name'] == 'retryStep'

    def test_mixed_phases_keeps_only_latest(self):
        """Test that only latest node is kept regardless of phase mix."""
        service = WorkflowService()
        
        # Create nodes with mixed phases
        nodes = {
            "node-1": {
                "id": "node-1",
                "name": "mixedstep",
                "displayName": "mixedStep",
                "type": "Pod",
                "phase": "Failed",
                "message": "Retry (1/100)",
                "startedAt": "2024-01-01T10:01:00Z",
                "boundaryID": None,
                "children": []
            },
            "node-2": {
                "id": "node-2",
                "name": "mixedstep",
                "displayName": "mixedStep",
                "type": "Pod",
                "phase": "Failed",
                "message": "Retry (2/100)",
                "startedAt": "2024-01-01T10:02:00Z",
                "boundaryID": None,
                "children": []
            },
            "node-3": {
                "id": "node-3",
                "name": "mixedstep",
                "displayName": "mixedStep",
                "type": "Pod",
                "phase": "Running",
                "message": "Retrying in 30 seconds... (3/100)",
                "startedAt": "2024-01-01T10:03:00Z",
                "boundaryID": None,
                "children": []
            }
        }
        
        tree = service._build_workflow_tree(nodes)
        
        # Should only have 1 node (the latest - node-3)
        assert len(tree) == 1
        
        node = tree[0]
        assert node['id'] == 'node-3'
        assert node['retry_attempt'] == 3
        assert node['retry_limit'] == 100
        assert node['phase'] == 'Running'


class TestRetryEdgeCases:
    """Test edge cases and error handling for retry functionality."""

    def test_extract_retry_info_malformed_message(self):
        """Test extraction with malformed retry message."""
        service = WorkflowService()
        
        node = {
            "message": "Retrying in seconds (3/)",
            "phase": "Running"
        }
        
        attempt, limit = service._extract_retry_info(node)
        
        assert attempt is None
        assert limit is None

    def test_extract_retry_info_partial_pattern(self):
        """Test extraction with partial pattern match."""
        service = WorkflowService()
        
        node = {
            "message": "Retrying in 30 seconds...",
            "phase": "Running"
        }
        
        attempt, limit = service._extract_retry_info(node)
        
        assert attempt is None
        assert limit is None

    def test_build_tree_with_missing_fields(self):
        """Test tree building with nodes missing optional fields."""
        service = WorkflowService()
        
        nodes = {
            "node-1": {
                "id": "node-1",
                "name": "minimal-step",
                "displayName": "Minimal Step",
                "type": "Pod",
                "phase": "Running",
                # No message field
                "boundaryID": None,
                "children": []
            }
        }
        
        tree = service._build_workflow_tree(nodes)
        
        assert len(tree) == 1
        node = tree[0]
        assert node['message'] is None
        assert node['retry_attempt'] is None
        assert node['retry_limit'] is None

    def test_get_step_rich_label_missing_retry_fields(self):
        """Test label formatting when retry fields are missing."""
        from console_link.workflow.commands.status import _get_step_rich_label
        
        node = {
            'display_name': 'Step Name',
            'phase': 'Running',
            'type': 'Pod'
            # No retry_attempt or retry_limit fields
        }
        
        label = _get_step_rich_label(node)
        
        assert 'Step Name' in label
        assert 'Attempt' not in label


class TestExhaustedRetryDisplay:
    """Test display formatting for exhausted retry attempts."""

    def test_exhausted_retries_shows_consolidated_format(self):
        """Test that exhausted retries show 'Failed (X/X attempts)' format."""
        from console_link.workflow.commands.status import _get_step_rich_label
        
        node = {
            'display_name': 'retry-on-error',
            'phase': 'Failed',
            'type': 'Pod',
            'retry_attempt': 2,
            'retry_limit': 2
        }
        
        label = _get_step_rich_label(node)
        
        assert 'retry-on-error' in label
        assert 'Failed (2/2 attempts)' in label
        assert 'red' in label  # Failed color
        assert '✗' in label  # Failed symbol
        # Should NOT show "Attempt X/Y" format
        assert 'Attempt 2/2' not in label

    def test_active_retry_shows_attempt_format(self):
        """Test that active retries still show 'Attempt X/Y' format."""
        from console_link.workflow.commands.status import _get_step_rich_label
        
        # Test with Running phase
        node_running = {
            'display_name': 'retry-on-error',
            'phase': 'Running',
            'type': 'Pod',
            'retry_attempt': 1,
            'retry_limit': 2
        }
        
        label = _get_step_rich_label(node_running)
        
        assert 'retry-on-error' in label
        assert 'Attempt 1/2' in label
        assert 'yellow' in label  # Running color
        assert 'Failed' not in label
        
        # Test with Failed phase but not exhausted
        node_failed = {
            'display_name': 'retry-on-error',
            'phase': 'Failed',
            'type': 'Pod',
            'retry_attempt': 1,
            'retry_limit': 2
        }
        
        label = _get_step_rich_label(node_failed)
        
        assert 'retry-on-error' in label
        assert 'Attempt 1/2' in label
        assert 'yellow' in label  # Should show as Running (yellow) when retrying
        assert 'Failed (2/2 attempts)' not in label

    def test_mixed_retry_states(self):
        """Test workflow with both active and exhausted retries."""
        from console_link.workflow.commands.status import _get_step_rich_label
        
        # Active retry
        active_node = {
            'display_name': 'step-retrying',
            'phase': 'Running',
            'type': 'Pod',
            'retry_attempt': 3,
            'retry_limit': 5
        }
        
        active_label = _get_step_rich_label(active_node)
        assert 'Attempt 3/5' in active_label
        assert 'yellow' in active_label
        
        # Exhausted retry
        exhausted_node = {
            'display_name': 'step-exhausted',
            'phase': 'Failed',
            'type': 'Pod',
            'retry_attempt': 5,
            'retry_limit': 5
        }
        
        exhausted_label = _get_step_rich_label(exhausted_node)
        assert 'Failed (5/5 attempts)' in exhausted_label
        assert 'red' in exhausted_label
        assert 'Attempt 5/5' not in exhausted_label

    def test_exhausted_retry_single_limit(self):
        """Test exhausted retry with retry_limit=1."""
        from console_link.workflow.commands.status import _get_step_rich_label
        
        node = {
            'display_name': 'single-retry-step',
            'phase': 'Failed',
            'type': 'Pod',
            'retry_attempt': 1,
            'retry_limit': 1
        }
        
        label = _get_step_rich_label(node)
        
        assert 'single-retry-step' in label
        assert 'Failed (1/1 attempts)' in label
        assert 'red' in label
        assert 'Attempt 1/1' not in label

    def test_exhausted_retry_high_limit(self):
        """Test exhausted retry with high retry_limit (200)."""
        from console_link.workflow.commands.status import _get_step_rich_label
        
        node = {
            'display_name': 'high-retry-step',
            'phase': 'Failed',
            'type': 'Pod',
            'retry_attempt': 200,
            'retry_limit': 200
        }
        
        label = _get_step_rich_label(node)
        
        assert 'high-retry-step' in label
        assert 'Failed (200/200 attempts)' in label
        assert 'red' in label
        assert 'Attempt 200/200' not in label

    def test_non_exhausted_at_limit_minus_one(self):
        """Test that retry at limit-1 still shows as active retry."""
        from console_link.workflow.commands.status import _get_step_rich_label
        
        node = {
            'display_name': 'almost-exhausted',
            'phase': 'Failed',
            'type': 'Pod',
            'retry_attempt': 9,
            'retry_limit': 10
        }
        
        label = _get_step_rich_label(node)
        
        assert 'almost-exhausted' in label
        assert 'Attempt 9/10' in label
        assert 'yellow' in label  # Should show as Running (yellow) when retrying
        assert 'Failed (10/10 attempts)' not in label

    def test_exhausted_retry_preserves_step_name(self):
        """Test that exhausted retry display preserves the full step name."""
        from console_link.workflow.commands.status import _get_step_rich_label
        
        node = {
            'display_name': 'checkRfsCompletion',
            'phase': 'Failed',
            'type': 'Pod',
            'retry_attempt': 200,
            'retry_limit': 200
        }
        
        label = _get_step_rich_label(node)
        
        assert 'checkRfsCompletion' in label
        assert 'Failed (200/200 attempts)' in label
        # Verify the exact format matches expected output
        assert '✗' in label
        assert 'red' in label
