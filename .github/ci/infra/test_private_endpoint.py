import runpy
import sys
from pathlib import Path
from types import ModuleType
import unittest
from unittest.mock import Mock, call, patch

with patch.dict(sys.modules, boto3=ModuleType('boto3')):
    provider = runpy.run_path(str(Path(__file__).with_name('private-endpoint.py')))
    response_url = provider['response_url']
    handler = provider['handler']


class TestResponseURL(unittest.TestCase):
    def test_allows_regional_cloudformation_callbacks(self):
        bucket = 'cloudformation-custom-resource-response-uswest2'
        for host in ('s3.us-west-2.amazonaws.com', 's3-us-west-2.amazonaws.com', 's3.amazonaws.com'):
            for url in (f'https://{bucket}.{host}/response', f'https://{host}/{bucket}/response'):
                with self.subTest(url=url):
                    response_url(url, 'us-west-2')

    def test_rejects_callbacks_outside_the_regional_bucket(self):
        bucket = 'cloudformation-custom-resource-response-uswest2'
        for url in (
            f'http://{bucket}.s3-us-west-2.amazonaws.com/response',
            f'https://@{bucket}.s3-us-west-2.amazonaws.com/response',
            f'https://{bucket}.s3-us-west-2.amazonaws.com.attacker.example/response',
            f'https://s3-us-west-2.amazonaws.com/{bucket}-other/response',
            f'https://{bucket}.s3-us-east-1.amazonaws.com/response',
        ):
            with self.subTest(url=url), self.assertRaises(ValueError):
                response_url(url, 'us-west-2')


class TestDelete(unittest.TestCase):
    def delete_without_physical_id(self, aoss, *, tagged=True):
        event = {
            'RequestType': 'Delete',
            'StackId': 'arn:aws:cloudformation:us-west-2:123456789012:stack/ci-test-endpoints/123-abc',
            'ResponseURL': (
                'https://cloudformation-custom-resource-response-uswest2.s3-us-west-2.amazonaws.com/response'
            ),
            'RequestId': 'abc',
            'LogicalResourceId': 'ClassicEndpoint',
            'PhysicalResourceId': 'ci-test-endpoints-ClassicEndpoint-17VWUX7NXHXM5',
            'ResourceProperties': {'Name': 'ci-test-classic', 'VpcId': 'vpc-test'},
        }
        context = Mock(invoked_function_arn='arn:aws:lambda:us-west-2:123456789012:function:ci')
        context.get_remaining_time_in_millis.return_value = 60000
        cloudformation = Mock()
        cloudformation.describe_stacks.return_value = {
            'Stacks': [{'Parameters': [{'ParameterKey': 'VpcId', 'ParameterValue': 'vpc-test'}]}],
        }
        ec2 = Mock()
        tags = [{'Key': 'MigrationCI', 'Value': 'github'}] if tagged else []
        ec2.describe_vpcs.return_value = {'Vpcs': [{'Tags': tags}]}
        with patch.dict('os.environ', AWS_REGION='us-west-2'), \
                patch.object(provider['boto3'], 'client', side_effect=[cloudformation, aoss, ec2], create=True), \
                patch('urllib.request.urlopen') as callback:
            handler(event, context)
        self.assertIn(b'"Status": "SUCCESS"' if tagged else b'"Status": "FAILED"', callback.call_args.args[0].data)

    def test_absent_endpoint_is_already_deleted(self):
        aoss = Mock()
        aoss.list_vpc_endpoints.return_value = {'vpcEndpointSummaries': []}
        self.delete_without_physical_id(aoss)
        aoss.batch_get_vpc_endpoint.assert_not_called()
        aoss.delete_vpc_endpoint.assert_not_called()

    def test_finds_endpoint_created_before_callback_failed(self):
        aoss = Mock()
        name = 'ci-test-classic'
        aoss.list_vpc_endpoints.side_effect = [
            {'nextToken': 'next', 'vpcEndpointSummaries': [{'name': name, 'id': 'vpce-other'}]},
            {'vpcEndpointSummaries': [{'name': name, 'id': 'vpce-test'}]},
        ]
        aoss.batch_get_vpc_endpoint.side_effect = [
            {'vpcEndpointDetails': [{'name': name, 'vpcId': 'vpc-other'}]},
            {'vpcEndpointDetails': [{'id': 'vpce-test', 'name': name, 'vpcId': 'vpc-test', 'status': 'ACTIVE'}]},
            {'vpcEndpointDetails': [], 'vpcEndpointErrorDetails': [{'errorCode': 'NOT_FOUND'}]},
        ]
        self.delete_without_physical_id(aoss)
        self.assertEqual([call(maxResults=100), call(maxResults=100, nextToken='next')],
                         aoss.list_vpc_endpoints.call_args_list)
        aoss.delete_vpc_endpoint.assert_called_once_with(id='vpce-test')

    def test_fallback_leaves_untagged_vpc_endpoint_alone(self):
        aoss = Mock()
        name = 'ci-test-classic'
        aoss.list_vpc_endpoints.return_value = {'vpcEndpointSummaries': [{'name': name, 'id': 'vpce-test'}]}
        aoss.batch_get_vpc_endpoint.return_value = {
            'vpcEndpointDetails': [{'id': 'vpce-test', 'name': name, 'vpcId': 'vpc-test', 'status': 'ACTIVE'}],
        }
        self.delete_without_physical_id(aoss, tagged=False)
        aoss.delete_vpc_endpoint.assert_not_called()
