package mx.ipn.esime.celex;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.*;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import java.math.BigDecimal;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import mx.ipn.esime.celex.config.DatabaseAuthenticationProvider;
import mx.ipn.esime.celex.repository.Db;
import mx.ipn.esime.celex.service.*;
import mx.ipn.esime.celex.web.Registration;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.*;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.web.context.WebApplicationContext;

@SpringBootTest(
    properties =
        "spring.datasource.url=jdbc:h2:mem:workflow;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1;LOCK_TIMEOUT=10000")
@ActiveProfiles("test")
class WorkflowTest {
  @Autowired mx.ipn.esime.celex.service.InitialAdminService initialAdmins;
  @Autowired Db db;
  @Autowired AccountService accounts;
  @Autowired QueueService queue;
  @Autowired EnrollmentService enrollments;
  @Autowired PaymentService payments;
  @Autowired AdminService admin;
  @Autowired PasswordEncoder encoder;
  @Autowired DatabaseAuthenticationProvider authentication;
  @Autowired PlatformTransactionManager manager;
  @Autowired WebApplicationContext context;
  @MockitoBean MailService mail;
  protected MockMvc mvc;

  @BeforeEach
  void clean() {
    for (String t :
        List.of(
            "roles_permisos",
            "verificaciones_codigo",
            "comprobantes_oficiales",
            "documentos_comprobantes",
            "solicitudes_inscripcion",
            "fila_virtual",
            "historial_academico",
            "prioridades_usuario",
            "grupos",
            "idiomas",
            "convocatorias",
            "bitacora_acciones",
            "usuarios")) db.update("DELETE FROM " + t);
    mvc =
        org.springframework.test.web.servlet.setup.MockMvcBuilders.webAppContextSetup(context)
            .apply(springSecurity())
            .build();
    reset(mail);
  }

  private int student(int n) {
    return db.id(
        "INSERT INTO"
            + " usuarios(nombre_completo,curp,telefono,correo,password_hash,tipo_usuario,estado_cuenta,intentos_fallidos)"
            + " VALUES(?,?,?,?,?,'Público general','Activa',0)",
        "Alumno " + n,
        "GAML000101HDFRNS" + String.format("%02d", n),
        "5555555555",
        "alumno" + n + "@example.invalid",
        encoder.encode("ClaveSegura2026"));
  }

  int call() {
    return db.id(
        "INSERT INTO convocatorias(nombre,fecha_inicio_fila,fecha_fin_fila,capacidad_n_fila)"
            + " VALUES('Convocatoria',?,?,100)",
        LocalDateTime.now().minusDays(1),
        LocalDateTime.now().plusDays(1));
  }

  private int group(int c, int u, int capacity) {
    int l = db.id("INSERT INTO idiomas(nombre) VALUES('Inglés')");
    int g =
        db.id(
            "INSERT INTO"
                + " grupos(id_convocatoria,id_idioma,nivel,modalidad,profesor,aula,dias,horario,cupo_total,cupo_disponible)"
                + " VALUES(?,?,'Básico 1','Sabatino','Docente','101','6','09:00-11:00',?,?)",
            c,
            l,
            capacity,
            capacity);
    admin.history(u, u, l, "Básico 1", null, 0, false);
    return g;
  }

  @Autowired CallSettings callOptions;
  @Autowired CallManagement callManagement;
  @Autowired AcademicRoutes academicRoutes;
  @Autowired IntakeService courseIntake;
  @Autowired AdministrativeExtras extras;

  private int configuredCall(int actor) {
    int c = call();
    callOptions.configure(actor, c, LocalDateTime.now().plusDays(3), 2);
    return c;
  }

  private void mature(int call) {
    var base = LocalDateTime.now().minusSeconds(20);
    db.update("UPDATE convocatorias SET fecha_inicio_fila=? WHERE id_convocatoria=?", base, call);
    db.update(
        "UPDATE fila_virtual SET fecha_ingreso=? WHERE id_convocatoria=? AND estado_turno='En"
            + " espera'",
        base.plusSeconds(1),
        call);
  }

  @Test
  void returningToEnrolledQueueResumesOwnRequestEvenAfterCallCloses() throws Exception {
    int learner = student(1), c = call(), g = group(c, learner, 3);
    student(2);
    String folio = queue.join(learner, c);
    int request = enrollments.select(learner, folio, g);
    String queuePath = "/fila/" + folio;
    for (String state : List.of("Pendiente", "Confirmada", "Rechazada", "Cancelada")) {
      db.update("UPDATE solicitudes_inscripcion SET estado_inscripcion=? WHERE id_solicitud=?", state, request);
      for (String path : List.of(queuePath, queuePath + "/grupos"))
        mvc.perform(get(path).with(user("alumno1@example.invalid")))
            .andExpect(status().is3xxRedirection())
            .andExpect(redirectedUrl("/solicitud/" + request));
    }
    db.update("UPDATE convocatorias SET estado='Cerrada' WHERE id_convocatoria=?", c);
    mvc.perform(get(queuePath).with(user("alumno1@example.invalid")))
        .andExpect(redirectedUrl("/solicitud/" + request));
    mvc.perform(post(queuePath + "/latido").with(user("alumno1@example.invalid")).with(csrf()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.solicitud_url").value("/solicitud/" + request));
    mvc.perform(get(queuePath).with(user("alumno2@example.invalid")))
        .andExpect(status().isNotFound());
    mvc.perform(post(queuePath + "/latido").with(user("alumno2@example.invalid")).with(csrf()))
        .andExpect(status().isNotFound());
    assertEquals(1, db.rows("SELECT * FROM solicitudes_inscripcion WHERE folio=?", folio).size());
    assertEquals(2, Db.number(db.one("SELECT * FROM grupos WHERE id_grupo=?", g), "cupo_disponible"));
  }

  @Test
  void assignedTeacherSeesConfirmedStudentsInTheCurrentGroup() throws Exception {
    int actor = student(1), professor = student(2), c = call(), g = group(c, actor, 2);
    db.update("INSERT INTO roles_permisos(id_usuario,tipo_permiso) VALUES(?,'ADMIN'),(?,'PROFESOR')", actor, professor);
    int request = enrollments.select(actor, queue.join(actor, c), g);
    db.update("UPDATE solicitudes_inscripcion SET estado_inscripcion='Confirmada' WHERE id_solicitud=?", request);
    assertTrue(teachers.groups(professor).isEmpty());
    teachers.assign(actor, g, professor);
    mvc.perform(get("/profesor/grupos/" + g).with(user("alumno2@example.invalid")))
        .andExpect(status().isOk())
        .andExpect(content().string(org.hamcrest.Matchers.containsString("Alumno 1")));
  }

  @Test
  void copyingCallTemplatesPreservesTheAssignedTeacherAccount() {
    int actor = student(1),
        teacher = student(2),
        sourceCall = call(),
        source = group(sourceCall, actor, 4);
    db.update(
        "INSERT INTO roles_permisos(id_usuario,tipo_permiso) VALUES(?,'ADMIN'),(?,'PROFESOR')",
        actor,
        teacher);
    teachers.assign(actor, source, teacher);
    var start = LocalDateTime.now().plusDays(1);
    int created =
        callManagement.create(
            actor,
            "Nuevo periodo",
            start,
            start.plusDays(1),
            100,
            start.plusDays(3),
            2,
            List.of(source),
            Map.of());
    int copied =
        Db.number(db.one("SELECT * FROM grupos WHERE id_convocatoria=?", created), "id_grupo");
    assertEquals(
        1,
        db.rows(
                "SELECT * FROM roles_permisos WHERE id_usuario=? AND tipo_permiso=?",
                teacher,
                "DOCENTE_GRUPO_" + copied)
            .size());
    assertEquals(
        1,
        db.rows(
                "SELECT * FROM roles_permisos WHERE id_usuario=? AND tipo_permiso=?",
                teacher,
                "DOCENTE_GRUPO_" + source)
            .size());
  }

  @Test
  void simultaneousWaitingRoomEntriesHonorValidatedPriority() throws Exception {
    int rest = student(1),
        fresh = student(2),
        external = student(3),
        scholar = student(4),
        high = student(5),
        c = configuredCall(rest);
    db.update(
        "UPDATE convocatorias SET fecha_inicio_fila=? WHERE id_convocatoria=?",
        LocalDateTime.now().plusMinutes(2),
        c);
    db.update(
        "INSERT INTO"
            + " prioridades_usuario(id_usuario,es_alumno_nuevo,promedio_anterior,es_becario,puntaje_beca)"
            + " VALUES(?,FALSE,0,FALSE,0),(?,TRUE,0,FALSE,0),(?,FALSE,9.50,FALSE,0),(?,FALSE,0,TRUE,10),(?,FALSE,0,TRUE,100)",
        rest,
        fresh,
        external,
        scholar,
        high);
    var users = List.of(rest, fresh, external, scholar, high);
    var pool = Executors.newFixedThreadPool(5);
    var gate = new CountDownLatch(1);
    var futures = new ArrayList<Future<String>>();
    try {
      for (int id : users)
        futures.add(
            pool.submit(
                () -> {
                  gate.await();
                  return queue.join(id, c);
                }));
      gate.countDown();
      var folios = new ArrayList<String>();
      for (var future : futures) folios.add(future.get(10, TimeUnit.SECONDS));
      for (int n = 0; n < users.size(); n++)
        assertEquals("En espera", queue.heartbeat(users.get(n), folios.get(n)).get("estado_turno"));
      mature(c);
      var expected = List.of(5, 4, 3, 2, 1);
      for (int n = 0; n < users.size(); n++) {
        var status = queue.heartbeat(users.get(n), folios.get(n));
        assertEquals(expected.get(n), Db.number(status, "posicion_actual"));
        assertEquals(expected.get(n), Db.number(status, "numero_turno"));
      }
    } finally {
      pool.shutdownNow();
    }
  }

  @Test
  void teacherGroupsOpen48HoursBeforeClassesAndRosterAfterCallClose() throws Exception {
    int root = student(1),
        teacher = student(2),
        learner = student(3),
        c = configuredCall(root),
        g = group(c, root, 2);
    int language = Db.number(db.one("SELECT * FROM grupos WHERE id_grupo=?", g), "id_idioma");
    db.update(
        "INSERT INTO roles_permisos(id_usuario,tipo_permiso) VALUES(?,'ADMIN'),(?,'PROFESOR')",
        root,
        teacher);
    teachers.assign(root, g, teacher);
    admin.history(root, learner, language, "Básico 1", null, 0, false);
    assertTrue(teachers.groups(teacher).isEmpty());
    String folio = queue.join(learner, c);
    mature(c);
    queue.heartbeat(learner, folio);
    int request =
        courseIntake.request(learner, folio, language, "Continuación", "Sabatino", "09:00-11:00");
    var image = new java.awt.image.BufferedImage(5, 5, java.awt.image.BufferedImage.TYPE_INT_RGB);
    var bytes = new java.io.ByteArrayOutputStream();
    javax.imageio.ImageIO.write(image, "png", bytes);
    var file =
        new org.springframework.mock.web.MockMultipartFile(
            "pago", "pago.png", "image/png", bytes.toByteArray());
    payments.submit(
        learner,
        request,
        "PAGO",
        LocalDate.now(),
        (BigDecimal)
            db.one("SELECT * FROM solicitudes_inscripcion WHERE id_solicitud=?", request)
                .get("monto_calculado"),
        file,
        null);
    courseIntake.assign(root, request, g);
    payments.review(root, request, "Aprobado", "");
    callOptions.configure(root, c, LocalDateTime.now().plusHours(47), 2);
    assertEquals(1, teachers.groups(teacher).size());
    assertTrue(teachers.students(teacher, g).isEmpty());
    assertThrows(
        org.springframework.security.access.AccessDeniedException.class,
        () -> teachers.grade(teacher, g, learner, 90));
    db.update("UPDATE convocatorias SET estado='Cerrada' WHERE id_convocatoria=?", c);
    assertEquals(1, teachers.students(teacher, g).size());
    assertNotNull(teachers.grade(teacher, g, learner, 90));
  }

  @Test
  void fiveSecondWindowRanksBeforeServingAndPreservesAssignedNumbers() throws Exception {
    int normal = student(1), scholar = student(2), c = configuredCall(normal);
    db.update(
        "UPDATE convocatorias SET fecha_inicio_fila=? WHERE id_convocatoria=?",
        LocalDateTime.now().plusMinutes(1),
        c);
    String first = queue.join(normal, c);
    assertEquals("En espera", queue.heartbeat(normal, first).get("estado_turno"));
    assertEquals(0, Db.number(queue.heartbeat(normal, first), "numero_turno"));
    String second = queue.join(scholar, c);
    // Priority is validated after entering the room but before its opening.
    db.update(
        "INSERT INTO prioridades_usuario(id_usuario,es_becario,puntaje_beca) VALUES(?,TRUE,100)",
        scholar);
    mature(c);
    var high = queue.heartbeat(scholar, second);
    assertEquals("Atendiendo", high.get("estado_turno"));
    assertEquals(1, Db.number(high, "numero_turno"));
    assertEquals(2, Db.number(queue.heartbeat(normal, first), "posicion_actual"));
    assertEquals(2, Db.number(queue.heartbeat(normal, first), "numero_turno"));
    assertEquals(Boolean.TRUE, high.get("tiene_prioridad"));
    assertEquals(
        1,
        db.rows(
                "SELECT id_turno FROM fila_virtual WHERE id_convocatoria=? AND"
                    + " estado_turno='Atendiendo'",
                c)
            .size());
  }

  @Test
  void publishedCallsRequireGroupsAndClosedCallsAreHidden() throws Exception {
    int actor = student(1), old = call(), source = group(old, actor, 4);
    var opening = LocalDateTime.now().plusDays(1);
    var close = opening.plusDays(1);
    assertThrows(
        IllegalArgumentException.class,
        () ->
            callManagement.create(
                actor,
                "Sin grupos",
                opening,
                close,
                100,
                close.plusDays(2),
                5,
                List.of(),
                Map.of()));
    int current =
        callManagement.create(
            actor,
            "Nueva convocatoria",
            opening,
            close,
            100,
            close.plusDays(2),
            5,
            List.of(source),
            Map.of());
    assertEquals(
        "Borrador",
        db.one("SELECT * FROM convocatorias WHERE id_convocatoria=?", current).get("estado"));
    assertThrows(IllegalArgumentException.class, () -> queue.join(actor, current));
    assertEquals(1, db.rows("SELECT * FROM grupos WHERE id_convocatoria=?", current).size());
    callManagement.enable(actor, current);
    assertNotNull(queue.join(actor, current));
    db.update("UPDATE fila_virtual SET estado_turno='Expirado' WHERE id_usuario=?", actor);
    db.update("UPDATE convocatorias SET estado='Cerrada' WHERE id_convocatoria=?", current);
    mvc.perform(get("/panel").with(user("alumno1@example.invalid")))
        .andExpect(
            content()
                .string(
                    org.hamcrest.Matchers.not(
                        org.hamcrest.Matchers.containsString("<h3>Nueva convocatoria</h3>"))));
  }

  @Test
  void novicePreferencesPaymentAndFinalAssignmentAreSeparate() throws Exception {
    int actor = student(1), novice = student(2), c = configuredCall(actor), g = group(c, actor, 3);
    int language = Db.number(db.one("SELECT * FROM grupos WHERE id_grupo=?", g), "id_idioma");
    db.update("UPDATE grupos SET nivel='Introductorio' WHERE id_grupo=?", g);
    String folio = queue.join(novice, c);
    mature(c);
    queue.heartbeat(novice, folio);
    int id = courseIntake.request(novice, folio, language, "Inicial", "Sabatino", "09:00-11:00");
    assertEquals(
        id, courseIntake.request(novice, folio, language, "Inicial", "Sabatino", "09:00-11:00"));
    assertEquals(
        "Pendiente",
        db.one("SELECT * FROM solicitudes_inscripcion WHERE id_solicitud=?", id)
            .get("estado_inscripcion"));
    var image = new java.awt.image.BufferedImage(5, 5, java.awt.image.BufferedImage.TYPE_INT_RGB);
    var bytes = new java.io.ByteArrayOutputStream();
    javax.imageio.ImageIO.write(image, "png", bytes);
    var payment =
        new org.springframework.mock.web.MockMultipartFile(
            "pago", "pago.png", "image/png", bytes.toByteArray());
    var amount =
        (BigDecimal)
            db.one("SELECT * FROM solicitudes_inscripcion WHERE id_solicitud=?", id)
                .get("monto_calculado");
    payments.submit(novice, id, "PAGO-INTAKE", LocalDate.now(), amount, payment, null);
    assertThrows(IllegalArgumentException.class, () -> payments.review(actor, id, "Aprobado", ""));
    assertEquals(
        "",
        db.one("SELECT * FROM documentos_comprobantes WHERE id_solicitud=?", id)
            .get("url_identificacion"));
    courseIntake.assign(actor, id, g);
    payments.review(actor, id, "Aprobado", "");
    assertEquals(
        "Confirmada",
        db.one("SELECT * FROM solicitudes_inscripcion WHERE id_solicitud=?", id)
            .get("estado_inscripcion"));
    mvc.perform(get("/solicitud/" + id).with(user("alumno2@example.invalid")))
        .andExpect(view().name("solicitud-v2"));
    assertFalse(db.rows("SELECT * FROM comprobantes_oficiales WHERE id_solicitud=?", id).isEmpty());
  }

  @Test
  void academicRoutesUseEntryNextLevelAndPlacementWithLimitedCapacity() {
    int actor = student(1),
        novice = student(2),
        returning = student(3),
        c = configuredCall(actor),
        g = group(c, actor, 3);
    int language = Db.number(db.one("SELECT * FROM grupos WHERE id_grupo=?", g), "id_idioma");
    db.update("UPDATE grupos SET nivel='Introductorio' WHERE id_grupo=?", g);
    assertEquals("Introductorio", academicRoutes.route(novice, language).get("nivel"));
    int french = db.id("INSERT INTO idiomas(nombre,activo) VALUES('Francés',TRUE)");
    assertEquals("Básico 1", academicRoutes.route(novice, french).get("nivel"));
    admin.history(actor, returning, language, "Básico 1", 80, 0, false);
    assertEquals("Básico 2", academicRoutes.route(returning, language).get("nivel"));
    admin.history(actor, returning, language, "Básico 1", 79, 1, false);
    assertEquals(Boolean.FALSE, academicRoutes.route(returning, language).get("puedeCurso"));
    String folio = queue.join(novice, c);
    mature(c);
    queue.heartbeat(novice, folio);
    int id = courseIntake.request(novice, folio, language, "Colocación", "", "");
    assertEquals(
        new BigDecimal("150.00"),
        db.one("SELECT * FROM solicitudes_inscripcion WHERE id_solicitud=?", id)
            .get("monto_calculado"));
    assertEquals("Colocación", callOptions.preference(id).get("via"));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            courseIntake.request(
                returning, "NO-FOLIO", language, "Inicial", "Sabatino", "09:00-11:00"));
  }

  @Test
  void proceduresUseVerifiedFeesPrivateDocumentsAndProtectedAudit() throws Exception {
    int user = student(1), other = student(2);
    assertEquals(new BigDecimal("241.00"), extras.fees(user).get("Diploma"));
    db.update("UPDATE usuarios SET tipo_usuario='Comunidad IPN' WHERE id_usuario=?", user);
    assertEquals(new BigDecimal("138.00"), extras.fees(user).get("Diploma"));
    assertEquals(new BigDecimal("68.50"), extras.fees(user).get("Historial académico"));
    int id = extras.submit(user, "Validación", "Validar documento", LocalDate.now(), null);
    assertEquals(1, extras.requests(user).size());
    assertTrue(extras.requests(other).isEmpty());
    assertThrows(
        org.springframework.security.access.AccessDeniedException.class,
        () -> extras.request(id, other, false));
    extras.review(user, id, "Aprobado", "");
    assertEquals("Aprobado", extras.request(id, user, false).get("estado"));
    int access =
        db.id(
            "INSERT INTO bitacora_acciones(id_usuario,accion,detalles) VALUES(?,'Inicio de"
                + " sesión','Acceso correcto')",
            user);
    assertThrows(IllegalArgumentException.class, () -> extras.clearActivities(user, List.of(id)));
    extras.clearActivities(user, List.of(access));
    assertTrue(db.rows("SELECT * FROM bitacora_acciones WHERE id_bitacora=?", access).isEmpty());
    assertEquals(1, extras.requests(user).size());
  }

  @Test
  void allLanguagesHaveCurriculumAndScholarshipIsVisible() throws Exception {
    int actor = student(1), user = student(2), c = call();
    int language = db.id("INSERT INTO idiomas(nombre,activo) VALUES('Alemán',TRUE)");
    assertEquals("Alemán", studentPortal.curriculum(user, language).get("idioma"));
    assertEquals(1, studentPortal.curriculum(user, language).get("total"));
    scholarships.record(actor, user, c, "Beneficiario", "Beca validada");
    mvc.perform(get("/perfil").with(user("alumno2@example.invalid")))
        .andExpect(
            content().string(org.hamcrest.Matchers.containsString("Eres beneficiario de beca")));
    verify(mail).send(eq("alumno2@example.invalid"), eq("CELEX · Resultado de beca"), anyString());
  }

  @Test
  void adminPermissionsAreSeparatedAndRevokedOnNextRequest() throws Exception {
    int root = student(1), restricted = student(2);
    db.update(
        "INSERT INTO roles_permisos(id_usuario,tipo_permiso) VALUES(?,'ADMIN'),(?,'ADMIN')",
        root,
        restricted);
    admin.permissions(root, restricted, Set.of("ACADEMICO"));
    var auth =
        user("alumno2@example.invalid")
            .authorities(
                new org.springframework.security.core.authority.SimpleGrantedAuthority("ADMIN"));
    mvc.perform(post("/admin/idiomas").with(auth).with(csrf()).param("nombre", "Italiano"))
        .andExpect(status().is3xxRedirection());
    mvc.perform(
            post("/admin/convocatorias")
                .with(auth)
                .with(csrf())
                .param("nombre", "No autorizada")
                .param("inicio", LocalDateTime.now().plusDays(1).toString())
                .param("fin", LocalDateTime.now().plusDays(2).toString())
                .param("capacidad", "100"))
        .andExpect(status().isForbidden());
    mvc.perform(get("/admin/reportes.csv").with(auth)).andExpect(status().isForbidden());
    mvc.perform(get("/admin/solicitudes/999").with(auth)).andExpect(status().isForbidden());
    mvc.perform(
            post("/admin/permisos")
                .with(auth)
                .with(csrf())
                .param("usuario", String.valueOf(root))
                .param("permisos", "CONFIGURACION"))
        .andExpect(status().isForbidden());
    mvc.perform(get("/admin").with(auth))
        .andExpect(
            content()
                .string(
                    org.hamcrest.Matchers.not(
                        org.hamcrest.Matchers.containsString("id=\"convocatorias\""))));
    admin.permissions(root, restricted, Set.of("AUDITORIA"));
    mvc.perform(get("/admin/reportes.csv").with(auth)).andExpect(status().isOk());
    mvc.perform(post("/admin/idiomas").with(auth).with(csrf()).param("nombre", "No autorizado"))
        .andExpect(status().isForbidden());
    assertThrows(IllegalArgumentException.class, () -> admin.permissions(root, root, Set.of()));
    assertThrows(
        org.springframework.security.access.AccessDeniedException.class,
        () -> admin.permissions(restricted, root, Set.of("CONFIGURACION")));
  }

  @Test
  void closedQueueReportsNextOpening() {
    int user = student(1), c = call();
    db.update("UPDATE convocatorias SET estado='Cerrada' WHERE id_convocatoria=?", c);
    int next =
        db.id(
            "INSERT INTO convocatorias(nombre,fecha_inicio_fila,fecha_fin_fila,capacidad_n_fila)"
                + " VALUES('Siguiente',?,?,100)",
            LocalDateTime.now().plusDays(1),
            LocalDateTime.now().plusDays(2));
    assertTrue(
        assertThrows(IllegalArgumentException.class, () -> queue.join(user, c))
            .getMessage()
            .contains("Próxima apertura:"));
  }

  @Test
  void expiredTurnRetainsOriginalFolioAndAssignedTurnPerSrs() throws Exception {
    int a = student(1), c = call();
    String original = queue.join(a, c);
    int assigned = Db.number(queue.heartbeat(a, original), "numero_turno");
    db.update(
        "UPDATE fila_virtual SET reserva_expira_en=? WHERE folio=?",
        LocalDateTime.now().minusMinutes(1),
        original);
    assertEquals(original, queue.join(a, c));
    var turn = queue.heartbeat(a, original);
    assertEquals(assigned, Db.number(turn, "numero_turno"));
    assertEquals(original, turn.get("folio_visible"));
    assertEquals(8, original.length());
    assertEquals(1, db.rows("SELECT id_turno FROM fila_virtual WHERE id_usuario=?", a).size());
  }

  @Test
  void srsPriorityUsesScholarScoreExternalAverageNewcomerThenArrival() throws Exception {
    int rest = student(1),
        newcomer = student(2),
        external = student(3),
        lowScholar = student(4),
        highScholar = student(5),
        ipn = student(6),
        c = call();
    db.update(
        "UPDATE usuarios SET correo='ipn@alumno.ipn.mx',tipo_usuario='Comunidad IPN' WHERE"
            + " id_usuario=?",
        ipn);
    db.update(
        "INSERT INTO prioridades_usuario(id_usuario,es_alumno_nuevo) VALUES(?,TRUE)", newcomer);
    db.update(
        "INSERT INTO prioridades_usuario(id_usuario,promedio_anterior) VALUES(?,9.50)", external);
    db.update(
        "INSERT INTO prioridades_usuario(id_usuario,es_becario,puntaje_beca) VALUES(?,TRUE,10)",
        lowScholar);
    db.update(
        "INSERT INTO prioridades_usuario(id_usuario,es_becario,puntaje_beca) VALUES(?,TRUE,100)",
        highScholar);
    String r = queue.join(rest, c),
        n = queue.join(newcomer, c),
        e = queue.join(external, c),
        l = queue.join(lowScholar, c),
        h = queue.join(highScholar, c),
        i = queue.join(ipn, c);
    assertEquals(1, Db.number(queue.heartbeat(highScholar, h), "posicion_actual"));
    assertEquals(2, Db.number(queue.heartbeat(lowScholar, l), "posicion_actual"));
    assertEquals(3, Db.number(queue.heartbeat(external, e), "posicion_actual"));
    assertEquals(4, Db.number(queue.heartbeat(newcomer, n), "posicion_actual"));
    assertEquals(5, Db.number(queue.heartbeat(rest, r), "posicion_actual"));
    assertEquals(6, Db.number(queue.heartbeat(ipn, i), "posicion_actual"));
    mvc.perform(get("/fila/" + e).with(user("alumno3@example.invalid")))
        .andExpect(content().string(org.hamcrest.Matchers.containsString("Prioridad aplicada:")));
  }

  @Test
  void firstLiveTurnShowsPositionOneRegardlessOfDatabaseId() throws Exception {
    int a = student(1), b = student(2), c = call();
    String old = queue.join(a, c);
    db.update(
        "UPDATE fila_virtual SET reserva_expira_en=? WHERE folio=?",
        LocalDateTime.now().minusMinutes(1),
        old);
    String current = queue.join(b, c);
    var turn = queue.heartbeat(b, current);
    assertEquals(1, Db.number(turn, "posicion_actual"));
    assertEquals(current, queue.join(b, c));
    mvc.perform(get("/fila/" + current).with(user("alumno2@example.invalid")))
        .andExpect(content().string(org.hamcrest.Matchers.containsString("Turno asignado:")));
  }

  @Test
  void srsOffersHideUnauthorizedLevelsAndLanguages() throws Exception {
    int owner = student(1), newcomer = student(2), c = call(), g = group(c, owner, 2);
    assertTrue(enrollments.offers(newcomer, c).isEmpty());
    assertEquals(1, enrollments.offers(owner, c).size());
    db.update("INSERT INTO idiomas(nombre,activo) VALUES('Francés',TRUE)");
    String folio = queue.join(owner, c);
    queue.heartbeat(owner, folio);
    mvc.perform(get("/fila/" + folio + "/grupos").with(user("alumno1@example.invalid")))
        .andExpect(
            content()
                .string(
                    org.hamcrest.Matchers.not(
                        org.hamcrest.Matchers.containsString("<option value=\"Francés\""))));
    db.update("UPDATE historial_academico SET periodos_inactivo=1 WHERE id_usuario=?", owner);
    assertTrue(enrollments.offers(owner, c).isEmpty());
    db.update(
        "UPDATE historial_academico SET periodos_inactivo=0,requiere_examen_colocacion=TRUE WHERE"
            + " id_usuario=?",
        owner);
    assertTrue(enrollments.offers(owner, c).isEmpty());
  }

  @Test
  void groupsCanBeEditedAndDeletedWithoutLosingRequests() throws Exception {
    int actor = student(1), c = call(), g = group(c, actor, 3);
    mvc.perform(
            post("/admin/grupos/" + g + "/eliminar")
                .with(csrf())
                .with(user("alumno1@example.invalid")))
        .andExpect(status().isForbidden());
    admin.editGroup(actor, g, "Profesor corregido", "202", "1,6", "12:00-14:00", 4, true);
    var edited = db.one("SELECT * FROM grupos WHERE id_grupo=?", g);
    assertEquals("Profesor corregido", edited.get("profesor"));
    assertEquals(4, Db.number(edited, "cupo_disponible"));
    assertThrows(
        IllegalArgumentException.class,
        () -> admin.editGroup(actor, g, "Docente", "202", "7", "12:00-14:00", 4, true));
    int language = Db.number(edited, "id_idioma");
    assertThrows(
        IllegalArgumentException.class,
        () ->
            admin.group(
                actor,
                c,
                language,
                "Básico 1",
                "Sabatino",
                "Docente",
                "203",
                "1,7",
                "12:00-14:00",
                4));
    db.update(
        "INSERT INTO roles_permisos(id_usuario,tipo_permiso) VALUES(?,?)",
        actor,
        "DOCENTE_GRUPO_" + g);
    db.update("INSERT INTO roles_permisos(id_usuario,tipo_permiso) VALUES(?,'ADMIN')", actor);
    mvc.perform(
            post("/admin/grupos/" + g + "/eliminar")
                .with(csrf())
                .with(
                    user("alumno1@example.invalid")
                        .authorities(
                            new org.springframework.security.core.authority.SimpleGrantedAuthority(
                                "ADMIN"))))
        .andExpect(redirectedUrl("/admin#oferta"));
    assertTrue(db.rows("SELECT * FROM grupos WHERE id_grupo=?", g).isEmpty());
    assertTrue(
        db.rows("SELECT * FROM roles_permisos WHERE tipo_permiso=?", "DOCENTE_GRUPO_" + g)
            .isEmpty());
    int usedGroup = group(c, actor, 2);
    String folio = queue.join(actor, c);
    enrollments.select(actor, folio, usedGroup);
    assertThrows(IllegalArgumentException.class, () -> admin.deleteGroup(actor, usedGroup));
    assertFalse(
        db.rows("SELECT * FROM solicitudes_inscripcion WHERE id_grupo=?", usedGroup).isEmpty());
    admin.editGroup(actor, usedGroup, "Docente", "101", "6", "09:00-11:00", 2, false);
    assertEquals(
        "Inactivo", db.one("SELECT * FROM grupos WHERE id_grupo=?", usedGroup).get("estado"));
  }

  @Test
  void loginRoutesByGrantedProfile() throws Exception {
    int adminId = student(1);
    student(2);
    int teacherId = student(3);
    db.update("INSERT INTO roles_permisos(id_usuario,tipo_permiso) VALUES(?,'ADMIN')", adminId);
    db.update(
        "INSERT INTO roles_permisos(id_usuario,tipo_permiso) VALUES(?,'PROFESOR')", teacherId);
    for (int n = 1; n <= 3; n++) {
      mvc.perform(
              post("/login")
                  .with(csrf())
                  .param("username", "alumno" + n + "@example.invalid")
                  .param("password", "ClaveSegura2026"))
          .andExpect(redirectedUrl(n == 1 ? "/admin" : "/panel"));
    }
    mvc.perform(
            get("/panel")
                .with(
                    user("alumno1@example.invalid")
                        .authorities(
                            new org.springframework.security.core.authority.SimpleGrantedAuthority(
                                "ADMIN"))))
        .andExpect(redirectedUrl("/admin"));
    mvc.perform(
            get("/panel")
                .with(
                    user("alumno3@example.invalid")
                        .authorities(
                            new org.springframework.security.core.authority.SimpleGrantedAuthority(
                                "PROFESOR"))))
        .andExpect(redirectedUrl("/profesor"));
  }

  @Test
  void registrationAndSingleUseCode() {
    accounts.register(
        new Registration(
            "Alumno", "", "5555555555", "alumno@ipn.mx", "ClaveSegura2026", "IPN", "2026123456"));
    var u = db.one("SELECT * FROM usuarios");
    assertNotEquals("ClaveSegura2026", u.get("password_hash"));
    assertEquals("Inactiva", u.get("estado_cuenta"));
    String code = (String) db.one("SELECT * FROM verificaciones_codigo").get("codigo");
    assertTrue(code.matches("[0-9]{6}"));
    assertThrows(
        IllegalArgumentException.class, () -> accounts.verify("alumno@ipn.mx", "incorrecto", null));
    accounts.verify("alumno@ipn.mx", code, null);
    assertEquals("Comunidad IPN", db.one("SELECT * FROM usuarios").get("tipo_usuario"));
    assertThrows(
        IllegalArgumentException.class, () -> accounts.verify("alumno@ipn.mx", code, null));
    verify(mail).send(eq("alumno@ipn.mx"), anyString(), contains(code));
  }

  @Test
  void fiveFailedAttemptsBlockCorrectPassword() {
    int u = student(1);
    for (int i = 0; i < 5; i++)
      assertThrows(
          org.springframework.security.core.AuthenticationException.class,
          () ->
              authentication.authenticate(
                  UsernamePasswordAuthenticationToken.unauthenticated(
                      "alumno1@example.invalid", "wrong")));
    assertEquals(
        5, Db.number(db.one("SELECT * FROM usuarios WHERE id_usuario=?", u), "intentos_fallidos"));
    assertThrows(
        org.springframework.security.authentication.LockedException.class,
        () ->
            authentication.authenticate(
                UsernamePasswordAuthenticationToken.unauthenticated(
                    "alumno1@example.invalid", "ClaveSegura2026")));
  }

  @Test
  void duplicateJoinAndPriorities() {
    int a = student(1), b = student(2), c = call();
    db.update(
        "INSERT INTO"
            + " prioridades_usuario(id_usuario,es_becario,puntaje_beca,promedio_anterior,es_alumno_nuevo)"
            + " VALUES(?,TRUE,10,0,FALSE)",
        b);
    String first = queue.join(a, c), second = queue.join(b, c);
    assertEquals(first, queue.join(a, c));
    assertEquals(2, db.rows("SELECT * FROM fila_virtual").size());
    assertEquals(1, Db.number(queue.heartbeat(b, second), "posicion_actual"));
    assertEquals(2, Db.number(queue.heartbeat(a, first), "posicion_actual"));
  }

  @Test
  void simultaneousJoinDoesNotDuplicate() throws Exception {
    int u = student(1), c = call();
    var pool = Executors.newFixedThreadPool(2);
    try {
      var gate = new CountDownLatch(1);
      Callable<String> task =
          () -> {
            gate.await();
            return queue.join(u, c);
          };
      var a = pool.submit(task);
      var b = pool.submit(task);
      gate.countDown();
      assertEquals(a.get(10, TimeUnit.SECONDS), b.get(10, TimeUnit.SECONDS));
      assertEquals(1, db.rows("SELECT * FROM fila_virtual").size());
    } finally {
      pool.shutdownNow();
    }
  }

  @Test
  void concurrentLastSeat() throws Exception {
    int a = student(1), b = student(2), c = call(), g = group(c, a, 1);
    int l = Db.number(db.one("SELECT * FROM grupos WHERE id_grupo=?", g), "id_idioma");
    admin.history(a, b, l, "Básico 1", null, 0, false);
    String fa = queue.join(a, c), fb = queue.join(b, c);
    var pool = Executors.newFixedThreadPool(2);
    try {
      var gate = new CountDownLatch(1);
      Callable<Boolean> ta =
          () -> {
            gate.await();
            try {
              enrollments.select(a, fa, g);
              return true;
            } catch (IllegalArgumentException e) {
              return false;
            }
          };
      Callable<Boolean> tb =
          () -> {
            gate.await();
            try {
              enrollments.select(b, fb, g);
              return true;
            } catch (IllegalArgumentException e) {
              return false;
            }
          };
      var aa = pool.submit(ta);
      var bb = pool.submit(tb);
      gate.countDown();
      assertEquals(
          1, (aa.get(10, TimeUnit.SECONDS) ? 1 : 0) + (bb.get(10, TimeUnit.SECONDS) ? 1 : 0));
      assertEquals(
          0, Db.number(db.one("SELECT * FROM grupos WHERE id_grupo=?", g), "cupo_disponible"));
      assertEquals(1, db.rows("SELECT * FROM solicitudes_inscripcion").size());
    } finally {
      pool.shutdownNow();
    }
  }

  @Test
  void securePagesAndTemplates() throws Exception {
    int u = student(1);
    db.update("INSERT INTO roles_permisos(id_usuario,tipo_permiso) VALUES(?,'ADMIN')", u);
    mvc.perform(get("/registro"))
        .andExpect(status().isOk())
        .andExpect(content().string(org.hamcrest.Matchers.containsString("CURP")));
    mvc.perform(get("/admin")).andExpect(status().is3xxRedirection());
    mvc.perform(post("/fila").param("convocatoria", "1").with(user("alumno1@example.invalid")))
        .andExpect(status().isForbidden());
    mvc.perform(get("/admin").with(user("alumno1@example.invalid"))).andExpect(status().isOk());
    db.update("DELETE FROM roles_permisos");
    mvc.perform(
            get("/admin")
                .with(
                    user("alumno1@example.invalid")
                        .authorities(
                            new org.springframework.security.core.authority.SimpleGrantedAuthority(
                                "ADMIN"))))
        .andExpect(status().isForbidden());
    mvc.perform(get("/panel").with(user("alumno1@example.invalid"))).andExpect(status().isOk());
  }

  @Test
  void fullPaymentCorrectionAndReceipt() throws Exception {
    int u = student(1), c = call(), g = group(c, u, 1);
    String folio = queue.join(u, c);
    int id = enrollments.select(u, folio, g);
    payments.scholarship(u, id, "Beneficiario");
    assertEquals(
        0,
        ((BigDecimal)
                db.one("SELECT * FROM solicitudes_inscripcion WHERE id_solicitud=?", id)
                    .get("monto_calculado"))
            .signum());
    var image = new java.awt.image.BufferedImage(10, 10, java.awt.image.BufferedImage.TYPE_INT_RGB);
    var imageBytes = new java.io.ByteArrayOutputStream();
    javax.imageio.ImageIO.write(image, "png", imageBytes);
    var payment =
        new org.springframework.mock.web.MockMultipartFile(
            "pago", "pago.png", "image/png", imageBytes.toByteArray());
    var identity =
        new org.springframework.mock.web.MockMultipartFile(
            "identificacion", "id.png", "image/png", imageBytes.toByteArray());
    payments.submit(u, id, "BECA", LocalDate.now(), BigDecimal.ZERO, payment, identity);
    payments.review(u, id, "Correccion", "Adjunta identificación legible");
    var request = db.one("SELECT * FROM solicitudes_inscripcion WHERE id_solicitud=?", id);
    assertEquals("En proceso", request.get("estado_inscripcion"));
    assertNotNull(request.get("prorroga_expira_en"));
    payments.submit(u, id, "BECA", LocalDate.now(), BigDecimal.ZERO, payment, identity);
    payments.review(u, id, "Aprobado", "");
    assertEquals(
        "Confirmada",
        db.one("SELECT * FROM solicitudes_inscripcion WHERE id_solicitud=?", id)
            .get("estado_inscripcion"));
    var receipt = db.one("SELECT * FROM comprobantes_oficiales WHERE id_solicitud=?", id);
    mvc.perform(get("/perfil").with(user("alumno1@example.invalid")))
        .andExpect(
            content().string(org.hamcrest.Matchers.containsString("Descargar Comprobante Oficial")))
        .andExpect(content().string(org.hamcrest.Matchers.containsString(folio)));

    byte[] qr =
        mvc.perform(get("/solicitud/" + id + "/qr").with(user("alumno1@example.invalid")))
            .andExpect(status().isOk())
            .andExpect(content().contentType("image/png"))
            .andReturn()
            .getResponse()
            .getContentAsByteArray();
    var pixels = javax.imageio.ImageIO.read(new java.io.ByteArrayInputStream(qr));
    var bitmap =
        new com.google.zxing.BinaryBitmap(
            new com.google.zxing.common.HybridBinarizer(
                new com.google.zxing.client.j2se.BufferedImageLuminanceSource(pixels)));
    assertEquals(
        "http://localhost:8080/validar/" + receipt.get("codigo_qr"),
        new com.google.zxing.MultiFormatReader().decode(bitmap).getText());

    byte[] pdf =
        java.nio.file.Files.readAllBytes(
            java.nio.file.Path.of("target/test-storage", (String) receipt.get("url_pdf")));
    var reader = new com.lowagie.text.pdf.PdfReader(pdf);
    assertEquals(1, reader.getNumberOfPages());
    String text = new com.lowagie.text.pdf.parser.PdfTextExtractor(reader).getTextFromPage(1);
    assertTrue(text.contains(folio));
    assertTrue(text.contains("09:00-11:00"));
    reader.close();
    mvc.perform(get("/solicitud/" + id).with(user("alumno1@example.invalid")))
        .andExpect(status().isOk());
    mvc.perform(get("/validar/" + receipt.get("codigo_qr")))
        .andExpect(status().isOk())
        .andExpect(content().string(org.hamcrest.Matchers.containsString("Comprobante vigente")));
    int other = student(2);
    int doc =
        Db.number(
            db.one(
                "SELECT * FROM documentos_comprobantes WHERE id_solicitud=? ORDER BY id_documento"
                    + " DESC LIMIT 1",
                id),
            "id_documento");
    mvc.perform(get("/documentos/" + doc + "/pago").with(user("alumno2@example.invalid")))
        .andExpect(status().isForbidden());
    db.update("INSERT INTO roles_permisos(id_usuario,tipo_permiso) VALUES(?,'ADMIN')", u);
    mvc.perform(get("/admin/solicitudes/" + id).with(user("alumno1@example.invalid")))
        .andExpect(status().isOk());
  }

  @Test
  void expiredQueueAndCapacity() {
    int u = student(1), v = student(2), c = call();
    db.update("UPDATE convocatorias SET capacidad_n_fila=1 WHERE id_convocatoria=?", c);
    String folio = queue.join(u, c);
    assertThrows(IllegalArgumentException.class, () -> queue.join(v, c));
    db.update(
        "UPDATE fila_virtual SET reserva_expira_en=? WHERE folio=?",
        LocalDateTime.now().minusMinutes(4),
        folio);
    queue.join(v, c);
    assertEquals(
        "Expirado", db.one("SELECT * FROM fila_virtual WHERE folio=?", folio).get("estado_turno"));
  }

  @Test
  void rejectThenFreeSeatExactlyOnce() {
    int u = student(1), c = call(), g = group(c, u, 1);
    int id = enrollments.select(u, queue.join(u, c), g);
    enrollments.cancel(u, id);
    assertEquals(
        1, Db.number(db.one("SELECT * FROM grupos WHERE id_grupo=?", g), "cupo_disponible"));
    assertThrows(IllegalArgumentException.class, () -> enrollments.cancel(u, id));
    assertEquals(
        1, Db.number(db.one("SELECT * FROM grupos WHERE id_grupo=?", g), "cupo_disponible"));
  }

  @Test
  void resetCodeAndNoAccountEnumeration() {
    int u = student(1);
    accounts.requestReset("missing@example.invalid");
    verifyNoInteractions(mail);
    accounts.requestReset("alumno1@example.invalid");
    String code = (String) db.one("SELECT * FROM verificaciones_codigo").get("codigo");
    assertTrue(code.matches("[0-9]{6}"));
    accounts.verify("alumno1@example.invalid", code, "OtraClave2026");
    assertTrue(
        encoder.matches(
            "OtraClave2026",
            (String) db.one("SELECT * FROM usuarios WHERE id_usuario=?", u).get("password_hash")));
    assertThrows(
        IllegalArgumentException.class,
        () -> accounts.verify("alumno1@example.invalid", code, "TerceraClave2026"));
  }

  @Test
  void highestScholarScoreWinsAndTieUsesArrival() {
    int a = student(1), b = student(2), c = call();
    db.update(
        "INSERT INTO"
            + " prioridades_usuario(id_usuario,es_becario,puntaje_beca,promedio_anterior,es_alumno_nuevo)"
            + " VALUES(?,TRUE,5,0,FALSE)",
        a);
    db.update(
        "INSERT INTO"
            + " prioridades_usuario(id_usuario,es_becario,puntaje_beca,promedio_anterior,es_alumno_nuevo)"
            + " VALUES(?,TRUE,100,0,FALSE)",
        b);
    String fa = queue.join(a, c), fb = queue.join(b, c);
    assertEquals(1, Db.number(queue.heartbeat(b, fb), "posicion_actual"));
    assertEquals(2, Db.number(queue.heartbeat(a, fa), "posicion_actual"));
  }

  @Test
  void mailFailureRollsBackRegistration() {
    doThrow(new org.springframework.mail.MailSendException("No disponible"))
        .when(mail)
        .send(anyString(), anyString(), anyString());
    assertThrows(
        org.springframework.mail.MailSendException.class,
        () ->
            accounts.register(
                new Registration(
                    "Alumno",
                    "",
                    "5555555555",
                    "alumno@ipn.mx",
                    "ClaveSegura2026",
                    "IPN",
                    "2026123456")));
    assertTrue(db.rows("SELECT * FROM usuarios").isEmpty());
    assertTrue(db.rows("SELECT * FROM verificaciones_codigo").isEmpty());
  }

  @Test
  void academicConflictAndRoleIsolation() throws Exception {
    int u = student(1), c = call(), g = group(c, u, 2);
    int l = Db.number(db.one("SELECT * FROM grupos WHERE id_grupo=?", g), "id_idioma");
    assertThrows(
        IllegalArgumentException.class,
        () ->
            admin.group(u, c, l, "Básico 1", "Intensivo", "Docente", "102", "6", "10:00-12:00", 2));
    admin.group(u, c, l, "Básico 1", "Intensivo", "Docente", "101", "6", "11:00-13:00", 2);
    db.update("INSERT INTO roles_permisos(id_usuario,tipo_permiso) VALUES(?,'ACADEMICO')", u);
    mvc.perform(
            post("/admin/convocatorias")
                .with(user("alumno1@example.invalid"))
                .with(csrf())
                .param("nombre", "No autorizada")
                .param("inicio", "2026-11-01T09:00")
                .param("fin", "2026-12-01T09:00")
                .param("capacidad", "100"))
        .andExpect(status().isForbidden());
    admin.history(u, u, l, "Básico 1", 80, 0, false);
    assertTrue(
        Rules.yes(
            db.one("SELECT * FROM historial_academico ORDER BY id_historial DESC LIMIT 1")
                .get("acreditado")));
  }

  @Test
  void queueDisconnectExpiresWebSession() throws Exception {
    int u = student(1), c = call();
    String folio = queue.join(u, c);
    db.update(
        "UPDATE fila_virtual SET reserva_expira_en=? WHERE folio=?",
        LocalDateTime.now().minusMinutes(4),
        folio);
    var session = new org.springframework.mock.web.MockHttpSession();
    session.setAttribute("queue.folio", folio);
    mvc.perform(get("/panel").session(session).with(user("alumno1@example.invalid")))
        .andExpect(status().is3xxRedirection())
        .andExpect(redirectedUrl("/login?expired"));
    assertTrue(session.isInvalid());
  }

  @Test
  void initialAdministratorRequiresActiveAccountAndCannotBeRepeated() {
    int u = student(1);
    db.update("UPDATE usuarios SET estado_cuenta='Inactiva' WHERE id_usuario=?", u);
    assertThrows(
        IllegalArgumentException.class, () -> initialAdmins.appoint("alumno1@example.invalid"));
    assertTrue(db.rows("SELECT * FROM roles_permisos").isEmpty());
    db.update("UPDATE usuarios SET estado_cuenta='Activa' WHERE id_usuario=?", u);
    initialAdmins.appoint("alumno1@example.invalid");
    assertEquals(1, db.rows("SELECT * FROM roles_permisos WHERE tipo_permiso='ADMIN'").size());
    int other = student(2);
    assertThrows(
        IllegalArgumentException.class, () -> initialAdmins.appoint("alumno2@example.invalid"));
    assertEquals(1, db.rows("SELECT * FROM roles_permisos WHERE tipo_permiso='ADMIN'").size());
  }

  @Test
  void liveOffersRespectOwnershipAndTurn() throws Exception {
    int a = student(1), b = student(2), c = call(), g = group(c, a, 1);
    String fa = queue.join(a, c), fb = queue.join(b, c);
    mvc.perform(get("/fila/" + fb + "/oferta").with(user("alumno2@example.invalid")))
        .andExpect(status().isConflict())
        .andExpect(
            content()
                .string(
                    org.hamcrest.Matchers.not(
                        org.hamcrest.Matchers.containsString("cupo_disponible"))));
    mvc.perform(get("/fila/" + fa + "/oferta").with(user("alumno1@example.invalid")))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$[0].cupo_disponible").value(1));
    mvc.perform(get("/fila/" + fa + "/oferta").with(user("alumno2@example.invalid")))
        .andExpect(status().isSeeOther())
        .andExpect(redirectedUrl("/fila/" + fb))
        .andExpect(
            content()
                .string(
                    org.hamcrest.Matchers.not(
                        org.hamcrest.Matchers.containsString("cupo_disponible"))));
  }

  @Test
  void reportsAndBackupsRespectPermissions() throws Exception {
    int u = student(1);
    for (String legacy : List.of("CONFIGURACION", "ACADEMICO", "FINANCIERO", "AUDITORIA")) {
      db.update("INSERT INTO roles_permisos(id_usuario,tipo_permiso) VALUES(?,?)", u, legacy);
      mvc.perform(get("/admin/reportes.csv").with(user("alumno1@example.invalid")))
          .andExpect(status().isForbidden());
      mvc.perform(post("/admin/respaldos").with(csrf()).with(user("alumno1@example.invalid")))
          .andExpect(status().isForbidden());
      mvc.perform(get("/admin").with(user("alumno1@example.invalid")))
          .andExpect(status().isForbidden());
    }
    db.update("INSERT INTO roles_permisos(id_usuario,tipo_permiso) VALUES(?,'ADMIN')", u);
    mvc.perform(get("/admin/reportes.csv").with(user("alumno1@example.invalid")))
        .andExpect(status().isOk());
    mvc.perform(get("/admin").with(user("alumno1@example.invalid")))
        .andExpect(status().isOk())
        .andExpect(content().string(org.hamcrest.Matchers.containsString("Perfiles de cuenta")))
        .andExpect(
            content()
                .string(
                    org.hamcrest.Matchers.not(
                        org.hamcrest.Matchers.containsString("<option>FINANCIERO</option>"))));
  }

  @Test
  void englishCatalogAppliesToGroupsAndAssignments() {
    int u = student(1), c = call(), g = group(c, u, 1);
    int language = Db.number(db.one("SELECT * FROM grupos WHERE id_grupo=?", g), "id_idioma");
    assertEquals(17, EnglishLevels.ALL.size());
    assertEquals("Introductorio", EnglishLevels.ALL.get(0));
    assertEquals("Avanzado 6", EnglishLevels.ALL.get(16));
    admin.history(u, u, language, "Introductorio", null, 0, false);
    admin.history(u, u, language, "Avanzado 6", null, 0, false);
    assertThrows(
        IllegalArgumentException.class,
        () -> admin.history(u, u, language, "Avanzado 7", null, 0, false));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            admin.group(
                u,
                c,
                language,
                "Básico 6",
                "Sabatino",
                "Otro docente",
                "Otra aula",
                "6",
                "13:00-15:00",
                10));
    assertEquals(1, db.rows("SELECT * FROM grupos").size());
    assertEquals(
        "Avanzado 6",
        db.one("SELECT * FROM historial_academico ORDER BY id_historial DESC LIMIT 1")
            .get("nivel_actual"));
    int french = db.id("INSERT INTO idiomas(nombre) VALUES('Francés')");
    admin.history(u, u, french, "A1", null, 0, false);
    assertEquals(
        "A1",
        db.one("SELECT * FROM historial_academico ORDER BY id_historial DESC LIMIT 1")
            .get("nivel_actual"));
  }

  @Autowired ScholarshipService scholarships;

  @Test
  void preawardAndIndependentDocumentReview() throws Exception {
    int u = student(1), c = call(), g = group(c, u, 1);
    scholarships.record(u, u, c, "Beneficiario", "Validada antes de elegir");
    int id = enrollments.select(u, queue.join(u, c), g);
    var request = db.one("SELECT * FROM solicitudes_inscripcion WHERE id_solicitud=?", id);
    assertEquals("Beneficiario", request.get("estado_beca"));
    assertEquals(0, ((BigDecimal) request.get("monto_calculado")).signum());
    var image = new java.awt.image.BufferedImage(2, 2, java.awt.image.BufferedImage.TYPE_INT_RGB);
    var bytes = new java.io.ByteArrayOutputStream();
    javax.imageio.ImageIO.write(image, "png", bytes);
    var file =
        new org.springframework.mock.web.MockMultipartFile(
            "file", "image.png", "image/png", bytes.toByteArray());
    payments.submit(u, id, "BECA", LocalDate.now(), BigDecimal.ZERO, file, file);
    assertThrows(
        IllegalArgumentException.class,
        () -> scholarships.record(u, u, c, "No beneficiario", "Cambio tardío"));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            payments.reviewDocuments(
                u, id, new DocumentAssessment("Aprobado", "", "Rechazado", "")));
    payments.reviewDocuments(
        u,
        id,
        new DocumentAssessment(
            "Aprobado", "Pago correcto", "Rechazado", "Identificación ilegible"));
    var assessment =
        DocumentAssessment.read(
            db.one("SELECT * FROM documentos_comprobantes WHERE id_solicitud=?", id));
    assertEquals("Aprobado", assessment.payment());
    assertEquals("Rechazado", assessment.identity());
    assertEquals("Identificación ilegible", assessment.identityNote());
    assertEquals(
        "En proceso",
        db.one("SELECT * FROM solicitudes_inscripcion WHERE id_solicitud=?", id)
            .get("estado_inscripcion"));
    assertTrue(db.rows("SELECT * FROM comprobantes_oficiales").isEmpty());
    db.update("INSERT INTO roles_permisos(id_usuario,tipo_permiso) VALUES(?,'ADMIN')", u);
    mvc.perform(get("/admin").with(user("alumno1@example.invalid")))
        .andExpect(status().isOk())
        .andExpect(
            content().string(org.hamcrest.Matchers.containsString("Dictamen previo de beca")));
    mvc.perform(get("/admin/solicitudes/" + id).with(user("alumno1@example.invalid")))
        .andExpect(status().isOk())
        .andExpect(
            content().string(org.hamcrest.Matchers.containsString("Identificación ilegible")));
  }

  @Test
  void holidaysAndLegacyAssessment() {
    assertEquals(
        LocalDateTime.of(2026, 11, 18, 12, 0),
        new BusinessCalendar("2026-11-16")
            .correctionDeadline(LocalDateTime.of(2026, 11, 13, 12, 0)));
    var legacy =
        DocumentAssessment.read(
            Map.of(
                "estatus_pago",
                "Aprobado",
                "observaciones_dictamen",
                "Documento validado anteriormente"));
    assertTrue(legacy.approved());
    assertEquals("Documento validado anteriormente", legacy.identityNote());
  }

  @Autowired TeacherService teachers;

  @Test
  void teacherOnlyAssignedConfirmedStudentsAndGrades() throws Exception {
    int owner = student(1),
        professor = student(2),
        outsider = student(3),
        otherTeacher = student(4),
        c = call(),
        g = group(c, owner, 2);
    db.update("INSERT INTO roles_permisos(id_usuario,tipo_permiso) VALUES(?,'ADMIN')", owner);
    admin.profile(owner, professor, "PROFESOR");
    admin.profile(owner, otherTeacher, "PROFESOR");
    teachers.assign(owner, g, professor);
    int request = enrollments.select(owner, queue.join(owner, c), g);
    assertThrows(
        org.springframework.security.access.AccessDeniedException.class,
        () -> teachers.grade(professor, g, owner, 85));
    db.update(
        "UPDATE solicitudes_inscripcion SET estado_inscripcion='Confirmada' WHERE id_solicitud=?",
        request);
    assertEquals(1, teachers.groups(professor).size());
    assertEquals(0, teachers.groups(otherTeacher).size());
    assertThrows(
        org.springframework.security.access.AccessDeniedException.class,
        () -> teachers.grade(otherTeacher, g, owner, 85));
    assertThrows(
        org.springframework.security.access.AccessDeniedException.class,
        () -> teachers.grade(professor, g, outsider, 85));
    assertThrows(IllegalArgumentException.class, () -> teachers.grade(professor, g, owner, 101));
    teachers.grade(professor, g, owner, 79);
    assertEquals(79, teachers.students(professor, g).get(0).get("calificacion"));
    teachers.grade(professor, g, owner, 95);
    var h =
        db.one(
            "SELECT * FROM historial_academico WHERE registrado_por=? ORDER BY id_historial DESC"
                + " LIMIT 1",
            professor);
    assertTrue(Rules.yes(h.get("acreditado")));
    assertEquals(95, Db.number(h, "calificacion"));
    assertEquals(professor, Db.number(h, "registrado_por"));
    mvc.perform(get("/panel").with(user("alumno2@example.invalid")))
        .andExpect(redirectedUrl("/profesor"));
    mvc.perform(get("/profesor").with(user("alumno2@example.invalid")))
        .andExpect(status().isOk())
        .andExpect(content().string(org.hamcrest.Matchers.containsString("Mis grupos")));
    mvc.perform(get("/profesor/grupos/" + g).with(user("alumno2@example.invalid")))
        .andExpect(status().isOk())
        .andExpect(content().string(org.hamcrest.Matchers.containsString("Alumno 1")));
    mvc.perform(get("/profesor/grupos/" + g).with(user("alumno4@example.invalid")))
        .andExpect(status().isForbidden());
    mvc.perform(
            post("/profesor/grupos/" + g + "/calificaciones")
                .with(user("alumno2@example.invalid"))
                .with(csrf())
                .param("alumno", String.valueOf(outsider))
                .param("calificacion", "100"))
        .andExpect(status().isForbidden());
    mvc.perform(get("/admin").with(user("alumno2@example.invalid")))
        .andExpect(status().isForbidden());
    mvc.perform(
            post("/admin/perfiles")
                .with(user("alumno2@example.invalid"))
                .with(csrf())
                .param("usuario", String.valueOf(professor))
                .param("perfil", "ADMIN"))
        .andExpect(status().isForbidden());
    mvc.perform(
            post("/fila")
                .with(user("alumno2@example.invalid"))
                .with(csrf())
                .param("convocatoria", String.valueOf(c)))
        .andExpect(status().isForbidden());
    assertEquals(
        "Público general",
        db.one("SELECT tipo_usuario FROM usuarios WHERE id_usuario=?", professor)
            .get("tipo_usuario"));
    mvc.perform(
            post("/profesor/grupos/" + g + "/calificaciones")
                .with(user("alumno2@example.invalid"))
                .with(csrf())
                .param("alumno", String.valueOf(owner))
                .param("calificacion", "90"))
        .andExpect(redirectedUrl("/profesor/grupos/" + g));
    int language = Db.number(db.one("SELECT * FROM grupos WHERE id_grupo=?", g), "id_idioma");
    admin.group(
        owner, c, language, "Básico 1", "Sabatino", "Otro docente", "102", "6", "09:00-11:00", 2);
    int otherGroup =
        Db.number(db.one("SELECT * FROM grupos ORDER BY id_grupo DESC LIMIT 1"), "id_grupo");
    assertThrows(
        IllegalArgumentException.class, () -> teachers.assign(owner, otherGroup, professor));
    admin.editGroup(owner, g, "Sustituto", "101", "6", "09:00-11:00", 2, true);
    assertTrue(teachers.groups(professor).isEmpty());
    teachers.assign(owner, g, null);
    mvc.perform(get("/profesor/grupos/" + g).with(user("alumno2@example.invalid")))
        .andExpect(status().isForbidden());
  }

  @Test
  void profileChangeRevokesTeacherImmediatelyAndPreservesSelfAdmin() throws Exception {
    int owner = student(1), professor = student(2), c = call(), g = group(c, owner, 2);
    db.update("INSERT INTO roles_permisos(id_usuario,tipo_permiso) VALUES(?,'ADMIN')", owner);
    assertThrows(IllegalArgumentException.class, () -> admin.profile(owner, owner, "PROFESOR"));
    assertThrows(
        IllegalArgumentException.class, () -> admin.profile(owner, professor, "FINANCIERO"));
    assertThrows(
        org.springframework.security.access.AccessDeniedException.class,
        () -> admin.profile(professor, professor, "ADMIN"));
    admin.profile(owner, professor, "PROFESOR");
    teachers.assign(owner, g, professor);
    admin.profile(owner, professor, "PROFESOR");
    assertEquals(1, teachers.groups(professor).size());
    admin.profile(owner, professor, "USUARIO");
    assertTrue(db.rows("SELECT * FROM roles_permisos WHERE id_usuario=?", professor).isEmpty());
    mvc.perform(get("/profesor").with(user("alumno2@example.invalid")))
        .andExpect(status().isForbidden());
    mvc.perform(get("/panel").with(user("alumno2@example.invalid"))).andExpect(status().isOk());
    mvc.perform(get("/profesor").with(user("alumno1@example.invalid")))
        .andExpect(status().isForbidden());
  }

  @Test
  void waitingRoomLocksSectionsAcrossSessionsWithoutChangingTurn() throws Exception {
    int first = student(1), waiting = student(2), c = call(), other = call();
    queue.join(first, c);
    String folio = queue.join(waiting, c);
    String path = "/fila/" + folio;
    mvc.perform(get("/panel").with(user("alumno2@example.invalid")))
        .andExpect(status().isSeeOther())
        .andExpect(redirectedUrl(path));
    mvc.perform(get("/recuperar").with(user("alumno2@example.invalid")))
        .andExpect(status().isSeeOther())
        .andExpect(redirectedUrl(path));
    mvc.perform(get("/solicitud/123").with(user("alumno2@example.invalid")))
        .andExpect(status().isSeeOther())
        .andExpect(redirectedUrl(path));
    mvc.perform(
            post("/fila")
                .with(user("alumno2@example.invalid"))
                .with(csrf())
                .param("convocatoria", String.valueOf(other)))
        .andExpect(status().isSeeOther())
        .andExpect(redirectedUrl(path));
    mvc.perform(get(path + "/grupos").with(user("alumno2@example.invalid")))
        .andExpect(status().isSeeOther())
        .andExpect(redirectedUrl(path));
    mvc.perform(
            post(path + "/grupo")
                .with(user("alumno2@example.invalid"))
                .with(csrf())
                .param("grupo", "1"))
        .andExpect(status().isSeeOther())
        .andExpect(redirectedUrl(path));
    mvc.perform(get(path).with(user("alumno2@example.invalid")))
        .andExpect(status().isOk())
        .andExpect(
            content()
                .string(org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString("<aside"))))
        .andExpect(
            content()
                .string(
                    org.hamcrest.Matchers.not(
                        org.hamcrest.Matchers.containsString(">Mi perfil</a>"))));
    mvc.perform(post(path + "/latido").with(user("alumno2@example.invalid")).with(csrf()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.folio").value(folio))
        .andExpect(jsonPath("$.posicion_actual").value(2));
    mvc.perform(
            get("/panel")
                .session(new org.springframework.mock.web.MockHttpSession())
                .with(user("alumno2@example.invalid")))
        .andExpect(redirectedUrl(path));
    assertEquals(1, db.rows("SELECT * FROM fila_virtual WHERE id_usuario=?", waiting).size());
    mvc.perform(post("/logout").with(user("alumno2@example.invalid")).with(csrf()))
        .andExpect(redirectedUrl("/login?logout"));
  }

  @Test
  void turnSelectionRestoresNavigationAndClosedCallDoesNotTrapUser() throws Exception {
    int u = student(1), c = call(), g = group(c, u, 1);
    String folio = queue.join(u, c), path = "/fila/" + folio;
    mvc.perform(get(path).with(user("alumno1@example.invalid"))).andExpect(status().isOk());
    mvc.perform(get(path + "/grupos").with(user("alumno1@example.invalid")))
        .andExpect(status().isOk())
        .andExpect(
            content()
                .string(org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString("<aside"))));
    mvc.perform(
            post(path + "/grupo")
                .with(user("alumno1@example.invalid"))
                .with(csrf())
                .param("grupo", String.valueOf(g)))
        .andExpect(status().is3xxRedirection());
    mvc.perform(get("/panel").with(user("alumno1@example.invalid")))
        .andExpect(status().isOk())
        .andExpect(content().string(org.hamcrest.Matchers.containsString("<aside")));
    int second = call();
    queue.join(u, second);
    db.update("UPDATE convocatorias SET estado='Cerrada' WHERE id_convocatoria=?", second);
    mvc.perform(get("/panel").with(user("alumno1@example.invalid"))).andExpect(status().isOk());
  }

  @Test
  void administratorKeepsManagementAccessWhileOwnQueueIsFocused() throws Exception {
    int u = student(1), c = call();
    String folio = queue.join(u, c);
    db.update("INSERT INTO roles_permisos(id_usuario,tipo_permiso) VALUES(?,'ADMIN')", u);
    mvc.perform(get("/fila/" + folio).with(user("alumno1@example.invalid")))
        .andExpect(status().isOk())
        .andExpect(
            content()
                .string(org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString("<aside"))));
    mvc.perform(get("/admin").with(user("alumno1@example.invalid"))).andExpect(status().isOk());
  }

  @Test
  void resumeActivationAfterDisconnectAndResendWithoutDuplicateAccount() throws Exception {
    String email = "pendiente@example.invalid", password = "ClaveSegura2026";
    accounts.register(
        new Registration("Cuenta pendiente", "GAML000101HDFRNS09", "5555555555", email, password));
    int id = Db.number(db.one("SELECT * FROM usuarios WHERE correo=?", email), "id_usuario");
    var initial = db.one("SELECT * FROM verificaciones_codigo WHERE id_usuario=?", id);
    String oldCode = (String) initial.get("codigo");
    assertThrows(
        org.springframework.security.authentication.DisabledException.class,
        () ->
            authentication.authenticate(
                UsernamePasswordAuthenticationToken.unauthenticated(email, password)));
    mvc.perform(get("/login"))
        .andExpect(status().isOk())
        .andExpect(
            content()
                .string(
                    org.hamcrest.Matchers.containsString("Verificar cuenta o reenviar código")));
    mvc.perform(get("/verificar"))
        .andExpect(status().isOk())
        .andExpect(
            content()
                .string(org.hamcrest.Matchers.containsString("Reenviar código de verificación")));
    mvc.perform(
            post("/verificar").with(csrf()).param("correo", email).param("codigo", "INCORRECTO"))
        .andExpect(view().name("verificar"))
        .andExpect(model().attributeExists("error"))
        .andExpect(model().attribute("correo", email));
    db.update(
        "UPDATE verificaciones_codigo SET expira_en=? WHERE id_usuario=?",
        LocalDateTime.now().plusMinutes(10),
        id);
    reset(mail);
    mvc.perform(post("/reenviar").with(csrf()).param("correo", email))
        .andExpect(status().isOk())
        .andExpect(view().name("verificar"))
        .andExpect(model().attribute("correo", email));
    verify(mail, times(1)).send(eq(email), anyString(), anyString());
    var latest =
        db.one("SELECT * FROM verificaciones_codigo WHERE id_usuario=? AND usado=FALSE", id);
    String newCode = (String) latest.get("codigo");
    assertFalse(newCode.equals(oldCode));
    assertTrue(
        Rules.yes(
            db.one(
                    "SELECT * FROM verificaciones_codigo WHERE id_codigo=?",
                    initial.get("id_codigo"))
                .get("usado")));
    mvc.perform(post("/reenviar").with(csrf()).param("correo", email)).andExpect(status().isOk());
    verify(mail, times(1)).send(eq(email), anyString(), anyString());
    mvc.perform(post("/verificar").with(csrf()).param("correo", email).param("codigo", oldCode))
        .andExpect(view().name("verificar"))
        .andExpect(model().attributeExists("error"));
    mvc.perform(
            post("/verificar")
                .with(csrf())
                .param("correo", email)
                .param("codigo", newCode.toLowerCase(Locale.ROOT)))
        .andExpect(view().name("login"));
    assertEquals(
        "Activa", db.one("SELECT * FROM usuarios WHERE id_usuario=?", id).get("estado_cuenta"));
    assertEquals(1, db.rows("SELECT * FROM usuarios WHERE correo=?", email).size());
    mvc.perform(post("/login").with(csrf()).param("username", email).param("password", password))
        .andExpect(redirectedUrl("/panel"));
    mvc.perform(post("/verificar").with(csrf()).param("correo", email).param("codigo", newCode))
        .andExpect(view().name("verificar"))
        .andExpect(model().attributeExists("error"));
    reset(mail);
    var active =
        mvc.perform(post("/reenviar").with(csrf()).param("correo", email))
            .andReturn()
            .getModelAndView();
    var unknown =
        mvc.perform(post("/reenviar").with(csrf()).param("correo", "desconocido@example.invalid"))
            .andReturn()
            .getModelAndView();
    assertEquals(active.getModel().get("message"), unknown.getModel().get("message"));
    verifyNoInteractions(mail);
  }

  @Test
  void failedResendKeepsPendingAccountAndPreviousCode() throws Exception {
    String email = "reintento@example.invalid";
    accounts.register(
        new Registration(
            "Cuenta pendiente", "GAML000101HDFRNS09", "5555555555", email, "ClaveSegura2026"));
    int id = Db.number(db.one("SELECT * FROM usuarios WHERE correo=?", email), "id_usuario");
    db.update(
        "UPDATE verificaciones_codigo SET expira_en=? WHERE id_usuario=?",
        LocalDateTime.now().plusMinutes(10),
        id);
    var original = db.one("SELECT * FROM verificaciones_codigo WHERE id_usuario=?", id);
    reset(mail);
    doThrow(new org.springframework.mail.MailSendException("fallo simulado"))
        .when(mail)
        .send(anyString(), anyString(), anyString());
    mvc.perform(post("/reenviar").with(csrf()).param("correo", email))
        .andExpect(status().isOk())
        .andExpect(view().name("verificar"))
        .andExpect(model().attributeExists("error"))
        .andExpect(model().attribute("correo", email))
        .andExpect(
            content()
                .string(org.hamcrest.Matchers.containsString("Reenviar código de verificación")));
    assertEquals(
        "Inactiva", db.one("SELECT * FROM usuarios WHERE id_usuario=?", id).get("estado_cuenta"));
    assertEquals(1, db.rows("SELECT * FROM verificaciones_codigo WHERE id_usuario=?", id).size());
    assertFalse(
        Rules.yes(
            db.one(
                    "SELECT * FROM verificaciones_codigo WHERE id_codigo=?",
                    original.get("id_codigo"))
                .get("usado")));
    reset(mail);
    mvc.perform(post("/reenviar").with(csrf()).param("correo", email))
        .andExpect(view().name("verificar"))
        .andExpect(model().attributeDoesNotExist("error"));
    verify(mail).send(eq(email), anyString(), anyString());
  }

  @Test
  void passwordRulesAndIdentitySelectionAreRequired() throws Exception {
    for (String password : List.of("abcd1234", "Abcdefgh", "Abcd123", "12345678"))
      assertThrows(IllegalArgumentException.class, () -> AccountService.validatePassword(password));
    for (String password : List.of("Clave123", "ABCDEFG1", "Árbol123"))
      assertDoesNotThrow(() -> AccountService.validatePassword(password));
    assertThrows(
        IllegalArgumentException.class,
        () -> AccountService.validatePassword("Á1" + "á".repeat(40)));
    mvc.perform(
            post("/registro")
                .with(csrf())
                .param("nombre", "García López Ana")
                .param("tipoRegistro", "EXTERNO")
                .param("curp", "GAML000101HDFRNS09")
                .param("telefono", "5555555555")
                .param("correo", "ana@example.invalid")
                .param("password", "abcd1234"))
        .andExpect(view().name("registro"))
        .andExpect(model().hasErrors());
    assertTrue(db.rows("SELECT * FROM usuarios").isEmpty());
    assertThrows(
        IllegalArgumentException.class,
        () ->
            accounts.register(
                new Registration(
                    "López Pérez Ana",
                    "",
                    "5555555555",
                    "ana@example.invalid",
                    "Clave123",
                    "IPN",
                    "2026123456")));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            accounts.register(
                new Registration(
                    "López Pérez Ana",
                    "GAML000101HDFRNS09",
                    "5555555555",
                    "ana@alumno.ipn.mx",
                    "Clave123")));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            accounts.register(
                new Registration(
                    "López Pérez Ana",
                    "",
                    "5555555555",
                    "ana@alumno.ipn.mx",
                    "Clave123",
                    "IPN",
                    "1234")));
    assertTrue(db.rows("SELECT * FROM usuarios").isEmpty());
  }

  @Test
  void externalUsesOnlyCurpAndIpnUsesOnlyBoleta() throws Exception {
    accounts.register(
        new Registration(
            "Zavala Ruiz Ana",
            "GAML000101HDFRNS09",
            "5555555555",
            "externo@example.invalid",
            "Clave123"));
    accounts.register(
        new Registration(
            "Aguirre López Luis",
            "",
            "5555555555",
            "alumno@alumno.ipn.mx",
            "Clave123",
            "IPN",
            "2026123456"));
    var external = db.one("SELECT * FROM usuarios WHERE correo='externo@example.invalid'");
    var ipn = db.one("SELECT * FROM usuarios WHERE correo='alumno@alumno.ipn.mx'");
    assertNull(external.get("boleta"));
    assertEquals("GAML000101HDFRNS09", external.get("curp"));
    assertNull(ipn.get("curp"));
    assertEquals("2026123456", ipn.get("boleta"));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            accounts.register(
                new Registration(
                    "Otro Alumno",
                    "",
                    "5555555555",
                    "otro@alumno.ipn.mx",
                    "Clave123",
                    "IPN",
                    "2026123456")));
    accounts.register(
        new Registration(
            "Barrera Pérez Eva",
            "",
            "5555555555",
            "eva@alumno.ipn.mx",
            "Clave123",
            "IPN",
            "2026123457"));
    assertEquals(2, db.rows("SELECT * FROM usuarios WHERE curp IS NULL").size());
    String code =
        (String)
            db.one(
                    "SELECT * FROM verificaciones_codigo WHERE id_usuario=? AND usado=FALSE",
                    ipn.get("id_usuario"))
                .get("codigo");
    accounts.verify("alumno@alumno.ipn.mx", code, null);
    assertEquals(
        "Comunidad IPN",
        db.one("SELECT * FROM usuarios WHERE id_usuario=?", ipn.get("id_usuario"))
            .get("tipo_usuario"));
    db.update(
        "INSERT INTO roles_permisos(id_usuario,tipo_permiso) VALUES(?,'ADMIN')",
        ipn.get("id_usuario"));
    var page =
        mvc.perform(get("/admin").with(user("alumno@alumno.ipn.mx")))
            .andExpect(status().isOk())
            .andReturn()
            .getModelAndView();
    var list = (java.util.List<java.util.Map<String, Object>>) page.getModel().get("users");
    assertEquals("Aguirre López Luis", list.get(0).get("nombre_completo"));
    assertEquals("Zavala Ruiz Ana", list.get(2).get("nombre_completo"));
    mvc.perform(get("/admin/usuarios/" + ipn.get("id_usuario")).with(user("alumno@alumno.ipn.mx")))
        .andExpect(status().isOk())
        .andExpect(content().string(org.hamcrest.Matchers.containsString("Boleta: 2026123456")));
  }

  @Test
  void registrationVerifiesInsideRegistrationScreen() throws Exception {
    var session = new org.springframework.mock.web.MockHttpSession();
    mvc.perform(get("/registro").session(session))
        .andExpect(status().isOk())
        .andExpect(
            content()
                .string(
                    org.hamcrest.Matchers.not(
                        org.hamcrest.Matchers.containsString("class=\"flow\""))));
    mvc.perform(
            post("/registro")
                .session(session)
                .with(csrf())
                .param("nombre", "García López Ana")
                .param("tipoRegistro", "EXTERNO")
                .param("curp", "GAML000101HDFRNS09")
                .param("telefono", "5555555555")
                .param("correo", "ana@example.invalid")
                .param("password", "Clave123"))
        .andExpect(redirectedUrl("/registro"));
    mvc.perform(get("/registro").session(session))
        .andExpect(view().name("registro"))
        .andExpect(model().attribute("registered", true));
    mvc.perform(get("/verificar").session(session))
        .andExpect(view().name("registro"))
        .andExpect(model().attribute("correo", "ana@example.invalid"))
        .andExpect(
            content()
                .string(
                    org.hamcrest.Matchers.not(
                        org.hamcrest.Matchers.containsString("name=\"tipoRegistro\""))))
        .andExpect(
            content()
                .string(
                    org.hamcrest.Matchers.not(
                        org.hamcrest.Matchers.containsString("class=\"flow\""))));
    mvc.perform(
            post("/verificar")
                .session(session)
                .with(csrf())
                .param("origen", "registro")
                .param("correo", "ana@example.invalid")
                .param("codigo", "INCORRECTO"))
        .andExpect(view().name("registro"))
        .andExpect(model().attributeExists("error"));
    mvc.perform(
            post("/reenviar")
                .session(session)
                .with(csrf())
                .param("origen", "registro")
                .param("correo", "ana@example.invalid"))
        .andExpect(view().name("registro"));
    String code =
        (String) db.one("SELECT * FROM verificaciones_codigo WHERE usado=FALSE").get("codigo");
    mvc.perform(
            post("/verificar")
                .session(session)
                .with(csrf())
                .param("correo", "ana@example.invalid")
                .param("codigo", code))
        .andExpect(view().name("login"));
    assertNull(session.getAttribute("verification.email"));
    mvc.perform(
            post("/login")
                .with(csrf())
                .param("username", "ana@example.invalid")
                .param("password", "Clave123"))
        .andExpect(redirectedUrl("/panel"));
  }

  @Autowired ProfileService profiles;
  @Autowired StudentPortalService studentPortal;

  @Test
  void profileOnlyUpdatesOwnNameAndPhone() throws Exception {
    int userId = student(1), other = student(2);
    var old = db.one("SELECT * FROM usuarios WHERE id_usuario=?", userId);
    mvc.perform(get("/perfil").with(user("alumno1@example.invalid")))
        .andExpect(status().isOk())
        .andExpect(model().attribute("activeSection", "perfil"))
        .andExpect(
            content()
                .string(
                    org.hamcrest.Matchers.not(
                        org.hamcrest.Matchers.containsString("Acceso y recuperación"))))
        .andExpect(
            content()
                .string(
                    org.hamcrest.Matchers.not(
                        org.hamcrest.Matchers.containsString((String) old.get("password_hash")))));
    mvc.perform(
            post("/perfil")
                .with(user("alumno1@example.invalid"))
                .with(csrf())
                .param("nombre", "Zapata López Ana")
                .param("telefono", "5512345678")
                .param("id_usuario", String.valueOf(other))
                .param("correo", "cambio@example.invalid")
                .param("tipo_usuario", "Comunidad IPN")
                .param("boleta", "2026123456"))
        .andExpect(redirectedUrl("/perfil"));
    var updated = db.one("SELECT * FROM usuarios WHERE id_usuario=?", userId);
    assertEquals("Zapata López Ana", updated.get("nombre_completo"));
    assertEquals("5512345678", updated.get("telefono"));
    assertEquals(old.get("correo"), updated.get("correo"));
    assertEquals(old.get("curp"), updated.get("curp"));
    assertNull(updated.get("boleta"));
    assertEquals(old.get("tipo_usuario"), updated.get("tipo_usuario"));
    assertEquals(
        "Alumno 2",
        db.one("SELECT * FROM usuarios WHERE id_usuario=?", other).get("nombre_completo"));
    mvc.perform(
            post("/perfil")
                .with(user("alumno1@example.invalid"))
                .with(csrf())
                .param("nombre", "No guardar")
                .param("telefono", "letras"))
        .andExpect(view().name("perfil"))
        .andExpect(model().hasErrors());
    assertEquals(
        "Zapata López Ana",
        db.one("SELECT * FROM usuarios WHERE id_usuario=?", userId).get("nombre_completo"));
  }

  @Test
  void changingPasswordKeepsCurrentSessionAndRevokesOthers() throws Exception {
    int u = student(1);
    var first =
        mvc.perform(
                post("/login")
                    .with(csrf())
                    .param("username", "alumno1@example.invalid")
                    .param("password", "ClaveSegura2026"))
            .andExpect(redirectedUrl("/panel"))
            .andReturn();
    var second =
        mvc.perform(
                post("/login")
                    .with(csrf())
                    .param("username", "alumno1@example.invalid")
                    .param("password", "ClaveSegura2026"))
            .andExpect(redirectedUrl("/panel"))
            .andReturn();
    var session =
        (org.springframework.mock.web.MockHttpSession) first.getRequest().getSession(false);
    var other =
        (org.springframework.mock.web.MockHttpSession) second.getRequest().getSession(false);
    String previousId = session.getId();
    mvc.perform(
            post("/perfil/contrasena")
                .session(session)
                .with(csrf())
                .param("actual", "incorrecta")
                .param("nueva", "NuevaClave2026")
                .param("confirmar", "NuevaClave2026"))
        .andExpect(redirectedUrl("/perfil#contrasena"))
        .andExpect(flash().attributeExists("passwordErrors"));
    assertTrue(
        encoder.matches(
            "ClaveSegura2026",
            (String) db.one("SELECT * FROM usuarios WHERE id_usuario=?", u).get("password_hash")));
    mvc.perform(
            post("/perfil/contrasena")
                .session(session)
                .with(csrf())
                .param("actual", "ClaveSegura2026")
                .param("nueva", "NuevaClave2026")
                .param("confirmar", "NuevaClave2026"))
        .andExpect(redirectedUrl("/perfil#contrasena"))
        .andExpect(flash().attributeExists("passwordSuccess"));
    assertNotEquals(previousId, session.getId());
    assertTrue(
        encoder.matches(
            "NuevaClave2026",
            (String) db.one("SELECT * FROM usuarios WHERE id_usuario=?", u).get("password_hash")));
    mvc.perform(get("/perfil").session(session)).andExpect(status().isOk());
    mvc.perform(get("/perfil").session(other)).andExpect(redirectedUrl("/login"));
    assertTrue(other.isInvalid());
    verifyNoInteractions(mail);
  }

  @Test
  void profilePasswordWrongAttemptsAreLimited() {
    int u = student(1);
    for (int i = 0; i < 5; i++)
      assertThrows(
          org.springframework.security.authentication.BadCredentialsException.class,
          () -> profiles.password(u, "incorrecta", "NuevaClave2026", "NuevaClave2026"));
    assertEquals(
        5, Db.number(db.one("SELECT * FROM usuarios WHERE id_usuario=?", u), "intentos_fallidos"));
    assertThrows(
        org.springframework.security.authentication.LockedException.class,
        () -> profiles.password(u, "ClaveSegura2026", "NuevaClave2026", "NuevaClave2026"));
  }

  @Test
  void personalCalendarPaymentsAndCurriculumArePrivate() throws Exception {
    int a = student(1), b = student(2), c = call(), g = group(c, a, 3);
    int language = Db.number(db.one("SELECT * FROM grupos WHERE id_grupo=?", g), "id_idioma");
    admin.history(a, b, language, "Básico 1", null, 0, false);
    String fa = queue.join(a, c);
    int ra = enrollments.select(a, fa, g);
    String fb = queue.join(b, c);
    int rb = enrollments.select(b, fb, g);
    BigDecimal amount =
        (BigDecimal)
            db.one("SELECT * FROM solicitudes_inscripcion WHERE id_solicitud=?", ra)
                .get("monto_calculado");
    for (int n = 1; n <= 2; n++)
      db.update(
          "INSERT INTO"
              + " documentos_comprobantes(id_solicitud,numero_referencia,fecha_pago,importe,url_comprobante_pago,url_identificacion,estatus_pago,observaciones_dictamen)"
              + " VALUES(?,?,?,?,'pago.pdf','id.pdf','Aprobado',?)",
          ra,
          "PROPIO-" + n,
          LocalDate.now(),
          amount,
          new DocumentAssessment(
                  "Aprobado", "Pago correcto", "Rechazado", "Corrige la identificación")
              .encode());
    db.update(
        "INSERT INTO"
            + " documentos_comprobantes(id_solicitud,numero_referencia,fecha_pago,importe,url_comprobante_pago,url_identificacion,estatus_pago)"
            + " VALUES(?,'AJENO-NO-MOSTRAR',?,?,'pago.pdf','id.pdf','Aprobado')",
        rb,
        LocalDate.now(),
        amount);
    db.update(
        "UPDATE solicitudes_inscripcion SET estado_inscripcion='En proceso',prorroga_expira_en=?"
            + " WHERE id_solicitud=?",
        LocalDate.now().atTime(12, 0),
        ra);
    var payments = studentPortal.payments(a);
    assertEquals(1, payments.get("aprobados"));
    assertEquals(0, ((BigDecimal) payments.get("importeAprobado")).compareTo(amount));
    assertEquals(1, ((java.util.List<?>) payments.get("registros")).size());
    mvc.perform(
            get("/pagos").param("usuario", String.valueOf(b)).with(user("alumno1@example.invalid")))
        .andExpect(status().isOk())
        .andExpect(content().string(org.hamcrest.Matchers.containsString("PROPIO-2")))
        .andExpect(
            content()
                .string(
                    org.hamcrest.Matchers.not(
                        org.hamcrest.Matchers.containsString("AJENO-NO-MOSTRAR"))));
    String month = java.time.YearMonth.now().toString();
    var calendar = studentPortal.calendar(a, month);
    var agenda = (java.util.List<StudentPortalService.Event>) calendar.get("agenda");
    assertTrue(agenda.stream().anyMatch(e -> e.titulo().contains(fa)));
    assertFalse(agenda.stream().anyMatch(e -> e.titulo().contains(fb)));
    assertEquals(42, ((java.util.List<?>) calendar.get("dias")).size());
    mvc.perform(get("/calendario").param("mes", month).with(user("alumno1@example.invalid")))
        .andExpect(status().isOk());
    db.update(
        "UPDATE solicitudes_inscripcion SET estado_inscripcion='Confirmada' WHERE id_solicitud=?",
        ra);
    assertEquals(1, studentPortal.schedule(a).size());
    assertTrue(studentPortal.schedule(b).isEmpty());
    admin.history(a, a, language, "Básico 1", 85, 0, false);
    admin.history(a, a, language, "Intermedio 2", null, 0, false);
    var map = studentPortal.curriculum(a);
    assertEquals(17, map.get("total"));
    assertEquals(1, map.get("acreditados"));
    assertEquals("Intermedio 2", map.get("nivelActual"));
    assertEquals(0, studentPortal.curriculum(b).get("acreditados"));
    mvc.perform(get("/mapa-curricular").with(user("alumno1@example.invalid")))
        .andExpect(status().isOk())
        .andExpect(content().string(org.hamcrest.Matchers.containsString("Avanzado 6")));
    assertThrows(IllegalArgumentException.class, () -> studentPortal.calendar(a, "malformado"));
  }

  @Test
  void queueShowsSrsPriorityAndIncludesGroupFilters() throws Exception {
    int u = student(1), c = call(), g = group(c, u, 2);
    db.update(
        "INSERT INTO prioridades_usuario(id_usuario,es_becario,puntaje_beca) VALUES(?,TRUE,100)",
        u);
    String folio = queue.join(u, c);
    mvc.perform(get("/fila/" + folio).with(user("alumno1@example.invalid")))
        .andExpect(status().isOk())
        .andExpect(
            content()
                .string(
                    org.hamcrest.Matchers.not(
                        org.hamcrest.Matchers.containsString("priority-ladder"))))
        .andExpect(content().string(org.hamcrest.Matchers.containsString("Prioridad aplicada")));
    assertEquals(
        4,
        Db.number(
            db.one("SELECT * FROM fila_virtual WHERE folio=?", folio),
            "nivel_prioridad_calculado"));
    mvc.perform(get("/fila/" + folio + "/grupos").with(user("alumno1@example.invalid")))
        .andExpect(status().isOk())
        .andExpect(
            content().string(org.hamcrest.Matchers.containsString("data-offer-filter=\"dia\"")))
        .andExpect(
            content().string(org.hamcrest.Matchers.containsString("data-offer-filter=\"horario\"")))
        .andExpect(
            content()
                .string(
                    org.hamcrest.Matchers.containsString("data-offer-filter=\"disponibilidad\"")));
    for (String section : List.of("/perfil", "/calendario", "/mapa-curricular", "/pagos"))
      mvc.perform(get(section).with(user("alumno1@example.invalid")))
          .andExpect(redirectedUrl("/fila/" + folio));
  }

  @Test
  void professorProfileNameKeepsGroupAssignment() {
    int owner = student(1), teacher = student(2), call = call(), group = group(call, owner, 2);
    db.update("INSERT INTO roles_permisos(id_usuario,tipo_permiso) VALUES(?,'ADMIN')", owner);
    admin.profile(owner, teacher, "PROFESOR");
    teachers.assign(owner, group, teacher);
    profiles.update(teacher, "Pérez López Laura", "5512345678");
    assertEquals(
        "Pérez López Laura",
        db.one("SELECT * FROM grupos WHERE id_grupo=?", group).get("profesor"));
    assertEquals(1, teachers.groups(teacher).size());
    assertEquals("alumno2@example.invalid", profiles.get(teacher).get("correo"));
  }
}
