# Dynamic VT-2D

Aplicación Android para crear y mostrar avatares VTuber 2D con imágenes PNG propias.

## Importar un avatar
1. Abre la aplicación y escribe el nombre de una expresión (por ejemplo, Neutro, Feliz o Confundido).
2. Pulsa **Importar 4 imágenes PNG** y selecciona exactamente cuatro imágenes transparentes en este orden:
   - Ojos abiertos + boca cerrada.
   - Ojos abiertos + boca abierta.
   - Ojos cerrados + boca cerrada.
   - Ojos cerrados + boca abierta.
3. Selecciona una expresión de la lista y usa los botones de ojos y boca para revisar sus cuatro fotogramas.
4. Pulsa **LIVE** y concede el permiso para mostrarse sobre otras aplicaciones si Android lo solicita.
5. Arrastra el avatar flotante para cambiarlo de lugar. El tamaño y la opacidad se configuran desde el estudio.

Si escribes un nombre que ya existe, importar cuatro imágenes con ese mismo nombre reemplaza sus fotogramas. Los datos y ajustes se guardan localmente en el teléfono.

## Funciones incluidas en el código
- Importar y guardar expresiones personalizadas de cuatro fotogramas PNG.
- Avatar flotante arrastrable, configuración de tamaño/opacidad y parpadeo automático.
- Seguimiento facial opcional con cámara frontal para ojos/boca y cambio a expresiones como Feliz si existe una expresión compatible.
- Reconocimiento de comandos de voz en español (Colombia), sujeto a la disponibilidad y configuración del servicio de reconocimiento instalado en Android.
- Panel flotante con modo lista y selección circular, bloqueo de movimiento y efecto de golpe.
- Grabación integrada de pantalla a MP4 en Android 10 o posterior, con consentimiento del sistema y guardado en Películas/Dynamic VT-2D.

La compilación de CI verifica que el APK se construya, pero la cámara, el micrófono, el permiso de superposición y la grabación aún requieren validación en un teléfono físico.

## Transparencia y grabación de pantalla

El bloqueo solo impide arrastrar el avatar; no debe cambiar su opacidad local. La opacidad del avatar se aplica a la superposición real, por lo que una grabadora externa que capture la pantalla normalmente registra esa misma transparencia.

Android no ofrece una API pública universal para mantener una ventana flotante visible para el usuario y excluir únicamente esa ventana de una grabación de pantalla. `FLAG_SECURE` puede producir un rectángulo negro, así que no se aplica al panel. La grabadora integrada nueva usa MediaProjection y guarda MP4, pero captura la pantalla compuesta: si el menú está visible, puede quedar incluido en el video. La exclusión selectiva del menú y la composición de un avatar opaco independiente de su opacidad local siguen pendientes de diseño y prueba en dispositivo real. Mientras tanto, cierra el menú antes de grabar si no quieres que aparezca en el MP4. La grabación integrada requiere Android 10 o posterior.
