package mx.ipn.esime.celex.service;

import java.util.*;
import tools.jackson.databind.json.JsonMapper;

public record DocumentAssessment(
    String payment, String paymentNote, String identity, String identityNote) {
  private static final JsonMapper JSON = JsonMapper.builder().build();
  private static final Set<String> STATES =
      Set.of("Pendiente de revisión", "Aprobado", "Rechazado");

  public DocumentAssessment {
    if (!STATES.contains(payment) || !STATES.contains(identity))
      throw new IllegalArgumentException("Dictamen de documento inválido");
    paymentNote = Objects.requireNonNullElse(paymentNote, "");
    identityNote = Objects.requireNonNullElse(identityNote, "");
    if (paymentNote.length() > 4000 || identityNote.length() > 4000)
      throw new IllegalArgumentException(
          "Las observaciones admiten hasta 4000 caracteres por documento.");
  }

  public static DocumentAssessment pending() {
    return new DocumentAssessment("Pendiente de revisión", "", "Pendiente de revisión", "");
  }

  public boolean approved() {
    return payment.equals("Aprobado") && identity.equals("Aprobado");
  }

  public boolean rejected() {
    return payment.equals("Rechazado") || identity.equals("Rechazado");
  }

  public String observations() {
    return (paymentNote.isBlank() ? "" : "Pago: " + paymentNote)
        + (paymentNote.isBlank() || identityNote.isBlank() ? "" : ". ")
        + (identityNote.isBlank() ? "" : "Identificación: " + identityNote);
  }

  public String encode() {
    var map = new LinkedHashMap<String, Object>();
    map.put("celexDictamen", 1);
    map.put("pago", Map.of("estado", payment, "observaciones", paymentNote));
    map.put("identificacion", Map.of("estado", identity, "observaciones", identityNote));
    return JSON.writeValueAsString(map);
  }

  public static DocumentAssessment read(Map<String, Object> row) {
    String raw = Objects.toString(row.get("observaciones_dictamen"), "");
    try {
      var tree = JSON.readTree(raw);
      if (tree.path("celexDictamen").asInt() == 1)
        return new DocumentAssessment(
            tree.path("pago").path("estado").asString(),
            tree.path("pago").path("observaciones").asString(""),
            tree.path("identificacion").path("estado").asString(),
            tree.path("identificacion").path("observaciones").asString(""));
    } catch (RuntimeException ignored) {
    }
    String legacy = Objects.toString(row.get("estatus_pago"), "Pendiente de revisión");
    if (!STATES.contains(legacy)) legacy = "Pendiente de revisión";
    return new DocumentAssessment(legacy, raw, legacy, raw);
  }

  public static List<Map<String, Object>> display(List<Map<String, Object>> rows) {
    return rows.stream()
        .map(
            row -> {
              var copy = new HashMap<>(row);
              var assessment = read(row);
              copy.put("estado_pago", assessment.payment);
              copy.put("estado_identificacion", assessment.identity);
              copy.put("observacion_pago", assessment.paymentNote);
              copy.put("observacion_identificacion", assessment.identityNote);
              copy.put("observaciones_dictamen", assessment.observations());
              return (Map<String, Object>) copy;
            })
        .toList();
  }
}
