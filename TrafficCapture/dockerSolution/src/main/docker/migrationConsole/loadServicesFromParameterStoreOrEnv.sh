#!/bin/bash

set -eou pipefail

source /.venv/bin/activate

if [ -n "${MIGRATION_SERVICES_YAML}" ]; then
  echo "Environment variable MIGRATION_SERVICES_YAML is set. Using provided YAML."
  echo "$MIGRATION_SERVICES_YAML" > "/etc/migration_services.yaml" || {
    echo "Error: Failed to write MIGRATION_SERVICES_YAML to /etc/migration_services.yaml"
    exit 1
  }
elif [ -z "${MIGRATION_SERVICES_YAML_PARAMETER+x}" ]; then
  echo "Environment variable MIGRATION_SERVICES_YAML_PARAMETER is not set. Exiting successfully and "
  echo "assuming the migration services yaml is already in place."
else
    # Retrieve the parameter value from AWS Systems Manager Parameter Store
    PARAMETER_VALUE=$(pipenv run aws ssm get-parameters --names "$MIGRATION_SERVICES_YAML_PARAMETER" --query "Parameters[0].Value" --output text)

    # Check if the retrieval was successful
    if [ $? -ne 0 ]; then
        echo "Failed to retrieve parameter: $MIGRATION_SERVICES_YAML_PARAMETER"
        exit 1
    fi

    # Define the output file path
    OUTPUT_FILE="/etc/migration_services.yaml"

    # Write the parameter value to the file
    echo "$PARAMETER_VALUE" > "$OUTPUT_FILE"

    # Check if the write was successful
    if [ $? -ne 0 ]; then
        echo "Failed to write to file: $OUTPUT_FILE"
        exit 1
    fi
    echo "Parameter value successfully written to $OUTPUT_FILE"
  # Retrieve the parameter value from AWS Systems Manager Parameter Store
  PARAMETER_VALUE=$(pipenv run aws ssm get-parameters --names "$MIGRATION_SERVICES_YAML_PARAMETER" --query "Parameters[0].Value" --output text)

  # Check if the retrieval was successful
  if [ $? -ne 0 ]; then
    echo "Failed to retrieve parameter: $MIGRATION_SERVICES_YAML_PARAMETER"
    exit 1
  fi

  # Define the output file path
  OUTPUT_FILE="/etc/migration_services.yaml"

  # Write the parameter value to the file
  echo "$PARAMETER_VALUE" > "$OUTPUT_FILE"

  # Check if the write was successful
  if [ $? -ne 0 ]; then
    echo "Failed to write to file: $OUTPUT_FILE"
    exit 1
  fi
  echo "Parameter value successfully written to $OUTPUT_FILE"
fi

# Generate bash completion script
console completion bash > /usr/share/bash-completion/completions/console
echo "Bash completion for console command has been set up and enabled."
