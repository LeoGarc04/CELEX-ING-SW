# CELEX ESIME Culhuacán

Sistema de registro, fila virtual e inscripción a cursos de idiomas.

**Stack:** Java 17, Spring Boot 4.1.2, Maven, Thymeleaf, Bootstrap, JavaScript, Spring Security, Spring Data JPA/Hibernate y MySQL 8.0.46.

## Instalar en otro equipo

Consulta [la guía de instalación](docs/INSTALACION.md) para Windows, macOS y Linux. Incluye creación de la base, configuración del correo, arranque y designación del primer administrador.

```sh
git clone https://github.com/LeoGarc04/CELEX-ING-SW.git
cd CELEX-ING-SW
./mvnw clean package
```

En Windows usa `mvnw.cmd clean package`. Inicializa una base vacía con `database/celex.sql`, copia `application-local.example.properties` como `application-local.properties` y configura localmente MySQL y SMTP. Después ejecuta:

```sh
java -jar target/celex-0.0.1-SNAPSHOT.jar --spring.profiles.active=local --spring.config.additional-location=file:./application-local.properties
```

Abre `http://localhost:8080/login`. Las credenciales, cuentas, documentos y respaldos del servidor actual no se incluyen en GitHub. Para trasladar información existente se necesita un respaldo privado, no solo clonar el repositorio.

## Perfiles y permisos

- **Usuario:** registro, verificación, fila, preferencias de inscripción, pagos, perfil, calendario, mapa curricular, trámites y comprobantes propios.
- **Profesor:** grupos vinculados a su cuenta, alumnos confirmados y calificaciones. Escribir el nombre del profesor no sustituye la vinculación de su cuenta.
- **Administrador:** gestión de convocatorias, grupos, cuentas, becas, historial, pagos, trámites, bitácora, reportes y respaldos. Puede tener permisos de Configuración, Académicos, Financieros y Auditoría.

Las cuentas nuevas son Usuario. El primer administrador se designa mediante `scripts/crear_admin_local.py` sobre una cuenta ya verificada, únicamente cuando la base todavía no tiene administrador. Los siguientes perfiles se gestionan desde el panel. Al iniciar sesión, administrador y profesor entran directamente a sus paneles.

## Registro y perfil

IPN utiliza boleta de diez dígitos y correo `@alumno.ipn.mx`; el público general utiliza CURP y correo personal. La contraseña requiere al menos ocho caracteres, una mayúscula y un número. El nombre comienza por apellidos; las listas se ordenan alfabéticamente.

La verificación usa código de seis dígitos, caducidad y reenvío para registros interrumpidos. La sección de verificación se encuentra dentro del registro. Mi perfil permite editar nombre, teléfono y contraseña; correo e identificador se conservan. Las contraseñas se guardan con hash BCrypt.

## Convocatorias y fila

Las convocatorias nuevas incluyen grupos, apertura, cierre, capacidad, inicio de clases y cupo de examen. Se guardan como borrador y requieren habilitación explícita. El usuario no ve borradores ni convocatorias cerradas o vencidas, pero conserva acceso a sus solicitudes.

La sala de espera admite entradas antes de la apertura. La fila ordena ventanas de cinco segundos aplicando las prioridades: becarios por puntaje descendente, externos con promedio anterior mayor a nueve, nuevos y resto. Los empates respetan la llegada; las ventanas posteriores no desplazan a las anteriores ni a una atención iniciada. La clasificación IPN determina cuotas y no agrega una prioridad distinta a este orden.

La prioridad se muestra a quienes tienen categoría preferente. Los folios nuevos tienen ocho caracteres. El turno se asigna después de ordenar la ventana y permanece estable; la posición actual cambia al avanzar. La reserva se conserva tres minutos sin conexión. El latido es cada segundo en convocatorias nuevas y cada veinte segundos en las anteriores.

Si el folio ya tiene solicitud, regresar a la fila lleva a esa solicitud, incluso tras el cierre. No genera una segunda inscripción ni consume otro cupo.

## Inscripción y revisión

Sin historial del idioma se ofrece Introductorio en inglés, Básico 1 en otros idiomas o examen de colocación. Con historial se aplica el nivel autorizado, continuación o repetición según acreditación, inactividad y reprobación. Una calificación de al menos 80 acredita. No se promueve sin evidencia ni se salta un nivel faltante.

El alumno elige idioma, vía de ingreso, modalidad y horario preferido. La selección reserva un lugar provisional. Después adjunta su comprobante de pago y termina la captura. El estado puede consultarse siempre.

Administración revisa en un plazo de 48 horas y asigna grupo, horario y profesor antes de aprobar. En intensivo puede definir el horario final. No hay aprobación automática por vencimiento del plazo. Tras rechazo, la corrección dispone de dos días hábiles; al vencer se cancela y libera el cupo según las reglas del proceso. Los festivos se configuran en `CELEX_HOLIDAYS`.

Las nuevas solicitudes piden solo comprobante de pago, en PDF/JPG/PNG hasta 2 MB. Los expedientes anteriores conservan el flujo de grupo directo y sus dos documentos de pago e identificación. Las reservas y reasignaciones controlan cupos mediante transacciones; los grupos validan traslapes de profesor y aula.

## Cuotas y trámites

| Concepto | MXN |
|---|---:|
| Curso IPN activo | $620.00 |
| Curso público general y egresados | $1,280.00 |
| Examen de colocación con cupo limitado | $150.00 |
| Historial académico | $68.50 |
| Constancia | $34.50 |
| Validación | $0.00 |
| Diploma IPN | $138.00 |
| Diploma público general | $241.00 |

La beca se dicta manualmente por alumno y convocatoria, se refleja en el perfil y se notifica por correo. Reduce el curso a cero; examen y trámites conservan sus propias cuotas. La prioridad de becario y el dictamen financiero son datos relacionados pero distintos.

Trámites admite solicitud, comprobante y revisión financiera. Validación no exige pago por ser gratuita. Cada usuario consulta únicamente sus archivos. La inscripción confirmada permite descargar el PDF oficial con QR de validación.

## Profesores y portal

En nuevas convocatorias los grupos docentes se habilitan desde 48 horas antes del inicio de clases y las listas después del cierre. Solo muestran alumnos confirmados; las mismas restricciones se aplican al acceso directo.

El portal dispone de calendario, historial de pagos y mapa curricular por idioma. Inglés contiene Introductorio, Básico 1 a 5, Intermedio 1 a 5 y Avanzado 1 a 6. Los demás idiomas muestran Básico 1 y los niveles configurados; no se incluyen programas oficiales no proporcionados. El teléfono y correo de Dudas se configuran en administración.

## Modelo y archivos privados

El esquema mantiene 13 tablas. El cambio autorizado de identificación agrega `usuarios.boleta` única y nula y permite `usuarios.curp` nula. La instalación nueva ya lo incluye. En bases antiguas debe aplicarse una sola vez `database/migrations/20261117_boleta.sql`, con respaldo y servicio detenido. Hibernate valida el esquema y no lo modifica automáticamente.

Los documentos se guardan fuera de las carpetas públicas, mediante referencias en la base. `roles_permisos` conserva perfiles y vínculos docentes. La bitácora conserva calificaciones vinculadas a grupos, becas y configuración estructurada de convocatorias, preferencias y trámites. Estos registros son parte de la información operativa y deben incluirse en los respaldos. Solo se permite eliminar determinadas actividades de acceso, con registro de la eliminación.

Git excluye `application-local.properties`, `.env`, documentos, respaldos, logs, `.tools/`, `target/`, cachés y `.DS_Store`. La plantilla de configuración no contiene credenciales. Bootstrap conserva su licencia.

## Pruebas

```sh
./mvnw test
```

Las pruebas generales usan H2 y correo simulado. La revisión del 19 de noviembre pasó 62 pruebas generales y tres regresiones específicas en MySQL, además del recorrido móvil de reingreso y lista docente. La actualización anterior pasó 58 pruebas MySQL completas. Algunos casos se comparten entre ambas suites; no son escenarios independientes.

Para MySQL usa exclusivamente una base desechable `celex_test_suite` en 127.0.0.1 y configura `CELEX_TEST_MYSQL_URL`, `CELEX_TEST_MYSQL_USER` y `CELEX_TEST_MYSQL_PASSWORD`:

```sh
./mvnw -Dtest=MySqlWorkflowTest test
```

La suite inicializa y limpia tablas y prueba restauración. La base debe estar vacía antes de la inicialización; nunca apuntarla a datos reales.

## Publicación y respaldos

GitHub almacena el código; no ejecuta este servidor Java/MySQL. El asistente macOS `scripts/publicar_pruebas.py` utiliza `.tools/cloudflared` para un enlace HTTPS temporal. Requiere terminal abierta, equipo encendido e internet. El enlace cambia al reiniciar; los QR emitidos conservan la dirección anterior. Una dirección fija y disponibilidad independiente del equipo requieren alojamiento permanente.

Los respaldos incluyen base y documentos. Requieren `mysqldump`, una ruta configurada y el servidor encendido. El respaldo semanal se programa los domingos a las 03:00, hora de Ciudad de México; también puede ejecutarse desde administración. La plantilla de instalación los deja desactivados hasta configurar la herramienta. La restauración mediante `scripts/restaurar_respaldo.py` reemplaza datos y exige confirmación.

Quedan por validar en el alojamiento definitivo el rendimiento en internet, disponibilidad, recuperación operativa y aceptación con usuarios. El ensayo local de 200 usuarios no certifica latencia pública. El orden de confirmaciones se aplica dentro de una sola instancia del servidor.
