#!/bin/bash
# 사용법: track.sh <version_id> <seconds> <outfile>  — 0.5초마다 상태가 바뀐 순간만 기록한다.
vid=$1; dur=$2; out=$3; last=""; end=$(( $(date +%s) + dur ))
while [ $(date +%s) -lt $end ]; do
  s=$(docker exec docgrid-reconcile-pg-20261016 psql -U app -d app -At -F ' ' -c "
    select 'doc='||d.status, 'ver='||v.status,
      'jobs='||coalesce((select string_agg(j.id||':'||j.status, ',' order by j.id) from embedding_jobs j where j.document_version_id=v.id),'-'),
      'reindexEvents='||coalesce((select string_agg(o.status, ',' order by o.id) from sync_outbox_events o where o.aggregate_type='DOCUMENT_VERSION' and o.aggregate_id=v.id and o.event_type='DOCUMENT_REINDEX_REQUESTED'),'-'),
      'activeVec='||(select count(*) from embeddings e where e.document_version_id=v.id and e.status='ACTIVE'),
      'issues='||coalesce((select string_agg(i.id||':'||i.issue_type||':'||i.status, ',' order by i.id) from sync_consistency_issues i where i.document_version_id=v.id),'-')
    from document_versions v join documents d on d.id=v.document_id where v.id=$vid" 2>&1)
  if [ "$s" != "$last" ]; then echo "$(date '+%H:%M:%S') $s" >> $out; last="$s"; fi
  sleep 0.5
done
