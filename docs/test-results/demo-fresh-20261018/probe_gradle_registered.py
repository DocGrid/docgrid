import os,subprocess,time,threading,json,importlib.util,tempfile
from pathlib import Path
spec=importlib.util.spec_from_file_location('runtime',Path.cwd()/'scripts/demo.py');m=importlib.util.module_from_spec(spec);spec.loader.exec_module(m)
env=dict(os.environ)
java=subprocess.run(['/usr/libexec/java_home','-v','17'],capture_output=True,text=True)
if java.returncode==0:env['JAVA_HOME']=java.stdout.strip()
with tempfile.TemporaryDirectory() as directory:
 path=Path(directory)/'children.json';stopped=threading.Event();owner=m.identity(os.getpid())
 tracker=threading.Thread(target=m.track_children,args=(owner,stopped,path));tracker.start()
 p=subprocess.Popen(['./backend/gradlew','-p','backend','--no-daemon','help'],stdout=subprocess.PIPE,stderr=subprocess.STDOUT,text=True,env=env,start_new_session=True)
 reader=threading.Thread(target=lambda:[print(line,end='',flush=True) for line in p.stdout]);reader.start()
 separate=set()
 while p.poll() is None:
  if path.exists():
   for item in m.read_json(path)['children']:
    if item['pid'] != p.pid and item['pgid'] != p.pid and item['pgid'] != owner['pgid']:
     separate.add(item['pid'])
  time.sleep(0.05)
 reader.join();stopped.set();tracker.join()
 print(json.dumps({'gradleExitCode':p.returncode,'registeredSeparateChildren':len(separate)}))
 raise SystemExit(0 if p.returncode==0 and separate else 1)
