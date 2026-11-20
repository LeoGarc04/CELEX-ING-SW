package mx.ipn.esime.celex.web;
import mx.ipn.esime.celex.service.*;
import org.springframework.stereotype.Controller;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;
import org.springframework.http.*;
@Controller
public class PlacementController {
  private final PlacementService placement;private final PortalController portal;
  public PlacementController(PlacementService placement,PortalController portal){this.placement=placement;this.portal=portal;}
  @PostMapping("/solicitud/{id}/nivel-solicitado")
  @PreAuthorize("hasAnyAuthority('USUARIO','ADMIN')")
  String choose(Authentication a,@PathVariable int id,@RequestParam String nivelExamen){placement.chooseLevel(portal.user(a),id,nivelExamen);return "redirect:/solicitud/"+id;}
  @PostMapping("/solicitud/{id}/curso-colocacion")
  @PreAuthorize("hasAnyAuthority('USUARIO','ADMIN')")
  String enroll(Authentication a,@PathVariable int id,@RequestParam String modalidad,@RequestParam String horario){return "redirect:/solicitud/"+placement.enroll(portal.user(a),id,modalidad,horario);}
  @GetMapping("/solicitud/{id}/resultado-colocacion")
  @PreAuthorize("hasAnyAuthority('USUARIO','ADMIN')")
  ResponseEntity<byte[]> pdf(Authentication a,@PathVariable int id)throws Exception{return ResponseEntity.ok().contentType(MediaType.APPLICATION_PDF).cacheControl(CacheControl.noStore()).header("Content-Disposition","inline; filename=resultado-colocacion.pdf").body(placement.resultPdf(id,portal.user(a)));}
  @GetMapping("/admin/examenes")
  @PreAuthorize("hasAuthority('ACADEMICO')")
  String exams(Model m){m.addAttribute("exams",placement.exams());return "examenes-admin";}
  @PostMapping("/admin/solicitudes/{id}/resultado-examen")
  @PreAuthorize("hasAuthority('ACADEMICO')")
  String result(Authentication a,@PathVariable int id,@RequestParam String resultado,@RequestParam String nivelAsignado,@RequestParam(defaultValue="")String observaciones,RedirectAttributes flash){placement.publish(portal.user(a),id,resultado,nivelAsignado,observaciones);flash.addFlashAttribute("success","Resultado publicado y notificado al alumno.");return "redirect:/admin/examenes";}
}
