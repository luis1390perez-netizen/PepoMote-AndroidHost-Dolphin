# Dolphin en Android — sin PC

Dolphin corre en una tablet (o en otro móvil, o en una consola portátil con
Android) y **este** móvil es el mando de Wii. No hace falta PC, ni el
receptor, ni emparejar nada: el móvil abre él mismo un servidor DSU
(cemuhook) y Dolphin se conecta a él por la Wi-Fi.

```
Móvil (PepoMote)  ──DSU/UDP 26760──>  Tablet con Dolphin
```

Hacen falta **dos dispositivos**: el que juega y el que se sostiene en la
mano. Los dos en la misma Wi-Fi.

## Por qué hay que tocar dos archivos a mano

Dolphin para Android **sí** trae el cliente DSU (está compilado para todas
las plataformas, `CIFACE_USE_DUALSHOCKUDPCLIENT`), pero **su pantalla de
ajustes no lo enseña**: en el Dolphin de escritorio hay un «Alternate Input
Sources» y en el de Android no. Y su carpeta de configuración es
almacenamiento privado de su app, donde ninguna otra app puede escribir desde
Android 11. Así que PepoMote no puede dejarlo hecho como hace en el PC: te da
los dos archivos ya escritos y los colocas tú, una sola vez.

## Paso a paso

1. En el móvil: **Dolphin en Android** → **Empezar a mandar**. Arriba sale la
   IP y el puerto (`26760`).
2. Copia los dos archivos que enseña la pantalla a esta carpeta **del
   dispositivo donde corre Dolphin**:

   ```
   /storage/emulated/0/Android/data/org.dolphinemu.dolphinemu/files/Config/
       DSUClient.ini
       WiimoteNew.ini
   ```

   `Android/data` no se abre con el explorador de archivos normal. Sirven
   ZArchiver o MiXplorer, o `adb push` desde un PC:

   ```
   adb push DSUClient.ini /storage/emulated/0/Android/data/org.dolphinemu.dolphinemu/files/Config/
   adb push WiimoteNew.ini /storage/emulated/0/Android/data/org.dolphinemu.dolphinemu/files/Config/
   ```

   Con Dolphin **cerrado**: al salir reescribe su configuración y se llevaría
   por delante lo que acabas de poner.
3. Abre Dolphin → **Mando** → **Mando de Wii 1** → **Emulado**. En la lista de
   dispositivos tiene que aparecer `DSUClient/0/PepoMote`. Si no aparece,
   cierra y vuelve a abrir Dolphin con el móvil ya mandando.
4. En el móvil, **Abrir el mando**. Apunta, agita, juega.

Cuando Dolphin se engancha, la pantalla del móvil pasa de «Esperando a
Dolphin…» a «Dolphin conectado». Esa es la señal de que los archivos
quedaron bien: si no cambia, el problema está en el paso 2, no en el móvil.

## La IP

La IP del móvil va escrita dentro de `DSUClient.ini`. Si cambia (un reinicio
del router, por ejemplo) Dolphin deja de encontrarlo y hay que volver a
copiar el archivo. Dos maneras de que no cambie nunca:

- reservar la IP del móvil en el router (DHCP estático), o
- **compartir Internet desde el móvil** y conectar a él el dispositivo con
  Dolphin: así el móvil es el router y su IP es siempre la misma.

## Qué funciona y qué no

Este modo emula **un Mando de Wii** con movimiento y botones: acelerómetro,
giroscopio, puntero por IMU (`IMUPointer`, con el recentrado en el botón de
apuntar) y batería. Basta para Wii Sports.

Lo que **no** hay aquí, y sí en el receptor de PC: Nunchuk, jugadores 2-4 y
el modo Wii U (Cemu no existe en Android). Tampoco el modo puntero, que
necesita mover el cursor del sistema operativo — imposible en un Android sin
root.

## Si no va

Antes de pelearse con Dolphin, comprueba que el móvil está sirviendo de
verdad. Desde un PC en la misma red:

```
python desktop/e2e/dsu_probe.py <ip-del-móvil>
```

Es un cliente DSU con la misma forma que el de Dolphin. Si ahí se ven los
`PadData` moviéndose cuando mueves el móvil, el servidor está bien y lo que
falla es la configuración de Dolphin (paso 2 o 3). Si no se ve nada:

- ¿están los dos en la misma Wi-Fi? (algunas redes de invitados aíslan a los
  clientes entre sí: usa el hotspot del móvil);
- ¿está el móvil «mandando» (la pantalla dice «Sirviendo en …»)?

## Para quien toque el código

- El servidor DSU es el **mismo crate** que usa el receptor de PC
  (`dsu/`), compilado para Android con `cargo-ndk` y envuelto en
  `dsu-android/` (JNI). Los mapeos de ejes y el formato del PadData no se
  reimplementan: eso mataría la única fuente de verdad de `dsu/src/mapping.rs`.
- Lo que cruza el JNI es el **paquete INPUT de PMP** que `MotionEngine` ya
  construye para mandar al PC, decodificado al otro lado con `pmp`. Así los
  vectores dorados de `protocol/vectors/` siguen cubriendo este camino.
- Los INI salen de `dsu/src/ini.rs`, compartidos con `desktop/src/dolphin.rs`:
  lo que el PC escribe solo y lo que Android enseña son el mismo texto.
- Sin la toolchain de Rust, Gradle avisa y compila la app sin el `.so`: todo
  lo demás sigue funcionando y solo falla este modo.
