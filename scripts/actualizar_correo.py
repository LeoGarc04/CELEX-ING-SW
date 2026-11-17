#!/usr/bin/env python3
"""Actualiza solo el correo local; conserva la base, cuentas y demás configuración."""
from pathlib import Path
import os, shlex, signal, subprocess, sys, time
from arrancar_local import ROOT, configure_mail, guard_private, properties_value, write_private, launch

def replace_mail_settings(text, mail):
    settings = {'spring.mail.host':mail['smtp'],'spring.mail.port':mail['port'],'spring.mail.username':mail['user'],'spring.mail.password':mail['password'],'spring.mail.properties.mail.smtp.auth':'true','spring.mail.properties.mail.smtp.starttls.enable':'false' if mail['port']=='465' else 'true','spring.mail.properties.mail.smtp.starttls.required':'false' if mail['port']=='465' else 'true','spring.mail.properties.mail.smtp.ssl.enable':'true' if mail['port']=='465' else 'false','spring.mail.test-connection':'true','celex.mail-from':mail['sender']}
    lines = [line for line in text.splitlines() if line.split('=',1)[0].strip() not in settings]
    return '\n'.join(lines)+'\n'+'\n'.join(key+'='+properties_value(value) for key,value in settings.items())+'\n'

def main():
    os.chdir(ROOT)
    config=ROOT/'application-local.properties'
    guard_private(config)
    if not config.exists():raise ValueError('Primero completa la configuración inicial de CELEX.')
    print('\nCELEX — actualizar correo y reiniciar\n')
    print('Se conservarán la conexión MySQL, las 13 tablas y todos los datos.')
    mail=configure_mail()
    write_private(config,replace_mail_settings(config.read_text(),mail))
    print('Nueva clave guardada de forma privada. Reiniciando CELEX para utilizarla...')
    listeners=subprocess.run(['/usr/sbin/lsof','-nP','-t','-iTCP:8080','-sTCP:LISTEN'],capture_output=True,text=True).stdout.split()
    for value in set(listeners):
        pid=int(value)
        command=subprocess.run(['/bin/ps','-p',str(pid),'-o','args='],capture_output=True,text=True).stdout.strip()
        args=shlex.split(command)
        if str(ROOT/'target/celex-0.0.1-SNAPSHOT.jar') not in args or '--spring.profiles.active=local' not in args:
            raise ValueError('La configuración se actualizó. El puerto 8080 lo ocupa otro proceso; se conserva sin detenerlo.')
        os.kill(pid,signal.SIGTERM)
        for _ in range(100):
            if subprocess.run(['/bin/ps','-p',str(pid)],stdout=subprocess.DEVNULL).returncode:break
            time.sleep(.1)
        else:raise ValueError('La configuración se actualizó. CELEX sigue cerrando; vuelve a abrir el inicio normal cuando termine.')
    return launch()

if __name__=='__main__':
    try:sys.exit(main())
    except KeyboardInterrupt:print('\nOperación interrumpida.');sys.exit(130)
    except Exception as error:print('\nNo se pudo completar:',str(error));input('\nPresiona Enter para cerrar.');sys.exit(1)
