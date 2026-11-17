package mx.ipn.esime.celex.service;

import java.time.*;
import java.util.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

@Service
public class BusinessCalendar {
  private final Set<LocalDate> holidays;

  public BusinessCalendar(@Value("${celex.holidays:}") String dates) {
    holidays = new HashSet<>();
    for (String date : dates.split(","))
      if (!date.isBlank()) holidays.add(LocalDate.parse(date.trim()));
  }

  public LocalDateTime correctionDeadline(LocalDateTime start) {
    var t = start;
    int days = 0;
    while (days < 2) {
      t = t.plusDays(1);
      if (t.getDayOfWeek() != DayOfWeek.SATURDAY
          && t.getDayOfWeek() != DayOfWeek.SUNDAY
          && !holidays.contains(t.toLocalDate())) days++;
    }
    return t;
  }
}
