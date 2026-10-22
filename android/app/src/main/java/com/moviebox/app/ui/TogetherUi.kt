package com.moviebox.app.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.widget.Toast
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.moviebox.app.data.Together

/** Tombol Nonton Bareng. Biru saat sedang di dalam room. */
@Composable
fun TogetherButton(size: Dp = 26.dp) {
    IconButton(onClick = { Together.showDialog = true }) {
        Icon(
            PlayerIcons.Group,
            contentDescription = "Nonton Bareng",
            tint = if (Together.roomCode != null) Accent else Color.White,
            modifier = Modifier.size(size),
        )
    }
}

/** Jendela Nonton Bareng: buat room, gabung dengan kode, bagikan, keluar. */
@Composable
fun TogetherDialog() {
    if (!Together.showDialog) return
    val ctx = LocalContext.current
    val code = Together.roomCode
    var input by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = { Together.showDialog = false },
        containerColor = Surface2,
        titleContentColor = Color.White,
        textContentColor = Color.White.copy(alpha = 0.85f),
        icon = { Icon(PlayerIcons.Group, null, tint = Accent) },
        title = { Text("Nonton Bareng", fontWeight = FontWeight.Bold) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                if (code == null) {
                    Text(
                        "Tonton film atau serial yang sama bersama teman di HP masing-masing, pas di detik yang sama. " +
                            "Buat room lalu bagikan kodenya, atau masukkan kode dari temanmu.",
                        fontSize = 14.sp,
                    )
                    Button(
                        onClick = { Together.create(ctx) },
                        colors = ButtonDefaults.buttonColors(containerColor = Accent, contentColor = Color(0xFF00172E)),
                        modifier = Modifier.fillMaxWidth(),
                    ) { Text("Buat room", fontWeight = FontWeight.Bold) }
                    OutlinedTextField(
                        value = input,
                        onValueChange = { input = it.uppercase().filter { c -> c.isLetterOrDigit() }.take(6) },
                        label = { Text("Kode room") },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Characters),
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = Accent, focusedLabelColor = Accent, cursorColor = Accent,
                            focusedTextColor = Color.White, unfocusedTextColor = Color.White,
                        ),
                        modifier = Modifier.fillMaxWidth(),
                    )
                    OutlinedButton(
                        onClick = {
                            if (!Together.join(ctx, input)) Toast.makeText(ctx, "Kode room harus 6 karakter", Toast.LENGTH_SHORT).show()
                        },
                        modifier = Modifier.fillMaxWidth(),
                    ) { Text("Gabung", color = Color.White) }
                } else {
                    Text("Kode room", fontSize = 13.sp)
                    Text(
                        code.chunked(3).joinToString(" "),
                        fontSize = 34.sp,
                        fontWeight = FontWeight.Bold,
                        fontFamily = FontFamily.Monospace,
                        letterSpacing = 4.sp,
                        color = Color.White,
                        modifier = Modifier.align(Alignment.CenterHorizontally),
                    )
                    Text(
                        when {
                            Together.isHost && Together.guestControl -> "Kamu host. Kamu dan anggota bisa memilih tontonan, putar, jeda, dan geser durasi."
                            Together.isHost -> "Kamu host. Putar film/serial apa pun, teman di room otomatis ikut di posisi yang sama."
                            Together.guestControl -> "Kamu anggota. Host mengizinkan kamu ikut memilih tontonan dan mengatur pemutaran."
                            else -> "Kamu anggota. Pemutaran mengikuti host."
                        },
                        fontSize = 14.sp,
                    )
                    if (Together.isGuest) {
                        Text(
                            Together.hostTitle?.let { "Host sedang memutar: $it" } ?: "Menunggu host memutar sesuatu…",
                            fontSize = 13.sp,
                            color = AccentLight,
                        )
                    }
                    if (Together.isHost) {
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .clickable { Together.updateGuestControl(!Together.guestControl) },
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text("Anggota juga bisa mengatur", fontSize = 14.sp, modifier = Modifier.weight(1f))
                            Switch(
                                checked = Together.guestControl,
                                onCheckedChange = { Together.updateGuestControl(it) },
                                colors = SwitchDefaults.colors(checkedTrackColor = Accent),
                            )
                        }
                    }
                    Text(
                        "Peserta: ${Together.members} · " + if (Together.connected) "Tersambung" else "Menyambungkan…",
                        fontSize = 13.sp,
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                        Button(
                            onClick = { shareRoom(ctx, code) },
                            colors = ButtonDefaults.buttonColors(containerColor = Accent, contentColor = Color(0xFF00172E)),
                            modifier = Modifier.weight(1f),
                        ) { Text("Bagikan") }
                        OutlinedButton(onClick = { copyRoom(ctx, code) }, modifier = Modifier.weight(1f)) {
                            Text("Salin kode", color = Color.White)
                        }
                    }
                    OutlinedButton(
                        onClick = { Together.leave(); Together.showDialog = false },
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 4.dp),
                    ) { Text(if (Together.isHost) "Tutup room" else "Keluar room", color = Color(0xFFFF6B6B)) }
                }
            }
        },
        confirmButton = { TextButton(onClick = { Together.showDialog = false }) { Text("Tutup", color = Accent) } },
    )
}

private fun shareRoom(ctx: Context, code: String) {
    val text = "Ayo nonton bareng di Lumi Box! Buka Nonton Bareng, lalu masukkan kode room: $code"
    val intent = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, text)
    ctx.startActivity(Intent.createChooser(intent, "Bagikan kode room").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
}

private fun copyRoom(ctx: Context, code: String) {
    val cm = ctx.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    cm.setPrimaryClip(ClipData.newPlainText("Kode room", code))
    Toast.makeText(ctx, "Kode disalin", Toast.LENGTH_SHORT).show()
}
