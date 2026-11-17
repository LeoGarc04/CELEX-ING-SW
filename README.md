# CELEX · ESIME Culhuacán

Sistema de registro e inscripción a cursos de idiomas.

**Tecnologías:** Java 17, Spring Boot 4.1.2, Maven, Thymeleaf, Bootstrap, JavaScript, Spring Security, Spring Data JPA y MySQL 8.0.46.

## Perfiles

| Perfil | Funciones |
|---|---|
| Usuario | Registro, fila virtual, inscripción, documentos y comprobante propios. |
| Profesor | Consulta de sus grupos y registro de calificaciones de alumnos confirmados. |
| Administrador | Convocatorias, grupos, cuentas, profesores, historial, prioridades, becas, pagos, bitácora, reportes y respaldos. |

Las cuentas nuevas tienen perfil Usuario. El administrador puede cambiarlo en **Perfiles y bitácora** y asignar grupos a profesores desde **Oferta académica**. El nombre del profesor en un grupo no sustituye la asignación de su cuenta.

La clasificación IPN/Público general se utiliza para calcular cuotas y es independiente del perfil.

## Instalación

Se requiere Java 17, Python 3 y MySQL. Los scripts de inicio y publicación están preparados para macOS.

```sh
./mvnw package
python3 scripts/arrancar_local.py
```

El script verifica MySQL y SMTP, y guarda la configuración en `application-local.properties`, con permisos privados y excluido de Git. La creación inicial de la base requiere confirmación. El esquema está en `database/celex.sql`.

Abre `http://localhost:8080/login`. Para reiniciar:

```sh
python3 scripts/reiniciar_local.py
```

También puedes configurar las variables indicadas en `.env.example` y ejecutar `./mvnw spring-boot:run`. El archivo `.env` no se carga automáticamente: debes exportar las variables o configurarlas en el IDE. Para Gmail utiliza una contraseña de aplicación.

Para designar al primer administrador, registra y verifica su cuenta y ejecuta:

```sh
python3 scripts/crear_admin_local.py
```

## Inscripciones

- Convocatorias con fechas de apertura, cierre y capacidad de fila configurables.
- Prioridades: becarios por puntaje, externos con promedio mayor a 9, alumnos nuevos y resto. Los empates se resuelven por orden de ingreso.
- Durante la espera el alumno permanece en una vista sin menú de secciones. Las direcciones de otras secciones vuelven a su fila activa; al confirmar grupo se recupera la navegación. El administrador conserva acceso a la gestión.
- La conexión a la fila se renueva cada 20 segundos. La reserva vence después de tres minutos sin conexión.
- Oferta por idioma, modalidad, nivel e historial. Inglés: Introductorio, Básico 1–5, Intermedio 1–5 y Avanzado 1–6.
- Selección de grupo con control de cupo y validación de traslapes de profesor y aula.
- Cuotas: IPN activo $620 MXN; público general y egresados $1,280 MXN. El examen de colocación es un concepto separado de $150 y no se cobra en este flujo.
- Becas por alumno y convocatoria. La beca y la prioridad de fila se registran por separado.
- Pago e identificación en PDF, JPG o PNG, hasta 2 MB por archivo.
- Dictamen independiente de ambos documentos. Las correcciones tienen un plazo de dos días hábiles; al vencer se cancela la solicitud y se libera el cupo.
- Comprobante PDF con QR, validación pública, reportes CSV y bitácora.

Los días festivos se configuran en `CELEX_HOLIDAYS`, separados por comas y con formato `AAAA-MM-DD`. Una calificación de 80 o más acredita el nivel; la asignación del siguiente nivel corresponde al administrador.

## Estructura y datos

El proyecto se organiza en `web`, `service`, `repository`, `entity` y `config`. El esquema contiene 13 tablas y Hibernate valida su estructura al iniciar.

Los documentos se almacenan en una carpeta privada, indicada por `CELEX_STORAGE`; las tablas guardan sus referencias. Los perfiles y las asignaciones docentes utilizan `roles_permisos`. Los dictámenes de documentos se guardan como datos versionados en `observaciones_dictamen`.

La bitácora vincula calificaciones con grupos y conserva decisiones de beca. Debe mantenerse junto con el historial académico en los respaldos.

## Publicación para pruebas

Instala `cloudflared` en `.tools/cloudflared` y ejecuta:

```sh
python3 scripts/publicar_pruebas.py
```

El script abre Safari con un enlace HTTPS temporal. Mantén la terminal abierta y la Mac encendida, con tapa abierta e internet. Control+C detiene la publicación.

El enlace cambia al reiniciar y puede caducar al perder conexión. Los QR emitidos conservan la dirección de ese momento. Para uso permanente se necesita alojamiento y una dirección estable; GitHub almacena el código, no ejecuta el servidor.

## Respaldos

El respaldo semanal se ejecuta los domingos a las 03:00, hora de Ciudad de México, mientras la aplicación esté encendida. Puede configurarse con `BACKUP_ENABLED` y `BACKUP_DIRECTORY`. El administrador también puede crear y descargar respaldos desde el panel.

Se requiere `mysqldump`; su ruta se configura con `celex.backup.mysqldump`. El ZIP incluye SQL y documentos. Las credenciales se toman de la configuración privada y no se incluyen en el respaldo.

Para restaurar, detén CELEX, conserva una copia actual y ejecuta:

```sh
python3 scripts/restaurar_respaldo.py RUTA_DEL_ZIP
```

La restauración solicita confirmación de la base que se reemplazará. Define retención, copia externa y monitoreo antes de operar con datos reales.

## Pruebas

```sh
./mvnw test
```

Las pruebas generales usan H2 y no envían correos externos. Para la suite MySQL, crea una base desechable `celex_test_suite` en 127.0.0.1 y define `CELEX_TEST_MYSQL_URL`, `CELEX_TEST_MYSQL_USER` y `CELEX_TEST_MYSQL_PASSWORD`:

```sh
./mvnw -Dtest=MySqlWorkflowTest test
```

Esta suite crea y limpia tablas y prueba la restauración. No debe ejecutarse contra una base con datos reales. La última revisión de perfiles pasó 32 pruebas generales y 28 con MySQL; también se comprobó la pantalla de profesor en escritorio y móvil.

## Archivos privados

Git excluye credenciales, `.env`, documentos de usuarios, respaldos, logs, `.tools/`, `target/`, cachés Python y `.DS_Store`. Bootstrap conserva su aviso de licencia.

Antes de producción quedan por validar la disponibilidad, la aceptación con usuarios, el calendario festivo, las reglas de inactividad de Introductorio y el tratamiento de comprobantes de becarios. El control de orden de confirmaciones corresponde a una sola instancia del servidor.
