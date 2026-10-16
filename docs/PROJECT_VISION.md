# Dynamic VT-2D — visión completa del producto

Este documento es la referencia funcional del proyecto. Distingue lo que ya existe, lo que está planeado y lo que necesita una prueba en un teléfono real. No se considera terminada una función solo porque compile.

## 1. Objetivo

Crear una aplicación Android VTuber 2D que permita usar un avatar propio sobre otras aplicaciones, reaccionar a la voz y al rostro, controlar expresiones desde un menú discreto y grabar videos donde el avatar se vea bien sin mostrar los controles.

## 2. Experiencia principal

1. Al abrir la app aparece un estudio para importar y configurar el avatar.
2. El usuario crea expresiones con imágenes PNG transparentes propias.
3. Al pulsar **LIVE**, Android solicita los permisos necesarios y aparece el avatar flotante.
4. El avatar se puede mover y mantener en una posición segura al cambiar orientación.
5. Un menú de control queda disponible para el usuario, con dos presentaciones: lista y radial/circular.
6. Durante una grabación iniciada desde la app, los controles se ocultan de la salida grabada y se restauran al terminar, siempre que la arquitectura de captura lo permita sin artefactos.
7. El video se guarda como MP4 en la galería y el usuario recibe un estado claro si se cancela un permiso o falla la grabación.

## 3. Avatar y expresiones

- Importar PNG transparentes desde el almacenamiento mediante el selector del sistema.
- Cada expresión utiliza cuatro fotogramas: ojos abiertos/boca cerrada; ojos abiertos/boca abierta; ojos cerrados/boca cerrada; ojos cerrados/boca abierta.
- Crear, seleccionar y reemplazar expresiones por nombre.
- Guardar localmente las referencias y ajustes.
- Ajustar tamaño y opacidad de la superposición en vivo.
- Mantener la opacidad local independiente del bloqueo de movimiento.
- Evitar que el avatar quede fuera de pantalla después de rotar el dispositivo.
- Validar referencias de imágenes revocadas o inaccesibles y mostrar una forma de reimportarlas.

## 4. Animación y entradas

- Parpadeo automático.
- Movimiento de reposo sutil, configurable y no mareante.
- Movimiento pequeño al hablar, sin saltos excesivos.
- Efecto de golpe opcional: animación breve y partículas/aves orbitando al avatar, con botón para activar/desactivar.
- Seguimiento facial con cámara frontal para apoyar expresiones o movimiento, con indicador claro de cámara activa y gestión de permisos.
- Detección de voz para abrir/cerrar la boca o cambiar animación. No afirmar sincronización labial precisa si solo se detecta volumen de audio.
- Priorizar estabilidad térmica, batería y limpieza de cámara/micrófono cuando LIVE se detiene.

## 5. Menú de control

Dos modos visuales seleccionables:

### Modo lista
- Controles organizados por grupos y desplazables en pantallas pequeñas.
- Secciones sugeridas: Expresiones, Animación, Posición, Apariencia, Cámara/Voz y Grabación.
- La selección de una expresión muestra sus opciones/fotogramas en una zona inferior sin tapar innecesariamente al avatar.

### Modo radial/circular
- Botón central para abrir/cerrar.
- Accesos alrededor para expresiones favoritas y acciones rápidas.
- Debe respetar los bordes de pantalla y ser accesible con una sola mano.
- La lista y el modo radial deben compartir el mismo estado y acciones, no mantener dos configuraciones distintas.

### Comportamiento del menú
- Visible para operar en el teléfono cuando no se graba.
- Oculto de la composición final del video, pero no se debe prometer invisibilidad universal frente a grabadores externos.
- Al iniciar/detener/cancelar una grabación, restaurar el estado previo del menú sin dejar ventanas huérfanas.

## 6. Grabación integrada

- Iniciar captura solo después del consentimiento explícito del diálogo del sistema Android.
- Usar un servicio foreground con el tipo y permisos exigidos por la versión de Android.
- Grabar MP4, publicar el archivo mediante MediaStore y mostrar confirmación/ruta al terminar.
- Controles de iniciar, pausar/reanudar y detener; mostrar estado y errores.
- Manejar rechazo de permisos, revocación de captura, rotación, interrupciones, falta de espacio y cierre del servicio.
- Liberar MediaProjection, VirtualDisplay, encoder, recorder y recursos en todos los caminos de salida.
- No pedir permiso de almacenamiento amplio cuando MediaStore resuelve la escritura.

### Limitación técnica crítica

MediaProjection captura la pantalla compuesta. No ofrece una API pública universal para excluir una ventana flotante arbitraria de la grabación mientras esa ventana sigue visible localmente. FLAG_SECURE puede hacer que el área capturada quede en blanco/negro. Por ello, esconder el menú y lograr que no aparezca un rectángulo negro son problemas distintos.

Para que el avatar se vea opaco en el MP4 aunque esté translúcido en el teléfono, el video necesita composición separada: una fuente de fondo sin el avatar y una capa transparente del avatar renderizada a alfa completo. La captura de pantalla por sí sola no garantiza que se pueda recuperar el fondo limpio de cualquier otra app. La solución final podría requerir limitar los modos soportados, capturar dentro de la propia app o diseñar un compositor compatible con las restricciones de Android. Esta función no se debe anunciar como resuelta hasta comprobarla en dispositivo real.

## 7. Privacidad, permisos y accesibilidad

- Explicar cuándo se usa cámara, micrófono y captura de pantalla.
- No iniciar captura sin consentimiento visible.
- Indicador persistente del sistema para la grabación.
- Detener recursos al salir de LIVE.
- Errores claros y recuperables, controles táctiles grandes y contraste suficiente.
- No ocultar los indicadores de privacidad del sistema.

## 8. Pruebas de aceptación

- [ ] Compila el APK de depuración para el commit exacto y el artefacto se puede descargar.
- [ ] Importar y reabrir varias expresiones personalizadas.
- [ ] El avatar flotante aparece, se mueve y no desaparece al rotar.
- [ ] Opacidad, tamaño y bloqueo se comportan independientemente.
- [ ] Cámara y voz solicitan permisos correctamente y se recuperan de una denegación.
- [ ] Los modos lista y radial accionan las mismas funciones.
- [ ] Las animaciones son suaves y se pueden desactivar.
- [ ] La grabación requiere consentimiento explícito y produce un MP4 reproducible en la galería.
- [ ] Los controles no salen en el video.
- [ ] No hay rectángulo negro donde se ocultan controles o avatar.
- [ ] El avatar aparece opaco en el video si esa opción está activada, sin cambiar su opacidad local.
- [ ] Cancelación, revocación, rotación, poco almacenamiento y parada liberan recursos.
- [ ] Pruebas en al menos una versión Android reciente y un teléfono físico.

## 9. Estado y criterio de finalización

El código y la compilación automatizada pueden verificar estructura y compilación, pero no sustituyen las pruebas de cámara, voz, superposiciones ni calidad de captura en hardware real. Cada función debe etiquetarse como **implementada**, **compila**, **probada en dispositivo** o **pendiente**; son niveles distintos.
