# Dynamic VT-2D

Aplicación Android para crear y mostrar avatares VTuber 2D con imágenes PNG propias.

## Importar un avatar
1. Abre la aplicación y escribe el nombre de una expresión (por ejemplo, Neutro, Feliz o Confundido).
2. Pulsa **Importar 4 imágenes PNG** y selecciona exactamente cuatro imágenes transparentes en este orden:
   - Ojos abiertos + boca cerrada.
   - Ojos abiertos + boca abierta.
   - Ojos cerrados + boca cerrada.
   - Ojos cerrados + boca abierta.
3. Selecciona una expresión de la lista y usa la vista previa para revisar sus cuatro fotogramas.
4. Pulsa **LIVE** y concede permisos de cámara, micrófono y superposición cuando Android los solicite.
5. En LIVE, toca dos veces el avatar para abrir o cerrar el panel privado.

## Controles LIVE
- **Expresiones:** selector en lista o circular; desplaza la tira para recorrer todas tus expresiones.
- **Opciones:** bloqueo, seguimiento facial, tamaño y controles rápidos.
- **Voz:** reconoce nombres de expresiones y comandos como “abrir menú”, “cerrar menú”, “bloquear”, “desbloquear” y “activar cámara”.
- **Cámara:** la cámara frontal detecta el estado de los ojos y la apertura de la boca; si hay una expresión llamada Feliz/Happy y una Neutro/Neutral, puede alternarlas al detectar una sonrisa.
- **Panel privado:** los controles se colocan en una ventana separada protegida contra capturas; las grabaciones de pantalla deben mostrar el avatar sin el menú.

### Nota sobre el modo bloqueo
El modo bloqueo reduce la opacidad visible del avatar y evita arrastrarlo. Android aplica la misma composición de transparencia a las grabaciones externas; no permite que un mismo overlay se vea translúcido en la pantalla del operador y a la vez opaco en una captura de otra aplicación.

Los datos, expresiones y ajustes se guardan localmente en el teléfono. Las imágenes permanecen referenciadas por los permisos persistentes del selector de documentos.

## Compilar
El workflow de GitHub Actions compila un APK de depuración al hacer push a `main`, publica el artefacto de CI y adjunta el APK a una versión de prueba cuando la compilación termina con éxito.
