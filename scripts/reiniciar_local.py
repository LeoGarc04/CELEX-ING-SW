#!/usr/bin/env python3
"""Reinicia exclusivamente la instalación local de CELEX; no modifica datos."""
import os,shlex,signal,subprocess,sys,time
from arrancar_local import ROOT,guard_private,launch

def main():
 config=ROOT/'application-local.properties';guard_private(config)
 if not config.exists():raise ValueError('Primero completa la configuración local de CELEX.')
 pids=subprocess.run(['/usr/sbin/lsof','-nP','-t','-iTCP:8080','-sTCP:LISTEN'],capture_output=True,text=True).stdout.split()
 for value in set(pids):
  pid=int(value);args=shlex.split(subprocess.run(['/bin/ps','-p',str(pid),'-o','args='],capture_output=True,text=True).stdout)
  if str(ROOT/'target/celex-0.0.1-SNAPSHOT.jar') not in args or '--spring.profiles.active=local' not in args:raise ValueError('El puerto lo ocupa otro proceso. Se conserva sin detenerlo.')
  print('Cerrando CELEX para cargar la actualización. La base y los documentos se conservan.')
  os.kill(pid,signal.SIGTERM)
  for _ in range(150):
   if subprocess.run(['/bin/ps','-p',str(pid)],stdout=subprocess.DEVNULL).returncode:break
   time.sleep(.1)
  else:raise ValueError('CELEX sigue cerrando. Espera unos segundos y vuelve a abrir este inicio.')
 return launch()
if __name__=='__main__':
 try:sys.exit(main())
 except KeyboardInterrupt:sys.exit(130)
 except Exception as e:print('No se pudo reiniciar:',str(e));input('Enter para cerrar.');sys.exit(1)
