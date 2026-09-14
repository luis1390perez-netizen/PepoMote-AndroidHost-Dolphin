# Party en «Dolphin en Android»: varios móviles, un solo Dolphin

Diseño aprobado el 2026-09-13. Amplía el modo «Dolphin en Android»
(`docs/DOLPHIN-ANDROID.md`) de **un** Mando de Wii a **hasta cuatro**, sin PC
y sin que el tráfico de juego pase por ningún intermediario.

## El problema

Hoy el modo sirve un solo mando: un móvil abre su servidor DSU en
`0.0.0.0:26760` y el Dolphin de otro dispositivo lee de él. Para jugar varios
hacen falta cuatro móviles mandando a la vez al mismo Dolphin, y alguien tiene
que producir los dos INI que Dolphin necesita con los cuatro dentro.

## La decisión de arquitectura

Dolphin admite **varios servidores DSU simultáneos**, sin límite. Verificado en
`DualShockUDPClient.cpp`: las entradas de `DSUClient.ini` se parten por `;`,
cada una es `descripción:dirección:puerto`, y cada dispositivo acaba llamándose
`DSUClient/<slot>/<descripción>`. Cada servidor se sondea además en sus cuatro
slots.

Eso permite que **cada móvil siga siendo exactamente lo que ya es hoy**: su
propio servidor DSU, en su IP, puerto 26760, slot 0. Lo único que cambia es el
archivo que se le deja a Dolphin, que ahora nombra a los N.

```
Móvil P1 (PepoMote) ──DSU/UDP──┐
Móvil P2 (PepoMote) ──DSU/UDP──┼──> Dolphin (otro dispositivo)
Móvil P3 (PepoMote) ──DSU/UDP──┘
```

### Alternativa descartada: un host que enruta

La idea inicial era que P1 hiciera de receptor (como el PC): los demás le
mandan PMP y él sirve los cuatro slots de un único servidor DSU. Da un archivo
con una sola IP, que sobrevive a que cambie la IP de un invitado.

Se descarta porque obliga a llevar a Android el lado **servidor** de PMP (TCP
de control, emparejamiento, reparto de slots, UDP de telemetría), que hoy vive
dentro del binario `desktop/` y no es un crate reutilizable: habría que
extraerlo y envolverlo por JNI. Además mete un salto de red extra en el camino
de la telemetría, convierte a P1 en punto único de fallo y le hace gastar
batería enrutando además de ser mando.

El papel de «anfitrión» se conserva, pero **solo para coordinar**: P1 recoge
quién juega y genera los archivos. El tráfico de juego nunca pasa por él.

## Roles

| | Sirve DSU | Muestra QR | Recoge jugadores | Genera los INI |
|---|---|---|---|---|
| P1 (anfitrión) | sí | sí | sí | sí |
| P2–P4 (invitados) | sí | no | no | no |

Todos los móviles son idénticos en el camino de datos. «Anfitrión» es un rol de
coordinación, no de red.

## Cómo se unen

### El QR

P1 muestra un QR con el mismo esquema que ya usa el emparejamiento con el PC
(`PROTOCOL.md` §1, `pepomote://pair?…`):

```
pepomote://party?v=1&host=<ip de P1>&port=26761&name=<nombre url-encoded>
```

El puerto 26761 TCP está libre en el móvil: hasta ahora el móvil solo era
*cliente* de ese puerto en el PC. No choca con el 26760/UDP del DSU.

### El canal de party

TCP, una línea JSON por mensaje terminada en `\n`, igual que el canal de
control de PMP.

| Sentido | Mensaje |
|---|---|
| invitado → P1 | `{"m":"join","v":1,"name":"Pixel 7"}` |
| P1 → invitado | `{"m":"joined","player":2,"host":"Galaxy S21"}` |
| P1 → invitado | `{"m":"err","e":"party_full"}` y cierra |
| invitado → P1 | `{"m":"ping"}` cada 3 s |

**P1 saca la IP del propio socket**, nunca de lo que diga el invitado: es el
dato que acaba dentro del INI y no debe poder falsearse por accidente.

La conexión se queda **abierta mientras dure la party**: es la señal de vida.
Si el socket se cierra, ese jugador sale de la lista. El `ping` cada 3 s cubre
el caso de un móvil que desaparece sin cerrar (Wi-Fi apagado, batería); P1 lo
da por muerto a los 10 s sin recibir nada. Sin esto, un fantasma dejaría su IP
en el archivo y Dolphin tendría un mando que nunca responde.

### Números de jugador

P1 es siempre el 1. Los demás reciben el primer hueco libre entre 2 y 4, por
orden de llegada. Si uno se cae, su hueco queda libre para el siguiente; si
vuelve, puede recibir un número distinto. No se intenta recordar quién era
quién: renumerar es más simple y el usuario ya ve la lista.

Máximo cuatro, que es lo que emula Dolphin (`MAX_PLAYERS`).

### Volver sin reescanear

El invitado guarda la última party (host y puerto) y, al entrar en el modo,
intenta reconectar solo antes de ofrecer el escáner. Los móviles de siempre no
vuelven a escanear nunca; uno nuevo escanea una vez. Si la reconexión falla
(P1 apagado, IP cambiada), se cae al escáner sin ruido.

## Los archivos

Los genera P1 y son los únicos que se copian a Dolphin. Siguen saliendo de
`dsu/src/ini.rs`, que es la fuente de verdad compartida con el receptor de PC.

```ini
# DSUClient.ini
[Server]
Enabled = True
Entries = PepoMote-P1:192.168.20.31:26760;PepoMote-P2:192.168.20.32:26760;
```

```ini
# WiimoteNew.ini
[Wiimote1]
Device = DSUClient/0/PepoMote-P1
Source = 1
…
[Wiimote2]
Device = DSUClient/0/PepoMote-P2
Source = 1
…
[Wiimote3]
Source = 0
[Wiimote4]
Source = 0
```

Cada móvil aporta **su** slot 0; lo que los distingue es el nombre del
servidor, no el slot.

### Cambios en `dsu/src/ini.rs`

| Antes | Después | Quién lo usa |
|---|---|---|
| `wiimote_mapping(slot: u8)` | `wiimote_mapping(device: &str)` | PC y Android |
| `dsu_entry(host, port)` | `dsu_entry(name, host, port)` | PC y Android |
| `dsu_client_ini(host, port)` | `dsu_client_ini(hosts: &[&str], port)` | solo Android |
| `wiimote_new_ini(slot: u8)` | `wiimote_new_ini(n_players: usize)` | solo Android |
| — | `player_name(n: usize) -> String` | nuevo, compartido |

`wiimote_mapping` pasa de recibir un slot a recibir el device completo porque
las dos topologías lo construyen distinto: el PC es **un servidor, N slots**
(`DSUClient/<slot>/PepoMote`) y la party es **N servidores, slot 0**
(`DSUClient/0/PepoMote-P<n>`). El receptor de PC construye su cadena en la
llamada y no cambia de comportamiento; son dos sitios,
`desktop/src/dolphin.rs:570` (`write_wiimotes`) y `:645` (`write_profiles`),
más `dsu_entry` en `:621`. `nunchuk_mapping` no se toca: en el PC el Nunchuk
sigue leyendo de otro slot del mismo servidor.

`player_name(n)` devuelve `PepoMote-P<n>` y lo usan **los dos** INI. Es
deliberado: si el nombre de `Entries` y el de `Device` se desincronizaran,
Dolphin no encontraría nada y el fallo sería mudo. Un test lo fija.

`dsu_client_ini` y `wiimote_new_ini` solo las llama `dsu-android`, así que
cambiarles la firma no toca al receptor.

### El puente JNI

`dsu-android/src/lib.rs` pasa a recibir la lista de IPs ya ordenada, en una
sola cadena separada por comas, y el número de jugadores:

- `nativeDsuClientIni(hosts: String, port: Int) -> String` — `"ip1,ip2"`
- `nativeWiimoteIni(count: Int) -> String`

Los nombres no cruzan el puente: se derivan de la posición con `player_name`,
en Rust, para que no haya dos sitios que los inventen.

## Android

| Archivo | Qué cambia |
|---|---|
| `net/Party.kt` | **nuevo**: servidor TCP de P1 (acepta, lee, vigila el latido) y cliente de invitado; estado en un `StateFlow` |
| `net/PartyStore.kt` | **nuevo**: recuerda la última party para reconectar sin escanear |
| `net/DsuServer.kt` | firmas nuevas de los dos INI |
| `service/DsuForegroundService.kt` | la party vive aquí, junto al DSU, para sobrevivir a la pantalla apagada |
| `ui/screens/DolphinAndroidScreen.kt` | QR, lista de jugadores en vivo, archivos con todos |
| `MainActivity.kt` | `pepomote://party?…` por deep link y por escáner |
| `res/values*/strings.xml` | textos es + en (los fija `StringsParityTest`) |

`service/Route.kt` **no se toca**: decide qué pantalla de mando se enseña y
eso no cambia — cada móvil sigue siendo un Mando de Wii, esté en party o no.

El QR se genera con zxing, que ya está en las dependencias por el escáner.

### Qué ve el usuario

- **P1**: «Crear party» → QR + lista («P1 este móvil», «P2 Pixel 7»…) + las dos
  tarjetas de archivo, ya con todos dentro.
- **Invitado**: «Unirse a una party» → escáner → «Unido como P2». Sin tarjetas
  de archivo: los genera el anfitrión.

## Fuera de alcance

- **Nunchuk.** Primero cuatro mandos pelados, que es lo que pide Wii Sports.
- **Elegir a mano quién es P2 o P3**: va por orden de llegada.
- **Teclear la IP sin cámara.** El escáner es el único camino en v1.
- **Cemu / modo puntero**: siguen siendo cosa del receptor de PC.

## Riesgos aceptados

- **Sin autenticación.** Cualquiera en la misma Wi-Fi que conozca la IP y el
  puerto puede ocupar un hueco. El impacto máximo es un colado ocupando un
  mando en una LAN doméstica. Si llegara a molestar, el siguiente paso es el
  código de 4 dígitos que ya usa el emparejamiento con el PC.
- **Las IPs van dentro del archivo.** Si cambia una hay que regenerar y
  recopiar. Mitigación documentada: compartir Internet desde P1, así P1 es el
  router y las IPs no se mueven.
- **Orden de arranque.** Todos los móviles deben estar sirviendo *antes* de
  abrir Dolphin; el que falte no aparecerá en su lista de dispositivos. Va en
  el doc como paso explícito.

## Pruebas

**Rust (`dsu`)** — los INI con N jugadores: que `Entries` lleve una entrada por
móvil, que cada `[WiimoteN]` apunte a `DSUClient/0/PepoMote-P<n>`, que los
sobrantes queden en `Source = 0`, y que el nombre de `Entries` y el de `Device`
coincidan para todo n. Los 146 tests del receptor son la red que prueba que el
PC no se movió.

**Kotlin** — parseo de `pepomote://party?…` (incluida una URL mal formada), la
asignación al primer hueco libre y la expulsión por latido perdido.

**Manual, con hardware** — dos móviles y un Dolphin: que los dos aparezcan en
la lista de dispositivos de Dolphin, que cada uno mueva su propio mando, y que
al matar a uno el otro siga jugando. `desktop/e2e/dsu_probe.py <ip>` contra
cada móvil por separado sigue siendo la forma de aislar si el fallo es del
servidor o de la configuración de Dolphin.
