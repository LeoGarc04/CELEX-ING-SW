package mx.ipn.esime.celex.service;

import java.util.*;
import mx.ipn.esime.celex.repository.Db;
import org.springframework.stereotype.Service;

@Service
public class AcademicRoutes {
  private final Db db;

  public AcademicRoutes(Db db) {
    this.db = db;
  }

  public List<String> levels(int language) {
    String name =
        (String) db.one("SELECT nombre FROM idiomas WHERE id_idioma=?", language).get("nombre");
    if (EnglishLevels.isEnglish(name)) return EnglishLevels.ALL;
    var values =
        new TreeSet<String>(
            (a, b) -> {
              List<String> stages = List.of("Básico", "Intermedio", "Avanzado");
              int sa = stage(a, stages), sb = stage(b, stages);
              int c = Integer.compare(sa, sb);
              if (c != 0) return c;
              c = Integer.compare(number(a), number(b));
              return c == 0 ? a.compareToIgnoreCase(b) : c;
            });
    values.add("Básico 1");
    for (var row :
        db.rows(
            "SELECT DISTINCT nivel FROM grupos WHERE id_idioma=? AND nivel NOT IN"
                + " ('Colocación','Trámite')",
            language)) values.add((String) row.get("nivel"));
    return new ArrayList<>(values);
  }

  public List<String> examLevels(int language) {
    return levels(language).stream()
        .filter(level -> level.equals("Introductorio")
            || (stage(level, List.of("Básico", "Intermedio", "Avanzado")) < 2)
            || level.equals("Avanzado 1"))
        .toList();
  }

  private static int stage(String value, List<String> stages) {
    for (int i = 0; i < stages.size(); i++) if (value.startsWith(stages.get(i))) return i;
    return 3;
  }

  private static int number(String value) {
    try {
      return Integer.parseInt(value.replaceAll("[^0-9]", ""));
    } catch (Exception e) {
      return 0;
    }
  }

  public boolean newcomer(int user) {
    return db.rows("SELECT id_historial FROM historial_academico WHERE id_usuario=?", user)
            .isEmpty()
        && db.rows(
                "SELECT s.id_solicitud FROM solicitudes_inscripcion s JOIN grupos g ON"
                    + " g.id_grupo=s.id_grupo WHERE s.id_usuario=? AND"
                    + " s.estado_inscripcion='Confirmada' AND g.nivel NOT IN"
                    + " ('Colocación','Trámite')",
                user)
            .isEmpty();
  }

  public Map<String, Object> route(int user, int language) {
    var rows =
        db.rows(
            "SELECT * FROM historial_academico WHERE id_usuario=? AND id_idioma=? ORDER BY"
                + " fecha_registro DESC,id_historial DESC",
            user,
            language);
    var result = new LinkedHashMap<String, Object>();
    result.put("puedeExamen", true);
    String name =
        (String) db.one("SELECT nombre FROM idiomas WHERE id_idioma=?", language).get("nombre");
    String entry = EnglishLevels.isEnglish(name) ? "Introductorio" : "Básico 1";
    result.put("nuevo", rows.isEmpty());
    result.put("via", rows.isEmpty() ? "Inicial" : "Continuación");
    result.put("nivel", entry);
    result.put("puedeCurso", true);
    if (rows.isEmpty()) {
      var prior =
          db.rows(
              "SELECT g.nivel FROM solicitudes_inscripcion s JOIN grupos g ON g.id_grupo=s.id_grupo"
                  + " WHERE s.id_usuario=? AND g.id_idioma=? AND s.estado_inscripcion='Confirmada'"
                  + " AND g.nivel NOT IN ('Colocación','Trámite') ORDER BY s.id_solicitud DESC"
                  + " LIMIT 1",
              user,
              language);
      if (!prior.isEmpty()) {
        result.put("nuevo", false);
        result.put("puedeCurso", false);
        result.put("via", "Continuación");
        result.put("nivel", prior.get(0).get("nivel"));
        result.put(
            "motivo",
            "Falta registrar la calificación o el nivel autorizado de tu curso anterior. Puedes"
                + " solicitar examen de colocación o revisión académica.");
      }
    }
    if (!rows.isEmpty()) {
      var h = rows.get(0);
      String current = (String) h.get("nivel_actual");
      result.put("nivel", current);
      if (!Rules.eligible(h)) {
        result.put("puedeCurso", false);
        result.put("motivo", Rules.academicDecision(h));
      } else if (h.get("calificacion") != null
          && Db.number(h, "calificacion") >= 80
          && Rules.yes(h.get("acreditado"))) {
        var levels = levels(language);
        int n = levels.indexOf(current);
        if (n < 0 || n + 1 >= levels.size()) {
          result.put("puedeCurso", false);
          result.put(
              "motivo",
              "No hay un nivel siguiente configurado. Solicita revisión académica o examen.");
        } else {
          String next = levels.get(n + 1);
          boolean sameStage =
              stage(current, List.of("Básico", "Intermedio", "Avanzado"))
                  == stage(next, List.of("Básico", "Intermedio", "Avanzado"));
          if ((sameStage && number(next) != number(current) + 1)
              || (!sameStage && number(next) > 1)) {
            result.put("puedeCurso", false);
            result.put(
                "motivo",
                "Falta configurar el nivel consecutivo. Solicita revisión académica; no se puede"
                    + " saltar un nivel.");
          } else result.put("nivel", next);
        }
      } else if (h.get("calificacion") != null && Db.number(h, "calificacion") < 80) {
        result.put("via", "Repetición");
      }
    }
    result.putIfAbsent("motivo", "Nivel disponible: " + result.get("nivel"));
    return result;
  }

  public boolean eligible(int user, Map<String, Object> group) {
    var route = route(user, Db.number(group, "id_idioma"));
    if ("Colocación".equals(group.get("nivel"))) return true;
    return Rules.yes(route.get("puedeCurso"))
        && Objects.equals(route.get("nivel"), group.get("nivel"));
  }
}
