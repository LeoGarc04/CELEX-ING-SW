package mx.ipn.esime.celex.service;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.stereotype.Service;

@Service
public class MailService {
  private final JavaMailSender sender;
  private final String from;

  public MailService(JavaMailSender sender, @Value("${celex.mail-from}") String from) {
    this.sender = sender;
    this.from = from;
  }

  private java.time.LocalDate quotaDay = java.time.LocalDate.now();
  private int sentToday;

  @Value("${celex.mail-daily-limit:400}")
  private int dailyLimit;

  private synchronized void reserve() {
    var today = java.time.LocalDate.now();
    if (!today.equals(quotaDay)) {
      quotaDay = today;
      sentToday = 0;
    }
    if (sentToday >= dailyLimit)
      throw new IllegalArgumentException(
          "Se alcanzó el límite diario de correo de esta instancia. Intenta mañana o contacta a"
              + " CELEX.");
    sentToday++;
  }

  public void send(String to, String subject, String body) {
    if (from.isBlank())
      throw new IllegalArgumentException(
          "El servicio de correo no está configurado. Contacta a CELEX.");
    var message = new SimpleMailMessage();
    message.setFrom(from);
    message.setTo(to);
    message.setSubject(subject);
    message.setText(body);
    reserve();
    sender.send(message);
  }
}
