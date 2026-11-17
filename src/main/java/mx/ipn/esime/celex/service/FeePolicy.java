package mx.ipn.esime.celex.service;

import java.math.BigDecimal;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

@Service
public class FeePolicy {
  private final BigDecimal ipn, external;

  public FeePolicy(
      @Value("${celex.fee-ipn}") BigDecimal ipn,
      @Value("${celex.fee-public}") BigDecimal external) {
    this.ipn = check(ipn);
    this.external = check(external);
  }

  private static BigDecimal check(BigDecimal value) {
    if (value.signum() < 0 || value.scale() > 2 || value.precision() > 10)
      throw new IllegalArgumentException("Cuota inválida");
    return value.setScale(2);
  }

  public BigDecimal full(String type) {
    return "Comunidad IPN".equals(type) ? ipn : external;
  }

  public BigDecimal calculate(String type, String scholarship) {
    return "Beneficiario".equals(scholarship) ? BigDecimal.ZERO.setScale(2) : full(type);
  }
}
