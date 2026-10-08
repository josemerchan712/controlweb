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
docker compose down                # parar la base de datos
```

## Estado
- Paso 0 (scaffold): hecho.
- Paso 1 (esquema Flyway V1 + entidades + tests de repositorio): hecho.
- Paso 2 (CRUD de sitios + protección SSRF): hecho.
- Paso 3 (seguridad JWT): hecho.
- Paso 4 (checker HTTP + SSL): hecho. Siguiente: paso 5 (scheduler que ejecuta el checker y guarda los `Check`).

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

## Seguridad (paso 3)
- **Denegar por defecto**: `SecurityConfig` exige JWT en todo salvo `POST /api/auth/login`, `/api/public/**` y
  `GET /actuator/health`. JWT HS256 con `oauth2-resource-server` (Nimbus): el decoder solo acepta HS256, emisor
  `controlweb` y token no caducado (sin margen de reloj); `alg=none`, otras firmas o payload manipulado → 401.
  Sin sesiones ni cookies (stateless), por eso CSRF está desactivado.
- **Arranque seguro**: `SecuritySettingsValidator` (un `EnvironmentPostProcessor`) valida la configuración ANTES de crear
  ningún bean (ni DataSource ni Flyway): `JWT_SECRET` obligatorio, Base64 y ≥ 32 bytes; `ADMIN_PASSWORD` ≥ 12
  caracteres y ≤ 72 bytes (límite de BCrypt); `ADMIN_EMAIL` válido; `CORS_ALLOWED_ORIGINS` solo orígenes exactos
  (sin `*`). Si falla, `InvalidSecurityConfigurationFailureAnalyzer` imprime "APPLICATION FAILED TO START" con la
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
- Los tests de slice `@WebMvcTest` usan `@AutoConfigureMockMvc(addFilters = false)`; la seguridad se prueba aparte.

## Fuera de la fase 1
Frontend React + Vite, Dockerfile y despliegue en Hetzner, refresh tokens.

> **OBLIGATORIO antes de desplegar:** el rate limiting del login (`POST /api/auth/login`).
