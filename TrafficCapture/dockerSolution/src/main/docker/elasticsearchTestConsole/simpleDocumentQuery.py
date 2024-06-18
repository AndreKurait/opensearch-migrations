#!/usr/bin/env python
import requests
from datetime import datetime
import argparse
import os
import urllib3
import time
from collections import deque
import sys
from concurrent import futures

# Suppress only the single InsecureRequestWarning from urllib3 needed for this script
urllib3.disable_warnings(urllib3.exceptions.InsecureRequestWarning)

# Set the base URLs from the environment variables or use default values
source_url_base = os.getenv('SOURCE_DOMAIN_ENDPOINT', 'https://capture-proxy-es:19200')
target_url_base = os.getenv('MIGRATION_DOMAIN_ENDPOINT', 'https://opensearchtarget:9200')
source_username = 'admin'
source_password = 'admin'
target_username = 'admin'
target_password = 'myStrongPassword123!'

def parse_args():
    parser = argparse.ArgumentParser()
    parser.add_argument("--source-endpoint", help="Source cluster endpoint e.g. http://source.elb.us-west-2.amazonaws.com:9200.")
    parser.add_argument("--target-endpoint", help="Target cluster endpoint e.g. http://target.elb.us-west-2.amazonaws.com:9200.")
    parser.add_argument("--source-username", help="Source cluster username.", default=source_username)
    parser.add_argument("--source-password", help="Source cluster password.", default=source_password)
    parser.add_argument("--target-username", help="Target cluster username.", default=target_username)
    parser.add_argument("--target-password", help="Target cluster password.", default=target_password)
    parser.add_argument("--source-no-auth", action='store_true', help="Flag to provide no auth in source requests.")
    parser.add_argument("--target-no-auth", action='store_true', help="Flag to provide no auth in target requests.")
    parser.add_argument("--no-clear-output", action='store_true', help="Flag to not clear the output before each run. Helpful for piping to a file or other utility.")
    return parser.parse_args()

def get_latest_document(session, url_base, auth):
    url = f"{url_base}/simple_doc_*/_search"
    query = {
        "size": 1,
        "sort": [
            {
                "timestamp": {
                    "order": "desc"
                }
            }
        ]
    }
    try:
        response = session.get(url, json=query, auth=auth, verify=False, timeout=0.5)
        response.raise_for_status()
        hits = response.json().get('hits', {}).get('hits', [])
        if hits:
            latest_doc = hits[0]['_source']
            return latest_doc
        else:
            print("No documents found.")
            return None
    except requests.RequestException as e:
        print(f"Error querying latest document: {e}")
        return None

def calculate_average_speedup_factor(data):
    """
    Calculate the average speedup factor from the given data.

    Parameters:
    data (list of tuples): The input data in the format (target_delay, current_time)

    Returns:
    float: The average speedup factor
    """

    first_delay = data[0][0]
    first_timestamp = data[0][1]
    last_delay = data[-1][0]
    last_timestamp = data[-1][1]
    average_speedup = max(1 + (first_delay - last_delay) / (last_timestamp - first_timestamp).total_seconds(), 0)

    return average_speedup

def calculate_delay(latest_document, current_time):
    latest_timestamp = latest_document['timestamp'] if latest_document else "N/A"
    delay = (current_time - datetime.fromisoformat(latest_timestamp)).total_seconds() if latest_document else "N/A"
    return latest_timestamp, delay

def print_delays(source_delay, target_delay, source_timestamp_diffs, target_timestamp_diffs):
    source_timestamp_diffs.append((source_delay, datetime.now()))
    target_timestamp_diffs.append((target_delay, datetime.now()))

    valid_source_diffs = [diff for diff, _ in source_timestamp_diffs if diff != "N/A"]
    valid_target_diffs = [diff for diff, _ in target_timestamp_diffs if diff != "N/A"]

    if len(target_timestamp_diffs) >= 2:
        speedup_factor = calculate_average_speedup_factor(target_timestamp_diffs)
        print(f"Speedup Factor (last 5 seconds): {speedup_factor:.3f}")
    else:
        print("Insufficient data points to calculate Speedup Factor")

    source_rolling_average = sum(valid_source_diffs) / len(valid_source_diffs) if valid_source_diffs else "N/A"
    target_rolling_average = sum(valid_target_diffs) / len(valid_target_diffs) if valid_target_diffs else "N/A"
    rolling_average_diff = abs(source_rolling_average - target_rolling_average) if source_rolling_average != "N/A" and target_rolling_average != "N/A" else "N/A"

    print(f"Rolling average of source delay over last 5 seconds: {source_rolling_average:.3f}" if source_rolling_average != "N/A" else "Rolling average of source delay over last 5 seconds: N/A")
    print(f"Rolling average of target delay over last 5 seconds: {target_rolling_average:.3f}" if target_rolling_average != "N/A" else "Rolling average of target delay over last 5 seconds: N/A")
    print(f"Difference in rolling averages over last 5 seconds:  {rolling_average_diff:.3f}" if rolling_average_diff != "N/A" else "Difference in rolling averages over last 5 seconds: N/A")

def main_loop():
    args = parse_args()
    source_url_base = args.source_endpoint if args.source_endpoint else os.getenv('SOURCE_DOMAIN_ENDPOINT', 'https://capture-proxy:9200')
    target_url_base = args.target_endpoint if args.target_endpoint else os.getenv('MIGRATION_DOMAIN_ENDPOINT', 'https://opensearchtarget:9200')

    source_auth = None if args.source_no_auth else (args.source_username, args.source_password)
    target_auth = None if args.target_no_auth else (args.target_username, args.target_password)
    
    source_timestamp_diffs = deque()
    target_timestamp_diffs = deque()

    session = requests.Session()
    start_time = time.time()

    while True:
        try:
            with futures.ThreadPoolExecutor() as executor:
                future_source = executor.submit(get_latest_document, session, source_url_base, source_auth)
                future_target = executor.submit(get_latest_document, session, target_url_base, target_auth)
                source_latest_document = future_source.result()
                target_latest_document = future_target.result()
            current_time = datetime.now()

            source_latest_timestamp, source_delay = calculate_delay(source_latest_document, current_time)
            target_latest_timestamp, target_delay = calculate_delay(target_latest_document, current_time)

            if not args.no_clear_output:
                # send clear command, not flushing until after print for smoother visuals
                print(f"\033c", end="")

            print(f"Source latest timestamp: {source_latest_timestamp}")
            print(f"Source delay in seconds: {source_delay:.3f}" if source_delay != "N/A" else "Source delay in seconds: N/A")
            print(f"Target latest timestamp: {target_latest_timestamp}")
            print(f"Target delay in seconds: {target_delay:.3f}" if target_delay != "N/A" else "Target delay in seconds: N/A")
            print_delays(source_delay, target_delay, source_timestamp_diffs, target_timestamp_diffs)
            sys.stdout.flush()

            # Remove data older than 5 seconds
            while source_timestamp_diffs and (datetime.now() - source_timestamp_diffs[0][1]).total_seconds() > 5:
                source_timestamp_diffs.popleft()
            while target_timestamp_diffs and (datetime.now() - target_timestamp_diffs[0][1]).total_seconds() > 5:
                target_timestamp_diffs.popleft()

            # Reset session every 5 seconds
            if time.time() - start_time >= 5:
                session.close()
                session = requests.Session()
                start_time = time.time()

            time.sleep(0.25)
        except Exception as e:
            print(f"An error occurred: {e}. Retrying...")

if __name__ == "__main__":
    main_loop()
