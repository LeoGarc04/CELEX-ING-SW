#!/usr/bin/env python3
from pathlib import Path
import subprocess,os
root=Path(__file__).resolve().parents[1]
config=root/'application-local.properties'
if not config.exists():raise SystemExit('Primero configura la conexión local con scripts/arrancar_local.py.')
print('Designación del primer administrador. No se crea una cuenta ni se cambia su contraseña.')
email=input('Correo de la cuenta que ya registraste y verificaste: ').strip()
if not email or '@' not in email or any(c.isspace() for c in email):raise SystemExit('Correo inválido.')
if input('Escribe DESIGNAR para conceder administración a esa cuenta: ').strip()!='DESIGNAR':raise SystemExit('Operación cancelada.')
args=['java','-jar',str(root/'target/celex-0.0.1-SNAPSHOT.jar'),'--spring.profiles.active=local','--spring.config.additional-location=file:'+str(config),'--spring.sql.init.mode=never','--spring.jpa.hibernate.ddl-auto=validate','--server.address=127.0.0.1','--server.port=0','--celex.maintenance.enabled=false','--celex.backup.enabled=false','--celex.setup.admin-email='+email]
raise SystemExit(subprocess.call(args,cwd=root))
