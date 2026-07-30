# O11y Java Agent Extension

Extensión del OpenTelemetry Java Agent que recibe policies por OpAMP HTTP y
produce atributos de span, logs correlacionados y métricas OTLP para HTTP,
métodos Java y mensajería. OpenTelemetry Operator inyecta el Java Agent con la extensión embebida.

## Compatibilidad

- JDK 21 y Maven 3.9+;
- compatibilidad exacta con OpenTelemetry Java Agent `2.28.1`;
- versión `2.28.1-o11y.9`: schema máximo 1.6 y puerta adicional para
  Quarkus `3.33.2.1` LTS en modo JVM.

## Compilar y probar

Desde esta carpeta:

```bash
mvn -B -ntp clean verify
```

La puerta Quarkus, que incluye HTTP, método, Kafka y JMS, se ejecuta aparte:

```bash
mvn -B -ntp -Pquarkus-smoke clean verify

mvn -B -ntp \
  -Pquarkus-smoke \
  -Dquarkus.smoke.stack=resteasy-classic-undertow \
  clean verify
```

## Construir imágenes

```bash
docker build \
  --target javaagent-runtime \
  --tag o11y-java-agent:dev \
  .
```

`javaagent-runtime` entrega un solo `opentelemetry-javaagent.jar` con la
extensión embebida. El workflow publica esa misma distribución como imagen
multi-arquitectura y como JAR adjunto al GitHub Release.

```bash
docker pull ghcr.io/wjma90/o11y-java-agent:2.28.1-o11y.9

gh release download v2.28.1-o11y.9 \
  --repo wjma90/otel-opamp-java-extension \
  --pattern 'o11y-java-agent-*.jar'
```

## Configuración

En Kubernetes, el CR `Instrumentation` define OTLP, OpAMP y el poll. Los
microservicios sólo declaran normalmente:

```text
OTEL_SERVICE_NAME
OTEL_RESOURCE_ATTRIBUTES
```

Variables y validaciones:
# Configuración

La extensión se configura al arrancar y recibe sus policies dinámicas mediante
OpAMP. No necesita una base de datos ni un archivo de policy local.

## Variables de entorno

| Variable | Default | Validación | Propósito |
|---|---|---|---|
| `OTEL_SERVICE_NAME` | `java-service` | 1–255 caracteres ASCII imprimibles | Identidad principal del agente |
| `OTEL_RESOURCE_ATTRIBUTES` | vacío | hasta 64 pares `key=value`; valor hasta 128 caracteres | Selectores e inventario OpAMP |
| `OPAMP_ENDPOINT` | `http://localhost:4320/v1/opamp` | URL absoluta HTTP(S), sin credenciales, query ni fragment | Endpoint del Control Plane |
| `OPAMP_TOKEN` | vacío | hasta 4096 caracteres ASCII visibles | Bearer token máquina-a-máquina |
| `OPAMP_TLS_CA_FILE` | vacío | archivo PEM X.509 de hasta 1 MiB; requiere endpoint HTTPS | CA privada adicional para OpAMP |
| `OPAMP_POLL_INTERVAL_SECONDS` | `10` | se acota entre 2 y 300 | Frecuencia de polling y retry |
| `O11Y_METHOD_PACKAGES` | autodetección | hasta 32 packages y 4096 caracteres | Override excepcional de instrumentación por método |

`OTEL_SERVICE_VERSION` y `OTEL_DEPLOYMENT_ENVIRONMENT` se reportan por
compatibilidad cuando existen. Para nuevo despliegue se prefieren
`service.version` y `deployment.environment.name` dentro de
`OTEL_RESOURCE_ATTRIBUTES`.

En Kubernetes estas variables de OpAMP pertenecen al CR `Instrumentation`; no
deben repetirse en cada Deployment. Los servicios sólo declaran normalmente:

```yaml
env:
  - name: OTEL_SERVICE_NAME
    value: exchange-service
  - name: OTEL_RESOURCE_ATTRIBUTES
    value: service.namespace=app,deployment.environment.name=local
```

## Transporte OpAMP

El cliente inicia requests HTTP periódicos al Control Plane. Reintenta fallos de
conexión con el mismo intervalo configurado y reporta atributos que permiten a
la UI mostrar `http-poll` y el intervalo efectivo. No usa WebSocket.

Una respuesta sólo se confirma como `APPLIED` después de que el JSON completo
fue parseado, validado y activado. Una respuesta inválida produce `FAILED` con
un mensaje acotado y no reemplaza la generación previa.

`OPAMP_TLS_CA_FILE` amplía la confianza normal de la JVM únicamente para el
cliente OpAMP. No desactiva la verificación del hostname, no modifica el
truststore global y no configura los exportadores OTLP. Monte el archivo como
Secret y use un hostname presente en el SAN del certificado servidor. La
conexión con CA adicional exige TLS 1.3.

## Gobierno de capturas

La extensión valida sintaxis, schema, límites, compatibilidad e identidad de
instrumentos. La denylist de headers, query/path params, propiedades JMS y
rutas de body/payload vive en el Control Plane. Por eso una policy debe
publicarse desde el Control Plane y no enviarse directamente al endpoint OpAMP
saltando sus validaciones de gobierno.

## Exportación OTLP

Traces, métricas y logs usan la configuración estándar del OpenTelemetry Java
Agent (`OTEL_EXPORTER_OTLP_*`). En el despliegue Kubernetes del proyecto esos
valores también los inyecta `Instrumentation`. La extensión no contiene un
endpoint Collector hardcodeado.


## Documentación

Las guías extensas son canónicas en `SUPPORT`[aquí](./SUPPORT.md)
