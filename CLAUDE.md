# Control Web — notas para Claude y para el desarrollador

Centro de control de webs: vigila sitios (uptime, latencia, SSL), guarda histórico, abre/cierra incidencias,
avisa por Telegram y expone una API REST privada (JWT) y un endpoint público de estado.
Proyecto de portfolio: prima el código limpio, probado (TDD) y bien explicado.

## Reglas de trabajo
- Código y comentarios en **inglés**; README y documentación en **español**.
- Tests primero, **un commit por paso**, y parar tras cada paso hasta que el usuario confirme.
- **Ningún secreto en el repositorio**: todo por variables de entorno (ver `.env.example`).
- Windows + PowerShell, portátil de 8 GB de RAM: Docker ligero (un solo PostgreSQL con `mem_limit`).
- No tocar ni asumir nada del VPS de Hetzner (Caddy + PostgreSQL 16, `status-api.josemariamerchan.dev`). El despliegue no es de la fase 1.
- Plan completo aprobado: `C:\Users\X420\.claude\plans\lazy-gathering-hopcroft.md` (pasos 0–10).

## Decisiones de arquitectura
- **Spring Boot 4.1** (la 3.5 ya no tiene soporte OSS), **Java 21 LTS**, Maven con Maven Wrapper.
- Paquetes **por funcionalidad con capas dentro** (`site/`, `monitoring/`, `incident/`, `alert/`, `auth/`, `publicstatus/`).
  Raíz: `dev.josemariamerchan.controlweb`.
- El esquema lo gestiona **Flyway** (`db/migration`); Hibernate solo valida (`ddl-auto: validate`).
- `spring.jpa.open-in-view=false`.
- Tests de integración con **Testcontainers** (`postgres:16-alpine`) mediante `@ServiceConnection`
  (`TestcontainersConfiguration`). Surefire limitado a `-Xmx512m`.
- Previsto en pasos siguientes: JWT con `oauth2-resource-server` (HS256), un único `@Scheduled` que elige los sitios
  pendientes, índice único parcial para no duplicar incidencias, eventos `AFTER_COMMIT` para las alertas,
  protección SSRF (`app.checker.allow-private-hosts`).

## Comandos (PowerShell)
Requiere JDK 21 y `JAVA_HOME` apuntando a él (`mvnw` lo usa). Docker Desktop debe estar en marcha para los tests.

```powershell
Copy-Item .env.example .env        # una sola vez; rellena DB_PASSWORD
docker compose up -d               # PostgreSQL de desarrollo
.\mvnw.cmd spring-boot:run         # arrancar la app
.\mvnw.cmd test                    # tests
.\mvnw.cmd verify                  # tests + empaquetado (se ejecuta antes de cada commit)
.\mvnw.cmd verify -Pfast          # solo para iterar: salta las clases @Tag("slow-scripts"); NUNCA antes de un commit
docker compose down                # parar la base de datos
```

## Estado
- Paso 0 (scaffold): hecho.
- Paso 1 (esquema Flyway V1 + entidades + tests de repositorio): hecho.
- Paso 2 (CRUD de sitios + protección SSRF): hecho.
- Paso 3 (seguridad JWT): hecho.
- Paso 4 (checker HTTP + SSL): hecho.
- Paso 5 (scheduler + comprobación manual): hecho.
- Paso 6 (motor de reglas, incidencias y eventos + API de reglas): hecho.
- Paso 7 (alertas por Telegram): hecho.
- Paso 8 (API de lectura: estado, histórico, estadísticas, incidencias): hecho.
- Paso 9 (endpoint público de estado): hecho.
- Paso 10 (pulido: retención, OpenAPI, CI, alertas en español, README, auditoría de secretos): hecho. **La fase 1 está completa.**
- Pendiente (fases siguientes, solo cuando el usuario lo pida): rate limiting del login (**obligatorio antes de desplegar**),
  Dockerfile y despliegue en Hetzner, dashboard React + Vite, refresh tokens.

## CRUD de sitios y SSRF (paso 2)
- `SiteUrlNormalizer` canonicaliza la URL antes de guardar (esquema y host en minúsculas, sin punto final en el host,
  sin puerto por defecto, sin fragmento, sin `/` raíz redundante) para que `UNIQUE(sites.url)` sea efectivo. Rechaza
  credenciales embebidas (`user:pass@host`) y esquemas distintos de http/https. Las rutas y queries no se tocan
  (una `/` final en una ruta real sí es significativa).
- `HostSafetyValidator` (`common/net`) es el guard SSRF: resuelve el host con un `HostResolver` **inyectable**
  (`DnsHostResolver` en producción, un fake en los tests) y rechaza loopback, privadas, link-local (169.254/16),
  multicast, reservadas, CGNAT y IPv4 embebida en IPv6 (mapped, compatible, NAT64, 6to4). Si CUALQUIER IP del host
  es no pública, se rechaza. Un host que no resuelve se acepta (el checker lo registrará como caído).
- Se activa con `app.checker.allow-private-hosts` (`false` por defecto; `true` en los perfiles `dev` y `test`).
  Se valida al crear/editar y **debe volver a validarse antes de cada petición y salto de redirección** (paso 4).
- Límite conocido: DNS rebinding entre la validación y la conexión (ventana mínima); cerrarlo exigiría fijar la IP.
- Al crear un sitio se crean sus 3 reglas por defecto (`app.alert-defaults.*` en `application.yml`).
- Borrar un sitio es **un solo `deleteById`**; checks, reglas e incidencias caen por `ON DELETE CASCADE`.
- Errores en `ProblemDetail` (`GlobalExceptionHandler`): 400 validación (con mapa `errors`), URL inválida o host no
  permitido; 404 sitio inexistente; 409 URL duplicada.
- Tests: perfil `test` activo por `pom.xml` (surefire). Un único contenedor PostgreSQL por JVM de test, compartido
  entre contextos (`TestcontainersConfiguration`); los tests que hacen commit deben limpiar sus datos.

## Modelo de datos (`V1__init.sql`)
Tablas `users`, `sites`, `checks`, `alert_rules`, `incidents`. Detalles que importan:
- `type` y `role` son `varchar` + `CHECK` (no ENUM de PostgreSQL), mapeados con `@Enumerated(STRING)`.
- `incidents` tiene el índice único parcial `uq_incidents_one_open_per_site_type (site_id, type) WHERE resolved_at IS NULL`:
  la base de datos impide dos incidencias abiertas del mismo tipo para un sitio (no se repiten alertas ni con concurrencia).
- Borrar un sitio borra en cascada (a nivel de BD) sus checks, reglas e incidencias.
- Las entidades no tienen setters públicos ni usan `Clock` internamente: reciben el `Instant` desde el servicio.
- Los tests de repositorio usan `@PostgresDataJpaTest` (PostgreSQL real con Flyway, no H2) para poder probar constraints.

## Checker HTTP + SSL (paso 4)
`HttpSiteChecker.check(url)` devuelve un `CheckResult` (up, statusCode, latencyMs, errorMessage, certExpiresAt); **no lanza
nunca**: DNS, timeout, conexión rechazada, TLS, interrupción (se conserva el flag) o un bug se convierten en un resultado
fallido. El paso 5 debe mapear `CheckResult` → entidad `Check` (hoy `Check.down(...)` no admite `cert_expires_at`; hay que
guardarlo también en checks fallidos por HTTP, p. ej. un 503 con certificado válido).
- **Cliente**: un único `HttpClient` (bean en `HttpClientConfig`), HTTP/1.1, `Redirect.NEVER`. Propiedades en
  `app.checker.*`: `connect-timeout` 5 s (conexión + handshake TLS), `request-timeout` 10 s (estado + cabeceras de CADA
  petición/salto), `max-redirects` 5, `user-agent` `ControlWeb/1.0`.
- **GET, no HEAD** (algunos servidores rechazan HEAD). Resultado `up` = estado final < 400 (un 401/403/404 cuenta como caído:
  apunta el sitio a una URL pública). La latencia es hasta recibir las cabeceras de la respuesta final, sumando los saltos.
- **El cuerpo no se descarga**: se usa `BodyHandlers.ofInputStream()` y se cierra el stream en cuanto llegan las cabeceras.
  `BodyHandlers.discarding()` NO sirve para esto: descarta los bytes pero los sigue leyendo del socket (un sitio que sirve
  un cuerpo interminable mantenía el check hasta el timeout y lo daba por caído; hay un test que lo demuestra).
- **Redirecciones a mano** (301/302/303/307/308 con `Location`, relativas resueltas): máximo 5, detección de bucle y de
  esquemas no http(s) o credenciales en el destino. Cada salto es una petición nueva que solo lleva `User-Agent` y `Accept`
  (no hay `CookieHandler`, nunca se envía `Authorization`/`Cookie`/`Referer`/`Origin`).
- **SSRF**: `HostSafetyValidator.assertSafe` se ejecuta antes de la primera petición y de CADA salto; si falla, el check se
  guarda como fallido con `Blocked: private address` y no se abre ninguna conexión. Hay tests para redirección a host
  privado, a `169.254.169.254` y para el cambio de DNS de público a privado entre dos checks.
- **Limitación conocida (TOCTOU / DNS rebinding)**: el guard resuelve el DNS y luego el `HttpClient` vuelve a resolverlo al
  conectar. Si un atacante controla el DNS del sitio, puede devolver una IP pública al guard y una privada al conectar
  (ventana de milisegundos). Re-validar en cada check y salto la reduce pero no la elimina. Cerrarla del todo exigiría
  conectar a la IP ya validada (fijar la IP resuelta en la conexión, manteniendo SNI y verificación de host) o aislar la red
  del proceso con un firewall de salida; queda fuera del MVP. **Antes de desplegar, bloquear en el firewall del servidor las
  conexiones salientes del contenedor hacia rangos privados/metadatos es la defensa en profundidad recomendada.**
- **Certificados**: si el handshake funciona se guarda `certExpiresAt` (el `notAfter` del certificado hoja, también si el
  sitio responde 5xx). Si falla, el check es fallido con `SSL error: the certificate has expired | is not valid yet | does
  not match the host name | is not trusted | TLS handshake failed`, sin stacktrace ni nombres de clase.
- **Higiene de mensajes y logs**: `error_message` ≤ 500 caracteres y sin query string, fragmento ni credenciales de URL
  (`MessageSanitizer`, aplicado en el constructor de `CheckResult`). Los logs solo llevan `scheme://host[:puerto]/ruta`,
  nunca la query, ni la traza de excepciones (en el `catch` final solo se registra el nombre de la clase).
- **Tests** sin WireMock ni archivos de claves: servidores `com.sun.net.httpserver` (HTTP/HTTPS) y certificados EC generados
  en memoria con BouncyCastle (solo `test`): CA de confianza, hoja válida, caducada, aún no válida, host incorrecto y CA
  desconocida (`TestServer`, `TestPki`).

## Endpoint público (paso 9)
`GET /api/public/status` es el ÚNICO endpoint de datos sin token (aparte de login y `/actuator/health`). Prioridad absoluta:
no filtrar nada.
- **Solo se leen datos públicos** (`PublicStatusLoader`): la primera consulta pide únicamente `public_visible AND active` y las
  demás se restringen a esos ids; el estado global sale de esos mismos sitios. Un sitio privado nunca entra en memoria, así que
  no puede influir en la respuesta (test: un sitio privado que cae deja la respuesta idéntica byte a byte). Siempre 4 sentencias.
- **Lista blanca estricta**: `{status, generatedAt, sites[{name, status, uptime30d}]}`. Sin URL, ids, latencias, textos de
  incidencias ni fechas de certificado. `PublicSiteView` solo tiene 3 campos; el test compara el conjunto EXACTO de claves JSON
  (añadir un campo rompe el build) y otro siembra URL, host, textos de error/incidencia y latencias reconocibles y comprueba que
  no aparecen en el cuerpo.
- **Estados** (`PublicState`): el estado derivado del paso 8 colapsado: UP, DEGRADED, DOWN; `PENDING` → UNKNOWN; los inactivos no
  salen. **Estado global**: sin sitios (o ninguno con estado conocido) → UNKNOWN; todos los conocidos DOWN → DOWN; algún DOWN o
  DEGRADED pero no todos caídos → DEGRADED (caída parcial); si no, UP. Los UNKNOWN se ignoran si hay alguno conocido. Sin sitios
  públicos: 200 con lista vacía y UNKNOWN. `uptime30d` = correctos/todos en `[ahora-30d, ahora]`, 2 decimales, `null` sin checks.
- **Caché** (`PublicStatusCache`, `app.public.cache-ttl` 15 s, con el `Clock`): una sola recarga a la vez (single flight) aunque
  lleguen cientos de peticiones; N peticiones dentro del TTL cuestan 0 sentencias adicionales. Si una recarga falla se sirve la
  última respuesta buena hasta 10×TTL (su `generatedAt` dice la verdad sobre su edad); sin respuesta reciente → 503 genérico con
  `Retry-After`. `generatedAt` es cuándo se leyó de la BD.
- **Seguridad**: solo la ruta EXACTA `/api/public/status` es pública (cualquier otra bajo `/api/public/` da 401 y un `POST` a esa
  ruta da 405). El filtro de tokens IGNORA la cabecera `Authorization` en esa ruta (un token caducado/basura no rompe la página
  pública; en el resto de rutas sigue siendo 401). `Cache-Control: public, max-age=30` solo en la respuesta 200 (los errores
  mantienen el no-cache de Spring Security), `X-Content-Type-Options: nosniff`, sin `Set-Cookie`. Errores como `ProblemDetail`:
  `GlobalExceptionHandler` tiene ahora una red final (`Exception` → 500 con mensaje fijo, sin clase/SQL/rutas) y un 503 genérico.
- **CORS propio** (`app.public.cors-allowed-origins` / `PUBLIC_CORS_ALLOWED_ORIGINS`): solo GET, sin credenciales, sin cabeceras
  extra, orígenes exactos; `*` o una ruta impiden arrancar (como `CORS_ALLOWED_ORIGINS`). Es independiente del CORS privado: el
  portfolio no puede llamar a la API privada ni el dashboard a la pública salvo que estén en ambas listas. Se registra antes que
  `/api/**` porque `UrlBasedCorsConfigurationSource` usa la primera coincidencia.
- Mutaciones verificadas: añadir la URL a la respuesta hace fallar los 2 tests de lista blanca; incluir sitios privados en el
  global hace fallar el test del punto 3 (y su variante sin sitios públicos).

## API de lectura (paso 8)
Todos los endpoints exigen JWT (el guardián los cubre), son solo `GET`, devuelven **DTOs (records), nunca entidades**, y no
tienen efectos secundarios (un test comprueba que solo se emiten `SELECT` y que ninguna fila cambia). Los errores son
`ProblemDetail`: parámetros inválidos → 400 (nunca 500), sitio/incidencia inexistente → 404.
- **`GET /api/status`**: `{generatedAt, summary{total,up,degraded,down,pending,paused}, sites[]}`. Cada sitio: `id, name, url,
  active, status, lastCheck{up,statusCode,latencyMs,checkedAt,certExpiresAt}|null, openIncidents[{type,since}]`. Orden estable:
  nombre (sin distinguir mayúsculas) y luego id. **Estado derivado** (por precedencia): `PAUSED` (inactivo) > `PENDING` (sin
  checks) > `DOWN` (incidencia DOWN abierta O último check fallido) > `DEGRADED` (incidencia HIGH_LATENCY o SSL_EXPIRING
  abierta con el sitio arriba) > `UP`. Una incidencia resuelta no cuenta. El "último check" es el más reciente por
  `checked_at` (desempate por id), no el de mayor id.
- **Sin N+1, medido**: siempre 3 sentencias sea cual sea el número de sitios (sitios; último check por sitio con
  `CROSS JOIN LATERAL ... LIMIT 1` sobre `idx_checks_site_checked_at`; incidencias abiertas como filas ligeras). No se usan
  entidades con proxies perezosos (`CheckQueries`, `OpenIncidentRow`). El test cuenta TODAS las sentencias SQL que pasan por
  el `DataSource` (`SqlStatementCounter`, un proxy JDBC de test) y exige que 20 sitios cuesten lo mismo que 2.
- **`GET /api/sites/{id}/checks?from&to&page&size`**: paginado (`{items,page,size,totalElements,totalPages}`), orden FIJO
  `checked_at DESC, id DESC` (no hay parámetro `sort`). `size` por defecto 50 y máximo 100 (`app.api.max-page-size`):
  **un `size` mayor es un 400, no se recorta en silencio**; `page` >= 0. `from`/`to` opcionales, ISO-8601 CON zona horaria
  (`2026-03-01T12:00:00Z` o `...+01:00`; una fecha sin hora o sin zona es 400), ambos inclusivos. `from > to` → 400. El rango
  `to - from` no puede superar 90 días (`app.api.max-history-range`); **solo se comprueba cuando se dan ambos** (con uno solo
  es un rango abierto y la paginación acota el coste). Los parámetros se validan ANTES de buscar el sitio (400 antes que 404).
- **`GET /api/sites/{id}/stats?period=24h|7d|30d`**: `{siteId, period, windowStart, windowEnd, totalChecks, successfulChecks,
  uptimePercent, avgLatencyMs, p95LatencyMs, firstCheckAt, lastCheckAt}`. **`period` ausente (o vacío) = `24h`**, sin distinguir
  mayúsculas; cualquier otro valor → 400. Calculado en SQL (`CheckQueries.STATS`) con el `now` del `Clock`: ventana CERRADA
  `[now - periodo, now]` (un check justo a 24 h está dentro; uno un segundo más viejo, fuera; los futuros no cuentan).
  `uptimePercent = correctos / TODOS los checks * 100` (los fallidos cuentan en contra); **sin checks → `null` en uptime y
  latencias, nunca 100 %**; todo fallido → `0.00`. La latencia solo cuenta checks CORRECTOS con `latency_ms`; `p95` con
  `percentile_cont(0.95)`; redondeo a 2 decimales. `firstCheckAt`/`lastCheckAt` = el rango real que cubren los datos.
- **`GET /api/incidents?status=open|resolved&siteId&page&size`** y **`GET /api/incidents/{id}`**: mismo límite de paginación,
  orden `opened_at DESC, id DESC`. `status` sin distinguir mayúsculas (ausente = ambos). Un `siteId` inexistente es un **404**
  (no una lista vacía). Cada incidencia: `id, siteId, siteName, type, status (OPEN|RESOLVED), openedAt, resolvedAt, ongoing,
  durationSeconds (null mientras está en curso), details`. Se carga con su sitio en la misma sentencia (`@EntityGraph`).
- **Índices (V2)**: los de `checks` (`idx_checks_site_checked_at`) y los de `incidents` por sitio ya existían. Faltaba uno:
  `idx_incidents_opened_at (opened_at DESC, id DESC)` para el listado global (sin él: `Seq Scan` + `Sort` en cada página).
  **No** se creó un índice parcial para `status=open`: el `EXPLAIN` sobre 40.000 incidencias mostró que PostgreSQL ya usa el
  índice parcial único de la V1 (`WHERE resolved_at IS NULL`) y solo lee las pocas incidencias abiertas. `ReadIndexesIntegrationTest`
  lo demuestra (también quitando el índice dentro de una transacción que se revierte).
- **Tests**: `TestClock` (primario en `TestcontainersConfiguration`) sigue la hora real hasta que un test la fija con `fix(...)`
  (siempre en el pasado: los tokens se validan contra el mismo reloj). Mutaciones verificadas: contar los fallidos como correctos
  en el uptime y hacer exclusivo el inicio de la ventana hacen fallar 5 y 3 tests.

## Alertas por Telegram (paso 7)
- **`Notifier`** (interfaz, `send(html)` NUNCA lanza; devuelve `SendResult` SENT / NOT_CONFIGURED / FAILED con un detalle seguro).
  `NotifierConfig` elige: `TelegramNotifier` solo si `TELEGRAM_BOT_TOKEN` Y `TELEGRAM_CHAT_ID` existen y tienen forma válida;
  si no, `LoggingNotifier`. Con solo una de las dos (o formato inválido) avisa al arrancar con un WARN claro que nombra las
  variables y NUNCA imprime valores. Con ninguna, un INFO.
- **El token NUNCA debe aparecer en logs, excepciones ni trazas.** Telegram exige el token en la ruta de la URL, así que:
  1) `RestClient` solo conoce `/sendMessage`; un interceptor (`BotTokenPathInterceptor`) antepone `/bot<token>` en el último
  momento, así los logs/observaciones/mensajes de excepción de Spring (`I/O error on POST request for "<uri>"`) no lo
  contienen; 2) `TelegramNotifier` jamás registra el mensaje de una excepción, solo clasificaciones propias; 3)
  `MessageSanitizer` enmascara `bot<id>:<secreto>` (también con `%3A`) en todo lo que pasa por él (`SendResult`, `details`,
  logs); 4) `TelegramProperties.toString()` oculta token y chat id; 5) la URL base debe ser https (http solo en loopback).
  Tests: `TelegramNotifierNetworkTest` provoca fallos reales (conexión rechazada, timeout, 401, 503) y revisa TODOS los
  loggers a nivel TRACE buscando el token en cualquiera de sus formas (literal, `%3A` y solo la parte secreta). Mutaciones
  verificadas: loguear el token a propósito y una implementación "ingenua" (token en la URI + log de `ex.getMessage()`)
  hacen fallar los tests. OJO: el servidor falso del JDK (`com.sun.net.httpserver`) registra la línea de petición con el
  token en la misma JVM; esos loggers se excluyen en el test porque son del servidor, no del cliente.
- **Envío asíncrono**: `IncidentAlertListener` (`@TransactionalEventListener(AFTER_COMMIT)`) solo da formato y encola en
  `AlertDispatcher`: un único virtual thread con cola acotada (`app.alerts.queue-capacity`, 100), así se conserva el orden
  (abierta antes que resuelta), nada bloquea al check ni al scheduler y, si Telegram está caído mucho tiempo, los avisos
  nuevos se descartan con un WARN en vez de acumularse. Todo fallo se captura. Al parar la app se vacía la cola
  (`app.alerts.shutdown-timeout`).
- **Timeouts y reintentos** (`app.telegram.*`): conexión 3 s, lectura 5 s, máx. 2 reintentos con 500 ms de pausa en errores
  de red y 5xx. 429: se respeta `retry_after` (cuerpo o cabecera `Retry-After`) solo si es <= `max-retry-after` (10 s); si no,
  el aviso se descarta y se registra. Cualquier otro 4xx (token/chat id inválidos) no se reintenta: un único WARN con la
  explicación de Telegram. Una redirección no es éxito y no se sigue. Sin reintentos infinitos.
- **Formato** (`AlertMessageFormatter`): HTML en español. El nombre del sitio y el detalle se ESCAPAN SIEMPRE (`& < >`);
  nunca se incluye la URL del sitio (las URL dentro del detalle se sustituyen por `[URL omitida]`); la resolución muestra la
  duración legible (`12 min`, `1 h 5 min`, `1 d 3 h`); máx. 4096 caracteres, recortando el texto SIN escapar para no partir
  una entidad. El detalle viene del motor de reglas y está en inglés técnico.
- **`POST /api/alerts/test`** (JWT, cubierto por el guardián): envía un mensaje de prueba de forma síncrona por el notifier.
  200 si se envió; 503 `ProblemDetail` sin datos sensibles si no está configurado o falla; 429 con `Retry-After` si se llama
  más de una vez cada 10 s (`app.alerts.test-interval`; todos los intentos cuentan; límite global en memoria, válido para una
  sola instancia).
- Tests de integración: `RecordingNotifier` (primario en `TestcontainersConfiguration`) sustituye a Telegram; el ciclo
  completo (incidencia abierta y cerrada) produce exactamente un aviso de apertura y uno de cierre, y un notifier que falla
  o lanza no afecta a checks ni incidencias.

## Reglas, incidencias y eventos (paso 6)
- **`RuleEngine`** (`incident/rules`) es Java puro: sin Spring, sin datos, sin reloj, sin estado (un test lo verifica por
  reflexión). Recibe un `RuleInput` (ahora, sitio activo, reglas, checks recientes NUEVO→VIEJO, tipos con incidencia abierta)
  y devuelve `Decision.Open/Close`. Como las incidencias abiertas forman parte de la entrada y todo se lee de la BD, un
  reinicio no cambia nada y evaluar dos veces lo mismo no duplica nada (idempotente).
- **DOWN**: abre tras `threshold` fallos consecutivos (con menos checks no se confirma); cierra con el primer check correcto.
  **HIGH_LATENCY**: abre cuando los últimos `app.rules.latency-samples` (3) checks CORRECTOS superan el umbral (> ms); un
  pico suelto no abre; los fallidos no cuentan como latencia; cierra cuando el último correcto es <= umbral.
  **SSL_EXPIRING**: usa el `cert_expires_at` del último check que lo tenga; abre si quedan menos de `threshold` días (o ya
  caducó); cierra al renovarse; sin información de certificado no hace nada. Un certificado caducado que hace fallar los
  checks genera ADEMÁS la incidencia DOWN (correcto).
- **Regla desactivada o ausente**: no abre y cierra la incidencia abierta de su tipo ("Rule disabled").
  **Sitio inactivo**: no se evalúa; cierra todas sus incidencias ("Site deactivated").
- **Orden de evaluación**: `CheckService` guarda el `Check` (su tx) y DESPUÉS llama a `IncidentService.evaluate` en otra tx
  corta, dentro de un `try/catch`: un fallo del motor nunca pierde el check ni rompe el check manual. Ventana de checks
  leídos = `max(50, umbral DOWN)`.
- **Aplicación**: `IncidentService` lee en una tx de solo lectura y aplica CADA decisión con `IncidentWriter` en su propia
  tx (`REQUIRES_NEW`): en PostgreSQL una violación aborta toda la transacción, así solo se revierte esa decisión. Si dos
  evaluaciones compiten por abrir, el índice único parcial deja pasar una; la otra recibe `DataIntegrityViolationException`,
  se captura y no hay evento. El cierre usa `SELECT ... FOR UPDATE`: dos cierres concurrentes producen un solo cierre y un
  solo evento (hay tests de ambas carreras y una mutación que lo demuestra).
- **Eventos**: `IncidentOpened` / `IncidentResolved` (llevan el NOMBRE del sitio, nunca su URL; el de resolución lleva la
  duración). Se publican dentro de la tx del writer; los consumidores deben usar
  `@TransactionalEventListener(AFTER_COMMIT)` (lo hace `IncidentLogListener`; el notificador de Telegram del paso 7 hará lo
  mismo). Los timestamps de `Incident` se truncan a microsegundos (precisión de PostgreSQL) para que evento y fila coincidan.
- **`details`**: <= 500 caracteres, sin query strings ni credenciales de URL (`MessageSanitizer` en `common/text`), y al
  cerrar se añade `| Closed: <motivo>` conservando siempre el motivo.
- **Desactivar un sitio** (`SiteService.update`, active true→false) cierra sus incidencias en la MISMA transacción
  (`IncidentWriter.closeAllOpen`, `REQUIRED`), no en el siguiente ciclo.
- **API de reglas**: `GET /api/sites/{id}/rules` y `PUT /api/sites/{id}/rules/{type}` (`threshold` obligatorio; rangos: DOWN
  1-100, HIGH_LATENCY 1-600000 ms, SSL_EXPIRING 1-365 días; `enabled` opcional). 404 si el sitio no existe, 400 si el
  tipo o el umbral no son válidos. Cambiar una regla NO reevalúa: se aplica en el siguiente check.
- Tests: tabla de casos del motor (`RuleEngineTest`, con repeticiones paso a paso para el flapping), integración con
  PostgreSQL del ciclo completo (fallo, fallo, DOWN abierta, correcto, cerrada), carreras, idempotencia tras "reinicio",
  desactivación y API de reglas. `IncidentEventRecorder` (en `TestcontainersConfiguration`) registra los eventos como los
  vería un listener AFTER_COMMIT.

## Scheduler y comprobación manual (paso 5)
- **Trigger**: `CheckScheduler` con `@Scheduled(fixedDelay)` (NO `fixedRate`; un test inspecciona la tarea registrada).
  Solo existe si `app.scheduler.enabled=true` (por defecto sí; el perfil `test` lo desactiva y los tests llaman a
  `CheckRunner.runCycle()` directamente). Propiedades `app.scheduler.*`: `initial-delay` 5 s, `fixed-delay` 10 s,
  `max-concurrency` 10, `shutdown-timeout` 10 s.
- **Selección de pendientes** (`SiteRepository.findDueSiteIds(now)`, SQL nativo): sitios activos, nunca comprobados o con
  `last_checked_at + check_interval_seconds <= now`; primero los nunca comprobados y luego los más atrasados. El `now` viene
  del `Clock` inyectado, no del reloj de PostgreSQL. `Check.checked_at` y `last_checked_at` son el instante de INICIO del check.
- **`CheckService.runCheck(id)` no es `@Transactional`**: tx corta de lectura (URL) → check de red SIN transacción ni conexión
  → tx corta de escritura (guarda el `Check` y `last_checked_at`, incluso si el check falló). Dos tests lo prueban: orden de
  begin/commit/red, y que el pool Hikari tiene 0 conexiones activas durante la petición (comprobado con una mutación).
  El `cert_expires_at` también se guarda en checks fallidos por HTTP (`Check.down(..., certExpiresAt)`).
- **`InFlightGuard`** (conjunto concurrente de ids) compartido por el ciclo y la comprobación manual: nunca dos checks del mismo
  sitio a la vez. El ciclo se salta los sitios ocupados; el endpoint manual responde 409.
- **`CheckRunner`**: executor de virtual threads + `Semaphore(max-concurrency)`. El ciclo espera a que terminen todos sus
  checks (así el `fixedDelay` cuenta desde el final y un ciclo lento retrasa al siguiente). Los checks manuales no cuentan
  para el límite. Un fallo (excepción inesperada, sitio borrado durante el check, BD caída al listar) se captura, se registra
  con el nombre de la clase y el mensaje saneado (sin query strings, sin stacktrace) y no afecta a los demás.
- **Parada limpia** (`@PreDestroy`): deja de aceptar trabajo, espera `shutdown-timeout` a los checks en curso y luego los
  interrumpe. `spring.task.scheduling.shutdown.await-termination=true` deja terminar el ciclo en marcha.
- **`POST /api/sites/{id}/check`** (JWT): ejecuta el check ya (también en sitios inactivos), guarda y devuelve el resultado
  (`CheckResponse`); 404 si no existe, 409 si ya hay un check en curso. Un sitio caído es un 200 (el fallo es el resultado).

## Seguridad (paso 3)
- **Denegar por defecto**: `SecurityConfig` exige JWT en todo salvo `POST /api/auth/login`, `/api/public/**` y
  `GET /actuator/health`. JWT HS256 con `oauth2-resource-server` (Nimbus): el decoder solo acepta HS256, emisor
  `controlweb` y token no caducado (sin margen de reloj); `alg=none`, otras firmas o payload manipulado → 401.
  Sin sesiones ni cookies (stateless), por eso CSRF está desactivado.
- **Arranque seguro**: `SecuritySettingsValidator` (un `EnvironmentPostProcessor`) valida la configuración ANTES de crear
  ningún bean (ni DataSource ni Flyway): `JWT_SECRET` obligatorio, Base64 y ≥ 32 bytes; `ADMIN_PASSWORD` ≥ 12
  caracteres y ≤ 72 bytes (límite de BCrypt); `ADMIN_EMAIL` válido; `CORS_ALLOWED_ORIGINS` solo orígenes exactos
  (sin `*`). Si falla, `InvalidConfigurationFailureAnalyzer` imprime "APPLICATION FAILED TO START" con la
  descripción y la acción, sin eco del valor. **No hay valores por defecto de secretos en `application.yml`.**
  Los `toString()` de las propiedades y de `LoginRequest`/`LoginResponse` ocultan secretos.
- **Login sin enumeración**: mismo 401 y mismo mensaje ("Invalid email or password") para email desconocido y contraseña
  incorrecta; siempre se hace una comparación BCrypt (contra un hash ficticio generado al arrancar, mismo coste) para no
  filtrar por tiempo. El email se normaliza (trim + minúsculas). No se loguean contraseñas ni tokens (test).
- **Admin**: `AdminSeeder` crea al usuario desde `ADMIN_EMAIL`/`ADMIN_PASSWORD` al arrancar y, si esa contraseña cambia,
  actualiza el hash en el siguiente arranque. No existe endpoint de registro.
- **Actuator**: solo `health`, público y sin detalles; la página índice `/actuator` está desactivada.
- **Swagger/OpenAPI** (springdoc): desactivado por defecto; en el perfil `dev` está activo y es público
  (`/swagger-ui.html`, `/v3/api-docs`), mediante una cadena de seguridad extra solo de ese perfil.
- **CORS**: orígenes exactos de `CORS_ALLOWED_ORIGINS`, sin credenciales, solo bajo `/api/**`; vacío = sin acceso cross-origin.
- **Tests**: las credenciales de los tests se generan al azar en cada ejecución (`TestCredentials`, inyectadas por
  `TestSecretsEnvironmentPostProcessor`), así no hay ningún secreto en el repo ni se cuela tu `.env`.
  `ApiSecurityGuardTest` recorre todos los endpoints `/api/**` y falla si alguno responde algo distinto de 401 sin token
  (salvo la lista explícita de públicos). Añadir un endpoint público es una decisión consciente: tocar `SecurityConfig`
  y la lista `isPublic` de ese test.
- **Los tests son herméticos respecto a tu `.env`**: `TestSecretsEnvironmentPostProcessor` elimina la fuente cargada desde
  `.env` en TODOS los tests (también cuando se arranca sin secretos con `test.secrets=false`), así un `.env` con secretos o un
  token real de Telegram nunca llega a un test (`TestIsolationTest`). Las pruebas con Telegram usan siempre un token falso
  aleatorio y un servidor local o `MockRestServiceServer`.
- Los tests de slice `@WebMvcTest` usan `@AutoConfigureMockMvc(addFilters = false)`; la seguridad se prueba aparte.

## Pulido (paso 10)
- **Retención** (`retention/`): `CheckRetentionJob` (cron `app.retention.cron`, por defecto 03:30) llama a
  `CheckRetentionService.purgeExpired()`, que borra los checks con `checked_at < now - days` (el `Clock` decide `now`; justo
  en el límite se conserva) en lotes de `app.retention.batch-size` (5000), cada lote en su transacción. No toca incidencias.
  Un fallo se captura y solo se registra el nombre de la excepción. `app.retention.enabled=false` (por defecto en los tests)
  elimina el job. `RETENTION_DAYS` < 30 hace fallar el arranque: `RetentionProperties` lanza `InvalidConfigurationException`
  (base de `InvalidSecurityConfigurationException`) y `InvalidConfigurationFailureAnalyzer` imprime el bloque
  "APPLICATION FAILED TO START". No hay índice nuevo para esto (un barrido nocturno no lo justifica).
- **OpenAPI** (`config/OpenApiConfig`): esquema Bearer global, `@Tag` por controlador y `@Operation(summary, description)` por
  endpoint; el resto (401/400/404/409/429/503 como `application/problem+json` con el esquema `ProblemDetail`, y quitar el
  requisito de token en login y `/api/public/status`) se añade por regla en un `OpenApiCustomizer`, sin ensuciar los
  controladores. Solo se sirve con el perfil `dev` (`OpenApiDocumentationTest`, `SwaggerDevProfileIntegrationTest`).
- **CI**: `.github/workflows/ci.yml` (`./mvnw verify`, Java 21 Temurin, caché Maven, `contents: read`, sin secretos, acciones fijadas
  por SHA completo; comprueba `docker info` porque Testcontainers lo necesita) y `.github/dependabot.yml` (Maven y acciones,
  semanal). `CiWorkflowTest` vigila esas garantías. **`mvnw` debe conservar el bit ejecutable en git** (`100755`).
- **Alertas en español**: `alert/IncidentDetailTranslator` traduce solo en el mensaje de Telegram los motivos conocidos (HTTP,
  timeout, DNS —sin repetir el host—, SSL, redirecciones, latencia, certificado); la base de datos sigue en inglés. Un motivo
  desconocido se muestra tal cual, saneado y escapado. Si cambias un texto de `HttpSiteChecker` o `RuleEngine`, actualiza el
  traductor y su test.
- **README** completo en español (diagramas Mermaid, decisiones, variables, ejemplos, limitaciones, hoja de ruta). Las insignias de CI
  usan `josemerchan712/controlweb` (el remoto actual).
- **Decisión del usuario**: el repositorio NO se hace público y NO se añade licencia hasta que él lo decida.

## Fase 2: despliegue en producción
El plan está en `docs/DESPLIEGUE.md` (un paso = un commit; se para tras cada uno). **Ese documento contiene detalles de
infraestructura** (esquema de redes y subredes, rutas del servidor, nombres de dominio, estructura de firewall y de copias);
**hay que revisarlo, y quitar o generalizar lo que sobre, antes de hacer público el repositorio.** La IP pública del VPS no
aparece (se escribe `<IP_PUBLICA_VPS>`), pero el resto sí. Requisito anotado para C5: el perfil `prod` no debe arrancar con
`app.proxy.trusted-addresses` vacío.

## Copias de seguridad (fase 2, paso C7b-1)
`deploy/backup/` (`backup.sh`, `restore.sh`, `restore-test.sh`, `common.sh`, `install.sh`): `pg_dump | age` con clave **pública** en el servidor
(`/etc/controlweb/backup-recipient.txt`) y privada solo en el PC del dueño; sin copia en claro en el disco. Se instalan como **root:root** en
`/usr/local/lib/controlweb/` con `sudo deploy/backup/install.sh` (que comprueba permisos): jamás se ejecutan desde el clon, que jose puede editar.
Una copia solo vale si toda la tubería salió bien, `pg_restore --list` la acepta con todas las tablas y tiene cabecera de age y tamaño; si no, no
se acepta, se borra el parcial y **no se rota nada** (rotación: 14 días, siempre las 3 más recientes). El servidor no puede descifrar sus copias:
`restore-test.sh` prueba un volcado NUEVO en `controlweb_restore_test` (siempre se borra), y el fichero guardado se verifica en el PC (C7b-3).
`restore.sh` exige `--yes`, una identidad en RAM (`/dev/shm`), comprueba antes de tocar nada, hace copia previa, para la app y restaura en una
transacción. Pruebas: `BackupScriptsTest`, `InstallScriptTest`. Detalle y comandos en `docs/DESPLIEGUE.md`.

## Tests lentos (`slow-scripts`)
`BackupScriptsTest`, `DeployScriptTest`, `VerifyScriptsTest` y `DownloadBackupsScriptTest` y `FirewallScriptTest` llevan `@Tag("slow-scripts")` (unos 8 min de los 13 de un `verify` en Windows).
`-Pfast` (perfil de `pom.xml`, **sin activación por defecto**) los excluye vía `test.excluded.groups`. Regla: `-Pfast` solo para iterar;
**antes de cada commit se ejecuta `verify` completo**, y el CI lo ejecuta siempre sin perfiles ni filtros (`CiWorkflowTest` prohíbe `-Pfast`,
`-Dtest`, `-DskipTests` y `-Dtest.excluded.groups` en el workflow; `FastProfileGuardianTest` vigila el perfil y las etiquetas).

## Units de systemd y regla de sudoers (fase 2, paso C7b-2)
`deploy/backup/systemd/` (cuatro units: copia diaria 04:15 UTC, prueba de restauración los domingos 05:00 UTC, ambas `Persistent`, con
endurecimiento de systemd) y `deploy/backup/controlweb-backup.sudoers` (UNA línea: `jose ALL=(root) NOPASSWD: /usr/bin/systemctl start
controlweb-backup.service`, sin comodines ni etiquetas). `install.sh` las instala (units root:root 0644, regla 0440) validando antes con
`visudo -cf` (también sobre la copia instalada, con nombre temporal `.new` que sudo ignora), `visudo -c`, `systemd-analyze verify` y
`daemon-reload`; **no activa los timers**. Por qué no permite escalar (dueño/modo de unit y script, entorno limpio de systemd, comando exacto) y
qué debe mostrar `sudo -l`: `docs/DESPLIEGUE.md`. Pruebas: `SystemdUnitsGuardianTest`, `InstallScriptTest` (CI Linux). Si cambias la regla,
cambia también `deploy.sh` y el guardián. `restore.sh` destruye SIEMPRE la identidad age (trap EXIT con `shred`, también tras Ctrl-C/kill).

## Descarga de copias a tu PC (fase 2, paso C7b-3)
Servidor: `serve-backup.sh` (solo lectura; petición de UNA línea por stdin: `list` | `sha256 NOMBRE` | `get NOMBRE`, el nombre debe ser exactamente
`controlweb-AAAAMMDDTHHMMSSZ.dump.age`), regla `controlweb-reader.sudoers` (`Defaults:cwbackup !use_pty` + el comando exacto sin argumentos) e
`install-reader.sh --pubkey FILE [--from IP]` (cuenta `cwbackup` sin contraseña, `authorized_keys` root:root con
`restrict,command="sudo -n …/serve-backup.sh"`; rechaza claves privadas, con opciones o que no sean ed25519). PC: `download-backups.ps1` (PowerShell 5.1:
ssh se ejecuta con `cmd /c "ssh … < petición > copia.partial"`, nunca tuberías ni `>` de PowerShell con binarios; tamaño + SHA-256 + cabecera age antes de renombrar;
salida 1 = fallo, 3 = copia del servidor obsoleta; `-Status`, `-InstallTask`, `-VerifyRestore` que descifra con age y restaura en un PostgreSQL desechable de Docker).
Ganchos de test: `CONTROLWEB_SSH_EXE`, `CONTROLWEB_AGE_EXE`, `CONTROLWEB_DOCKER_EXE`. Guía completa (claves age y SSH, qué copias de la privada guardar,
tarea programada, comandos exactos): `docs/DESPLIEGUE.md`.

## Firewall de salida (fase 2, paso C7c-1)
`deploy/firewall/` (`controlweb-firewall.sh` -> `/usr/local/sbin/controlweb-firewall`, dos units, `install.sh`). Todo cuelga de `-i cw-egress` (dos saltos: `DOCKER-USER` -> `CW-EGRESS`,
`INPUT` -> `CW-INPUT`); solo se crean/borran cadenas `CW-*`; REJECT nunca DROP; los saltos se añaden los últimos y `stop` los quita los primeros. La IP pública va en
`/etc/controlweb/firewall.conf` (`PUBLIC_IP=`, `ALLOW_DNS=`), nunca en el repo. La unit va `Before=docker.service` y NO tiene dependencia fuerte con Docker en ningún sentido (si la tuviera,
un fallo o un `stop` pararía Docker y Rotten Odds); `controlweb-firewall-guard.service` para SOLO `controlweb-app` si las reglas no están. Pruebas: `FirewallScriptTest` (iptables falso con estado +
`IptablesModel`, que falla ante reglas que no entiende), `FirewallUnitsGuardianTest`, `InstallFirewallScriptTest`. Guía y comandos de comprobación: `docs/DESPLIEGUE.md`.

## Publicar y volver atrás (fase 2, paso C7a)
`deploy/release.ps1` (PC, PowerShell 5.1: construye desde un worktree limpio de un commit subido, guarda la imagen en un fichero —nunca por
una tubería—, la sube con su SHA-256 y lanza el despliegue) y `deploy/deploy.sh` (servidor, sin root: comprobaciones antes de cambiar nada,
copia previa salvo en el primer despliegue, `current`/`previous`, espera a sano 240 s, vuelta atrás **automática solo si no hay migraciones
nuevas**, `rollback [--force]`, `status`). Detalle y comandos en `docs/DESPLIEGUE.md`. `DeployScriptTest` y `ReleaseScriptTest` los prueban sin
servidor (unos 2 min en Windows). El `.deploy/` del clon (estado) no se versiona.

## Imagen, compose y CI (fase 2, paso C6)
`Dockerfile` (JDK para compilar, JRE para ejecutar; etiqueta exacta + digest; usuario 10001; HEALTHCHECK con `wget`; contexto en lista
blanca en `.dockerignore`), `compose.prod.yml` (sin valores secretos: todo `${X:?mensaje}`; app y base de datos endurecidas;
redes `controlweb_proxy` (externa), `controlweb_db` (interna) y `controlweb_egress`), `deploy/verify-image.sh`,
`deploy/verify-container.sh`, `deploy/measure-memory.sh` y el job `image` de CI (construye sin publicar y arranca el stack con el
perfil `prod`). `DockerfileGuardianTest` y `ComposeProdGuardianTest` vigilan los dos ficheros sin Docker. Los comandos `docker inspect`
que comprueban cada medida están en `docs/DESPLIEGUE.md`. `-Xmx256m` en 512 MiB está decidido con una medición real (pico de RSS 361 MiB en 10 min; ver `docs/DESPLIEGUE.md`, «Medición de memoria»), con una comprobación a la semana (umbral 440 MiB → subir el límite a 640 MiB). Al cambiar de versión
una imagen base hay que cambiar etiqueta **y** digest (Dependabot lo hace).

## Perfil `prod` (fase 2, paso C5)
`application-prod.yml` (sin secretos) + `ProductionSettingsValidator`, que con el perfil `prod` impide arrancar (error claro,
todas las causas a la vez, sin repetir valores) si: `TRUSTED_PROXY_ADDRESSES` está vacío; `allow-private-hosts=true`; las API docs
o Swagger no están desactivadas explícitamente; los límites del login son más laxos que los de producción
(`LoginRateLimitProperties.defaults()` es el techo, así el perfil `test` no se cuela); los orígenes CORS tienen `http://` o
`localhost`; `JWT_SECRET`/`ADMIN_PASSWORD`/`DB_PASSWORD` están vacíos o son valores de ejemplo (`isPlaceholder`; un test exige que
todo valor de `.env.example` lo sea); `HEARTBEAT_URL` no es https; o `management.endpoints.web.exposure.include` no es exactamente `health`. Actuator: solo `/actuator/health` (sin detalles ni sondas); lo
demás da 404 al administrador y 401 sin token. Logs silenciosos (sin SQL, sin depuración de Spring Security/Web/Tomcat).
- Si añades un ajuste que en producción deba ser distinto que en desarrollo, añádelo al validador **y** a su test.
- `ConfigFilesGuardianTest` falla si un YAML de `src/main/resources` contiene algo que parezca un secreto literal; los secretos
  son `${NOMBRE:}` con valor por defecto vacío. `RestoreLoggingLevels` (extensión de test) devuelve los niveles de log tras un test
  que arranca el perfil `prod`, para no debilitar tests posteriores que buscan secretos en los logs.
- Mutaciones verificadas: desactivar cada una de las 8 comprobaciones, el filtro por perfil, el registro del validador, y estropear
  `application-prod.yml` (exponer actuator, mostrar detalles, niveles DEBUG, sondas, Swagger, un secreto literal) hacen fallar tests.

## Heartbeat (fase 2, paso C3)
Opcional (`app.heartbeat.url` / `HEARTBEAT_URL`, vacío = apagado). Tras cada ciclo del scheduler que se ejecutó
(`CheckRunner.runCycle()` devuelve `true`), `CheckScheduler` llama a `Heartbeat.beat()`, que hace un GET a un servicio externo
(p. ej. healthchecks.io) en su propio hilo virtual: como máximo uno en vuelo y uno por `min-interval` (60 s), sin seguir
redirecciones, sin que un fallo escape. **La URL es un secreto**: solo por entorno, validada al arrancar (https, o http a localhost),
nunca en logs ni en mensajes de error; solo se registra el cambio de estado. Con la base de datos caída no hay ping: el silencio es
la alarma. Mutaciones verificadas: quitar el límite de frecuencia, registrar la URL, latir sin ciclo, seguir redirecciones y quitar
la guarda de un solo ping en vuelo hacen fallar tests.

## Rate limiting del login (fase 2, paso C1)
`POST /api/auth/login` está limitado con **token buckets propios** (`auth/ratelimit`, sin dependencias: ~100 líneas, todo el
tiempo sale del `Clock`; se descartó Bucket4j porque no aportaba nada que justificara una dependencia). Cuatro cubos
(`app.login-rate-limit.*`, valores en `application.yml`): global 60/min; cliente 10 intentos/min; cliente 5 fallos de ráfaga
y luego 20/h; **cuenta 20 fallos y luego 20/h** (email normalizado, exista o no: un 429 no revela qué emails son reales).
- Dos puertas: `LoginRateLimitFilter` (antes de Spring Security y de leer el cuerpo: cuentan hasta las peticiones rotas) y
  `AuthController` (cuenta, ANTES de BCrypt; los fallos se registran después). Una petición rechazada no gasta CPU. Un login
  correcto no gasta fichas de fallos. 429 `ProblemDetail` con `Retry-After`, mismo mensaje para cualquier cubo.
- IP real: `ClientIpResolver` solo cree en `X-Forwarded-For` si el peer TCP es EXACTAMENTE una IP de `app.proxy.trusted-addresses`
  (`TRUSTED_PROXY_ADDRESSES`; rangos, nombres y comodines impiden arrancar); entonces usa la entrada MÁS A LA DERECHA.
  IPv6 se agrupa por /64. Vacío (por defecto) = nunca se lee la cabecera. En producción: la IP fija de Caddy (docs/DESPLIEGUE.md).
- En los tests el perfil `test` pone límites enormes (la suite hace login constantemente); `LoginRateLimitIntegrationTest`
  fija los de producción. Mutaciones verificadas: quitar la comprobación de cuenta previa a BCrypt, creer siempre
  `X-Forwarded-For`, usar la entrada izquierda y gastar fichas en logins correctos hacen fallar tests.
- **Limitación conocida (documentada en docs/DESPLIEGUE.md):** un atacante con muchas IP puede mantener vacío el cubo de una
  cuenta y bloquear el login aunque la contraseña sea correcta. Salida: SSH y reiniciar la app (el estado está en memoria).
  También se puede "olvidar" un cubo creando `max-keys` (10.000) claves más recientes: lo frena el límite por IP.

## Fuera de la fase 1 (pendiente)
Frontend React + Vite, Dockerfile y despliegue en Hetzner, refresh tokens. Licencia y visibilidad pública del repositorio:
decisión del usuario, pendiente.

> Rate limiting del login (`POST /api/auth/login`): **implementado en el paso C1 de la fase 2**; antes de desplegar hay que
> configurar `TRUSTED_PROXY_ADDRESSES` con la IP fija de Caddy (ver docs/DESPLIEGUE.md).
