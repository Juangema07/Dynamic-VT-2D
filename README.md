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

## Estado actual
La versión inicial incluye importación y persistencia de expresiones, vista previa de los cuatro fotogramas, selección de expresión, avatar flotante arrastrable, tamaño, opacidad y parpadeo automático básico. El modo de detección facial por cámara y los efectos avanzados todavía no están implementados.

## Transparencia y grabación de pantalla

El bloqueo solo impide arrastrar el avatar; no debe cambiar su opacidad local. La opacidad del avatar se aplica a la superposición real, por lo que una grabadora externa que capture la pantalla normalmente registra esa misma transparencia.

Android no ofrece a una aplicación normal una API pública y universal para mantener el panel visible en el teléfono y, al mismo tiempo, excluirlo de cualquier grabación externa sin que aparezca un rectángulo negro. La bandera `FLAG_SECURE` puede producir ese rectángulo; por eso el panel actual no la usa. Para una grabación con avatar opaco y controles ausentes mientras el teléfono sigue mostrando el avatar translúcido, hace falta una función de grabación integrada que componga por separado la salida del video. Esa función no está implementada todavía. Al grabar con otra app, cierra el menú antes de empezar para que no aparezca en el video.
