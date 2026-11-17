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
  void load200() throws Exception {
    int c = call();
    db.update("UPDATE convocatorias SET capacidad_n_fila=200 WHERE id_convocatoria=?", c);
    String hash = encoder.encode("una-contraseña-segura");
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
}
