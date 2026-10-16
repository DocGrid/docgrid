#!/usr/bin/env python3
"""루프백 전용 PostgreSQL 물리 복제 fixture를 생성하고 표식이 있는 시험 자원만 제거한다."""
import subprocess, time, json, sys
N='docgrid-permission-isolated'
I='pgvector/pgvector:0.8.1-pg17'
def d(*a):
 r=subprocess.run(['docker',*a],text=True,capture_output=True)
 if r.returncode: raise RuntimeError('Docker operation failed: '+a[0])
 return r.stdout.strip()
def sql(node,q):
 return d('exec',N+'-'+node,'psql','-h','127.0.0.1','-U','postgres','-d','postgres','-At','-c',q)
if sys.argv[1]=='up':
 d('network','create','--internal',N)
 d('run','-d','--label','docgrid.permission.fixture=true','--name',N+'-primary','--network',N,'-p','127.0.0.1:55439:5432','-e','POSTGRES_HOST_AUTH_METHOD=trust',I,'postgres','-c','wal_level=replica','-c','max_wal_senders=10','-c','max_replication_slots=10','-c','wal_keep_size=128MB')
 for _ in range(60):
  try:
   sql('primary','select 1'); break
  except Exception: time.sleep(.5)
 else: raise RuntimeError('primary readiness timeout')
 sql('primary','CREATE EXTENSION vector')
 d('exec',N+'-primary','sh','-c',"echo 'host replication all all trust' >> /var/lib/postgresql/data/pg_hba.conf")
 sql('primary','select pg_reload_conf()')
 for i in (1,2):
  name=N+'-standby'+str(i)
  d('run','-d','--label','docgrid.permission.fixture=true','--name',name,'--network',N,'-e','POSTGRES_HOST_AUTH_METHOD=trust','--entrypoint','bash',I,'-ec',"mkdir -p /var/lib/postgresql/data; chown postgres /var/lib/postgresql/data; chmod 700 /var/lib/postgresql/data; gosu postgres pg_basebackup -h "+N+"-primary -U postgres -D /var/lib/postgresql/data -Fp -Xs -R; echo \"primary_conninfo = 'host="+N+"-primary user=postgres application_name=standby"+str(i)+"'\" >> /var/lib/postgresql/data/postgresql.auto.conf; exec gosu postgres postgres")
 for _ in range(60):
  if sql('primary',"select count(*) from pg_stat_replication where state='streaming'")=='2': break
  time.sleep(.5)
 else: raise RuntimeError('replica readiness timeout')
 print(json.dumps({'environment':'isolated-local','postgres':sql('primary','show server_version'),'streaming_standbys':2,'public_resources_changed':False}))
elif sys.argv[1]=='down':
 for x in ('standby2','standby1','primary'):
  label=d('inspect','--format','{{ index .Config.Labels \"docgrid.permission.fixture\" }}',N+'-'+x)
  if label!='true': raise RuntimeError('Refusing to remove an unlabelled container')
  d('rm','-f','-v',N+'-'+x)
 d('network','rm',N)
 print('isolated fixture removed')
