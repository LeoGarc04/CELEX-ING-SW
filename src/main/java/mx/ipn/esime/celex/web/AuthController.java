package mx.ipn.esime.celex.web;

import jakarta.validation.Valid;
import mx.ipn.esime.celex.service.AccountService;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.validation.BindingResult;
import org.springframework.web.bind.annotation.*;

@Controller
public class AuthController {
  private final AccountService accounts;

  public AuthController(AccountService accounts) {
    this.accounts = accounts;
  }

  @GetMapping("/")
  String home() {
    return "redirect:/panel";
  }

  @GetMapping("/login")
  String login() {
    return "login";
  }

  @GetMapping("/registro")
  String register(Model m, jakarta.servlet.http.HttpSession session) {
    if (session.getAttribute("verification.email") instanceof String email) {
      m.addAttribute("correo", email);
      session.setAttribute("verification.origin", "registro");
      return verificationView(m, session);
    }
    m.addAttribute("registration", new Registration("", "", "", "", "", "", ""));
    return "registro";
  }

  @PostMapping("/registro")
  String register(
      @Valid @ModelAttribute("registration") Registration r,
      BindingResult errors,
      Model m,
      jakarta.servlet.http.HttpSession session,
      org.springframework.web.servlet.mvc.support.RedirectAttributes flash) {
    if (errors.hasErrors()) return "registro";
    try {
      accounts.register(r);
    } catch (IllegalArgumentException e) {
      errors.reject("registro", e.getMessage());
      return "registro";
    } catch (org.springframework.dao.DataIntegrityViolationException e) {
      errors.reject("registro", "El correo, la CURP o la boleta ya están registrados.");
      return "registro";
    }
    String email = AccountService.normalize(r.correo());
    session.setAttribute("verification.email", email);
    session.setAttribute("verification.origin", "registro");
    flash.addFlashAttribute("message", "Enviamos tu código de activación. Ingresa los seis dígitos para activar tu cuenta.");
    return "redirect:/registro";
  }

  @GetMapping("/verificar")
  String verify(Model m, jakarta.servlet.http.HttpSession session) {
    if (!m.containsAttribute("correo")) {
      Object email = session.getAttribute("verification.email");
      if (email instanceof String) m.addAttribute("correo", email);
    }
    return verificationView(m, session);
  }

  @PostMapping("/verificar")
  String verify(
      @RequestParam String correo,
      @RequestParam String codigo,
      Model m,
      jakarta.servlet.http.HttpSession session) {
    m.addAttribute("correo", correo);
    session.setAttribute("verification.email", AccountService.normalize(correo));
    try {
      accounts.verify(correo, codigo.trim().toUpperCase(java.util.Locale.ROOT), null);
    } catch (IllegalArgumentException e) {
      m.addAttribute(
          "error",
          "El código no es válido o venció. Revisa el correo y el código, o solicita uno nuevo.");
      return verificationView(m, session);
    }
    session.removeAttribute("verification.email");
    session.removeAttribute("verification.origin");
    m.addAttribute("message", "Cuenta activada. Ya puedes iniciar sesión.");
    return "login";
  }

  @PostMapping("/reenviar")
  String resend(@RequestParam String correo, Model m, jakarta.servlet.http.HttpSession session) {
    m.addAttribute("correo", correo);
    session.setAttribute("verification.email", AccountService.normalize(correo));
    try {
      accounts.requestActivation(correo);
    } catch (org.springframework.mail.MailException e) {
      m.addAttribute(
          "error",
          "No se pudo enviar el correo. Espera un momento y vuelve a solicitar el código.");
      return verificationView(m, session);
    } catch (IllegalArgumentException e) {
      m.addAttribute("error", e.getMessage());
      return verificationView(m, session);
    }
    m.addAttribute(
        "message",
        "Si la cuenta está pendiente y pasó un minuto desde el último envío, recibirás un nuevo"
            + " código.");
    return verificationView(m, session);
  }

  private String verificationView(Model m, jakarta.servlet.http.HttpSession session) {
    if ("registro".equals(session.getAttribute("verification.origin"))) {
      m.addAttribute("registered", true);
      m.addAttribute("registration", new Registration("", "", "", "", "", "", ""));
      return "registro";
    }
    return "verificar";
  }

  @GetMapping("/recuperar")
  String recover() {
    return "recuperar";
  }

  @PostMapping("/recuperar")
  String recover(@RequestParam String correo, Model m) {
    accounts.requestReset(correo);
    m.addAttribute("message", "Si la cuenta está activa, recibirás un código por correo.");
    m.addAttribute("correo", correo);
    return "restablecer";
  }

  @GetMapping("/restablecer")
  String reset() {
    return "restablecer";
  }

  @PostMapping("/restablecer")
  String reset(
      @RequestParam String correo,
      @RequestParam String codigo,
      @RequestParam String password,
      Model m) {
    accounts.verify(correo, codigo, password);
    m.addAttribute("message", "Contraseña actualizada.");
    return "login";
  }
}
