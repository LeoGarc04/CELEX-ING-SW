package mx.ipn.esime.celex;

import mx.ipn.esime.celex.service.MailService;
import org.junit.jupiter.api.Test;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.test.util.ReflectionTestUtils;
import org.mockito.ArgumentCaptor;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class MailServiceTest {
  @Test void mailKeepsSectionsAndLinksReadable() {
    var sender=mock(JavaMailSender.class);
    var service=new MailService(sender,"celex@example.invalid");
    ReflectionTestUtils.setField(service,"dailyLimit",400);
    service.send("alumno@example.invalid","CELEX · Dictamen de inscripción","Folio: ABCD1234\nEstado: Confirmada\n\nDescarga tu comprobante:\nhttps://example.invalid/solicitud/7/comprobante");
    var capture=ArgumentCaptor.forClass(SimpleMailMessage.class);verify(sender).send(capture.capture());
    String body=capture.getValue().getText();
    assertTrue(body.startsWith("CELEX ESIME Culhuacán\nDictamen de inscripción\n\n"));
    assertTrue(body.contains("Folio: ABCD1234\nEstado: Confirmada\n\n"));
    assertTrue(body.contains("\nhttps://example.invalid/solicitud/7/comprobante\n\nDudas y contacto"));
    assertEquals("alumno@example.invalid",capture.getValue().getTo()[0]);
  }
}
