package dev.o11y.agent.policy;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

class PolicyValidatorTest {
  private static final List<String> METHOD_PACKAGES = List.of("dev.o11y.rates.service");

  @Test
  void reportsTheNewestSchemaAcceptedByTheValidator() {
    assertEquals("1.6", PolicyValidator.MAX_SUPPORTED_SCHEMA_VERSION);
  }

  @Test
  void validatesAndCompilesStandardAttributesForHttpEventMetrics() throws Exception {
    DynamicPolicy policy = DynamicPolicy.parse(bodyEventPolicy());
    policy.schemaVersion = "1.6";
    DynamicPolicy.EventMetricPolicy metric = policy.eventMetricPolicies.getFirst();
    metric.standardAttributes =
        List.of(
            "http.request.method",
            "http.route",
            "http.response.status_code",
            "error.type");

    assertTrue(PolicyValidator.validate(policy, METHOD_PACKAGES).isEmpty());
    String compiled = BodyPolicyCompiler.compile(policy);
    String encodedAttributes =
        Base64.getUrlEncoder()
            .withoutPadding()
            .encodeToString(
                String.join("\u001f", metric.standardAttributes)
                    .getBytes(StandardCharsets.UTF_8));
    assertTrue(compiled.contains(encodedAttributes));

    policy.schemaVersion = "1.5";
    assertTrue(
        PolicyValidator.validate(policy, METHOD_PACKAGES).stream()
            .anyMatch(error -> error.contains("require schemaVersion 1.6")));

    policy.schemaVersion = "1.6";
    policy.bodyEventPolicies.getFirst().direction = "OUTGOING";
    assertTrue(
        PolicyValidator.validate(policy, METHOD_PACKAGES).stream()
            .anyMatch(error -> error.contains("http.route is supported only for INCOMING")));
  }

  @Test
  void acceptsACompleteDynamicPolicyAndCompilesMethodRules() throws Exception {
    DynamicPolicy policy = DynamicPolicy.parse(validPolicy("test.business.events", "COUNTER"));

    assertTrue(PolicyValidator.validate(policy, METHOD_PACKAGES).isEmpty());
    String compiled = MethodPolicyCompiler.compile(policy);
    assertTrue(compiled.startsWith("V|1\nM|"));
    assertTrue(compiled.contains("\nC|"));
    assertTrue(compiled.contains("\nI|"));
    assertFalse(compiled.contains("customerType"));
  }

  @Test
  void acceptsAndCanonicalizesOuterWhitespaceInMethodObjectPaths() throws Exception {
    DynamicPolicy policy =
        DynamicPolicy.parse(
            validPolicy("test.trimmed.paths", "COUNTER")
                .replace("\"path\": \"customerType\"", "\"path\": \"  customerType  \""));
    DynamicPolicy.MethodPolicy method = policy.methodPolicies.getFirst();
    DynamicPolicy.Capture returned = new DynamicPolicy.Capture();
    returned.source = "RETURN";
    returned.path = "  result.amount  ";
    returned.attribute = "exchange.target.amount";
    returned.type = "DOUBLE";
    returned.destinations.add("SPAN");
    method.captures.add(returned);
    method.metrics.getFirst().value.source = "ARGUMENT";
    method.metrics.getFirst().value.argumentIndex = 0;
    method.metrics.getFirst().value.path = "  quote.amount  ";

    assertEquals("customerType", method.captures.getFirst().path);
    assertTrue(PolicyValidator.validate(policy, METHOD_PACKAGES).isEmpty());
  }

  @Test
  void acceptsPolicyDefinedNamesAndRejectsUnboundedMetricLabels() throws Exception {
    DynamicPolicy policyDefinedHeader =
        DynamicPolicy.parse(validPolicy("test.sensitive.events", "COUNTER")
            .replace("x-client-channel", "authorization"));
    DynamicPolicy unbounded =
        DynamicPolicy.parse(validPolicy("test.unbounded.events", "COUNTER")
            .replace("[\"WEB\", \"API\"]", "[]"));
    DynamicPolicy policyDefinedPath =
        DynamicPolicy.parse(validPolicy("test.sensitive.path", "COUNTER")
            .replace("\"path\": \"customerType\"", "\"path\": \"password\""));

    assertTrue(PolicyValidator.validate(policyDefinedHeader, METHOD_PACKAGES).isEmpty());
    assertTrue(
        PolicyValidator.validate(unbounded, METHOD_PACKAGES).stream()
            .anyMatch(error -> error.contains("ENUM requires")));
    assertTrue(PolicyValidator.validate(policyDefinedPath, METHOD_PACKAGES).isEmpty());
  }

  @Test
  void scopesGenericHeadersByDirectionButKeepsDeniedHeadersGlobal() throws Exception {
    DynamicPolicy bothDirections =
        DynamicPolicy.parse(
            """
            {
              "schemaVersion": "1.3",
              "requestHeaders": [
                {"name": "X-Correlation-ID"},
                {"name": "x-correlation-id", "direction": "outgoing"}
              ]
            }
            """);
    DynamicPolicy duplicateIncoming =
        DynamicPolicy.parse(
            """
            {
              "schemaVersion": "1.3",
              "requestHeaders": [
                {"name": "x-correlation-id"},
                {"name": "X-Correlation-ID", "direction": "INCOMING"}
              ]
            }
            """);
    DynamicPolicy invalidDirection =
        DynamicPolicy.parse(
            """
            {
              "schemaVersion": "1.3",
              "responseHeaders": [{"name": "x-result", "direction": "SIDEWAYS"}]
            }
            """);
    DynamicPolicy duplicateDenied =
        DynamicPolicy.parse(
            """
            {
              "schemaVersion": "1.3",
              "deniedHeaders": [
                {"name": "authorization", "direction": "INCOMING"},
                {"name": "Authorization", "direction": "OUTGOING"}
              ]
            }
            """);

    assertTrue(PolicyValidator.validate(bothDirections).isEmpty());
    assertEquals("INCOMING", bothDirections.requestHeaders.getFirst().direction);
    assertEquals("OUTGOING", bothDirections.requestHeaders.get(1).direction);
    assertTrue(
        PolicyValidator.validate(duplicateIncoming).stream()
            .anyMatch(error -> error.contains("duplicated header")));
    assertTrue(
        PolicyValidator.validate(invalidDirection).stream()
            .anyMatch(error -> error.contains("direction must be INCOMING or OUTGOING")));
    assertTrue(
        PolicyValidator.validate(duplicateDenied).stream()
            .anyMatch(error -> error.contains("duplicated header")));
  }

  @Test
  void locksInstrumentTypeUnitAndBucketsForTheLifetimeOfTheAgent() throws Exception {
    String name = "test.immutable.events." + System.nanoTime();
    DynamicPolicy first = DynamicPolicy.parse(validPolicy(name, "COUNTER"));
    DynamicPolicy changed = DynamicPolicy.parse(validPolicy(name, "UP_DOWN_COUNTER"));

    PolicyValidator.validateAndLock(first, METHOD_PACKAGES);

    assertThrows(
        IllegalArgumentException.class,
        () -> PolicyValidator.validateAndLock(changed, METHOD_PACKAGES));
  }

  @Test
  void rejectsOversizedMetricMethodCaptureAndBucketCollections() throws Exception {
    DynamicPolicy tooManyHttpMetrics =
        policyWithHttpMetrics(
            "test.limit.http", PolicyValidator.MAX_HTTP_METRIC_POLICIES + 1);

    DynamicPolicy tooManyMethods = new DynamicPolicy();
    for (int index = 0; index <= PolicyValidator.MAX_METHOD_POLICIES; index++) {
      DynamicPolicy.MethodPolicy method = new DynamicPolicy.MethodPolicy();
      method.id = "disabled-method-" + index;
      method.enabled = false;
      tooManyMethods.methodPolicies.add(method);
    }

    DynamicPolicy tooManyCaptures =
        DynamicPolicy.parse(validPolicy("test.limit.captures", "COUNTER"));
    DynamicPolicy.Capture capture =
        tooManyCaptures.methodPolicies.getFirst().captures.getFirst();
    while (tooManyCaptures.methodPolicies.getFirst().captures.size()
        <= PolicyValidator.MAX_METHOD_CAPTURES_PER_POLICY) {
      tooManyCaptures.methodPolicies.getFirst().captures.add(capture);
    }

    DynamicPolicy tooManyMethodMetrics =
        DynamicPolicy.parse(validPolicy("test.limit.method.metrics", "COUNTER"));
    DynamicPolicy.MethodMetric methodMetric =
        tooManyMethodMetrics.methodPolicies.getFirst().metrics.getFirst();
    while (tooManyMethodMetrics.methodPolicies.getFirst().metrics.size()
        <= PolicyValidator.MAX_METHOD_METRICS_PER_POLICY) {
      tooManyMethodMetrics.methodPolicies.getFirst().metrics.add(methodMetric);
    }

    DynamicPolicy tooManyBuckets =
        DynamicPolicy.parse(validPolicy("test.limit.buckets", "COUNTER"));
    DynamicPolicy.HttpMetricPolicy histogram = tooManyBuckets.metricPolicies.getFirst();
    histogram.buckets.clear();
    for (int index = 1; index <= PolicyValidator.MAX_EXPLICIT_BUCKETS + 1; index++) {
      histogram.buckets.add((double) index);
    }

    assertTrue(
        PolicyValidator.validate(tooManyHttpMetrics, List.of()).stream()
            .anyMatch(error -> error.contains("metricPolicies exceeds its limit")));
    assertTrue(
        PolicyValidator.validate(tooManyMethods, List.of()).stream()
            .anyMatch(error -> error.contains("methodPolicies exceeds its limit")));
    assertTrue(
        PolicyValidator.validate(tooManyCaptures, METHOD_PACKAGES).stream()
            .anyMatch(error -> error.contains("captures exceeds its limit")));
    assertTrue(
        PolicyValidator.validate(tooManyMethodMetrics, METHOD_PACKAGES).stream()
            .anyMatch(error -> error.contains("metrics exceeds its limit")));
    assertTrue(
        PolicyValidator.validate(tooManyBuckets, METHOD_PACKAGES).stream()
            .anyMatch(error -> error.contains("buckets exceeds its limit")));
  }

  @Test
  void rejectsMetricDimensionCardinalityExplosionAcrossPolicyTypes() throws Exception {
    DynamicPolicy http = DynamicPolicy.parse(validPolicy("test.cardinality.http", "COUNTER"));
    DynamicPolicy.HttpMetricPolicy httpMetric = http.metricPolicies.getFirst();
    httpMetric.customAttributes.clear();
    for (int index = 0; index < 5; index++) {
      httpMetric.customAttributes.add(boundedAttribute("http.dimension." + index, "x-dim-" + index));
    }

    DynamicPolicy method = DynamicPolicy.parse(validPolicy("test.cardinality.method", "COUNTER"));
    DynamicPolicy.MethodPolicy methodPolicy = method.methodPolicies.getFirst();
    methodPolicy.captures.clear();
    for (int index = 0; index < 5; index++) {
      DynamicPolicy.Capture capture = new DynamicPolicy.Capture();
      capture.source = "ARGUMENT";
      capture.argumentIndex = 0;
      capture.path = "dimension" + index;
      capture.attribute = "method.dimension." + index;
      capture.destinations.add("METRIC");
      capture.valuePolicy = boundedValues();
      methodPolicy.captures.add(capture);
    }

    DynamicPolicy event = DynamicPolicy.parse(bodyEventPolicy());
    DynamicPolicy.BodyEventPolicy bodyEvent = event.bodyEventPolicies.getFirst();
    DynamicPolicy.EventMetricPolicy eventMetric = event.eventMetricPolicies.getFirst();
    for (int index = 0; index < 4; index++) {
      DynamicPolicy.BodyField field = new DynamicPolicy.BodyField();
      field.attribute = "event.dimension." + index;
      field.source = "REQUEST_BODY";
      field.path = "dimension" + index;
      field.destinations.add("METRIC");
      field.valuePolicy = boundedValues();
      bodyEvent.fields.add(field);
      eventMetric.dimensions.add(field.attribute);
    }

    assertTrue(
        PolicyValidator.validate(http, METHOD_PACKAGES).stream()
            .anyMatch(error -> error.contains("cardinality exceeds")));
    assertTrue(
        PolicyValidator.validate(method, METHOD_PACKAGES).stream()
            .anyMatch(error -> error.contains("cardinality exceeds")));
    assertTrue(
        PolicyValidator.validate(event, METHOD_PACKAGES).stream()
            .anyMatch(error -> error.contains("cardinality exceeds")));
  }

  @Test
  void lifetimeInstrumentLimitRejectsTransactionWithoutMutatingTheLock() {
    PolicyValidator.resetLockedIdentitiesForTest();
    try {
      int generations =
          PolicyValidator.MAX_LIFETIME_INSTRUMENT_IDENTITIES
              / PolicyValidator.MAX_HTTP_METRIC_POLICIES;
      for (int generation = 0; generation < generations; generation++) {
        PolicyValidator.validateAndLock(
            policyWithHttpMetrics(
                "test.lifetime.g" + generation,
                PolicyValidator.MAX_HTTP_METRIC_POLICIES),
            List.of());
      }

      assertEquals(
          PolicyValidator.MAX_LIFETIME_INSTRUMENT_IDENTITIES,
          PolicyValidator.lockedIdentityCountForTest());
      DynamicPolicy rejected = policyWithHttpMetrics("test.lifetime.rejected", 1);

      IllegalArgumentException error =
          assertThrows(
              IllegalArgumentException.class,
              () -> PolicyValidator.validateAndLock(rejected, List.of()));

      assertTrue(error.getMessage().contains("lifetime limit"));
      assertEquals(
          PolicyValidator.MAX_LIFETIME_INSTRUMENT_IDENTITIES,
          PolicyValidator.lockedIdentityCountForTest());
    } finally {
      PolicyValidator.resetLockedIdentitiesForTest();
    }
  }

  @Test
  void acceptsGenericHttpValuesAndRejectsAnUnknownSource() throws Exception {
    DynamicPolicy constant =
        DynamicPolicy.parse(validPolicy("test.constant.events", "COUNTER"));
    DynamicPolicy.HttpMetricPolicy constantMetric = constant.metricPolicies.getFirst();
    constantMetric.value.source = "CONSTANT";
    constantMetric.value.constant = 1;
    constantMetric.instrument = "COUNTER";
    constantMetric.unit = "1";
    constantMetric.buckets.clear();

    DynamicPolicy attribute =
        DynamicPolicy.parse(validPolicy("test.attribute.events", "COUNTER"));
    attribute.metricPolicies.getFirst().value.source = "ATTRIBUTE";
    attribute.metricPolicies.getFirst().value.path = "http.response.status_code";

    DynamicPolicy stringAttribute =
        DynamicPolicy.parse(validPolicy("test.string.attribute.events", "COUNTER"));
    stringAttribute.metricPolicies.getFirst().value.source = "ATTRIBUTE";
    stringAttribute.metricPolicies.getFirst().value.path = "http.request.method";

    DynamicPolicy reservedClientMetric =
        DynamicPolicy.parse(validPolicy("test.reserved.client.events", "COUNTER"));
    reservedClientMetric.metricPolicies.getFirst().name = "http.client.request.duration";

    DynamicPolicy unknown =
        DynamicPolicy.parse(validPolicy("test.unknown.events", "COUNTER"));
    unknown.metricPolicies.getFirst().value.source = "NAMED_METRIC";

    DynamicPolicy outgoing =
        DynamicPolicy.parse(validPolicy("test.outgoing.http.events", "COUNTER"));
    outgoing.metricPolicies.getFirst().direction = "OUTGOING";
    DynamicPolicy invalidDirection =
        DynamicPolicy.parse(validPolicy("test.invalid.http.direction", "COUNTER"));
    invalidDirection.metricPolicies.getFirst().direction = "SIDEWAYS";

    assertTrue(PolicyValidator.validate(constant, METHOD_PACKAGES).isEmpty());
    assertTrue(PolicyValidator.validate(attribute, METHOD_PACKAGES).isEmpty());
    assertTrue(
        PolicyValidator.validate(stringAttribute, METHOD_PACKAGES).stream()
            .anyMatch(error -> error.contains("numeric HTTP attribute")));
    assertTrue(
        PolicyValidator.validate(reservedClientMetric, METHOD_PACKAGES).stream()
            .anyMatch(error -> error.contains("invalid or reserved metric name")));
    assertTrue(PolicyValidator.validate(outgoing, METHOD_PACKAGES).isEmpty());
    assertTrue(
        PolicyValidator.validate(unknown, METHOD_PACKAGES).stream()
            .anyMatch(error -> error.contains("unsupported HTTP value source")));
    assertTrue(
        PolicyValidator.validate(invalidDirection, METHOD_PACKAGES).stream()
            .anyMatch(error -> error.contains("direction must be INCOMING or OUTGOING")));
  }

  @Test
  void disablesMethodCaptureSafelyWhenNoPackagesAreConfigured() throws Exception {
    DynamicPolicy methodPolicy =
        DynamicPolicy.parse(validPolicy("test.no.packages.events", "COUNTER"));
    DynamicPolicy httpOnly =
        DynamicPolicy.parse(validPolicy("test.http.only.events", "COUNTER"));
    httpOnly.methodPolicies.clear();
    DynamicPolicy disabled =
        DynamicPolicy.parse(validPolicy("test.disabled.method.events", "COUNTER"));
    disabled.methodPolicies.getFirst().enabled = false;

    assertTrue(
        PolicyValidator.validate(methodPolicy, List.of()).stream()
            .anyMatch(error -> error.contains("no safe application package was discovered")));
    assertTrue(PolicyValidator.validate(httpOnly, List.of()).isEmpty());
    assertTrue(PolicyValidator.validate(disabled, List.of()).isEmpty());
  }

  @Test
  void parsesOnlyExplicitGenericPackagePrefixesWithoutBusinessDefaults() {
    assertTrue(PolicyValidator.parseAllowedPackages(null).isEmpty());
    assertTrue(PolicyValidator.parseAllowedPackages("  ").isEmpty());
    assertEquals(
        List.of("com.example.api", "org.acme.domain"),
        PolicyValidator.parseAllowedPackages(
            " com.example.api,invalid-package!,org.acme.domain,com..invalid,com,"
                + "java.lang,com.example.api "));
  }

  @Test
  void requiresMethodPolicyToStayWithinAnExplicitPackageBoundary() throws Exception {
    DynamicPolicy allowed =
        DynamicPolicy.parse(validPolicy("test.allowed.package.events", "COUNTER"));
    DynamicPolicy sibling =
        DynamicPolicy.parse(validPolicy("test.sibling.package.events", "COUNTER"));

    assertTrue(
        PolicyValidator.validate(allowed, List.of("dev.o11y.rates")).isEmpty());
    assertTrue(
        PolicyValidator.validate(sibling, List.of("dev.o11y.rate")).stream()
            .anyMatch(error -> error.contains("outside o11y.method.packages")));
  }

  @Test
  void rejectsUnsupportedMethodCaptureTypes() throws Exception {
    DynamicPolicy policy =
        DynamicPolicy.parse(validPolicy("test.capture.type.events", "COUNTER"));
    policy.methodPolicies.getFirst().captures.getFirst().type = "DECIMAL";

    assertTrue(
        PolicyValidator.validate(policy, METHOD_PACKAGES).stream()
            .anyMatch(error -> error.contains("unsupported capture type DECIMAL")));
  }

  @Test
  void acceptsEventsWithoutImplicitMetadataAndValidatesDeclaredStaticAttributes()
      throws Exception {
    DynamicPolicy withoutMetadata = DynamicPolicy.parse(bodyEventPolicy());
    withoutMetadata.bodyEventPolicies.getFirst().staticAttributes.clear();
    DynamicPolicy invalid = DynamicPolicy.parse(bodyEventPolicy());
    DynamicPolicy.StaticAttribute attribute =
        invalid.bodyEventPolicies.getFirst().staticAttributes.getFirst();
    attribute.type = "BOOLEAN";
    attribute.value = "not-a-boolean";

    assertTrue(PolicyValidator.validate(withoutMetadata).isEmpty());
    assertTrue(
        PolicyValidator.validate(invalid).stream()
            .anyMatch(error -> error.contains("invalid BOOLEAN value")));
  }

  @Test
  void acceptsRequestAndResponseBusinessEventAndRejectsItOnSchemaOneZero()
      throws Exception {
    DynamicPolicy valid = DynamicPolicy.parse(bodyEventPolicy());
    DynamicPolicy oldSchema =
        DynamicPolicy.parse(bodyEventPolicy().replace("\"1.3\"", "\"1.2\""));
    DynamicPolicy policyDefinedResponse =
        DynamicPolicy.parse(bodyEventPolicy().replace("\"status\"", "\"password\""));

    var validErrors = PolicyValidator.validate(valid);
    assertTrue(validErrors.isEmpty(), () -> validErrors.toString());
    assertTrue(
        PolicyValidator.validate(oldSchema).stream()
            .anyMatch(error -> error.contains("schemaVersion 1.3")));
    assertTrue(PolicyValidator.validate(policyDefinedResponse).isEmpty());
  }

  @Test
  void acceptsCounterEventWithoutExtractedFieldsAndKeepsTheFieldLimit() throws Exception {
    DynamicPolicy countOnly = DynamicPolicy.parse(bodyEventPolicy());
    countOnly.bodyEventPolicies.getFirst().fields.clear();
    countOnly.eventMetricPolicies.subList(1, countOnly.eventMetricPolicies.size()).clear();
    countOnly.eventMetricPolicies.getFirst().dimensions.clear();

    var countOnlyErrors = PolicyValidator.validate(countOnly);
    assertTrue(countOnlyErrors.isEmpty(), () -> countOnlyErrors.toString());

    DynamicPolicy overLimit = DynamicPolicy.parse(bodyEventPolicy());
    DynamicPolicy.BodyEventPolicy event = overLimit.bodyEventPolicies.getFirst();
    event.fields.clear();
    overLimit.eventMetricPolicies.clear();
    for (int index = 0; index < 33; index++) {
      DynamicPolicy.BodyField field = new DynamicPolicy.BodyField();
      field.attribute = "event.field." + index;
      field.source = "REQUEST_BODY";
      field.path = "field" + index;
      field.type = "STRING";
      field.destinations.add("SPAN");
      event.fields.add(field);
    }

    assertTrue(
        PolicyValidator.validate(overLimit).stream()
            .anyMatch(error -> error.contains("fields exceeds its limit of 32 entries")));
  }

  @Test
  void rejectsHttpEventWithoutAnEffectiveOutput() throws Exception {
    DynamicPolicy withoutOutput = DynamicPolicy.parse(bodyEventPolicy());
    DynamicPolicy.BodyEventPolicy event = withoutOutput.bodyEventPolicies.getFirst();
    event.staticAttributes.clear();
    event.fields.clear();
    event.log.enabled = false;
    withoutOutput.eventMetricPolicies.clear();

    assertTrue(
        PolicyValidator.validate(withoutOutput).stream()
            .anyMatch(error -> error.contains("must define at least one effective output")));

    DynamicPolicy staticSpan = DynamicPolicy.parse(bodyEventPolicy());
    DynamicPolicy.BodyEventPolicy staticSpanEvent = staticSpan.bodyEventPolicies.getFirst();
    staticSpanEvent.fields.clear();
    staticSpanEvent.log.enabled = false;
    staticSpan.eventMetricPolicies.clear();
    var staticSpanErrors = PolicyValidator.validate(staticSpan);
    assertTrue(staticSpanErrors.isEmpty(), () -> staticSpanErrors.toString());

    DynamicPolicy logOnly = DynamicPolicy.parse(bodyEventPolicy());
    DynamicPolicy.BodyEventPolicy logOnlyEvent = logOnly.bodyEventPolicies.getFirst();
    logOnlyEvent.staticAttributes.clear();
    logOnlyEvent.fields.clear();
    logOnly.eventMetricPolicies.clear();
    var logOnlyErrors = PolicyValidator.validate(logOnly);
    assertTrue(logOnlyErrors.isEmpty(), () -> logOnlyErrors.toString());

    DynamicPolicy metricDestinationOnly = DynamicPolicy.parse(bodyEventPolicy());
    DynamicPolicy.BodyEventPolicy metricOnlyEvent =
        metricDestinationOnly.bodyEventPolicies.getFirst();
    metricOnlyEvent.staticAttributes.clear();
    metricOnlyEvent.log.enabled = false;
    metricOnlyEvent.fields.subList(1, metricOnlyEvent.fields.size()).clear();
    metricOnlyEvent.fields.getFirst().destinations.clear();
    metricOnlyEvent.fields.getFirst().destinations.add("METRIC");
    metricDestinationOnly.eventMetricPolicies.clear();
    assertTrue(
        PolicyValidator.validate(metricDestinationOnly).stream()
            .anyMatch(error -> error.contains("must define at least one effective output")));
  }

  @Test
  void reservesEventNamesAcrossEnabledAndDisabledRules() throws Exception {
    DynamicPolicy enabledAndDisabled = DynamicPolicy.parse(bodyEventPolicy());
    DynamicPolicy.BodyEventPolicy disabledDuplicate =
        DynamicPolicy.parse(bodyEventPolicy()).bodyEventPolicies.getFirst();
    disabledDuplicate.id = "transfer-approved-disabled";
    disabledDuplicate.enabled = false;
    enabledAndDisabled.bodyEventPolicies.add(disabledDuplicate);
    enabledAndDisabled.eventMetricPolicies.clear();

    DynamicPolicy twoDisabled = DynamicPolicy.parse(bodyEventPolicy());
    twoDisabled.bodyEventPolicies.getFirst().enabled = false;
    DynamicPolicy.BodyEventPolicy secondDisabled =
        DynamicPolicy.parse(bodyEventPolicy()).bodyEventPolicies.getFirst();
    secondDisabled.id = "transfer-approved-disabled-2";
    secondDisabled.enabled = false;
    twoDisabled.bodyEventPolicies.add(secondDisabled);
    twoDisabled.eventMetricPolicies.clear();

    assertTrue(
        PolicyValidator.validate(enabledAndDisabled).stream()
            .anyMatch(error -> error.contains("enabled and disabled HTTP event rules")));
    assertTrue(
        PolicyValidator.validate(twoDisabled).stream()
            .anyMatch(error -> error.contains("enabled and disabled HTTP event rules")));
  }

  @Test
  void requiresSchemaOneFourForBoundedHeaderAndQueryEventSources() throws Exception {
    DynamicPolicy valid = DynamicPolicy.parse(bodyEventPolicy());
    valid.schemaVersion = "1.4";
    DynamicPolicy.BodyEventPolicy event = valid.bodyEventPolicies.getFirst();
    event.conditions.removeIf(
        condition ->
            Set.of("REQUEST_BODY", "RESPONSE_BODY", "RESPONSE_STATUS")
                .contains(condition.source));
    DynamicPolicy.HttpCondition methodCondition =
        event.conditions.stream()
            .filter(condition -> "REQUEST_METHOD".equals(condition.source))
            .findFirst()
            .orElseThrow();
    methodCondition.values.clear();
    methodCondition.values.add("GET");
    event.fields.clear();
    valid.eventMetricPolicies.clear();

    DynamicPolicy.HttpCondition headerCondition = new DynamicPolicy.HttpCondition();
    headerCondition.source = "RESPONSE_HEADER";
    headerCondition.path = "x-operation-result";
    headerCondition.operator = "EQUALS";
    headerCondition.values.add("APPROVED");
    event.conditions.add(headerCondition);

    DynamicPolicy.BodyField requestHeader = new DynamicPolicy.BodyField();
    requestHeader.source = "REQUEST_HEADER";
    requestHeader.path = "x-customer-tier";
    requestHeader.attribute = "customer.tier";
    requestHeader.type = "STRING";
    requestHeader.destinations.add("SPAN");
    event.fields.add(requestHeader);

    DynamicPolicy.BodyField query = new DynamicPolicy.BodyField();
    query.source = "REQUEST_QUERY";
    query.path = "campaign";
    query.attribute = "request.campaign";
    query.type = "STRING";
    query.destinations.add("METRIC");
    query.valuePolicy = boundedValues();
    event.fields.add(query);

    assertTrue(PolicyValidator.validate(valid).isEmpty());

    valid.schemaVersion = "1.3";
    assertTrue(
        PolicyValidator.validate(valid).stream()
            .anyMatch(error -> error.contains("require schemaVersion 1.4")));

    valid.schemaVersion = "1.4";
    query.path = "campaign[0]";
    assertTrue(
        PolicyValidator.validate(valid).stream()
            .anyMatch(error -> error.contains("invalid selector for REQUEST_QUERY")));
  }

  @Test
  void scopesBodyPathsByRequestOrResponseAndAcceptsOutgoingEvents()
      throws Exception {
    DynamicPolicy samePathInBothBodies = DynamicPolicy.parse(bodyEventPolicy());
    DynamicPolicy.BodyField responseField = new DynamicPolicy.BodyField();
    responseField.attribute = "response.channel";
    responseField.source = "RESPONSE_BODY";
    responseField.path = "channel";
    responseField.type = "STRING";
    responseField.destinations.add("SPAN");
    samePathInBothBodies.bodyEventPolicies.getFirst().fields.add(responseField);

    DynamicPolicy duplicateRequestPath = DynamicPolicy.parse(bodyEventPolicy());
    DynamicPolicy.BodyField duplicateField = new DynamicPolicy.BodyField();
    duplicateField.attribute = "request.channel.copy";
    duplicateField.source = "REQUEST_BODY";
    duplicateField.path = "channel";
    duplicateField.type = "STRING";
    duplicateField.destinations.add("SPAN");
    duplicateRequestPath.bodyEventPolicies.getFirst().fields.add(duplicateField);

    DynamicPolicy outgoing = DynamicPolicy.parse(bodyEventPolicy());
    outgoing.bodyEventPolicies.getFirst().direction = "OUTGOING";
    DynamicPolicy invalidDirection = DynamicPolicy.parse(bodyEventPolicy());
    invalidDirection.bodyEventPolicies.getFirst().direction = "SIDEWAYS";

    assertTrue(PolicyValidator.validate(samePathInBothBodies).isEmpty());
    assertTrue(
        PolicyValidator.validate(duplicateRequestPath).stream()
            .anyMatch(error -> error.contains("duplicated JSON path channel")));
    assertTrue(PolicyValidator.validate(outgoing).isEmpty());
    assertTrue(
        PolicyValidator.validate(invalidDirection).stream()
            .anyMatch(error -> error.contains("direction must be INCOMING or OUTGOING")));
  }

  @Test
  void acceptsAValidatedDerivedFieldAndRejectsUnknownInputs() throws Exception {
    DynamicPolicy valid = DynamicPolicy.parse(bodyEventPolicy());
    DynamicPolicy.DerivedField validField = derivedField("transaction.amount * 1.02");
    valid.bodyEventPolicies.getFirst().derivedFields.add(validField);

    DynamicPolicy unknown = DynamicPolicy.parse(bodyEventPolicy());
    unknown.bodyEventPolicies.getFirst().derivedFields.add(
        derivedField("transaction.unknown * 1.02"));

    DynamicPolicy oldSchema = DynamicPolicy.parse(bodyEventPolicy());
    oldSchema.schemaVersion = "1.2";
    oldSchema.bodyEventPolicies.getFirst().derivedFields.add(
        derivedField("transaction.amount * 1.02"));

    var validErrors = PolicyValidator.validate(valid);
    assertTrue(validErrors.isEmpty(), () -> validErrors.toString());
    assertTrue(
        PolicyValidator.validate(unknown).stream()
            .anyMatch(error -> error.contains("unknown or non-numeric field")));
    assertTrue(
        PolicyValidator.validate(oldSchema).stream()
            .anyMatch(error -> error.contains("schemaVersion 1.3")));
  }

  private static String validPolicy(String metricName, String instrument) {
    return """
        {
          "schemaVersion": "1.3",
          "requestHeaders": [{"name": "x-request-id"}],
          "responseHeaders": [{"name": "x-rate-type"}],
          "deniedHeaders": [{"name": "authorization"}],
          "metricPolicies": [{
            "id": "http-v1",
            "enabled": true,
            "value": {
              "source": "DURATION",
              "argumentIndex": -1,
              "path": "",
              "constant": 1
            },
            "name": "test.http.duration",
            "instrument": "HISTOGRAM",
            "unit": "s",
            "description": "Controlled HTTP duration",
            "standardAttributes": ["http.request.method", "http.route"],
            "customAttributes": [{
              "source": "REQUEST_HEADER",
              "argumentIndex": -1,
              "path": "",
              "constant": 1,
              "header": "x-client-channel",
              "attribute": "client.channel",
              "destinations": ["SPAN"],
              "valuePolicy": {
                "type": "ENUM",
                "allowed": ["WEB", "API"],
                "fallback": "OTHER",
                "ranges": []
              }
            }],
            "buckets": [0.01, 0.1, 1]
          }],
          "methodPolicies": [{
            "id": "method-v1",
            "enabled": true,
            "packagePrefix": "dev.o11y.rates.service",
            "className": "dev.o11y.rates.service.ExchangeRateCalculator",
            "methodName": "calculate",
            "captures": [{
              "source": "ARGUMENT",
              "argumentIndex": 0,
              "path": "customerType",
              "constant": 1,
              "attribute": "customer.type",
              "type": "STRING",
              "destinations": ["SPAN", "METRIC", "LOG"],
              "valuePolicy": {
                "type": "ENUM",
                "allowed": ["SALARY_ACCOUNT", "STANDARD"],
                "fallback": "OTHER",
                "ranges": []
              }
            }],
            "metrics": [{
              "name": "%s",
              "instrument": "%s",
              "unit": "1",
              "description": "Business events",
              "value": {
                "source": "CONSTANT",
                "argumentIndex": -1,
                "path": "",
                "constant": 1
              },
              "buckets": []
            }],
            "log": {
              "enabled": true,
              "severity": "INFO",
              "body": "Business method completed"
            }
          }]
        }
        """.formatted(metricName, instrument);
  }

  private static DynamicPolicy policyWithHttpMetrics(String prefix, int count) {
    DynamicPolicy policy = new DynamicPolicy();
    for (int index = 0; index < count; index++) {
      DynamicPolicy.HttpMetricPolicy metric = new DynamicPolicy.HttpMetricPolicy();
      metric.id = prefix + ".id." + index;
      metric.name = prefix + ".metric." + index;
      metric.instrument = "COUNTER";
      metric.unit = "1";
      metric.value.source = "CONSTANT";
      metric.value.constant = 1;
      policy.metricPolicies.add(metric);
    }
    return policy;
  }

  private static DynamicPolicy.AttributeSource boundedAttribute(String attribute, String header) {
    DynamicPolicy.AttributeSource source = new DynamicPolicy.AttributeSource();
    source.source = "REQUEST_HEADER";
    source.attribute = attribute;
    source.header = header;
    source.destinations.add("SPAN");
    source.valuePolicy = boundedValues();
    return source;
  }

  private static DynamicPolicy.ValuePolicy boundedValues() {
    DynamicPolicy.ValuePolicy policy = new DynamicPolicy.ValuePolicy();
    policy.allowed =
        new java.util.ArrayList<>(
            List.of("A", "B", "C", "D", "E", "F", "G", "H"));
    policy.fallback = "OTHER";
    return policy;
  }

  private static String bodyEventPolicy() {
    return """
        {
          "schemaVersion": "1.3",
          "requestHeaders": [],
          "responseHeaders": [],
          "deniedHeaders": [{"name": "authorization"}],
          "deniedBodyPaths": [{"name": "$.password"}],
          "metricPolicies": [],
          "methodPolicies": [],
          "bodyEventPolicies": [{
            "id": "transfer-approved-v1",
            "enabled": true,
            "ruleName": "Approved transfer",
            "direction": "INCOMING",
            "requestContentType": "application/json",
            "responseContentType": "application/json",
            "conditions": [
              {"source": "REQUEST_PATH", "path": "", "operator": "EQUALS", "values": ["/api/transfer"]},
              {"source": "REQUEST_METHOD", "path": "", "operator": "EQUALS", "values": ["POST"]},
              {"source": "RESPONSE_STATUS", "path": "", "operator": "IN", "values": ["200", "201"]},
              {"source": "RESPONSE_BODY", "path": "status", "operator": "EQUALS", "values": ["APPROVED"]}
            ],
            "eventName": "transfer-approved",
            "staticAttributes": [
              {
                "attribute": "event.type",
                "value": "transfer-approved",
                "type": "STRING",
                "destinations": ["SPAN", "LOG"]
              }
            ],
            "maxBodyBytes": 65536,
            "fields": [
              {
                "attribute": "business.channel",
                "source": "REQUEST_BODY",
                "path": "channel",
                "type": "STRING",
                "destinations": ["SPAN", "LOG", "METRIC"],
                "valuePolicy": {"type": "ENUM", "allowed": ["MOBILE", "WEB"], "fallback": "OTHER", "ranges": []}
              },
              {
                "attribute": "transaction.amount",
                "source": "REQUEST_BODY",
                "path": "amount",
                "type": "DOUBLE",
                "destinations": ["SPAN", "LOG"],
                "valuePolicy": {"type": "RANGE", "allowed": [], "fallback": "OTHER", "ranges": []}
              },
              {
                "attribute": "business.result",
                "source": "RESPONSE_BODY",
                "path": "status",
                "type": "STRING",
                "destinations": ["SPAN", "LOG"],
                "valuePolicy": {"type": "ENUM", "allowed": ["APPROVED"], "fallback": "OTHER", "ranges": []}
              }
            ],
            "log": {"enabled": true, "severity": "INFO", "body": "Transfer approved"}
          }],
          "eventMetricPolicies": [
            {
              "id": "transfer-count-v1",
              "enabled": true,
              "eventName": "transfer-approved",
              "name": "biz.transfer.count",
              "instrument": "COUNTER",
              "unit": "1",
              "description": "Approved transfers",
              "valueField": "",
              "dimensions": ["business.channel"],
              "buckets": []
            },
            {
              "id": "transfer-amount-v1",
              "enabled": true,
              "eventName": "transfer-approved",
              "name": "biz.transfer.amount",
              "instrument": "HISTOGRAM",
              "unit": "1",
              "description": "Approved transfer amount",
              "valueField": "transaction.amount",
              "dimensions": ["business.channel"],
              "buckets": [100, 500, 1000, 3000, 10000]
            }
          ]
        }
        """;
  }

  private static DynamicPolicy.DerivedField derivedField(String expression) {
    DynamicPolicy.DerivedField field = new DynamicPolicy.DerivedField();
    field.attribute = "transaction.standard_amount";
    field.expression = expression;
    field.type = "DOUBLE";
    field.destinations.add("SPAN");
    field.destinations.add("LOG");
    return field;
  }
}
