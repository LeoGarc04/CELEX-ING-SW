package mx.ipn.esime.celex.service;

import java.math.BigDecimal;
import java.time.*;
import java.util.*;
import java.util.List;
import java.io.ByteArrayOutputStream;
import java.awt.Color;
import com.lowagie.text.*;
import mx.ipn.esime.celex.repository.Db;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.security.access.AccessDeniedException;

@Service
public class PlacementService {
  private final Db db;
  private final CallSettings settings;
  private final AcademicRoutes routes;
  private final QueueService queue;
  private final FeePolicy fees;
  private final ScholarshipService scholarships;
  private final AdminService admin;
  private final MailService mail;
  private final Clock clock;
  private final String publicUrl;

  public PlacementService(Db db,CallSettings settings,AcademicRoutes routes,QueueService queue,
      FeePolicy fees,ScholarshipService scholarships,AdminService admin,MailService mail,Clock clock,
      @org.springframework.beans.factory.annotation.Value("${celex.public-url}") String publicUrl) {
    this.db=db;this.settings=settings;this.routes=routes;this.queue=queue;this.fees=fees;
    this.scholarships=scholarships;this.admin=admin;this.mail=mail;this.clock=clock;this.publicUrl=publicUrl;
  }

  private Map<String,Object> exam(int id) {
    return db.one("SELECT s.*,g.id_convocatoria,g.id_idioma,g.nivel,u.nombre_completo,u.tipo_usuario,u.correo,i.nombre AS idioma FROM solicitudes_inscripcion s JOIN grupos g ON g.id_grupo=s.id_grupo JOIN usuarios u ON u.id_usuario=s.id_usuario JOIN idiomas i ON i.id_idioma=g.id_idioma WHERE s.id_solicitud=? AND g.nivel='Colocación'",id);
  }

  private Map<String,Object> owned(int id,int user) {
    var e=exam(id);
    if(Db.number(e,"id_usuario")!=user)throw new AccessDeniedException("Solo puedes consultar tu propio examen.");
    return e;
  }

  private Map<String,Object> lock(int id) {
    var e=exam(id);queue.lock(Db.number(e,"id_convocatoria"));
    db.one("SELECT id_solicitud FROM solicitudes_inscripcion WHERE id_solicitud=? FOR UPDATE",id);
    return exam(id);
  }

  public Map<String,Object> result(int id) {return settings.read("Resultado colocación · "+id);}

  private Integer course(int id) {
    Object value=settings.read("Curso de colocación · "+id).get("solicitud");
    return value instanceof Number n?n.intValue():null;
  }

  public Map<String,Object> view(int id,int user) {
    var e=owned(id,user);var v=new LinkedHashMap<String,Object>();var result=result(id);
    v.put("resultado",result);v.put("niveles",routes.examLevels(Db.number(e,"id_idioma")));
    v.put("nivelSolicitado",Objects.toString(settings.preference(id).get("nivelSolicitado"),""));
    Integer child=course(id);
    v.put("curso",child!=null && !"Cancelada".equals(db.one("SELECT estado_inscripcion FROM solicitudes_inscripcion WHERE id_solicitud=?",child).get("estado_inscripcion"))?child:null);
    v.put("cuotaCurso",fees.calculate((String)e.get("tipo_usuario"),scholarships.status(user,Db.number(e,"id_convocatoria"))));
    v.put("grupos",result.isEmpty()?List.of():db.rows("SELECT id_grupo,modalidad,horario,dias,cupo_disponible FROM grupos WHERE id_convocatoria=? AND id_idioma=? AND nivel=? AND estado='Activo' AND cupo_disponible>0 ORDER BY modalidad,horario",e.get("id_convocatoria"),e.get("id_idioma"),result.get("nivelAsignado")));
    return v;
  }

  @Transactional
  public void chooseLevel(int user,int id,String level) {
    var e=lock(id);owned(id,user);
    if(!List.of("Pendiente","Pendiente de revisión","En proceso","Confirmada").contains(e.get("estado_inscripcion")) || !result(id).isEmpty())
      throw new IllegalArgumentException("El examen ya no admite cambiar el nivel solicitado.");
    if(!routes.examLevels(Db.number(e,"id_idioma")).contains(level))throw new IllegalArgumentException("Selecciona un nivel válido del idioma.");
    var preference=settings.preference(id);
    Object chosen=preference.get("nivelSolicitado");
    if(chosen!=null && !chosen.equals(level))throw new IllegalArgumentException("El nivel solicitado ya quedó registrado. Contacta a CELEX si necesitas corregirlo.");
    if(chosen==null){
      preference.put("nivelSolicitado",level);preference.put("via","Colocación");
      settings.write(user,"Preferencia inscripción · "+id,preference);
    }
  }

  @Transactional
  public void publish(int actor,int id,String outcome,String level,String note) {
    var e=lock(id);
    if(!"Confirmada".equals(e.get("estado_inscripcion")))throw new IllegalArgumentException("Valida el pago y la cita del examen antes de publicar su resultado.");
    String requested=Objects.toString(settings.preference(id).get("nivelSolicitado"),"");
    if(requested.isBlank())throw new IllegalArgumentException("El alumno debe registrar el nivel para el que solicitó el examen.");
    if(!Set.of("Aprobado","No aprobado","Nivel inicial").contains(outcome) || !routes.examLevels(Db.number(e,"id_idioma")).contains(level) || note==null || note.length()>4000)
      throw new IllegalArgumentException("Revisa resultado, nivel asignado y observaciones.");
    Integer child=course(id);
    if(child!=null && !"Cancelada".equals(db.one("SELECT estado_inscripcion FROM solicitudes_inscripcion WHERE id_solicitud=?",child).get("estado_inscripcion")))
      throw new IllegalArgumentException("Ya existe una solicitud de curso derivada. Revisa ese expediente antes de cambiar el resultado.");
    var value=new LinkedHashMap<String,Object>();value.put("resultado",outcome);value.put("nivelSolicitado",requested);
    value.put("nivelAsignado",level);value.put("observaciones",note);value.put("fecha",LocalDateTime.now(clock).toString());value.put("registradoPor",actor);
    settings.write(actor,"Resultado colocación · "+id,value);
    admin.history(actor,Db.number(e,"id_usuario"),Db.number(e,"id_idioma"),level,null,0,false);
    mail.send((String)e.get("correo"),"CELEX · Resultado de examen de colocación",
        "Folio: "+e.get("folio")+"\nResultado: "+outcome+"\nNivel solicitado: "+requested
        +"\nNivel asignado por CELEX: "+level+"\n\nObservaciones\n"+note
        +"\n\nSiguiente paso\nConsulta tu resultado y elige modalidad y horario entre los grupos publicados con cupo:\n"
        +publicUrl+"/solicitud/"+id
        +"\n\nEl pago del examen es independiente del pago del curso. Continúa al pago únicamente después de elegir una opción disponible.");
  }

  @Transactional
  public int enroll(int user,int id,String modality,String time) {
    var e=lock(id);owned(id,user);var r=result(id);
    if(!"Confirmada".equals(e.get("estado_inscripcion")) || r.isEmpty())throw new IllegalArgumentException("Espera a que CELEX publique tu resultado.");
    Integer previous=course(id);
    if(previous!=null && !"Cancelada".equals(db.one("SELECT estado_inscripcion FROM solicitudes_inscripcion WHERE id_solicitud=?",previous).get("estado_inscripcion")))return previous;
    var candidates=db.rows("SELECT g.* FROM grupos g JOIN idiomas i ON i.id_idioma=g.id_idioma WHERE g.id_convocatoria=? AND g.id_idioma=? AND g.nivel=? AND g.modalidad=? AND g.horario=? AND g.estado='Activo' AND i.activo=TRUE AND g.cupo_disponible>0 ORDER BY g.id_grupo FOR UPDATE",e.get("id_convocatoria"),e.get("id_idioma"),r.get("nivelAsignado"),modality,time);
    if(candidates.isEmpty())throw new IllegalArgumentException("No hay cupo para el nivel y preferencia seleccionados. Consulta la oferta publicada por CELEX.");
    int group=Db.number(candidates.get(0),"id_grupo");
    if(db.update("UPDATE grupos SET cupo_disponible=cupo_disponible-1 WHERE id_grupo=? AND cupo_disponible>0",group)!=1)
      throw new IllegalArgumentException("El último lugar ya fue reservado. Elige otro horario.");
    String folio=null;
    for(int n=0;n<8;n++){
      folio=queue.newFolio();
      try {db.update("INSERT INTO fila_virtual(folio,id_usuario,id_convocatoria,posicion_actual,nivel_prioridad_calculado,estado_turno,fecha_ingreso) VALUES(?,?,?,0,1,'Inscrito',?)",folio,user,e.get("id_convocatoria"),LocalDateTime.now(clock));break;}
      catch(org.springframework.dao.DuplicateKeyException collision){if(n==7)throw collision;}
    }
    String scholarship=scholarships.status(user,Db.number(e,"id_convocatoria"));
    BigDecimal fee=fees.calculate((String)e.get("tipo_usuario"),scholarship);
    int child=db.id("INSERT INTO solicitudes_inscripcion(folio,id_usuario,id_grupo,estado_inscripcion,monto_calculado,estado_beca) VALUES(?,?,?,'Pendiente',?,?)",folio,user,group,fee,scholarship);
    var pref=new LinkedHashMap<String,Object>();pref.put("version",3);pref.put("via","Curso por colocación");pref.put("nivel",r.get("nivelAsignado"));pref.put("idioma",e.get("id_idioma"));pref.put("modalidad",modality);pref.put("horario",time);pref.put("convocatoria",e.get("id_convocatoria"));pref.put("origenExamen",id);
    settings.write(user,"Preferencia inscripción · "+child,pref);
    settings.write(user,"Curso de colocación · "+id,Map.of("solicitud",child));
    return child;
  }

  public List<Map<String,Object>> exams() {
    var rows=db.rows("SELECT s.id_solicitud,s.folio,s.id_usuario,u.nombre_completo,g.id_idioma,i.nombre AS idioma,g.profesor,g.dias,g.horario FROM solicitudes_inscripcion s JOIN grupos g ON g.id_grupo=s.id_grupo JOIN usuarios u ON u.id_usuario=s.id_usuario JOIN idiomas i ON i.id_idioma=g.id_idioma WHERE g.nivel='Colocación' AND s.estado_inscripcion='Confirmada' ORDER BY s.id_solicitud DESC");
    for(var e:rows){int id=Db.number(e,"id_solicitud");e.put("nivelSolicitado",Objects.toString(settings.preference(id).get("nivelSolicitado"),"Pendiente de selección por el alumno"));e.put("nivelPendiente",!settings.preference(id).containsKey("nivelSolicitado"));e.put("resultado",result(id));e.put("niveles",routes.examLevels(Db.number(e,"id_idioma")));e.put("curso",course(id));}
    return rows;
  }

  public byte[] resultPdf(int id,int user) throws Exception {
    var e=owned(id,user);var r=result(id);if(r.isEmpty())throw new IllegalArgumentException("Aún no se publica el resultado del examen.");
    var out=new ByteArrayOutputStream();var doc=new Document(PageSize.A4,50,50,50,50);com.lowagie.text.pdf.PdfWriter.getInstance(doc,out);doc.open();
    Color green=new Color(0,107,87);doc.add(new Paragraph("INSTITUTO POLITÉCNICO NACIONAL",new Font(Font.HELVETICA,18,Font.BOLD,green)));
    doc.add(new Paragraph("ESIME Culhuacán · CELEX",new Font(Font.HELVETICA,14,Font.BOLD,green)));doc.add(new Paragraph("Comprobante de resultado de examen de colocación",new Font(Font.HELVETICA,15,Font.BOLD)));doc.add(Chunk.NEWLINE);
    for(var item:new String[][]{{"Folio",Objects.toString(e.get("folio"))},{"Alumno",Objects.toString(e.get("nombre_completo"))},{"Idioma",Objects.toString(e.get("idioma"))},{"Nivel solicitado por el alumno",Objects.toString(r.get("nivelSolicitado"))},{"Resultado",Objects.toString(r.get("resultado"))},{"Nivel asignado por CELEX",Objects.toString(r.get("nivelAsignado"))},{"Observaciones",Objects.toString(r.get("observaciones"))}})doc.add(new Paragraph(item[0]+": "+item[1]));
    doc.add(Chunk.NEWLINE);doc.add(new Paragraph("Continúa con tu inscripción al curso",new Font(Font.HELVETICA,14,Font.BOLD,green)));
    doc.add(new Paragraph("Cuota actual del curso: $"+fees.calculate((String)e.get("tipo_usuario"),scholarships.status(user,Db.number(e,"id_convocatoria")))+" MXN."));
    doc.add(new Paragraph("Elige modalidad y horario entre las opciones del nivel asignado. La cuota del curso es independiente de los $150.00 del examen. Consulta el importe de tu solicitud y la guía de pago, adjunta el comprobante y revisa su estado. CELEX confirmará grupo, profesor e inicio de clases después de validar el pago."));doc.close();return out.toByteArray();
  }
}
