import runpy
import sys
from pathlib import Path
from types import ModuleType
import unittest
from unittest.mock import patch

with patch.dict(sys.modules, boto3=ModuleType('boto3')):
    response_url = runpy.run_path(str(Path(__file__).with_name('private-endpoint.py')))['response_url']


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
