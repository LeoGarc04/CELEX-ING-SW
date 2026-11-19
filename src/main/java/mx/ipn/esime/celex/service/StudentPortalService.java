package mx.ipn.esime.celex.service;

import java.math.BigDecimal;
import java.time.*;
import java.time.format.DateTimeFormatter;
import java.time.temporal.TemporalAdjusters;
import java.util.*;
import mx.ipn.esime.celex.repository.Db;
import org.springframework.stereotype.Service;

@Service
public class StudentPortalService {
  private final Db db;
  private final Clock clock;
  @org.springframework.beans.factory.annotation.Autowired private AcademicRoutes routes;

  public StudentPortalService(Db db, Clock clock) {
    this.db = db;
    this.clock = clock;
  }

  public record Event(LocalDate fecha, String hora, String titulo, String enlace, String tipo) {}

  private void add(
      List<Event> events,
      LocalDateTime value,
      String title,
      String link,
      String type,
      LocalDate first,
      LocalDate end) {
    if (value != null && !value.toLocalDate().isBefore(first) && value.toLocalDate().isBefore(end))
      events.add(
          new Event(
              value.toLocalDate(),
              value.toLocalTime().format(DateTimeFormatter.ofPattern("HH:mm")),
              title,
              link,
              type));
  }

  public Map<String, Object> calendar(int user, String requestedMonth) {
    YearMonth month;
    try {
      month =
          requestedMonth == null || requestedMonth.isBlank()
              ? YearMonth.now(clock)
              : YearMonth.parse(requestedMonth);
      if (month.getYear() < 1970 || month.getYear() > 2100) throw new IllegalArgumentException();
    } catch (RuntimeException e) {
      throw new IllegalArgumentException("Mes inválido. Usa el formato AAAA-MM entre 1970 y 2100.");
    }
    LocalDate first = month.atDay(1).with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY)),
        end = first.plusDays(42);
    var events = new ArrayList<Event>();
    for (var call :
        db.rows(
            "SELECT id_convocatoria,nombre,fecha_inicio_fila,fecha_fin_fila FROM convocatorias"
                + " WHERE (fecha_inicio_fila>=? AND fecha_inicio_fila<?) OR (fecha_fin_fila>=? AND"
                + " fecha_fin_fila<?) ORDER BY fecha_inicio_fila",
            first.atStartOfDay(),
            end.atStartOfDay(),
            first.atStartOfDay(),
            end.atStartOfDay())) {
      add(
          events,
          QueueService.time(call.get("fecha_inicio_fila")),
          "Apertura · " + call.get("nombre"),
          "/panel",
          "apertura",
          first,
          end);
      add(
          events,
          QueueService.time(call.get("fecha_fin_fila")),
          "Cierre · " + call.get("nombre"),
          "/panel",
          "cierre",
          first,
          end);
    }
    for (var request :
        db.rows(
            "SELECT id_solicitud,folio,prorroga_expira_en FROM solicitudes_inscripcion WHERE"
                + " id_usuario=? AND estado_inscripcion='En proceso' AND prorroga_expira_en>=? AND"
                + " prorroga_expira_en<?",
            user,
            first.atStartOfDay(),
            end.atStartOfDay()))
      add(
          events,
          QueueService.time(request.get("prorroga_expira_en")),
          "Límite de corrección · " + request.get("folio"),
          "/solicitud/" + request.get("id_solicitud"),
          "correccion",
          first,
          end);
    for (var payment :
        db.rows(
            "SELECT d.fecha_pago,s.id_solicitud,s.folio FROM documentos_comprobantes d JOIN"
                + " solicitudes_inscripcion s ON s.id_solicitud=d.id_solicitud WHERE s.id_usuario=?"
                + " AND d.id_documento=(SELECT MAX(d2.id_documento) FROM documentos_comprobantes d2"
                + " WHERE d2.id_solicitud=s.id_solicitud) AND d.fecha_pago>=? AND d.fecha_pago<?",
            user,
            first,
            end)) {
      LocalDate date = (LocalDate) payment.get("fecha_pago");
      events.add(
          new Event(
              date,
              "",
              "Comprobante registrado · " + payment.get("folio"),
              "/solicitud/" + payment.get("id_solicitud"),
              "pago"));
    }
    events.sort(
        Comparator.comparing(Event::fecha).thenComparing(Event::hora).thenComparing(Event::titulo));
    var cells = new ArrayList<Map<String, Object>>();
    LocalDate today = LocalDate.now(clock);
    for (int i = 0; i < 42; i++) {
      LocalDate date = first.plusDays(i);
      cells.add(
          Map.of(
              "fecha",
              date,
              "numero",
              date.getDayOfMonth(),
              "actual",
              YearMonth.from(date).equals(month),
              "hoy",
              date.equals(today),
              "eventos",
              events.stream().filter(e -> e.fecha().equals(date)).toList()));
    }
    var result = new HashMap<String, Object>();
    result.put("mes", month.toString());
    result.put(
        "titulo",
        month
            .atDay(1)
            .format(DateTimeFormatter.ofPattern("MMMM yyyy", Locale.forLanguageTag("es-MX"))));
    result.put(
        "anterior",
        month.getYear() == 1970 && month.getMonthValue() == 1
            ? null
            : month.minusMonths(1).toString());
    result.put(
        "siguiente",
        month.getYear() == 2100 && month.getMonthValue() == 12
            ? null
            : month.plusMonths(1).toString());
    result.put("dias", cells);
    result.put(
        "agenda", events.stream().filter(e -> YearMonth.from(e.fecha()).equals(month)).toList());
    result.put("semana", List.of("Lun", "Mar", "Mié", "Jue", "Vie", "Sáb", "Dom"));
    return result;
  }

  public List<Map<String, Object>> schedule(int user) {
    return db.rows(
        "SELECT g.*,i.nombre AS idioma FROM grupos g JOIN idiomas i ON i.id_idioma=g.id_idioma"
            + " WHERE g.estado='Activo' AND (EXISTS(SELECT 1 FROM solicitudes_inscripcion s WHERE"
            + " s.id_grupo=g.id_grupo AND s.id_usuario=? AND s.estado_inscripcion='Confirmada') OR"
            + " (EXISTS(SELECT 1 FROM roles_permisos p WHERE p.id_usuario=? AND"
            + " p.tipo_permiso='PROFESOR') AND EXISTS(SELECT 1 FROM roles_permisos r WHERE"
            + " r.id_usuario=? AND r.tipo_permiso=CONCAT('DOCENTE_GRUPO_',g.id_grupo)))) ORDER BY"
            + " i.nombre,g.nivel,g.horario,g.id_grupo",
        user,
        user,
        user);
  }

  public Map<String, Object> payments(int user) {
    var rows =
        DocumentAssessment.display(
            db.rows(
                "SELECT s.*,g.nivel,i.nombre AS"
                    + " idioma,d.id_documento,d.numero_referencia,d.fecha_pago,d.importe,d.estatus_pago,d.observaciones_dictamen,(SELECT"
                    + " COUNT(*) FROM documentos_comprobantes dc WHERE"
                    + " dc.id_solicitud=s.id_solicitud) AS entregas FROM solicitudes_inscripcion s"
                    + " JOIN grupos g ON g.id_grupo=s.id_grupo JOIN idiomas i ON"
                    + " i.id_idioma=g.id_idioma LEFT JOIN documentos_comprobantes d ON"
                    + " d.id_documento=(SELECT MAX(d2.id_documento) FROM documentos_comprobantes d2"
                    + " WHERE d2.id_solicitud=s.id_solicitud) WHERE s.id_usuario=? ORDER BY"
                    + " s.id_solicitud DESC",
                user));
    int approved = 0, pending = 0, correction = 0;
    BigDecimal total = BigDecimal.ZERO;
    for (var row : rows) {
      if (row.get("id_documento") == null) continue;
      switch (row.get("estado_pago").toString()) {
        case "Aprobado" -> {
          approved++;
          total = total.add((BigDecimal) row.get("importe"));
        }
        case "Rechazado" -> correction++;
        default -> pending++;
      }
    }
    return Map.of(
        "registros",
        rows,
        "aprobados",
        approved,
        "pendientes",
        pending,
        "correcciones",
        correction,
        "importeAprobado",
        total);
  }

  public List<Map<String, Object>> languages() {
    return db.rows("SELECT id_idioma,nombre FROM idiomas WHERE activo=TRUE ORDER BY nombre");
  }

  public Map<String, Object> curriculum(int user) {
    var available = languages();
    if (available.isEmpty())
      return Map.of(
          "etapas",
          List.of(),
          "nivelActual",
          "",
          "acreditados",
          0,
          "total",
          0,
          "decision",
          "No hay idiomas publicados.",
          "idioma",
          "",
          "idIdioma",
          0);
    var chosen =
        available.stream()
            .filter(l -> EnglishLevels.isEnglish((String) l.get("nombre")))
            .findFirst()
            .orElse(available.get(0));
    return curriculum(user, Db.number(chosen, "id_idioma"));
  }

  public Map<String, Object> curriculum(int user, int language) {
    var selected =
        languages().stream()
            .filter(l -> Db.number(l, "id_idioma") == language)
            .findFirst()
            .orElseThrow(() -> new IllegalArgumentException("Idioma no disponible."));
    var histories =
        db.rows(
            "SELECT * FROM historial_academico WHERE id_usuario=? AND id_idioma=? ORDER BY"
                + " fecha_registro DESC,id_historial DESC",
            user,
            language);
    String current = histories.isEmpty() ? "" : (String) histories.get(0).get("nivel_actual");
    var latest = new HashMap<String, Map<String, Object>>();
    for (var h : histories) latest.putIfAbsent((String) h.get("nivel_actual"), h);
    var stages = new ArrayList<Map<String, Object>>();
    int passed = 0;
    var names = routes.levels(language);
    for (String title : List.of("Introductorio", "Básico", "Intermedio", "Avanzado", "Otros")) {
      var levels = new ArrayList<Map<String, Object>>();
      for (String name : names) {
        boolean other =
            !name.equals("Introductorio")
                && !name.startsWith("Básico ")
                && !name.startsWith("Intermedio ")
                && !name.startsWith("Avanzado ");
        if (!(title.equals("Otros") ? other : name.equals(title) || name.startsWith(title + " ")))
          continue;
        var h = latest.get(name);
        boolean accredited =
            h != null
                && Rules.yes(h.get("acreditado"))
                && h.get("calificacion") != null
                && Db.number(h, "calificacion") >= 80;
        if (accredited) passed++;
        var level = new HashMap<String, Object>();
        level.put("nombre", name);
        level.put("asignado", name.equals(current));
        level.put("acreditado", accredited);
        level.put("calificacion", h == null ? null : h.get("calificacion"));
        levels.add(level);
      }
      if (!levels.isEmpty()) stages.add(Map.of("nombre", title, "niveles", levels));
    }
    var result = new HashMap<String, Object>();
    result.put("etapas", stages);
    result.put("nivelActual", current);
    result.put("acreditados", passed);
    result.put("total", names.size());
    result.put(
        "decision",
        histories.isEmpty()
            ? "Si empiezas desde cero puedes solicitar el nivel inicial o examen de colocación."
            : Rules.academicDecision(histories.get(0)));
    result.put("idioma", selected.get("nombre"));
    result.put("idIdioma", language);
    return result;
  }
}
