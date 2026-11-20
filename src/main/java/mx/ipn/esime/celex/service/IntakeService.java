package mx.ipn.esime.celex.service;

import java.time.*;
import java.util.*;
import mx.ipn.esime.celex.repository.Db;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class IntakeService {
  private final Db db;
  private final QueueService queue;
  private final EnrollmentService enrollments;
  private final AcademicRoutes routes;
  private final CallSettings settings;
  private final Clock clock;

  public IntakeService(
      Db db,
      QueueService queue,
      EnrollmentService enrollments,
      AcademicRoutes routes,
      CallSettings settings,
      Clock clock) {
    this.db = db;
    this.queue = queue;
    this.enrollments = enrollments;
    this.routes = routes;
    this.settings = settings;
    this.clock = clock;
  }

  public List<Map<String, Object>> options(int user, int call) {
    var languages =
        db.rows(
            "SELECT DISTINCT i.id_idioma,i.nombre FROM idiomas i JOIN grupos g ON"
                + " g.id_idioma=i.id_idioma WHERE g.id_convocatoria=? AND g.estado='Activo' AND"
                + " i.activo=TRUE AND g.nivel NOT IN ('Colocación','Trámite') ORDER BY i.nombre",
            call);
    for (var language : languages) {
      int id = Db.number(language, "id_idioma");
      var route = routes.route(user, id);
      language.putAll(route);
      language.put("nivelesExamen",routes.examLevels(id));
      var exam =
          db.rows(
              "SELECT cupo_disponible FROM grupos WHERE id_convocatoria=? AND id_idioma=? AND"
                  + " nivel='Colocación'",
              call,
              id);
      language.put(
          "cupoExamen",
          exam.isEmpty() ? settings.examCapacity(call) : Db.number(exam.get(0), "cupo_disponible"));
      language.put(
          "groups",
          db.rows(
              "SELECT id_grupo,modalidad,horario,dias,cupo_disponible FROM grupos WHERE"
                  + " id_convocatoria=? AND id_idioma=? AND nivel=? AND estado='Activo' ORDER BY"
                  + " modalidad,horario",
              call,
              id,
              route.get("nivel")));
    }
    return languages;
  }

  @Transactional
  public int request(int user,String folio,int language,String route,String modality,String preferredTime) {
    return request(user,folio,language,route,modality,preferredTime,"");
  }

  @Transactional
  public int request(
      int user, String folio, int language, String route, String modality, String preferredTime, String requestedLevel) {
    var turn = db.one("SELECT * FROM fila_virtual WHERE folio=? AND id_usuario=?", folio, user);
    int call = Db.number(turn, "id_convocatoria");
    queue.lock(call);
    var previous = db.rows("SELECT id_solicitud FROM solicitudes_inscripcion WHERE folio=?", folio);
    if (!previous.isEmpty()) return Db.number(previous.get(0), "id_solicitud");
    if (options(user, call).stream().noneMatch(l -> Db.number(l, "id_idioma") == language))
      throw new IllegalArgumentException("Idioma no ofertado en esta convocatoria.");
    var allowed = routes.route(user, language);
    int group;
    if ("Colocación".equals(route)) {
      if (!routes.examLevels(language).contains(requestedLevel)) throw new IllegalArgumentException("Selecciona el nivel para el que deseas realizar el examen, hasta Avanzado 1.");
      var existing =
          db.rows(
              "SELECT id_grupo FROM grupos WHERE id_convocatoria=? AND id_idioma=? AND"
                  + " nivel='Colocación'",
              call,
              language);
      group =
          existing.isEmpty()
              ? db.id(
                  "INSERT INTO"
                      + " grupos(id_convocatoria,id_idioma,nivel,modalidad,profesor,aula,dias,horario,cupo_total,cupo_disponible,estado)"
                      + " VALUES(?,?,'Colocación','Examen de colocación','Por asignar','Por"
                      + " asignar','Pendiente','Por asignar',?,?,'Activo')",
                  call,
                  language,
                  settings.examCapacity(call),
                  settings.examCapacity(call))
              : Db.number(existing.get(0), "id_grupo");
    } else {
      if (!route.equals(allowed.get("via")) || !Rules.yes(allowed.get("puedeCurso")))
        throw new IllegalArgumentException(
            "Ruta académica no autorizada. Selecciona examen de colocación o solicita revisión.");
      var candidates =
          db.rows(
              "SELECT id_grupo FROM grupos WHERE id_convocatoria=? AND id_idioma=? AND nivel=? AND"
                  + " modalidad=? AND horario=? AND estado='Activo' AND cupo_disponible>0 ORDER BY"
                  + " id_grupo",
              call,
              language,
              allowed.get("nivel"),
              modality,
              preferredTime);
      if (candidates.isEmpty())
        throw new IllegalArgumentException(
            "No hay lugares para esa preferencia. Elige otra modalidad u horario.");
      group = Db.number(candidates.get(0), "id_grupo");
    }
    int request = enrollments.select(user, folio, group);
    var current = settings.preference(request);
    if (current.isEmpty()) {
      var preference = new LinkedHashMap<String, Object>();
      preference.put("version", 3);
      if ("Colocación".equals(route)) preference.put("nivelSolicitado",requestedLevel);
      preference.put("idioma", language);
      preference.put("via", route);
      preference.put("nivel", "Colocación".equals(route) ? "Colocación" : allowed.get("nivel"));
      preference.put("modalidad", "Colocación".equals(route) ? "Examen de colocación" : modality);
      preference.put("horario", "Colocación".equals(route) ? "Por asignar" : preferredTime);
      preference.put("convocatoria", call);
      settings.write(user, "Preferencia inscripción · " + request, preference);
      if ("Colocación".equals(route))
        db.update(
            "UPDATE solicitudes_inscripcion SET monto_calculado=150.00 WHERE id_solicitud=?",
            request);
    }
    return request;
  }

  public List<Map<String, Object>> assignable(int request) {
    var preference = settings.preference(request);
    if (preference.isEmpty()) return List.of();
    return db.rows(
        "SELECT g.*,i.nombre AS idioma FROM grupos g JOIN idiomas i ON i.id_idioma=g.id_idioma"
            + " WHERE g.id_convocatoria=? AND g.id_idioma=? AND g.nivel=? AND g.estado='Activo' AND"
            + " g.modalidad=? AND (LOWER(g.modalidad) LIKE '%intensivo%' OR g.nivel='Colocación' OR"
            + " g.horario=?) ORDER BY g.modalidad,g.horario,g.id_grupo",
        preference.get("convocatoria"),
        preference.get("idioma"),
        preference.get("nivel"),
        preference.get("modalidad"),
        preference.get("horario"));
  }

  public List<String> examLevels(int request) {
    var row=db.one("SELECT g.id_idioma FROM solicitudes_inscripcion s JOIN grupos g ON g.id_grupo=s.id_grupo WHERE s.id_solicitud=?",request);
    return routes.levels(Db.number(row,"id_idioma"));
  }

  @Transactional
  public void examLevel(int actor,int request,String level) {
    var chosen=settings.preference(request).get("nivelSolicitado");
    if (chosen==null || !chosen.equals(level))
      throw new IllegalArgumentException("El nivel solicitado del examen lo selecciona el alumno. Administración registra el nivel asignado al publicar el resultado.");
    var row=db.one("SELECT g.nivel,g.id_idioma FROM solicitudes_inscripcion s JOIN grupos g ON g.id_grupo=s.id_grupo WHERE s.id_solicitud=? FOR UPDATE",request);
    if (!"Colocación".equals(row.get("nivel")) || level==null || !routes.levels(Db.number(row,"id_idioma")).contains(level))
      throw new IllegalArgumentException("Selecciona el nivel del idioma al que aplicará el examen.");
    settings.write(actor,"Nivel examen · "+request,Map.of("nivel",level));
  }

  @Transactional
  public void assign(int actor,int request,int chosen) {
    assign(actor,request,chosen,"");
  }

  @Transactional
  public void assign(int actor, int request, int chosen, String examLevel) {
    var initial =
        db.one(
            "SELECT s.*,g.id_convocatoria FROM solicitudes_inscripcion s JOIN grupos g ON"
                + " g.id_grupo=s.id_grupo WHERE s.id_solicitud=?",
            request);
    int call = Db.number(initial, "id_convocatoria");
    queue.lock(call);
    var row =
        db.one("SELECT * FROM solicitudes_inscripcion WHERE id_solicitud=? FOR UPDATE", request);
    if (!"Pendiente de revisión".equals(row.get("estado_inscripcion")))
      throw new IllegalArgumentException("Primero el usuario debe enviar el pago a revisión.");
    if (assignable(request).stream().noneMatch(g -> Db.number(g, "id_grupo") == chosen))
      throw new IllegalArgumentException("Grupo no compatible con el idioma y nivel solicitado.");
    if ("Colocación".equals(settings.preference(request).get("via"))) {
      String requested=Objects.toString(settings.preference(request).get("nivelSolicitado"),"");
      if(!examLevel.isBlank() && !examLevel.equals(requested)) throw new IllegalArgumentException("No puedes cambiar el nivel solicitado por el alumno.");
      examLevel(actor,request,requested);
    }
    int old = Db.number(row, "id_grupo");
    db.rows(
        "SELECT * FROM grupos WHERE id_grupo IN (?,?) ORDER BY id_grupo FOR UPDATE", old, chosen);
    var selected = db.one("SELECT * FROM grupos WHERE id_grupo=?", chosen);
    if ("Por asignar".equals(selected.get("profesor"))
        || "Por asignar".equals(selected.get("horario")))
      throw new IllegalArgumentException(
          "Configura profesor y horario del grupo o examen antes de asignarlo.");
    if (old != chosen) {
      if (db.update(
              "UPDATE grupos SET cupo_disponible=cupo_disponible-1 WHERE id_grupo=? AND"
                  + " cupo_disponible>0",
              chosen)
          != 1) throw new IllegalArgumentException("El grupo seleccionado ya no tiene cupo.");
      db.update(
          "UPDATE grupos SET cupo_disponible=cupo_disponible+1 WHERE id_grupo=? AND"
              + " cupo_disponible<cupo_total",
          old);
      db.update(
          "UPDATE solicitudes_inscripcion SET id_grupo=? WHERE id_solicitud=?", chosen, request);
    }
    settings.write(
        actor,
        "Asignación inscripción · " + request,
        Map.of(
            "grupo",
            chosen,
            "fecha",
            LocalDateTime.now(clock).toString(),
            "profesor",
            selected.get("profesor"),
            "horario",
            selected.get("horario")));
  }
}
