# Dolphin en Android — sin PC

Dolphin corre en una tablet (o en otro móvil, o en una consola portátil con
Android) y **el móvil (o los móviles)** son los mandos de Wii. No hace falta
PC, ni el receptor, ni emparejar nada: cada móvil abre su propio servidor DSU
(cemuhook) y Dolphin se conecta directamente a él por la Wi-Fi.

```
Móvil P1 (PepoMote)  ──DSU/UDP 26760──┐
Móvil P2 (PepoMote)  ──DSU/UDP 26760──┼──>  Tablet con Dolphin
Móvil P3 (PepoMote)  ──DSU/UDP 26760──┘
```

Hacen falta al menos **dos dispositivos**: el que juega y el que se sostiene
en la mano. Hasta cuatro móviles pueden jugar a la vez contra el mismo
Dolphin — es «la party» —, cada uno sirviendo su propio DSU: el tráfico de
juego nunca pasa por otro móvil. Todos en la misma Wi-Fi.

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
   dispositivos tiene que aparecer `DSUClient/0/PepoMote-P1`. Si no aparece,
   cierra y vuelve a abrir Dolphin con el móvil ya mandando.
4. En el móvil, **Abrir el mando**. Apunta, agita, juega.

Cuando Dolphin se engancha, la pantalla del móvil pasa de «Esperando a
Dolphin…» a «Dolphin conectado». Esa es la señal de que los archivos
quedaron bien: si no cambia, el problema está en el paso 2, no en el móvil.

## Varios mandos (la party)

Hasta cuatro móviles contra el mismo Dolphin. Cada uno sirve su **propio**
servidor DSU: el anfitrión no reenvía nada, solo recoge las IPs para poder
escribir los dos archivos con todos dentro.

1. En **todos** los móviles: **Dolphin en Android** → **Empezar a mandar**.
2. El primero (el Jugador 1) enseña un **código QR** bajo la lista de
   jugadores.
3. En cada uno de los demás: **Unirse a la party de otro móvil** y escanear
   ese QR. Al hacerlo aparecen en la lista del anfitrión, y su propia pantalla
   dice «Unido a la party como Jugador 2», 3 o 4.
4. Los dos archivos que enseña **el anfitrión** ya nombran a todos. Cópialos
   al dispositivo de Dolphin como en el paso a paso de arriba, con Dolphin
   **cerrado**.
5. En Dolphin, **Mando de Wii 1**, **2**… → **Emulado**: cada uno tiene que
   ver su `DSUClient/0/PepoMote-P1`, `PepoMote-P2`…

Dos cosas que dan problemas si se hacen al revés:

- **Todos los móviles tienen que estar sirviendo ANTES de abrir Dolphin.** El
  que falte no saldrá en su lista de dispositivos, y no se entera después: hay
  que cerrar Dolphin y volver a abrirlo.
- **Si cambia una IP hay que regenerar y recopiar los archivos**, porque van
  escritas dentro. Ver «La IP» aquí abajo.

El número de jugador va por orden de llegada, y si alguien se va los de detrás
se corren: los archivos y las pantallas se recalculan juntos, así que si
alguien abandona la partida hay que volver a copiarlos.

Para salir de una party, **Salir de la party** en la pantalla del invitado. Si
no, ese móvil se reengancha solo cada vez que vuelve a mandar.

## La IP

La IP del móvil va escrita dentro de `DSUClient.ini`. Si cambia (un reinicio
del router, por ejemplo) Dolphin deja de encontrarlo y hay que volver a
copiar el archivo. Dos maneras de que no cambie nunca:

- reservar la IP del móvil en el router (DHCP estático), o
- **compartir Internet desde el móvil** y conectar a él el dispositivo con
  Dolphin: así el móvil es el router y su IP es siempre la misma.

## Qué funciona y qué no

Este modo emula **Mandos de Wii** con movimiento y botones: acelerómetro,
giroscopio, puntero por IMU (`IMUIR`, con el recentrado en el botón de
apuntar) y batería. Hasta cuatro a la vez. Basta para Wii Sports.

Lo que **no** hay aquí, y sí en el receptor de PC: Nunchuk y el modo Wii U
(Cemu no existe en Android). Tampoco el modo puntero, que necesita mover el
cursor del sistema operativo — imposible en un Android sin root.

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

Con varios mandos, `dsu_probe.py` se apunta a **cada** móvil por separado: así
se sabe cuál de ellos es el que no está sirviendo, sin tocar Dolphin.

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
