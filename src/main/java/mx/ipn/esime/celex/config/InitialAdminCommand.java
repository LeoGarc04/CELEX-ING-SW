package mx.ipn.esime.celex.config;

import mx.ipn.esime.celex.service.InitialAdminService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(name = "celex.setup.admin-email")
public class InitialAdminCommand implements ApplicationRunner {
  private final InitialAdminService service;
  private final ConfigurableApplicationContext context;
  private final String email;

  public InitialAdminCommand(
      InitialAdminService service,
      ConfigurableApplicationContext context,
      @Value("${celex.setup.admin-email}") String email) {
    this.service = service;
    this.context = context;
    this.email = email;
  }

  @Override
  public void run(ApplicationArguments args) {
    int status = 0;
    try {
      service.appoint(email);
      System.out.println("Administrador inicial asignado. Inicia sesión con tu cuenta verificada.");
    } catch (IllegalArgumentException e) {
      status = 1;
      System.err.println("No se asignó el administrador: " + e.getMessage());
    }
    final int result = status;
    SpringApplication.exit(context, () -> result);
    System.exit(status);
  }
}
