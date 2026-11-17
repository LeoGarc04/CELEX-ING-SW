package mx.ipn.esime.celex;

import static org.junit.jupiter.api.Assertions.*;

import java.math.BigDecimal;
import java.time.*;
import java.util.*;
import mx.ipn.esime.celex.service.*;
import org.junit.jupiter.api.Test;

class RulesTest {
  @Test
  void priorityOrdering() {
    int scholar = Rules.priority(true, 1, false, BigDecimal.ZERO, false),
        high = Rules.priority(true, 100, false, BigDecimal.ZERO, false),
        external = Rules.priority(false, 0, true, new BigDecimal("9.01"), false),
        newcomer = Rules.priority(false, 0, false, BigDecimal.ZERO, true),
        rest = Rules.priority(false, 0, true, new BigDecimal("9"), false);
    assertTrue(high == scholar && scholar > external && external > newcomer && newcomer > rest);
  }

  @Test
  void eligibility() {
    var h = new HashMap<String, Object>();
    h.put("requiere_examen_colocacion", false);
    h.put("periodos_inactivo", 1);
    h.put("nivel_actual", "Básico 1");
    assertFalse(Rules.eligible(h));
    h.put("nivel_actual", "Intermedio 1");
    assertTrue(Rules.eligible(h));
    h.put("periodos_inactivo", 2);
    assertFalse(Rules.eligible(h));
    h.put("calificacion", 79);
    h.put("periodos_inactivo", 1);
    assertFalse(Rules.eligible(h));
    h.put("periodos_inactivo", 0);
    assertTrue(Rules.eligible(h));
  }

  @Test
  void businessDays() {
    assertEquals(
        LocalDateTime.of(2026, 11, 17, 14, 0),
        Rules.twoBusinessDays(LocalDateTime.of(2026, 11, 13, 14, 0)));
  }

  @Test
  void scheduleBoundaries() {
    assertTrue(Rules.overlaps("1,3", "09:00-11:00", "3", "10:00-12:00"));
    assertFalse(Rules.overlaps("1", "09:00-11:00", "1", "11:00-12:00"));
    assertFalse(Rules.overlaps("1", "09:00-11:00", "2", "09:00-11:00"));
    assertThrows(IllegalArgumentException.class, () -> Rules.interval("12:00-09:00"));
  }

  @Test
  void rejectFakeUpload() {
    assertThrows(
        IllegalArgumentException.class,
        () -> PrivateStorage.format("<script>alert(1)</script>".getBytes()));
  }
}
