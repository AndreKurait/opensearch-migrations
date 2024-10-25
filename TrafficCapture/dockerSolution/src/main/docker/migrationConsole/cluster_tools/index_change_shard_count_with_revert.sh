#!/bin/bash

# Variables passed as arguments
INDEX_NAME=$1
TARGET_SHARDS=$2

# Check if both arguments are provided
if [ -z "$INDEX_NAME" ] || [ -z "$TARGET_SHARDS" ]; then
    echo "Usage: $0 <index_name> <target_number_of_primary_shards>"
    exit 1
fi

# Step 1: Check if the index contains 0 documents
DOC_COUNT=$(console clusters curl -XGET target_cluster /$INDEX_NAME/_count | jq '.count')

if [ "$DOC_COUNT" -ne 0 ]; then
    echo "Index $INDEX_NAME contains documents. Aborting."
    exit 1
else
    echo "Index $INDEX_NAME contains 0 documents. Proceeding."
fi

# Step 2: Retrieve the index settings (without non-applicable settings)
echo "Fetching settings from the index: $INDEX_NAME"
SOURCE_SETTINGS=$(console clusters curl -XGET target_cluster /$INDEX_NAME/_settings)

# Remove unnecessary settings like shards, UUID, version, and creation date
FILTERED_SETTINGS=$(echo $SOURCE_SETTINGS | jq '.[].settings.index 
  | del(.number_of_shards) 
  | del(.provided_name) 
  | del(.uuid) 
  | del(.creation_date) 
  | del(.version)')

# Add the number_of_shards to the filtered settings
UPDATED_SETTINGS=$(echo $FILTERED_SETTINGS | jq --arg shards "$TARGET_SHARDS" '. + {number_of_shards: $shards}')

echo "Updated settings: $UPDATED_SETTINGS"

# Generate a random suffix for the temporary index
RANDOM_SUFFIX=$(date +%s)
TEMP_INDEX_NAME="${INDEX_NAME}_temp_${RANDOM_SUFFIX}"

# Step 3: Attempt to create the new index with the temporary suffix
echo "Creating temporary index: $TEMP_INDEX_NAME"
CREATE_TEMP_INDEX_RESPONSE=$(console clusters curl -XPUT target_cluster /$TEMP_INDEX_NAME -H 'Content-Type: application/json' -d "{
  \"settings\": $UPDATED_SETTINGS
}")

# Check if the temporary index was created successfully
if echo "$CREATE_TEMP_INDEX_RESPONSE" | grep -q '"acknowledged":true'; then
    echo "Temporary index $TEMP_INDEX_NAME created successfully."

    # Step 4: Delete the original index
    echo "Deleting index: $INDEX_NAME"
    console clusters curl -XDELETE target_cluster /$INDEX_NAME

    # Step 5: Re-create the index with the updated settings
    echo "Recreating index: $INDEX_NAME with $TARGET_SHARDS primary shards"
    console clusters curl -XPUT target_cluster /$INDEX_NAME -H 'Content-Type: application/json' -d "{
      \"settings\": $UPDATED_SETTINGS
    }"

    echo "Index $INDEX_NAME recreated successfully with $TARGET_SHARDS primary shards."

    # Step 6: Delete the temporary index
    echo "Deleting temporary index: $TEMP_INDEX_NAME"
    console clusters curl -XDELETE target_cluster /$TEMP_INDEX_NAME

    echo "Temporary index $TEMP_INDEX_NAME deleted successfully."

else
    echo "Failed to create temporary index $TEMP_INDEX_NAME. Aborting."
    exit 1
fi