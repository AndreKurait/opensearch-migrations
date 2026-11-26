ARG BASE_IMAGE=migrations/elasticsearch_client_test_console:latest
# Pinned Kafka image the tools are copied from. Declared in the global scope so it
# can be used in a FROM below; BuildKit forbids variable expansion in COPY --from,
# so we alias the image as a named stage and copy from that. Overridden by the
# build to the cache-rewritten ref; see buildImages/build.gradle.
ARG KAFKA_IMAGE="docker.io/apache/kafka:3.9.2"

# Kafka tools source: alias the pinned image as a stage so COPY --from can
# reference it by a literal stage name.
FROM ${KAFKA_IMAGE} AS kafka-dist

# Stage 1: Download and install tools (kafka, kubectl, helm)
FROM ${BASE_IMAGE} AS migration-console-tools
ARG CI_PACKAGE_ENDPOINT

RUN ARCH=$(uname -m | sed 's/x86_64/amd64/; s/aarch64/arm64/') && \
    if [ "$ARCH" != "amd64" ] && [ "$ARCH" != "arm64" ]; then \
        echo "Unsupported architecture: $ARCH" && exit 1; \
    fi && \
    echo "Using architecture: $ARCH" && \
    echo "$ARCH" > /arch.env

ARG HELM_VERSION="3.14.0"
ARG KUBECTL_VERSION="1.32.1"
ARG STERN_VERSION="1.33.1"

# Kafka distribution: copy the tools tree from the pinned kafka-dist stage
# (deterministic, no Apache-mirror download/retry), then drop in the AWS MSK IAM
# auth jar (single fetch, no retries — a failure fails the build so it can be
# re-run against the layer cache rather than silently varying).
COPY --from=kafka-dist /opt/kafka /root/kafka-tools/kafka
RUN tool_base=https://github.com/aws/aws-msk-iam-auth/releases/download && \
    if [ -n "$CI_PACKAGE_ENDPOINT" ]; then tool_base="http://$CI_PACKAGE_ENDPOINT/raw/msk-iam"; fi && \
    mkdir -p /root/kafka-tools/aws && \
    curl -fSL \
        "${tool_base}/v2.3.4/aws-msk-iam-auth-2.3.4-all.jar" \
        -o /root/kafka-tools/kafka/libs/msk-iam-auth.jar

# Get kubectl and Helm distributions
RUN tool_base=https://dl.k8s.io && \
    if [ -n "$CI_PACKAGE_ENDPOINT" ]; then tool_base="http://$CI_PACKAGE_ENDPOINT/raw/kubectl"; fi && \
    ARCH=$(cat /arch.env) && \
    mkdir -p /k8s/bin && \
    curl --retry 5 --retry-delay 5 --retry-connrefused -fSL "${tool_base}/release/v${KUBECTL_VERSION}/bin/linux/${ARCH}/kubectl" -o /k8s/bin/kubectl && \
    chmod +x /k8s/bin/kubectl

RUN tool_base=https://get.helm.sh && \
    if [ -n "$CI_PACKAGE_ENDPOINT" ]; then tool_base="http://$CI_PACKAGE_ENDPOINT/raw/helm"; fi && \
    ARCH=$(cat /arch.env) && \
    curl --retry 5 --retry-delay 5 --retry-connrefused -fSL "${tool_base}/helm-v${HELM_VERSION}-linux-${ARCH}.tar.gz" | tar -xz && \
    mv linux-${ARCH}/helm /k8s/bin/

# Install stern - used by 'workflow log -f' for multi-pod log tailing
RUN tool_base=https://github.com/stern/stern/releases/download && \
    if [ -n "$CI_PACKAGE_ENDPOINT" ]; then tool_base="http://$CI_PACKAGE_ENDPOINT/raw/stern"; fi && \
    ARCH=$(cat /arch.env) && \
    curl --retry 5 --retry-delay 5 --retry-connrefused -fSL \
        "${tool_base}/v${STERN_VERSION}/stern_${STERN_VERSION}_linux_${ARCH}.tar.gz" | tar -xz && \
    mv stern /k8s/bin/ && \
    chmod +x /k8s/bin/stern


# Stage 2: Main migration console base image with Python environment and scripts
FROM ${BASE_IMAGE}
ARG CI_PACKAGE_ENDPOINT

# Define the virtual environment path
ENV WORKON_HOME=/
ENV PIPENV_CUSTOM_VENV_NAME=.venv
ENV PIPENV_DEFAULT_PYTHON_VERSION=3.11
ENV PIPENV_MAX_DEPTH=1
ENV PATH="/.venv/bin:${PATH}"

# Copy tools from previous stage
COPY --from=migration-console-tools /root/kafka-tools /root/kafka-tools
COPY --from=migration-console-tools /k8s/bin/ /usr/local/bin

RUN kubectl completion bash > /etc/bash_completion.d/kubectl && \
    helm completion bash > /etc/bash_completion.d/helm && \
    mkdir -p /config

WORKDIR /root

# Install Node.js for config processor and workflow template creation
# NPM is to setup the schema checking that coc uses
ARG CI_PACKAGE_SETUP
RUN if [ -n "${CI_PACKAGE_ENDPOINT:-}" ]; then printf %s "$CI_PACKAGE_SETUP" | base64 -d | sh; fi
RUN dnf ${CI_PACKAGE_ENDPOINT:+--setopt=reposdir=/tmp/ci-rpm-repos} install -y  \
      --setopt=install_weak_deps=False \
      --setopt=tsflags=nodocs \
      --setopt=keepcache=False \
      --setopt=plugins=0 \
      --setopt=logfilelevel=0 \
        nodejs22 \
        nodejs22-npm \
        bash-completion \
        && \
        dnf clean all

# Install mount-s3 for direct S3 bucket mounting (requires SYS_ADMIN capability at runtime)
RUN tool_base=https://mountpoint-s3-release.s3.amazonaws.com && \
    if [ -n "$CI_PACKAGE_ENDPOINT" ]; then tool_base="http://$CI_PACKAGE_ENDPOINT/raw/mount-s3"; fi && \
    MOUNT_S3_VERSION="1.22.2" && \
    ARCH=$(uname -m) && \
    if [ "$ARCH" = "aarch64" ]; then ARCH="arm64"; fi && \
    curl --retry 5 --retry-delay 5 --retry-connrefused -fSL \
        "${tool_base}/${MOUNT_S3_VERSION}/${ARCH}/mount-s3-${MOUNT_S3_VERSION}-${ARCH}.rpm" \
        -o /tmp/mount-s3.rpm && \
    dnf ${CI_PACKAGE_ENDPOINT:+--setopt=reposdir=/tmp/ci-rpm-repos} install -y /tmp/mount-s3.rpm && \
    rm -f /tmp/mount-s3.rpm

# Install gcsfuse for direct GCS bucket mounting (requires SYS_ADMIN capability at runtime)
RUN tool_base=https://github.com/GoogleCloudPlatform/gcsfuse/releases/download && \
    if [ -n "$CI_PACKAGE_ENDPOINT" ]; then tool_base="http://$CI_PACKAGE_ENDPOINT/raw/gcsfuse"; fi && \
    GCSFUSE_VERSION="3.9.2" && \
    ARCH=$(uname -m) && \
    dnf ${CI_PACKAGE_ENDPOINT:+--setopt=reposdir=/tmp/ci-rpm-repos} install -y \
        --setopt=install_weak_deps=False \
        --setopt=tsflags=nodocs \
        fuse && \
    curl --retry 5 --retry-delay 5 --retry-connrefused -fSL \
        "${tool_base}/v${GCSFUSE_VERSION}/gcsfuse-${GCSFUSE_VERSION}-1.${ARCH}.rpm" \
        -o /tmp/gcsfuse.rpm && \
    dnf ${CI_PACKAGE_ENDPOINT:+--setopt=reposdir=/tmp/ci-rpm-repos} install -y /tmp/gcsfuse.rpm && \
    rm -f /tmp/gcsfuse.rpm

COPY setupVimSchemaChecker.sh /tmp/setupVimSchemaChecker.sh
RUN chmod +x /tmp/setupVimSchemaChecker.sh && \
    /tmp/setupVimSchemaChecker.sh && \
    rm /tmp/setupVimSchemaChecker.sh

# Copy migration console scripts and configs
COPY msk-iam-auth.properties /root/kafka-tools/aws
COPY kafkaCmdRef.md /root/kafka-tools
COPY kafkaExport.sh /root/kafka-tools
COPY kafkaImport.sh /root/kafka-tools
RUN chmod ug+x /root/kafka-tools/kafkaExport.sh /root/kafka-tools/kafkaImport.sh

COPY loadServicesFromParameterStore.sh /root/
RUN chmod ug+x /root/loadServicesFromParameterStore.sh

COPY start-console.sh /root/
RUN chmod +x /root/start-console.sh

# Copy Python libraries and install dependencies
# Note: .dockerignore should exclude .venv, but we ensure clean install
WORKDIR /root
COPY lib /root/lib

WORKDIR /root/lib/console_link
RUN if [ -n "${CI_PACKAGE_ENDPOINT:-}" ]; then export PIP_TRUSTED_HOST="${CI_PACKAGE_ENDPOINT%:*}" PIP_INDEX_URL="http://$CI_PACKAGE_ENDPOINT/pypi/simple/" PIPENV_PYPI_MIRROR="http://$CI_PACKAGE_ENDPOINT/pypi/simple/" PIP_DISABLE_PIP_VERSION_CHECK=1; fi && pipenv install --deploy
WORKDIR /root/lib/integ_test
RUN if [ -n "${CI_PACKAGE_ENDPOINT:-}" ]; then export PIP_TRUSTED_HOST="${CI_PACKAGE_ENDPOINT%:*}" PIP_INDEX_URL="http://$CI_PACKAGE_ENDPOINT/pypi/simple/" PIPENV_PYPI_MIRROR="http://$CI_PACKAGE_ENDPOINT/pypi/simple/" PIP_DISABLE_PIP_VERSION_CHECK=1; fi && pipenv install --deploy

COPY cluster_tools /root/cluster_tools
RUN rm -rf /root/cluster_tools/.venv
WORKDIR /root/cluster_tools
RUN if [ -n "${CI_PACKAGE_ENDPOINT:-}" ]; then export PIP_TRUSTED_HOST="${CI_PACKAGE_ENDPOINT%:*}" PIP_INDEX_URL="http://$CI_PACKAGE_ENDPOINT/pypi/simple/" PIPENV_PYPI_MIRROR="http://$CI_PACKAGE_ENDPOINT/pypi/simple/" PIP_DISABLE_PIP_VERSION_CHECK=1; fi && pipenv install
WORKDIR /root

RUN mkdir -p /usr/share/bash-completion/completions && \
    _CONSOLE_COMPLETE=bash_source console > /usr/share/bash-completion/completions/console && \
    test -s /usr/share/bash-completion/completions/console


COPY nodeStaging/configProcessor /root/configProcessor

RUN chmod +x /root/configProcessor/*.sh
ENV CONFIG_PROCESSOR_DIR=/root/configProcessor
ENV NODEJS=node-22

COPY nodeStaging/migrationResources /root/migrationResources
ENV MIGRATION_RESOURCES_DIR=/root/migrationResources

COPY nodeStaging/workflowTemplates /root/workflows
RUN chmod +x /root/workflows/.workflowScripts/*.sh
COPY workflowConfigToServicesConfig.jq /root/
ENV WORKFLOW_CONFIG_JQ_SCRIPT=/root/workflowConfigToServicesConfig.jq

# Console setup bash completion and venv for interactive access
RUN echo '. /.venv/bin/activate' >> /etc/profile.d/venv.sh && \
    echo '. /etc/profile.d/bash_completion.sh' >> ~/.bashrc && \
    echo '. /etc/profile.d/venv.sh' >> ~/.bashrc && \
    echo 'echo Welcome to the Migration Assistant Console' >> ~/.bashrc && \
    echo 'eval "$(register-python-argcomplete cluster_tools)"' >> ~/.bashrc && \
    echo '. /usr/share/bash-completion/completions/console' >> ~/.bashrc && \
    echo 'workflow util completions bash > /usr/share/bash-completion/completions/workflow' >> ~/.bashrc && \
    echo '. /usr/share/bash-completion/completions/workflow' >> ~/.bashrc && \
    echo 'loadtest util completions bash > /usr/share/bash-completion/completions/loadtest' >> ~/.bashrc && \
    echo '. /usr/share/bash-completion/completions/loadtest' >> ~/.bashrc && \
    echo 'PS1="(\\t) \\[\\e[92m\\]migration-console \\[\\e[0m\\](\\w) -> "'>> ~/.bashrc

# Set ENV to control startup script in /bin/sh mode
ENV ENV=/root/.bashrc

CMD ["/root/start-console.sh"]
