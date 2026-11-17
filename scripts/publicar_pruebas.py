#!/usr/bin/env python3
"""Publica la instalación real por un túnel temporal; ninguna credencial sale del archivo privado."""
import os,re,signal,subprocess,sys,time,urllib.request,json,shlex
from pathlib import Path
from arrancar_local import ROOT,guard_private

def stop_local():
 pids=subprocess.run(['/usr/sbin/lsof','-nP','-t','-iTCP:8080','-sTCP:LISTEN'],capture_output=True,text=True).stdout.split()
 for value in set(pids):
  pid=int(value);args=shlex.split(subprocess.run(['/bin/ps','-p',value,'-o','args='],capture_output=True,text=True).stdout)
  if str(ROOT/'target/celex-0.0.1-SNAPSHOT.jar') not in args or '--spring.profiles.active=local' not in args:raise ValueError('Otro programa usa el puerto 8080. Se conserva sin detenerlo.')
  os.kill(pid,signal.SIGTERM)
  for _ in range(150):
   if subprocess.run(['/bin/ps','-p',value],stdout=subprocess.DEVNULL).returncode:break
   time.sleep(.1)
  else:raise ValueError('Espera a que CELEX termine de cerrar.')

def main():
 guard_private(ROOT/'application-local.properties')
 binary=ROOT/'.tools/cloudflared';jar=ROOT/'target/celex-0.0.1-SNAPSHOT.jar'
 if not binary.is_file() or not jar.is_file():raise ValueError('Falta instalar cloudflared o compilar CELEX.')
 if subprocess.run(['/usr/sbin/lsof','-nP','-t','-iTCP:8082','-sTCP:LISTEN'],capture_output=True).stdout:raise ValueError('Ya hay un servicio en 8082. Cierra la publicación anterior antes de continuar.')
 logs=ROOT/'target';logs.mkdir(exist_ok=True);os.umask(0o077)
 processes=[]
 try:
  with (logs/'tunnel.log').open('w') as tunnel_log:
   tunnel=subprocess.Popen([str(binary),'tunnel','--no-autoupdate','--protocol','http2','--url','http://127.0.0.1:8082'],stdout=tunnel_log,stderr=subprocess.STDOUT);processes.append(tunnel)
   url=None
   for _ in range(120):
    if tunnel.poll() is not None:raise ValueError('Cloudflare no pudo abrir el enlace. Consulta target/tunnel.log.')
    match=re.search(r'https://[a-z0-9-]+\.trycloudflare\.com',(logs/'tunnel.log').read_text())
    if match:url=match.group();break
    time.sleep(.5)
   if not url:raise ValueError('Cloudflare no entregó un enlace a tiempo.')
   stop_local()
   with (logs/'publico.log').open('w') as app_log:
    args=['java','-jar',str(jar),'--spring.profiles.active=local','--spring.config.additional-location=file:'+str(ROOT/'application-local.properties'),'--spring.sql.init.mode=never','--spring.jpa.hibernate.ddl-auto=validate','--server.port=8082','--server.address=127.0.0.1','--server.servlet.session.cookie.secure=true','--server.forward-headers-strategy=framework','--celex.public-url='+url,'--celex.public-testing=true','--celex.backup.enabled=true','--celex.backup.on-startup=true']
    app=subprocess.Popen(args,cwd=ROOT,stdout=app_log,stderr=subprocess.STDOUT);processes.append(app)
    awake=subprocess.Popen(['/usr/bin/caffeinate','-i','-w',str(app.pid)]);processes.append(awake)
    for _ in range(180):
     if app.poll() is not None:raise ValueError('CELEX no inició. Consulta target/publico.log.')
     try:
      if urllib.request.urlopen('http://127.0.0.1:8082/salud',timeout=2).status==200:break
     except Exception:pass
     time.sleep(.5)
    else:raise ValueError('CELEX no quedó disponible a tiempo.')
    (logs/'publicacion.json').write_text(json.dumps({'url':url,'app':app.pid,'tunnel':tunnel.pid,'awake':awake.pid}))
    print('\nCELEX REAL DISPONIBLE PARA PRUEBAS PÚBLICAS:\n'+url+'/login\n',flush=True)
    print('Comparte el enlace. Conserva esta ventana abierta y la Mac conectada. Control+C detiene la publicación. El enlace cambiará al abrirlo de nuevo.',flush=True)
    subprocess.Popen(['/usr/bin/open','-a','Safari',url+'/login'])
    while app.poll() is None and tunnel.poll() is None:time.sleep(1)
    return 0
 finally:
  for proc in reversed(processes):
   if proc.poll() is None:proc.terminate()
  for proc in processes:
   try:proc.wait(timeout=15)
   except subprocess.TimeoutExpired:proc.kill()
if __name__=='__main__':
 try:sys.exit(main())
 except KeyboardInterrupt:print('\nPublicación detenida. Los datos se conservan.')
 except Exception as e:print('No se pudo publicar:',str(e));sys.exit(1)
