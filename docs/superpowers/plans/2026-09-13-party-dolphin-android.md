# Party en «Dolphin en Android» — plan de implementación

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Que hasta cuatro móviles sean cuatro Mandos de Wii contra un mismo Dolphin de otro dispositivo Android, sin PC.

**Architecture:** Cada móvil sigue sirviendo su propio DSU en su IP:26760, slot 0 — el camino de datos no cambia. El móvil P1 hace de anfitrión **solo para coordinar**: muestra un QR, recoge por TCP a quien se una y genera los dos INI que nombran a todos. Dolphin admite varios servidores DSU a la vez, así que el tráfico de juego nunca pasa por P1.

**Tech Stack:** Rust (crates `dsu`, `dsu-android` con JNI), Kotlin + Jetpack Compose (Android), zxing (QR), `org.json` (mensajes).

**Spec:** `docs/superpowers/specs/2026-09-13-party-dolphin-android-design.md`

## Global Constraints

- Comentarios, docs y mensajes de commit **en español** (CLAUDE.md).
- Textos de interfaz en **español e inglés**, con `StringsParityTest` vigilando que las claves y los `%1$s` coincidan.
- `MAX_PLAYERS = 4` — lo que emula Dolphin. Definido en `dsu/src/lib.rs`.
- Puerto DSU: **26760/UDP**. Puerto de party: **26761/TCP**.
- Nombre de servidor DSU de cada jugador: `PepoMote-P<n>`, generado **solo** por `player_name(n)` en `dsu/src/ini.rs`.
- `protocol/PROTOCOL.md` manda sobre el código. La party es un canal nuevo: hay que documentarlo ahí.
- `dsu/src/mapping.rs` no se toca en todo el plan.
- Sin dependencias nuevas: zxing y `org.json` ya están.

---

### Task 1: `ini.rs` genera los INI de N jugadores

**Files:**
- Modify: `dsu/src/ini.rs`

**Interfaces:**
- Consumes: `crate::MAX_PLAYERS`
- Produces:
  - `pub fn player_name(n: usize) -> String` → `"PepoMote-P1"`
  - `pub fn wiimote_mapping(device: &str) -> String` (antes tomaba `slot: u8`)
  - `pub fn dsu_entry(name: &str, host: &str, port: u16) -> String`
  - `pub fn dsu_client_ini(hosts: &[&str], port: u16) -> String`
  - `pub fn wiimote_new_ini(n_players: usize) -> String`
  - `pub fn nunchuk_mapping(slot: u8) -> String` — **sin cambios**

> Nota: el RED de esta tarea ya está aplicado en el árbol de trabajo (los tests de `dsu/src/ini.rs` ya piden las firmas nuevas). Empieza por el Step 2 para verlos fallar.

- [ ] **Step 1: Escribir los tests que fallan**

En `dsu/src/ini.rs`, dentro de `mod tests`, sustituir `el_slot_va_en_el_device`, `entrada_dsu_sin_punto_y_coma`, `dsu_client_ini_completo` y el arranque de `wiimote_new_ini_enciende_uno_y_apaga_los_demas` por:

```rust
    #[test]
    fn el_device_va_tal_cual_en_el_mapeo() {
        // El PC: un servidor, N slots. La party: N servidores, slot 0.
        assert!(wiimote_mapping("DSUClient/0/PepoMote")
            .starts_with("Device = DSUClient/0/PepoMote\nSource = 1\n"));
        assert!(wiimote_mapping("DSUClient/2/PepoMote")
            .starts_with("Device = DSUClient/2/PepoMote\n"));
        assert!(wiimote_mapping("DSUClient/0/PepoMote-P3")
            .starts_with("Device = DSUClient/0/PepoMote-P3\n"));
        assert!(nunchuk_mapping(3).contains("Nunchuk/Buttons/C = `DSUClient/3/PepoMote:Cross`"));
    }

    #[test]
    fn el_jugador_da_nombre_al_servidor() {
        assert_eq!(player_name(1), "PepoMote-P1");
        assert_eq!(player_name(4), "PepoMote-P4");
    }

    #[test]
    fn entrada_dsu_sin_punto_y_coma() {
        assert_eq!(dsu_entry("PepoMote", "127.0.0.1", 26760), "PepoMote:127.0.0.1:26760");
        assert_eq!(
            dsu_entry("PepoMote-P2", "192.168.1.42", 26760),
            "PepoMote-P2:192.168.1.42:26760"
        );
    }

    #[test]
    fn dsu_client_ini_de_un_solo_movil() {
        let ini = dsu_client_ini(&["192.168.1.42"], 26760);
        assert_eq!(
            ini,
            "[Server]\nEnabled = True\nEntries = PepoMote-P1:192.168.1.42:26760;\n"
        );
    }

    #[test]
    fn dsu_client_ini_de_la_party_lista_a_todos() {
        let ini = dsu_client_ini(&["192.168.1.42", "192.168.1.43", "192.168.1.44"], 26760);
        assert_eq!(
            ini,
            "[Server]\nEnabled = True\nEntries = PepoMote-P1:192.168.1.42:26760;\
             PepoMote-P2:192.168.1.43:26760;PepoMote-P3:192.168.1.44:26760;\n"
        );
    }

    /// Si el nombre de `Entries` y el de `Device` se desincronizaran, Dolphin
    /// no encontraría nada y el fallo sería mudo: los dos salen de player_name.
    #[test]
    fn el_nombre_de_entries_y_el_del_device_son_el_mismo() {
        let hosts = ["10.0.0.1", "10.0.0.2", "10.0.0.3", "10.0.0.4"];
        let server = dsu_client_ini(&hosts, 26760);
        let wiimotes = wiimote_new_ini(hosts.len());
        for n in 1..=hosts.len() {
            let name = player_name(n);
            assert!(server.contains(&format!("{name}:{}:26760;", hosts[n - 1])), "falta {name}");
            assert!(
                wiimotes.contains(&format!("Device = DSUClient/0/{name}\n")),
                "el Wiimote{n} no apunta a {name}"
            );
        }
    }

    #[test]
    fn la_party_enciende_un_mando_por_jugador_y_apaga_el_resto() {
        let ini = wiimote_new_ini(3);
        assert_eq!(ini.matches("Source = 1").count(), 3, "un mando por jugador");
        for n in 1..=3 {
            assert!(ini.contains(&format!("[Wiimote{n}]\nDevice = DSUClient/0/PepoMote-P{n}\n")));
        }
        assert!(ini.contains("[Wiimote4]\nSource = 0\n"), "el cuarto se apaga");
        assert_eq!(ini.matches("Extension = None").count(), 3, "ninguno lleva Nunchuk");
    }

    /// Dolphin emula cuatro: un quinto no cabe y pedirlo no debe colarse.
    #[test]
    fn la_party_se_corta_en_max_players() {
        let ini = wiimote_new_ini(9);
        assert_eq!(ini.matches("Source = 1").count(), MAX_PLAYERS);
        assert!(!ini.contains("[Wiimote5]"));
    }
```

Y cambiar la primera línea de `wiimote_new_ini_enciende_uno_y_apaga_los_demas` a:

```rust
        let ini = wiimote_new_ini(1);
        assert!(ini.starts_with("[Wiimote1]\nDevice = DSUClient/0/PepoMote-P1\nSource = 1\n"));
```

- [ ] **Step 2: Ver que fallan**

Run: `cd dsu && cargo test ini::`
Expected: FAIL — `cannot find function player_name`, y `wiimote_mapping`/`dsu_entry`/`dsu_client_ini`/`wiimote_new_ini` con argumentos que no cuadran.

- [ ] **Step 3: Implementar lo mínimo**

En `dsu/src/ini.rs`, sustituir las cuatro funciones (dejar `nunchuk_mapping` intacta):

```rust
/// El nombre con el que el jugador `n` (1..=MAX_PLAYERS) sale en Dolphin.
/// Lo usan los DOS archivos: `Entries` en DSUClient.ini y `Device` en
/// WiimoteNew.ini. Si cada uno lo inventara por su cuenta y se
/// desincronizaran, Dolphin no encontraría el mando y no diría por qué.
pub fn player_name(n: usize) -> String {
    format!("PepoMote-P{n}")
}

/// Cuerpo de `[WiimoteN]` leyendo del device DSU dado, ya montado por quien
/// llama: el receptor de PC es UN servidor con N slots
/// (`DSUClient/<slot>/PepoMote`) y la party de móviles son N servidores en el
/// slot 0 (`DSUClient/0/PepoMote-P<n>`).
pub fn wiimote_mapping(device: &str) -> String {
    MAPPING.replace("{DEV}", device)
}

/// Una entrada de la clave `Entries` de DSUClient.ini, SIN el `;` final
/// (quien la inserta decide si hay que separarla de las que ya había).
pub fn dsu_entry(name: &str, host: &str, port: u16) -> String {
    format!("{name}:{host}:{port}")
}

/// `DSUClient.ini` entero con un servidor por móvil, en orden de jugador.
/// Para colocarlo tal cual donde no se puede escribir por código (Android).
/// Pisa cualquier otro servidor DSU configurado: es el precio de darlo hecho.
pub fn dsu_client_ini(hosts: &[&str], port: u16) -> String {
    let entries: String = hosts
        .iter()
        .take(MAX_PLAYERS)
        .enumerate()
        .map(|(i, host)| format!("{};", dsu_entry(&player_name(i + 1), host, port)))
        .collect();
    format!("[Server]\nEnabled = True\nEntries = {entries}\n")
}

/// `WiimoteNew.ini` entero: un Mando de Wii emulado por jugador, cada uno
/// leyendo del slot 0 de SU móvil, y los sobrantes apagados (un mando emulado
/// de más sale en pantalla en los juegos y estorba).
pub fn wiimote_new_ini(n_players: usize) -> String {
    let n = n_players.clamp(1, MAX_PLAYERS);
    let mut out = String::new();
    for i in 1..=n {
        let device = format!("DSUClient/0/{}", player_name(i));
        out.push_str(&format!("[Wiimote{i}]\n{}Extension = None\n", wiimote_mapping(&device)));
    }
    for i in n + 1..=MAX_PLAYERS {
        out.push_str(&format!("[Wiimote{i}]\nSource = 0\n"));
    }
    out
}
```

Cambiar también la línea del `MAPPING` para que el hueco sea el device entero:

```rust
const MAPPING: &str = "Device = {DEV}
```

(antes era `Device = DSUClient/{DEV}/PepoMote`)

- [ ] **Step 4: Ver que pasan**

Run: `cd dsu && cargo test`
Expected: PASS, 25 tests, sin warnings.

- [ ] **Step 5: Commit**

```bash
git add dsu/src/ini.rs
git commit -m "dsu: los INI de Dolphin admiten varios moviles, uno por jugador"
```

---

### Task 2: El receptor de PC se adapta a las firmas nuevas

El PC no cambia de comportamiento: sigue siendo un servidor con N slots. Solo construye ahora la cadena del device en la llamada.

**Files:**
- Modify: `desktop/src/dolphin.rs:570`, `:621`, `:645`

**Interfaces:**
- Consumes: `ini::wiimote_mapping(device: &str)`, `ini::dsu_entry(name, host, port)` de la Task 1
- Produces: nada nuevo

- [ ] **Step 1: Ver el fallo de compilación (es el «test rojo» aquí)**

Run: `cd desktop && cargo build --release`
Expected: FAIL — tres errores de tipos: `wiimote_mapping` recibe `&str` y no `u8` (dos sitios), `dsu_entry` quiere tres argumentos.

- [ ] **Step 2: Arreglar las tres llamadas**

En `write_wiimotes` (línea ~570):

```rust
        let device = format!("DSUClient/{wslot}/PepoMote");
        let mut body: Vec<String> = ini::wiimote_mapping(&device).lines().map(|l| l.to_owned()).collect();
```

En `ensure_dsu_server` (línea ~621):

```rust
    let entry = ini::dsu_entry("PepoMote", "127.0.0.1", dsu_server::DSU_PORT);
```

En `write_profiles` (línea ~645):

```rust
        let device = format!("DSUClient/{slot}/PepoMote");
        let body: String = ini::wiimote_mapping(&device)
```

- [ ] **Step 3: Ver que todo pasa**

Run: `cd desktop && cargo test --release`
Expected: PASS, 146 tests. Los de `dolphin::tests` (`dsu_server_agrega_sin_duplicar_ni_borrar`, `un_mando_por_movil_y_los_demas_apagados`, `wiimotes_escribe_slots_y_preserva_lo_ajeno`) son la prueba de que el PC no se movió.

- [ ] **Step 4: Commit**

```bash
git add desktop/src/dolphin.rs
git commit -m "receptor: monta el device DSU en la llamada, sin cambiar lo que escribe"
```

---

### Task 3: El puente JNI pasa la lista de móviles

**Files:**
- Modify: `dsu-android/src/lib.rs:115-143`

**Interfaces:**
- Consumes: `dsu::ini::{dsu_client_ini, wiimote_new_ini}` de la Task 1
- Produces (firmas Java que consume la Task 4):
  - `nativeDsuClientIni(hosts: String, port: jint) -> jstring` — `hosts` = IPs separadas por comas, en orden de jugador
  - `nativeWiimoteIni(count: jint) -> jstring`

Los nombres `PepoMote-P<n>` **no** cruzan el puente: se derivan de la posición, en Rust, para que no haya dos sitios que los inventen.

- [ ] **Step 1: Sustituir las dos funciones**

```rust
/// El `DSUClient.ini` que hay que dejarle a Dolphin, con un servidor por
/// móvil de la party. `hosts` son las IPs separadas por comas y en orden de
/// jugador (la primera es el anfitrión). Sale de `dsu::ini`, el mismo sitio
/// del que sale el que el receptor de PC escribe solo.
#[no_mangle]
pub extern "system" fn Java_dev_pepotech_pepomote_net_DsuServer_nativeDsuClientIni<'local>(
    mut env: JNIEnv<'local>,
    _this: JObject<'local>,
    hosts: JString<'local>,
    port: jint,
) -> jstring {
    let hosts: String = match env.get_string(&hosts) {
        Ok(s) => s.into(),
        Err(_) => return JObject::null().into_raw(),
    };
    let hosts: Vec<&str> = hosts.split(',').map(str::trim).filter(|s| !s.is_empty()).collect();
    if hosts.is_empty() {
        return JObject::null().into_raw();
    }
    let port = if (1..=65535).contains(&port) { port as u16 } else { dsu::DSU_PORT };
    match env.new_string(dsu::ini::dsu_client_ini(&hosts, port)) {
        Ok(s) => s.into_raw(),
        Err(_) => JObject::null().into_raw(),
    }
}

/// El `WiimoteNew.ini` con un Mando de Wii emulado por jugador de la party y
/// los sobrantes apagados.
#[no_mangle]
pub extern "system" fn Java_dev_pepotech_pepomote_net_DsuServer_nativeWiimoteIni<'local>(
    env: JNIEnv<'local>,
    _this: JObject<'local>,
    count: jint,
) -> jstring {
    match env.new_string(dsu::ini::wiimote_new_ini(count.max(1) as usize)) {
        Ok(s) => s.into_raw(),
        Err(_) => JObject::null().into_raw(),
    }
}
```

- [ ] **Step 2: Comprobar que compila limpio**

Run: `cd dsu-android && cargo build`
Expected: PASS, cero warnings. Un `mut env` de más es warning, uno de menos es error: `nativeDsuClientIni` necesita `mut`, `nativeWiimoteIni` no.

- [ ] **Step 3: Commit**

```bash
git add dsu-android/src/lib.rs
git commit -m "JNI: los INI se piden con la lista de moviles de la party"
```

---

### Task 4: `DsuServer.kt` expone las firmas nuevas

**Files:**
- Modify: `android/app/src/main/java/dev/pepotech/pepomote/net/DsuServer.kt`

**Interfaces:**
- Consumes: los `native*` de la Task 3
- Produces (los usa la Task 8):
  - `fun dsuClientIni(hosts: List<String>): String`
  - `fun wiimoteIni(players: Int): String`

- [ ] **Step 1: Cambiar las declaraciones y los envoltorios**

```kotlin
    private external fun nativeDsuClientIni(hosts: String, port: Int): String?
    private external fun nativeWiimoteIni(count: Int): String?

    /**
     * El DSUClient.ini con un servidor por móvil de la party, en orden de
     * jugador: el primero es el anfitrión. Los nombres (`PepoMote-P1`…) los
     * pone el lado Rust, que es también quien escribe el WiimoteNew.ini.
     */
    fun dsuClientIni(hosts: List<String>): String =
        if (available && hosts.isNotEmpty()) {
            nativeDsuClientIni(hosts.joinToString(","), PORT) ?: MISSING_LIB
        } else {
            MISSING_LIB
        }

    /** El WiimoteNew.ini con un mando por jugador de la party. */
    fun wiimoteIni(players: Int): String =
        if (available) nativeWiimoteIni(players.coerceAtLeast(1)) ?: MISSING_LIB else MISSING_LIB
```

- [ ] **Step 2: Adaptar las dos llamadas de la pantalla, sin UI nueva todavía**

`DolphinAndroidScreen.kt` llama a las firmas viejas y dejaría el módulo sin
compilar — y entonces los tests de las Tasks 5-7 no podrían ni ejecutarse,
porque Gradle compila todo el módulo antes de correrlos. Mínimo para seguir en
verde (la lista de jugadores llega en la Task 8):

```kotlin
        FileCard(
            name = "DSUClient.ini",
            body = DsuServer.dsuClientIni(listOf(ip ?: "0.0.0.0")),
            context = context
        )
        Spacer(Modifier.height(12.dp))
        FileCard(name = "WiimoteNew.ini", body = DsuServer.wiimoteIni(1), context = context)
```

- [ ] **Step 3: Comprobar que compila y pasa**

Run: `cd android && ./gradlew test`
Expected: PASS. Comportamiento idéntico al de hoy: un jugador, un servidor.

- [ ] **Step 4: Commit**

```bash
git add android/app/src/main/java/dev/pepotech/pepomote/net/DsuServer.kt android/app/src/main/java/dev/pepotech/pepomote/ui/screens/DolphinAndroidScreen.kt
git commit -m "android: los INI se piden con la lista de moviles"
```

---

### Task 5: `PartyRoster` — quién juega y con qué número

Lógica pura, sin red: así se puede probar de verdad. El anfitrión **no** está en el roster (no se une por socket); es siempre P1 y quien genere los INI antepone su IP.

**Files:**
- Create: `android/app/src/main/java/dev/pepotech/pepomote/net/PartyRoster.kt`
- Test: `android/app/src/test/java/dev/pepotech/pepomote/net/PartyRosterTest.kt`

**Interfaces:**
- Produces (los usa la Task 6):
  - `data class PartyPlayer(val player: Int, val name: String, val host: String)`
  - `class PartyRoster(maxPlayers: Int = 4)`
  - `fun join(id: Long, name: String, host: String, nowMs: Long): Int?`
  - `fun ping(id: Long, nowMs: Long)`
  - `fun leave(id: Long)`
  - `fun prune(nowMs: Long, timeoutMs: Long = 10_000): List<Long>`
  - `fun guests(): List<PartyPlayer>`

- [ ] **Step 1: Escribir el test que falla**

```kotlin
package dev.pepotech.pepomote.net

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PartyRosterTest {

    @Test
    fun losInvitadosEmpiezanEnElJugadorDos() {
        val roster = PartyRoster()
        assertEquals(2, roster.join(1L, "Pixel", "10.0.0.2", 0L))
        assertEquals(3, roster.join(2L, "Moto", "10.0.0.3", 0L))
        assertEquals(listOf(2, 3), roster.guests().map { it.player })
    }

    @Test
    fun elHuecoDelQueSeVaLoOcupaElSiguiente() {
        val roster = PartyRoster()
        roster.join(1L, "Pixel", "10.0.0.2", 0L)
        roster.join(2L, "Moto", "10.0.0.3", 0L)
        roster.leave(1L)
        assertEquals(2, roster.join(3L, "Xiaomi", "10.0.0.4", 0L))
    }

    @Test
    fun elQuintoNoCabe() {
        val roster = PartyRoster()
        roster.join(1L, "a", "10.0.0.2", 0L)
        roster.join(2L, "b", "10.0.0.3", 0L)
        roster.join(3L, "c", "10.0.0.4", 0L)
        assertNull("solo caben 4 contando al anfitrion", roster.join(4L, "d", "10.0.0.5", 0L))
    }

    @Test
    fun elQueDejaDeDarSenalesSeCae() {
        val roster = PartyRoster()
        roster.join(1L, "Pixel", "10.0.0.2", 0L)
        roster.join(2L, "Moto", "10.0.0.3", 0L)
        roster.ping(2L, 9_000L)
        assertEquals(listOf(1L), roster.prune(12_000L))
        assertEquals(listOf(3), roster.guests().map { it.player })
    }
}
```

- [ ] **Step 2: Ver que falla**

Run: `cd android && ./gradlew testDebugUnitTest --tests "dev.pepotech.pepomote.net.PartyRosterTest"`
Expected: FAIL — `Unresolved reference: PartyRoster`.

- [ ] **Step 3: Implementar**

```kotlin
package dev.pepotech.pepomote.net

/** Un móvil de la party: su número de jugador, su nombre y su IP. */
data class PartyPlayer(val player: Int, val name: String, val host: String)

/**
 * Quién está en la party y con qué número. El anfitrión NO está aquí: es
 * siempre el Jugador 1 y quien genera los INI antepone su propia IP. Esto
 * lleva solo a los invitados (2..maxPlayers).
 *
 * No sabe de red a propósito: el transporte es de [PartyHost], y así el
 * reparto de números y la caída por silencio se prueban sin abrir un socket.
 */
class PartyRoster(private val maxPlayers: Int = MAX_PLAYERS) {

    private class Guest(val player: Int, val name: String, val host: String, var seen: Long)

    private val guests = LinkedHashMap<Long, Guest>()

    /** @return el número de jugador, o null si la party está llena. */
    @Synchronized
    fun join(id: Long, name: String, host: String, nowMs: Long): Int? {
        guests.remove(id)
        val taken = guests.values.map { it.player }.toSet()
        val player = (2..maxPlayers).firstOrNull { it !in taken } ?: return null
        guests[id] = Guest(player, name, host, nowMs)
        return player
    }

    @Synchronized
    fun ping(id: Long, nowMs: Long) {
        guests[id]?.seen = nowMs
    }

    @Synchronized
    fun leave(id: Long) {
        guests.remove(id)
    }

    /**
     * Saca a los que llevan demasiado sin dar señales y devuelve sus ids.
     * Un móvil que se va sin cerrar (Wi-Fi apagado, batería) dejaría si no su
     * IP en el archivo, y Dolphin tendría un mando que nunca responde.
     */
    @Synchronized
    fun prune(nowMs: Long, timeoutMs: Long = TIMEOUT_MS): List<Long> {
        val muertos = guests.filterValues { nowMs - it.seen > timeoutMs }.keys.toList()
        muertos.forEach { guests.remove(it) }
        return muertos
    }

    /** Los invitados, ordenados por número de jugador. */
    @Synchronized
    fun guests(): List<PartyPlayer> =
        guests.values.sortedBy { it.player }.map { PartyPlayer(it.player, it.name, it.host) }

    companion object {
        const val MAX_PLAYERS = 4
        const val TIMEOUT_MS = 10_000L
    }
}
```

- [ ] **Step 4: Ver que pasa**

Run: `cd android && ./gradlew testDebugUnitTest --tests "dev.pepotech.pepomote.net.PartyRosterTest"`
Expected: PASS, 4 tests.

- [ ] **Step 5: Commit**

```bash
git add android/app/src/main/java/dev/pepotech/pepomote/net/PartyRoster.kt android/app/src/test/java/dev/pepotech/pepomote/net/PartyRosterTest.kt
git commit -m "party: reparto de numeros de jugador y caida por silencio"
```

---

### Task 6: `Party.kt` — el canal TCP entre móviles

**Files:**
- Create: `android/app/src/main/java/dev/pepotech/pepomote/net/Party.kt`
- Test: `android/app/src/test/java/dev/pepotech/pepomote/net/PartyTest.kt`

**Interfaces:**
- Consumes: `PartyRoster`, `PartyPlayer` de la Task 5
- Produces (los usan las Tasks 7 y 8):
  - `object Party { const val PORT = 26761 }`
  - `class PartyHost(port: Int = Party.PORT)` con `fun start()`, `fun stop()`, `val guests: StateFlow<List<PartyPlayer>>`
  - `class PartyGuest(host: String, port: Int)` con `fun start()`, `fun stop()`, `val player: StateFlow<Int?>`

- [ ] **Step 1: Escribir el test que falla**

Usa sockets de verdad en localhost: los unit tests de Gradle corren en JVM.

```kotlin
package dev.pepotech.pepomote.net

import org.junit.Assert.assertEquals
import org.junit.Test
import java.net.ServerSocket

class PartyTest {

    private fun puertoLibre(): Int = ServerSocket(0).use { it.localPort }

    @Test
    fun elInvitadoSeUneYElAnfitrionLoVe() {
        val port = puertoLibre()
        val anfitrion = PartyHost(port)
        anfitrion.start()
        try {
            val invitado = PartyGuest("127.0.0.1", port)
            invitado.start()
            try {
                esperaA { invitado.player.value == 2 }
                esperaA { anfitrion.guests.value.size == 1 }
                assertEquals(2, anfitrion.guests.value.first().player)
                assertEquals("127.0.0.1", anfitrion.guests.value.first().host)
            } finally {
                invitado.stop()
            }
            esperaA { anfitrion.guests.value.isEmpty() }
        } finally {
            anfitrion.stop()
        }
    }

    private fun esperaA(limiteMs: Long = 3_000, cond: () -> Boolean) {
        val fin = System.currentTimeMillis() + limiteMs
        while (System.currentTimeMillis() < fin) {
            if (cond()) return
            Thread.sleep(20)
        }
        throw AssertionError("no se cumplio en $limiteMs ms")
    }
}
```

- [ ] **Step 2: Ver que falla**

Run: `cd android && ./gradlew testDebugUnitTest --tests "dev.pepotech.pepomote.net.PartyTest"`
Expected: FAIL — `Unresolved reference: PartyHost`.

- [ ] **Step 3: Implementar**

```kotlin
package dev.pepotech.pepomote.net

import android.os.Build
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.json.JSONObject
import java.io.BufferedReader
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import kotlin.concurrent.thread

/**
 * La «party»: varios móviles mandando al MISMO Dolphin. Cada uno sigue
 * sirviendo su propio DSU en su IP; esto solo sirve para que el anfitrión
 * sepa las IPs de los demás y pueda escribir los dos INI con todos dentro.
 * El tráfico de juego NO pasa por aquí.
 *
 * Una línea JSON por mensaje, como el canal de control de PMP.
 */
object Party {
    /** TCP. El 26760/UDP es el DSU; aquí el móvil solo era cliente del PC. */
    const val PORT = 26761
    const val PING_MS = 3_000L

    fun joinMessage(name: String): String =
        JSONObject().put("m", "join").put("v", 1).put("name", name).toString()

    fun pingMessage(): String = JSONObject().put("m", "ping").toString()

    fun deviceName(): String = "${Build.MANUFACTURER} ${Build.MODEL}".trim()
}

/**
 * El anfitrión (Jugador 1): acepta a quien se una, lleva el roster y lo
 * publica. La IP de cada invitado se saca del PROPIO socket, nunca de lo que
 * diga el invitado: es el dato que acaba dentro del INI.
 */
class PartyHost(private val port: Int = Party.PORT) {

    private val roster = PartyRoster()
    private val _guests = MutableStateFlow<List<PartyPlayer>>(emptyList())
    val guests: StateFlow<List<PartyPlayer>> = _guests

    private var server: ServerSocket? = null
    @Volatile private var running = false
    private var nextId = 1L

    fun start() {
        if (running) return
        running = true
        val srv = ServerSocket(port)
        server = srv
        thread(isDaemon = true, name = "party-host") {
            while (running) {
                val socket = try {
                    srv.accept()
                } catch (e: Exception) {
                    break
                }
                val id = synchronized(this) { nextId++ }
                thread(isDaemon = true, name = "party-guest-$id") { atiende(id, socket) }
            }
        }
        thread(isDaemon = true, name = "party-prune") {
            while (running) {
                Thread.sleep(1_000)
                if (roster.prune(System.currentTimeMillis()).isNotEmpty()) publica()
            }
        }
    }

    private fun atiende(id: Long, socket: Socket) {
        socket.use { s ->
            try {
                val reader = s.getInputStream().bufferedReader()
                val writer = s.getOutputStream().bufferedWriter()
                val primera = reader.readLine() ?: return
                val msg = JSONObject(primera)
                if (msg.optString("m") != "join") return
                val host = (s.inetAddress?.hostAddress) ?: return
                val player = roster.join(id, msg.optString("name", "?"), host, System.currentTimeMillis())
                if (player == null) {
                    writer.write(JSONObject().put("m", "err").put("e", "party_full").toString())
                    writer.write("\n")
                    writer.flush()
                    return
                }
                writer.write(JSONObject().put("m", "joined").put("player", player).toString())
                writer.write("\n")
                writer.flush()
                publica()
                bucle(id, reader)
            } catch (e: Exception) {
                // se cayó: sale del roster igual que si se hubiera ido
            } finally {
                roster.leave(id)
                publica()
            }
        }
    }

    private fun bucle(id: Long, reader: BufferedReader) {
        while (running) {
            reader.readLine() ?: return
            roster.ping(id, System.currentTimeMillis())
        }
    }

    private fun publica() {
        _guests.value = roster.guests()
    }

    fun stop() {
        running = false
        try {
            server?.close()
        } catch (e: Exception) {
        }
        server = null
        _guests.value = emptyList()
    }
}

/** El invitado: se une al anfitrión y le da señales de vida. */
class PartyGuest(private val host: String, private val port: Int = Party.PORT) {

    private val _player = MutableStateFlow<Int?>(null)
    val player: StateFlow<Int?> = _player

    private var socket: Socket? = null
    @Volatile private var running = false

    fun start() {
        if (running) return
        running = true
        thread(isDaemon = true, name = "party-guest") {
            try {
                val s = Socket()
                s.connect(InetSocketAddress(host, port), 4_000)
                socket = s
                val reader = s.getInputStream().bufferedReader()
                val writer = s.getOutputStream().bufferedWriter()
                writer.write(Party.joinMessage(Party.deviceName()))
                writer.write("\n")
                writer.flush()
                val respuesta = JSONObject(reader.readLine() ?: return@thread)
                if (respuesta.optString("m") != "joined") return@thread
                _player.value = respuesta.optInt("player")
                while (running) {
                    Thread.sleep(Party.PING_MS)
                    writer.write(Party.pingMessage())
                    writer.write("\n")
                    writer.flush()
                }
            } catch (e: Exception) {
                // sin anfitrión: la pantalla ofrece escanear otra vez
            } finally {
                _player.value = null
            }
        }
    }

    fun stop() {
        running = false
        try {
            socket?.close()
        } catch (e: Exception) {
        }
        socket = null
        _player.value = null
    }
}
```

- [ ] **Step 4: Ver que pasa**

Run: `cd android && ./gradlew testDebugUnitTest --tests "dev.pepotech.pepomote.net.PartyTest"`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add android/app/src/main/java/dev/pepotech/pepomote/net/Party.kt android/app/src/test/java/dev/pepotech/pepomote/net/PartyTest.kt
git commit -m "party: canal TCP entre moviles, la IP sale del socket"
```

---

### Task 7: `PartyStore` — el QR y volver sin reescanear

**Files:**
- Create: `android/app/src/main/java/dev/pepotech/pepomote/net/PartyStore.kt`
- Test: `android/app/src/test/java/dev/pepotech/pepomote/net/PartyStoreTest.kt`

**Interfaces:**
- Consumes: `Party.PORT` de la Task 6
- Produces (los usa la Task 8):
  - `data class PartyInvite(val host: String, val port: Int, val name: String)`
  - `fun PartyStore.inviteUrl(host: String, name: String): String`
  - `fun PartyStore.parseInviteUrl(url: String): PartyInvite?`
  - `fun PartyStore.remember(context: Context, invite: PartyInvite)`
  - `fun PartyStore.last(context: Context): PartyInvite?`

- [ ] **Step 1: Escribir el test que falla**

```kotlin
package dev.pepotech.pepomote.net

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PartyStoreTest {

    @Test
    fun laUrlDeInvitacionSeLeeDeVuelta() {
        val url = PartyStore.inviteUrl("192.168.1.42", "Galaxy S21")
        val invite = PartyStore.parseInviteUrl(url)!!
        assertEquals("192.168.1.42", invite.host)
        assertEquals(Party.PORT, invite.port)
        assertEquals("Galaxy S21", invite.name)
    }

    @Test
    fun elQrDeEmparejarConElPcNoEsUnaParty() {
        assertNull(PartyStore.parseInviteUrl("pepomote://pair?v=1&host=10.0.0.1&port=26761&t=ab"))
    }

    @Test
    fun unaUrlSinHostNoVale() {
        assertNull(PartyStore.parseInviteUrl("pepomote://party?v=1&port=26761"))
        assertNull(PartyStore.parseInviteUrl("cualquier cosa"))
    }
}
```

- [ ] **Step 2: Ver que falla**

Run: `cd android && ./gradlew testDebugUnitTest --tests "dev.pepotech.pepomote.net.PartyStoreTest"`
Expected: FAIL — `Unresolved reference: PartyStore`.

- [ ] **Step 3: Implementar**

`Uri.parse` es de Android y no corre en un unit test de JVM, así que el parseo se hace a mano sobre la cadena (como hace `Discovery` con sus datagramas).

```kotlin
package dev.pepotech.pepomote.net

import android.content.Context
import java.net.URLDecoder
import java.net.URLEncoder

/** Una party a la que unirse: dónde está el anfitrión y cómo se llama. */
data class PartyInvite(val host: String, val port: Int, val name: String)

/**
 * El QR del anfitrión y la party recordada. El esquema es el mismo que el de
 * emparejar con el PC (`PROTOCOL.md` §1), cambiando `pair` por `party`:
 *
 *     pepomote://party?v=1&host=<ip>&port=26761&name=<url-encoded>
 *
 * Se parsea a mano y no con `Uri.parse` para que el test de JVM pueda
 * probarlo sin emulador.
 */
object PartyStore {

    private const val PREFS = "party"
    private const val KEY_HOST = "host"
    private const val KEY_PORT = "port"
    private const val KEY_NAME = "name"
    private const val PREFIX = "pepomote://party?"

    fun inviteUrl(host: String, name: String): String =
        "${PREFIX}v=1&host=$host&port=${Party.PORT}&name=${URLEncoder.encode(name, "UTF-8")}"

    fun parseInviteUrl(url: String): PartyInvite? {
        if (!url.startsWith(PREFIX)) return null
        val params = url.removePrefix(PREFIX).split('&').mapNotNull {
            val (k, v) = it.split('=', limit = 2).takeIf { p -> p.size == 2 } ?: return@mapNotNull null
            k to v
        }.toMap()
        val host = params["host"]?.takeIf { it.isNotBlank() } ?: return null
        val port = params["port"]?.toIntOrNull() ?: Party.PORT
        val name = params["name"]?.let { URLDecoder.decode(it, "UTF-8") } ?: ""
        return PartyInvite(host, port, name)
    }

    fun remember(context: Context, invite: PartyInvite) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString(KEY_HOST, invite.host)
            .putInt(KEY_PORT, invite.port)
            .putString(KEY_NAME, invite.name)
            .apply()
    }

    /** La última party, para reconectar sin volver a escanear. */
    fun last(context: Context): PartyInvite? {
        val p = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val host = p.getString(KEY_HOST, null) ?: return null
        return PartyInvite(host, p.getInt(KEY_PORT, Party.PORT), p.getString(KEY_NAME, "") ?: "")
    }
}
```

- [ ] **Step 4: Ver que pasa**

Run: `cd android && ./gradlew testDebugUnitTest --tests "dev.pepotech.pepomote.net.PartyStoreTest"`
Expected: PASS, 3 tests.

- [ ] **Step 5: Commit**

```bash
git add android/app/src/main/java/dev/pepotech/pepomote/net/PartyStore.kt android/app/src/test/java/dev/pepotech/pepomote/net/PartyStoreTest.kt
git commit -m "party: URL de invitacion y party recordada"
```

---

### Task 8: La pantalla — QR, lista de jugadores y los INI con todos

Cierra el círculo: es la primera tarea tras la 4 en la que la app vuelve a compilar.

**Files:**
- Modify: `android/app/src/main/java/dev/pepotech/pepomote/service/DsuState.kt`
- Modify: `android/app/src/main/java/dev/pepotech/pepomote/service/DsuForegroundService.kt`
- Modify: `android/app/src/main/java/dev/pepotech/pepomote/ui/screens/DolphinAndroidScreen.kt`
- Modify: `android/app/src/main/java/dev/pepotech/pepomote/MainActivity.kt:285-305`
- Modify: `android/app/src/main/res/values/strings.xml`, `android/app/src/main/res/values-en/strings.xml`

**Interfaces:**
- Consumes: `DsuServer.dsuClientIni(List<String>)`, `DsuServer.wiimoteIni(Int)` (Task 4); `PartyHost`, `PartyGuest`, `Party` (Task 6); `PartyStore`, `PartyInvite` (Task 7)

- [ ] **Step 1: Estado de la party en `DsuState`**

Añadir a `DsuState`:

```kotlin
        /** Los invitados de la party, vacío si se juega solo. */
        val guests: MutableStateFlow<List<PartyPlayer>> = MutableStateFlow(emptyList())

        /** Si este móvil se unió a la party de otro: su número de jugador. */
        val joinedAs: MutableStateFlow<Int?> = MutableStateFlow(null)
```

(importa `dev.pepotech.pepomote.net.PartyPlayer` y `kotlinx.coroutines.flow.MutableStateFlow`)

- [ ] **Step 2: El servicio alberga la party**

En `DsuForegroundService`, junto a `motion`:

```kotlin
    private var partyHost: PartyHost? = null
    private var partyGuest: PartyGuest? = null
```

En `onStartCommand`, tras arrancar el DSU, arrancar el anfitrión y, si había una party recordada, unirse a ella:

```kotlin
        partyHost = PartyHost().also { host ->
            try {
                host.start()
            } catch (e: Exception) {
                // el puerto de party ocupado no impide jugar solo
            }
        }
        PartyStore.last(this)?.let { invite ->
            partyGuest = PartyGuest(invite.host, invite.port).also { it.start() }
        }
```

En `onDestroy`, antes de `DsuState.publish(DsuState.Ui.Off)`:

```kotlin
        partyHost?.stop()
        partyHost = null
        partyGuest?.stop()
        partyGuest = null
```

Y en `startPolling`, publicar también los flujos de la party:

```kotlin
                DsuState.guests.value = partyHost?.guests?.value ?: emptyList()
                DsuState.joinedAs.value = partyGuest?.player?.value
```

- [ ] **Step 3: La pantalla**

En `DolphinAndroidScreen`, tras la tarjeta de estado, añadir la lista y el QR, y cambiar las dos `FileCard` para que usen la lista entera:

```kotlin
    val guests by DsuState.guests.collectAsState()
    val joinedAs by DsuState.joinedAs.collectAsState()
    val hosts = listOf(ip ?: "0.0.0.0") + guests.map { it.host }
```

```kotlin
        // --- la party: quién juega
        if (joinedAs == null) {
            Spacer(Modifier.height(16.dp))
            InfoCard {
                Text(stringResource(R.string.party_title), style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.height(8.dp))
                Text(stringResource(R.string.party_me, 1), style = MaterialTheme.typography.bodyMedium)
                guests.forEach { g ->
                    Text(
                        stringResource(R.string.party_guest, g.player, g.name),
                        style = MaterialTheme.typography.bodyMedium
                    )
                }
                if (ip != null && guests.size < PartyRoster.MAX_PLAYERS - 1) {
                    Spacer(Modifier.height(12.dp))
                    QrCode(PartyStore.inviteUrl(ip, Party.deviceName()))
                    Spacer(Modifier.height(6.dp))
                    Text(stringResource(R.string.party_scan_me), style = MaterialTheme.typography.bodySmall)
                }
            }
        } else {
            Spacer(Modifier.height(16.dp))
            InfoCard {
                Text(
                    stringResource(R.string.party_joined, joinedAs ?: 0),
                    style = MaterialTheme.typography.titleMedium
                )
            }
        }
```

Y las tarjetas de archivo, que solo salen en el anfitrión:

```kotlin
        if (joinedAs == null) {
            FileCard(name = "DSUClient.ini", body = DsuServer.dsuClientIni(hosts), context = context)
            Spacer(Modifier.height(12.dp))
            FileCard(name = "WiimoteNew.ini", body = DsuServer.wiimoteIni(hosts.size), context = context)
        }
```

El QR, con zxing (ya está por el escáner):

```kotlin
/** El QR que escanean los demás móviles para unirse a esta party. */
@Composable
private fun QrCode(text: String, size: Int = 480) {
    val bitmap = remember(text) {
        val matrix = com.google.zxing.qrcode.QRCodeWriter()
            .encode(text, com.google.zxing.BarcodeFormat.QR_CODE, size, size)
        android.graphics.Bitmap.createBitmap(size, size, android.graphics.Bitmap.Config.ARGB_8888).apply {
            for (x in 0 until size) {
                for (y in 0 until size) {
                    setPixel(x, y, if (matrix[x, y]) android.graphics.Color.BLACK else android.graphics.Color.WHITE)
                }
            }
        }
    }
    Image(bitmap.asImageBitmap(), contentDescription = null, modifier = Modifier.size(220.dp))
}
```

(imports: `androidx.compose.foundation.Image`, `androidx.compose.ui.graphics.asImageBitmap`, `androidx.compose.runtime.remember`, `androidx.compose.runtime.collectAsState`, `androidx.compose.runtime.getValue`, y los de `net`)

- [ ] **Step 4: Aceptar el QR de party en `MainActivity`**

En `onQrScanned` (línea ~285), antes de intentar el emparejamiento con PC:

```kotlin
        PartyStore.parseInviteUrl(contents)?.let { invite ->
            PartyStore.remember(this, invite)
            DsuForegroundService.stop(this)
            DsuForegroundService.start(this)
            currentScreen = Screen.DolphinAndroid
            return
        }
```

- [ ] **Step 5: Textos, en los dos idiomas**

`res/values/strings.xml`:

```xml
    <string name="party_title">Quién juega</string>
    <string name="party_me">Jugador %1$d · este móvil</string>
    <string name="party_guest">Jugador %1$d · %2$s</string>
    <string name="party_scan_me">Que los demás escaneen este código para unirse</string>
    <string name="party_joined">Unido a la party como Jugador %1$d</string>
```

`res/values-en/strings.xml`:

```xml
    <string name="party_title">Who's playing</string>
    <string name="party_me">Player %1$d · this phone</string>
    <string name="party_guest">Player %1$d · %2$s</string>
    <string name="party_scan_me">Have the others scan this code to join</string>
    <string name="party_joined">Joined the party as Player %1$d</string>
```

- [ ] **Step 6: Compilar y pasar todo**

Run: `cd android && ./gradlew test assembleDebug`
Expected: PASS, incluido `StringsParityTest` (mismas claves y mismos `%1$d`/`%2$s` en los dos idiomas).

- [ ] **Step 7: Commit**

```bash
git add android/app/src/main/java/dev/pepotech/pepomote/ android/app/src/main/res/values/strings.xml android/app/src/main/res/values-en/strings.xml
git commit -m "party: QR del anfitrion, lista de jugadores y los INI con todos"
```

---

### Task 9: Documentación

**Files:**
- Modify: `docs/DOLPHIN-ANDROID.md`
- Modify: `protocol/PROTOCOL.md`
- Modify: `CLAUDE.md`

- [ ] **Step 1: `docs/DOLPHIN-ANDROID.md`**

Sustituir «Hacen falta **dos dispositivos**» por el caso de varios mandos, y añadir una sección «Varios mandos» que explique: el primer móvil muestra el QR, los demás lo escanean, el anfitrión genera los dos INI con todos, todos los móviles tienen que estar sirviendo **antes** de abrir Dolphin, y si cambia una IP hay que regenerar (mitigación: hotspot del anfitrión). En «Qué funciona y qué no», quitar «jugadores 2-4» de la lista de lo que no hay y dejar solo Nunchuk y modo Wii U.

- [ ] **Step 2: `protocol/PROTOCOL.md`**

Añadir una sección para el canal de party: puerto 26761/TCP, el esquema `pepomote://party?v=1&host=…&port=…&name=…`, los cuatro mensajes (`join`, `joined`, `err party_full`, `ping`), que la IP la toma el anfitrión del socket, el latido de 3 s con caída a los 10 s, y que no hay autenticación por decisión consciente.

- [ ] **Step 3: `CLAUDE.md`**

En el párrafo de «Dolphin en Android», mencionar que admite hasta cuatro móviles y que el reparto vive en `net/PartyRoster.kt` y `net/Party.kt`.

- [ ] **Step 4: Commit**

```bash
git add docs/DOLPHIN-ANDROID.md protocol/PROTOCOL.md CLAUDE.md
git commit -m "docs: la party de varios mandos en Dolphin en Android"
```

---

### Task 10: Prueba con hardware

No es automatizable: hacen falta dos móviles y un Dolphin.

- [ ] **Step 1:** Instalar el APK en los dos móviles, abrir «Dolphin en Android» en ambos y pulsar «Empezar a mandar».
- [ ] **Step 2:** En el segundo, escanear el QR del primero. Comprobar que el primero lista «Jugador 2» y el segundo dice «Unido como Jugador 2».
- [ ] **Step 3:** Copiar los dos INI del anfitrión al dispositivo de Dolphin, **con Dolphin cerrado**.
- [ ] **Step 4:** Abrir Dolphin. En Mando de Wii 1 y 2 → Emulado, tienen que aparecer `DSUClient/0/PepoMote-P1` y `DSUClient/0/PepoMote-P2`.
- [ ] **Step 5:** Comprobar que cada móvil mueve su propio mando y que al apagar el Wi-Fi de uno el otro sigue jugando.
- [ ] **Step 6:** Si un mando no aparece: `python desktop/e2e/dsu_probe.py <ip de ese móvil>` para saber si falla su servidor o la configuración de Dolphin.
