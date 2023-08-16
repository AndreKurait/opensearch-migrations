FROM ubuntu:focal

ENV DEBIAN_FRONTEND noninteractive

RUN apt-get update && \
    apt-get install -y --no-install-recommends python3.9 python3-pip python3-dev gcc libc-dev git curl && \
    pip3 install opensearch-benchmark

COPY runTestBenchmarks.sh /root/
RUN chmod ugo+x /root/runTestBenchmarks.sh
WORKDIR /root

CMD tail -f /dev/null