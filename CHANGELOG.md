# Changelog

Todos los cambios relevantes de este componente se documentan aquí. El formato
sigue [Keep a Changelog](https://keepachangelog.com/es-ES/1.1.0/). La versión
del producto identifica el agente compatible y añade una revisión O11y
independiente.

## [Unreleased]

## [2.28.1-o11y.10] - 2026-07-30

### Changed

- El smoke Servlet entrega su `PolicySet` por OpAMP HTTP/protobuf real y exige
  la confirmación `APPLIED` del Java Agent antes de validar la telemetría.

## [2.28.1-o11y.9] - 2026-07-30

### Changed

- La distribución pública se reduce al Java Agent con la extensión embebida:
  una imagen OCI multi-arquitectura y el mismo agente combinado como JAR del
  GitHub Release. Ya no se publica una imagen ni un JAR de extensión aislados.
- El contexto TLS con CA OpAMP adicional exige TLS 1.3; conserva validación de
  hostname y evita negociar protocolos obsoletos.
- Los builds Quarkus anidados usan el Maven disponible en la imagen de CI y ya
  no dependen de un Maven Wrapper ausente.

### Fixed

- El ensamblado del JAR combinado conserva el UID/GID del runner de Actions,
  permitiendo fijar permisos de sólo lectura antes de publicar el asset.
- Los fixtures y smoke tests declaran su consumo JSON, ejecutan Java sin shell
  ni comando configurable y reservan puertos sólo sobre loopback, resolviendo
  los cuatro hallazgos bloqueantes del gate Semgrep.

## [2.28.1-o11y.6] - 2026-07-24

### Added

- `OPAMP_TLS_CA_FILE` permite añadir una CA privada o autofirmada al truststore
  JVM sólo para el cliente OpAMP; exige HTTPS y conserva la verificación de
  hostname.

### Changed

- El poll OpAMP exitoso `o11y_opamp=online` se registra en nivel `FINE`
  (`DEBUG`) para evitar una línea `INFO` cada 10 segundos. Los fallos continúan
  en `WARNING`.

## [2.28.1-o11y.5] - 2026-07-23

### Added

- Schema `1.6` para enriquecer métricas de eventos HTTP con
  `http.request.method`, `http.route`, `http.response.status_code` y
  `error.type`, derivados del contexto instrumentado y no de headers de
  negocio.
- Las métricas creadas por policy conservan automáticamente los resource
  attributes del SDK OpenTelemetry de cada instancia.

### Changed

- `http.route` usa la plantilla de ruta del span servidor y se omite cuando no
  está disponible; no se acepta para HTTP saliente.

### Fixed

- Los eventos Servlet conservan la excepción real y normalizan a status 500
  cuando el contenedor aún expone un 2xx provisional.

### Added

- Puerta JVM real para Quarkus 3.33.2.1 LTS: Quarkus REST sobre Vert.x,
  RESTEasy Classic con Undertow, captura por método sobre fast-jar y policies
  Kafka/JMS con brokers efímeros.
- Descubrimiento seguro del package de aplicación en layouts Quarkus fast-jar,
  runner y uber-jar, sin requerir `O11Y_METHOD_PACKAGES` en el fast-jar válido.
- Pruebas de matcher JMS para los overloads soportados de `MessageProducer`,
  `JMSProducer`, `MessageConsumer`, `JMSConsumer` y `MessageListener`.

### Fixed

- Los overloads `JMSProducer.send(Destination, String/byte[])` ya no duplican
  telemetría cuando el provider delega internamente al overload `Message`.
- La captura Quarkus conserva el contexto del span servidor, libera su estado
  al cerrar o abortar y falla de forma segura si no puede registrar el handler
  de cierre.
- Jakarta Servlet propaga excepciones al cierre del intercambio y usa status
  500 cuando el contenedor todavía reporta un 2xx provisional; el matcher
  distingue correctamente `service` (2 argumentos) de `doFilter` (3).

## [2.28.1-o11y.3] - 2026-07-22

### Added

- Schema `1.5` para path parameters HTTP con nombre y eventos de mensajería
  independientes por producer/consumer de Kafka y JMS.
- Captura HTTP saliente para Spring `WebClient` sobre Reactor Netty,
  `java.net.http.HttpClient` y Apache HttpClient 5.x, además de los clientes ya
  soportados.
- Módulo aislado para aplicaciones `javax.servlet` 4.x dentro del mismo JAR de
  extensión, sin cargar simultáneamente las APIs Jakarta y Javax.
- Policies Kafka/JMS con topic o destination obligatorio, headers o properties
  explícitos, payload JSON acotado y salidas a span, log, Counter o Histogram.

### Changed

- `event.name` queda documentado como un atributo OTel normal definido por la
  policy; no renombra spans, logs ni métricas y tampoco crea un Span Event.
- Producer y consumer se evalúan como operaciones asíncronas separadas, sin un
  mapa global ni una correlación implícita en memoria.
- Kafka y JMS leen únicamente los headers y propiedades seleccionados por la
  generación activa; los datos no solicitados no se materializan.
- JDK HttpClient asíncrono cierra el estado ante cancelación y WebClient espera
  la finalización real de todos los grupos de `writeAndFlushWith`.

### Fixed

- Una excepción síncrona en `javax.servlet` termina la captura como fallo y no
  puede emitir un falso evento exitoso con el status 2xx inicial.

## [2.28.1-o11y.2] - 2026-07-21

### Changed

- Las reglas HTTP pueden omitir `fields` cuando sólo cuentan coincidencias o
  producen otra salida explícita.
- La validación rechaza reglas activas que no enriquecerían un span, no
  emitirían un log y no registrarían ninguna métrica.
- La documentación presenta la misma capacidad como un único flujo
  **Cuándo → Datos capturados → Salidas**, manteniendo compatibles las claves
  JSON históricas.

## [2.28.1-o11y.1] - 2026-07-21

### Added

- Schema `1.4` para usar request headers, response headers y query params como
  condiciones o campos de un evento HTTP correlacionado, tanto entrante como
  saliente.
- Laboratorio manual con fixture Servlet en el host, Control Plane/Collector
  efímeros, distribución real de la policy mediante OpAMP y ejecución del mismo
  Java Agent combinado que produce la imagen final.

### Changed

- La documentación agrupa HTTP entrante, método Java y HTTP saliente bajo el
  concepto **Evento de telemetría**; las claves JSON anteriores permanecen
  compatibles.
- Headers directos y de evento usan presupuestos independientes; valores
  multivaluados tienen semántica `FIRST`, request/response sólo se almacenan
  cuando la regla necesita ese lado y el schema se compara como `major.minor`.
- Workflow, paths de seguridad y documentación de release parten de la raíz de
  un repositorio independiente y usan tags `v<versión>`.
- La versión inmutable del producto se separa del OpenTelemetry Java Agent
  oficial compatible: `2.28.1-o11y.1` usa y verifica el agente `2.28.1`.

## [2.28.1] - 2026-07-20

### Added

- Polling OpAMP por HTTP con intervalo de 10 segundos, reintento y confirmación
  individual de remote config.
- Aplicación atómica de múltiples policies y retiro a un `PolicySet` vacío.
- Captura HTTP entrante para Jakarta Servlet y saliente para Spring
  `RestClient`, Apache HttpClient 4.x y OkHttp.
- Captura acotada de headers y bodies JSON de request/response sin consumir el
  stream de la aplicación.
- Business events que combinan request y response, campos extraídos y
  expresiones numéricas calculadas.
- Atributos de span, logs OTLP correlacionados y métricas dinámicas con control
  de cardinalidad.
- Instrumentación de métodos por argumentos, retorno, duración y valores
  constantes, con descubrimiento seguro del package de aplicación.
- JAR sombreado y reproducible con dependencias privadas relocadas.
- Imágenes separadas para la extensión y para el Java Agent con la extensión
  embebida.
- Workflow de release con compatibilidad mínima, SBOM, checksums, provenance,
  digests OCI y scan Trivy por arquitectura.

### Security

- Tamaños, cardinalidad, JSON, nombres e identidades de instrumentos se validan
  antes de activar una generación.
- La extensión no contiene una denylist de negocio; el gobierno de datos
  sensibles corresponde al Control Plane.
- El Java Agent oficial queda fijado por versión y SHA-256 en metadata de
  release antes de construir la imagen embebida.
