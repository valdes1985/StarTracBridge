# Star Trac Bridge 0.2 beta — corrección de compatibilidad RSC y diagnóstico

**Motivo de la actualización:** el Fenix 6 detectó la publicidad BLE de la versión 0.1, pero mostró «Failed» al intentar emparejar.

Se ha corregido un problema de conformidad del servicio RSC: cuando RSC Feature indica **Total Distance Measurement Supported** (`0x0002`), el servicio también necesita exponer **SC Control Point `0x2A55`**, con escritura e indicaciones. La versión 0.1 omitía esa característica. Esta versión implementa el procedimiento `Set Cumulative Value`, además de presentar los servicios `Device Information (0x180A)` y anuncios con respuesta de exploración que incluye el nombre Bluetooth del teléfono cuando quepa.

La interfaz muestra ahora el **último evento BLE**, el número de enlaces establecidos, las suscripciones a velocidad y las indicaciones del punto de control. Si el reloj sigue mostrando «Failed», envía una captura de esa sección inmediatamente después del intento: podremos distinguir fallo de enlace, de descubrimiento GATT o de suscripción. Verás mensajes similares a:

- `Publicidad BLE RSC activada`, con `enlaces: 0`: esperando Garmin.
- `Garmin: enlace BLE establecido`: el reloj sí ha establecido la conexión física.
- `Garmin: lectura 2a54`: está investigando las funciones del sensor.
- `Garmin: Notify RSC activado`: ya está suscrito a velocidad.
- `Garmin: desconectado (GATT ...)`: se interrumpió el enlace; conserva el código GATT.

**No se ha confirmado todavía que el Fenix 6 acepte un periférico RSC simulado mediante un móvil Android.** Si esta corrección no funciona, habrá que estudiar los registros y considerar emisión ANT+ o un FIT combinado, en vez de seguir modificando a ciegas.

## Actualización desde la versión anterior

En Android Studio, abre la carpeta **de este proyecto v0.2**; espera Gradle Sync; usa `Build → Build APK(s)` y copia `app/build/outputs/apk/debug/app-debug.apk` al móvil. Puedes instalar encima de 0.1 si ambos APK se firmaron con la misma clave `debug`. De lo contrario desinstala 0.1 e instala 0.2. Para probar no hace falta ir al gimnasio: activa **SIMULAR 8 km/h** y añade el sensor en el Garmin.

---

# Star Trac Bridge 0.2 beta — proyecto Android Studio

**Estado:** prototipo experimental con código fuente. No es un APK compilado ni una integración probada con el Garmin Fenix 6. No usar para registrar entrenamientos importantes antes de validar el enlace. No contiene código ni recursos copiados de GymTrakr.

## Qué implementa

- Android (mínimo API 26): cliente BLE GATT que escanea aparatos anunciados como `TREADMILL ####` y permite seleccionar la cinta. El número y la dirección pueden cambiar; **no** se conservan identificadores fijos.
- Conecta al servicio GymConnect `a026ee07-0a7d-4ab3-97fa-f1500f9feb8b`, se suscribe a `a026e01d-0a7d-4ab3-97fa-f1500f9feb8b` activando `CCCD 0x2902` y analiza los paquetes verificados de **19 bytes** del 26 de octubre de 2026. Ignora los de 6 bytes.
- Decodifica velocidad (`offset 7`, `uint16 LE /100`, km/h), distancia (`offset 9`, `uint32 LE`, metros), segundos (`offset 3`, `uint32 LE`). El primer valor de distancia se usa como cero para la sesión del puente.
- Servidor GATT Android + anunciante periférico BLE RSC `0x1814` con medida `0x2A53`, características de funcionalidad `0x2A54`, ubicación `0x2A5D` y el punto de control SC `0x2A55` exigido por la función de distancia acumulada.
- Codifica velocidad RSC como `m/s × 256`, distancia en décimas de metro. **RSC obliga a incluir cadencia; se envía `0`, que no significa una cadencia medida.** Es posible que el Garmin rechace el sensor o interprete erróneamente la cadencia.
- Pausa manual del **puente** (NO controla ni pausa físicamente la cinta o el reloj); corta transmisión de velocidad y congela distancia, restableciendo la referencia al reanudar.
- Velocidad 0 si se interrumpe la recepción más de 4 segundos; sin inventar más metros.
- Servicio en primer plano para no depender de mantener abierta la pantalla. Android debe admitir BLE **central y periférico a la vez**.
- Modo **SIMULADO 8 / 10 km/h** para verificar el enlace con Garmin fuera del gimnasio: **NO guardar ninguna actividad real mientras esté activo**.

## Requisitos para compilar

1. PC con **Android Studio** reciente, JDK 17 (integrado), SDK Android API 35 y conexión a Internet para descargar el Android Gradle Plugin 8.6.1 y Gradle 8.10.2.
2. Abrir la carpeta `StarTracBridge_AndroidStudio` como proyecto Gradle mediante **File → Open**.
3. Esta entrega **no incluye** el binario estándar `gradle-wrapper.jar` porque el entorno no tiene SDK/Gradle ni acceso de red. Si Android Studio solicita un Gradle local, instalar Gradle 8.10.2, configurarlo como distribución local y ejecutar `gradle wrapper --gradle-version 8.10.2` desde la raíz. Después puede usarse el Wrapper normal.
4. Esperar a la sincronización (*Gradle Sync*). Si es necesario, instalar API 35 desde **SDK Manager**.
5. Menú **Build → Build Bundle(s) / APK(s) → Build APK(s)**. APK esperado: `app/build/outputs/apk/debug/app-debug.apk` **después de compilarlo**.
6. Copiar ese APK al móvil y autorizar la instalación. Permitir Bluetooth/Dispositivos cercanos y notificaciones. En Android 11 o anterior, permitir también Ubicación y mantenerla activada para escanear.

No hay ninguna dependencia de terceros en el código Android; solo API Android estándar y Android Gradle Plugin.

## Primera prueba RECOMENDADA (sin cinta)

1. Abre la app, concede permisos y pulsa **PROBAR SIN CINTA · SIMULAR 8 km/h**.
2. Verifica que `RSC` indique que anuncia el servicio (`0x1814`).
3. En el Garmin Fenix 6 abre **MENU → Sensores y accesorios → Añadir nuevo → Buscar todos**; si aparece un *Foot Pod* o sensor BLE, selecciónalo.
4. Antes de grabar una actividad de prueba, observa si aparece **8 km/h**; cambia la demo a **10 km/h** y verifica el cambio.
5. Mira en la app si indica **suscriptores: 1**. Conexión GATT sola no demuestra recepción de las medidas: la suscripción a notificaciones sí es un dato relevante.
6. **NO guardes una actividad con valores simulados**. Detén el puente tras probar.

El Fenix 6 admite sensores externos, pero **no se ha confirmado que acepte el servicio BLE RSC ofrecido por este teléfono como foot pod**. La BLE RSC impone además una cadencia obligatoria (en esta beta: 0); si el reloj prioriza ese campo, no uses el puente en entrenamientos reales. No se ha implementado ANT+.

## Segunda prueba (en gimnasio)

1. Cierra GymTrakr, nRF Connect y QZ: no deberían intentar usar la misma conexión mientras se prueba el puente.
2. Pulsa Bluetooth en la Star Trac para activar la ventana de emparejamiento. En la app toca **Buscar cintas**, selecciona la que corresponde a tu consola.
3. Observa si aparecen valores correctos: **8,00 / 10,00 km/h**, distancia y reloj de la máquina.
4. Busca el sensor en el Fenix. En un **entrenamiento corto y prescindible**, comprueba si la velocidad se actualiza, si la distancia avanza y qué sucede con la cadencia Garmin antes de guardar.
5. Si el Fenix no admite el RSC virtual, el lector GymConnect seguiría siendo válido y podremos añadir más adelante una alternativa de FIT automático o explorar ANT+ con hardware compatible.

## Ejecutar pruebas del protocolo sin Android

Requiere solo Java JDK 17 o posterior:

```sh
mkdir -p build-tests
javac -d build-tests app/src/main/java/com/startrac/bridge/GymConnectDecoder.java app/src/main/java/com/startrac/bridge/RscCodec.java tests/ProtocolTest.java
java -cp build-tests com.startrac.bridge.ProtocolTest
```

Esperado: `PASS: 8 real BLE frames; six-byte filter; RSC units and flags`

## Limitaciones y aspectos por verificar

- **No se pudo compilar ni ejecutar en Android aquí**, ya que no hay Android SDK y no hay acceso a los repositorios de Gradle. Los tests Java puros sí se ejecutaron.
- No se ha probado todavía en hardware: la coexistencia de BLE central y periférico depende del teléfono.
- Los paquetes de 19 bytes están comprobados en la consola probada, no garantizados en todas las versiones OpenHub.
- No hay control remoto de velocidad/inclinación; no existe mando a la cinta.
- Un campo RSC de cadencia 0 podría alterar las métricas de Garmin; no se inventa cadencia.
- No hay almacenamiento de FIT ni sincronización con Garmin Connect en esta beta. El Garmin grabaría los datos solo **si acepta el sensor virtual**.
- El usuario debe elegir manualmente la cinta del gimnasio y volver a conectar tras interrupciones. Una actualización futura puede añadir reintentos configurables.
- No se garantiza que el reloj muestre el teléfono por nombre; se anuncia el UUID del servicio y Android puede mostrar el nombre Bluetooth normal del móvil.

**Aviso:** software experimental, sin garantía de funcionamiento; mantén la alternativa de GymTrakr 1.6.4 para entrenamientos reales hasta comprobar la integración.

## Compilar en GitHub Actions (alternativa sin Android Studio)

Esta carpeta incluye `.github/workflows/build-apk.yml`, un flujo de GitHub Actions para construir el APK en un servidor con Android SDK y subir el resultado como artefacto. Para usarlo, crea un repositorio GitHub, sube los **contenidos** de esta carpeta a la raíz de su rama `main`, abre `Actions → Android debug APK → Run workflow`, espera el resultado y descarga el artefacto `StarTracBridge-debug-apk`. Es necesario que GitHub Actions esté habilitado y que la compilación resulte satisfactoria; **aún no se ha ejecutado este workflow**. La descarga de GitHub será un ZIP con `app-debug.apk` si la compilación finaliza correctamente.
