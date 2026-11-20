package mx.ipn.esime.celex.service;

import com.google.zxing.*;
import com.google.zxing.client.j2se.MatrixToImageWriter;
import com.google.zxing.qrcode.QRCodeWriter;
import com.lowagie.text.*;
import com.lowagie.text.pdf.*;
import java.awt.Color;
import java.io.*;
import java.util.*;
import mx.ipn.esime.celex.repository.Db;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class ReceiptService {
  private final Db db;
  private final PrivateStorage storage;
  private final String publicUrl;
  @org.springframework.beans.factory.annotation.Autowired private CallSettings settings;

  public ReceiptService(Db db,PrivateStorage storage,@Value("${celex.public-url}") String url) {
    this.db=db;this.storage=storage;publicUrl=url;
  }

  @Transactional
  public void refreshForOwner(int id,int user) throws Exception {
    if(db.rows("SELECT id_solicitud FROM solicitudes_inscripcion WHERE id_solicitud=? AND id_usuario=? AND estado_inscripcion='Confirmada' FOR UPDATE",id,user).isEmpty())
      throw new org.springframework.security.access.AccessDeniedException("Comprobante no disponible para esta cuenta.");
    issue(id);
  }

  @Transactional
  public void issue(int id) throws Exception {
    db.one("SELECT id_solicitud FROM solicitudes_inscripcion WHERE id_solicitud=? FOR UPDATE",id);
    var s=db.one("SELECT s.*,u.nombre_completo,g.id_convocatoria,g.nivel,g.modalidad,g.profesor,g.aula,g.dias,g.horario,i.nombre AS idioma FROM solicitudes_inscripcion s JOIN usuarios u ON u.id_usuario=s.id_usuario JOIN grupos g ON g.id_grupo=s.id_grupo JOIN idiomas i ON i.id_idioma=g.id_idioma WHERE s.id_solicitud=? AND s.estado_inscripcion='Confirmada'",id);
    String requested=Objects.toString(settings.preference(id).get("nivelSolicitado"),"");
    if("Colocación".equals(s.get("nivel")) && requested.isBlank())
      throw new IllegalArgumentException("El alumno debe confirmar el nivel solicitado del examen antes de generar su comprobante.");
    s.put("nivel_examen",requested);
    var start=settings.classes(Db.number(s,"id_convocatoria"));
    s.put("inicio_clases",start==null?"Por confirmar por CELEX":start.format(java.time.format.DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm")));
    var existing=db.rows("SELECT * FROM comprobantes_oficiales WHERE id_solicitud=?",id);
    String token=existing.isEmpty()?UUID.randomUUID().toString():(String)existing.get(0).get("codigo_qr");
    String url=publicUrl+"/validar/"+token;
    String signature=HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(("layout5|"+new TreeMap<>(s)+url).getBytes(java.nio.charset.StandardCharsets.UTF_8)));
    if(!existing.isEmpty() && signature.equals(settings.read("Diseño comprobante · "+id).get("firma")))return;
    String key=storage.write(pdf(s,url),"pdf");
    if(existing.isEmpty())db.update("INSERT INTO comprobantes_oficiales(id_solicitud,codigo_qr,url_pdf) VALUES(?,?,?)",id,token,key);
    else db.update("UPDATE comprobantes_oficiales SET url_pdf=? WHERE id_solicitud=?",key,id);
    var layout=new LinkedHashMap<String,Object>();layout.put("version",5);layout.put("firma",signature);layout.put("pdf",key);
    if(!existing.isEmpty())layout.put("pdfAnterior",existing.get(0).get("url_pdf"));
    settings.write(Db.number(s,"id_usuario"),"Diseño comprobante · "+id,layout);
  }

  public static byte[] pdf(Map<String,Object> s,String url) throws Exception {
    var output=new ByteArrayOutputStream();var doc=new Document(PageSize.A4,50,50,50,50);PdfWriter.getInstance(doc,output);doc.open();
    Color green=new Color(0,107,87);Color pale=new Color(232,245,239);Color ink=new Color(31,49,44);
    var header=new PdfPTable(1);header.setWidthPercentage(100);header.setSpacingAfter(24);
    var cell=new PdfPCell();cell.setBackgroundColor(green);cell.setBorder(Rectangle.NO_BORDER);cell.setPadding(20);
    cell.addElement(new Paragraph("INSTITUTO POLITÉCNICO NACIONAL",new Font(Font.HELVETICA,17,Font.BOLD,Color.WHITE)));
    cell.addElement(new Paragraph("ESIME Culhuacán · CELEX",new Font(Font.HELVETICA,14,Font.NORMAL,Color.WHITE)));
    cell.addElement(new Paragraph("Comprobante oficial de inscripción",new Font(Font.HELVETICA,11,Font.NORMAL,Color.WHITE)));
    header.addCell(cell);doc.add(header);
    boolean exam="Colocación".equals(s.get("nivel"));
    if(exam){var title=new Paragraph("Examen por colocación",new Font(Font.HELVETICA,16,Font.BOLD,green));title.setSpacingAfter(16);doc.add(title);}
    String[][] fields=exam?new String[][]{{"Folio","folio"},{"Alumno","nombre_completo"},{"Idioma","idioma"},{"Nivel al que aplica el examen","nivel_examen"},{"Profesor","profesor"},{"Aula","aula"},{"Día","dias"},{"Horario","horario"}}:new String[][]{{"Folio","folio"},{"Alumno","nombre_completo"},{"Idioma","idioma"},{"Grupo","id_grupo"},{"Nivel","nivel"},{"Modalidad","modalidad"},{"Profesor","profesor"},{"Aula","aula"},{"Días","dias"},{"Horario","horario"},{"Cuota","monto_calculado"},{"Inicio de clases","inicio_clases"}};
    var table=new PdfPTable(new float[]{38,62});table.setWidthPercentage(100);table.setSpacingAfter(18);
    for(var f:fields){var label=new PdfPCell(new Phrase(f[0],new Font(Font.HELVETICA,11,Font.BOLD,ink)));label.setBackgroundColor(pale);label.setPadding(10);label.setBorderColor(new Color(212,229,221));table.addCell(label);
      String value="dias".equals(f[1])?Rules.dayNames(Objects.toString(s.get(f[1]),"")):Objects.toString(s.get(f[1]),"");
      var data=new PdfPCell(new Phrase(value,new Font(Font.HELVETICA,12,Font.NORMAL,ink)));data.setPadding(10);data.setBorderColor(new Color(212,229,221));table.addCell(data);}
    doc.add(table);var matrix=new QRCodeWriter().encode(url,BarcodeFormat.QR_CODE,200,200);var png=new ByteArrayOutputStream();MatrixToImageWriter.writeToStream(matrix,"PNG",png);Image qr=Image.getInstance(png.toByteArray());qr.scaleAbsolute(140,140);qr.setAlignment(Element.ALIGN_CENTER);doc.add(qr);
    if(!exam){var note=new Paragraph("Escanea el QR para validar la vigencia de este comprobante.",new Font(Font.HELVETICA,10));note.setAlignment(Element.ALIGN_CENTER);doc.add(note);}
    doc.close();return output.toByteArray();
  }
}
