# Using same base image as other Java containers in this repo
ARG BASE_IMAGE=docker.io/library/amazoncorretto:21-al2023-headless
FROM ${BASE_IMAGE}

# Install procps to use pgrep in entrypoint.sh
ARG CI_PACKAGE_ENDPOINT
ARG CI_PACKAGE_SETUP
RUN if [ -n "${CI_PACKAGE_ENDPOINT:-}" ]; then printf %s "$CI_PACKAGE_SETUP" | base64 -d | sh; fi
RUN dnf ${CI_PACKAGE_ENDPOINT:+--setopt=reposdir=/tmp/ci-rpm-repos} install -y procps && \
    dnf clean all && \
    rm -rf /var/cache/dnf

# Requires Gradle to genearte runtime jars initially
COPY ./build/runtimeJars /rfs-app/jars
COPY ./build/version/VERSION /rfs-app/VERSION
WORKDIR /rfs-app

# Copy the Java runner scripts into the container
COPY ./runJavaWithClasspath.sh /rfs-app/runJavaWithClasspath.sh
RUN chmod +x /rfs-app/runJavaWithClasspath.sh
COPY ./runJavaWithClasspathWithRepeat.sh /rfs-app/runJavaWithClasspathWithRepeat.sh
RUN chmod +x /rfs-app/runJavaWithClasspathWithRepeat.sh

# Copy the entry point script into the container
COPY ./entrypoint.sh /rfs-app/entrypoint.sh
RUN chmod +x /rfs-app/entrypoint.sh

CMD ["tail", "-f", "/dev/null"]
