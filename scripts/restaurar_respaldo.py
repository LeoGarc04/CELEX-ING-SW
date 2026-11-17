#!/usr/bin/env python3
"""Restauración manual y explícita de un respaldo CELEX generado por la aplicación."""
import argparse,getpass,os,re,subprocess,tempfile,zipfile,shutil,stat
from pathlib import Path
ROOT=Path(__file__).resolve().parents[1]
def main():
 parser=argparse.ArgumentParser();parser.add_argument('respaldo',type=Path);args=parser.parse_args()
 for port in (8080,8082):
  if subprocess.run(['/usr/sbin/lsof','-nP','-t','-iTCP:'+str(port),'-sTCP:LISTEN'],capture_output=True).stdout:raise ValueError('Detén CELEX y su publicación antes de restaurar.')
 database=input('Nombre exacto de la base que se restaurará [celex]: ').strip() or 'celex'
 if not re.fullmatch(r'[A-Za-z0-9_]+',database):raise ValueError('Nombre de base inválido.')
 host=input('Servidor MySQL [localhost]: ').strip() or 'localhost';port=int(input('Puerto [3306]: ').strip() or '3306');user=input('Usuario MySQL [root]: ').strip() or 'root';password=getpass.getpass('Contraseña MySQL (privada): ')
 print('Se reemplazarán los registros de '+database+' por el respaldo. Conserva primero un respaldo actual; los documentos actuales se guardarán en una carpeta aparte.')
 if input('Para continuar escribe RESTAURAR '+database+': ')!='RESTAURAR '+database:raise ValueError('Restauración cancelada.')
 os.umask(0o077)
 with tempfile.TemporaryDirectory(prefix='celex-restore-') as folder:
  stage=Path(folder);total=0
  with zipfile.ZipFile(args.respaldo) as archive:
   for item in archive.infolist():
    total+=item.file_size
    if total>2*1024**3 or stat.S_ISLNK(item.external_attr>>16) or not (item.filename=='database.sql' or re.fullmatch(r'documents/[A-Za-z0-9_.-]+',item.filename)):raise ValueError('Contenido de respaldo inválido.')
   archive.extractall(stage)
  sql=stage/'database.sql'
  if not sql.is_file():raise ValueError('Falta database.sql.')
  text=sql.read_text()
  names=re.findall(r'(?:USE\s+|CREATE DATABASE[^;]*?)(?:`)([^`]+)(?:`)',text,re.I)
  if not names or any(name!=database for name in names):raise ValueError('El respaldo pertenece a otra base. No se modificó MySQL.')
  def quote(value):return '"'+value.replace('\\','\\\\').replace('"','\\"').replace('\n','\\n').replace('\r','\\r')+'"'
  client=stage/'client.cnf';client.write_text('[client]\nhost='+quote(host)+'\nport='+str(port)+'\nuser='+quote(user)+'\npassword='+quote(password)+'\n');client.chmod(0o600)
  with sql.open('rb') as data:
   result=subprocess.run(['/usr/local/mysql/bin/mysql','--defaults-extra-file='+str(client),database],stdin=data,capture_output=True)
  if result.returncode:raise ValueError('MySQL no completó la restauración. No reinicies CELEX hasta revisar el respaldo; los documentos actuales se conservaron.')
  storage=ROOT/'storage';documents=stage/'documents'
  if storage.exists():storage.rename(ROOT/('storage-anterior-'+__import__('datetime').datetime.now().strftime('%Y%m%d-%H%M%S')))
  storage.mkdir(mode=0o700)
  if documents.exists():
   for file in documents.iterdir():shutil.copy2(file,storage/file.name)
 print('Restauración finalizada. Puedes volver a iniciar CELEX. No se alteró la definición del modelo.')
if __name__=='__main__':
 try:main()
 except (Exception,KeyboardInterrupt) as error:print('Restauración detenida:',str(error));raise SystemExit(1)
