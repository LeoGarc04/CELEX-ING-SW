package mx.ipn.esime.celex.web;

import mx.ipn.esime.celex.service.TeacherService;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

@Controller
@RequestMapping("/profesor")
@PreAuthorize("hasAuthority('PROFESOR')")
public class TeacherController {
  private final TeacherService teachers;
  private final PortalController portal;

  public TeacherController(TeacherService teachers, PortalController portal) {
    this.teachers = teachers;
    this.portal = portal;
  }

  @GetMapping
  String home(Authentication a, Model m) {
    m.addAttribute("groups", teachers.groups(portal.user(a)));
    return "profesor";
  }

  @GetMapping("/grupos/{id}")
  String group(Authentication a, @PathVariable int id, Model m) {
    m.addAttribute("group", teachers.group(portal.user(a), id));
    m.addAttribute("students", teachers.students(portal.user(a), id));
    return "calificaciones";
  }

  @PostMapping("/grupos/{id}/calificaciones")
  String grade(
      Authentication a,
      @PathVariable int id,
      @RequestParam int alumno,
      @RequestParam int calificacion,
      RedirectAttributes flash) {
    flash.addFlashAttribute("success", teachers.grade(portal.user(a), id, alumno, calificacion));
    return "redirect:/profesor/grupos/" + id;
  }
}
