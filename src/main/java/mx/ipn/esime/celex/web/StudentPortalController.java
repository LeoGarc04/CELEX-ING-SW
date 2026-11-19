package mx.ipn.esime.celex.web;

import mx.ipn.esime.celex.service.StudentPortalService;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;

@Controller
public class StudentPortalController {
  private final StudentPortalService students;
  private final PortalController portal;

  public StudentPortalController(StudentPortalService students, PortalController portal) {
    this.students = students;
    this.portal = portal;
  }

  @GetMapping("/calendario")
  String calendar(Authentication a, @RequestParam(defaultValue = "") String mes, Model m) {
    int user = portal.user(a);
    m.addAttribute("calendar", students.calendar(user, mes));
    m.addAttribute("schedule", students.schedule(user));
    return "calendario";
  }

  @GetMapping("/mapa-curricular")
  @PreAuthorize("hasAnyAuthority('USUARIO','ADMIN')")
  String curriculum(Authentication a, Model m, @RequestParam(required = false) Integer idioma) {
    m.addAttribute("languages", students.languages());
    m.addAttribute(
        "curriculum",
        idioma == null
            ? students.curriculum(portal.user(a))
            : students.curriculum(portal.user(a), idioma));
    return "mapa-curricular";
  }

  @GetMapping("/pagos")
  @PreAuthorize("hasAnyAuthority('USUARIO','ADMIN')")
  String payments(Authentication a, Model m) {
    m.addAttribute("payments", students.payments(portal.user(a)));
    return "pagos";
  }
}
