package mx.ipn.esime.celex.web;

import mx.ipn.esime.celex.repository.Db;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;

@RestController
public class ReadinessController {
  private final Db db;

  public ReadinessController(Db db) {
    this.db = db;
  }

  @GetMapping("/salud")
  ResponseEntity<String> health() {
    try {
      db.one("SELECT 1 AS ready");
      return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body("CELEX disponible");
    } catch (Exception e) {
      return ResponseEntity.status(503).body("CELEX temporalmente no disponible");
    }
  }
}
