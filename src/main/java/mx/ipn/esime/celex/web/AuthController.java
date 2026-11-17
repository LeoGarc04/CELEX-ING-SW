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
  String register(Model m) {
    m.addAttribute("registration", new Registration("", "", "", "", ""));
    return "registro";
  }

  @PostMapping("/registro")
  String register(
      @Valid @ModelAttribute("registration") Registration r, BindingResult errors, Model m) {
    if (errors.hasErrors()) return "registro";
    accounts.register(r);
    m.addAttribute("correo", r.correo());
    m.addAttribute("message", "Enviamos tu código de activación.");
    return "verificar";
  }

  @GetMapping("/verificar")
  String verify() {
    return "verificar";
  }

  @PostMapping("/verificar")
  String verify(@RequestParam String correo, @RequestParam String codigo, Model m) {
    accounts.verify(correo, codigo, null);
    m.addAttribute("message", "Cuenta activada. Ya puedes iniciar sesión.");
    return "login";
  }

  @PostMapping("/reenviar")
  String resend(@RequestParam String correo, Model m) {
    accounts.requestActivation(correo);
    m.addAttribute("correo", correo);
    m.addAttribute(
        "message",
        "Si la cuenta está pendiente y pasó un minuto desde el último envío, recibirás un nuevo"
            + " código.");
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
