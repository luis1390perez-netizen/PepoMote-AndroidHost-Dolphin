//! Lo que hay que escribirle a Dolphin para que lea de este servidor DSU.
//!
//! Vive aquí y no en el receptor porque hay dos sitios que lo necesitan y
//! tienen que decir EXACTAMENTE lo mismo: el PC, que escribe estos INI en la
//! carpeta de Dolphin él solo (`desktop/src/dolphin.rs`), y Android, que no
//! puede —la carpeta de Dolphin para Android es almacenamiento privado de la
//! app— y se los enseña al usuario para que los coloque a mano.

use crate::MAX_PLAYERS;

/// Mapeo validado contra Dolphin real (h3): botones por bytes analógicos,
/// cruceta "Pad N/S/W/E", IMU completo y recentrado por botón Touch.
/// {DEV} = device DSU completo ya montado por quien llama.
///
/// El grupo del puntero por IMU se llama `IMUIR` y NO `IMUPointer` (en
/// Dolphin la clase es IMUCursor pero el nombre que va al INI es "IMUIR":
/// WiimoteEmu.cpp). Con el nombre equivocado Dolphin no lee nada y el grupo
/// se queda en su valor por defecto, que en Android es APAGADO
/// (IMUCursor.cpp lo pone Disabled bajo `#ifdef ANDROID`, Enabled fuera):
/// en el PC no se notaba y en «Dolphin en Android» dejaba el puntero muerto.
/// Sus únicos ajustes son `Total Yaw` y `Accelerometer Influence`; no hay
/// `Total Pitch`.
const MAPPING: &str = "Device = {DEV}
Source = 1
Buttons/A = Cross
Buttons/B = Circle
Buttons/1 = Square
Buttons/2 = Triangle
Buttons/- = Share
Buttons/+ = Options
Buttons/Home = PS
D-Pad/Up = `Pad N`
D-Pad/Down = `Pad S`
D-Pad/Left = `Pad W`
D-Pad/Right = `Pad E`
IMUAccelerometer/Up = `Accel Up`
IMUAccelerometer/Down = `Accel Down`
IMUAccelerometer/Left = `Accel Left`
IMUAccelerometer/Right = `Accel Right`
IMUAccelerometer/Forward = `Accel Forward`
IMUAccelerometer/Backward = `Accel Backward`
IMUGyroscope/Pitch Up = `Gyro Pitch Up`
IMUGyroscope/Pitch Down = `Gyro Pitch Down`
IMUGyroscope/Roll Left = `Gyro Roll Left`
IMUGyroscope/Roll Right = `Gyro Roll Right`
IMUGyroscope/Yaw Left = `Gyro Yaw Left`
IMUGyroscope/Yaw Right = `Gyro Yaw Right`
IMUGyroscope/Dead Zone = 3.
IMUIR/Enabled = True
IMUIR/Recenter = `Touch Button`
IMUIR/Total Yaw = 25.000000000000000
Options/Battery = `Battery`
";

/// Nunchuk emulado alimentado por el pad DSU del OTRO móvil ({NDEV} = su
/// slot): stick, C/Z (Cross/Circle de ese pad) y su acelerómetro real
/// (Dolphin lo usa tal cual para agitar/inclinar). Ver protocol/DSU.md.
const NUNCHUK_MAPPING: &str = "Extension = Nunchuk
Nunchuk/Buttons/C = `DSUClient/{NDEV}/PepoMote:Cross`
Nunchuk/Buttons/Z = `DSUClient/{NDEV}/PepoMote:Circle`
Nunchuk/Stick/Up = `DSUClient/{NDEV}/PepoMote:Left Y+`
Nunchuk/Stick/Down = `DSUClient/{NDEV}/PepoMote:Left Y-`
Nunchuk/Stick/Left = `DSUClient/{NDEV}/PepoMote:Left X-`
Nunchuk/Stick/Right = `DSUClient/{NDEV}/PepoMote:Left X+`
Nunchuk/IMUAccelerometer/Up = `DSUClient/{NDEV}/PepoMote:Accel Up`
Nunchuk/IMUAccelerometer/Down = `DSUClient/{NDEV}/PepoMote:Accel Down`
Nunchuk/IMUAccelerometer/Left = `DSUClient/{NDEV}/PepoMote:Accel Left`
Nunchuk/IMUAccelerometer/Right = `DSUClient/{NDEV}/PepoMote:Accel Right`
Nunchuk/IMUAccelerometer/Forward = `DSUClient/{NDEV}/PepoMote:Accel Forward`
Nunchuk/IMUAccelerometer/Backward = `DSUClient/{NDEV}/PepoMote:Accel Backward`
";

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

/// Las líneas del Nunchuk emulado que lee del slot DSU dado (el del OTRO
/// móvil). Sin esto, el Wiimote va con `Extension = None`.
pub fn nunchuk_mapping(slot: u8) -> String {
    NUNCHUK_MAPPING.replace("{NDEV}", &slot.to_string())
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

#[cfg(test)]
mod tests {
    use super::*;

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

    #[test]
    fn wiimote_new_ini_enciende_uno_y_apaga_los_demas() {
        let ini = wiimote_new_ini(1);
        assert!(ini.starts_with("[Wiimote1]\nDevice = DSUClient/0/PepoMote-P1\nSource = 1\n"));
        assert!(ini.contains("IMUIR/Recenter = `Touch Button`\n"));
        assert!(ini.contains("IMUIR/Enabled = True\n"));
        // Dolphin llama IMUIR al grupo; con IMUPointer no lee nada y en
        // Android el grupo nace apagado (IMUCursor.cpp, #ifdef ANDROID)
        assert!(!ini.contains("IMUPointer"), "IMUPointer no existe en Dolphin");
        // IMUCursor solo tiene Total Yaw y Accelerometer Influence
        assert!(!ini.contains("Total Pitch"), "Total Pitch no existe en Dolphin");
        assert!(ini.contains("Extension = None\n"));
        for n in 2..=MAX_PLAYERS {
            assert!(ini.contains(&format!("[Wiimote{n}]\nSource = 0\n")), "falta Wiimote{n}");
        }
        assert_eq!(ini.matches("Source = 1").count(), 1, "solo un mando emulado");
    }
}
