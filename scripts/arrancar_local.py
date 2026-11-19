#!/usr/bin/env python3
"""Configuración e inicio local de CELEX."""
from pathlib import Path
import getpass, hashlib, json, os, re, subprocess, sys, tempfile

ROOT = Path(__file__).resolve().parents[1]
EXPECTED = ('usuarios','roles_permisos','verificaciones_codigo','convocatorias','idiomas','grupos','historial_academico','prioridades_usuario','fila_virtual','solicitudes_inscripcion','documentos_comprobantes','comprobantes_oficiales','bitacora_acciones')
SCHEMA_HASH = '5fc2d8b246f5cb1da6c13f79e793863b4f04e3b8e70ef665fd1b125d29e87ff9'

def ask(label, default=''):
    value = input(label + (f' [{default}]' if default else '') + ': ').strip()
    return value or default

def guard_private(path):
    if path.is_symlink():
        raise ValueError('La configuración privada no debe ser un enlace.')
    ignored = subprocess.run(['git','check-ignore','--quiet','--',str(path)], cwd=ROOT).returncode == 0
    tracked = subprocess.check_output(['git','-c','core.fsmonitor=false','ls-files','--',str(path)], cwd=ROOT, text=True)
    if not ignored or tracked.strip():
        raise ValueError('El archivo privado no está excluido correctamente de Git. No se guardarán credenciales.')
    if path.exists():
        path.chmod(0o600)

def write_private(path, text):
    guard_private(path)
    fd, name = tempfile.mkstemp(prefix='.celex-private-', dir=str(ROOT/'target'))
    temporary = Path(name)
    try:
        os.chmod(name, 0o600)
        with os.fdopen(fd,'w') as output:
            output.write(text)
        os.replace(temporary, path)
    finally:
        temporary.unlink(missing_ok=True)

def properties_value(value):
    return value.replace('\\','\\\\').replace('\n','\\n').replace('\r','\\r').replace('\t','\\t').replace(' ','\\ ')

def mysql_error(result):
    match = re.search(r'ERROR\s+(\d+)', result.stderr)
    code = match.group(1) if match else ''
    messages = {
        '1045':'MySQL respondió, pero rechazó el acceso. Revisa su usuario y contraseña.',
        '2002':'No se pudo conectar a MySQL. Revisa que esté encendido.',
        '2003':'No se pudo conectar a MySQL. Revisa servidor y puerto.',
        '1044':'El usuario no tiene permiso sobre la base indicada. No se cambiaron permisos.',
        '2026':'No se pudo establecer la conexión segura con MySQL.'}
    return ValueError(messages.get(code,'MySQL no pudo completar la operación') + (' (código '+code+').' if code else '.'))

class Mysql:
    def __init__(self, state): self.state = state
    def __enter__(self):
        def option(value): return '"' + value.replace('\\','\\\\').replace('"','\\"') + '"'
        file = tempfile.NamedTemporaryFile(mode='w',prefix='celex-db-',delete=False)
        self.path = Path(file.name)
        os.chmod(self.path,0o600)
        with file:
            file.write('[client]\nprotocol=TCP\nhost='+option(self.state['host'])+'\nport='+self.state['port']+'\nuser='+option(self.state['user'])+'\npassword='+option(self.state['password'])+'\n')
        return self
    def __exit__(self,*args): self.path.unlink(missing_ok=True)
    def run(self, sql, database=None):
        args = ['/usr/local/mysql/bin/mysql','--defaults-file='+str(self.path),'--batch','--skip-column-names']
        if database: args.append('--database='+database)
        result = subprocess.run(args,input=sql,capture_output=True,text=True)
        if result.returncode: raise mysql_error(result)
        return result.stdout.strip()

def initialize(client, create_database):
    sql = (ROOT/'database/celex.sql').read_bytes()
    if hashlib.sha256(sql).hexdigest() != SCHEMA_HASH:
        raise ValueError('El SQL difiere del modelo revisado. Se conserva sin ejecutar hasta revisarlo.')
    # Comprobar que la base siga vacía antes de crear las tablas.
    exists = int(client.run("SELECT COUNT(*) FROM information_schema.SCHEMATA WHERE SCHEMA_NAME='celex'"))
    if create_database and exists:
        raise ValueError('La base celex apareció durante la configuración; se conserva sin cambios.')
    if not create_database:
        count = int(client.run("SELECT COUNT(*) FROM information_schema.TABLES WHERE TABLE_SCHEMA='celex'"))
        if count:
            raise ValueError('La base ya contiene tablas; se conserva sin cambios.')
    print('\nSe creará el esquema de database/celex.sql.')
    print('No se cargarán usuarios, grupos ni otros datos de ejemplo.')
    if ask('Para autorizar esta creación escribe CREAR') != 'CREAR':
        raise ValueError('Creación cancelada. Puedes reanudar después sin repetir el acceso guardado.')
    if create_database:
        client.run('CREATE DATABASE celex CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;')
    client.run(sql.decode('utf-8'))
    print('Base celex inicializada.')

def configure_database():
    path = ROOT/'.celex-setup.json'
    guard_private(path)
    if path.exists():
        state = json.loads(path.read_text())
        print('Se reanudará el acceso MySQL guardado de forma privada.')
    else:
        state = {'user':ask('Usuario de MySQL','root'),'password':getpass.getpass('Contraseña de MySQL: '),'host':ask('Servidor de MySQL','localhost'),'port':ask('Puerto MySQL','3306'),'database':ask('Base de datos','celex')}
    if not state['port'].isdigit() or not state['database'].replace('_','').isalnum() or any(c in state['host'] for c in '\r\n/;'):
        raise ValueError('Revisa servidor, puerto y nombre de base.')
    if any('\n' in value or '\r' in value for value in state.values()):
        raise ValueError('Los valores no deben contener saltos de línea.')
    with Mysql(state) as client:
        client.run('SELECT 1')
        print('Acceso a MySQL verificado.')
        write_private(path,json.dumps(state))
        available = client.run("SELECT SCHEMA_NAME FROM information_schema.SCHEMATA WHERE SCHEMA_NAME NOT IN ('mysql','information_schema','performance_schema','sys') ORDER BY SCHEMA_NAME").splitlines()
        expected = ','.join("'"+name+"'" for name in EXPECTED)
        rows = client.run('SELECT TABLE_SCHEMA,COUNT(DISTINCT TABLE_NAME) FROM information_schema.TABLES WHERE TABLE_NAME IN ('+expected+') GROUP BY TABLE_SCHEMA ORDER BY TABLE_SCHEMA')
        counts = {}
        for line in rows.splitlines():
            name,count = line.split('\t');counts[name] = int(count)
        print('Bases disponibles: '+(', '.join(available) if available else 'ninguna base de usuario'))
        database = state['database']
        if counts.get(database) != 13:
            candidates = [name for name,count in counts.items() if count == 13]
            if candidates:
                database = ask('Base con las 13 tablas que quieres usar',candidates[0])
            elif database == 'celex' and database not in available:
                initialize(client,True)
            elif database == 'celex' and int(client.run("SELECT COUNT(*) FROM information_schema.TABLES WHERE TABLE_SCHEMA='celex'")) == 0:
                initialize(client,False)
            else:
                raise ValueError('La base seleccionada no contiene el modelo completo. Se conservaron sus tablas y datos.')
        if not database.replace('_','').isalnum(): raise ValueError('Nombre de base no válido.')
        count = int(client.run('SELECT COUNT(*) FROM information_schema.TABLES WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME IN ('+expected+')',database))
        total = int(client.run('SELECT COUNT(*) FROM information_schema.TABLES WHERE TABLE_SCHEMA=DATABASE()',database))
        if count != 13: raise ValueError('La inicialización no quedó completa. No se borrará ni recreará ninguna tabla automáticamente.')
        state['database'] = database
        write_private(path,json.dumps(state))
        (ROOT/'target/diagnostico-mysql.json').write_text(json.dumps({'conexion_verificada':True,'servidor':state['host'],'puerto':state['port'],'base_seleccionada':database,'tablas_celex':count,'tablas_totales':total},indent=2))
    print('Conexión verificada. Se encontraron las 13 tablas.\n')
    return state

def configure_mail():
    import smtplib, ssl
    while True:
        print('Configura el correo emisor de CELEX. Para Gmail usa una contraseña de aplicación de 16 caracteres, no la contraseña normal.')
        state = {'sender':'celex0511@gmail.com','smtp':'smtp.gmail.com','port':'587','user':'celex0511@gmail.com'}
        print('Correo emisor: '+state['sender'])
        print('Servidor y puerto: smtp.gmail.com · 587 (configurados automáticamente)')
        print('Pega únicamente la NUEVA contraseña de aplicación en el siguiente campo oculto.')
        state['password'] = getpass.getpass('CLAVE DE APLICACIÓN DE GOOGLE (16 caracteres; no se verá al pegar): ')
        if state['smtp'] == 'smtp.gmail.com':
            state['password'] = ''.join(state['password'].split())
            if len(state['password']) != 16 or not state['password'].isascii():
                print('Esa no tiene el formato de una contraseña de aplicación de Google. Genera una en https://myaccount.google.com/apppasswords y pega la clave de 16 caracteres. No se intentó iniciar sesión.')
                if ask('Enter para reintentar; SALIR para continuar después') == 'SALIR':
                    raise ValueError('El acceso a MySQL quedó guardado. Puedes reanudar cuando tengas la contraseña de aplicación.')
                continue
        if not all(state.values()) or not state['port'].isdigit() or any('\r' in v or '\n' in v for v in state.values()):
            print('Faltan datos o alguno tiene formato incorrecto.');continue
        phase='conexión'
        try:
            print('Comprobando acceso al correo, sin enviar mensajes...')
            if state['port'] == '465':
                with smtplib.SMTP_SSL(state['smtp'],int(state['port']),timeout=15,context=ssl.create_default_context()) as connection:
                    phase='autenticación';connection.login(state['user'],state['password'])
            else:
                with smtplib.SMTP(state['smtp'],int(state['port']),timeout=15) as connection:
                    connection.ehlo();phase='certificado y TLS';connection.starttls(context=ssl.create_default_context());connection.ehlo();phase='autenticación';connection.login(state['user'],state['password'])
            print('Acceso al correo verificado.')
            return state
        except (OSError,smtplib.SMTPException,UnicodeError) as error:
            diagnostic={'servidor':state['smtp'],'puerto':state['port'],'fase':phase,'tipo_error':type(error).__name__,'codigo_smtp':getattr(error,'smtp_code',None),'codigo_red':getattr(error,'errno',None)}
            (ROOT/'target/diagnostico-correo.json').write_text(json.dumps(diagnostic,ensure_ascii=False,indent=2))
            if isinstance(error,smtplib.SMTPAuthenticationError):
                print('Gmail rechazó el acceso. Comprueba la dirección, la verificación en dos pasos y la contraseña de aplicación de esa misma cuenta.')
            elif isinstance(error,ssl.SSLCertVerificationError):
                print('No se pudo verificar el certificado del correo. No se desactivará la seguridad TLS; se debe revisar la instalación de certificados.')
            elif isinstance(error,(TimeoutError,ConnectionError)):
                print('La conexión se interrumpió o tardó demasiado. Revisa internet y vuelve a intentarlo.')
            else:
                print('Falló la etapa de '+phase+' ('+type(error).__name__+'). El diagnóstico sin contraseñas está en target/diagnostico-correo.json.')
        if ask('Enter para reintentar; escribe SALIR para continuar más tarde') == 'SALIR':
            raise ValueError('Configuración pausada. El acceso MySQL permanece guardado de forma privada.')

def save_configuration(db, mail):
    config = ROOT/'application-local.properties'
    if config.exists(): raise ValueError('Ya existe configuración local. Se conserva sin sobrescribirla.')
    settings = {'spring.datasource.url':f"jdbc:mysql://{db['host']}:{db['port']}/{db['database']}",'spring.datasource.username':db['user'],'spring.datasource.password':db['password'],'spring.jpa.hibernate.ddl-auto':'validate','spring.mail.host':mail['smtp'],'spring.mail.port':mail['port'],'spring.mail.username':mail['user'],'spring.mail.password':mail['password'],'spring.mail.properties.mail.smtp.auth':'true','spring.mail.properties.mail.smtp.starttls.enable':'false' if mail['port']=='465' else 'true','spring.mail.properties.mail.smtp.starttls.required':'false' if mail['port']=='465' else 'true','spring.mail.properties.mail.smtp.ssl.enable':'true' if mail['port']=='465' else 'false','spring.mail.test-connection':'true','celex.mail-from':mail['sender'],'celex.institutional-domains':'alumno.ipn.mx','celex.fee-ipn':'620.00','celex.fee-public':'1280.00','celex.storage':str(ROOT/'storage'),'celex.public-url':'http://localhost:8080','server.address':'127.0.0.1','server.port':'8080'}
    write_private(config,'# Configuración privada local. No subir a Git.\n'+'\n'.join(key+'='+properties_value(value) for key,value in settings.items())+'\n')
    (ROOT/'.celex-setup.json').unlink(missing_ok=True)
    print('Configuración privada guardada; las credenciales no se subirán a Git.')

def launch():
    jar = ROOT/'target/celex-0.0.1-SNAPSHOT.jar'
    if not jar.exists():raise ValueError('Falta compilar el ejecutable antes de iniciar.')
    print('\nIniciando CELEX. Mantén esta ventana abierta.')
    print('Cuando aparezca “Started CelexApplication”, abre http://localhost:8080/login')
    print('Para detenerlo, presiona Control+C.\n')
    env = os.environ.copy()
    for key in list(env):
        if key.startswith(('SPRING_PROFILES_','SPRING_DATASOURCE_','SPRING_SQL_INIT_')) or key in ('SPRING_APPLICATION_JSON','SPRING_CONFIG_IMPORT','SPRING_CONFIG_LOCATION'):env.pop(key,None)
    args = ['java','-jar',str(jar),'--spring.profiles.active=local','--spring.config.additional-location=file:'+str(ROOT/'application-local.properties'),'--spring.sql.init.mode=never','--spring.jpa.hibernate.ddl-auto=validate']
    process = subprocess.Popen(args,cwd=ROOT,env=env)
    try:return process.wait()
    except KeyboardInterrupt:
        process.terminate();return process.wait()

def main():
    os.chdir(ROOT);(ROOT/'target').mkdir(exist_ok=True)
    print('\nCELEX — configuración e inicio\n')
    print('Si la base no existe, se pedirá confirmación para crearla. Las tablas existentes se conservarán.')
    print('Introduce las contraseñas en esta terminal.\n')
    config = ROOT/'application-local.properties';guard_private(config)
    if not config.exists():save_configuration(configure_database(),configure_mail())
    else:print('Se utilizará la configuración privada ya guardada, sin mostrar sus valores.')
    return launch()

if __name__ == '__main__':
    try:sys.exit(main())
    except KeyboardInterrupt:
        print('\nConfiguración interrumpida. Ejecuta de nuevo el script para continuar.');sys.exit(130)
    except Exception as error:
        print('\nNo se inició el sistema:',str(error));input('\nPresiona Enter para cerrar.');sys.exit(1)
