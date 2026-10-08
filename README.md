# Control Web

Centro de control de webs: vigila tus sitios desplegados (disponibilidad, latencia y certificado SSL),
guarda el histórico, abre y cierra incidencias, avisa por Telegram y ofrece un dashboard con página de estado pública.

> **Estado:** fase 1 (backend, solo en local) en desarrollo. Hay ya CRUD de sitios y autenticación JWT.

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

## Autenticación
Al arrancar se crea el administrador a partir de `ADMIN_EMAIL` y `ADMIN_PASSWORD` (no hay registro público).

```powershell
$login = Invoke-RestMethod -Method Post -Uri http://localhost:8080/api/auth/login -ContentType application/json `
  -Body (@{ email = "admin@example.com"; password = "<tu ADMIN_PASSWORD>" } | ConvertTo-Json)
Invoke-RestMethod -Uri http://localhost:8080/api/sites -Headers @{ Authorization = "Bearer $($login.token)" }
```

Con el perfil `dev` (`.\mvnw.cmd spring-boot:run "-Dspring-boot.run.profiles=dev"`) la documentación OpenAPI está
en `http://localhost:8080/swagger-ui.html`. En cualquier otro perfil no es pública.
