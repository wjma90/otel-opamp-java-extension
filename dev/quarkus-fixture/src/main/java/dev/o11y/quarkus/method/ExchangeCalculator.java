package dev.o11y.quarkus.method;

import jakarta.enterprise.context.ApplicationScoped;

@ApplicationScoped
public class ExchangeCalculator {
  public double convert(double sourceAmount, double exchangeRate) {
    return Math.round((sourceAmount / exchangeRate) * 100.0) / 100.0;
  }
}
