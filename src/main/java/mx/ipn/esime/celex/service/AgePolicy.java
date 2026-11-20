package mx.ipn.esime.celex.service;

import java.time.*;
import java.util.Map;
import mx.ipn.esime.celex.repository.Db;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class AgePolicy {
  private final Db db;
  private final CallSettings settings;
  private final Clock clock;

  public AgePolicy(Db db, CallSettings settings, Clock clock) {
    this.db = db;
    this.settings = settings;
    this.clock = clock;
  }

  public void validate(LocalDate birth) {
    if (birth == null || birth.isAfter(LocalDate.now(clock).minusYears(15)))
      throw new IllegalArgumentException("Para inscribirte debes tener al menos 15 años cumplidos. Revisa tu fecha de nacimiento.");
  }

  public LocalDate birth(int user) {
    Object value = settings.read("Fecha nacimiento · " + user).get("fecha");
    return value == null ? null : LocalDate.parse(value.toString());
  }

  public boolean eligible(int user) {
    LocalDate birth = birth(user);
    return birth != null && !birth.isAfter(LocalDate.now(clock).minusYears(15));
  }

  public void require(int user) {
    validate(birth(user));
  }

  @Transactional
  public void record(int user, LocalDate birth) {
    validate(birth);
    db.one("SELECT id_usuario FROM usuarios WHERE id_usuario=? FOR UPDATE", user);
    LocalDate previous = birth(user);
    if (previous != null && !previous.equals(birth))
      throw new IllegalArgumentException("Tu fecha de nacimiento ya está registrada. Contacta a CELEX si requiere corrección.");
    if (previous == null)
      settings.write(user, "Fecha nacimiento · " + user, Map.of("fecha", birth.toString()));
  }
}
