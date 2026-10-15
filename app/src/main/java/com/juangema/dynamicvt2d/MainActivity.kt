package com.juangema.dynamicvt2d

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme {
                Surface(modifier = Modifier.fillMaxSize(), color = Color(0xFF10131D)) {
                    HomeScreen(
                        onLive = { activateLive() },
                        onPermission = { openOverlaySettings() }
                    )
                }
            }
        }
    }

    private fun openOverlaySettings() {
        startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
            Uri.parse("package:$packageName")))
    }

    private fun activateLive() {
        if (!Settings.canDrawOverlays(this)) {
            openOverlaySettings()
            return
        }
        startService(Intent(this, AvatarOverlayService::class.java))
    }
}

@Composable
private fun HomeScreen(onLive: () -> Unit, onPermission: () -> Unit) {
    Column(
        modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        Text("Dynamic VT-2D", fontSize = 28.sp, fontWeight = FontWeight.Bold, color = Color.White)
        Text("Tu avatar, tus expresiones, tu estilo.", color = Color(0xFFBFC7DA))
        Card(modifier = Modifier.fillMaxWidth()) {
            Column(
                modifier = Modifier.fillMaxWidth().background(Color(0xFF202638)).padding(18.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text("VISTA PREVIA", color = Color(0xFFBFC7DA), fontSize = 12.sp)
                Spacer(Modifier.height(8.dp))
                Text("🙂", fontSize = 100.sp)
                Text("Neutro", color = Color.White, fontWeight = FontWeight.SemiBold)
                Text("Expresión inicial", color = Color(0xFFBFC7DA), fontSize = 12.sp)
            }
        }
        Button(onClick = onLive, modifier = Modifier.fillMaxWidth()) {
            Text("●  LIVE — Activar avatar flotante")
        }
        OutlinedButton(onClick = onPermission, modifier = Modifier.fillMaxWidth()) {
            Text("Permiso para mostrarse sobre otras apps")
        }
        Text("TU ESTUDIO", color = Color(0xFF8FA7FF), fontWeight = FontWeight.Bold)
        FeatureCard("Expresiones", "Crea todas las expresiones que quieras, con 4 PNG transparentes por expresión.")
        FeatureCard("Parpadeo", "Modo automático y detección facial experimental con cámara.")
        FeatureCard("Efectos y movimiento", "Impacto caricaturesco, estrellas, rebote, balanceo e inclinación.")
        FeatureCard("Calibración", "Tamaño, posición, opacidad, bloqueo y sensibilidad.")
        FeatureCard("Opciones", "Preferencias y ajustes guardados localmente (en desarrollo).")
        Text("Prototipo inicial: Live usa un emoji de prueba. El importador de PNG y los ajustes avanzados aún no están implementados.", color = Color(0xFFBFC7DA), fontSize = 12.sp)
    }
}

@Composable
private fun FeatureCard(title: String, description: String) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.background(Color(0xFF202638)).padding(16.dp)) {
            Text(title, color = Color.White, fontWeight = FontWeight.SemiBold, fontSize = 17.sp)
            Spacer(Modifier.height(4.dp))
            Text(description, color = Color(0xFFBFC7DA), fontSize = 13.sp)
        }
    }
}
