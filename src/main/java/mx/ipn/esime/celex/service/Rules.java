package mx.ipn.esime.celex.service;

import java.math.BigDecimal;
import java.text.Normalizer;
import java.time.*;
import java.util.*;
import mx.ipn.esime.celex.repository.Db;

public final class Rules {
  private Rules() {}

  public static boolean yes(Object v) {
    return Boolean.TRUE.equals(v) || (v instanceof Number n && n.intValue() != 0);
  }

  public static int priority(
      boolean scholar, int score, boolean external, BigDecimal average, boolean newcomer) {
    if (score < 0) throw new IllegalArgumentException("El puntaje de beca no puede ser negativo");
    if (scholar) return 4;
    if (external && average.compareTo(new BigDecimal("9")) > 0) return 3;
    return newcomer ? 2 : 1;
  }

  public static boolean eligible(Map<String, Object> h) {
    if (yes(h.get("requiere_examen_colocacion"))) return false;
    int inactive = Db.number(h, "periodos_inactivo");
    String level =
        Normalizer.normalize(h.get("nivel_actual").toString(), Normalizer.Form.NFD)
            .replaceAll("\\p{M}", "")
            .toLowerCase(Locale.ROOT);
    if (level.contains("basico") && inactive > 0) return false;
    if ((level.contains("intermedio") || level.contains("avanzado")) && inactive > 1) return false;
    return h.get("calificacion") == null || Db.number(h, "calificacion") >= 80 || inactive == 0;
  }

  public static String academicDecision(Map<String, Object> history) {
    if (!eligible(history)) return "Requiere examen de colocación antes de inscribirse.";
    if (history.get("calificacion") == null) return "Nivel asignado; sin calificación registrada.";
    return Db.number(history, "calificacion") >= 80
        ? "Nivel acreditado."
        : "Nivel no acreditado: debe repetir el mismo nivel en el periodo inmediato siguiente.";
  }

  public static LocalDateTime twoBusinessDays(LocalDateTime start) {
    var t = start;
    int days = 0;
    while (days < 2) {
      t = t.plusDays(1);
      if (t.getDayOfWeek() != DayOfWeek.SATURDAY && t.getDayOfWeek() != DayOfWeek.SUNDAY) days++;
    }
    return t;
  }

  public static boolean overlaps(String daysA, String timeA, String daysB, String timeB) {
    var a = interval(timeA);
    var b = interval(timeB);
    var days = new HashSet<>(Arrays.asList(daysA.split(",")));
    days.retainAll(Arrays.asList(daysB.split(",")));
    return !days.isEmpty() && a[0].isBefore(b[1]) && b[0].isBefore(a[1]);
  }

  public static LocalTime[] interval(String value) {
    try {
      var v = value.split("-");
      if (v.length != 2) throw new IllegalArgumentException();
      var a = LocalTime.parse(v[0]);
      var b = LocalTime.parse(v[1]);
      if (!a.isBefore(b)) throw new IllegalArgumentException();
      return new LocalTime[] {a, b};
    } catch (Exception e) {
      throw new IllegalArgumentException(
          "Horario inválido. Usa HH:mm-HH:mm, dentro del mismo día.");
    }
  }

  public static String dayNames(String value) {
    var labels =
        java.util.List.of("lunes", "martes", "miércoles", "jueves", "viernes", "sábado", "domingo");
    return java.util.Arrays.stream(value.split(","))
        .map(
            x -> {
              try {
                return labels.get(Integer.parseInt(x.trim()) - 1);
              } catch (Exception e) {
                return x;
              }
            })
        .collect(java.util.stream.Collectors.joining(", "));
  }
}
