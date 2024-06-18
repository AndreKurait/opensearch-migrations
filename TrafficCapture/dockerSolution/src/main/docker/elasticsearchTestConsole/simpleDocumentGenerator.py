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

# Function to get current date in a specific format for indexing
def get_current_date_index():
    return datetime.now().strftime("%Y-%m-%d")


# Function to send a request
def send_request(index_suffix, url_base, auth):
    timestamp = datetime.now().isoformat()
    url = f"{url_base}/simple_doc_{index_suffix}/_doc/{timestamp}?refresh=true"
    payload = {
        "timestamp": timestamp,
        "new_field": "apple"
    }
    try:
        # a new connection for every request
        response = session.put(url, json=payload, auth=auth, headers=keep_alive_headers, verify=False, timeout=0.5)
        print(response.text)
        print(f"Request sent at {timestamp}: {response.status_code}")
        return response.status_code
    except requests.RequestException as e:
        print(f"Error sending request: {e}")
        return None


def parse_args():
    parser = argparse.ArgumentParser()
    parser.add_argument("--endpoint", help="Source cluster endpoint e.g. http://test.elb.us-west-2.amazonaws.com:9200.")
    parser.add_argument("--no-auth", action='store_true', help="Flag to provide no auth in requests.")
    parser.add_argument("--no-clear-output", action='store_true', help="Flag to not clear the output before each run. Helpful for piping to a file or other utility.")
    return parser.parse_args()


args = parse_args()

# Set the base URL from the argument or environment variable SOURCE_DOMAIN_ENDPOINT or use a default value
url_base = args.endpoint if args.endpoint else os.environ.get('SOURCE_DOMAIN_ENDPOINT', 'https://capture-proxy:9200')
auth = None if args.no_auth else ('admin', 'admin')

session = requests.Session()
keep_alive_headers = {
    'Connection': 'keep-alive'
}

# Main loop
counter = 1
total2xxCount = 0
total4xxCount = 0
total5xxCount = 0
totalErrorCount = 0
start_time = time.time()
while True:
    if not args.no_clear_output:
        # send clear command, not flushing until after print for smoother visuals
        print(f"\033c", end="")

    current_index = get_current_date_index()
    response_code = send_request(current_index, url_base, auth)
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
    time.sleep(0.05)
    # Reset connection every 5 seconds
    if time.time() - start_time >= 5:
        session.close()
        session = requests.Session()
        start_time = time.time()
