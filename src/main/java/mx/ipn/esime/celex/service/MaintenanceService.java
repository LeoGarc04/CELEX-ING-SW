package mx.ipn.esime.celex.service;

import java.time.*;
import mx.ipn.esime.celex.repository.Db;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@org.springframework.boot.autoconfigure.condition.ConditionalOnProperty(
    name = "celex.maintenance.enabled",
    havingValue = "true",
    matchIfMissing = true)
public class MaintenanceService {
  private final Db db;
  private final QueueService queue;
  private final EnrollmentService enrollments;
  private final Clock clock;

  public MaintenanceService(Db db, QueueService queue, EnrollmentService enrollments, Clock clock) {
    this.db = db;
    this.queue = queue;
    this.enrollments = enrollments;
    this.clock = clock;
  }

  @Scheduled(fixedDelay = 30000)
  @Transactional
  public void run() {
    var now = LocalDateTime.now(clock);
    for (var c : db.rows("SELECT id_convocatoria FROM convocatorias ORDER BY id_convocatoria")) {
      int id = Db.number(c, "id_convocatoria");
      queue.lock(id);
      queue.expire(id);
      queue.order(id);
    }
    for (var s :
        db.rows(
            "SELECT * FROM solicitudes_inscripcion WHERE estado_inscripcion='En proceso' AND"
                + " prorroga_expira_en<=? ORDER BY id_solicitud FOR UPDATE",
            now)) {
      enrollments.release(s, "Cancelada");
      db.audit(
          Db.number(s, "id_usuario"),
          "Cancelación por vencimiento",
          "Solicitud " + s.get("id_solicitud"));
    }
  }
}
