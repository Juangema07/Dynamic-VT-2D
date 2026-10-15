package com.juangema.dynamicvt2d

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.compose.setContent
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.json.JSONArray
import org.json.JSONObject

data class AvatarExpression(val name: String, val frames: List<String>)

object AvatarStore {
    private const val PREFS = "dynamic_vt2d"
    private const val EXPRESSIONS = "expressions"
    private const val SELECTED = "selected_expression"
    private fun prefs(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun load(context: Context): List<AvatarExpression> {
        val raw = prefs(context).getString(EXPRESSIONS, "[]") ?: "[]"
        return runCatching {
            val array = JSONArray(raw)
            (0 until array.length()).mapNotNull { i ->
                val item = array.optJSONObject(i) ?: return@mapNotNull null
                val framesJson = item.optJSONArray("frames") ?: return@mapNotNull null
                val frames = (0 until framesJson.length()).map { framesJson.optString(it) }
                if (item.optString("name").isBlank() || frames.size != 4 || frames.any { it.isBlank() }) null
                else AvatarExpression(item.optString("name"), frames)
            }
        }.getOrDefault(emptyList())
    }

    fun save(context: Context, expressions: List<AvatarExpression>) {
        val array = JSONArray()
        expressions.forEach { expression ->
            val frames = JSONArray()
            expression.frames.forEach { frames.put(it) }
            array.put(JSONObject().put("name", expression.name).put("frames", frames))
        }
        prefs(context).edit().putString(EXPRESSIONS, array.toString()).apply()
    }

    fun selected(context: Context): String = prefs(context).getString(SELECTED, "") ?: ""
    fun select(context: Context, name: String) { prefs(context).edit().putString(SELECTED, name).apply() }
    fun selectedExpression(context: Context): AvatarExpression? =
        load(context).firstOrNull { it.name == selected(context) } ?: load(context).firstOrNull()

    fun size(context: Context): Float = prefs(context).getFloat("avatar_size", 180f)
    fun setSize(context: Context, value: Float) { prefs(context).edit().putFloat("avatar_size", value).apply() }
    fun opacity(context: Context): Float = prefs(context).getFloat("avatar_opacity", 1f)
    fun setOpacity(context: Context, value: Float) { prefs(context).edit().putFloat("avatar_opacity", value).apply() }
    fun rotation(context: Context): Float = prefs(context).getFloat("avatar_rotation", 0f)
    fun setRotation(context: Context, value: Float) { prefs(context).edit().putFloat("avatar_rotation", value).apply() }
    fun blinkMode(context: Context): String = prefs(context).getString("blink_mode", "AUTO") ?: "AUTO"
    fun setBlinkMode(context: Context, value: String) { prefs(context).edit().putString("blink_mode", value).apply() }
    fun mouthMode(context: Context): String = prefs(context).getString("mouth_mode", "VOICE") ?: "VOICE"
    fun setMouthMode(context: Context, value: String) { prefs(context).edit().putString("mouth_mode", value).apply() }
}

class MainActivity : ComponentActivity() {
    private val trackingPermissionLauncher = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        startOverlayService()
    }
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (android.os.Build.VERSION.SDK_INT >= 23 &&
            checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), 204)
        }
        setContent {
            MaterialTheme {
                Surface(modifier = Modifier.fillMaxSize(), color = Color(0xFF10131D)) {
                    StudioScreen(
                        onLive = { activateLive() },
                        onStop = { stopService(Intent(this, AvatarOverlayService::class.java)) },
                        onPermission = { openOverlaySettings() },
                        onApply = { activateLive() }
                    )
                }
            }
        }
    }

    private fun openOverlaySettings() {
        startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:" + packageName)))
    }

    private fun activateLive() {
        if (!Settings.canDrawOverlays(this)) {
            openOverlaySettings()
            return
        }
        val missing = arrayOf(Manifest.permission.RECORD_AUDIO, Manifest.permission.CAMERA)
            .filter { checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED }
        if (missing.isNotEmpty()) {
            trackingPermissionLauncher.launch(missing.toTypedArray())
            return
        }
        startOverlayService()
    }

    private fun startOverlayService() {
        if (!Settings.canDrawOverlays(this)) return
        startService(Intent(this, AvatarOverlayService::class.java))
    }
}

@Composable
private fun StudioScreen(
    onLive: () -> Unit,
    onStop: () -> Unit,
    onPermission: () -> Unit,
    onApply: () -> Unit
) {
    val context = LocalContext.current
    var expressions by remember { mutableStateOf(AvatarStore.load(context)) }
    var selectedName by remember { mutableStateOf(AvatarStore.selected(context)) }
    var expressionName by remember { mutableStateOf("") }
    var eyesClosed by remember { mutableStateOf(false) }
    var mouthOpen by remember { mutableStateOf(false) }
    var avatarSize by remember { mutableStateOf(AvatarStore.size(context)) }
    var avatarOpacity by remember { mutableStateOf(AvatarStore.opacity(context)) }
    var blinkMode by remember { mutableStateOf(AvatarStore.blinkMode(context)) }
    var mouthMode by remember { mutableStateOf(AvatarStore.mouthMode(context)) }
    var importMessage by remember { mutableStateOf("Importa cuatro PNG transparentes por expresión.") }

    val selected = expressions.firstOrNull { it.name == selectedName } ?: expressions.firstOrNull()
    val frameIndex = (if (eyesClosed) 2 else 0) + if (mouthOpen) 1 else 0
    val previewUri = selected?.frames?.getOrNull(frameIndex)

    val imagePicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        if (uris.size != 4) {
            importMessage = "Debes seleccionar exactamente 4 imágenes. Seleccionaste " + uris.size + "."
        } else {
            val name = expressionName.trim().ifBlank { "Neutro" }
            uris.forEach { uri ->
                runCatching {
                    context.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }
            }
            val newExpression = AvatarExpression(name, uris.map { it.toString() })
            val updated = expressions.filterNot { it.name.equals(name, ignoreCase = true) } + newExpression
            AvatarStore.save(context, updated)
            AvatarStore.select(context, name)
            expressions = updated
            selectedName = name
            eyesClosed = false
            mouthOpen = false
            importMessage = "Expresión '" + name + "' guardada con sus cuatro imágenes."
        }
    }

    Column(
        modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(18.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text("Dynamic VT-2D", fontSize = 28.sp, fontWeight = FontWeight.Bold, color = Color.White)
        Text("Estudio de avatar 2D", color = Color(0xFFBFC7DA))
        Card(modifier = Modifier.fillMaxWidth()) {
            Column(
                modifier = Modifier.fillMaxWidth().background(Color(0xFF202638)).padding(16.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text("VISTA PREVIA", color = Color(0xFFBFC7DA), fontSize = 12.sp)
                if (previewUri != null) {
                    val bitmap = remember(previewUri) {
                        runCatching {
                            context.contentResolver.openInputStream(Uri.parse(previewUri))?.use { BitmapFactory.decodeStream(it)?.asImageBitmap() }
                        }.getOrNull()
                    }
                    if (bitmap != null) {
                        Image(bitmap = bitmap, contentDescription = "Vista previa del avatar", modifier = Modifier.size(190.dp), contentScale = ContentScale.Fit)
                    } else {
                        Text("No se pudo abrir la imagen. Vuelve a importarla.", color = Color.White)
                    }
                } else {
                    Text("🙂", fontSize = 92.sp)
                    Text("Aún no has importado un avatar", color = Color.White)
                }
                Text(selected?.name ?: "Sin expresión seleccionada", color = Color.White, fontWeight = FontWeight.SemiBold)
                Text("Fotograma: " + (frameIndex + 1) + " de 4", color = Color(0xFFBFC7DA), fontSize = 12.sp)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = { eyesClosed = !eyesClosed }) { Text(if (eyesClosed) "Ojos cerrados" else "Ojos abiertos") }
                    OutlinedButton(onClick = { mouthOpen = !mouthOpen }) { Text(if (mouthOpen) "Boca abierta" else "Boca cerrada") }
                }
            }
        }

        Text("1. CREA O ACTUALIZA UNA EXPRESIÓN", color = Color(0xFF8FA7FF), fontWeight = FontWeight.Bold)
        TextField(
            value = expressionName,
            onValueChange = { expressionName = it },
            modifier = Modifier.fillMaxWidth(),
            label = { Text("Nombre: Neutro, Feliz, Confundido…") },
            singleLine = true
        )
        Button(onClick = { imagePicker.launch(arrayOf("image/png", "image/*")) }, modifier = Modifier.fillMaxWidth()) {
            Text("Importar 4 imágenes PNG")
        }
        Text("Orden: 1) ojos abiertos + boca cerrada, 2) ojos abiertos + boca abierta, 3) ojos cerrados + boca cerrada, 4) ojos cerrados + boca abierta.", color = Color(0xFFBFC7DA), fontSize = 12.sp)
        Text(importMessage, color = Color(0xFFB9E7C5), fontSize = 12.sp)

        Text("2. TUS EXPRESIONES (" + expressions.size + ")", color = Color(0xFF8FA7FF), fontWeight = FontWeight.Bold)
        if (expressions.isEmpty()) {
            Text("Todavía no hay expresiones. Importa cuatro imágenes para crear la primera.", color = Color(0xFFBFC7DA))
        } else {
            expressions.forEach { expression ->
                Card(modifier = Modifier.fillMaxWidth()) {
                    Row(
                        modifier = Modifier.fillMaxWidth().background(Color(0xFF202638)).padding(10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(expression.name, color = Color.White, fontWeight = FontWeight.SemiBold)
                            Text("4 fotogramas PNG", color = Color(0xFFBFC7DA), fontSize = 12.sp)
                        }
                        OutlinedButton(onClick = {
                            selectedName = expression.name
                            AvatarStore.select(context, expression.name)
                            eyesClosed = false
                            mouthOpen = false
                        }) { Text(if (expression.name == selectedName) "Seleccionada" else "Usar") }
                    }
                }
            }
        }

        Text("3. ANIMACIÓN Y CONTROLES FLOTANTES", color = Color(0xFF8FA7FF), fontWeight = FontWeight.Bold)
        Text("Movimiento de boca", color = Color.White, fontWeight = FontWeight.SemiBold)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = {
                mouthMode = "VOICE"
                AvatarStore.setMouthMode(context, mouthMode)
            }) { Text(if (mouthMode == "VOICE") "✓ Voz" else "Voz") }
            OutlinedButton(onClick = {
                mouthMode = "CAMERA"
                AvatarStore.setMouthMode(context, mouthMode)
            }) { Text(if (mouthMode == "CAMERA") "✓ Cámara" else "Cámara") }
        }
        Text(if (mouthMode == "VOICE") "La boca responde al nivel de sonido del micrófono." else "Modo cámara seleccionable; la detección real de apertura de boca aún es experimental y no está integrada.", color = Color(0xFFBFC7DA), fontSize = 12.sp)

        Text("Parpadeo", color = Color.White, fontWeight = FontWeight.SemiBold)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = {
                blinkMode = "AUTO"
                AvatarStore.setBlinkMode(context, blinkMode)
            }) { Text(if (blinkMode == "AUTO") "✓ Automático" else "Automático") }
            OutlinedButton(onClick = {
                blinkMode = "CAMERA"
                AvatarStore.setBlinkMode(context, blinkMode)
            }) { Text(if (blinkMode == "CAMERA") "✓ Cámara" else "Cámara") }
        }
        Text(if (blinkMode == "AUTO") "Parpadeo aleatorio automático." else "Modo cámara seleccionable; el seguimiento ocular real todavía no está integrado.", color = Color(0xFFBFC7DA), fontSize = 12.sp)

        Text("Tamaño: " + avatarSize.toInt() + " dp", color = Color.White)
        Slider(value = avatarSize, onValueChange = {
            avatarSize = it
            AvatarStore.setSize(context, it)
        }, valueRange = 90f..320f)
        Text("Opacidad: " + (avatarOpacity * 100).toInt() + "%", color = Color.White)
        Slider(value = avatarOpacity, onValueChange = {
            avatarOpacity = it
            AvatarStore.setOpacity(context, it)
        }, valueRange = 0.2f..1f)
        Text("Arrastra el avatar para moverlo. Tócalo para abrir los controles; activa el modo grabación desde el menú para ocultarlos. En modo grabación, un toque cambia de expresión y una pulsación larga sale del modo sin mostrar un menú durante la grabación.", color = Color(0xFFBFC7DA), fontSize = 12.sp)
        Button(onClick = onLive, modifier = Modifier.fillMaxWidth()) { Text("● LIVE — Mostrar avatar flotante") }
        OutlinedButton(onClick = onApply, modifier = Modifier.fillMaxWidth()) { Text("Aplicar ajustes al LIVE") }
        OutlinedButton(onClick = onStop, modifier = Modifier.fillMaxWidth()) { Text("Detener avatar flotante") }
        OutlinedButton(onClick = onPermission, modifier = Modifier.fillMaxWidth()) { Text("Permiso para mostrarse sobre otras apps") }

        Text("Nota: la detección por cámara aparece como opción experimental, pero todavía no está implementada en esta compilación. La detección por voz usa el nivel de audio, no reconocimiento de palabras.", color = Color(0xFFBFC7DA), fontSize = 12.sp)
    }
}
