package mx.ipn.esime.celex.service;

import java.time.*;
import java.util.*;
import mx.ipn.esime.celex.repository.Db;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.json.JsonMapper;

@Service
public class CallSettings {
  private final Db db;
  private static final JsonMapper JSON = JsonMapper.builder().build();

  public CallSettings(Db db) {
    this.db = db;
  }

  @SuppressWarnings("unchecked")
  public Map<String, Object> read(String action) {
    var rows =
        db.rows(
            "SELECT detalles FROM bitacora_acciones WHERE accion=? ORDER BY id_bitacora DESC LIMIT"
                + " 1",
            action);
    if (rows.isEmpty()) return new LinkedHashMap<>();
    return new LinkedHashMap<>(JSON.readValue((String) rows.get(0).get("detalles"), Map.class));
  }

  public void write(int actor, String action, Map<String, ?> value) {
    db.audit(actor, action, JSON.writeValueAsString(value));
  }

  public Map<String, Object> call(int id) {
    return read("Configuración convocatoria · " + id);
  }

  public boolean managed(int id) {
    return Integer.valueOf(2).equals(call(id).get("version"));
  }

  public LocalDateTime classes(int id) {
    var value = call(id).get("inicioClases");
    return value == null ? null : LocalDateTime.parse(value.toString());
  }

  public int examCapacity(int id) {
    var value = call(id).get("cupoExamen");
    return value instanceof Number n ? n.intValue() : 25;
  }

  public Map<String, Object> preference(int id) {
    return read("Preferencia inscripción · " + id);
  }

  public Map<String, Object> assignment(int id) {
    return read("Asignación inscripción · " + id);
  }

  public boolean assigned(int id) {
    return !assignment(id).isEmpty();
  }

  @Transactional
  public void configure(int actor, int id, LocalDateTime classes, int examCapacity) {
    var c = db.one("SELECT * FROM convocatorias WHERE id_convocatoria=? FOR UPDATE", id);
    if (classes == null
        || classes.isBefore(QueueService.time(c.get("fecha_fin_fila")))
        || examCapacity < 1
        || examCapacity > 10000)
      throw new IllegalArgumentException(
          "El inicio de clases debe ser posterior al cierre; revisa el cupo de examen (1–10000).");
    for (var exam :
        db.rows(
            "SELECT * FROM grupos WHERE id_convocatoria=? AND nivel='Colocación' ORDER BY id_grupo"
                + " FOR UPDATE",
            id)) {
      int occupied = Db.number(exam, "cupo_total") - Db.number(exam, "cupo_disponible");
      if (examCapacity < occupied)
        throw new IllegalArgumentException(
            "El cupo del examen no puede ser menor que las reservas existentes.");
      db.update(
          "UPDATE grupos SET cupo_total=?,cupo_disponible=? WHERE id_grupo=?",
          examCapacity,
          examCapacity - occupied,
          exam.get("id_grupo"));
    }
    write(
        actor,
        "Configuración convocatoria · " + id,
        Map.of("version", 2, "inicioClases", classes.toString(), "cupoExamen", examCapacity));
  }
}
