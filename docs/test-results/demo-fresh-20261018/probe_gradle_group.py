import os,subprocess,time,threading,json
env=dict(os.environ)
java=subprocess.run(['/usr/libexec/java_home','-v','17'],capture_output=True,text=True)
if java.returncode==0:env['JAVA_HOME']=java.stdout.strip()
p=subprocess.Popen(['./backend/gradlew','-p','backend','--no-daemon','help'],stdout=subprocess.PIPE,stderr=subprocess.STDOUT,text=True,env=env,start_new_session=True)
thread=threading.Thread(target=lambda:[print(line,end='',flush=True) for line in p.stdout]);thread.start()
observed={}
while p.poll() is None:
 result=subprocess.run(['ps','-eo','pid=,ppid=,pgid=,args='],capture_output=True,text=True)
 for line in result.stdout.splitlines():
  fields=line.split(maxsplit=3)
  if len(fields)==4 and fields[1]==str(p.pid):
   observed[fields[0]]={'role':'gradle-child','sameGroup':fields[2]==str(p.pid)}
 time.sleep(0.1)
thread.join();print(json.dumps({'gradleExitCode':p.returncode,'directChildren':list(observed.values())}))
raise SystemExit(0 if p.returncode==0 and all(x['sameGroup'] for x in observed.values()) else 1)
