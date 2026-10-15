# Dynamic VT 2D

Aplicación Android para mostrar un avatar VTuber 2D flotante mientras se usan otras aplicaciones, se graba la pantalla o se hace una transmisión en vivo.

## Objetivo

Mostrar únicamente el avatar como superposición flotante. Los controles deben poder abrirse por separado y permanecer fuera de la captura cuando el método de grabación/transmisión lo permita.

## Funciones principales

- Avatar 2D con imágenes PNG transparentes.
- Cambiar manualmente entre expresiones del mismo personaje.
- Mover el avatar y ajustar su tamaño.
- Ajustar la opacidad del avatar.
- Bloqueo de interacción para poder tocar otras aplicaciones sin desplazar el avatar.
- Panel de control separado del avatar.
- Guardar posición, tamaño, opacidad y expresión seleccionada.
- Iniciar y detener el servicio flotante desde la aplicación.
- Permiso de Android para mostrar ventanas sobre otras aplicaciones.

## Expresiones previstas

Neutral, Sonrisa, Enojado, Triste, Sorprendido, Risa, Serio intenso, Molesto, Asustado y Confundido. Se utilizarán variantes del mismo avatar para conservar el diseño entre expresiones.

## Requisitos técnicos y límites

- Objetivo inicial: Android.
- Implementación propuesta: Kotlin y Android SDK nativo.
- La superposición debe usar una ventana independiente y transparente.
- La captura de pantalla de Android normalmente captura las superposiciones. Ocultar únicamente el panel de controles de la grabación depende del método de captura y de la compatibilidad del dispositivo. No se debe prometer que todas las aplicaciones de grabación o streaming puedan excluir una ventana concreta.
- El bloqueo de interacción debe evitar arrastres accidentales y dejar que el usuario interactúe con las aplicaciones subyacentes.
- La app no debe cambiar automáticamente la expresión ni alterar la apariencia del avatar; los cambios visuales serán manuales.

## Etapas de desarrollo

1. Crear la estructura Android y la pantalla principal.
2. Implementar el permiso de superposición y una ventana flotante transparente.
3. Añadir arrastre, tamaño y posición persistente.
4. Añadir selector manual de expresiones y opacidad.
5. Implementar bloqueo de interacción y panel independiente.
6. Probar grabación de pantalla, streaming, reinicio del servicio y restricciones de batería.
7. Preparar una compilación APK de prueba cuando el proyecto compile correctamente.

## Estado

Plan inicial. La estructura y las funciones deben implementarse y probarse; todavía no hay una versión APK.
