package mx.ipn.esime.celex.config;

import org.springframework.context.annotation.*;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.web.SecurityFilterChain;

@Configuration
@EnableMethodSecurity
public class SecurityConfig {
  @Bean
  SecurityFilterChain security(
      HttpSecurity http,
      DatabaseAuthenticationProvider provider,
      mx.ipn.esime.celex.repository.Db db,
      java.time.Clock clock)
      throws Exception {
    return http.addFilterBefore(
            new OrderedEnrollmentFilter(),
            org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter
                .class)
        .addFilterBefore(
            new PublicAttemptFilter(),
            org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter
                .class)
        .addFilterBefore(
            new LivePermissionsFilter(db, clock),
            org.springframework.security.web.access.intercept.AuthorizationFilter.class)
        .authenticationProvider(provider)
        .authorizeHttpRequests(
            a ->
                a.requestMatchers(
                        "/",
                        "/registro",
                        "/salud",
                        "/reenviar",
                        "/verificar",
                        "/recuperar",
                        "/restablecer",
                        "/login",
                        "/css/**",
                        "/js/**",
                        "/validar/**",
                        "/error")
                    .permitAll()
                    .requestMatchers(
                        org.springframework.http.HttpMethod.POST,
                        "/admin/convocatorias",
                        "/admin/convocatorias/**")
                    .hasAuthority("CONFIGURACION")
                    .requestMatchers("/admin/**")
                    .hasAuthority("ADMIN")
                    .requestMatchers("/profesor/**", "/profesor")
                    .hasAuthority("PROFESOR")
                    .requestMatchers("/fila/**", "/fila", "/solicitud/**", "/documentos/**")
                    .hasAnyAuthority("ADMIN", "USUARIO")
                    .anyRequest()
                    .authenticated())
        .formLogin(
            f ->
                f.loginPage("/login")
                    .successHandler(
                        (request, response, authentication) -> {
                          boolean administrator =
                              authentication.getAuthorities().stream()
                                  .anyMatch(authority -> "ADMIN".equals(authority.getAuthority()));
                          request.getSession().removeAttribute("SPRING_SECURITY_SAVED_REQUEST");
                          response.sendRedirect(
                              request.getContextPath() + (administrator ? "/admin" : "/panel"));
                        })
                    .failureUrl("/login?error")
                    .permitAll())
        .logout(l -> l.logoutSuccessUrl("/login?logout"))
        .build();
  }
}
