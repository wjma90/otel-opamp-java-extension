package dev.o11y.agent.http.client;

import java.util.List;

/** Shared helper classes injected beside every supported HTTP client implementation. */
public final class HttpClientInstrumentationHelpers {
  private HttpClientInstrumentationHelpers() {}

  public static List<String> common() {
    return List.of(
        "dev.o11y.agent.http.client.OutgoingHttpExchange",
        "dev.o11y.agent.http.client.OutgoingHttpExchange$BoundedBytes",
        "dev.o11y.agent.http.client.OutgoingHttpExchange$CapturingOutputStream",
        "dev.o11y.agent.http.client.OutgoingHttpExchange$ClearingPrefixStream",
        "dev.o11y.agent.http.client.OutgoingHttpExchange$OwnershipKey",
        "dev.o11y.agent.http.client.OutgoingHttpExchange$ReplayBody",
        "dev.o11y.agent.http.client.OutgoingHttpExchange$RequestAttempt",
        "dev.o11y.agent.http.client.ReflectiveHttpAccess",
        "dev.o11y.agent.http.runtime.BodyNumericExpression",
        "dev.o11y.agent.http.runtime.BodyNumericExpression$Compiled",
        "dev.o11y.agent.http.runtime.BodyNumericExpression$Node",
        "dev.o11y.agent.http.runtime.BodyNumericExpression$Parser",
        "dev.o11y.agent.http.runtime.BoundedJsonParser",
        "dev.o11y.agent.http.runtime.HttpErrorType",
        "dev.o11y.agent.http.runtime.HttpBodyPolicyEngine",
        "dev.o11y.agent.http.runtime.HttpBodyPolicyEngine$PolicyCache",
        "dev.o11y.agent.http.runtime.HttpBodyPolicyEngine$CapturePlan",
        "dev.o11y.agent.http.runtime.HttpBodyPolicyEngine$BodyParseKey",
        "dev.o11y.agent.http.runtime.HttpBodyPolicyEngine$InvalidBody",
        "dev.o11y.agent.http.runtime.HttpBodyPolicyEngine$RuntimePolicy",
        "dev.o11y.agent.http.runtime.HttpBodyPolicyEngine$EventRule",
        "dev.o11y.agent.http.runtime.HttpBodyPolicyEngine$Condition",
        "dev.o11y.agent.http.runtime.HttpBodyPolicyEngine$StaticAttribute",
        "dev.o11y.agent.http.runtime.HttpBodyPolicyEngine$FieldRule",
        "dev.o11y.agent.http.runtime.HttpBodyPolicyEngine$DerivedRule",
        "dev.o11y.agent.http.runtime.HttpBodyPolicyEngine$ValuePolicy",
        "dev.o11y.agent.http.runtime.HttpBodyPolicyEngine$ValueRange",
        "dev.o11y.agent.http.runtime.HttpBodyPolicyEngine$EventMetric",
        "dev.o11y.agent.http.runtime.HttpBodyPolicyEngine$ExtractedField",
        "dev.o11y.agent.http.runtime.HttpBodyPolicyEngine$InstrumentHandle",
        "dev.o11y.agent.http.runtime.HttpBodyPolicyEngine$Recorder");
  }
}
