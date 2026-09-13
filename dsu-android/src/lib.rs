//! Puente JNI: el servidor DSU (crate `dsu`) dentro de la app de Android.
//!
//! En el PC el móvil manda su telemetría PMP al receptor y el receptor sirve
//! DSU a Dolphin. En Android no hay receptor: el propio móvil sirve DSU, y
//! Dolphin —que corre en OTRO dispositivo— se conecta a él por la Wi-Fi. Por
//! eso aquí se escucha en `0.0.0.0` y no en el loopback.
//!
//! Lo que cruza el puente son los MISMOS bytes del paquete `INPUT` que
//! `MotionEngine` ya construye para mandar por la red (y que los vectores
//! dorados de `protocol/vectors/` prueban en Kotlin y en Rust): aquí se
//! decodifican con `pmp` y se empujan al DSU. Ni un float suelto por el JNI,
//! ni un segundo códec que pueda desviarse del primero.
//!
//! El servidor se arranca UNA vez por proceso y se queda: un hilo aparcado
//! en un `recv` con timeout y un socket UDP. Pararlo y rearrancarlo no
//! aportaría nada (el receptor de PC tampoco lo para nunca) y sí daría un
//! puerto ocupado a medias. Lo que se enciende y se apaga es el envío de
//! muestras, que lo decide el servicio de Android.

use dsu::{Dsu, MotionSample};
use jni::objects::{JByteArray, JObject, JString};
use jni::sys::{jint, jstring};
use jni::JNIEnv;
use std::sync::{Arc, Mutex};

static DSU: Mutex<Option<Arc<Dsu>>> = Mutex::new(None);

/// El servidor vivo, si lo hay (clonado fuera del candado: empujar una
/// muestra hace E/S de red y no debe bloquear a nadie más).
fn current() -> Option<Arc<Dsu>> {
    DSU.lock().ok()?.clone()
}

/// `null` = todo bien. Cualquier otra cosa es el error, ya en texto, para
/// que la pantalla de ajustes lo enseñe tal cual: aquí no hay log de Android
/// y un booleano no diría si el puerto está ocupado o si falta la Wi-Fi.
fn ok() -> jstring {
    JObject::null().into_raw()
}

fn fail(env: &mut JNIEnv, msg: &str) -> jstring {
    match env.new_string(msg) {
        Ok(s) => s.into_raw(),
        // Sin memoria para el propio mensaje de error: que al menos no
        // parezca que fue bien
        Err(_) => JObject::null().into_raw(),
    }
}

/// Arranca el servidor DSU si no estaba ya. Idempotente: llamarlo otra vez
/// con el servidor vivo no vuelve a abrir el puerto.
///
/// Devuelve `null` si está sirviendo, o el error si no pudo abrir el puerto.
#[no_mangle]
pub extern "system" fn Java_dev_pepotech_pepomote_net_DsuServer_nativeStart<'local>(
    mut env: JNIEnv<'local>,
    _this: JObject<'local>,
    bind: JString<'local>,
    port: jint,
) -> jstring {
    let bind: String = match env.get_string(&bind) {
        Ok(s) => s.into(),
        Err(_) => return fail(&mut env, "dirección de escucha no válida"),
    };
    if !(1..=65535).contains(&port) {
        return fail(&mut env, &format!("puerto fuera de rango: {port}"));
    }

    let mut slot = match DSU.lock() {
        Ok(s) => s,
        Err(_) => return fail(&mut env, "el servidor DSU se quedó en mal estado"),
    };
    if slot.is_some() {
        return ok();
    }
    match dsu::start(&bind, port as u16) {
        Ok(d) => {
            *slot = Some(d);
            ok()
        }
        Err(e) => fail(&mut env, &e),
    }
}

/// Un paquete `INPUT` de PMP recién construido por `MotionEngine`: se
/// decodifica y sale como PadData del slot 0 (Jugador 1). Un paquete que no
/// sea un INPUT válido se ignora en silencio, igual que hace el receptor.
#[no_mangle]
pub extern "system" fn Java_dev_pepotech_pepomote_net_DsuServer_nativePush<'local>(
    mut env: JNIEnv<'local>,
    _this: JObject<'local>,
    packet: JByteArray<'local>,
) {
    let Some(dsu) = current() else { return };
    let Ok(bytes) = env.convert_byte_array(&packet) else { return };
    let Some(pmp::Packet::Input(p)) = pmp::parse(&bytes) else { return };
    dsu.push(0, &MotionSample::wii(&p));
}

/// Clientes DSU vivos. Dolphin abre uno por mando emulado, así que en cuanto
/// esto pasa de 0 es que el DSUClient.ini quedó bien puesto: es la única
/// señal que tiene el usuario de que la parte de Dolphin está hecha.
#[no_mangle]
pub extern "system" fn Java_dev_pepotech_pepomote_net_DsuServer_nativeClients<'local>(
    _env: JNIEnv<'local>,
    _this: JObject<'local>,
) -> jint {
    current().map(|d| d.client_count()).unwrap_or(0) as jint
}

/// El `DSUClient.ini` que hay que dejarle a Dolphin, con la IP de ESTE móvil.
/// Sale de `dsu::ini`, el mismo sitio del que sale el que el receptor de PC
/// escribe solo: si un día cambia el mapeo, cambia en los dos a la vez.
#[no_mangle]
pub extern "system" fn Java_dev_pepotech_pepomote_net_DsuServer_nativeDsuClientIni<'local>(
    mut env: JNIEnv<'local>,
    _this: JObject<'local>,
    host: JString<'local>,
    port: jint,
) -> jstring {
    let host: String = match env.get_string(&host) {
        Ok(s) => s.into(),
        Err(_) => return JObject::null().into_raw(),
    };
    let port = if (1..=65535).contains(&port) { port as u16 } else { dsu::DSU_PORT };
    match env.new_string(dsu::ini::dsu_client_ini(&host, port)) {
        Ok(s) => s.into_raw(),
        Err(_) => JObject::null().into_raw(),
    }
}

/// El `WiimoteNew.ini` con el Mando de Wii emulado del Jugador 1 leyendo del
/// slot DSU 0, y los otros tres apagados.
#[no_mangle]
pub extern "system" fn Java_dev_pepotech_pepomote_net_DsuServer_nativeWiimoteIni<'local>(
    mut env: JNIEnv<'local>,
    _this: JObject<'local>,
) -> jstring {
    match env.new_string(dsu::ini::wiimote_new_ini(0)) {
        Ok(s) => s.into_raw(),
        Err(_) => JObject::null().into_raw(),
    }
}
