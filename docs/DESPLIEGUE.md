# Fase 2: producción en el VPS de Hetzner (plan)

> **Estado:** plan aprobado el 2026-10-08. **Nada de esto se ha ejecutado en el servidor.** Cada paso se hace por separado:
> tests/revisión primero, un commit por paso, y se para para que el usuario lo revise.
>
> Convenciones: **[CW]** solo este repo · **[SRV]** servidor, sin tocar Rotten Odds · **[RO]** repo de Rotten Odds (con
> especial cuidado) · **[CF]** Cloudflare · ⏸ = parar y probar antes de seguir.
>
> Este documento no lleva secretos. La IP pública del VPS se escribe `<IP_PUBLICA_VPS>`; las IP internas de Docker
> (10.77.0.0/16) solo tienen sentido dentro del servidor.

## Cambios acordados al aprobar el plan

1. Este documento se guarda en un primer commit, antes de escribir código.
2. **Orden S5/S6 (decisión):** se mantiene S5 (primer despliegue) antes que S6 (copias) y `deploy.sh` **omite la copia previa
   cuando no hay versión anterior**, es decir, mientras no exista la imagen `controlweb-app:current` ni el fichero
   `.deploy-previous` (primer despliegue: no hay nada que proteger, la base de datos está vacía). En cualquier despliegue
   posterior la copia previa es **obligatoria**: si `controlweb-backup.service` no existe o falla, el despliegue se aborta.
   Así S5 no depende de S6 y, después de S6, ningún despliegue puede saltarse la copia.
3. **Rate limiting:** se corrige una afirmación del borrador. Ver [Limitación conocida](#limitación-conocida-bloqueo-de-la-cuenta-por-muchas-ips).
4. **SSH (S2):** ya existe `/etc/ssh/sshd_config.d/00-hardening.conf` (`PermitRootLogin no`, `PasswordAuthentication no`,
   `KbdInteractiveAuthentication no`, `PubkeyAuthentication yes`). El drop-in nuevo **solo añade** `AllowUsers jose` y
   `MaxAuthTries 3`, y se verifica con `sshd -T`.
5. **R1 requiere** haber comprobado antes que existe la **primera copia de Hetzner** (ver S-R1).

## Resumen de decisiones

| Tema | Decisión | Motivo principal |
|---|---|---|
| Base de datos | PostgreSQL propio de ControlWeb | Compartir obligaría a entrar en `rottenodds_internal` (prohibido) y su `backup.sh` tiene la base fija |
| Redes | Red compartida nueva `controlweb_proxy` (`internal`, subred fija) + red de BD propia + red de salida propia | Caddy con IP fija (10.77.0.10) sin depender de `rottenodds_edge` |
| Caddy | `import` de fragmentos desde `/opt/caddy-sites` (fuera del repo) | Un único commit en Rotten Odds; luego ControlWeb no lo vuelve a tocar |
| Imagen | Se construye en el PC y se sube con `docker save -o` → `scp` → `docker load -i` | Sin token en el servidor y sin compilar en él |
| Memoria | Swap de 2 GiB (swappiness 10) y límites por contenedor | Red de seguridad, no capacidad |
| Copia fuera del servidor | Backups de Hetzner + copia diaria cifrada con `age` que descarga el PC | Las dos sin tarjeta nueva |
| Aviso externo | Heartbeat a healthchecks.io (+ Worker de Cloudflare opcional) | Gratis, sin tarjeta, detecta servidor y scheduler parados |

## 1. Rate limiting del login

**Librería:** Bucket4j core (sin starter) + Caffeine para acotar memoria. Bucket4j acepta un `TimeMeter` propio: se conecta
al `Clock` de la aplicación y los tests lo controlan con `TestClock`.

| Cubo | Qué cuenta | Para qué |
|---|---|---|
| IP · peticiones | todas las de login | Inundación, incluido JSON malformado |
| IP · fallos | credenciales incorrectas | Fuerza bruta desde una IP |
| Cuenta · fallos | email normalizado, **exista o no** | Fuerza bruta distribuida (muchas IP) |
| Global | todos los intentos | Que BCrypt (~100 ms) no sea un vector de DoS |

Comportamiento:
- Los límites se comprueban **antes** de BCrypt: una petición rechazada no cuesta CPU.
- Respuesta: 429 `ProblemDetail` con `Retry-After`, con el mismo mensaje sea cual sea el cubo agotado.
- El límite por cuenta también cuenta emails inexistentes (si no, un 429 delataría cuál es el email del administrador).
- Un login correcto no gasta fichas de fallos.
- Memoria acotada (10.000 claves por tipo, expiración tras 2 h sin uso).

### Limitación conocida: bloqueo de la cuenta por muchas IPs

El límite por cuenta protege contra la fuerza bruta distribuida, pero tiene un coste que hay que asumir:
**un atacante con muchas IP puede mantener vacío el cubo de la cuenta y bloquear el login del administrador, incluso con la
contraseña correcta**, porque el límite se comprueba antes de mirar la contraseña. Cuesta muy poco (unas pocas peticiones
por hora bastan) y subir el límite solo encarece el ataque marginalmente.

Lo que sí es cierto:
- No hay bloqueo permanente: en cuanto el atacante para, el cubo se recarga solo.
- Solo se bloquea el **login**. Las comprobaciones, las incidencias y los avisos de Telegram siguen funcionando, y el token
  ya emitido sigue siendo válido hasta que caduca.
- **Salida de emergencia:** entrar por SSH y reiniciar la aplicación (`docker compose -p controlweb restart controlweb-app`).
  Los cubos viven en memoria y se vacían. Si el ataque continúa, el cubo se vuelve a vaciar; en ese caso, bloquear el
  origen en el Cloud Firewall de Hetzner o esperar.
- Se acepta porque es un panel de un solo administrador y la disponibilidad del login es menos importante que impedir
  el acierto de la contraseña.

**Implementación de C1 (desviaciones y valores finales):**
- **Cubo por cuenta: 20 fichas y +1 cada 3 min** (decisión del usuario; el borrador decía 10 y +1 cada 6 min). No evita el bloqueo
  por muchas IP, solo da más holgura al administrador y sigue haciendo inalcanzable la fuerza bruta contra una contraseña aleatoria.
- **Token bucket propio en lugar de Bucket4j:** unas 100 líneas, todo el tiempo sale del `Clock` y no hay dependencia nueva que
  mantener. La API es la misma (`tryConsume`, `peek`), así que sustituirlo por Bucket4j sería local a `TokenBuckets`.
- Dos puertas: `LoginRateLimitFilter` (antes de Spring Security y de leer el cuerpo) y `AuthController` (cuenta, antes de BCrypt).
- Memoria acotada con un LRU de `max-keys` (10.000) claves por cubo. Efecto a conocer: quien cree 10.000 claves más recientes
  puede "olvidar" un cubo concreto; el límite por IP (y el global de 60 intentos/min) lo hace costoso.

**IP real detrás de Caddy:** `ClientIpResolver` propio con `app.proxy.trusted-addresses` (IP exactas, sin rangos ni nombres;
arranque fail-fast si no son literales; vacío por defecto = nunca se confía en `X-Forwarded-For`). En producción:
`10.77.0.10`, la IP fija de Caddy en `controlweb_proxy`. Solo si el peer TCP es esa IP se toma la entrada **más a la
derecha** de `X-Forwarded-For` (Caddy, sin proxies de confianza, la reescribe con la IP real).

**Cloudflare:** `status-api` en **DNS only** (como la API de Rotten Odds). Si algún día pasara a proxied, Caddy vería IP de
Cloudflare: habría que confiar en sus rangos y leer `CF-Connecting-IP`, y su filtro anti-bots se aplicaría a la propia API.

Tests (con `TestClock`): el 6.º fallo da 429 con `Retry-After` correcto; tras avanzar el tiempo se permite uno; un login
correcto no cuenta; límite por cuenta repartido entre IP distintas; email existente e inexistente responden igual; con 429 no
se invoca BCrypt; `X-Forwarded-For` desde un peer no confiable se ignora; configurar `trusted-addresses` con un rango o un
nombre impide arrancar; mutación quitando la comprobación previa a BCrypt.

## 2. Base de datos

PostgreSQL propio (`shared_buffers=32MB`, `max_connections=20`, pool Hikari de 5). Frente a una base nueva en el de Rotten
Odds: +40–80 MiB reales, pero aislamiento total (versión, configuración, reinicios), backup propio y cero riesgo para Rotten
Odds. Compartir exigiría unirse a `rottenodds_internal`, lo que queda descartado.

## 3. Redes

| Red | Creada por | Subred | Bridge | `internal` | Miembros |
|---|---|---|---|---|---|
| `controlweb_proxy` | `docker network create` (una vez, a mano) | 10.77.0.0/24 | `cw-proxy` | sí | Caddy .10, app .20 |
| `controlweb_db` | Compose de ControlWeb | 10.77.1.0/24 | `cw-db` | sí | app, PostgreSQL |
| `controlweb_egress` | Compose de ControlWeb | 10.77.2.0/24 | `cw-egress` | no | **solo la app** |

- 10.77.0.0/16 está fuera de los pools por defecto de Docker, así que ninguna red automática futura chocará.
- `controlweb_proxy` es `internal` para que la ruta por defecto de Caddy (y su salida ACME) no cambie al unirse. Se comprueba
  con `ip route` dentro del contenedor.
- La crea una orden manual, no un Compose, para que ningún `down` de ningún proyecto la borre.
- Servicios `controlweb-app` y `controlweb-db`: ningún `api`, `db` ni `caddy`. Caddy no está en `controlweb_db` y la app no
  está en ninguna red de Rotten Odds. Se demuestra con `nslookup` (ver S5/R1).

## 4. Caddy

Elegida la opción **(b)**: un `import` de fragmentos fuera del repo. Cambio mínimo en el Caddyfile de Rotten Odds:

```caddy
# Sitios de otros proyectos de este servidor (fragmentos fuera del repo). Si no hay ninguno, Caddy solo avisa.
import /etc/caddy/sites/*.caddy
```

En su Compose (servicio `caddy`): volumen `/opt/caddy-sites:/etc/caddy/sites:ro`, y la red externa `controlweb_proxy` con
`ipv4_address: 10.77.0.10`. Un glob sin coincidencias es un aviso, no un error (se comprueba con `caddy validate`).

Riesgo: un fragmento roto en disco + un reinicio = Caddy no arranca y **cae Rotten Odds**. Por eso el fragmento solo se instala
con `caddy-install.sh`, que valida con un contenedor desechable (misma imagen que el Caddy en marcha), mueve el fichero de
forma atómica y solo entonces hace `caddy reload` (sin corte).

Fragmento de ControlWeb (`deploy/caddy/controlweb.caddy`, instalado como root, 644):

```caddy
status-api.josemariamerchan.dev {
	encode zstd gzip
	request_body {
		max_size 64KB
	}
	header {
		Strict-Transport-Security "max-age=31536000"
		X-Content-Type-Options "nosniff"
		Referrer-Policy "no-referrer"
		Permissions-Policy "camera=(), microphone=(), geolocation=(), payment=()"
		Content-Security-Policy "default-src 'none'; frame-ancestors 'none'"
		-Server
	}
	@hidden {
		path /actuator* /v3/api-docs* /swagger-ui*
		not path /actuator/health
	}
	respond @hidden 404
	reverse_proxy controlweb-app:8080
}
```

**CORS: solo en la app** (una única fuente de verdad, ya probada). Caddy no añade ni quita `Access-Control-*`.

## 5. Dockerfile y perfil `prod`

- Construcción: `eclipse-temurin:21-jdk` + Maven con caché BuildKit + `-Djarmode=tools extract --layers`.
- Ejecución: `eclipse-temurin:21-jre-alpine`, usuario `10001`, healthcheck con el `wget` de busybox
  (`wget -q -O /dev/null http://127.0.0.1:8080/actuator/health`).
- Compose: `read_only: true`, `tmpfs: /tmp`, `cap_drop: [ALL]`, `no-new-privileges`, `pids_limit`, logs `json-file`
  (`max-size 10m`, `max-file 3`).
- JVM (se mide en local con `--memory=512m` antes de subir nada): `-XX:+UseSerialGC -Xms128m -Xmx256m
  -XX:MaxMetaspaceSize=160m -XX:ReservedCodeCacheSize=64m -Xss512k -XX:MaxDirectMemorySize=32m -XX:+ExitOnOutOfMemoryError`,
  en `JAVA_TOOL_OPTIONS`. RSS esperado ≈ 330–420 MiB → límite de contenedor **512 MiB**.
- `application-prod.yml`: sin Swagger, scheduler activo, retención 90 días con cron `0 45 4 * * *`,
  `trusted-addresses: 10.77.0.10`, Hikari 5, `spring.flyway.ignore-migration-patterns: "*:future"`, secretos solo por entorno.
  Con el perfil `prod`, `allow-private-hosts=true` impide arrancar.

## 6. Construcción y entrega de la imagen

Elegida **(c)**: construir en el PC desde un worktree limpio de un commit con CI en verde y subir con
`docker save -o` → `scp` → `ssh docker load -i`. Sin credenciales en el servidor. (a) Construir en el servidor necesita
1,5–1,9 GiB de pico sin swap y arriesga al OOM killer sobre Rotten Odds; (b) GHCR exigiría un PAT clásico `read:packages`
(lectura de todos tus paquetes) guardado en claro en el servidor.

En PowerShell 5.1 `docker save | ssh …` y `ssh … > fichero` **corrompen binarios** (tuberías de texto/UTF-16): los scripts
usan ficheros intermedios. El CI añade un job que construye la imagen sin publicarla. El repo se clona en el servidor solo para
los ficheros de `deploy/`, con **deploy key propia de solo lectura** y alias SSH propio.

## 7. Memoria (3.790 MiB)

| Contenedor | Uso real | Límite |
|---|---|---|
| Rotten Odds api | 277 | 1.024 |
| Rotten Odds db | 67 | 768 |
| Caddy | ~40–60 | sin límite (no se toca) |
| **ControlWeb app** | ~330–420 | **512** (`memswap_limit` = 512) |
| **ControlWeb db** | ~40–80 | **256** (`memswap_limit` = 256) |
| Sistema + Docker | ~400–450 | — |

Uso real esperado ≈ 1.250 MiB (≈ 2.500 MiB libres). Todo al límite ≈ 3.110 MiB: cabe. Swap de 2 GiB solo como red de
seguridad (`apt`, `docker load`, `pg_dump`) y para que el OOM killer no elija la API de Rotten Odds. Efecto a conocer: los
contenedores de Rotten Odds sin `memswap_limit` pueden usar swap antes de que los mate el OOM (más lentos en vez de muertos).

### Medición de memoria y de arranque (C6, hecha el 2026-10-09)

Condiciones: portátil con Docker Desktop (WSL2, VM de 3,7 GiB), la imagen construida del commit de C6, `compose.prod.yml` con el límite
real de **512 MiB** para la aplicación y 256 MiB para PostgreSQL, **10 minutos** de medición, **29 sitios** cada 30 s (24 de
`example.com|org|net`, los 3 propios y 2 inexistentes), tráfico sobre la API de lectura y algún login erróneo. JVM:
`-XX:+UseSerialGC -Xms128m -Xmx256m -XX:MaxMetaspaceSize=160m -XX:ReservedCodeCacheSize=64m -Xss512k -XX:MaxDirectMemorySize=32m`.

| Dato | Resultado |
|---|---|
| Memoria de la aplicación (`docker stats`) | 322,3 MiB (a los 134 s) → 337,7 MiB (a los 717 s); **seguía creciendo al final**, más despacio (tabla abajo) |
| Pico de RSS del proceso (`VmHWM`) | 370.140 kB ≈ **361,5 MiB** |
| Pico del cgroup (`memory.peak`) | 360.501.248 bytes ≈ **343,8 MiB** |
| PostgreSQL | 43 MiB (límite 256 MiB) |
| `OOMKilled` / reinicios / avisos en el log | no / 0 / 0 |
| Checks guardados / incidencias | 602 / 2 (las dos webs inexistentes) |
| Arranque de la aplicación | 24 s |
| Todo el stack sano (incluye crear la base de datos desde cero) | 56 s |

**Decisión: se mantiene `-Xmx256m` y el límite de contenedor de 512 MiB**, con la condición de la comprobación de S10. Margen
medido: 150 MiB (29 %) sobre el pico de RSS y 168 MiB sobre el pico del cgroup, y 157 MiB sobre la última muestra de RSS.

Curva medida (17 muestras cada ~36 s, de 134 s a 717 s desde `up`; el arranque no está en la tabla; `anon` es el contador del cgroup):

| t (s) | `docker stats` (MiB) | RSS (MiB) | anon (MiB) | | t (s) | `docker stats` (MiB) | RSS (MiB) | anon (MiB) |
|---|---|---|---|---|---|---|---|---|
| 134 | 322,3 | 340,8 | 319 | | 463 | 335,2 | 353,1 | 332 |
| 171 | 327,8 | 344,8 | 322 | | 499 | 335,4 | 353,7 | 332 |
| 207 | 329,7 | 347,7 | 325 | | 536 | 335,8 | 354,2 | 333 |
| 244 | 330,4 | 348,5 | 327 | | 572 | 336,1 | 354,3 | 333 |
| 280 | 332,3 | 350,5 | 329 | | 608 | 336,6 | 354,6 | 333 |
| 317 | 334,1 | 351,7 | 330 | | 644 | 336,6 | 354,8 | 333 |
| 353 | 334,0 | 352,1 | 329 | | 681 | 336,8 | 355,0 | 334 |
| 390 | 333,8 | 352,2 | 331 | | 717 | 337,7 | 355,3 | 334 |
| 426 | 334,5 | 352,5 | 331 | | | | | |

Qué dicen estos números (y nada más):
- **El RSS no se estabilizó en 10 minutos.** Subió en los 16 intervalos, sin ninguno a la baja: de 340,8 a 355,3 MiB (**+14,4 MiB**
  en 583 s). `anon` pasó de 319 a 334 MiB (+15).
- **Pero crece cada vez más despacio.** RSS por tramos: 134→280 s, +9,7 MiB (4,0 MiB/min); 280→499 s, +3,2 MiB (0,9 MiB/min);
  499→717 s, +1,6 MiB (0,4 MiB/min); en los últimos 181 s, +1,05 MiB (0,35 MiB/min). `anon` en los mismos tramos: +10, +3 y +2 MiB.
  Los hilos se mantuvieron entre 51 y 55; PostgreSQL pasó de 42,1 a 43,5 MiB.
- **El pico de RSS (`VmHWM`, 361,5 MiB) está 6,2 MiB por encima de la muestra más alta (355,3 MiB):** ocurrió antes de la primera
  muestra (el arranque) o entre dos muestras; la tabla no permite decir cuándo.
- **No permite saber en qué valor se detiene** (si es que se detiene), y extrapolar una recta de 0,35 MiB/min no tiene sentido porque la
  pendiente baja. Tampoco lo garantiza la configuración: los máximos de la JVM (heap 256 + metaspace 160 + caché de código 64 + memoria
  directa 32) suman 512 MiB antes de contar las pilas de los hilos, las estructuras del GC y las bibliotecas nativas, así que no
  demuestran por sí solos que quepa en el límite. Lo único que lo dirá es observarlo más tiempo: por eso S10.

Otros límites de esta medición:
- En producción solo se expone `/actuator/health` y la imagen no trae `jcmd`: no se observó cuánto heap había reservado ni usado.
- Docker Desktop sobre WSL2, no un VPS Linux nativo: el consumo del propio runtime cambia, el de la JVM no demasiado.
- La carga es mucho mayor que la real (29 sitios cada 30 s frente a 3 sitios cada 1-5 min).
- No se ejercitó el job de retención (04:45), ni Telegram real, ni el heartbeat, ni tráfico real del endpoint público.

Qué se hace con el resultado: como la memoria seguía creciendo, S10 no es una formalidad: se toman **tres lecturas** (día 1, día 3
y día 7) para ver la pendiente, y la regla es **si `memory.peak` supera 440 MiB (86 % del límite), o hay un solo `OOMKilled` o
reinicio, se sube el límite del contenedor a 640 MiB** (el reparto de la sección 7 admite +128 MiB: todos los límites suman unos
3.240 MiB de 3.790) **sin tocar `-Xmx`**.

Márgenes de arranque en el VPS: en el portátil la aplicación tarda 24 s. Se supone, con prudencia, hasta 3 veces más en el VPS
(comparte dos vCPU con la pila de Rotten Odds): ≈ 75 s, y ≈ 170 s la primera vez (con la creación de la base de datos). Por eso:
`HEALTHCHECK --start-period=120s` (los fallos dentro de ese periodo no cuentan), `--start-interval=5s` (durante el arranque se
comprueba cada 5 s y el contenedor pasa a sano a los pocos segundos de estar listo, no a los 30 s del siguiente ciclo) y la espera de
`deploy.sh` por defecto de **240 s** (la de Rotten Odds es de 180 s), configurable con `HEALTH_TIMEOUT`.

## 8. Despliegue y vuelta atrás

`deploy/release.ps1` (PC): árbol limpio, en `main` y subido → construye `controlweb-app:<sha>` → `docker save -o` → `scp` →
`ssh docker load -i` → `ssh deploy.sh <sha>`. El CI en verde de ese commit lo verifica el usuario en GitHub.

`deploy/deploy.sh` (servidor, como jose):
1. Se niega si hay cambios locales o si el firewall (`controlweb-firewall.service`) no está activo.
2. `git pull --ff-only` (solo ficheros de despliegue).
3. **Copia previa:** obligatoria si hay versión anterior; **se omite solo en el primer despliegue** (sin `.deploy-previous` ni
   imagen `:current`). Si hay versión anterior y la copia falla, aborta.
4. Guarda la versión actual (`.deploy-previous`, `:current` → `:previous`), etiqueta la nueva, `up -d` y espera al healthcheck.
5. Si falla: sin migraciones nuevas → rollback automático; con migraciones nuevas → se detiene y se indica qué copia restaurar
   (no restaura sola).
6. Subcomando `rollback` manual. Conserva las 3 últimas imágenes.

Flyway solo va hacia delante: migraciones solo aditivas (columnas nulables, tablas nuevas); con `*:future` la imagen anterior
arranca sobre un esquema más nuevo. Un cambio destructivo exige restaurar la copia previa.

Secretos: `/opt/controlweb/.env.prod`, `root:jose`, modo 640, distintos de los locales y generados **en el servidor**.
Telegram de producción: bot nuevo y el mismo chat (enviarle `/start` al bot nuevo antes de la primera prueba).

## 9. Copias de seguridad

Scripts de root en `/usr/local/lib/controlweb/` (instalados con `sudo install`, **no editables por jose**; no se copia el patrón
de Rotten Odds, cuyo servicio ejecuta como root un script en una ruta escribible por jose). Timers:
`controlweb-backup.timer` 04:15 UTC y `controlweb-restore-test.timer` domingos 05:00 UTC.

`pg_dump -Fc`, verificación con `pg_restore --list`, rotación de 14 días en `/var/backups/controlweb` (700) y
**restauración real** semanal en una base temporal (`--exit-on-error`, comprobación de tablas, versión de Flyway y filas),
que se borra después. Prueba manual trimestral desde la copia del PC en un Docker local.

| Opción fuera del servidor | Tarjeta | Conclusión |
|---|---|---|
| Backups de Hetzner (+20 % ≈ 1,33 €/mes) | No | **Sí.** Cubren también Rotten Odds. Consistentes como ante un apagón: complementan a `pg_dump` |
| Descarga al PC cifrada con `age` | No | **Sí.** El servidor cifra con la clave pública; la privada solo en el PC y en el gestor de contraseñas |
| Backblaze B2 | Las fuentes se contradicen | Descartado mientras no se confirme |
| Cloudflare R2 | Pide medio de pago | Descartado |

Descarga al PC: clave SSH dedicada con `restrict,command=…` (solo puede leer la última copia `.age`), tarea de Windows diaria
con "ejecutar lo antes posible si se perdió" (con el PC apagado se pone al día al encenderlo; más de 14 días apagado deja un
hueco que cubre Hetzner). En PowerShell 5.1 usar `cmd /c "ssh … > fichero"`.

## 10. Firewall de salida

Script de root `/usr/local/sbin/controlweb-firewall` + `controlweb-firewall.service` (`Before=docker.service`,
`WantedBy=multi-user.target`). Sin `iptables-persistent`. Todo cuelga de `-i cw-egress`, así que no afecta a Rotten Odds ni al
tráfico Caddy↔app (`cw-proxy`) ni app↔BD (`cw-db`).

```
DOCKER-USER: -i cw-egress -j CW-EGRESS
CW-EGRESS:
  1 ESTABLISHED,RELATED                                              -> RETURN
  2 tcp, ctstate DNAT, ctorigdst <IP_PUBLICA_VPS>, ctorigdstport 443 -> RETURN   (hairpin)
  3 tcp, ctstate DNAT, ctorigdst <IP_PUBLICA_VPS>, ctorigdstport 80  -> RETURN
  4 -d 0/8, 10/8, 100.64/10, 127/8, 169.254/16, 172.16/12, 192.0.0/24, 192.0.2/24, 192.168/16,
       198.18/15, 198.51.100/24, 203.0.113/24, 224/4, 240/4          -> CW-DENY
  5 resto                                                            -> RETURN
CW-DENY: LOG limitado -> REJECT (tcp-reset / icmp-admin-prohibited)
INPUT:   -i cw-egress -j CW-INPUT ;  CW-INPUT: ESTABLISHED,RELATED -> ACCEPT ; resto -> REJECT   (SSH incluido)
```

`DOCKER-USER` ve el destino tras el DNAT (el hairpin llega como 172.18.0.2:443): las reglas 2–3 permiten exactamente "lo que
iba a la IP pública en 80/443" usando el destino original de conntrack, sin abrir ningún rango privado. Basta con IPv4 porque
las redes Docker no tienen IPv6 (opcional: regla espejo en `ip6tables`).

Pruebas desde `controlweb-app` (S5):

| Prueba | Esperado |
|---|---|
| `wget -T5 https://example.com`, `nslookup example.com` | OK |
| `wget -T5 https://rottenodds-api.josemariamerchan.dev/health` | 200 (hairpin) |
| `nc -zw3 controlweb-db 5432` | OK |
| `wget -T3 http://169.254.169.254/` | rechazado al instante |
| `nc -zw3 172.28.0.10 443`, `nc -zw3 172.18.0.2 443` | rechazado |
| `nc -zw3 10.77.2.1 22`, `nc -zw3 <IP_PUBLICA_VPS> 22` | rechazado (INPUT) |
| `sudo iptables -L CW-EGRESS -vn` | suben los contadores de las reglas 2 y 4 |

Riesgo a verificar en S5: que Docker 29 no aísle el tráfico DNAT entre bridges distintos (la prueba de hairpin hecha fue desde
el mismo bridge). Plan B: vigilar esa URL solo con el monitor externo.

## 11. Pruebas SSRF de límites

`HostSafetyValidator` (resolver falso). **Permitir:** 172.67.164.248, 104.21.49.164, 172.15.255.255, 172.32.0.0, 100.63.255.255,
100.128.0.0. **Bloquear:** 172.16.0.0, 172.31.255.255, 100.64.0.0, 100.127.255.255.

## 12. Monitorización

Antes de programar nada, medir (S0) con `curl -4 -A 'ControlWeb/1.0'` el código que devuelven las tres URL. Si Cloudflare
responde 403/503 con `cf-mitigated: challenge`: regla WAF personalizada con `ip.src eq <IP_PUBLICA_VPS>` → Skip (custom rules,
Browser Integrity Check, Security Level); Bot Fight Mode no se puede saltar en el plan gratuito. **No** se aceptan 403 como
"correcto" (ocultaría una caída real). Mejora condicional (C4): que el error diga "desafío de Cloudflare".

Altas: portfolio y casino cada 300 s; `rottenodds-api…/health` cada 60 s (umbral DOWN 2).

Aviso externo: heartbeat de ControlWeb a healthchecks.io (Hobbyist, 20 checks, sin tarjeta según Capterra; confirmar al
registrarse; integración con Telegram) con la URL secreta solo en `.env.prod`, más un Worker de Cloudflare con cron
opcional. UptimeRobot Free: 50 monitores a 5 min, uso no comercial y Telegram probablemente solo de pago.

**Implementación de C3 (heartbeat):** `CheckScheduler` llama a `Heartbeat.beat()` **solo después de un ciclo que se ejecutó**
(`CheckRunner.runCycle()` devuelve `true` cuando pudo cargar los sitios pendientes, aunque no hubiera ninguno y falle algún
check; `false` si la base de datos falla o la app se está parando). Con la base de datos caída no hay ping y healthchecks.io
avisa por el silencio. El ping va en su propio hilo virtual, como máximo uno en vuelo y uno por `min-interval` (60 s, con el
`Clock`); una redirección es un fallo y no se sigue; solo se registra el cambio de estado (un WARN al empezar a fallar, un INFO al
recuperarse) y nunca la URL ni el mensaje de una excepción. `HEARTBEAT_URL` es un secreto: https (o http solo a localhost),
sin credenciales embebidas, y un valor inválido impide arrancar sin repetirlo. En healthchecks.io se configura el check con un
**periodo de 1 minuto y una gracia de 3 a 5 minutos** (el heartbeat sale como mucho una vez por minuto) y la integración con
Telegram del propio servicio. La gracia evita falsos avisos cuando un despliegue reinicia la app: arrancar la JVM, las migraciones
de Flyway y el primer ciclo tardan del orden de un minuto, y un `rollback` automático puede sumar otro.

**Implementación de C5 (perfil `prod`):** `ProductionSettingsValidator` (un `EnvironmentPostProcessor`, como el de seguridad)
se niega a arrancar con el perfil `prod` si ocurre cualquiera de estas cosas. Se informan **todas a la vez** y **por nombre,
nunca por valor** (varias son secretos):
1. `TRUSTED_PROXY_ADDRESSES` vacío (sin ella todos los clientes comparten la IP de Caddy y 5 fallos bloquearían a todo el mundo).
2. `app.checker.allow-private-hosts=true`.
3. API docs o Swagger activados **o no desactivados explícitamente** (springdoc está activo por defecto).
4. Límites del login más laxos que los de producción (más fichas o recarga más rápida): el perfil `test` no puede colarse.
   El techo es `LoginRateLimitProperties.defaults()`, y un test comprueba que coincide con los valores por defecto reales.
5. `CORS_ALLOWED_ORIGINS` o `PUBLIC_CORS_ALLOWED_ORIGINS` con `http://` o con `localhost`/`127.x`/`[::1]`/`0.0.0.0`.
6. `JWT_SECRET`, `ADMIN_PASSWORD` o `DB_PASSWORD` vacíos o iguales a un valor de ejemplo (lista de marcadores; un test comprueba
   que todo valor de `.env.example` está en ella). Un `JWT_SECRET` de un solo byte repetido (32 ceros) también se rechaza.
7. `HEARTBEAT_URL` definida pero que no sea https.
8. `management.endpoints.web.exposure.include` distinto de exactamente `health` (también si falta o es `*`).

`application-prod.yml` no lleva secretos: desactiva Swagger/api-docs, expone solo `/actuator/health` sin detalles ni sondas,
fija el pool a 5 conexiones, `*:future` en Flyway, errores sin mensaje ni traza, apagado ordenado, retención a las 04:45 y
niveles de log silenciosos (sin SQL ni parámetros, sin depuración de Spring Security, Spring Web ni Tomcat). Tests: un test por
condición (`ProductionSettingsValidatorTest`), arranque real de la aplicación con los ficheros reales (`ProductionProfileStartupTest`,
incluido `prod,test` y `prod,dev`), la aplicación completa con perfil `prod` y PostgreSQL (`ProdProfileIntegrationTest`: actuator,
Swagger, niveles de log y que ninguna URL con query string ni cabecera `Authorization` llega a los logs) y un guardián de los YAML
(`ConfigFilesGuardianTest`).

Matiz sobre «404»: con el perfil `prod`, `/actuator/env`, `/actuator/beans`, etc. dan **404 al administrador** (no existen) y **401 a
quien no lleva token**, igual que cualquier otra ruta desconocida, de modo que nada revela qué existe. Desde fuera, el fragmento de
Caddy devuelve 404 a esas rutas.

**Implementación de C6 (imagen, compose y CI):**

*Imágenes base fijadas por etiqueta exacta Y digest* (el digest del índice multi-arquitectura, así vale en amd64 y arm64):
`eclipse-temurin:21.0.12_8-jdk-alpine-3.23` para compilar, `eclipse-temurin:21.0.12_8-jre-alpine-3.23` para ejecutar y
`postgres:16.15-alpine3.23` para la base de datos. Dependabot (ecosistemas `docker` y `docker-compose`) propone las actualizaciones.
La construcción usa el Maven Wrapper del proyecto (`-Dmaven.test.skip=true`, `MAVEN_OPTS=-Xmx512m`) con la caché de Maven en un
*cache mount* de BuildKit (las dependencias no entran en ninguna capa). El contexto de construcción es una **lista blanca**
(`.dockerignore`: solo `pom.xml`, `mvnw`, `.mvn/` y `src/main/`), así que `.env`, `.git`, `docs/`, los tests y `deploy/` ni siquiera
llegan al daemon. La imagen final lleva las capas de Spring Boot (dependencias, cargador, snapshots, aplicación), el usuario
`10001`, `SPRING_PROFILES_ACTIVE=prod` y un `HEALTHCHECK` con el `wget` de busybox (la imagen no trae `curl`).

*Qué se espera que gaste construir (estimación, a confirmar con la primera construcción):*

| | Estimación |
|---|---|
| RAM del build | pico ≈ 0,8–1,1 GiB dentro de la VM de Docker (JVM de Maven con `-Xmx512m` + javac + descarga del wrapper) |
| Disco, primera vez | ≈ 1,3–1,6 GiB en el disco virtual de Docker (imágenes JDK+JRE ≈ 0,5 GiB, caché de Maven ≈ 0,3 GiB, imagen final ≈ 0,2 GiB, capas) |
| Disco, siguientes | unos MB por capa nueva; la caché de Maven se reutiliza |
| Tiempo | 3–6 min la primera vez, 1–2 min después |
| Medición de 10 min | aplicación ≤ 512 MiB + PostgreSQL ≤ 256 MiB (límites del compose) |

Un solo `docker build` a la vez; la VM de Docker Desktop de este portátil tiene 4 GiB.

*Qué comprueba cada capa (un fallo es un test o un script, no una lectura a ojo):*

| Qué | Cómo |
|---|---|
| Imágenes fijadas por digest, usuario no root, `HEALTHCHECK` sin curl, sin `COPY . .`, sin secretos en `ENV`, `.dockerignore` en lista blanca | `DockerfileGuardianTest` (estático, en `mvnw verify`) |
| compose: ningún valor secreto, variables obligatorias `${X:?mensaje}`, endurecimiento, redes, nunca una red de Rotten Odds | `ComposeProdGuardianTest` (estático) |
| La imagen construida no contiene código fuente, tests, `.env`, `.git`, `docs/`, `Dockerfile`, `pom.xml`; el jar solo trae clases compiladas; usuario no root; sin variables sospechosas | `deploy/verify-image.sh <imagen>` (también en CI) |
| Lo que Docker aplicó de verdad al contenedor en marcha | `deploy/verify-container.sh <contenedor>` (también en CI) |

Las mismas comprobaciones a mano, contra el contenedor en marcha (`$C` = nombre o id del contenedor de la aplicación):

```bash
docker inspect --format '{{.Config.User}}' $C                                  # 10001:10001
docker inspect --format '{{.HostConfig.ReadonlyRootfs}}' $C                    # true
docker inspect --format '{{.HostConfig.CapDrop}} {{.HostConfig.CapAdd}}' $C    # [ALL] [] (o <no value>)
docker inspect --format '{{.HostConfig.SecurityOpt}}' $C                       # [no-new-privileges:true]
docker inspect --format '{{.HostConfig.PidsLimit}}' $C                         # 256
docker inspect --format '{{.HostConfig.Memory}} {{.HostConfig.MemorySwap}}' $C # 536870912 536870912 (sin swap)
docker inspect --format '{{json .HostConfig.Tmpfs}}' $C                        # {"/tmp":"rw,noexec,nosuid,nodev,size=32m"}
docker inspect --format '{{.HostConfig.LogConfig.Type}} {{.HostConfig.LogConfig.Config}}' $C  # json-file map[max-file:3 max-size:10m]
docker inspect --format '{{.State.Health.Status}} {{.State.OOMKilled}} {{.RestartCount}}' $C   # healthy false 0
docker exec $C id -u                                                           # 10001
docker exec $C sh -c "grep -E 'CapEff|NoNewPrivs' /proc/1/status"              # CapEff 0000000000000000 / NoNewPrivs 1
docker exec $C touch /app/x                                                    # falla: sistema de ficheros de solo lectura
docker exec $C wget -q -O - http://127.0.0.1:8080/actuator/health              # {"status":"UP"}
docker exec $C wget -q -O /dev/null http://127.0.0.1:8080/actuator/env         # falla (404)
```

*Los scripts de comprobación se prueban sin Docker* (`VerifyScriptsTest`): se ejecutan contra un `docker` falso que contesta como el
real, y comprueban que el formato de la salida (un espacio al final o al principio, líneas en blanco, fin de línea CRLF, otro orden de
los nombres) nunca decide el resultado. Un fallo real de CI se debió a eso: la lista de redes empezaba por un espacio. Ojo con un matiz
de Windows: el Git Bash de Windows quita el `\r` dentro de `$(...)`, así que ahí el CRLF no se nota y por eso el test también rellena con
espacios, que sí sobreviven; en Linux (CI) se notan ambos. Cada comparación usa `trim`/`words`, y `verify-image.sh` elige el primer
Python que funcione de verdad (en Windows `python3` puede ser el atajo de la Microsoft Store).

*Dependabot:* ignora las versiones mayores de `postgres` (16 → 18 cambia el formato en disco y la ubicación del directorio de datos de la
imagen: exige una migración planificada con dump/restore) y de `eclipse-temurin` (el proyecto es Java 21 LTS; la 24 no es LTS).
Las versiones menores y los parches de ambos siguen llegando.

*CI (`job image`):* construye la imagen **sin publicarla** y sin secretos (las credenciales se generan al azar dentro del job y no se
imprimen), con `contents: read` y las acciones fijadas por SHA; ejecuta `verify-image.sh`, levanta el stack real
(`compose.prod.yml` con el perfil `prod` y un PostgreSQL de prueba) hasta que está sano, comprueba en el log que el perfil activo es
`prod` y ejecuta `verify-container.sh`.

*Nombre del servicio y DNS:* los servicios se llaman `controlweb-app` y `controlweb-db`, nunca `api`, `db` ni `caddy`; Caddy no está en
`controlweb_db` y la aplicación no está en ninguna red de Rotten Odds, de modo que no hay nombre que pueda resolverse a dos
destinos. El volumen de datos se llama `controlweb_prod_pgdata` para no chocar con el volumen del PostgreSQL de desarrollo.

*JVM:* las opciones (`JAVA_TOOL_OPTIONS`) están en el compose con el valor **decidido tras la medición** (`-Xmx256m` en 512 MiB, ver «Medición de memoria»),
sustituible desde `.env.prod` sin reconstruir.

**Implementación de C7a (publicar y volver atrás):**

*Desde tu PC* (Windows PowerShell 5.1, `deploy/release.ps1`; necesita Git, Docker Desktop y `ssh`/`scp` con clave, sin contraseña):

```powershell
# desplegar la última versión de main (antes: mira que el CI de ese commit está en verde en GitHub y haz push)
.\deploy\release.ps1 -SshTarget jose@<host> -CiChecked
# volver a la versión anterior / ver qué corre
.\deploy\release.ps1 -SshTarget jose@<host> -Rollback
.\deploy\release.ps1 -SshTarget jose@<host> -Status
# ver los pasos sin hacer nada (no construye, no sube, no toca el servidor)
.\deploy\release.ps1 -SshTarget jose@<host> -DryRun
```

`-SshTarget` puede ir en la variable de entorno `CONTROLWEB_SSH` (un alias de `~/.ssh/config` sirve). El script: comprueba que el árbol de trabajo
está limpio, que estás en `main` y que `HEAD` es igual a `origin/main`; exige `-CiChecked` (no puede ver tu repositorio privado);
construye la imagen desde un `git worktree` desechable de ese commit (nada local se cuela); la verifica con `verify-image.sh` (Git Bash);
la guarda en un **fichero** con `docker save -o` (nunca por una tubería: en PowerShell 5.1 una tubería entre programas convierte los
datos binarios en texto y los corrompe), calcula su SHA-256 y sube imagen y suma con `scp` a `~/controlweb-releases/`; y ejecuta
`~/controlweb/deploy/deploy.sh <commit> <fichero>`. Código de salida distinto de 0 si cualquier paso falla.

*En el servidor* (`deploy/deploy.sh`, como `jose`, sin root):

| Paso | Qué hace | Si falla |
|---|---|---|
| 1. Comprobaciones | argumentos, SHA-256 del tar, `.env.prod`, red `controlweb_proxy`, cortafuegos de salida activo, clon sin cambios locales, commit presente en GitHub, y `compose.prod.yml` **del commit nuevo** acepta las variables de `.env.prod` | se niega **antes de cambiar nada** |
| 2. Copia previa | `sudo systemctl start controlweb-backup.service` (bloquea hasta que termina y verifica la copia). **Se omite solo en el primer despliegue** (sin versión anterior ni base de datos) | se niega **antes de cambiar nada** |
| 3. Cambio | `docker load`; `current` → `previous`; `<commit>` → `current`; el clon pasa al commit; estado en `~/controlweb/.deploy/` | — |
| 4. Arranque | `docker compose up -d` y espera a sano (`HEALTH_TIMEOUT`, 240 s; la de Rotten Odds es de 180 s) | ver abajo |

Si la versión nueva **no** llega a estar sana: **sin migraciones nuevas** se vuelve sola a la anterior (imagen `previous`, código del commit
anterior) y el despliegue termina con error; **con migraciones nuevas** (hay diferencias en `src/main/resources/db/migration` entre las dos
versiones) **no** se vuelve sola: Flyway solo va hacia delante. Se **para la aplicación** (la base de datos sigue) y el mensaje indica
restaurar la copia previa (`restore.sh`, C7b) o, si las migraciones solo añaden cosas, probar `deploy.sh rollback --force`. Si es el
primer despliegue y falla, se para la aplicación (no hay a qué volver).

`deploy.sh rollback` (a mano) se niega a cruzar un cambio de migraciones salvo `--force`, y `deploy.sh status` dice qué corre y a qué se
volvería. Los ficheros subidos se borran tras un despliegue correcto y solo se conservan las imágenes de la versión actual y la anterior.

Detalles que importan: la ruta de la imagen puede ser relativa (`release.ps1` la pasa así por `ssh`); el script entero se lee **antes** de
ejecutarse (`main "$@"; exit $?`) porque `git checkout` sustituye el propio `deploy.sh`; la copia previa se pide aunque no haya estado
guardado si el contenedor de la base de datos ya existe.

*Orden de instalación:* la copia previa necesita el servicio `controlweb-backup.service` y una regla de `sudo` (C7b). Hasta que exista,
solo funciona el **primer** despliegue (S5), que no hace copia; S6 (copias) va **antes** del segundo despliegue.

*Pruebas:* `DeployScriptTest` ejecuta `deploy.sh` de verdad (bash y git reales) contra un `docker`, `systemctl` y `sudo` falsos, con un
repositorio real de tres commits (el tercero añade una migración) y uno bare como «GitHub»: primer y segundo despliegue, la copia antes de
cargar la imagen, vuelta atrás automática, **no** automática con migraciones, rechazo por cambios locales, cortafuegos inactivo, copia fallida,
variable que falta, tar corrupto, commit desconocido, vuelta atrás manual y `--force`. `ReleaseScriptTest` comprueba que `release.ps1` es
sintácticamente correcto, que un `-DryRun` lista todos los pasos y no crea nada, y reglas como «ningún dato binario por una tubería».
En Windows estas pruebas tardan unos 2 minutos (cada llamada a `docker` lanza un `bash`); en Linux, segundos.

**Implementación de C7b-1 (scripts de copia y restauración con `age`):**

*Diseño.* Las copias se cifran con **age** con una clave **asimétrica**: en el servidor solo está la clave **pública**
(`/etc/controlweb/backup-recipient.txt`); la **privada vive solo en tu PC**. El servidor puede escribir copias pero **no leerlas**: quien se
lleve el disco, o un servidor comprometido, no obtiene la base de datos. `pg_dump` se ejecuta dentro del contenedor de la base de datos
y su salida pasa por `age` al salir (`pg_dump | age`): **nunca se escribe una copia en claro en el disco del servidor**.

Los scripts (`deploy/backup/`) se instalan como ficheros de **root** en `/usr/local/lib/controlweb/`, nunca se ejecutan desde el clon de tu
carpeta personal (que jose puede editar: quien pueda editar un script que root ejecuta puede ser root):

| Script | Para qué |
|---|---|
| `backup.sh` | copia cifrada; la usan el timer de las 04:15 UTC y el despliegue (copia previa) |
| `restore-test.sh` | prueba semanal: vuelca la base de datos real y restaura ese volcado en `controlweb_restore_test` con `pg_restore --exit-on-error`; comprueba tablas, versión de Flyway y recuentos; **la base temporal se borra siempre** (éxito, fallo, Ctrl-C, kill); su nombre es fijo y el script se niega a tocar otra |
| `restore.sh` | restaura una copia sobre la base de datos real (destructivo, con `--yes`, copia de seguridad previa, aplicación parada, todo o nada) |
| `common.sh`, `install.sh` | funciones comunes; instalador que comprueba los permisos |

*Qué hace falta para que una copia «cuente»* (`backup.sh`): que **todas** las etapas de `pg_dump | tee | age` terminen bien; que `pg_restore --list`
(con la misma corriente de bytes, dentro del contenedor) acepte el archivo y su índice contenga `sites`, `checks`, `incidents`, `alert_rules`,
`users` y `flyway_schema_history`; que el fichero empiece por la cabecera de age y tenga un tamaño razonable. Solo entonces se renombra a su
nombre definitivo (atómico) y **solo después** se rota. **Una copia que no se puede leer o que no pasa `pg_restore --list` no se da por buena, se
borra su fichero parcial, se anota el fallo en `/var/lib/controlweb-backup/last-failure` y no se toca ninguna copia anterior** (ni siquiera las que
ya superan la retención). Rotación: se borran las de más de 14 días, pero las **3 más recientes se conservan siempre**. Un bloqueo con el PID
evita dos copias a la vez y se recupera solo si el proceso murió. Límites: `pg_restore --list` lee el índice, no cada bloque de datos; por eso
existen la prueba de restauración semanal y la verificación en tu PC.

*Límite importante de la clave asimétrica:* el servidor **no puede descifrar** sus propias copias, así que la prueba semanal del servidor
restaura un volcado **nuevo**, no el fichero guardado (de ese solo comprueba que existe, es reciente, tiene cabecera de age y tamaño razonable).
La prueba de que un fichero guardado se descifra y restaura de verdad se hace en **tu PC** (C7b-3: `download-backups.ps1 -VerifyRestore`) y a mano
tras cualquier incidencia.

*Claves de cifrado (en tu PC, PowerShell):*

```powershell
# 1. Instala age (sin ser administrador): winget install FiloSottile.age   (si no, descarga el zip de https://github.com/FiloSottile/age/releases)
# 2. Genera el par de claves, la privada en una carpeta tuya:
New-Item -ItemType Directory -Force "$env:USERPROFILE\.controlweb" | Out-Null
age-keygen -o "$env:USERPROFILE\.controlweb\backup-identity.txt"
# 3. La clave PÚBLICA (la única que sube al servidor):
age-keygen -y "$env:USERPROFILE\.controlweb\backup-identity.txt" | Out-File -Encoding ascii "$env:USERPROFILE\.controlweb\backup-recipient.txt"
# 4. Solo tú puedes leer la privada:
icacls "$env:USERPROFILE\.controlweb\backup-identity.txt" /inheritance:r /grant:r "$($env:USERNAME):(R)"
```

**Dónde guardar la clave privada** (`backup-identity.txt`, son tres líneas): (1) tu PC, en esa carpeta; (2) **una copia en tu gestor de contraseñas**
(pega el texto completo como nota segura); (3) opcionalmente una copia **sin conexión** (un USB guardado aparte). Nunca en el servidor, nunca en
el repositorio, nunca en el correo. **Si la pierdes (y no tienes ninguna copia) no se puede descifrar ninguna copia existente**: age no tiene
recuperación. Lo que queda es generar un par nuevo, cambiar la clave pública del servidor (las copias nuevas ya se podrían leer) y, para los
datos anteriores, recurrir a las copias de disco completo de Hetzner. Por eso la privada necesita dos copias independientes.

*Instalación en el servidor (S6)* — la clave pública se instala una vez, y el instalador comprueba los permisos:

```bash
sudo apt install age                                            # Ubuntu 24.04 trae age 1.1.x
scp backup-recipient.txt jose@HOST:                             # desde tu PC; es la clave PÚBLICA
sudo install -o root -g root -m 644 backup-recipient.txt /etc/controlweb/backup-recipient.txt
grep -c AGE-SECRET-KEY /etc/controlweb/backup-recipient.txt     # debe imprimir 0
cd ~/controlweb && git log -1 --stat && git diff HEAD~1 -- deploy/backup | less    # revisa lo que vas a instalar como root
sudo deploy/backup/install.sh                                   # copia con `install` (dueño root, modo fijo) y comprueba permisos
```

Cómo comprobar los permisos tú mismo (todo debe ser `root:root`, y nada escribible por grupo u otros salvo lo que se indica):

```bash
stat -c '%U:%G %a %n' /usr/local/lib/controlweb /usr/local/lib/controlweb/* /var/backups/controlweb /var/lib/controlweb-backup /etc/controlweb /etc/controlweb/backup-recipient.txt
# esperado: root:root 755 (carpeta y cuatro scripts) · 700 /var/backups/controlweb · 755 /var/lib/controlweb-backup y /etc/controlweb · 644 backup-recipient.txt
namei -l /usr/local/lib/controlweb/backup.sh                    # ninguna carpeta de la ruta debe pertenecer a jose ni ser escribible por otros
sudo -u jose test -w /usr/local/lib/controlweb/backup.sh && echo "MAL: jose puede editarlo" || echo "bien: jose no puede editarlo"
```

Primera copia a mano (antes de activar los timers) y comprobación:

```bash
sudo /usr/local/lib/controlweb/backup.sh
cat /var/lib/controlweb-backup/last                             # fecha, nombre y tamaño
sudo ls -l /var/backups/controlweb                              # un .dump.age, modo 600, dueño root
```

*Restaurar (incidencia).* La clave privada se copia al servidor **a la RAM** solo mientras dura la restauración y se destruye después:

```bash
# desde tu PC:  scp "$env:USERPROFILE\.controlweb\backup-identity.txt" jose@HOST:/dev/shm/controlweb-identity.txt
sudo /usr/local/lib/controlweb/restore.sh --identity /dev/shm/controlweb-identity.txt /var/backups/controlweb/controlweb-FECHA.dump.age        # sin --yes: solo explica
sudo /usr/local/lib/controlweb/restore.sh --identity /dev/shm/controlweb-identity.txt --yes /var/backups/controlweb/controlweb-FECHA.dump.age
shred -u /dev/shm/controlweb-identity.txt
```

`restore.sh` se niega a usar una identidad que esté en un disco (solo `tmpfs`/`ramfs`) salvo `--allow-disk-identity`. Antes de cambiar nada
comprueba que la identidad descifra la copia y que el archivo tiene todas las tablas; toma una copia de seguridad de la base de datos actual
(salvo `--no-safety-backup`); para la aplicación; restaura con `--clean --if-exists --exit-on-error --single-transaction` (si falla, la base de
datos queda como estaba); y **no arranca la aplicación**: despliega después la versión que corresponda a esos datos
(`deploy.sh rollback --force` o un despliegue nuevo).

*Pruebas:* `BackupScriptsTest` (31 casos) ejecuta los tres scripts reales contra un `docker` y un `age` falsos: la copia contiene el volcado
completo byte a byte (aunque `pg_restore --list` deje de leer pronto), permisos 0600/0700, rotación (14 días, mínimo 3), **ocho formas de
copia mala** (`pg_restore --list` la rechaza, falta una tabla, `pg_restore --list` termina con error tras un índice completo, `pg_dump` falla, `age`
falla, no escribe nada, escribe basura sin cabecera, escribe solo la cabecera) en las que no se acepta nada y **no se rota ninguna copia anterior**, claves incorrectas (falta, privada en el servidor, dos),
no ser root, base de datos parada, disco lleno, bloqueo vivo y bloqueo viejo, restos de una ejecución caída, el test de restauración con
limpieza garantizada en cuatro tipos de fallo, y `restore.sh` con y sin `--yes`, identidad errónea o en disco, fallo a mitad y `--target-db`.
`InstallScriptTest` (solo Linux; la lógica también se ejecutó a mano en WSL) comprueba modos, repetición segura, el script que se vuelve
escribible por el grupo, la clave privada en el fichero de la clave pública y las carpetas superiores.

## 13. Pasos

### Fase A: solo este repo

| Paso | Contenido | Comprobación | Deshacer |
|---|---|---|---|
| **C0 [CW]** | Este documento | Revisión | revert |
| **C1 [CW]** ✅ | Rate limiting + `ClientIpResolver` | Hecho: ver «Implementación de C1» | revert |
| **C2 [CW]** ✅ | Tests SSRF de límites (`HostSafetyBoundaryTest`) | Hecho: 47 casos, con mutaciones comprobadas | revert |
| **C2b [CW]** ✅ | Rangos de documentación y benchmarking en `HostSafetyValidator` | Hecho (ver requisitos de C5) | revert |
| **C3 [CW]** ✅ | Heartbeat opcional (`app.heartbeat.url`, vacío = apagado) | Hecho: ver «Implementación de C3» | revert |
| **C4 [CW]** *(omitido por ahora; ver S0)* | Mensaje "desafío de Cloudflare" | Tests | revert |
| **C5 [CW]** ✅ | `application-prod.yml`, protecciones del perfil `prod`, Flyway `*:future`, cron de retención | Hecho: ver «Implementación de C5» | revert |
| **C6 [CW]** ✅ | Dockerfile, `compose.prod.yml`, job de CI de la imagen | Hecho; medido en el portátil (ver «Medición de memoria») | revert |
| **C7a [CW]** ✅ | `release.ps1` y `deploy.sh` con vuelta atrás | Hecho: ver «Implementación de C7a» | revert |
| **C7b-1 [CW]** ✅ | Scripts de copia cifrada con `age`, restauración, prueba de restauración e instalador | Hecho: ver «Implementación de C7b-1» | revert |
| **C7b-2 [CW]** | Units de systemd (copia 04:15 UTC, prueba semanal), regla de `sudoers` validada con `visudo -c` | tests estáticos | revert |
| **C7b-3 [CW]** | Descarga a tu PC (`download-backups.ps1`), clave SSH restringida, tarea programada de Windows, verificación de restauración local | tests de `download-backups.ps1` | revert |
| **C7c [CW]** | Cortafuegos de salida (`controlweb-firewall`), `caddy-install.sh`, fragmento de Caddy y shellcheck en CI | `bash -n`, shellcheck, revisión | revert |

**Requisitos de C5 (añadidos al aprobar C1):**
- El perfil `prod` **debe negarse a arrancar si `app.proxy.trusted-addresses` está vacío**, con test. Sin esa IP todos los
  clientes comparten la IP de Caddy (el peer TCP) y 5 fallos de login bloquearían el acceso de todo el mundo.
- Además, `allow-private-hosts=true` sigue impidiendo arrancar con el perfil `prod`.
- **Resuelto en C2b:** el firewall de salida bloquea también rangos de documentación y benchmarking (192.0.0.0/24, 192.0.2.0/24,
  198.18.0.0/15, 198.51.100.0/24, 203.0.113.0/24) y ahora `HostSafetyValidator` los bloquea igual, con
  `HostSafetyDocumentationRangesTest` (extremos bloqueados, vecinas permitidas, también mapeados en IPv6 y con 4 mutaciones
  comprobadas). Las dos capas coinciden en estos rangos.

### Fase B: servidor

- **S0 [SRV, solo lectura] ✅ hecho:** desde el VPS, con `curl -4` y `User-Agent: ControlWeb/1.0`, `josemariamerchan.dev`,
  `rottenodds.josemariamerchan.dev` y `rottenodds-api.josemariamerchan.dev/health` responden **200 y sin `cf-mitigated`**, por lo que
  **C4 se omite por ahora**. **Se reconfirma en S8** con los primeros checks reales de ControlWeb: el cliente HTTP de Java tiene
  otra huella TLS que `curl`, y Cloudflare podría tratarlo distinto. Si en S8 aparecen 403 de Cloudflare, se retoma C4 (mensaje
  "desafío de Cloudflare") y la regla WAF personalizada con Skip (punto 12).
- **S1 [SRV] Swap 2 GiB:** `fallocate`, `mkswap`, `swapon`, entrada en `/etc/fstab`, `vm.swappiness=10`. Comprobar con
  `swapon --show; free -m; sudo findmnt --verify`. Deshacer: `swapoff`, borrar la línea de fstab y los ficheros.
- **S2 [SRV] SSH (opcional):** el endurecimiento base ya existe en `00-hardening.conf`. El drop-in nuevo
  (`/etc/ssh/sshd_config.d/10-controlweb.conf`) **solo añade** `AllowUsers jose` y `MaxAuthTries 3`. Aplicar con
  `sudo sshd -t && sudo systemctl reload ssh` **con la sesión actual abierta**, entrar por una segunda sesión y verificar con
  `sudo sshd -T | grep -Ei 'allowusers|maxauthtries|passwordauthentication|permitrootlogin|kbdinteractive|pubkeyauth'`.
  No se restringe el puerto 22 por IP. Deshacer: borrar el drop-in y recargar.
- **S3 [SRV] Deploy key, carpetas y secretos:** clave `~/.ssh/controlweb_deploy` (ed25519), bloque `Host github-controlweb` en
  `~/.ssh/config` (sin pisar el de Rotten Odds), clave pública en GitHub → Deploy keys **sin escritura**, clon en
  `~/controlweb`, `/opt/controlweb` (`root:jose`, 750), `/opt/caddy-sites` (755) y `.env.prod` (`root:jose`, 640) rellenado con
  valores generados en el servidor con `openssl rand -base64 32` (nunca en el chat).
- **S4 [SRV] Red compartida y firewall:** `docker network create --driver bridge --internal --subnet 10.77.0.0/24 --opt
  com.docker.network.bridge.name=cw-proxy controlweb_proxy`; instalar el firewall con `sudo install` y
  `sudo systemctl enable --now controlweb-firewall`. Comprobar `iptables -S DOCKER-USER/CW-EGRESS` y que Rotten Odds responde.
  Deshacer: `systemctl disable --now controlweb-firewall` y `docker network rm controlweb_proxy`.
- **S5 [SRV] Primer despliegue interno** con `release.ps1` (Caddy aún no enruta nada). La copia previa se omite (no hay
  versión anterior). ⏸ Health interno, tabla del punto 10, `docker stats`, logs y `nslookup`.
- **S6 [SRV] Copias:** scripts y timers, `age`, primera copia manual y `restore-test`, tarea en el PC y Backups de Hetzner
  activados. ⏸ Descifrar una copia en el PC y restaurarla en un Docker local. A partir de aquí `deploy.sh` exige la copia previa.

### Fase C: único cambio en Rotten Odds

- **R1 [RO]** — **Requisitos previos:** (1) existen `controlweb_proxy` y `/opt/caddy-sites` (vacía); (2) **se ha comprobado que
  existe la primera copia de Hetzner** del servidor (panel de Hetzner → Backups); (3) la copia local de Rotten Odds está hecha.
  Cambio en un PR: las 2 líneas del Caddyfile + volumen `/opt/caddy-sites` + red externa `controlweb_proxy` con
  `ipv4_address: 10.77.0.10` + nota en su DEPLOY.md. Validar antes de fusionar con `caddy validate` en un contenedor desechable y
  `docker compose config -q`. Provoca un corte de ~2–5 s al recrear caddy (en horas tranquilas). Comprobar el health de Rotten
  Odds, `ip route` de Caddy (ruta por defecto sin cambios) y `nslookup api` / `nslookup controlweb-app` desde Caddy.
  Deshacer: `deploy.sh rollback` **y** `git revert`. ⏸ 24 h observando Rotten Odds antes de seguir.

### Fase D: exposición

- **S7 [CF + SRV]:** registro A `status-api` → `<IP_PUBLICA_VPS>` en **DNS only**; `sudo caddy-install.sh`. Comprobar certificado
  y cabeceras. Deshacer: borrar el fragmento y `caddy reload`.
- **S8:** alta de los tres sitios y **reconfirmación de que Cloudflare no devuelve 403 a los checks de Java** (ver S0; si lo hace, C4 y regla WAF), heartbeat, Worker opcional y `POST /api/alerts/test`.
- **S9 ⏸ Batería final:** health `UP`; Swagger, `/v3/api-docs` y `/actuator/env` → 404; 6 logins erróneos → 5×401 y luego 429 con
  `Retry-After`; lo mismo cambiando `X-Forwarded-For` (sigue el 429); CORS de `/api/public/status` con el origen del portfolio
  (permitido) y con otro (no); `/api/sites` sin cabecera CORS desde el portfolio; `sudo reboot` (todo vuelve solo, firewall
  incluido); imagen rota a propósito (rollback automático); `deploy.sh rollback`; `restore-test`.
- **S10 tras una semana:** **la curva de memoria** (la medición de C6 solo duró 10 minutos y el RSS seguía creciendo al terminar:
  toma estas lecturas el día 1, el 3 y el 7 y apunta la fecha, para ver la pendiente, no solo el valor final):
  `docker exec controlweb-app cat /sys/fs/cgroup/memory.peak` (bytes; umbral 440 MiB = 461.373.440), `docker exec controlweb-app sh -c "awk '/^VmHWM/ {print \$2}' /proc/1/status"`
  (kB), `docker stats --no-stream`, `docker inspect --format '{{.State.OOMKilled}} {{.RestartCount}}' controlweb-app`; si se supera el umbral o
  hay un `OOMKilled`, subir el límite a 640 MiB (ver «Medición de memoria»). Además: `docker stats` y OOM/reinicios, swap usado (~0), tamaño de copias y de `checks`, siete copias
  verificadas (y las del PC), contadores `CW-EGRESS-DENY`, falsos positivos, heartbeat, caducidad del certificado, health de Rotten
  Odds, número de 429, log de retención a las 04:45 y PR de Dependabot.

## 14. Riesgos

1. Un fragmento de Caddy roto + reinicio tumba Rotten Odds: solo se instala con `caddy-install.sh`.
2. Rotten Odds pasa a depender de `controlweb_proxy`: documentado en su DEPLOY.md; la red solo se borra tras revertir R1.
3. Trampas binarias de PowerShell 5.1 (`docker save |`, `ssh >`).
4. Los logs de Docker no rotan por defecto (también en Rotten Odds).
5. El grupo `docker` equivale a root: los permisos de `.env.prod` protegen de errores, no de un jose comprometido.
6. El login puede bloquearse con muchas IP (ver punto 1); salida: SSH y reinicio.

## 15. Opcional y aparte (Rotten Odds, fuera de este plan)

Mover su `backup.sh` a una ruta propiedad de root (su servicio de systemd lo ejecuta como root desde una ruta escribible por
jose), rotación de logs en su Compose y valorar construir fuera del servidor.
