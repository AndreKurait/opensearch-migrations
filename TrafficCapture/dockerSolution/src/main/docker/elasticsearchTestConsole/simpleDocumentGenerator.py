#!/usr/bin/env python
import sys
import requests
import time
import argparse
from datetime import datetime
import urllib3
import os

# Disable InsecureRequestWarning
urllib3.disable_warnings(urllib3.exceptions.InsecureRequestWarning)

# Set the base URL from the environment variable SOURCE_DOMAIN_ENDPOINT or use a default value
url_base = os.environ.get('SOURCE_DOMAIN_ENDPOINT', 'https://capture-proxy:9200')
username = 'admin'
password = 'admin'

session = requests.Session()
keep_alive_headers = {
    'Connection': 'keep-alive'
}


# Function to get current date in a specific format for indexing
def get_current_date_index():
    return datetime.now().strftime("%Y-%m-%d")


# Function to send a request
def send_request(index_suffix, url_base):
    timestamp = datetime.now().isoformat()
    url = f"{url_base}/simple_doc_{index_suffix}/_doc/{timestamp}?refresh=true"
    # Basic Authentication
    auth = (username, password)
    payload = {
        "timestamp": timestamp,
        "new_field": "apple"
    }
    try:
        # a new connection for every request
        #response = requests.put(url, json=payload, auth=auth)
        response = session.put(url, json=payload, auth=auth, headers=keep_alive_headers, verify=False)
        print(response.text)
        print(f"Request sent at {timestamp}: {response.status_code}")
        return response.status_code
    except requests.RequestException as e:
        print(f"Error sending request: {e}")
        return None


def parse_args():
    parser = argparse.ArgumentParser()
    parser.add_argument("--endpoint", help="Source cluster endpoint e.g. http://test.elb.us-west-2.amazonaws.com:9200.")
    return parser.parse_args()


args = parse_args()
# Main loop
counter = 1
total2xxCount = 0
total4xxCount = 0
total5xxCount = 0
totalErrorCount = 0
while True:
    current_index = get_current_date_index()
    response_code = send_request(current_index, url_base)
    if (response_code is not None):
        first_digit = int(str(response_code)[:1])
        if (first_digit == 2):
            total2xxCount += 1
        elif (first_digit == 4):
            total4xxCount += 1
        elif (first_digit == 5):
            total5xxCount += 1
    else:
        totalErrorCount += 1
    print(f"Summary: 2xx responses = {total2xxCount}, 4xx responses = {total4xxCount}, "
          f"5xx responses = {total5xxCount}, Error requests = {totalErrorCount}")
    sys.stdout.flush()
    counter += 1
    time.sleep(0.1)
