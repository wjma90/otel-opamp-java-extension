# Compatibilidad y soporte

Esta matriz separa tres conceptos:

- **rango objetivo**: el matcher y el adaptador fueron diseñados para esa API;
- **versión probada**: una prueba del árbol actual ejercita esa versión;
- **certificada**: la prueba forma parte de un `clean verify` exitoso de una
  release publicada.

Una coincidencia de clases, una unitaria o una versión dentro del rango objetivo
no bastan para declarar certificación. La release publicada
`2.28.1-o11y.6` incorpora schema 1.6 y conserva como certificadas únicamente
las combinaciones ejercitadas por sus puertas de release. El detalle y los
comandos reproducibles están en [Quarkus JVM](QUARKUS-JVM.md).

## Plataforma

| Componente | Mínimo | Release `2.28.1-o11y.6` |
| --- | ---: | ---: |
| Java runtime | 21 | 21 |
| Maven | 3.9 | Maven Wrapper / 3.9+ |
| OpenTelemetry Java Agent | 2.28.1 | 2.28.1 exacto |
| Schema de policy | 1.0 | máximo 1.6 |

La compatibilidad con el Java Agent es exacta, no un rango `2.x`. Headers y
query dentro de reglas HTTP requieren 1.4; path params y mensajería requieren
1.5. Los schemas 1.0–1.4 conservan la semántica anterior.

## HTTP entrante

| API o runtime | Alcance objetivo | Versión probada | Estado |
| --- | ---: | ---: | --- |
| Jakarta Servlet | desde API 6.0.0 | 6.0.0 y 6.1.0 | Certificada en `2.28.1-o11y.6` |
| Apache Tomcat Jakarta | desde 10.1.0 | 10.1.0 y 11.0.21 | Certificada en `2.28.1-o11y.6` |
| Javax Servlet | desde API 4.0 | API 4.0.1 | Implementada en módulo separado; falta smoke con contenedor real |
| Quarkus REST sobre Vert.x | versión exacta 3.33.2.1/JVM, HTTP/1.1 | 3.33.2.1 LTS | Certificada en `2.28.1-o11y.6`; módulo dedicado, no Servlet |
| RESTEasy Classic + Undertow | versión exacta 3.33.2.1/JVM, HTTP/1.1 | Quarkus 3.33.2.1 LTS | Certificada en `2.28.1-o11y.6` mediante Jakarta Servlet |

Jakarta y Javax usan módulos y helpers diferentes. El módulo Javax sólo se
activa cuando el classloader de la aplicación contiene `javax.servlet`; no
carga tipos Jakarta ni exige agregar O11y al classpath. Está pensado para
containers Java EE 8/Tomcat 9. Jetty, Undertow, JBoss y otros containers que
implementen el mismo contrato pueden ser compatibles, pero no quedan
certificados sin un smoke por runtime.

Quarkus REST no atraviesa la API Servlet: la release instala un módulo
específico sobre el request context de Quarkus REST/Vert.x. RESTEasy Classic
con Undertow sí atraviesa Jakarta Servlet y reutiliza el módulo Servlet
existente; la prueba exige que una misma regla se emita exactamente una vez.
Esto no declara soporte para handlers o rutas creados directamente con el
`Router` genérico de Vert.x. La puerta fuerza HTTP/1.1. HTTP/2 y h2c no están
certificados en esta release ni se promete allí la misma correlación con el
span servidor del Java Agent `2.28.1`.

## HTTP saliente

Todas las librerías usan el mismo contrato `direction=OUTGOING`, condiciones
request/response, captura acotada y destinos span/log/métrica.

| Librería | Rango objetivo desde | Versión probada en el árbol | Alcance | Estado de release |
| --- | ---: | ---: | --- | --- |
| Spring `RestClient` | 6.1.0 | 6.1.0 y 7.0.7 | Síncrono, builder/factory | Certificada en `2.28.1-o11y.6` |
| Apache HttpClient 4 | 4.3 | 4.5.14 | `CloseableHttpClient` | Certificada en `2.28.1-o11y.6` |
| OkHttp | 3.4 | 5.3.2 | Síncrono y callback asíncrono | Certificada en `2.28.1-o11y.6` |
| Spring `WebClient` | 6.0.0 | 7.0.7 + Reactor Netty 1.3.6 | Flujo reactivo con captura preservando backpressure | Certificada en `2.28.1-o11y.6` |
| JDK `java.net.http.HttpClient` | Java 21 | Java 21 | `send` y `sendAsync`, BodyPublisher/BodyHandler acotados | Certificada en `2.28.1-o11y.6` |
| Apache HttpClient 5 | 5.0 | 5.5.2 | Classic y facade `Simple` async | Certificada en `2.28.1-o11y.6` |

El mínimo efectivo del JDK HttpClient es Java 21 porque ése es el piso del
producto, aunque la API exista desde Java 11. El soporte Apache 5 async cubre la
facade completamente bufferizada `SimpleHttpRequest`; no promete todas las APIs
streaming/reactive del cliente. WebClient se prueba con su connector Reactor
Netty por defecto.

Los rangos mínimos de Apache 4/5, OkHttp y WebClient siguen siendo rangos
objetivo. Sólo las versiones exactas incorporadas a un `clean verify` exitoso
pueden declararse certificadas.

## Fuentes HTTP por schema

| Fuente | Schema mínimo | Dirección | Semántica |
| --- | ---: | --- | --- |
| `REQUEST_BODY` / `RESPONSE_BODY` | 1.3 | ambas | escalar de JSON completo y acotado |
| `REQUEST_HEADER` / `RESPONSE_HEADER` | 1.4 | ambas | header nombrado; case-insensitive |
| `REQUEST_QUERY` | 1.4 | ambas | query nombrado; case-sensitive |
| `REQUEST_PATH_PARAM` | 1.5 | sólo `INCOMING` | variable nombrada de un `REQUEST_PATH` template |

Para `REQUEST_PATH_PARAM`, la condición de path debe declarar por ejemplo
`/accounts/{accountId}` y la fuente usa `accountId`. Spring MVC aporta el mapa
resuelto cuando está disponible; el fallback compara segmentos exactos del
template Servlet. No hay wildcard, regex ni soporte saliente.

Una condición ausente no coincide. Un campo extraído ausente se omite. Headers
y query multivaluados usan sólo el primer valor retenido. No se exportan bodies
completos ni se inventan valores.

## Kafka y JMS

| Tecnología | Scope | API objetivo | Evidencia actual | Estado |
| --- | --- | --- | --- | --- |
| Kafka producer | `KAFKA_PRODUCER` | `KafkaProducer.send(ProducerRecord)` | unitarias y Quarkus SmallRye Kafka 4.33.0/Kafka Clients 4.1.2 con broker KRaft real | Incluida en `2.28.1-o11y.6` |
| Kafka consumer | `KAFKA_CONSUMER` | `KafkaConsumer.poll` | unitarias y registro real producido/consumido, con máximo 1000 por poll | Incluida en `2.28.1-o11y.6` |
| JMS producer | `JMS_PRODUCER` | `MessageProducer.send(..., Message, ...)` y `JMSProducer.send(Destination, Message/String/byte[])` en `javax.jms` y `jakarta.jms` | unitarias y Artemis Jakarta client/provider 2.52.0 real | Incluida en `2.28.1-o11y.6` |
| JMS consumer | `JMS_CONSUMER` | `MessageConsumer`, `JMSConsumer`, `MessageListener` | unitarias y mensaje real enviado/recibido con Artemis 2.52.0 | Incluida en `2.28.1-o11y.6` |

La puerta `quarkus-smoke` de la release levanta brokers efímeros reales dentro
del proceso de prueba y ejecuta producer y consumer de Kafka y JMS con el Java
Agent oficial y el JAR empaquetado. Las versiones anteriores son versiones
exactas probadas por esa puerta, no rangos mínimos.

La regresión de matchers cubre el contrato genérico usado por ambos namespaces:
`MessageProducer`, `JMSProducer`, `MessageConsumer`, `JMSConsumer` y
`MessageListener`, incluidos `receiveNoWait` y los payloads admitidos. El smoke
con broker real usa Jakarta `JMSProducer.send(..., String)` y
`JMSConsumer.receive(long)`; no se afirma que cada overload o el namespace
Javax haya sido recorrido contra un provider real.

Payload soportado:

- Kafka: `String` y `byte[]` que contengan JSON UTF-8 completo;
- JMS: texto de `TextMessage` o `String`/`byte[]` de los overloads de
  conveniencia indicados, con JSON completo;
- headers Kafka: sólo valores bytes con UTF-8 válido;
- propiedades JMS: String, Number o Boolean.

No se soportan serializers arbitrarios, Avro/Protobuf, Kafka Streams, Spring
Cloud Stream como API propia, los overloads `JMSProducer` con `Map` o
`Serializable`, `BytesMessage`, `MapMessage`, `ObjectMessage`, `StreamMessage`,
correlación request/reply ni join producer/consumer dentro de la extensión.

En tiempo de ejecución sólo se leen los headers y propiedades nombrados por la
policy compilada. Los headers Kafka no solicitados no materializan su valor y
las propiedades JMS no se enumeran. En `KAFKA_CONSUMER`, un destino `SPAN`
enriquece como máximo el contexto activo alrededor de `poll`; no crea ni
representa el span de procesamiento de `@KafkaListener`.

## Método Java

La captura por método usa clases de aplicación descubiertas de forma segura y
lee argumentos, retorno, duración o constantes. En la release
`2.28.1-o11y.6`, un fast-jar de Quarkus resuelve el artefacto de aplicación
desde su layout/metadata y excluye clases generadas y dependencias; el smoke de
Quarkus 3.33.2.1 comprueba argumento, retorno, span, log e Histogram sin
`O11Y_METHOD_PACKAGES`. Si el artefacto es ambiguo, falla cerrado.

No captura variables locales, no abre campos privados y no fusiona el scope de
método con HTTP o mensajería.

## No soportado actualmente

- clientes HTTP diferentes de la matriz anterior;
- aplicaciones Quarkus compiladas como native image;
- handlers HTTP creados directamente con el `Router` genérico de Vert.x;
- XML, multipart, protobuf o bodies/payloads arbitrarios para extracción;
- path params en HTTP saliente;
- variables locales Java;
- correlación síncrona HTTP+método o producer+consumer como un solo evento;
- ACK asíncrono Kafka, éxito del `@KafkaListener` de Spring, commit de offset,
  retry o DLQ como condición de policy;
- runtimes/middleware sin smoke indicado en esta matriz.

El Java Agent oficial puede producir spans estándar para otras tecnologías.
Eso no implica que la extensión pueda aplicarles una policy dinámica.

## Arquitecturas de imagen

El workflow publica manifiestos OCI para `linux/amd64` y `linux/arm64`. El JAR
es independiente de arquitectura. No se publican imágenes Windows.
