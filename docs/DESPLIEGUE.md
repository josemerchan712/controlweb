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

### Implementación de C7c-1: firewall de salida (`controlweb-firewall`)

| Fichero del repo | Se instala en | Dueño y modo |
|---|---|---|
| `deploy/firewall/controlweb-firewall.sh` | `/usr/local/sbin/controlweb-firewall` | root:root 0755 |
| `deploy/firewall/controlweb-firewall.service` | `/etc/systemd/system/` | root:root 0644 |
| `deploy/firewall/controlweb-firewall-guard.service` | `/etc/systemd/system/` | root:root 0644 |
| `deploy/firewall/install.sh` | lo ejecutas tú con `sudo` | copia con `install`, comprueba dueño/modo y carpetas, `systemd-analyze verify`, `daemon-reload`; **no arranca, no activa, no crea la configuración** |
| `/etc/controlweb/firewall.conf` | **la escribes tú** | `PUBLIC_IP=<IP pública del VPS>` (nunca en el repositorio) y, si hace falta, `ALLOW_DNS=<resolutores en dirección privada>`; root:root, no escribible por otros |

**Todo cuelga del bridge `cw-egress`.** Solo hay dos puntos de entrada, cada uno una única regla de salto con `-i cw-egress`:
`DOCKER-USER: -i cw-egress -j CW-EGRESS` e `INPUT: -i cw-egress -j CW-INPUT`. Un paquete que no entra por `cw-egress` (los bridges de Rotten Odds,
`cw-proxy`, `cw-db`, la tarjeta de red, `lo`) no cumple esa regla y por tanto **nunca visita ninguna regla nuestra**. El script solo crea y borra las tres cadenas
`CW-EGRESS`, `CW-DENY` y `CW-INPUT`: no cambia políticas, no vacía ninguna cadena de Docker ni de otro, no toca la tabla `nat`, no usa `iptables-save/restore`.
Las reglas son las del plan (ver arriba), con `REJECT` (`tcp-reset` o `icmp-admin-prohibited`) y nunca `DROP`. El orden de trabajo es deliberado: **primero se
rellenan las cadenas y los dos saltos se añaden los últimos** (si algo falla a medias, el `trap` quita lo nuestro y no queda un firewall a medio construir) y
`stop` quita **primero los saltos** y luego las cadenas.

*Instalar y probar sin riesgo para Rotten Odds (en el servidor, tú):*

```bash
cd ~/controlweb && git log -1 --stat && less deploy/firewall/controlweb-firewall.sh        # revisa lo que vas a instalar como root
sudo deploy/firewall/install.sh
echo 'PUBLIC_IP=<IP pública del VPS>' | sudo tee /etc/controlweb/firewall.conf && sudo chmod 644 /etc/controlweb/firewall.conf
resolvectl status | grep -i 'DNS Servers'     # si algún resolutor está en 10.x, 172.16-31.x o 192.168.x: añade «ALLOW_DNS=ip ip» a firewall.conf (si no, la app no resolvería nombres)
docker info | grep -i firewall                # si dijera «nftables» en vez de iptables, DOCKER-USER no se consulta y este firewall no vale: PARA y dímelo

# 1) foto del cortafuegos actual (solo lectura), arrancar, y comparar: SOLO deben aparecer líneas nuevas CW-* y las dos de salto
sudo iptables-save | sed 's/\[[0-9]*:[0-9]*\]//' | grep -v '^#' > /tmp/fw-antes.txt
sudo systemctl start controlweb-firewall.service
sudo iptables-save | sed 's/\[[0-9]*:[0-9]*\]//' | grep -v '^#' > /tmp/fw-con.txt
diff /tmp/fw-antes.txt /tmp/fw-con.txt                        # solo «>» con CW-DENY, CW-EGRESS, CW-INPUT, sus reglas y 2 saltos «-i cw-egress»
# 2) idempotente: aplicar otra vez no cambia nada
sudo systemctl restart controlweb-firewall.service
sudo iptables-save | sed 's/\[[0-9]*:[0-9]*\]//' | grep -v '^#' | diff /tmp/fw-con.txt -          # sin diferencias
# 3) reversible: parar lo deja como estaba
sudo systemctl stop controlweb-firewall.service
sudo iptables-save | sed 's/\[[0-9]*:[0-9]*\]//' | grep -v '^#' | diff /tmp/fw-antes.txt -         # sin diferencias
# 4) ahora sí, arrancar y dejarlo activado en cada arranque (el firewall primero, luego el guardián)
sudo systemctl enable --now controlweb-firewall.service && sudo systemctl enable controlweb-firewall-guard.service
sudo iptables -L CW-EGRESS -vn --line-numbers ; sudo iptables -L DOCKER-USER -vn --line-numbers | head -4
```

Mientras la red `controlweb_egress` no exista, el interfaz `cw-egress` no existe y las dos reglas de salto no coinciden con **nada** (ni siquiera con la propia
aplicación): arrancar el firewall antes de desplegar es inocuo para Rotten Odds. Los comandos de la tabla de pruebas del plan (desde `controlweb-app`, paso S5:
`wget` a `example.com`, hairpin a la API de Rotten Odds, `nc` a la BD, `169.254.169.254`, `172.28.0.10`/`172.18.0.2`, `10.77.2.1:22` y la IP pública `:22`, y
`sudo iptables -L CW-EGRESS -vn` con contadores que suben en las reglas 2 y 4) siguen siendo la comprobación en vivo; los tests de abajo prueban la lógica, no
sustituyen a S5. Quitarlo todo: `sudo systemctl disable --now controlweb-firewall.service controlweb-firewall-guard.service`.

#### `Before=` frente a `Requires=` (y por qué hay un guardián)

| Opción | Si el firewall falla o lo paras | Consecuencia |
|---|---|---|
| **`Before=docker.service`** (elegida) | Docker arranca igual (solo es un orden) | Rotten Odds no se entera. Coste: tras un reinicio con el firewall roto, el contenedor de ControlWeb (`restart: unless-stopped`) arrancaría sin reglas durante unos segundos → lo cubre el guardián. |
| `Requires=` / `BindsTo=` del firewall sobre Docker, o de `docker.service` sobre el firewall (con `Before=`) | **Docker no arranca**; y `systemctl stop controlweb-firewall` **para Docker** | **Rotten Odds cae.** Descartado: `FirewallUnitsGuardianTest` prohíbe cualquier dependencia fuerte entre ambos en los dos sentidos. |
| `Wants=` | Igual que `Before=` pero además arrastra el firewall al arrancar Docker | No aporta nada si ya está en `multi-user.target`. |

**El guardián** (`controlweb-firewall-guard.service`, `After=docker.service controlweb-firewall.service`, no es dependencia de nada) ejecuta `controlweb-firewall guard`:
si las reglas no están (comprobación real con `iptables -C`, no solo el estado de systemd), para **únicamente** el contenedor `controlweb-app` y solo si
pertenece al proyecto de Compose `controlweb` (comprueba la etiqueta); no toca Docker, ni Rotten Odds, ni `compose`, ni borra nada; nunca hace fallar el arranque.
El firewall lo invoca también en `ExecStopPost` (con `-`): si lo paras con la aplicación en marcha, la aplicación se para. Para volver a arrancarla, arregla el
firewall y despliega (o `docker start controlweb-app`). Riesgo residual honesto: tras un reinicio con el firewall roto, hay una ventana de segundos entre que Docker
arranca la aplicación y el guardián la para. Si no la quieres, la alternativa es poner `restart: "no"` y arrancarla desde una unit con `Requires=controlweb-firewall.service`
(más piezas y cambia C6): dímelo y lo preparo.

**`deploy.sh` y la aplicación expuesta sin reglas:** `deploy.sh` ejecuta `check_firewall` (`systemctl is-active controlweb-firewall.service`) al principio de `deploy` y de
`rollback`, **antes de cargar la imagen ni tocar nada**, y se niega con «the application must not run without it» (`DeployScriptTest` «egress firewall not active: refused» y
`FirewallUnitsGuardianTest` comprueba que la llamada está antes de `docker load` y en `rollback`). Con eso: no se despliega sin firewall (guardia de `deploy.sh`) y
no se queda en marcha tras un reinicio sin firewall (guardián). Si alguien arranca la aplicación a mano con `docker compose up` sin firewall, ningún script lo impide
hasta el siguiente arranque del guardián: no hay forma de prohibírselo a root.

**Límites que conoces:** (1) al *reaplicar* (`restart`), las cadenas se vacían y se rellenan: durante milisegundos no filtran (solo en reaplicaciones, no en arranques);
(2) un DNS privado se debe listar en `ALLOW_DNS` o la aplicación no resolverá; (3) sigue pendiente la prueba S5 de que Docker 29 no aísla el DNAT entre bridges (hairpin); (4) solo IPv4.

*Pruebas de C7c-1.* `FirewallScriptTest` (19 casos, `@Tag("slow-scripts")`, también en CI) ejecuta el script real contra un `iptables` falso **con estado**
(`fake-iptables.sh`: respeta que `-N` falla si existe, `-X` falla si la cadena no está vacía o sigue referenciada, `-D` falla si no existe, rechaza cualquier `-t`) y un evaluador de
reglas (`IptablesModel`, que **falla ante cualquier regla que no entienda**, así que no se puede añadir un tipo de regla sin que las pruebas lo aprendan):
(1) más de 10.000 paquetes de 11 interfaces que no son `cw-egress` (Rotten Odds, `cw-proxy`, `cw-db`, `eth0`, `lo`, `cw-egress2`...) × 14 destinos × 6 puertos × 3 protocolos × 4 estados
**no visitan ninguna cadena CW-** y reciben el mismo veredicto que antes de existir el firewall; (2) las únicas reglas añadidas fuera de nuestras cadenas son los dos saltos, **en primer lugar**, y
ninguna llamada usa otra tabla, políticas, `-Z`, `-R` ni `-E`; (3) tabla de decisiones de `cw-egress`: Internet pública pasa, 26 destinos privados/reservados se rechazan (tcp-reset o icmp-admin-prohibited) y los bordes
de cada rango pasan, hairpin (80/443 con destino original = IP pública y ctstate DNAT) pasa y todo lo demás se rechaza, resolutor privado solo si está en `ALLOW_DNS`, INPUT rechaza SSH y todo
desde `cw-egress` pero no cambia el SSH desde fuera; (4) aplicar 2 y 3 veces = una vez, y repara un salto borrado o una cadena manipulada; (5) `stop` deja todo **byte a byte** como estaba antes (y doble `stop` o `stop` sin arrancar no fallan);
(6) **un fallo en cualquiera de las llamadas a iptables** (las últimas 10 una a una y una de cada 3 del resto) termina con error y sin ningún salto ni cadena CW- (y un error que no sea «no existe» no se confunde con «ausente»: no duplica saltos);
(7) validación de la configuración (sin fichero, IP privada/metadatos/documentación/loopback/multicast, texto, código de shell, claves desconocidas, DNS inválido: no llama a iptables); (8) `status` y el guardián (solo `docker stop controlweb-app`, solo si la etiqueta es `controlweb`, nunca falla).
`FirewallUnitsGuardianTest` (6) y `InstallFirewallScriptTest` (3, Linux, también en CI) vigilan las units y el instalador.

*Mutaciones verificadas de C7c-1* (24, todas hacen fallar tests): salto sin `-i cw-egress` (FORWARD e INPUT); `DROP` en lugar de `REJECT`; sin limpieza tras un fallo; salto insertado siempre (no idempotente);
sin hairpin en 443; sin bloquear 169.254/16; `stop` que borra cadenas antes que saltos; `stop` que deja `CW-DENY`; hairpin para cualquier destino original; vaciar `DOCKER-USER`; aceptar una IP pública privada; guardián sin comprobar el proyecto;
INPUT sin aceptar lo establecido; errores de iptables leídos como «ausente»; salto de INPUT al final en vez de al principio; la regla 5 que rechaza todo; 172.16/12 recortado; y, en las units, `Requires=docker.service`, quitar `Before=docker.service`,
guardián sin `After=docker.service`, sin guardián al parar, `PartOf=docker.service` y `deploy.sh` sin `check_firewall`.

### Implementación de C7c-2: `caddy-install.sh` y el fragmento de Caddy

`deploy/caddy/controlweb.caddy` es el fragmento de arriba (sin cambios) y `deploy/caddy/caddy-install.sh` es lo único que lo instala. Se ejecuta con `sudo` desde
el clon revisado, y solo después de que Rotten Odds tenga hecho su cambio mínimo (R1: el `import` en su Caddyfile y el volumen `/opt/caddy-sites:/etc/caddy/sites:ro`).

```bash
sudo deploy/caddy/caddy-install.sh --check      # solo valida (pasos 1 y 2); no cambia nada, no recarga
sudo deploy/caddy/caddy-install.sh              # valida, mueve, recarga
curl -sI https://status-api.josemariamerchan.dev/actuator/health | head -3             # desde fuera
```

*Qué hace, en este orden* (cada fallo termina con `exit 1` y la carpeta `/opt/caddy-sites` **idéntica a como estaba**):

1. **Mira el fragmento** (solo sus líneas, sin Docker): tamaño 1 B–8 KiB, sin bytes NUL; el **único** bloque de primer nivel es `status-api.josemariamerchan.dev {` (nada de otro dominio, de
   opciones globales ni de un segundo sitio: así este fragmento no puede secuestrar el dominio de Rotten Odds); el **único** `reverse_proxy` es `controlweb-app:8080`; prohíbe `import`, `exec`,
   `file_server`, `root`, `tls`, `log`, `admin`, `storage`, `handle_path`, `rewrite`..., y los marcadores que leen el entorno o ficheros (`{$X}`, `{env.X}`, `{file.X}`).
2. **Localiza el Caddy que corre**: exactamente **un** contenedor en marcha del proyecto de Compose `rottenodds`, servicio `caddy` (por etiquetas; `CADDY_CONTAINER=nombre` lo fija a mano). Comprueba
   que tiene montados el Caddyfile (lo lee en el host) y `/opt/caddy-sites` en `/etc/caddy/sites`, y que el Caddyfile tiene la línea `import /etc/caddy/sites/*.caddy`: si no, se niega, porque el fragmento
   no se leería nunca (R1 sin hacer).
3. **Valida con un contenedor desechable de la MISMA imagen** (el **id** `sha256:…` de la imagen del contenedor que corre, no una etiqueta que pudo cambiar): `--rm`, `--network none`, `--read-only`
   (con `tmpfs` para `/tmp`, `/data` y `/config`), `--cap-drop ALL`, `no-new-privileges`, 256 MiB, el **mismo entorno** que el Caddy real (por si el Caddyfile usa variables; el fichero temporal
   con el entorno es privado y se borra al terminar), el Caddyfile real en solo lectura, y una **copia** de la carpeta de sitios que ya contiene el fragmento nuevo junto con todos los de los demás
   proyectos: se valida la configuración completa, no el fragmento suelto. La carpeta real no se monta nunca.
4. **Mueve con un renombrado atómico**: el fichero se escribe como `.controlweb.caddy.new` (empieza por punto y no acaba en `.caddy`: el `import` nunca puede leer un fichero a medias) y se renombra a
   `controlweb.caddy` con `mv` en la misma carpeta. El fragmento anterior, si lo había, se guarda antes como `.controlweb.caddy.previous` (oculto, tampoco lo lee el `import`).
5. **Recarga sin corte** (`docker exec <caddy> caddy reload`, que es graceful). Si falla: devuelve el fragmento anterior con otro renombrado (o borra el nuevo si no había anterior), vuelve a
   recargar por si el primer intento sí se aplicó pese al error (un *timeout*), y sale con error.

Nunca toca los fragmentos de otros proyectos y toma un cerrojo (`mkdir`) para que no corran dos a la vez. Deshacer a mano: `sudo mv -f /opt/caddy-sites/.controlweb.caddy.previous /opt/caddy-sites/controlweb.caddy`
o `sudo rm /opt/caddy-sites/controlweb.caddy`, y en ambos casos `docker exec <caddy> caddy reload --config /etc/caddy/Caddyfile --adapter caddyfile`.

*Límites honestos:* `caddy validate` comprueba la sintaxis y el aprovisionamiento de módulos, **no** que `controlweb-app` resuelva ni responda (hasta la fase D no existe en la red de Caddy) ni que el certificado se pueda emitir
(DNS y puertos 80/443 son de la fase D); `caddy reload` aplica lo que dicen los ficheros, así que cualquier cambio hecho a Caddy solo por la API de administración se perdería (igual que con un reload normal de Rotten Odds).

*Pruebas:* `CaddyInstallScriptTest` (15 casos, también en CI) con un `docker` falso: instalación nueva y actualización (el fragmento ya estaba en su sitio cuando Caddy recargó, el anterior se conserva,
ningún fichero temporal que el `import` pudiera leer, el entorno de Caddy no aparece en logs), `--check`, validación que falla, validador que no puede ejecutarse, **recarga que falla** (con y sin fragmento previo: byte a byte como antes y una segunda recarga),
recarga que falla solo la primera vez, 9 situaciones de entorno incorrectas (ningún Caddy, dos, imagen sin id, sin Caddyfile, sin montaje de sitios, montaje de otra carpeta, sin línea `import`) rechazadas **antes** de ejecutar nada, 24 fragmentos
prohibidos rechazados **antes** de preguntarle nada a Docker, cerrojo ajeno, argumentos, el fragmento del repositorio (validado y con sus cabeceras/404 de rutas ocultas) y la estructura atómica del código. También se ejecutó a mano en WSL Ubuntu (herramientas GNU).
*Mutaciones verificadas* (20, todas hacen fallar tests): saltarse la validación; validar con otra imagen o con red; no deshacer tras una recarga fallida (con y sin anterior); no exigir el `import`; no exigir el dominio; no comprobar el montaje; validar la carpeta
real en vez de la copia; aceptar el primero de varios Caddy; recargar en `--check`; copiar en vez de renombrar; no guardar el anterior; no exigir el upstream; desactivar la lista de directivas o de marcadores; ignorar el cerrojo; no borrar la carpeta de trabajo; y, en el fragmento, quitar el 404 de las rutas ocultas o apuntar a otro upstream.

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

*Restaurar (incidencia).* La clave privada viaja a la **RAM** del servidor (`/dev/shm`), se usa en **una** ejecución de `restore.sh` y el propio
script la **destruye siempre al terminar** (con `shred`, desde un `trap`): éxito, cualquier fallo, una opción mal escrita, solo «explicar»,
Ctrl-C, `kill` o una desconexión. Para la siguiente restauración se vuelve a copiar. Cómo pasarla **sin dejarla en el historial de la terminal
ni en un disco**:

1. En el servidor, una orden corta **sin la clave**: crear el fichero vacío y privado *antes* de copiar nada dentro. Así nace con modo 600 y
   no hay ni un instante en que otro usuario pueda leerla:

   ```powershell
   ssh jose@HOST 'umask 077; : > /dev/shm/controlweb-identity.txt'
   ```

2. Desde tu PC, copiar el fichero (en `scp` el contenido va directo de fichero a fichero):

   ```powershell
   scp "$env:USERPROFILE\.controlweb\backup-identity.txt" jose@HOST:/dev/shm/controlweb-identity.txt
   ```

   La clave **no pasa por la línea de órdenes ni por el historial** (PowerShell y bash solo guardan la *ruta* del fichero), no se imprime
   y `scp` no cambia los finales de línea (una tubería de PowerShell 5.1 los cambiaría a CRLF). **No pegues nunca la clave en una terminal**
   ni la pases con `echo`, `cat` o `ssh 'echo ...'`: el historial de la shell la guardaría. Como el fichero ya existe con modo 600, `scp` lo
   rellena sin cambiarle el modo.

3. Ver qué haría, **sin clave** (explica y sale con estado 2, no toca nada):

   ```bash
   sudo /usr/local/lib/controlweb/restore.sh /var/backups/controlweb/controlweb-FECHA.dump.age
   ```

4. Restaurar. Antes de cambiar nada comprueba la identidad y que el archivo es completo; toma una copia de seguridad de la base de datos actual
   (salvo `--no-safety-backup`); para la aplicación; restaura con `--clean --if-exists --exit-on-error --single-transaction` (si falla, la base
   de datos queda como estaba); y **no arranca la aplicación**: despliega después la versión que corresponda a esos datos
   (`deploy.sh rollback --force` o un despliegue nuevo):

   ```bash
   sudo /usr/local/lib/controlweb/restore.sh --identity /dev/shm/controlweb-identity.txt --yes /var/backups/controlweb/controlweb-FECHA.dump.age
   ls -l /dev/shm/controlweb-identity.txt      # debe decir «No such file or directory»: el script la destruyó
   ```

5. Si no estás seguro de que se borró (se cortó la conexión y no viste el mensaje «the identity file ... was destroyed»):
   `shred -u /dev/shm/controlweb-identity.txt`. Un reinicio del servidor vacía `/dev/shm` de todos modos.

Reglas de `restore.sh` sobre la identidad: solo **borra** el fichero si está en RAM (`tmpfs`/`ramfs`) o si lo nombras con `--delete-identity` (nunca borra
por sorpresa la única copia de una clave que esté en un disco); **se niega** a usar una identidad que esté en un disco (salvo `--allow-disk-identity`),
que no sea una identidad de age, o cuyo modo no sea 600 (legible por otros). Riesgo residual, dicho con franqueza: `/dev/shm` es memoria, pero el
núcleo puede mover páginas a la *swap* (el servidor tendrá 2 GiB, S1) y `shred` no borra una copia que ya estuviera allí. Es poco probable
(minutos de vida) y la swap solo la lee root; si te preocupa, cuando haya memoria libre (`free -m`) puedes hacer `sudo swapoff -a && sudo swapon -a`.

*Pruebas:* `BackupScriptsTest` (45 casos) ejecuta los tres scripts reales contra un `docker` y un `age` falsos: la copia contiene el volcado
completo byte a byte (aunque `pg_restore --list` deje de leer pronto), permisos 0600/0700, rotación (14 días, mínimo 3), **ocho formas de
copia mala** (`pg_restore --list` la rechaza, falta una tabla, `pg_restore --list` termina con error tras un índice completo, `pg_dump` falla, `age`
falla, no escribe nada, escribe basura sin cabecera, escribe solo la cabecera) en las que no se acepta nada y **no se rota ninguna copia anterior**, claves incorrectas (falta, privada en el servidor, dos),
no ser root, base de datos parada, disco lleno, bloqueo vivo y bloqueo viejo, restos de una ejecución caída, el test de restauración con
limpieza garantizada en cuatro tipos de fallo, y `restore.sh` con y sin `--yes`, identidad errónea o en disco, fallo a mitad y `--target-db`; y que **la identidad se destruye
siempre** (ocho resultados distintos, solo por estar en RAM, nunca un fichero de disco sin pedirlo, y Ctrl-C a mitad de la restauración).
`InstallScriptTest` (solo Linux) se **ejecuta en el CI** (`ubuntu-latest`, job `verify`): `./mvnw verify` corre todas las pruebas sin
filtros y la suposición que lo omite solo salta en Windows. Para que no se pueda omitir sin que nadie se entere, el job comprueba después de las
pruebas el informe de surefire de `InstallScriptTest`, `InstallReaderScriptTest`, `ServeBackupScriptTest`, `BackupScriptsTest`, `DeployScriptTest` y `VerifyScriptsTest`: cada uno debe tener
pruebas, **ninguna fallida y ninguna omitida** (paso «Check that the script tests really ran here»; `CiWorkflowTest` vigila que ese paso
exista y que `verify` se llame sin perfiles ni filtros). En el portátil con Windows se omiten sus 10 casos; la lógica del instalador también
se ejecutó a mano en WSL Ubuntu. Comprueba modos, repetición segura, el script que se vuelve
escribible por el grupo, la clave privada en el fichero de la clave pública y las carpetas superiores.

### Implementación de C7b-2: units de systemd y regla de `sudoers`

Cuatro units y una regla, todo en `deploy/backup/` y todo instalado por `sudo deploy/backup/install.sh` (el mismo instalador de C7b-1):

| Fichero del repo | Se instala en | Dueño y modo | Qué hace |
|---|---|---|---|
| `systemd/controlweb-backup.service` | `/etc/systemd/system/` | root:root 0644 | ejecuta `/usr/local/lib/controlweb/backup.sh` (`Type=oneshot`, `User=root`, sin argumentos) |
| `systemd/controlweb-backup.timer` | ídem | root:root 0644 | todos los días **04:15 UTC**, `Persistent=true` (si el servidor estaba apagado, la hace al arrancar) |
| `systemd/controlweb-restore-test.service` | ídem | root:root 0644 | ejecuta `restore-test.sh` (volcado NUEVO restaurado en `controlweb_restore_test`) |
| `systemd/controlweb-restore-test.timer` | ídem | root:root 0644 | **domingos 05:00 UTC**, `Persistent=true` |
| `controlweb-backup.sudoers` | `/etc/sudoers.d/controlweb-backup` | root:root **0440** | una sola regla, ver abajo |

Por qué esas horas: la copia de Rotten Odds sale sobre las 03:30 UTC (más hasta 10 min de retraso aleatorio) y la purga de checks de ControlWeb
(`app.retention.cron`) a las 03:30; a las 04:15 ninguna de las dos está en marcha. La prueba semanal va 45 minutos después de la copia diaria.
Los dos servicios llevan límites de tiempo (20 y 30 min), prioridad baja de CPU y de disco (`Nice`, `IOSchedulingClass=idle`) para no molestar a
Rotten Odds, y endurecimiento de systemd: `NoNewPrivileges`, `PrivateTmp`, `PrivateDevices`, `ProtectSystem=strict` (todo el sistema de ficheros
en solo lectura salvo `ReadWritePaths=/var/backups/controlweb /var/lib/controlweb-backup /run`), `ProtectHome=yes` (no ven `/home`), y protección
del núcleo, de los cgroups, de los bits SUID y de las políticas de tiempo real.

**La regla de `sudoers`** (una única línea, sin comodines, sin etiquetas, sin segundo comando):

```
jose ALL=(root) NOPASSWD: /usr/bin/systemctl start controlweb-backup.service
```

La usa `deploy.sh` para hacer la copia antes de cada despliegue sin pedir contraseña (`sudo systemctl start controlweb-backup.service`).

*Por qué no permite escalar privilegios:*

1. **Solo ese comando con esos argumentos.** En `sudoers`, un comando con argumentos concretos solo coincide con esos argumentos exactos: nada de
   `start otra.service`, de `restart`, de `--user`, de `-H` ni de órdenes añadidas detrás. No hay `*`, ni `ALL` como comando, ni `SETENV`: jose no
   puede cambiar el entorno con el que se ejecuta `systemctl`.
2. **`systemctl start` no ejecuta nada de jose.** El comando solo le pide a systemd (PID 1, que es root) que arranque una unit. Quien ejecuta el
   script es **systemd con un entorno limpio**, no el proceso de `sudo`: ni las variables de jose (`PATH`, `LD_PRELOAD`, `BASH_ENV`...), ni su
   directorio actual, ni su entrada estándar llegan al script. Además `start` no abre un paginador ni un editor (que serían la vía clásica de escape
   con `less` o `vi`; los tendría `systemctl status` o `edit`, que la regla **no** permite).
3. **La unit y el script solo los puede cambiar root.** `/etc/systemd/system/controlweb-backup.service` es root:root 0644 en una carpeta de root, y
   `ExecStart` apunta a `/usr/local/lib/controlweb/backup.sh`, root:root 0755, dentro de carpetas de root (`/usr`, `/usr/local`, `/usr/local/lib`,
   `/usr/local/lib/controlweb`). Si jose pudiera editar cualquiera de los dos, la regla sería «jose ejecuta lo que quiera como root». Por eso
   `install.sh` **comprueba** dueño y modo de cada fichero y que **ninguna carpeta de la ruta** (scripts, units y `/etc/sudoers.d`) pertenezca a
   otro usuario ni sea escribible por grupo u otros, y falla si algo no cuadra. El script **no se ejecuta nunca desde el clon** de jose.
4. **La unit no admite argumentos ni lee ficheros de jose**: sin `EnvironmentFile`, sin `Environment`, sin `ExecStartPre`, sin shell, sin
   `WorkingDirectory`. `backup.sh` solo lee `/etc/controlweb/backup-recipient.txt` (root:root) y habla con el contenedor de la base de datos.
5. **El propio `sudoers` no se puede romper a medias.** `install.sh` comprueba el fichero con `visudo -cf` **antes** de instalar nada, lo copia con
   un nombre temporal `controlweb-backup.new` (`sudo` ignora los ficheros cuyo nombre lleva un punto: no está activo), lo vuelve a validar y solo
   entonces lo renombra (`mv` es atómico). Si `visudo` lo rechaza, el fichero activo anterior sigue como estaba. Al final ejecuta `visudo -c` sobre
   **toda** la configuración y `systemd-analyze verify` sobre las units, y hace `daemon-reload`. Además se niega a instalar un fichero con más de una
   regla, con `*`, `?`, `[`, `\`, `SETENV`, `EXEC` o un comando abierto.

*Una advertencia honesta:* si jose está en el grupo `docker` (lo normal si ejecuta `docker compose` sin `sudo`), **jose ya es equivalente a root** en
ese servidor (`docker run -v /:/host ...`). Esta regla no añade riesgo a esa situación, pero tampoco la arregla: lo que protege es el caso de una
cuenta o un proceso que consiga `sudo` solo por este camino, y que la regla sea mínima y verificable. Compruébalo con `id` y decide si te vale.

*Qué debe mostrar `sudo -l` para jose* (la lista puede incluir antes las reglas por defecto del sistema; lo importante es esta línea y que
**no haya ninguna otra con `NOPASSWD`** que no conozcas):

```
User jose may run the following commands on HOST:
    (root) NOPASSWD: /usr/bin/systemctl start controlweb-backup.service
```

Y la prueba de que solo vale eso (cada una de estas tres debe **pedir contraseña o negarse**; no ejecutes nada que no sea esto):

```bash
sudo -n systemctl restart controlweb-backup.service        # «sudo: a password is required»
sudo -n systemctl start controlweb-restore-test.service    # «sudo: a password is required»
sudo -n /usr/local/lib/controlweb/backup.sh                # «sudo: a password is required»
sudo -n systemctl start controlweb-backup.service && echo "esta SÍ funciona (hace una copia de verdad)"
```

*Instalar y activar (S6, en el servidor, tú):*

```bash
sudo deploy/backup/install.sh                  # instala scripts, units y regla; valida con visudo y systemd-analyze; NO activa los timers
sudo visudo -c                                 # debe terminar con «parsed OK» en /etc/sudoers y en /etc/sudoers.d/controlweb-backup
sudo -l -U jose                                # debe mostrar la línea de arriba
sudo /usr/local/lib/controlweb/backup.sh       # primera copia a mano (ver arriba)
sudo systemctl enable --now controlweb-backup.timer controlweb-restore-test.timer
systemctl list-timers 'controlweb-*'           # próxima ejecución 04:15 UTC y el domingo 05:00 UTC
journalctl -u controlweb-backup.service -n 30  # resultado de la última copia
```

*Pruebas de C7b-2:* `SystemdUnitsGuardianTest` (7 casos, en cualquier sistema) vigila que la regla sea **exactamente** esa línea (sin `*`, sin segunda
regla, sin `ALL`), que `deploy.sh` pida esa unit, que los servicios ejecuten un único script de `/usr/local/lib/controlweb` sin argumentos ni
entorno, que conserven todo el endurecimiento y las rutas de escritura, que los horarios sean los acordados y que `install.sh` instale justo estos
ficheros y nunca active un timer. `InstallScriptTest` (ahora 10 casos, Linux, también en CI) prueba con `visudo`, `systemctl` y `systemd-analyze` falsos
que se instalan units 0644 y regla 0440, que se hace `daemon-reload` sin activar nada, que una regla rechazada por `visudo` no se instala (ni se copia
nada), que una copia rechazada nunca llega a estar activa y deja intacta la anterior, que fallan `visudo -c`, `systemd-analyze` o `daemon-reload`, y que
unit y regla vuelven a sus modos si se aflojan. Mutaciones verificadas (11, todas hacen fallar tests): comodín en la regla, segunda regla `ALL`, otro
subcomando, argumento en `ExecStart`, quitar `NoNewPrivileges`, añadir `EnvironmentFile`, cambiar la hora, hacer diario el test semanal, activar los timers
desde el instalador, ejecutar el script desde el clon y ampliar `ReadWritePaths`. Los validadores reales se ejecutaron a mano en WSL Ubuntu:
`visudo -cf` aceptó la regla, `systemd-analyze verify` aceptó las units (salvo la dependencia de `docker.service`, que WSL no tiene) y
`systemd-analyze calendar` confirmó los dos horarios (`04:15:00 UTC` diario, `Sun 05:00:00 UTC`).

### Implementación de C7b-3: descarga de las copias a tu PC, clave SSH restringida y comprobación de restauración

Hasta aquí las copias cifradas viven **solo en el servidor** (14 días). C7b-3 las trae a tu PC (donde está la clave que las descifra) y te da la
prueba que el servidor no puede hacer: que una copia guardada **se descifra y se restaura de verdad**.

```
tu PC  --ssh (clave restringida, cwbackup@HOST)-->  sshd ejecuta SOLO:  sudo -n /usr/local/lib/controlweb/serve-backup.sh
 download-backups.ps1                                   (regla de sudoers: ese comando exacto, sin argumentos)
   escribe «get controlweb-….dump.age» en un fichero, lo pasa por stdin con «cmd /c ssh … < petición > copia»
   comprueba tamaño + SHA-256 + cabecera age, y solo entonces renombra el fichero
```

| Fichero | Dónde corre / se instala | Qué es |
|---|---|---|
| `deploy/backup/serve-backup.sh` | servidor, `/usr/local/lib/controlweb/` (root:root 0755) | solo lectura: `list`, `sha256 NOMBRE`, `get NOMBRE` |
| `deploy/backup/controlweb-reader.sudoers` | servidor, `/etc/sudoers.d/controlweb-reader` (root:root 0440) | `cwbackup ALL=(root) NOPASSWD: /usr/local/lib/controlweb/serve-backup.sh` |
| `deploy/backup/install-reader.sh` | servidor, con `sudo` | crea la cuenta `cwbackup`, instala todo lo anterior y la línea de la clave |
| `deploy/backup/download-backups.ps1` | **tu PC** (Windows PowerShell 5.1) | descarga, comprueba, rota, estado, tarea programada y `-VerifyRestore` |

#### 1. Dónde y cómo generas la clave age (en tu PC, nunca en el servidor)

```powershell
winget install FiloSottile.age                       # una vez, sin ser administrador; cierra y abre PowerShell
New-Item -ItemType Directory -Force "$env:USERPROFILE\.controlweb" | Out-Null
age-keygen -o "$env:USERPROFILE\.controlweb\backup-identity.txt"      # imprime «Public key: age1…»; la privada va al fichero
icacls "$env:USERPROFILE\.controlweb" /inheritance:r /grant:r "${env:USERNAME}:(OI)(CI)F"    # solo tu usuario puede leerlo
$public = (age-keygen -y "$env:USERPROFILE\.controlweb\backup-identity.txt")
[System.IO.File]::WriteAllText("$env:USERPROFILE\.controlweb\backup-recipient.txt", $public + "`n", (New-Object System.Text.UTF8Encoding($false)))
Get-Content "$env:USERPROFILE\.controlweb\backup-recipient.txt"       # una línea que empieza por age1…
```

**Nunca uses `>` con `age-keygen` en PowerShell 5.1**: escribe el fichero en UTF-16 y age no lo lee. Usa `-o`. La clave **pública** (`age1…`) es la única
que sube al servidor (`/etc/controlweb/backup-recipient.txt`, ver «Instalación en el servidor»). La **privada**
(`backup-identity.txt`, el fichero con `AGE-SECRET-KEY-1…`) es lo único que permite leer las copias: sin ella, todas las copias son basura.

*Qué copias guardas de la privada* (la regla: **al menos dos sitios independientes**, y ninguno es el servidor ni el repositorio ni la misma nube
donde guardes las copias descargadas):

1. **El fichero en tu PC** (`~\.controlweb\backup-identity.txt`), para el día a día y `-VerifyRestore`.
2. **Una entrada en tu gestor de contraseñas** (nota segura con las tres líneas del fichero). Es la copia que sobrevive a que se rompa el disco.
3. **Una copia en papel o en un USB guardado fuera de casa** (imprime el fichero y guárdalo donde guardarías un documento importante). Es la que
   sobrevive si pierdes el PC *y* el acceso al gestor.

Prueba la copia de seguridad **de la clave**, no solo la de la base de datos: una vez al mes, `-VerifyRestore` con el fichero recuperado del gestor
(no con el del PC). Si pierdes **todas** las copias de la privada no hay forma de recuperar los datos cifrados. Si sospechas que la privada se ha
filtrado: genera un par nuevo, sustituye la pública en el servidor (las copias nuevas usan la nueva; las viejas solo se abren con la vieja, así
que conserva también la vieja mientras te hagan falta esas copias).

#### 2. La clave SSH restringida (solo para descargar)

En tu PC, una clave **distinta de la que usas para entrar como jose**, sin frase de paso (la tarea programada no puede teclearla; la restricción
del servidor es lo que la hace segura):

```powershell
ssh-keygen -t ed25519 -f "$env:USERPROFILE\.ssh\controlweb_backup_ed25519" -N '""' -C "controlweb-backup-download"   # las comillas '""' son necesarias en PowerShell 5.1
icacls "$env:USERPROFILE\.ssh\controlweb_backup_ed25519" /inheritance:r /grant:r "${env:USERNAME}:R"                 # OpenSSH se niega a usar una privada legible por otros
scp "$env:USERPROFILE\.ssh\controlweb_backup_ed25519.pub" jose@HOST:      # la PÚBLICA (.pub)
```

En el servidor, revisando antes lo que instalas (y **sin** que `install-reader.sh` toque la configuración de `sshd`):

```bash
sudo sshd -T | grep -iE '^(allowusers|allowgroups|pubkeyauthentication|authorizedkeysfile|permitrootlogin)'
#   si hay «allowusers» o «allowgroups», cwbackup tiene que estar incluido: es un cambio de sshd_config que decides tú
cd ~/controlweb && git log -1 --stat && less deploy/backup/serve-backup.sh deploy/backup/install-reader.sh
sudo deploy/backup/install.sh                                              # la carpeta /usr/local/lib/controlweb tiene que existir
sudo deploy/backup/install-reader.sh --pubkey ~/controlweb_backup_ed25519.pub              # opcional: --from TU.IP.PUBLICA
cat /var/lib/cwbackup/.ssh/authorized_keys                                 # restrict,command="sudo -n /usr/local/lib/controlweb/serve-backup.sh" ssh-ed25519 AAAA… controlweb-backup-download
sudo -l -U cwbackup                                                        # solo: (root) NOPASSWD: /usr/local/lib/controlweb/serve-backup.sh
```

*Qué hace cada pieza de la línea de `authorized_keys`:* `restrict` desactiva de golpe terminal, reenvío de puertos, agente, X11 y `~/.ssh/rc`;
`command="…"` obliga a que, **pida lo que pida el cliente**, sshd ejecute solo ese comando (lo que escriba el cliente en la línea de órdenes se
ignora: la petición viaja por la entrada estándar). `from="IP"` (opcional) limita la clave a tu IP. Si tu IP cambia mucho, no lo uses.

*Por qué no permite escalar privilegios:*

1. El fichero `authorized_keys`, su carpeta y el home de `cwbackup` (`/var/lib/cwbackup`) son **root:root**: la cuenta no puede reescribir su propia línea para quitarse la restricción. `install-reader.sh` lo comprueba (dueño, modo y que ninguna carpeta de la ruta sea escribible por otros).
2. La cuenta no tiene contraseña (cuenta de sistema, bloqueada): solo entra con esa clave, y esa clave solo ejecuta ese comando.
3. `serve-backup.sh` es root:root 0755 en una carpeta de root y la regla de `sudoers` es **ese comando exacto sin argumentos**: no hay comodín que abrir.
4. La petición (una línea, máximo 200 caracteres) se valida contra una lista cerrada: `list`; `sha256 NOMBRE`; `get NOMBRE`, y `NOMBRE` debe coincidir con `controlweb-AAAAMMDDTHHMMSSZ.dump.age`. Sin rutas, sin `..`, sin comodines, sin opciones, sin enlaces simbólicos, sin segunda línea: más de 30 peticiones hostiles distintas están en `ServeBackupScriptTest` y todas dan «refused», estado 2 y salida vacía.
5. Solo lee: el script no escribe, no borra ni cambia permisos (un test lo comprueba sobre el texto y sobre la carpeta).
6. `install-reader.sh` rechaza antes de tocar nada: una clave privada, una clave con opciones propias (`command=`, `permitopen=`…), otro tipo de clave que no sea `ssh-ed25519`, varias claves, y un `--from` que no sea una IP o un rango.

*Qué pasa si te roban la clave SSH de descarga:* solo pueden bajar texto cifrado que no pueden abrir y no pueden cambiar ni borrar nada. Rótala (genera otra,
`install-reader.sh --pubkey` de nuevo: sustituye la línea). *Qué pasa si te roban la clave age privada:* eso sí es grave (ver arriba).

Pruebas que **debes ver tú** (la restricción funciona de verdad; nada de esto cambia el servidor):

```powershell
.\deploy\backup\download-backups.ps1 -SshTarget cwbackup@HOST -List      # lista las copias
ssh -i "$env:USERPROFILE\.ssh\controlweb_backup_ed25519" cwbackup@HOST id < NUL        # debe decir «refused: empty request»: NO ejecuta id, NO da shell
ssh -i "$env:USERPROFILE\.ssh\controlweb_backup_ed25519" cwbackup@HOST "get ../../etc/passwd" < NUL   # «refused: empty request»: la orden de la línea se ignora
```

#### 3. Descargar

```powershell
[Environment]::SetEnvironmentVariable('CONTROLWEB_BACKUP_SSH', 'cwbackup@HOST', 'User')    # una vez; reabre PowerShell
.\deploy\backup\download-backups.ps1 -DryRun                                # qué haría
.\deploy\backup\download-backups.ps1                                        # descarga lo que falte a ~\controlweb-backups
.\deploy\backup\download-backups.ps1 -Status                                # copia más reciente, última ejecución, tarea programada
```

Para cada copia que falta: `get` a un fichero `.partial` (la redirección la hace `cmd.exe`: `ssh … < petición.txt > copia.partial`, byte a byte;
**ni tuberías de PowerShell con datos binarios ni `>` de PowerShell**, que en 5.1 los convierten a texto y los corrompen); compara el **tamaño** con
el que dice el servidor; compara el **SHA-256** con el que calcula el servidor; comprueba la **cabecera `age-encryption.org/v1`**; y solo entonces
renombra. Si algo falla borra el parcial, **no toca las copias buenas**, escribe `last-failure.txt` y sale con 1. La rotación local (`-Keep 30`) solo se
hace tras una ejecución completa y buena. Salida 3 = la copia más reciente **del servidor** tiene más de `-MaxAgeHours` (36 h): el servidor dejó de
hacer copias aunque la descarga funcione. *Límite honesto:* el SHA-256 lo calcula el mismo servidor, así que detecta errores de transferencia, no un
servidor malicioso; la prueba real es `-VerifyRestore`.

#### 4. Tarea programada de Windows

```powershell
.\deploy\backup\download-backups.ps1 -InstallTask -SshTarget cwbackup@HOST -TaskTime 10:00      # diaria; -RemoveTask la quita
Get-ScheduledTaskInfo -TaskName 'ControlWeb backup download' | Format-List LastRunTime, LastTaskResult, NextRunTime
```

La tarea corre como **tu usuario, solo cuando has iniciado sesión** (no guarda tu contraseña), sin elevar permisos, también con batería, y solo si hay red.

- **Si el PC está apagado a las 10:00**: `StartWhenAvailable` la ejecuta en cuanto se pueda tras arrancar e iniciar sesión (una sola vez; descarga todo lo que falte). El servidor guarda 14 días: si el PC pasa más de 14 días apagado, las copias de ese hueco se pierden para tu PC (siguen siendo copias del servidor mientras no rote).
- **Si falla** (sin red, clave mala, servidor caído…): reintenta 3 veces cada 30 min; sale con 1 (fallo) o 3 (servidor sin copias recientes).
- **Cómo ver que falló**: no hay aviso emergente (no quiero que un fallo silencioso parezca un éxito, pero tampoco fingir una alerta que no existe). Mira:
  1. `.\deploy\backup\download-backups.ps1 -Status` (sale con 1 si la copia local más reciente tiene más de 36 h) y qué dice `last-run.txt` / `last-failure.txt` en `~\controlweb-backups`.
  2. `Get-ScheduledTaskInfo` (`LastTaskResult` distinto de 0 = falló) o el Programador de tareas (`taskschd.msc`).
  3. `~\controlweb-backups\download.log` (todo lo que hizo, con hora UTC).
  Hábito recomendado: los domingos, `-Status`; el primer domingo de cada mes, `-VerifyRestore`.

#### 5. Comprobar en tu PC que una copia descargada se descifra y se restaura de verdad

```powershell
winget install FiloSottile.age                                                                  # si aún no lo tienes
.\deploy\backup\download-backups.ps1 -VerifyRestore -Identity "$env:USERPROFILE\.controlweb\backup-identity.txt"
# una copia concreta (cualquiera de las descargadas):
.\deploy\backup\download-backups.ps1 -VerifyRestore -Identity "$env:USERPROFILE\.controlweb\backup-identity.txt" -CopyName controlweb-20260101T041500Z.dump.age
```

Necesita Docker Desktop en marcha (~512 MiB durante 1–2 minutos). Qué hace, en este orden: (1) `age --decrypt -i IDENTIDAD -o ...\restore.dump COPIA` (age escribe
el fichero él mismo, **sin tuberías**; la identidad no se imprime ni se copia); (2) comprueba que lo descifrado empieza por `PGDMP` (un archivo de
PostgreSQL); (3) arranca un PostgreSQL desechable con **la misma imagen exacta que `compose.prod.yml`** y límite de 512 MiB; (4) `docker cp` + `pg_restore
--no-owner --no-privileges --exit-on-error --single-transaction`; (5) cuenta las filas de `sites`, `checks`, `incidents`, `alert_rules`, `users` y
`flyway_schema_history` (y la última migración); (6) **siempre**, aunque algo falle, borra el contenedor y la carpeta temporal con el volcado descifrado.
Termina con `RESTORE OK` y estado 0, o `RESTORE FAILED` y estado 1.

Dónde queda el volcado descifrado mientras dura: en `%TEMP%\controlweb-verify-…` y dentro del contenedor, ambos borrados al terminar. Borrar en NTFS no
sobrescribe los bloques: si te importa, activa BitLocker o el «cifrado de dispositivo» de Windows (Configuración → Privacidad y seguridad). A mano, sin el
script (si falla algo y quieres ver dónde): `age --decrypt -i IDENTIDAD -o restore.dump COPIA.age`, luego `docker run -d --name prueba -m 512m -e POSTGRES_PASSWORD=cualquiera -e POSTGRES_DB=prueba <imagen de compose.prod.yml>`,
`docker cp restore.dump prueba:/tmp/`, `docker exec prueba pg_restore -U postgres -d prueba --no-owner --no-privileges --single-transaction /tmp/restore.dump`,
`docker rm -f prueba`. **Nunca** `age -d … | docker exec -i …` en PowerShell 5.1 (la tubería corrompe datos binarios).

*Pruebas de C7b-3 y qué cubren:* `ServeBackupScriptTest` (8 casos; en CI también) — salida exacta byte a byte con todos los valores de byte, más de 30 peticiones hostiles
rechazadas sin salida, solo lectura, enlace simbólico rechazado. `InstallReaderScriptTest` (7 casos; Linux, **también en CI**) — línea de clave exacta, modos, cuenta creada una sola vez,
rechazos de claves privadas/con opciones/RSA/varias/vacías/age, validación de `--from`, `visudo` que rechaza. `DownloadBackupsScriptTest` (16 casos; Windows, `@Tag("slow-scripts")`)
ejecuta el script **real** con un sustituto de `ssh.exe` que ejecuta el `serve-backup.sh` real: binario idéntico, repetición sin descargas, copia nueva,
descarga corrupta/truncada/checksum falso/sin cabecera age rechazadas sin dejar nada, fallo de conexión, rotación solo tras éxito, servidor obsoleto (3), `-Status`, `-DryRun`,
reglas estáticas y `-VerifyRestore` contra un `age` y un `docker` falsos (éxito, `pg_restore` que falla, tabla ausente, identidad errónea, no es un archivo, Docker parado: siempre limpia).
`FastProfileGuardianTest` y el paso de CI vigilan que estas clases se ejecuten siempre en CI (las de Windows solo se ejecutan en Windows).

*Mutaciones verificadas de C7b-3* (cada una hace fallar tests): `serve-backup.sh` sin comprobar el patrón del nombre, sin el filtro de caracteres, o escribiendo
en la carpeta; `download-backups.ps1` sin comparar el SHA-256, sin comparar el tamaño, sin comprobar la cabecera age, dejando el `.partial` malo, con
`StrictHostKeyChecking=no`, rotando antes de comprobar, sin tratar como error la copia obsoleta del servidor, sin borrar el contenedor, sin borrar el volcado
descifrado, sin comprobar `PGDMP` y sin tratar como error una tabla ausente (14 en total); y, en WSL Ubuntu, `install-reader.sh` sin la comprobación del formato de la clave
(instalaría una clave con `command="/bin/sh"` delante). Además se vio que sudo en Ubuntu puede ejecutar el comando en un pseudo-terminal y estropear datos binarios:
por eso la regla lleva `Defaults:cwbackup !use_pty` (sin terminal en este flujo no se pierde nada) y, de todos modos, el SHA-256 y el tamaño harían fallar la descarga en voz alta.
**Lo primero que debes mirar al probarlo en el servidor** es que `-List` funciona y que la primera descarga termina sin «checksum mismatch».

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
| **C7b-2 [CW]** ✅ | Units de systemd (copia 04:15 UTC, prueba semanal), regla de `sudoers` validada con `visudo -c` | Hecho: ver «Implementación de C7b-2» | revert |
| **C7b-3 [CW]** ✅ | Descarga a tu PC (`download-backups.ps1`), clave SSH restringida, tarea programada de Windows, verificación de restauración local | Hecho: ver «Implementación de C7b-3» | revert |
| **C7c [CW]** | Cortafuegos de salida (`controlweb-firewall`), `caddy-install.sh`, fragmento de Caddy y shellcheck en CI | C7c-1 y C7c-2 hechos (ver sus secciones); falta C7c-3 (shellcheck en CI) | revert |

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
