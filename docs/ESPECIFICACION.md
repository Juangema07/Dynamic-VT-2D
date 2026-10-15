# Dynamic VT-2D — Especificación funcional

## Objetivo
Aplicación Android para crear, previsualizar y usar un avatar VTuber 2D personalizable como ventana flotante sobre otras aplicaciones.

## Experiencia principal
- Interfaz completa de aplicación: Inicio, Avatar, Expresiones, Efectos, Calibración, Opciones y Ayuda.
- Botón **Live** para activar/desactivar el avatar flotante.
- Vista previa dentro de la aplicación antes de activar Live.
- El avatar neutro viene preseleccionado y se identifica con 🙂.
- Controles para posición, escala, opacidad/transparencia y bloqueo de movimiento accidental.
- Panel de controles separado del avatar flotante. La posibilidad de excluirlo de una grabación depende del dispositivo y de la app de captura; no se promete compatibilidad universal.

## Expresiones personalizadas sin límite artificial
El usuario puede crear tantas expresiones como necesite. Cada expresión tiene nombre, emoji/icono opcional y exactamente cuatro fotogramas PNG con transparencia:
1. Ojos abiertos + boca cerrada
2. Ojos abiertos + boca abierta
3. Ojos cerrados + boca cerrada
4. Ojos cerrados + boca abierta

El editor debe permitir importar/reemplazar las cuatro imágenes, comprobar cuáles faltan, previsualizar la expresión y elegirla manualmente. El avatar conserva su diseño: solo cambian los fotogramas y ajustes que el usuario elija. La expresión **Neutro 🙂** se crea como selección inicial; las imágenes reales se podrán asignar/importar.

## Parpadeo y boca
- **Automático:** parpadeo ocasional alternando a los fotogramas con ojos cerrados. Debe poder activarse/desactivarse y ajustar intervalo/duración.
- **Detección facial experimental:** opción para usar la cámara frontal y estimar cuándo el usuario parpadea. Requiere permiso de cámara, calibración y pruebas de compatibilidad; no debe activarse sin consentimiento. Es una función distinta del parpadeo automático.
- La boca puede alternarse manualmente entre cerrada/abierta; la sincronización automática de voz queda como mejora opcional posterior.

## Efectos y movimiento
- Efectos visuales activables de estilo caricatura: impacto/golpe cómico, estrellas, espirales, temblor breve, rebote, squash-and-stretch y pequeñas partículas.
- Movimientos configurables: balanceo suave, rebote, inclinación, respiración visual sutil y reacción al arrastre. Controles para intensidad, velocidad y activar/desactivar cada efecto.
- Los efectos deben ser opcionales, ligeros y no cambiar permanentemente el arte original del avatar.

## Calibración y opciones
- Ajuste de posición inicial, tamaño, opacidad, sensibilidad al arrastre y zona de interacción.
- Bloqueo del avatar para tocar la pantalla sin moverlo.
- Preferencias de parpadeo, cámara, efectos, movimiento y comportamiento al iniciar.
- Guardar ajustes y expresiones localmente. No limitar la cantidad de expresiones mediante un límite arbitrario; el límite práctico dependerá del almacenamiento disponible.

## Privacidad y limitaciones técnicas
- La cámara solo se usa si el usuario activa la detección facial y concede permiso.
- La detección debe poder detenerse y mostrar claramente cuándo la cámara está activa.
- Android y algunas aplicaciones de grabación pueden capturar ventanas superpuestas; excluir el panel de control de todas las grabaciones no es técnicamente garantizable en todos los dispositivos.
- No incluir imágenes con fondo sólido: se recomiendan PNG RGBA transparentes y una escala coherente entre los cuatro fotogramas.

## Fases
1. Base Android y menú principal con previsualización.
2. Importador y biblioteca de expresiones de cuatro fotogramas.
3. Ventana flotante, Live, arrastre, tamaño, opacidad y bloqueo.
4. Parpadeo automático y selector manual de expresión/boca.
5. Calibración y guardado local.
6. Efectos caricaturescos y movimiento configurable.
7. Detección facial experimental con cámara y calibración.
8. Pruebas en distintos Android, grabadores y dispositivos; generar APK cuando se pueda compilar y validar el proyecto.
