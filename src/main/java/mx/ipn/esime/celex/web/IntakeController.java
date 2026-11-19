package mx.ipn.esime.celex.web;

import java.time.*;
import java.util.*;
import mx.ipn.esime.celex.service.*;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

@Controller
public class IntakeController {
  private final PortalController portal;
  private final IntakeService intake;
  private final CallManagement calls;
  private final CallSettings settings;
  private final AdministrativeExtras extras;
  private final PrivateStorage storage;

  public IntakeController(
      PortalController portal,
      IntakeService intake,
      CallManagement calls,
      CallSettings settings,
      AdministrativeExtras extras,
      PrivateStorage storage) {
    this.portal = portal;
    this.intake = intake;
    this.calls = calls;
    this.settings = settings;
    this.extras = extras;
    this.storage = storage;
  }

  @PostMapping("/fila/{folio}/preferencia")
  @PreAuthorize("hasAnyAuthority('USUARIO','ADMIN')")
  String request(
      Authentication a,
      @PathVariable String folio,
      @RequestParam int idioma,
      @RequestParam String via,
      @RequestParam(defaultValue = "") String modalidad,
      @RequestParam(defaultValue = "") String horario,
      jakarta.servlet.http.HttpSession session) {
    int id = intake.request(portal.user(a), folio, idioma, via, modalidad, horario);
    session.removeAttribute("queue.folio");
    return "redirect:/solicitud/" + id;
  }

  @PostMapping("/admin/convocatorias/{id}/habilitar")
  @PreAuthorize("hasAuthority('CONFIGURACION')")
  String enable(Authentication a, @PathVariable int id) {
    calls.enable(portal.user(a), id);
    return "redirect:/admin#convocatorias";
  }

  @PostMapping("/admin/convocatorias/{id}/clases")
  @PreAuthorize("hasAuthority('CONFIGURACION')")
  String classes(
      Authentication a,
      @PathVariable int id,
      @RequestParam LocalDateTime inicioClases,
      @RequestParam int cupoExamen) {
    settings.configure(portal.user(a), id, inicioClases, cupoExamen);
    return "redirect:/admin#convocatorias";
  }

  @PostMapping("/admin/contacto")
  @PreAuthorize("hasAuthority('CONFIGURACION')")
  String contact(Authentication a, @RequestParam String telefono, @RequestParam String correo) {
    if (telefono.length() > 40
        || correo.length() > 100
        || !telefono.isBlank() && !telefono.matches("[+0-9 ()-]{7,40}")
        || !correo.isBlank() && !correo.matches("[^\\s@]+@[^\\s@]+\\.[^\\s@]+")
        || telefono.isBlank() && correo.isBlank())
      throw new IllegalArgumentException(
          "Ingresa al menos un teléfono o correo de contacto válido.");
    settings.write(
        portal.user(a), "Contacto CELEX", Map.of("telefono", telefono, "correo", correo));
    return "redirect:/admin#convocatorias";
  }

  @PostMapping("/admin/solicitudes/{id}/asignar")
  @PreAuthorize("hasAuthority('FINANCIERO')")
  String assign(Authentication a, @PathVariable int id, @RequestParam int grupo) {
    intake.assign(portal.user(a), id, grupo);
    return "redirect:/admin/solicitudes/" + id;
  }

  @GetMapping("/tramites")
  @PreAuthorize("hasAnyAuthority('USUARIO','ADMIN')")
  String procedures(Authentication a, Model m) {
    m.addAttribute("fees", extras.fees(portal.user(a)));
    m.addAttribute("procedures", extras.requests(portal.user(a)));
    return "tramites";
  }

  @PostMapping("/tramites")
  @PreAuthorize("hasAnyAuthority('USUARIO','ADMIN')")
  String procedure(
      Authentication a,
      @RequestParam String tipo,
      @RequestParam String referencia,
      @RequestParam LocalDate fecha,
      @RequestParam(required = false) MultipartFile pago,
      RedirectAttributes flash)
      throws Exception {
    extras.submit(portal.user(a), tipo, referencia, fecha, pago);
    flash.addFlashAttribute("success", "Solicitud de trámite enviada a revisión.");
    return "redirect:/tramites";
  }

  @GetMapping("/admin/tramites")
  @PreAuthorize("hasAuthority('FINANCIERO')")
  String reviews(Model m) {
    m.addAttribute("procedures", extras.requests(null));
    return "tramites-admin";
  }

  @PostMapping("/admin/tramites/{id}")
  @PreAuthorize("hasAuthority('FINANCIERO')")
  String review(
      Authentication a,
      @PathVariable int id,
      @RequestParam String estado,
      @RequestParam(defaultValue = "") String observaciones) {
    extras.review(portal.user(a), id, estado, observaciones);
    return "redirect:/admin/tramites";
  }

  @GetMapping("/tramites/{id}/comprobante")
  @PreAuthorize("hasAnyAuthority('USUARIO','ADMIN')")
  org.springframework.http.ResponseEntity<byte[]> file(Authentication a, @PathVariable int id)
      throws Exception {
    boolean finance =
        a.getAuthorities().stream().anyMatch(x -> x.getAuthority().equals("FINANCIERO"));
    var value = extras.request(id, portal.user(a), finance);
    String key = (String) value.get("archivo");
    if (key.isBlank()) throw new IllegalArgumentException("Este trámite no requiere comprobante.");
    return org.springframework.http.ResponseEntity.ok()
        .header("Cache-Control", "no-store")
        .contentType(
            org.springframework.http.MediaType.parseMediaType(
                key.endsWith("pdf")
                    ? "application/pdf"
                    : key.endsWith("png") ? "image/png" : "image/jpeg"))
        .body(storage.read(key));
  }

  @PostMapping("/admin/bitacora/eliminar")
  @PreAuthorize("hasAuthority('AUDITORIA')")
  String clear(
      Authentication a, @RequestParam List<Integer> actividades, RedirectAttributes flash) {
    extras.clearActivities(portal.user(a), actividades);
    flash.addFlashAttribute(
        "success", "Actividades de acceso eliminadas. Los movimientos críticos se conservaron.");
    return "redirect:/admin#seguridad";
  }
}
