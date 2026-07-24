package dev.o11y.agent.http.client.webclient;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.springframework.web.reactive.function.client.ExchangeFilterFunction;
import org.springframework.web.reactive.function.client.WebClient;

class SpringWebClientBuilderTypeInstrumentationTest {
  @Test
  void installsExactlyOneOutermostPolicyFilterWithoutReorderingUserFilters() {
    WebClient.Builder builder = WebClient.builder();
    ExchangeFilterFunction first = (request, next) -> next.exchange(request);
    ExchangeFilterFunction later = (request, next) -> next.exchange(request);
    builder.filter(first);
    SpringWebClientBridge.install(builder);
    builder.filter(later);
    SpringWebClientBridge.install(builder);

    AtomicReference<List<ExchangeFilterFunction>> snapshot = new AtomicReference<>();
    builder.filters(filters -> snapshot.set(new ArrayList<>(filters)));

    assertEquals(3, snapshot.get().size());
    assertTrue(snapshot.get().get(0).getClass().getName().contains("PolicyFilter"));
    assertEquals(first, snapshot.get().get(1));
    assertEquals(later, snapshot.get().get(2));
    assertEquals(
        1,
        snapshot.get().stream()
            .filter(filter -> filter.getClass().getName().contains("PolicyFilter"))
            .count());
  }
}
