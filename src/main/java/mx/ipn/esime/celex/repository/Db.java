package mx.ipn.esime.celex.repository;

import java.util.*;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

@Component
public class Db {
  private final JdbcClient client;

  public Db(JdbcClient client) {
    this.client = client;
  }

  public List<Map<String, Object>> rows(String sql, Object... args) {
    return client.sql(sql).params(args).query().listOfRows().stream()
        .map(
            row -> {
              Map<String, Object> normalized = new HashMap<>(row);
              normalized.replaceAll(
                  (key, value) ->
                      value instanceof java.sql.Timestamp t
                          ? t.toLocalDateTime()
                          : value instanceof java.sql.Date d ? d.toLocalDate() : value);
              return normalized;
            })
        .toList();
  }

  public Map<String, Object> one(String sql, Object... args) {
    var rows = rows(sql, args);
    if (rows.size() != 1) throw new IllegalArgumentException("Registro no disponible");
    return rows.get(0);
  }

  public int update(String sql, Object... args) {
    return client.sql(sql).params(args).update();
  }

  public int id(String sql, Object... args) {
    var keys = new org.springframework.jdbc.support.GeneratedKeyHolder();
    client.sql(sql).params(args).update(keys);
    return ((Number)
            java.util.Objects.requireNonNull(keys.getKeys()).values().stream()
                .filter(Number.class::isInstance)
                .findFirst()
                .orElseThrow())
        .intValue();
  }

  public static int number(Map<String, Object> row, String key) {
    return ((Number) row.get(key)).intValue();
  }

  public void audit(int user, String action, String detail) {
    update(
        "INSERT INTO bitacora_acciones(id_usuario,accion,detalles,fecha_hora)"
            + " VALUES(?,?,?,CURRENT_TIMESTAMP)",
        user,
        action,
        detail);
  }
}
