package dev.o11y.agent.method;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import dev.o11y.agent.policy.DynamicPolicy;
import dev.o11y.agent.policy.MethodPolicyCompiler;
import java.util.Map;
import org.junit.jupiter.api.Test;

class MethodCaptureHelperTest {
  @Test
  void canonicalizesAndResolvesWhitespaceAroundArgumentReturnAndMetricPaths() throws Exception {
    DynamicPolicy policy = policyWithWhitespaceAroundObjectPaths();

    MethodCaptureHelper.MethodRule rule =
        MethodCaptureHelper.parse(MethodPolicyCompiler.compile(policy)).getFirst();
    Object[] arguments = {
      Map.of(
          "customer", Map.of("type", "PREMIUM"),
          "quote", Map.of("amount", 2500.0))
    };
    Object returned = Map.of("result", Map.of("amount", 1035.0));

    assertEquals("customer.type", rule.captures.get(0).source.path);
    assertEquals(
        "PREMIUM",
        MethodCaptureHelper.resolve(rule.captures.get(0).source, arguments, returned, 0, 1));
    assertEquals("result.amount", rule.captures.get(1).source.path);
    assertEquals(
        1035.0,
        MethodCaptureHelper.resolve(rule.captures.get(1).source, arguments, returned, 0, 1));
    assertEquals("quote.amount", rule.metrics.getFirst().value.path);
    assertEquals(
        2500.0,
        MethodCaptureHelper.resolve(rule.metrics.getFirst().value, arguments, returned, 0, 1));
  }

  @Test
  void resolvesMapRecordAndPublicJavaBeanProperties() {
    CustomerRecord record = new CustomerRecord("PREMIUM", new AccountRecord(2500.0));
    CustomerBean bean = new CustomerBean("STANDARD", true);

    assertEquals(
        "PREMIUM",
        MethodCaptureHelper.resolve(
            argumentSource("customer.type"),
            new Object[] {Map.of("customer", record)},
            null,
            0,
            1));
    assertEquals(
        2500.0,
        MethodCaptureHelper.resolve(
            argumentSource("account.amount"), new Object[] {record}, null, 0, 1));
    assertEquals(
        "STANDARD",
        MethodCaptureHelper.resolve(
            argumentSource("customerType"), new Object[] {bean}, null, 0, 1));
    assertEquals(
        true,
        MethodCaptureHelper.resolve(
            argumentSource("salaryAccount"), new Object[] {bean}, null, 0, 1));
  }

  @Test
  void neverInvokesArbitraryZeroArgumentMethodsOrReadsPrivateFields() {
    DangerousObject dangerous = new DangerousObject();

    assertNull(
        MethodCaptureHelper.resolve(
            argumentSource("close"), new Object[] {dangerous}, null, 0, 1));
    assertNull(
        MethodCaptureHelper.resolve(
            argumentSource("delete"), new Object[] {dangerous}, null, 0, 1));
    assertNull(
        MethodCaptureHelper.resolve(
            argumentSource("secret"), new Object[] {dangerous}, null, 0, 1));
    assertNull(
        MethodCaptureHelper.resolve(
            argumentSource("class.name"), new Object[] {dangerous}, null, 0, 1));
    assertEquals(0, dangerous.closeCalls);
    assertEquals(0, dangerous.deleteCalls);
  }

  @Test
  void neverInvokesArbitraryToStringWhileCoercingCapturedValues() {
    DangerousText dangerous = new DangerousText();

    assertNull(MethodCaptureHelper.coerce(dangerous, "STRING"));
    assertNull(MethodCaptureHelper.coerce(dangerous, "DOUBLE"));
    assertNull(MethodCaptureHelper.coerce(dangerous, "LONG"));
    assertNull(MethodCaptureHelper.coerce(dangerous, "BOOLEAN"));
    assertEquals(0, dangerous.toStringCalls);
    assertEquals("PREMIUM", MethodCaptureHelper.coerce(CustomerType.PREMIUM, "STRING"));
  }

  private static MethodCaptureHelper.ValueSource argumentSource(String path) {
    MethodCaptureHelper.ValueSource source = new MethodCaptureHelper.ValueSource();
    source.type = "ARGUMENT";
    source.argumentIndex = 0;
    source.path = path;
    return source;
  }

  public record AccountRecord(double amount) {}

  public record CustomerRecord(String type, AccountRecord account) {}

  public static final class CustomerBean {
    private final String customerType;
    private final boolean salaryAccount;

    CustomerBean(String customerType, boolean salaryAccount) {
      this.customerType = customerType;
      this.salaryAccount = salaryAccount;
    }

    public String getCustomerType() {
      return customerType;
    }

    public boolean isSalaryAccount() {
      return salaryAccount;
    }
  }

  public static final class DangerousObject {
    private final String secret = "must-not-be-readable";
    private int closeCalls;
    private int deleteCalls;

    public void close() {
      closeCalls++;
    }

    public String delete() {
      deleteCalls++;
      return "deleted";
    }
  }

  private enum CustomerType {
    PREMIUM
  }

  private static final class DangerousText {
    private int toStringCalls;

    @Override
    public String toString() {
      toStringCalls++;
      throw new IllegalStateException("must not be invoked by instrumentation");
    }
  }

  private static DynamicPolicy policyWithWhitespaceAroundObjectPaths() throws Exception {
    return DynamicPolicy.parse(
        """
        {
          "schemaVersion": "1.3",
          "methodPolicies": [{
            "id": "exchange-calculation",
            "className": "dev.o11y.exchange.ExchangeCalculator",
            "methodName": "calculate",
            "captures": [
              {
                "source": "ARGUMENT",
                "argumentIndex": 0,
                "path": "  customer.type  ",
                "attribute": "customer.type",
                "destinations": ["SPAN"]
              },
              {
                "source": "RETURN",
                "path": "\\tresult.amount\\n",
                "attribute": "exchange.target.amount",
                "type": "DOUBLE",
                "destinations": ["SPAN"]
              }
            ],
            "metrics": [{
              "name": "cambistapp.currency_exchange.source.amount",
              "instrument": "HISTOGRAM",
              "unit": "{PEN}",
              "value": {
                "source": "ARGUMENT",
                "argumentIndex": 0,
                "path": " quote.amount "
              },
              "buckets": [100.0]
            }]
          }]
        }
        """);
  }
}
