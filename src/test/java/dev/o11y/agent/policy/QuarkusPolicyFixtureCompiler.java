package dev.o11y.agent.policy;

import java.util.List;

/** Compiles the Quarkus smoke policy without coupling validation to the Maven launcher package. */
public final class QuarkusPolicyFixtureCompiler {
  private QuarkusPolicyFixtureCompiler() {}

  public static Compiled compile(String json) throws Exception {
    DynamicPolicy policy = DynamicPolicy.parse(json);
    List<String> errors = PolicyValidator.validate(policy, List.of("dev.o11y.quarkus"));
    if (!errors.isEmpty()) {
      throw new IllegalArgumentException(String.join("; ", errors));
    }
    return new Compiled(
        MethodPolicyCompiler.compile(policy), BodyPolicyCompiler.compile(policy));
  }

  public record Compiled(String method, String http) {}
}
