# Control Web

Centro de control de webs: vigila tus sitios desplegados (disponibilidad, latencia y certificado SSL),
guarda el histórico, abre y cierra incidencias, avisa por Telegram y ofrece un dashboard con página de estado pública.

> **Estado:** fase 1 (backend, solo en local) en desarrollo. Hay ya CRUD de sitios, autenticación JWT, comprobaciones periódicas, incidencias y alertas por Telegram.

## Stack
- Java 21, Spring Boot 4.1, Maven
- PostgreSQL 16 + Flyway
- Tests: JUnit 5, Mockito, Testcontainers
- Frontend (fase posterior): React + Vite

## Requisitos
- JDK 21 con `JAVA_HOME` configurado
- Docker Desktop

## Puesta en marcha

```powershell
Copy-Item .env.example .env     # edita DB_PASSWORD (y el resto cuando se necesiten)
docker compose up -d            # PostgreSQL 16 de desarrollo
.\mvnw.cmd spring-boot:run      # arrancar la aplicación
```

## Tests

```powershell
.\mvnw.cmd verify
```

Los tests de integración levantan su propio PostgreSQL con Testcontainers, así que Docker debe estar en marcha.

## Configuración
Todo se configura por variables de entorno; consulta `.env.example`. **Nunca subas tu `.env` al repositorio.**

La aplicación **no arranca** si falta o es débil alguno de estos valores (y te dice cuál):

| Variable | Requisito |
|---|---|
| `JWT_SECRET` | Base64 de al menos 32 bytes aleatorios |
| `ADMIN_EMAIL` | Un email válido |
| `ADMIN_PASSWORD` | Al menos 12 caracteres (máximo 72 bytes) |
| `CORS_ALLOWED_ORIGINS` | Orígenes exactos separados por comas; sin comodín `*`. Vacío = sin acceso cross-origin |

Para generar un `JWT_SECRET` en PowerShell:

```powershell
$b = New-Object byte[] 32; [Security.Cryptography.RandomNumberGenerator]::Create().GetBytes($b); [Convert]::ToBase64String($b)
```

## API (resumen)
Todo bajo `/api` requiere `Authorization: Bearer <token>` salvo el login y `/api/public/status`. Los errores son `application/problem+json`.

| Endpoint | Descripción |
|---|---|
| `POST /api/auth/login` | Devuelve el token JWT |
| `GET/POST /api/sites`, `GET/PUT/DELETE /api/sites/{id}` | Gestión de sitios |
| `POST /api/sites/{id}/check` | Comprobación manual inmediata |
| `GET/PUT /api/sites/{id}/rules[/{type}]` | Reglas de alerta del sitio |
| `GET /api/status` | Estado de todos los sitios (UP, DEGRADED, DOWN, PENDING, PAUSED) + resumen |
| `GET /api/sites/{id}/checks?from&to&page&size` | Histórico paginado (más reciente primero; máx. 100 por página; rango ≤ 90 días) |
| `GET /api/sites/{id}/stats?period=24h\|7d\|30d` | Uptime %, latencia media y p95 |
| `GET /api/incidents?status&siteId&page&size`, `GET /api/incidents/{id}` | Incidencias |
| `POST /api/alerts/test` | Mensaje de prueba por Telegram |
| `GET /api/public/status` | **Sin token.** Estado público para tu portfolio: solo nombre, estado y uptime de 30 días de los sitios marcados como públicos |

```powershell
$h = @{ Authorization = "Bearer $($login.token)" }
Invoke-RestMethod -Uri http://localhost:8080/api/status -Headers $h
Invoke-RestMethod -Uri "http://localhost:8080/api/sites/1/stats?period=7d" -Headers $h
```

## Página de estado pública
`GET /api/public/status` no necesita token y es lo que puede consumir tu portfolio. Solo muestra los sitios con
`publicVisible: true` que estén activos, y de cada uno únicamente su **nombre, su estado (UP, DEGRADED, DOWN, UNKNOWN) y su
uptime de 30 días**; nunca URLs, ids, latencias ni detalles. El estado global se calcula solo con esos sitios, así que un
sitio privado caído no cambia nada. La respuesta se guarda 15 segundos en memoria para no cargar la base de datos.

Para llamarla desde el navegador, pon el origen de tu portfolio (exacto, sin comodín) en `PUBLIC_CORS_ALLOWED_ORIGINS`.

```powershell
Invoke-RestMethod http://localhost:8080/api/public/status
```

## Alertas por Telegram
Opcional. Necesitas **las dos** variables `TELEGRAM_BOT_TOKEN` y `TELEGRAM_CHAT_ID` (mira los pasos en `.env.example`).
Si falta alguna, la app arranca igualmente, te avisa en el log de cuál falta (sin mostrar valores) y las alertas solo se
escriben en el log. El token es un secreto: solo por variable de entorno y nunca se registra.

Para comprobar que funciona (hace falta el token JWT de la sección siguiente; máximo una llamada cada 10 segundos):

```powershell
Invoke-RestMethod -Method Post -Uri http://localhost:8080/api/alerts/test -Headers @{ Authorization = "Bearer $($login.token)" }
```

Cada incidencia produce un aviso al abrirse y otro al resolverse (con su duración). Los avisos incluyen el nombre del sitio,
nunca su URL.

## Autenticación
Al arrancar se crea el administrador a partir de `ADMIN_EMAIL` y `ADMIN_PASSWORD` (no hay registro público).

```powershell
$login = Invoke-RestMethod -Method Post -Uri http://localhost:8080/api/auth/login -ContentType application/json `
  -Body (@{ email = "admin@example.com"; password = "<tu ADMIN_PASSWORD>" } | ConvertTo-Json)
Invoke-RestMethod -Uri http://localhost:8080/api/sites -Headers @{ Authorization = "Bearer $($login.token)" }
```

Con el perfil `dev` (`.\mvnw.cmd spring-boot:run "-Dspring-boot.run.profiles=dev"`) la documentación OpenAPI está
en `http://localhost:8080/swagger-ui.html`. En cualquier otro perfil no es pública.
