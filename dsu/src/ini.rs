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
/// {DEV} = índice de pad del cliente DSU (slot).
///
/// El grupo del puntero por IMU se llama `IMUIR` y NO `IMUPointer` (en
/// Dolphin la clase es IMUCursor pero el nombre que va al INI es "IMUIR":
/// WiimoteEmu.cpp). Con el nombre equivocado Dolphin no lee nada y el grupo
/// se queda en su valor por defecto, que en Android es APAGADO
/// (IMUCursor.cpp lo pone Disabled bajo `#ifdef ANDROID`, Enabled fuera):
/// en el PC no se notaba y en «Dolphin en Android» dejaba el puntero muerto.
/// Sus únicos ajustes son `Total Yaw` y `Accelerometer Influence`; no hay
/// `Total Pitch`.
const MAPPING: &str = "Device = DSUClient/{DEV}/PepoMote
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

/// Cuerpo de `[WiimoteN]` para el móvil que ocupa el slot DSU dado.
pub fn wiimote_mapping(slot: u8) -> String {
    MAPPING.replace("{DEV}", &slot.to_string())
}

/// Las líneas del Nunchuk emulado que lee del slot DSU dado (el del OTRO
/// móvil). Sin esto, el Wiimote va con `Extension = None`.
pub fn nunchuk_mapping(slot: u8) -> String {
    NUNCHUK_MAPPING.replace("{NDEV}", &slot.to_string())
}

/// Una entrada de la clave `Entries` de DSUClient.ini, SIN el `;` final
/// (quien la inserta decide si hay que separarla de las que ya había).
pub fn dsu_entry(host: &str, port: u16) -> String {
    format!("PepoMote:{host}:{port}")
}

/// `DSUClient.ini` entero, para colocarlo tal cual donde no se puede escribir
/// por código (Android). Pisa cualquier otro servidor DSU configurado: es el
/// precio de darlo hecho.
pub fn dsu_client_ini(host: &str, port: u16) -> String {
    format!("[Server]\nEnabled = True\nEntries = {};\n", dsu_entry(host, port))
}

/// `WiimoteNew.ini` entero con UN Mando de Wii emulado (Jugador 1) leyendo
/// del slot DSU dado, y los otros tres apagados: un mando emulado de más
/// sale en pantalla en los juegos y estorba.
pub fn wiimote_new_ini(slot: u8) -> String {
    let mut out = format!("[Wiimote1]\n{}Extension = None\n", wiimote_mapping(slot));
    for n in 2..=MAX_PLAYERS {
        out.push_str(&format!("[Wiimote{n}]\nSource = 0\n"));
    }
    out
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn el_slot_va_en_el_device() {
        assert!(wiimote_mapping(0).starts_with("Device = DSUClient/0/PepoMote\nSource = 1\n"));
        assert!(wiimote_mapping(2).starts_with("Device = DSUClient/2/PepoMote\n"));
        assert!(nunchuk_mapping(3).contains("Nunchuk/Buttons/C = `DSUClient/3/PepoMote:Cross`"));
    }

    #[test]
    fn entrada_dsu_sin_punto_y_coma() {
        assert_eq!(dsu_entry("127.0.0.1", 26760), "PepoMote:127.0.0.1:26760");
        assert_eq!(dsu_entry("192.168.1.42", 26760), "PepoMote:192.168.1.42:26760");
    }

    #[test]
    fn dsu_client_ini_completo() {
        let ini = dsu_client_ini("192.168.1.42", 26760);
        assert_eq!(
            ini,
            "[Server]\nEnabled = True\nEntries = PepoMote:192.168.1.42:26760;\n"
        );
    }

    #[test]
    fn wiimote_new_ini_enciende_uno_y_apaga_los_demas() {
        let ini = wiimote_new_ini(0);
        assert!(ini.starts_with("[Wiimote1]\nDevice = DSUClient/0/PepoMote\nSource = 1\n"));
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
