package mx.ipn.esime.celex.web;

import jakarta.servlet.http.*;
import jakarta.validation.Valid;
import java.util.*;
import mx.ipn.esime.celex.config.DatabaseAuthenticationProvider;
import mx.ipn.esime.celex.service.ProfileService;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.*;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.validation.BindingResult;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

@Controller
@RequestMapping("/perfil")
public class ProfileController {
  private final ProfileService profiles;
  private final PortalController portal;

  public ProfileController(ProfileService profiles, PortalController portal) {
    this.profiles = profiles;
    this.portal = portal;
  }

  private void page(Authentication a, Model m) {
    var account = profiles.get(portal.user(a));
    m.addAttribute("account", account);
    if (!m.containsAttribute("details"))
      m.addAttribute(
          "details",
          new ProfileDetails(
              (String) account.get("nombre_completo"), (String) account.get("telefono")));
  }

  @GetMapping
  String profile(Authentication a, Model m) {
    page(a, m);
    return "perfil";
  }

  @PostMapping
  String update(
      Authentication a,
      @Valid @ModelAttribute("details") ProfileDetails details,
      BindingResult errors,
      Model m,
      RedirectAttributes flash) {
    if (errors.hasErrors()) {
      page(a, m);
      return "perfil";
    }
    try {
      profiles.update(portal.user(a), details.nombre(), details.telefono());
    } catch (IllegalArgumentException e) {
      errors.reject("perfil", e.getMessage());
      page(a, m);
      return "perfil";
    }
    flash.addFlashAttribute("success", "Tus datos se actualizaron.");
    return "redirect:/perfil";
  }

  @PostMapping("/contrasena")
  String password(
      Authentication a,
      @Valid @ModelAttribute("password") ProfilePassword form,
      BindingResult errors,
      RedirectAttributes flash,
      HttpServletRequest request,
      HttpServletResponse response) {
    if (errors.hasErrors()) {
      flash.addFlashAttribute(
          "passwordErrors",
          errors.getAllErrors().stream()
              .map(x -> Objects.toString(x.getDefaultMessage(), "Revisa la contraseña"))
              .toList());
      return "redirect:/perfil#contrasena";
    }
    String hash;
    try {
      hash = profiles.password(portal.user(a), form.actual(), form.nueva(), form.confirmar());
    } catch (AuthenticationException | IllegalArgumentException e) {
      flash.addFlashAttribute("passwordErrors", List.of(e.getMessage()));
      return "redirect:/perfil#contrasena";
    }
    var auth =
        UsernamePasswordAuthenticationToken.authenticated(
            a.getPrincipal(), null, a.getAuthorities());
    auth.setDetails(DatabaseAuthenticationProvider.passwordVersion(hash));
    var context = SecurityContextHolder.createEmptyContext();
    context.setAuthentication(auth);
    SecurityContextHolder.setContext(context);
    if (request.getSession(false) != null) request.changeSessionId();
    new HttpSessionSecurityContextRepository().saveContext(context, request, response);
    flash.addFlashAttribute(
        "passwordSuccess",
        "Contraseña actualizada. Las demás sesiones deberán iniciar sesión de nuevo.");
    return "redirect:/perfil#contrasena";
  }
}
