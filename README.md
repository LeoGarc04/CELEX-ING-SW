# CELEX · ESIME Culhuacán

Sistema de registro e inscripción: Java 17, Spring Boot 4.1.2, Maven, Thymeleaf, Bootstrap local, JavaScript, Spring Security, Spring Data JPA/Hibernate y MySQL 8.0.46.

## Perfiles

- **Usuario:** cuenta, fila virtual, inscripción, documentos y comprobante propios.
- **Profesor:** grupos asignados por el administrador, alumnos con inscripción confirmada y registro de calificaciones de 0 a 100.
- **Administrador:** convocatorias, oferta académica, usuarios, perfiles, profesores, historial, prioridades, becas, pagos, bitácora, reportes y respaldos.

Solo se reconocen estos tres perfiles. Los permisos administrativos anteriores ya no conceden acceso. Las cuentas nuevas son de usuario; el registro público no permite elegir un perfil privilegiado. La clasificación Comunidad IPN/Público general determina la cuota, no los permisos.

Una cuenta de profesor debe registrarse y verificar su correo. El administrador cambia su perfil en **Perfiles y bitácora** y asigna sus grupos en **Oferta académica**. Escribir un nombre en el campo profesor del grupo no concede acceso. Al cambiar ese nombre mediante la edición del grupo se retira la asignación de cuenta anterior. Los cambios de perfil y asignación se revalidan en cada petición.

## Modelo y arquitectura

`database/celex.sql` conserva las 13 tablas, columnas y relaciones aprobadas. El SQL incluido es el esquema, no una copia de datos de usuarios. Hibernate utiliza `ddl-auto=validate` en ejecución normal.

Capas: `web` para controladores/formularios, `service` para reglas/transacciones, `repository` y `entity` para persistencia. Se conserva la entidad `usuario` y su repositorio JPA; las operaciones de concurrencia utilizan SQL parametrizado con JdbcClient y transacciones de Spring.

Sin ampliar el modelo:

- `roles_permisos` almacena los perfiles y las asignaciones de cuenta a grupo (`DOCENTE_GRUPO_<id>`). Estas asignaciones no son perfiles visibles.
- `historial_academico` guarda calificación, acreditación, profesor responsable y fecha. La bitácora vincula cada registro docente con su grupo.
- `observaciones_dictamen` guarda una estructura versionada con los dictámenes independientes de pago e identificación; se admiten dictámenes anteriores en texto.
- La bitácora conserva también las decisiones de beca por alumno/convocatoria. Es información de negocio y debe respaldarse; no debe purgarse como un log temporal.
- Los documentos se guardan en una carpeta privada y las tablas almacenan referencias opacas. No se publica la carpeta como recurso estático.

Los documentos originales de requisitos, arquitectura y prototipo no se modificaron.

## Configuración y arranque

Requisitos: Java 17, Python 3 para los asistentes y MySQL 8.0.46. El manejo de permisos de archivos privados y los asistentes de publicación se probaron en macOS.

```sh
./mvnw package
python3 scripts/arrancar_local.py
```

El asistente solicita las credenciales únicamente en la terminal, verifica MySQL/SMTP y guarda `application-local.properties` privado, excluido de Git. Si falta la base, requiere confirmación local antes de inicializar el SQL aprobado. No carga usuarios ni grupos de ejemplo. Con la configuración guardada no necesita volver a introducir las claves en cada inicio.

Después del inicio, abrir `http://localhost:8080/login`. Para reiniciar la instalación local:

```sh
python3 scripts/reiniciar_local.py
```

También pueden configurarse variables de entorno usando `.env.example` como referencia y ejecutar `./mvnw spring-boot:run`. El archivo `.env` **no se carga automáticamente**: exporta las variables en la terminal o configúralas en el entorno del IDE.

Variables principales: `DB_URL`, `DB_USER`, `DB_PASSWORD`, `SMTP_HOST`, `SMTP_PORT`, `SMTP_USER`, `SMTP_PASSWORD`, `MAIL_FROM`, `SMTP_AUTH`, `SMTP_TLS`, `IPN_DOMAINS`, `FEE_IPN`, `FEE_PUBLIC`, `CELEX_STORAGE`, `PUBLIC_URL`, `COOKIE_SECURE` y `PORT`. En Gmail se requiere contraseña de aplicación. Nunca introducir credenciales reales en archivos versionados.

No hay cuentas administrativas ni contraseñas por defecto. Para designar al primer administrador, registrar y verificar su cuenta y ejecutar localmente:

```sh
python3 scripts/crear_admin_local.py
```

El asistente requiere confirmación y solo permite la designación inicial cuando no existe administrador. Después los perfiles se gestionan desde el panel. El administrador no puede retirarse su propio perfil.

## Funciones y reglas

- Registro y recuperación con códigos de un solo uso de 15 minutos; contraseñas BCrypt. Cinco fallos bloquean el acceso diez minutos.
- Convocatorias con fechas, cierre manual y capacidad n configurable. Prioridad: becarios por puntaje, externos con promedio mayor a 9, alumnos nuevos y resto; desempate temporal. Se conserva el turno que ya está en atención.
- Un alumno selecciona grupo a la vez por convocatoria. Los latidos cada 20 segundos renuevan la reserva durante tres minutos; el mantenimiento libera turnos vencidos. La estimación inicial de espera es orientativa y se ajusta con selecciones observadas.
- Oferta filtrada por idioma, modalidad, nivel e historial. Inglés: Introductorio, Básico 1–5, Intermedio 1–5 y Avanzado 1–6. El siguiente nivel se asigna administrativamente.
- Validación de traslapes de profesor/aula, transacciones y decremento condicionado para evitar sobrecupos. Las confirmaciones se procesan en orden de recepción en una instancia del servidor, conservando las reglas de turno y elegibilidad. No se promete coordinación FIFO entre varias instancias.
- Cuotas autorizadas: IPN activo $620 MXN; público general y egresados $1,280 MXN. Dominio institucional: `alumno.ipn.mx`. El examen de colocación de $150 es un concepto separado y no se agrega automáticamente al curso.
- Beca previa por alumno/convocatoria, aplicada a la solicitud antes del envío de documentos. Beneficiario tiene cuota cero; la prioridad de becario es una decisión independiente.
- Comprobante e identificación PDF/JPG/PNG de hasta 2 MB cada uno, con comprobación de formato y acceso autorizado.
- Dictámenes independientes: ambos documentos aprobados confirman y generan PDF/QR. El rechazo de un documento exige motivo y abre corrección de dos días hábiles. Se excluyen fines de semana y fechas configuradas en `CELEX_HOLIDAYS` (`AAAA-MM-DD` separadas por comas). Al vencer el plazo se cancela y libera cupo una sola vez.
- Historial de calificaciones: 80 o más acredita. Los profesores solo califican alumnos confirmados en sus grupos; cada guardado conserva trazabilidad. No pueden gestionar pagos, becas, cuentas ni perfiles.
- Reportes CSV, bitácora, PDF con QR y validación pública sin exponer CURP, correo ni documentos privados.

## Compartir pruebas por internet

Para macOS, instalar el ejecutable oficial de `cloudflared` en `.tools/cloudflared` (carpeta excluida de Git). Con la aplicación compilada y la configuración privada guardada:

```sh
python3 scripts/publicar_pruebas.py
```

El asistente abre un túnel temporal y Safari con el enlace HTTPS, usa la base real y evita mantener dos instancias locales. Mantén la terminal abierta, la Mac encendida con tapa abierta e internet. Control+C detiene la publicación y conserva los datos.

El enlace cambia al reiniciar y puede caducar tras una desconexión. Los QR emitidos contienen el enlace de ese momento: para comprobantes duraderos hace falta una dirección estable. Esta modalidad gratuita de pruebas no proporciona disponibilidad permanente ni SLA. GitHub almacena el código; subir el repositorio no inicia ni aloja el servidor Java/MySQL.

## Respaldos y restauración

Los respaldos están habilitados por defecto (`BACKUP_ENABLED=true`) cada domingo a las 03:00, zona America/Mexico_City, mientras la aplicación está encendida. El administrador puede crearlos y descargarlos desde el panel. Requieren `mysqldump`; su ruta predeterminada es `/usr/local/mysql/bin/mysqldump` y puede configurarse con `celex.backup.mysqldump`.

El ZIP incluye SQL y documentos privados. El acceso MySQL se toma de la configuración privada, usando un archivo temporal 0600 que se elimina y no entra en el ZIP; las claves no se pasan en argumentos. Deben definirse retención, copia externa y monitoreo antes de operar en producción.

Para restaurar: detener CELEX, conservar primero un respaldo actual y ejecutar `python3 scripts/restaurar_respaldo.py RUTA_DEL_ZIP`. La operación requiere un operador y confirmación explícita de la base que se reemplazará. Preserva los documentos anteriores en una carpeta privada aparte. La restauración se ensayó en MySQL desechable; no se restauró la base real como parte de las pruebas.

## Pruebas

```sh
./mvnw test
```

Ejecuta pruebas H2 aisladas, sin credenciales reales ni correos externos. La suite MySQL se omite salvo configuración explícita. Última verificación de perfiles: 29 pruebas generales y 25 con MySQL aislado, sin fallos; también se revisó el profesor en navegador de escritorio y móvil.

Para MySQL usa exclusivamente una base desechable `celex_test_suite` en 127.0.0.1, y define `CELEX_TEST_MYSQL_URL`, `CELEX_TEST_MYSQL_USER`, `CELEX_TEST_MYSQL_PASSWORD`:

```sh
./mvnw -Dtest=MySqlWorkflowTest test
```

Las pruebas inicializan y limpian tablas, y ensayan restauración: **nunca apuntarlas a una base con datos reales**. Cubren registro, bloqueo, perfiles, aislamiento de grupos, calificaciones, fila, concurrencia, becas, documentos, correcciones, PDF y respaldo/restauración. La carga interna de 200 ingresos no sustituye una medición HTTP desde internet ni acredita disponibilidad de producción.

## Archivos excluidos

No se versionan credenciales locales, `.env`, archivos de usuarios, respaldos, `.tools/`, logs, `target/`, cachés Python ni `.DS_Store`. Los artefactos generados anteriormente seguidos se retiran del índice, conservando los archivos físicos. Bootstrap se distribuye localmente con su aviso de licencia.

## Límites que requieren validación operativa

Disponibilidad durante toda la convocatoria, aceptación con usuarios, calendario festivo concreto, reglas de inactividad de Introductorio, tratamiento de comprobantes de becarios y dirección pública estable. La implementación y sus pruebas no equivalen a una certificación de producción.
