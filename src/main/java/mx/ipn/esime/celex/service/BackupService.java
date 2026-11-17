package mx.ipn.esime.celex.service;

import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.zip.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

@Service
@ConditionalOnProperty(name = "celex.backup.enabled", havingValue = "true")
public class BackupService {
  private static final org.slf4j.Logger log =
      org.slf4j.LoggerFactory.getLogger(BackupService.class);
  private final String binary, loginPath, database;
  private final Path storage, backups;
  private String jdbcUrl, jdbcUser, jdbcPassword;

  @org.springframework.beans.factory.annotation.Autowired
  public BackupService(
      @Value("${celex.backup.mysqldump:/usr/local/mysql/bin/mysqldump}") String binary,
      @Value("${celex.backup.database:celex}") String database,
      @Value("${celex.storage}") String storage,
      @Value("${celex.backup.directory:./backups}") String backups,
      @Value("${spring.datasource.url}") String url,
      @Value("${spring.datasource.username}") String user,
      @Value("${spring.datasource.password}") String password) {
    this(binary, "celex-backup", database, storage, backups);
    this.jdbcUrl = url;
    this.jdbcUser = user;
    this.jdbcPassword = password;
  }

  private static String quote(String s) {
    return "\""
        + s.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n").replace("\r", "\\r")
        + "\"";
  }

  public BackupService(
      @Value("${celex.backup.mysqldump:/usr/local/mysql/bin/mysqldump}") String binary,
      @Value("${celex.backup.login-path:celex-backup}") String loginPath,
      @Value("${celex.backup.database:celex}") String database,
      @Value("${celex.storage}") String storage,
      @Value("${celex.backup.directory:./backups}") String backups) {
    this.binary = binary;
    this.loginPath = loginPath;
    this.database = database;
    this.storage = Path.of(storage).toAbsolutePath().normalize();
    this.backups = Path.of(backups).toAbsolutePath().normalize();
  }

  @Scheduled(cron = "${celex.backup.cron:0 0 3 * * SUN}", zone = "${celex.zone}")
  public void weekly() {
    try {
      snapshot();
      log.info("Respaldo semanal CELEX completado");
    } catch (Exception e) {
      log.error("Falló el respaldo CELEX: {}", e.getMessage());
    }
  }

  public synchronized Path snapshot() throws Exception {
    if (!database.matches("[a-zA-Z0-9_]+") || !loginPath.matches("[a-zA-Z0-9_-]+"))
      throw new IllegalArgumentException("Configuración de respaldo inválida");
    Files.createDirectories(backups);
    Files.setPosixFilePermissions(
        backups, java.nio.file.attribute.PosixFilePermissions.fromString("rwx------"));
    Path stage = Files.createTempDirectory(backups, "snapshot-"),
        sql = stage.resolve("database.sql"),
        archive = stage.resolve("backup.zip");
    try {
      var args = new ArrayList<String>();
      args.add(binary);
      if (jdbcUrl != null) {
        var uri = java.net.URI.create(jdbcUrl.substring(5));
        if (!"mysql".equals(uri.getScheme())) throw new IOException("Respaldo requiere MySQL");
        Path credentials = stage.resolve("client.cnf");
        Files.createFile(
            credentials,
            java.nio.file.attribute.PosixFilePermissions.asFileAttribute(
                java.nio.file.attribute.PosixFilePermissions.fromString("rw-------")));
        Files.writeString(
            credentials,
            "[client]\nhost="
                + quote(uri.getHost())
                + "\nport="
                + (uri.getPort() < 0 ? 3306 : uri.getPort())
                + "\nuser="
                + quote(jdbcUser)
                + "\npassword="
                + quote(jdbcPassword)
                + "\n");
        args.add("--defaults-extra-file=" + credentials);
        args.add("--no-tablespaces");
        args.add("--set-gtid-purged=OFF");
      } else args.add("--login-path=" + loginPath);
      args.addAll(
          List.of("--single-transaction", "--routines", "--triggers", "--databases", database));
      var process =
          new ProcessBuilder(args)
              .redirectOutput(sql.toFile())
              .redirectError(stage.resolve("dump.log").toFile())
              .start();
      if (!process.waitFor(10, TimeUnit.MINUTES)) {
        process.destroyForcibly();
        throw new IOException("Tiempo de respaldo excedido");
      }
      if (process.exitValue() != 0) throw new IOException("mysqldump no completó el respaldo");
      try (var zip = new ZipOutputStream(Files.newOutputStream(archive))) {
        add(zip, sql, "database.sql");
        if (Files.isDirectory(storage))
          try (var files = Files.list(storage)) {
            for (Path file :
                files.filter(x -> Files.isRegularFile(x, LinkOption.NOFOLLOW_LINKS)).toList())
              add(zip, file, "documents/" + file.getFileName());
          }
      }
      Path result =
          backups.resolve("celex-" + java.time.Instant.now().toString().replace(':', '-') + ".zip");
      Files.move(archive, result, StandardCopyOption.ATOMIC_MOVE);
      Files.setPosixFilePermissions(
          result, java.nio.file.attribute.PosixFilePermissions.fromString("rw-------"));
      return result;
    } finally {
      try (var files = Files.walk(stage)) {
        for (var file : files.sorted(Comparator.reverseOrder()).toList())
          Files.deleteIfExists(file);
      }
    }
  }

  private static void add(ZipOutputStream zip, Path file, String name) throws IOException {
    zip.putNextEntry(new ZipEntry(name));
    Files.copy(file, zip);
    zip.closeEntry();
  }
}
