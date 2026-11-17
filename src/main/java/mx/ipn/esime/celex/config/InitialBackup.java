package mx.ipn.esime.celex.config;

@org.springframework.stereotype.Component
@org.springframework.boot.autoconfigure.condition.ConditionalOnProperty(
    name = "celex.backup.on-startup",
    havingValue = "true")
public class InitialBackup implements org.springframework.boot.ApplicationRunner {
  private final mx.ipn.esime.celex.service.BackupCatalog backups;

  public InitialBackup(mx.ipn.esime.celex.service.BackupCatalog backups) {
    this.backups = backups;
  }

  public void run(org.springframework.boot.ApplicationArguments args) throws Exception {
    if (backups.enabled() && backups.list().isEmpty()) backups.create();
  }
}
