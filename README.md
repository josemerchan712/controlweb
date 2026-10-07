# Control Web

Centro de control de webs: vigila tus sitios desplegados (disponibilidad, latencia y certificado SSL),
guarda el histórico, abre y cierra incidencias, avisa por Telegram y ofrece un dashboard con página de estado pública.

> **Estado:** fase 1 (backend, solo en local) en desarrollo. Ahora mismo existe solo el esqueleto del proyecto.

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
