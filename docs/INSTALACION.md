# Instalación de CELEX en otro equipo

Esta guía instala la aplicación real con una base nueva. El repositorio no incluye cuentas, contraseñas, pagos, documentos ni respaldos del servidor de origen.

## Requisitos

- JDK 17 y la variable JAVA_HOME configurada. Comprobar con `java -version`.
- MySQL 8.0.46 encendido y un usuario con acceso a la base `celex`.
- Git para clonar el repositorio. Maven se descarga mediante el wrapper incluido; se requiere internet durante la primera compilación.
- Una cuenta SMTP para enviar verificaciones. Para Gmail se necesita contraseña de aplicación, ingresada solo en la configuración local.
- Python 3 si se utilizan los asistentes de configuración o de primer administrador.

## Descargar y compilar

```sh
git clone https://github.com/LeoGarc04/CELEX-ING-SW.git
cd CELEX-ING-SW
```

En Windows, desde PowerShell:

```powershell
.\mvnw.cmd clean package
```

En macOS o Linux:

```sh
chmod +x mvnw
./mvnw clean package
```

Las pruebas generales usan una base H2 aislada y correo simulado; no necesitan las credenciales de la base real. No activar el perfil `test` para usar el sistema normalmente.

## Actualizar una instalación existente

El procedimiento de instalación y los requisitos técnicos se conservan. Esta actualización no agrega tablas, columnas, dependencias externas ni variables de configuración obligatorias.

1. Detén CELEX y conserva un respaldo privado de la base y de los documentos.
2. Desde el repositorio ejecuta `git pull --ff-only`. Si hay cambios locales o una divergencia, consérvalos y resuélvelos antes de actualizar; no los sobrescribas.
3. Compila de nuevo con `./mvnw clean package` o `.\mvnw.cmd clean package` en Windows.
4. Inicia con el mismo comando de Java y tu archivo `application-local.properties` existente.

No vuelvas a crear la base ni ejecutes el esquema inicial sobre información existente. La única migración previa sigue siendo la de boleta para bases antiguas que aún no la tengan.

## Crear una base vacía

Conéctate desde MySQL Workbench o el cliente MySQL. Ejecuta esto únicamente si no existe la base:

```sql
CREATE DATABASE celex CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;
```

Abre y ejecuta `database/celex.sql` en esa base nueva. Contiene la estructura de las 13 tablas, sin usuarios ni datos de ejemplo. No ejecutes este archivo sobre una base con información existente.

Si trasladas los datos del equipo anterior, utiliza un respaldo privado de base y documentos en lugar de inicializar tablas vacías. Un respaldo antiguo que aún no incluya `usuarios.boleta` requiere la migración `database/migrations/20261117_boleta.sql`, una sola vez y con respaldo previo. La instalación nueva ya tiene ese cambio y no requiere la migración.

## Configuración privada

Copia `application-local.example.properties` a `application-local.properties` en la raíz del proyecto. Sustituye todos los valores que dicen CAMBIAR y guarda el archivo. En Windows comprueba que no se haya guardado con extensión `.txt`.

- Usuario y contraseña MySQL: corresponden a este equipo, no a una cuenta de CELEX.
- Correo y contraseña SMTP: corresponden al remitente que enviará los códigos a los alumnos.
- Para Gmail, conserva `smtp.gmail.com`, puerto 587 y TLS. No utilices la contraseña normal del correo.
- `celex.storage` apunta a la carpeta privada de documentos; no debe publicarse como carpeta estática.
- `celex.public-url` debe ser la dirección por la que se accede al sistema. Para pruebas locales conserva `http://localhost:8080`.

El archivo privado está excluido de Git. No subas capturas que muestren su contenido ni lo compartas en GitHub.

## Encender el servidor

Desde la raíz del proyecto, en cualquier sistema operativo con Java instalado:

```sh
java -jar target/celex-0.0.1-SNAPSHOT.jar --spring.profiles.active=local --spring.config.additional-location=file:./application-local.properties
```

Mantén esa terminal abierta. Cuando termine el arranque entra a:

```text
http://localhost:8080/login
```

Control+C detiene la aplicación y conserva la información en MySQL y en la carpeta de documentos. Para encenderla nuevamente repite el comando. Si el puerto 8080 está ocupado puedes añadir `--server.port=8081` y ajustar `celex.public-url` al mismo puerto antes de emitir comprobantes.

En macOS también puedes usar el asistente `python3 scripts/arrancar_local.py`; requiere el cliente MySQL instalado en `/usr/local/mysql/bin`. Los asistentes de inicio, reinicio y publicación fueron preparados para macOS. La ruta manual de Java anterior permite ejecutar la aplicación en Windows y Linux.

## Ejecutar desde Visual Studio Code

Abre la carpeta raíz `CELEX-ING-SW` y entra en **Ejecutar y depurar** (el icono de triángulo con un insecto). Las configuraciones ya están incluidas:

- **CELEX · Publicar en internet (Mac):** pulsa el triángulo verde o F5. Compila el proyecto sin repetir la suite de pruebas, inicia el servidor real con su configuración local y abre el enlace temporal de Cloudflare. Requiere la configuración privada, MySQL encendido y `.tools/cloudflared`, como el asistente de publicación habitual. Cierra cualquier publicación anterior antes de iniciarla.
- **CELEX · Ejecutar y depurar localmente:** inicia Spring Boot con la configuración privada y permite usar puntos de interrupción. Requiere Extension Pack for Java de Microsoft. Abre `http://localhost:8080/login`; esta opción no crea un enlace público.

No necesitas escribir comandos. VS Code muestra los mensajes en su terminal integrada. El botón Detener termina la ejecución iniciada desde VS Code. La publicación pública sigue requiriendo el equipo encendido y conexión a internet.

## Primer administrador

1. Registra y verifica tu cuenta desde la página. Las cuentas nuevas empiezan como Usuario.
2. Detén el servidor con Control+C.
3. Desde la raíz ejecuta `python3 scripts/crear_admin_local.py`. En Windows utiliza `py scripts/crear_admin_local.py` si Python está instalado mediante ese lanzador.
4. Escribe el correo de la cuenta verificada y confirma con DESIGNAR. Solo funciona cuando la base aún no tiene administrador; no crea una cuenta ni cambia su contraseña.
5. Enciende CELEX de nuevo e inicia sesión. La cuenta entrará al panel administrativo.

Después, los perfiles y permisos se gestionan desde administración. Para que un profesor vea su grupo debes vincular su cuenta desde Oferta académica: escribir su nombre en el grupo no sustituye esa vinculación.

## Preparar una convocatoria

1. Crear idiomas y grupos, o seleccionar grupos anteriores como plantillas al crear una convocatoria.
2. Registrar apertura, cierre, capacidad, inicio de clases y cupo de examen. Guardar y habilitar la convocatoria.
3. Registrar prioridades y becas aplicables antes de la selección.
4. Probar con otra cuenta de Usuario: sala de espera, preferencias, pago y consulta de estado.
5. Desde administración asignar grupo definitivo y dictaminar. El plazo de revisión es de 48 horas; no se aprueba automáticamente al vencer.

Las listas docentes de nuevas convocatorias requieren cierre de convocatoria y que los grupos ya estén habilitados desde 48 horas antes de clases. Solo incluyen inscripciones confirmadas.

## Acceso desde otros dispositivos

`localhost` solo funciona en el equipo donde está el servidor. Para probar dentro de la misma red se necesita su dirección IP y permitir el puerto en el cortafuegos. No abras el puerto de MySQL a internet.

TryCloudflare permite un enlace temporal mientras ese equipo permanezca encendido; el asistente `scripts/publicar_pruebas.py` es para macOS y requiere `cloudflared` en `.tools/cloudflared`. No se incluye ese ejecutable en Git. Para una dirección estable y acceso con el equipo apagado se necesita alojamiento independiente.

## Respaldos y traslado de datos

Para disponer de los usuarios e inscripciones actuales en otro equipo, el código de GitHub no basta: se necesita un respaldo privado de la base y de la carpeta de documentos, incluida la bitácora que conserva configuración operativa. No guardes ese respaldo en el repositorio.

Configura la ruta de `mysqldump` antes de habilitar respaldos automáticos. La plantilla los deja desactivados hasta configurar esa herramienta; en el servidor habitual pueden permanecer habilitados. La restauración reemplaza información: revisa el procedimiento y conserva una copia antes de ejecutarla.

## Problemas frecuentes

- Java incorrecto: configurar JAVA_HOME para JDK 17 y abrir otra terminal.
- Acceso MySQL rechazado: revisar usuario, contraseña, puerto y que el servicio esté encendido.
- Error de validación de tablas: falta ejecutar el esquema inicial o la migración correspondiente a una base antigua. Hibernate valida; no crea ni cambia tablas automáticamente.
- No llega el código: revisar SMTP, contraseña de aplicación, spam y conexión de red. Usa el reenvío desde verificación.
- Profesor sin grupo: revisar la vinculación de cuenta, convocatoria y fecha de inicio de clases.
- Fila muestra una inscripción ya iniciada: al volver al folio debe abrir su solicitud; no es necesario crear otra ni consumir un segundo cupo.
