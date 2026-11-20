package mx.ipn.esime.celex;

import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

@SpringBootTest(
    properties = {
      "spring.datasource.url=${CELEX_TEST_MYSQL_URL}",
      "spring.datasource.username=${CELEX_TEST_MYSQL_USER}",
      "spring.datasource.password=${CELEX_TEST_MYSQL_PASSWORD}"
    })
@ActiveProfiles(value = "mysql-test", inheritProfiles = false)
@EnabledIfEnvironmentVariable(
    named = "CELEX_TEST_MYSQL_URL",
    matches = "jdbc:mysql://127\\.0\\.0\\.1:[0-9]+/celex_test_[a-z_]+.*")
class MySqlWorkflowTest extends WorkflowTest {
  @org.junit.jupiter.api.Test
  void managedWaitingRoom200() throws Exception {
    int c = call();
    db.update(
        "UPDATE convocatorias SET capacidad_n_fila=200,fecha_inicio_fila=? WHERE id_convocatoria=?",
        java.time.LocalDateTime.now().plusMinutes(5),
        c);
    String hash = encoder.encode("ClaveSegura2026");
    var users = new java.util.ArrayList<Integer>();
    for (int n = 0; n < 200; n++)
      users.add(
          db.id(
              "INSERT INTO"
                  + " usuarios(nombre_completo,curp,telefono,correo,password_hash,tipo_usuario,estado_cuenta,intentos_fallidos)"
                  + " VALUES(?,?,?,?,?,'Público general','Activa',0)",
              "Sala " + n,
              String.format("ROOM%014d", n),
              "5555555555",
              "sala" + n + "@example.invalid",
              hash));
    callOptions.configure(users.get(0), c, java.time.LocalDateTime.now().plusDays(3), 25);
    var pool = java.util.concurrent.Executors.newFixedThreadPool(200);
    try {
      var gate = new java.util.concurrent.CountDownLatch(1);
      var pending = new java.util.ArrayList<java.util.concurrent.Future<Long>>();
      for (int id : users)
        pending.add(
            pool.submit(
                () -> {
                  gate.await();
                  long began = System.nanoTime();
                  queue.join(id, c);
                  return (System.nanoTime() - began) / 1000000;
                }));
      gate.countDown();
      var values = new java.util.ArrayList<Long>();
      for (var future : pending) values.add(future.get(90, java.util.concurrent.TimeUnit.SECONDS));
      java.util.Collections.sort(values);
      long joinP95 = values.get(189);
      org.junit.jupiter.api.Assertions.assertEquals(
          0,
          db.rows(
                  "SELECT id_turno FROM fila_virtual WHERE id_convocatoria=? AND"
                      + " estado_turno='Atendiendo'",
                  c)
              .size());
      var opening = java.time.LocalDateTime.now().minusSeconds(20);
      db.update("UPDATE convocatorias SET fecha_inicio_fila=? WHERE id_convocatoria=?", opening, c);
      db.update(
          "UPDATE fila_virtual SET fecha_ingreso=? WHERE id_convocatoria=?",
          opening.plusSeconds(1),
          c);
      pending.clear();
      var release = new java.util.concurrent.CountDownLatch(1);
      for (int id : users) {
        String folio =
            (String) db.one("SELECT folio FROM fila_virtual WHERE id_usuario=?", id).get("folio");
        pending.add(
            pool.submit(
                () -> {
                  release.await();
                  long began = System.nanoTime();
                  queue.heartbeat(id, folio);
                  return (System.nanoTime() - began) / 1000000;
                }));
      }
      release.countDown();
      values.clear();
      for (var future : pending) values.add(future.get(90, java.util.concurrent.TimeUnit.SECONDS));
      java.util.Collections.sort(values);
      long releaseP95 = values.get(189);
      java.nio.file.Files.writeString(
          java.nio.file.Path.of("target/mysql-waiting-room-load.json"),
          "{\"users\":200,\"join_p95_ms\":" + joinP95 + ",\"release_p95_ms\":" + releaseP95 + "}");
      org.junit.jupiter.api.Assertions.assertTrue(joinP95 < 3000, "P95 sala: " + joinP95);
      org.junit.jupiter.api.Assertions.assertTrue(
          releaseP95 < 3000, "P95 liberación: " + releaseP95);
      org.junit.jupiter.api.Assertions.assertEquals(
          200,
          db.rows(
                  "SELECT id_bitacora FROM bitacora_acciones WHERE accion LIKE ?",
                  "Número turno · " + c + " · %")
              .size());
      org.junit.jupiter.api.Assertions.assertEquals(
          1,
          db.rows(
                  "SELECT id_turno FROM fila_virtual WHERE id_convocatoria=? AND"
                      + " estado_turno='Atendiendo'",
                  c)
              .size());
    } finally {
      pool.shutdownNow();
    }
  }

  @org.junit.jupiter.api.Test
  void load200() throws Exception {
    int c = call();
    db.update("UPDATE convocatorias SET capacidad_n_fila=200 WHERE id_convocatoria=?", c);
    String hash = encoder.encode("ClaveSegura2026");
    var users = new java.util.ArrayList<Integer>();
    for (int i = 0; i < 200; i++)
      users.add(
          db.id(
              "INSERT INTO"
                  + " usuarios(nombre_completo,curp,telefono,correo,password_hash,tipo_usuario,estado_cuenta,intentos_fallidos)"
                  + " VALUES(?,?,?,?,?,'Público general','Activa',0)",
              "Carga " + i,
              String.format("LOAD%014d", i),
              "5555555555",
              "carga" + i + "@example.invalid",
              hash));
    var pool = java.util.concurrent.Executors.newFixedThreadPool(200);
    var samples = new java.util.ArrayList<Long>();
    try {
      var gate = new java.util.concurrent.CountDownLatch(1);
      var futures = new java.util.ArrayList<java.util.concurrent.Future<Long>>();
      for (int id : users)
        futures.add(
            pool.submit(
                () -> {
                  gate.await();
                  long start = System.nanoTime();
                  queue.join(id, c);
                  return (System.nanoTime() - start) / 1000000;
                }));
      gate.countDown();
      for (var future : futures) samples.add(future.get(90, java.util.concurrent.TimeUnit.SECONDS));
      java.util.Collections.sort(samples);
      long p95 = samples.get(189);
      org.junit.jupiter.api.Assertions.assertEquals(
          200, db.rows("SELECT * FROM fila_virtual WHERE id_convocatoria=?", c).size());
      java.nio.file.Files.writeString(
          java.nio.file.Path.of("target/mysql-load.json"),
          "{\"users\":200,\"concurrent_workers\":200,\"join_p95_ms\":"
              + p95
              + ",\"max_ms\":"
              + samples.get(199)
              + "}");
      org.junit.jupiter.api.Assertions.assertTrue(
          p95 < 3000, "P95 de ingreso a fila: " + p95 + " ms");
      var httpSamples = new java.util.ArrayList<Long>();
      var webGate = new java.util.concurrent.CountDownLatch(1);
      var webFutures = new java.util.ArrayList<java.util.concurrent.Future<Long>>();
      for (int n = 0; n < users.size(); n++) {
        String email = "carga" + n + "@example.invalid";
        String folio =
            (String)
                db.one("SELECT folio FROM fila_virtual WHERE id_usuario=?", users.get(n))
                    .get("folio");
        webFutures.add(
            pool.submit(
                () -> {
                  webGate.await();
                  long started = System.nanoTime();
                  mvc.perform(
                          org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post(
                                  "/fila/" + folio + "/latido")
                              .with(
                                  org.springframework.security.test.web.servlet.request
                                      .SecurityMockMvcRequestPostProcessors.csrf())
                              .with(
                                  org.springframework.security.test.web.servlet.request
                                      .SecurityMockMvcRequestPostProcessors.user(email)))
                      .andExpect(
                          org.springframework.test.web.servlet.result.MockMvcResultMatchers.status()
                              .isOk());
                  return (System.nanoTime() - started) / 1000000;
                }));
      }
      webGate.countDown();
      for (var future : webFutures)
        httpSamples.add(future.get(90, java.util.concurrent.TimeUnit.SECONDS));
      java.util.Collections.sort(httpSamples);
      long webP95 = httpSamples.get(189);
      java.nio.file.Files.writeString(
          java.nio.file.Path.of("target/mysql-http-load.json"),
          "{\"users\":200,\"concurrent_workers\":200,\"heartbeat_p95_ms\":"
              + webP95
              + ",\"max_ms\":"
              + httpSamples.get(199)
              + ",\"environment\":\"MockMvc with security and local MySQL; excludes internet"
              + " transport\"}");
      org.junit.jupiter.api.Assertions.assertTrue(
          webP95 < 3000, "P95 web local de fila: " + webP95 + " ms");

    } finally {
      pool.shutdownNow();
    }
  }

  @org.junit.jupiter.api.Test
  void backupAndRestoreDisposableDatabase(@org.junit.jupiter.api.io.TempDir java.nio.file.Path temp)
      throws Exception {
    org.junit.jupiter.api.Assertions.assertEquals(
        "celex_test_suite",
        db.one("SELECT DATABASE() AS current_database").get("current_database"));
    var uri = java.net.URI.create(System.getenv("CELEX_TEST_MYSQL_URL").substring(5));
    var config = temp.resolve("client.cnf");
    java.nio.file.Files.writeString(
        config,
        "[client]\nhost=127.0.0.1\nprotocol=TCP\nport="
            + uri.getPort()
            + "\nuser="
            + System.getenv("CELEX_TEST_MYSQL_USER")
            + "\npassword="
            + System.getenv("CELEX_TEST_MYSQL_PASSWORD")
            + "\n");
    java.nio.file.Files.setPosixFilePermissions(
        config, java.nio.file.attribute.PosixFilePermissions.fromString("rw-------"));
    var wrapper = temp.resolve("dump");
    java.nio.file.Files.writeString(
        wrapper,
        "#!/bin/sh\nshift\nexec /usr/local/mysql/bin/mysqldump --defaults-extra-file='"
            + config.toString().replace("'", "'\\''")
            + "' \"$@\"\n");
    org.junit.jupiter.api.Assertions.assertTrue(wrapper.toFile().setExecutable(true, true));
    var storage = temp.resolve("storage");
    java.nio.file.Files.createDirectories(storage);
    java.nio.file.Files.writeString(storage.resolve("fixture.txt"), "comprobante de prueba");
    int id = db.id("INSERT INTO idiomas(nombre) VALUES('RESPALDO PRUEBA')");
    var backup =
        new mx.ipn.esime.celex.service.BackupService(
            wrapper.toString(),
            "fixture",
            "celex_test_suite",
            storage.toString(),
            temp.resolve("backups").toString());
    var zipPath = backup.snapshot();
    var sql = temp.resolve("restore.sql");
    try (var zip = new java.util.zip.ZipFile(zipPath.toFile())) {
      org.junit.jupiter.api.Assertions.assertNotNull(zip.getEntry("documents/fixture.txt"));
      try (var input = zip.getInputStream(zip.getEntry("database.sql"))) {
        java.nio.file.Files.copy(input, sql);
      }
    }
    db.update("DELETE FROM idiomas WHERE id_idioma=?", id);
    var restore =
        new ProcessBuilder(
                "/usr/local/mysql/bin/mysql", "--defaults-extra-file=" + config, "celex_test_suite")
            .redirectInput(sql.toFile())
            .redirectOutput(temp.resolve("restore.log").toFile())
            .redirectErrorStream(true)
            .start();
    org.junit.jupiter.api.Assertions.assertTrue(
        restore.waitFor(30, java.util.concurrent.TimeUnit.SECONDS));
    org.junit.jupiter.api.Assertions.assertEquals(0, restore.exitValue());
    org.junit.jupiter.api.Assertions.assertEquals(
        "RESPALDO PRUEBA",
        db.one("SELECT nombre FROM idiomas WHERE id_idioma=?", id).get("nombre"));
  }

  @org.junit.jupiter.api.Test
  void datasourceBackupUsesPrivateTemporaryCredentials(
      @org.junit.jupiter.api.io.TempDir java.nio.file.Path temp) throws Exception {
    var service =
        new mx.ipn.esime.celex.service.BackupService(
            "/usr/local/mysql/bin/mysqldump",
            "celex_test_suite",
            temp.resolve("storage").toString(),
            temp.resolve("backups").toString(),
            System.getenv("CELEX_TEST_MYSQL_URL"),
            System.getenv("CELEX_TEST_MYSQL_USER"),
            System.getenv("CELEX_TEST_MYSQL_PASSWORD"));
    var archive = service.snapshot();
    try (var zip = new java.util.zip.ZipFile(archive.toFile())) {
      org.junit.jupiter.api.Assertions.assertNotNull(zip.getEntry("database.sql"));
      org.junit.jupiter.api.Assertions.assertNull(zip.getEntry("client.cnf"));
    }
    try (var files = java.nio.file.Files.list(temp.resolve("backups"))) {
      org.junit.jupiter.api.Assertions.assertEquals(1, files.count());
    }
  }

  @org.junit.jupiter.api.Test
  void boletaMigrationPreservesLegacyAccount() throws Exception {
    var ds = context.getBean(javax.sql.DataSource.class);
    var migration =
        new org.springframework.jdbc.datasource.init.ResourceDatabasePopulator(
            new org.springframework.core.io.FileSystemResource(
                "database/migrations/20261117_boleta.sql"));
    db.update("ALTER TABLE usuarios DROP COLUMN boleta");
    db.update("ALTER TABLE usuarios MODIFY COLUMN curp VARCHAR(18) NOT NULL");
    try {
      String hash = encoder.encode("claveantigua");
      int id =
          db.id(
              "INSERT INTO"
                  + " usuarios(nombre_completo,curp,telefono,correo,password_hash,tipo_usuario,estado_cuenta,intentos_fallidos)"
                  + " VALUES('Cuenta"
                  + " existente','GAML000101HDFRNS09','5555555555','anterior@example.invalid',?,'Público"
                  + " general','Activa',0)",
              hash);
      migration.execute(ds);
      var account = db.one("SELECT * FROM usuarios WHERE id_usuario=?", id);
      org.junit.jupiter.api.Assertions.assertEquals(hash, account.get("password_hash"));
      org.junit.jupiter.api.Assertions.assertEquals("GAML000101HDFRNS09", account.get("curp"));
      org.junit.jupiter.api.Assertions.assertNull(account.get("boleta"));
      org.junit.jupiter.api.Assertions.assertTrue(
          authentication
              .authenticate(
                  org.springframework.security.authentication.UsernamePasswordAuthenticationToken
                      .unauthenticated("anterior@example.invalid", "claveantigua"))
              .isAuthenticated());
      accounts.register(
          new mx.ipn.esime.celex.web.Registration(
              "López Pérez Ana",
              "",
              "5555555555",
              "nueva@alumno.ipn.mx",
              "Clave123",
              "IPN",
              "2026123456", java.time.LocalDate.now().minusYears(20)));
      org.junit.jupiter.api.Assertions.assertEquals(2, db.rows("SELECT * FROM usuarios").size());
    } finally {
      var column =
          db.rows(
              "SELECT COLUMN_NAME FROM information_schema.COLUMNS WHERE"
                  + " TABLE_SCHEMA='celex_test_suite' AND TABLE_NAME='usuarios' AND"
                  + " COLUMN_NAME='boleta'");
      if (column.isEmpty()) migration.execute(ds);
    }
  }
}
