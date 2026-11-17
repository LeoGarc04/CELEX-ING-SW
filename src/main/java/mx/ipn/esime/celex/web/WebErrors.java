package mx.ipn.esime.celex.web;

import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.mail.MailException;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;

@ControllerAdvice
public class WebErrors {
  @ExceptionHandler(IllegalArgumentException.class)
  String bad(IllegalArgumentException e, Model m) {
    m.addAttribute("message", e.getMessage());
    return "error";
  }

  @ExceptionHandler(DataIntegrityViolationException.class)
  String duplicate(Model m) {
    m.addAttribute(
        "message",
        "Los datos ya están registrados o no cumplen las restricciones. Revisa e intenta de"
            + " nuevo.");
    return "error";
  }

  @ExceptionHandler(org.springframework.web.multipart.MaxUploadSizeExceededException.class)
  String upload(Model m) {
    m.addAttribute(
        "message",
        "Cada archivo debe ocupar como máximo 2 MB. Reduce su tamaño y vuelve a enviarlo.");
    return "error";
  }

  @ExceptionHandler(MailException.class)
  String mail(Model m) {
    m.addAttribute("message", "No se pudo enviar el correo. Intenta más tarde o contacta a CELEX.");
    return "error";
  }
}
