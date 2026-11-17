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
  private MockMvc mvc;

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
        encoder.encode("una-contraseña-segura"));
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

  @Test
  void registrationAndSingleUseCode() {
    accounts.register(
        new Registration(
            "Alumno",
            "GAML000101HDFRNS09",
            "5555555555",
            "alumno@ipn.mx",
            "una-contraseña-segura"));
    var u = db.one("SELECT * FROM usuarios");
    assertNotEquals("una-contraseña-segura", u.get("password_hash"));
    assertEquals("Inactiva", u.get("estado_cuenta"));
    String code = (String) db.one("SELECT * FROM verificaciones_codigo").get("codigo");
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
                    "alumno1@example.invalid", "una-contraseña-segura")));
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
    accounts.verify("alumno1@example.invalid", code, "otra-contraseña-segura");
    assertTrue(
        encoder.matches(
            "otra-contraseña-segura",
            (String) db.one("SELECT * FROM usuarios WHERE id_usuario=?", u).get("password_hash")));
    assertThrows(
        IllegalArgumentException.class,
        () -> accounts.verify("alumno1@example.invalid", code, "tercera-contraseña-segura"));
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
                    "GAML000101HDFRNS09",
                    "5555555555",
                    "alumno@ipn.mx",
                    "una-contraseña-segura")));
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
    String email = "pendiente@example.invalid", password = "una-contraseña-segura";
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
            "Cuenta pendiente",
            "GAML000101HDFRNS09",
            "5555555555",
            email,
            "una-contraseña-segura"));
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
}
