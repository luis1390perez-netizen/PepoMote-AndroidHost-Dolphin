//! Servidor DSU (cemuhook): lo que Dolphin y Cemu leen como mando con
//! movimiento. Fuente de verdad del formato: protocol/DSU.md.
//!
//! Lo comparten el receptor de PC (que lo alimenta con la telemetría PMP que
//! le llega por la red) y el emisor de Android (que lo alimenta con sus
//! propios sensores y sirve a un Dolphin de otro dispositivo). Por eso aquí
//! no hay nada del receptor: ni estado de la ventana, ni desalojo de puertos,
//! ni red PMP. Solo `std::net` y aritmética.
#![forbid(unsafe_code)]

pub mod ini;
mod mapping;
mod server;

use std::collections::HashMap;
use std::net::{SocketAddr, UdpSocket};
use std::sync::atomic::{AtomicU32, AtomicUsize, Ordering};
use std::sync::{Arc, Mutex};
use std::time::{Duration, Instant};

/// Límite del protocolo DSU: 4 mandos.
pub const MAX_PLAYERS: usize = 4;

pub use server::DSU_PORT;

/// Qué lee el PadData: el perfil Wii (Dolphin: Home→PS, Touch = pulso de
/// recentrado) o el Wii U (Cemu: Home→Touch, gatillos, stick derecho, táctil).
#[derive(Clone, Copy, PartialEq, Eq, Debug, Default)]
pub enum DsuProfile {
    #[default]
    Wii,
    WiiU,
}

/// Muestra de movimiento que la telemetría empuja al DSU.
#[derive(Clone, Copy)]
pub struct MotionSample {
    pub t_us: u64,
    pub accel_ms2: [f32; 3],
    pub gyro_rads: [f32; 3],
    pub buttons: u32,
    pub battery_pct: u8,
    pub recenter_count: u8,
    /// Stick del Nunchuk o izquierdo del GamePad (−127..127, +X derecha,
    /// +Y arriba); 0,0 en un Wiimote.
    pub stick_x: i8,
    pub stick_y: i8,
    /// Stick derecho del GamePad; 0,0 si no hay.
    pub stick_rx: i8,
    pub stick_ry: i8,
    /// Toque en el touchpad DSU (x 0..1920, y 0..942, origen arriba-izquierda):
    /// la pantalla táctil del GamePad o el puntero IR de un Mando Wii en Cemu.
    pub touch: Option<(u16, u16)>,
    pub profile: DsuProfile,
}

impl MotionSample {
    /// Un `INPUT` del móvil tal cual, en el perfil Wii (Dolphin): sin táctil,
    /// que es cosa del GamePad de Wii U. Mismo contenido que la rama Dolphin
    /// del hilo de telemetría del receptor.
    pub fn wii(p: &pmp::InputPacket) -> Self {
        Self {
            t_us: p.t_sensor_us,
            accel_ms2: p.accel,
            gyro_rads: p.gyro,
            buttons: p.buttons,
            battery_pct: p.battery_pct,
            recenter_count: p.recenter_count,
            stick_x: p.stick_x,
            stick_y: p.stick_y,
            stick_rx: p.stick_rx,
            stick_ry: p.stick_ry,
            touch: None,
            profile: DsuProfile::Wii,
        }
    }
}

/// Duración del pulso del botón Touch al recentrar (IMUPointer/Recenter).
const RECENTER_PULSE: Duration = Duration::from_millis(150);

#[derive(Clone, Copy)]
struct Pulse {
    last_recenter: Option<u8>,
    until: Instant,
}

pub type SlotSamples = Arc<Mutex<[Option<(MotionSample, Instant)>; MAX_PLAYERS]>>;

/// Un cliente DSU registrado y QUÉ slots pidió (bitmask, bit i = slot i).
/// Dolphin abre un socket por mando y se suscribe solo a su slot; al recibir
/// no filtra: se queda con el último PadData que entre por ese socket. Si se
/// le mandan todos los slots, cada Wiimote se mueve con todos los móviles.
#[derive(Clone, Copy)]
pub struct Client {
    pub last_seen: Instant,
    pub slots: u8,
}

pub type Clients = Arc<Mutex<HashMap<SocketAddr, Client>>>;

/// Cuántos clientes DSU hay vivos ahora mismo. Lo escribe el hilo del
/// servidor en cada barrido (1/s) y lo lee quien pinte el estado: en el PC la
/// ventana, en Android la pantalla de ajustes. Un atómico en vez del estado
/// compartido del receptor, que aquí no existe.
pub type ClientCount = Arc<AtomicUsize>;

/// El PadData se construye y envía INLINE desde el hilo que trae las muestras
/// (cero colas: la misma latencia que el puntero). Cada jugador va en su
/// slot DSU (0..3) con su propia MAC y su propio pulso de recentrado, y solo
/// a los clientes suscritos a ese slot.
/// El hilo del servidor solo atiende las peticiones de Dolphin (1/s).
pub struct Dsu {
    socket: UdpSocket,
    clients: Clients,
    last: SlotSamples,
    counter: AtomicU32,
    pulse: Mutex<[Pulse; MAX_PLAYERS]>,
    client_count: ClientCount,
}

impl Dsu {
    /// Envía la muestra del slot a todos los clientes DSU registrados.
    pub fn push(&self, slot: u8, sample: &MotionSample) {
        let slot_idx = slot as usize;
        if slot_idx >= MAX_PLAYERS {
            return;
        }

        let touch = {
            let mut pulses = self.pulse.lock().unwrap();
            let p = &mut pulses[slot_idx];
            if p.last_recenter != Some(sample.recenter_count) {
                if p.last_recenter.is_some() {
                    p.until = Instant::now() + RECENTER_PULSE;
                }
                p.last_recenter = Some(sample.recenter_count);
            }
            Instant::now() < p.until
        };

        self.last.lock().unwrap()[slot_idx] = Some((*sample, Instant::now()));

        let clients = self.clients.lock().unwrap();
        let slot_bit = 1u8 << slot;
        if !clients.values().any(|c| c.slots & slot_bit != 0) {
            return;
        }
        let counter = self.counter.fetch_add(1, Ordering::Relaxed).wrapping_add(1);
        let packet = server::pad_data_packet(slot, sample, touch, counter);
        for (addr, c) in clients.iter() {
            if c.slots & slot_bit != 0 {
                let _ = self.socket.send_to(&packet, addr);
            }
        }
    }

    /// Clientes DSU vivos (Dolphin abre uno por mando emulado). Cero =
    /// nadie nos habla: el emulador no está configurado o no llega.
    pub fn client_count(&self) -> usize {
        self.client_count.load(Ordering::Relaxed)
    }
}

/// Puerto del servidor DSU (26760). `PEPOMOTE_DSU_PORT` lo cambia, solo para
/// los e2e en el propio equipo: un receptor de prueba junto al real.
pub fn port() -> u16 {
    std::env::var("PEPOMOTE_DSU_PORT")
        .ok()
        .and_then(|p| p.parse::<u16>().ok())
        .unwrap_or(DSU_PORT)
}

/// Arranca el servidor sobre un socket YA abierto. El receptor de PC lo abre
/// él mismo (desalojando a quien tenga el puerto: DS4Windows, BetterJoy, un
/// PepoMote colgado), así que aquí solo se recibe hecho.
pub fn start_with_socket(socket: UdpSocket) -> Result<Arc<Dsu>, String> {
    let recv_socket = socket.try_clone().map_err(|e| format!("DSU: {e}"))?;

    let idle = Pulse {
        last_recenter: None,
        until: Instant::now(),
    };
    let dsu = Arc::new(Dsu {
        socket,
        clients: Arc::new(Mutex::new(HashMap::new())),
        last: Arc::new(Mutex::new([None; MAX_PLAYERS])),
        counter: AtomicU32::new(0),
        pulse: Mutex::new([idle; MAX_PLAYERS]),
        client_count: Arc::new(AtomicUsize::new(0)),
    });

    {
        let clients = dsu.clients.clone();
        let last = dsu.last.clone();
        let count = dsu.client_count.clone();
        std::thread::Builder::new()
            .name("pmp-dsu".into())
            .spawn(move || server::run(recv_socket, clients, last, count))
            .map_err(|e| format!("DSU: {e}"))?;
    }
    Ok(dsu)
}

/// Abre el socket y arranca. `bind` es `127.0.0.1` cuando Dolphin corre en la
/// misma máquina (el receptor de PC) y `0.0.0.0` cuando Dolphin está en OTRO
/// dispositivo y tiene que poder llegar por la red (el emisor de Android).
pub fn start(bind: &str, port: u16) -> Result<Arc<Dsu>, String> {
    let socket = UdpSocket::bind((bind, port)).map_err(|e| format!("DSU {bind}:{port}: {e}"))?;
    start_with_socket(socket)
}

#[cfg(test)]
mod tests {
    use super::*;

    /// El vector dorado del protocolo, decodificado por `pmp`, tiene que
    /// llegar intacto al PadData: este es el punto donde se juntan los dos
    /// crates, y el único sitio donde se prueba `MotionSample::wii`.
    #[test]
    fn vector_dorado_pmp_hasta_el_paddata() {
        let hex: String = include_str!("../../protocol/vectors/input_motion.hex")
            .chars()
            .filter(|c| !c.is_whitespace())
            .collect();
        let bytes: Vec<u8> = (0..hex.len())
            .step_by(2)
            .map(|i| u8::from_str_radix(&hex[i..i + 2], 16).unwrap())
            .collect();
        let pkt = match pmp::parse(&bytes) {
            Some(pmp::Packet::Input(p)) => p,
            other => panic!("el vector dorado tiene que ser un INPUT, no {other:?}"),
        };

        let sample = MotionSample::wii(&pkt);
        assert_eq!(sample.t_us, pkt.t_sensor_us);
        assert_eq!(sample.buttons, pkt.buttons);
        assert_eq!(sample.profile, DsuProfile::Wii);
        assert!(sample.touch.is_none(), "el perfil Wii no lleva táctil");

        let out = server::pad_data_packet(0, &sample, false, 1);
        assert_eq!(out.len(), 100);
        assert_eq!(&out[0..4], b"DSUS");
        // El timestamp del sensor viaja sin tocar hasta los bytes 68..76
        assert_eq!(
            u64::from_le_bytes(out[68..76].try_into().unwrap()),
            pkt.t_sensor_us
        );
        // Y el gyro sale en °/s con la matriz de signos de mapping.rs
        let (_, gyro) = mapping::to_dsu(pkt.accel, pkt.gyro);
        assert_eq!(f32::from_le_bytes(out[88..92].try_into().unwrap()), gyro[0]);
        assert_eq!(f32::from_le_bytes(out[92..96].try_into().unwrap()), gyro[1]);
        assert_eq!(f32::from_le_bytes(out[96..100].try_into().unwrap()), gyro[2]);
    }
}
