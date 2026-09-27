#!/usr/bin/env bash
# Capture public-safe contract evidence from the three existing DocGrid VMs.
set -euo pipefail

: "${OPENSQL_GCP_ZONE:?Set the zone containing the existing three VMs}"
: "${OPENSQL_SSH_KEY:?Set the SSH private-key path used for the existing VMs}"
: "${OPENSQL_EXPECTED_ACCOUNT:?Set the approved GCP account}"
: "${OPENSQL_EXPECTED_PROJECT:?Set the approved GCP project}"

active_account="$(gcloud auth list --filter=status:ACTIVE --format='value(account)')"
active_project="$(gcloud config get-value project 2>/dev/null)"
if [[ "$active_account" != "$OPENSQL_EXPECTED_ACCOUNT" ||
      "$active_project" != "$OPENSQL_EXPECTED_PROJECT" ]]; then
    echo 'The active GCP account or project differs from the approved target.' >&2
    exit 1
fi

repository_root="$(cd "$(dirname "$0")/../.." && pwd)"
collector="$repository_root/scripts/opensql/capture_ha_contract.py"
output="$repository_root/docs/test-results/opensql-contract-evidence"
temporary="$(mktemp -d)"
trap 'rm -r "$temporary"' EXIT
started_at="$(date -u +%Y-%m-%dT%H:%M:%SZ)"

# 1. Read the container and VM separately; neither remote command prints raw config files.
for node in node1 node2 node3; do
    vm="docgrid-$node"
    gcloud compute ssh "$vm" --zone="$OPENSQL_GCP_ZONE" \
        --ssh-key-file="$OPENSQL_SSH_KEY" \
        --command="sudo docker exec --user opensql -i $vm python3 - collect --node $node" --quiet \
        < "$collector" > "$temporary/$node.collect.json"
    gcloud compute ssh "$vm" --zone="$OPENSQL_GCP_ZONE" \
        --ssh-key-file="$OPENSQL_SSH_KEY" \
        --command="python3 - runtime --node $node" --quiet \
        < "$collector" > "$temporary/$node.runtime.json"
    python3 "$collector" merge "$temporary/$node.collect.json" \
        "$temporary/$node.runtime.json" > "$temporary/$node.json"
done

# 2. Read OpenProxy's effective settings without exporting its administrator credentials.
for pair in 'node2 proxy-a' 'node3 proxy-b'; do
    read -r node proxy <<< "$pair"
    vm="docgrid-$node"
    gcloud compute ssh "$vm" --zone="$OPENSQL_GCP_ZONE" \
        --ssh-key-file="$OPENSQL_SSH_KEY" \
        --command="sudo docker exec -i $vm python3 - admin --node $node" --quiet \
        < "$collector" > "$temporary/$proxy-admin.json"
done

# 3. Validate every sanitized input before replacing any published snapshot.
python3 "$collector" assemble "$temporary/node1.json" "$temporary/node2.json" \
    "$temporary/node3.json" --admin "$temporary/proxy-a-admin.json" \
    "$temporary/proxy-b-admin.json" > "$temporary/contract-manifest.json"
finished_at="$(date -u +%Y-%m-%dT%H:%M:%SZ)"
jq --arg started "$started_at" --arg finished "$finished_at" \
    '. + {capture_started_at_utc: $started, capture_finished_at_utc: $finished}' \
    "$temporary/contract-manifest.json" > "$temporary/timed-manifest.json"
mv "$temporary/timed-manifest.json" "$temporary/contract-manifest.json"
for name in node1 node2 node3 proxy-a-admin proxy-b-admin contract-manifest; do
    cp "$temporary/$name.json" "$output/$name.json"
done
echo 'Public-safe OpenSQL contract snapshots refreshed.'
