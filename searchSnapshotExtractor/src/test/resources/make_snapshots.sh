#!/usr/bin/env bash
# ---------------------------------------------------------------------------
# • Elasticsearch majors: 1–2, 5–9
# • OpenSearch majors:    1–3
# For **each** product/major we run two variants:
#   ▸   nocomp   – snapshot.metadata.compress=false
#   ▸   comp     – snapshot.metadata.compress=true
# Every variant creates two snapshots (v1 & v2) of a tiny test index and stores
# them inside:
#     ./snapshot_data/{es,os}<MAJOR>_{nocomp,comp}/
# ---------------------------------------------------------------------------
set -euo pipefail

HOST_PORT=9200
BASE_DIR="$(pwd)/snapshot_data"
INDEX=simple_index
REPO_BASE=my_repo
SNAP1=snapshot_v1
SNAP2=snapshot_v2

PRODUCTS=( 
  es:1 es:2 es:5 es:6 es:7 es:8 es:9 \
  os:1 os:2 os:3
)

# ---------- helpers --------------------------------------------------------
get_image() {
  local P=${1%%:*} M=${1##*:}
  if [[ $P == es ]]; then
    case $M in
      1) echo "elasticsearch:1.7.6" ;;
      2) echo "elasticsearch:2.4.6" ;;
      5) echo "docker.elastic.co/elasticsearch/elasticsearch:5.6.16" ;;
      6) echo "docker.elastic.co/elasticsearch/elasticsearch:6.8.23" ;;
      7) echo "docker.elastic.co/elasticsearch/elasticsearch:7.17.29" ;;
      8) echo "docker.elastic.co/elasticsearch/elasticsearch:8.14.0" ;;
      9) echo "docker.elastic.co/elasticsearch/elasticsearch:9.0.3" ;;
      *) echo "" ;;
    esac
  else
    case $M in
      1) echo "opensearchproject/opensearch:1.3.20" ;;
      2) echo "opensearchproject/opensearch:2.19.2" ;;
      3) echo "opensearchproject/opensearch:3.1.0" ;;
      *) echo "" ;;
    esac
  fi
}

get_platform() {
  local P=${1%%:*} M=${1##*:}
  [[ $P == es && $M -eq 9 ]] && echo linux/arm64 || echo linux/amd64
}

to_upper() { printf '%s' "$1" | tr '[:lower:]' '[:upper:]'; }

# ---------- run_variant PRODUCT MAJOR COMPRESS IMAGE PLATFORM --------------
run_variant() {
  local P=$1 M=$2 COMPRESS=$3 IMAGE=$4 PLATFORM=$5
  local COMP_LABEL; COMP_LABEL=$([[ $COMPRESS == true ]] && echo comp || echo nocomp)
  local PREFIX;     PREFIX=$([[ $P == es ]] && echo es || echo os)
  local DIR_NAME="${PREFIX}${M}_${COMP_LABEL}"
  local ES_DIR="${BASE_DIR}/${DIR_NAME}"
  local CFG="$(pwd)/${DIR_NAME}.yml"
  local CONTAINER="${DIR_NAME}"
  local CFG_PATH; [[ $P == es ]] && CFG_PATH=/usr/share/elasticsearch/config/elasticsearch.yml \
                                 || CFG_PATH=/usr/share/opensearch/config/opensearch.yml
  local P_UPPER; P_UPPER=$(to_upper "$P")

  echo -e "\n──── ${P_UPPER} ${M} (${IMAGE}) ${COMP_LABEL} ────"

  rm -rf "$ES_DIR" "$CFG" && mkdir -p "$ES_DIR"

  # ---------- minimal configs ---------------------------------------------
  if [[ $P == es ]]; then
    {
      echo network.host: 0.0.0.0
      echo "path.repo: [\"/snapshot\"]"
      [[ $M -le 2 ]] && echo bootstrap.system_call_filter: false
      [[ $M -ge 5 ]] && echo discovery.type: single-node
      if [[ $M -ge 5 ]]; then
        cat <<'YML'
xpack.security.enabled: false
xpack.watcher.enabled: false
xpack.ml.enabled: false
YML
      fi
      [[ $M -ge 8 ]] && {
        echo xpack.security.transport.ssl.enabled: false
        echo xpack.security.http.ssl.enabled: false
      }
    } > "$CFG"
  else  # OpenSearch
    {
      echo network.host: 0.0.0.0
      echo "path.repo: [\"/snapshot\"]"
      echo plugins.security.disabled: true
      echo discovery.type: single-node            # ⇒ bypass bootstrap checks
      echo bootstrap.system_call_filter: false    # ⇒ avoid seccomp failure
    } > "$CFG"
  fi

  # ---------- ENV differences ---------------------------------------------
  local EXTRA_ENV=()
  if [[ $P == os ]]; then
    EXTRA_ENV+=( -e OPENSEARCH_JAVA_OPTS="-Xms2g -Xmx2g" \
                 -e DISABLE_INSTALL_DEMO_CONFIG=true \
                 -e DISABLE_SECURITY_PLUGIN=true )
  else
    EXTRA_ENV+=( -e ES_JAVA_OPTS="-Xms2g -Xmx2g" )
  fi

  # ---------- launch -------------------------------------------------------
  docker pull --platform "$PLATFORM" "$IMAGE"
  docker rm -f "$CONTAINER" 2>/dev/null || true
  docker run -d --name "$CONTAINER" --platform "$PLATFORM" \
    -p ${HOST_PORT}:9200 \
    -m 4g --memory-swap 4g \
    "${EXTRA_ENV[@]}" \
    -v "$ES_DIR":/snapshot \
    -v "$CFG":${CFG_PATH} \
    "$IMAGE"

  echo -n "⏳  starting… "; for _ in $(seq 1 60); do
    curl -s "http://localhost:${HOST_PORT}" >/dev/null && break; sleep 2; done
  echo "✅"

  # ---------- create index + doc1 -----------------------------------------
  local IDX_URL="http://localhost:${HOST_PORT}/${INDEX}"
  if [[ $P == es && $M -lt 7 ]]; then
    curl -s -XPUT "$IDX_URL" -H 'Content-Type: application/json' -d '{"settings":{"number_of_shards":1,"number_of_replicas":0},"mappings":{"doc":{"properties":{"value":{"type":"integer"}}}}}'
    curl -s -XPUT "$IDX_URL/doc/1" -H 'Content-Type: application/json' -d '{"value":42}'
  else
    curl -s -XPUT "$IDX_URL" -H 'Content-Type: application/json' -d '{"settings":{"number_of_shards":1,"number_of_replicas":0},"mappings":{"properties":{"value":{"type":"integer"}}}}'
    curl -s -XPUT "$IDX_URL/_doc/1" -H 'Content-Type: application/json' -d '{"value":42}'
  fi
  curl -s -XPOST "$IDX_URL/_flush" >/dev/null

  # ---------- snapshot v1 --------------------------------------------------
  local REPO="${REPO_BASE}_${COMP_LABEL}"
  curl -s -XPUT "http://localhost:${HOST_PORT}/_snapshot/${REPO}" \
       -H 'Content-Type: application/json' \
       -d "{\"type\":\"fs\",\"settings\":{\"location\":\"/snapshot\",\"compress\":${COMPRESS}}}"
  curl -s -XPUT "http://localhost:${HOST_PORT}/_snapshot/${REPO}/${SNAP1}?wait_for_completion=true" \
       -H 'Content-Type: application/json' \
       -d "{\"indices\":\"${INDEX}\",\"include_global_state\":true}"

  # ---------- add doc2 + snapshot v2 --------------------------------------
  if [[ $P == es && $M -lt 7 ]]; then
    curl -s -XPUT "$IDX_URL/doc/2" -H 'Content-Type: application/json' -d '{"value":99}'
  else
    curl -s -XPUT "$IDX_URL/_doc/2" -H 'Content-Type: application/json' -d '{"value":99}'
  fi
  curl -s -XPOST "$IDX_URL/_flush" >/dev/null
  curl -s -XPUT "http://localhost:${HOST_PORT}/_snapshot/${REPO}/${SNAP2}?wait_for_completion=true" \
       -H 'Content-Type: application/json' \
       -d "{\"indices\":\"${INDEX}\",\"include_global_state\":true}"

  echo "📦  snapshots saved to $ES_DIR"

  docker rm -f "$CONTAINER" >/dev/null
  rm -f "$CFG"
}

# ---------- main loop ------------------------------------------------------
for ITEM in "${PRODUCTS[@]}"; do
  P=${ITEM%%:*} M=${ITEM##*:}
  IMAGE=$(get_image "$ITEM") || true
  [[ -z $IMAGE ]] && { echo "⚠️  No image for $P $M – skipping."; continue; }
  PLATFORM=$(get_platform "$ITEM")

  run_variant "$P" "$M" false "$IMAGE" "$PLATFORM"
  run_variant "$P" "$M" true  "$IMAGE" "$PLATFORM"
done

echo -e "\n🎉  All snapshots are under ${BASE_DIR}/{es,os}*_*/"
      