package mx.ipn.esime.celex.service;

import java.io.IOException;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

@Service
public class BackupCatalog {
  private final ObjectProvider<BackupService> service;
  private final Path directory;
  private final Clock clock;

  public BackupCatalog(
      ObjectProvider<BackupService> service,
      @Value("${celex.backup.directory:./backups}") String directory,
      Clock clock) {
    this.service = service;
    this.directory = Path.of(directory).toAbsolutePath().normalize();
    this.clock = clock;
  }

  public boolean enabled() {
    return service.getIfAvailable() != null;
  }

  public List<Map<String, Object>> list() throws IOException {
    if (!Files.isDirectory(directory)) return List.of();
    try (var stream = Files.list(directory)) {
      var paths =
          stream
              .filter(
                  p ->
                      p.getFileName().toString().matches("celex-[0-9TZ.\\-]+\\.zip")
                          && Files.isRegularFile(p, LinkOption.NOFOLLOW_LINKS))
              .sorted(Comparator.reverseOrder())
              .toList();
      var result = new ArrayList<Map<String, Object>>();
      for (var p : paths)
        result.add(
            Map.of(
                "nombre",
                p.getFileName().toString(),
                "fecha",
                LocalDateTime.ofInstant(Files.getLastModifiedTime(p).toInstant(), clock.getZone()),
                "bytes",
                Files.size(p)));
      return result;
    }
  }

  public Path file(String name) {
    if (!name.matches("celex-[0-9TZ.\\-]+\\.zip"))
      throw new IllegalArgumentException("Respaldo no disponible");
    var path = directory.resolve(name).normalize();
    if (!path.getParent().equals(directory)
        || !Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS))
      throw new IllegalArgumentException("Respaldo no disponible");
    return path;
  }

  public Path create() throws Exception {
    var backup = service.getIfAvailable();
    if (backup == null)
      throw new IllegalArgumentException("Los respaldos todavía no están configurados.");
    return backup.snapshot();
  }
}
