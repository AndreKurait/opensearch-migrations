import runpy
import sys
from pathlib import Path
from types import ModuleType
import unittest
from unittest.mock import Mock, patch

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
    def test_delete_after_create_failed_to_return_physical_id(self):
        event = {
            'RequestType': 'Delete',
            'StackId': 'arn:aws:cloudformation:us-west-2:123456789012:stack/ci-test-endpoints/123-abc',
            'ResponseURL': 'https://cloudformation-custom-resource-response-uswest2.s3-us-west-2.amazonaws.com/response',
            'RequestId': 'abc',
            'LogicalResourceId': 'ClassicEndpoint',
            'PhysicalResourceId': 'ci-test-endpoints-ClassicEndpoint-17VWUX7NXHXM5',
            'ResourceProperties': {'Name': 'ci-test-classic', 'VpcId': 'vpc-test'},
        }
        context = Mock(invoked_function_arn='arn:aws:lambda:us-west-2:123456789012:function:ci')
        cloudformation = Mock()
        cloudformation.describe_stacks.return_value = {
            'Stacks': [{'Parameters': [{'ParameterKey': 'VpcId', 'ParameterValue': 'vpc-test'}]}],
        }
        aoss = Mock()
        with patch.dict('os.environ', AWS_REGION='us-west-2'), \
                patch.object(provider['boto3'], 'client', side_effect=[cloudformation, aoss], create=True), \
                patch('urllib.request.urlopen') as callback:
            handler(event, context)
        aoss.batch_get_vpc_endpoint.assert_not_called()
        aoss.delete_vpc_endpoint.assert_not_called()
        self.assertIn(b'"Status": "SUCCESS"', callback.call_args.args[0].data)
