FROM amazonlinux:2023

ENV PIP_ROOT_USER_ACTION ignore
ENV LANG C.UTF-8

# procps-ng used for enabling 'watch' command on console
RUN dnf install -y --setopt=install_weak_deps=False \
        curl-minimal \
        diffutils \
        clang \
        git \
        glibc-devel \
        hostname \
        java-17-amazon-corretto \
        jq \
        less \
        make \
        openssl-devel \
        pkg-config \
        procps-ng \
        python3.11 \
        python3.11-devel \
        python3.11-pip \
        python3.11-wheel \
        tar \
        unzip \
        vim \
        wget \
        zlib-devel \
        && \
    dnf clean all && \
    rm -rf /var/cache/dnf

WORKDIR /tmp

# Install HDF5 from source for Opensearch Benchmark compatibility with ARM
ARG HDF5_VERSION=1.14.6

RUN wget -O hdf5-${HDF5_VERSION}.tar.gz https://github.com/HDFGroup/hdf5/archive/refs/tags/hdf5-${HDF5_VERSION}.tar.gz \
    && tar -xzf hdf5-${HDF5_VERSION}.tar.gz \
    && rm hdf5-${HDF5_VERSION}.tar.gz

RUN cd hdf5-hdf5-1.14.6 && \
    CC=clang CFLAGS="-w" \
    ./configure --prefix=/usr/local \
                --disable-tools \
                --disable-tests  \
                --enable-optimizations \
                --disable-fortran && \
    make -s -Oline -j$(nproc --ignore=1) install && \
    cd .. && \
    rm -rf /tmp/*

# Define the virtual environment path to use for all pipenv runs
ENV WORKON_HOME=/
ENV PIPENV_CUSTOM_VENV_NAME=.venv
ENV PIPENV_DEFAULT_PYTHON_VERSION=3.11
ENV PIPENV_MAX_DEPTH=1

RUN python3.11 -m pip install pipenv
WORKDIR /
RUN python3.11 -m venv .venv

WORKDIR /root
COPY Pipfile .
COPY Pipfile.lock .

ENV LD_LIBRARY_PATH="/usr/local/lib"
RUN HDF5_DIR=/usr/local pipenv install --deploy

COPY *.sh /root/
COPY *.py /root/

RUN chmod ug+x /root/*.sh
RUN chmod ug+x /root/*.py

CMD tail -f /dev/null
