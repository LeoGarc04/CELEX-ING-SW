package mx.ipn.esime.celex.service;

import com.google.zxing.*;
import com.google.zxing.client.j2se.MatrixToImageWriter;
import com.google.zxing.qrcode.QRCodeWriter;
import com.lowagie.text.*;
import com.lowagie.text.pdf.PdfWriter;
import java.io.*;
import java.util.*;
import mx.ipn.esime.celex.repository.Db;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

@Service
public class ReceiptService {
  private final Db db;
  private final PrivateStorage storage;
  private final String publicUrl;

  public ReceiptService(Db db, PrivateStorage storage, @Value("${celex.public-url}") String url) {
    this.db = db;
    this.storage = storage;
    publicUrl = url;
  }

  public void issue(int id) throws Exception {
    if (!db.rows(
            "SELECT id_comprobante_oficial FROM comprobantes_oficiales WHERE id_solicitud=?", id)
        .isEmpty()) return;
    var s =
        db.one(
            "SELECT"
                + " s.*,u.nombre_completo,g.nivel,g.modalidad,g.profesor,g.aula,g.dias,g.horario,i.nombre"
                + " AS idioma FROM solicitudes_inscripcion s JOIN usuarios u ON"
                + " u.id_usuario=s.id_usuario JOIN grupos g ON g.id_grupo=s.id_grupo JOIN idiomas i"
                + " ON i.id_idioma=g.id_idioma WHERE s.id_solicitud=? AND"
                + " s.estado_inscripcion='Confirmada'",
            id);
    String token = UUID.randomUUID().toString();
    String url = publicUrl + "/validar/" + token;
    byte[] bytes = pdf(s, url);
    String key = storage.write(bytes, "pdf");
    db.update(
        "INSERT INTO comprobantes_oficiales(id_solicitud,codigo_qr,url_pdf) VALUES(?,?,?)",
        id,
        token,
        key);
  }

  public static byte[] pdf(Map<String, Object> s, String url) throws Exception {
    var output = new ByteArrayOutputStream();
    var doc = new Document(PageSize.A4, 50, 50, 50, 50);
    PdfWriter.getInstance(doc, output);
    doc.open();
    doc.add(
        new Paragraph("INSTITUTO POLITÉCNICO NACIONAL", new Font(Font.HELVETICA, 18, Font.BOLD)));
    doc.add(new Paragraph("ESIME Culhuacán · CELEX", new Font(Font.HELVETICA, 14)));
    doc.add(new Paragraph("Comprobante oficial de inscripción"));
    doc.add(Chunk.NEWLINE);
    String[][] fields = {
      {"Folio", "folio"},
      {"Alumno", "nombre_completo"},
      {"Idioma", "idioma"},
      {"Nivel", "nivel"},
      {"Modalidad", "modalidad"},
      {"Profesor", "profesor"},
      {"Aula", "aula"},
      {"Días", "dias"},
      {"Horario", "horario"},
      {"Cuota", "monto_calculado"}
    };
    for (var f : fields)
      doc.add(
          new Paragraph(
              f[0]
                  + ": "
                  + ("dias".equals(f[1])
                      ? Rules.dayNames(Objects.toString(s.get(f[1]), ""))
                      : Objects.toString(s.get(f[1]), ""))));
    doc.add(Chunk.NEWLINE);
    var matrix = new QRCodeWriter().encode(url, BarcodeFormat.QR_CODE, 160, 160);
    var png = new ByteArrayOutputStream();
    MatrixToImageWriter.writeToStream(matrix, "PNG", png);
    Image image = Image.getInstance(png.toByteArray());
    image.scaleAbsolute(140, 140);
    doc.add(image);
    doc.add(new Paragraph("Escanea el QR para validar la vigencia de este comprobante."));
    doc.close();
    return output.toByteArray();
  }
}
