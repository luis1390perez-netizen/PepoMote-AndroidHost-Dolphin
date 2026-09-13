//! El servidor DSU del receptor: el crate `dsu` (compartido con el emisor de
//! Android) más lo que solo tiene sentido aquí.
//!
//! Aquí Dolphin corre en la MISMA máquina, así que el socket va a
//! `127.0.0.1` y hay que desalojar a quien tenga el puerto (otro servidor
//! DSU como DS4Windows o BetterJoy, o un PepoMote colgado) — nada de eso
//! existe en Android, donde Dolphin está en otro dispositivo y el crate abre
//! `0.0.0.0` él solo.

use crate::state::SharedState;
use std::sync::{Arc, OnceLock};

pub use dsu_server::{Dsu, DsuProfile, MotionSample};

/// El servidor vivo, para que la ventana pueda contar clientes sin que haya
/// que pasearle el `Arc` por media aplicación.
static DSU: OnceLock<Arc<Dsu>> = OnceLock::new();

pub fn port() -> u16 {
    dsu_server::port()
}

pub fn start(shared: SharedState) -> Option<Arc<Dsu>> {
    let port = port();
    // Otro servidor DSU (DS4Windows, BetterJoy, un PepoMote colgado) en el
    // mismo puerto: se le cierra y se recupera el puerto
    let socket = match crate::ports::bind_udp(&shared, "127.0.0.1", port, "DSU") {
        Ok(s) => s,
        Err(e) => {
            shared.lock().unwrap().last_error = Some(e);
            return None;
        }
    };
    match dsu_server::start_with_socket(socket) {
        Ok(dsu) => {
            let _ = DSU.set(dsu.clone());
            Some(dsu)
        }
        Err(e) => {
            shared.lock().unwrap().last_error = Some(e);
            None
        }
    }
}

/// Clientes DSU vivos (Dolphin abre uno por mando emulado), para la ventana.
pub fn client_count() -> usize {
    DSU.get().map(|d| d.client_count()).unwrap_or(0)
}
