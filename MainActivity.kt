package com.example.callnotes

import android.Manifest
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.CallLog
import android.provider.ContactsContract
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver

data class Contact(val name: String, val number: String)

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { MaterialTheme { Screen() } }
    }
}

// शेवटचा कॉल उचलला गेला का ते कॉल लॉगवरून तपासतो
fun checkLastCall(ctx: Context, number: String): String {
    val last10 = number.filter { it.isDigit() }.takeLast(10)
    ctx.contentResolver.query(
        CallLog.Calls.CONTENT_URI,
        arrayOf(CallLog.Calls.DURATION),
        "${CallLog.Calls.NUMBER} LIKE ? AND ${CallLog.Calls.TYPE} = ?",
        arrayOf("%$last10", CallLog.Calls.OUTGOING_TYPE.toString()),
        "${CallLog.Calls.DATE} DESC"
    )?.use { c ->
        if (c.moveToFirst()) {
            return if (c.getInt(0) > 0) "✅ कॉल उचलला" else "❌ कॉल उचलला नाही"
        }
    }
    return ""
}

// ---------- CSV helpers ----------
fun csvEscape(s: String) = "\"" + s.replace("\"", "\"\"") + "\""

fun parseCsv(text: String): List<List<String>> {
    val rows = mutableListOf<List<String>>()
    var row = mutableListOf<String>()
    val sb = StringBuilder()
    var inQuotes = false
    var i = 0
    while (i < text.length) {
        val ch = text[i]
        if (inQuotes) {
            if (ch == '"') {
                if (i + 1 < text.length && text[i + 1] == '"') { sb.append('"'); i++ }
                else inQuotes = false
            } else sb.append(ch)
        } else when (ch) {
            '"' -> inQuotes = true
            ',' -> { row.add(sb.toString()); sb.clear() }
            '\r' -> {}
            '\n' -> { row.add(sb.toString()); sb.clear(); rows.add(row); row = mutableListOf() }
            else -> sb.append(ch)
        }
        i++
    }
    if (sb.isNotEmpty() || row.isNotEmpty()) { row.add(sb.toString()); rows.add(row) }
    return rows
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun Screen() {
    val ctx = LocalContext.current
    val prefs = remember { ctx.getSharedPreferences("callnotes", Context.MODE_PRIVATE) }
    val contacts = remember { mutableStateListOf<Contact>() }
    val status = remember { mutableStateMapOf<String, String>() }
    val notes = remember { mutableStateMapOf<String, String>() }
    var pending by remember { mutableStateOf<String?>(null) }

    var showDialog by remember { mutableStateOf(false) }
    var menuOpen by remember { mutableStateOf(false) }
    var nameInput by remember { mutableStateOf("") }
    var numberInput by remember { mutableStateOf("") }

    fun toast(msg: String) = Toast.makeText(ctx, msg, Toast.LENGTH_SHORT).show()

    fun saveList() {
        prefs.edit().putString(
            "list",
            contacts.joinToString("\n") { "${it.name.replace("\t", " ").replace("\n", " ")}\t${it.number}" }
        ).apply()
    }

    // सर्व काही (यादी, स्टेटस, नोंदी) सेव्ह करा
    fun saveAll() {
        saveList()
        val e = prefs.edit()
        contacts.forEach {
            e.putString("s_${it.number}", status[it.number] ?: "")
            e.putString("n_${it.number}", notes[it.number] ?: "")
        }
        e.apply()
        toast("सेव्ह झाले")
    }

    // अँप उघडल्यावर सेव्ह केलेली यादी लोड करा
    LaunchedEffect(Unit) {
        (prefs.getString("list", "") ?: "").lines()
            .filter { it.contains("\t") }
            .forEach {
                val p = it.split("\t")
                contacts.add(Contact(p[0], p[1]))
                status[p[1]] = prefs.getString("s_${p[1]}", "") ?: ""
                notes[p[1]] = prefs.getString("n_${p[1]}", "") ?: ""
            }
    }

    val permLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { }

    LaunchedEffect(Unit) {
        permLauncher.launch(
            arrayOf(Manifest.permission.CALL_PHONE, Manifest.permission.READ_CALL_LOG)
        )
    }

    // कॉन्टॅक्ट लिस्टमधून नंबर निवडण्यासाठी
    val pickLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { r ->
        r.data?.data?.let { uri ->
            ctx.contentResolver.query(
                uri,
                arrayOf(
                    ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME,
                    ContactsContract.CommonDataKinds.Phone.NUMBER
                ), null, null, null
            )?.use { c ->
                if (c.moveToFirst()) {
                    nameInput = c.getString(0) ?: ""
                    numberInput = (c.getString(1) ?: "").replace(" ", "")
                }
            }
        }
    }

    // ---------- Export (CSV) ----------
    val exportLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("text/csv")
    ) { uri ->
        if (uri != null) {
            try {
                val sb = StringBuilder("\uFEFF")
                sb.append("Name,Number,Status,Note\n")
                contacts.forEach {
                    sb.append(csvEscape(it.name)).append(',')
                        .append(csvEscape(it.number)).append(',')
                        .append(csvEscape(status[it.number] ?: "")).append(',')
                        .append(csvEscape(notes[it.number] ?: "")).append('\n')
                }
                ctx.contentResolver.openOutputStream(uri)?.use {
                    it.write(sb.toString().toByteArray(Charsets.UTF_8))
                }
                toast("${contacts.size} नंबर एक्सपोर्ट झाले")
            } catch (e: Exception) {
                toast("एक्सपोर्ट अयशस्वी")
            }
        }
    }

    // ---------- Import (CSV) ----------
    val importLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) {
            try {
                val text = ctx.contentResolver.openInputStream(uri)
                    ?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }
                    ?.removePrefix("\uFEFF") ?: ""
                var added = 0
                parseCsv(text).forEach { r ->
                    var name = r.getOrElse(0) { "" }.trim()
                    var number = r.getOrElse(1) { "" }.replace(" ", "").trim()
                    if (r.size == 1) { number = name.replace(" ", ""); name = "" }
                    // हेडर ओळ / चुकीच्या ओळी वगळा
                    if (number.none { it.isDigit() }) return@forEach
                    val st = r.getOrElse(2) { "" }
                    val nt = r.getOrElse(3) { "" }
                    if (contacts.none { it.number == number }) {
                        contacts.add(Contact(name.ifBlank { number }, number))
                        status[number] = st
                        notes[number] = nt
                        added++
                    }
                }
                saveAll()
                toast("$added नंबर इम्पोर्ट झाले")
            } catch (e: Exception) {
                toast("इम्पोर्ट अयशस्वी")
            }
        }
    }

    // कॉलनंतर अँपमध्ये परत आल्यावर स्टेटस अपडेट
    val owner = LocalLifecycleOwner.current
    DisposableEffect(owner) {
        val obs = LifecycleEventObserver { _, e ->
            if (e == Lifecycle.Event.ON_RESUME) {
                pending?.let { num ->
                    val s = checkLastCall(ctx, num)
                    status[num] = s
                    prefs.edit().putString("s_$num", s).apply()
                    pending = null
                }
            }
        }
        owner.lifecycle.addObserver(obs)
        onDispose { owner.lifecycle.removeObserver(obs) }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("कॉल नोट्स") },
                actions = {
                    TextButton(onClick = { saveAll() }) { Text("सेव्ह") }
                    Box {
                        TextButton(onClick = { menuOpen = true }) { Text("⋮") }
                        DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                            DropdownMenuItem(
                                text = { Text("इम्पोर्ट (CSV)") },
                                onClick = {
                                    menuOpen = false
                                    importLauncher.launch(
                                        arrayOf("text/*", "application/csv", "application/vnd.ms-excel")
                                    )
                                }
                            )
                            DropdownMenuItem(
                                text = { Text("एक्सपोर्ट (CSV)") },
                                onClick = {
                                    menuOpen = false
                                    exportLauncher.launch("call_notes.csv")
                                }
                            )
                            DropdownMenuItem(
                                text = { Text("सर्व डिटेल क्लिअर") },
                                onClick = {
                                    menuOpen = false
                                    val e = prefs.edit()
                                    contacts.forEach {
                                        status[it.number] = ""; notes[it.number] = ""
                                        e.remove("s_${it.number}").remove("n_${it.number}")
                                    }
                                    e.apply()
                                }
                            )
                        }
                    }
                }
            )
        },
        floatingActionButton = {
            FloatingActionButton(onClick = {
                nameInput = ""; numberInput = ""; showDialog = true
            }) { Text("+ नंबर", Modifier.padding(horizontal = 16.dp)) }
        }
    ) { pad ->
        LazyColumn(Modifier.padding(pad).padding(horizontal = 12.dp)) {
            items(contacts, key = { it.number }) { c ->
                Card(Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
                    Column(Modifier.padding(12.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text(c.name, style = MaterialTheme.typography.titleMedium)
                                Text(c.number, style = MaterialTheme.typography.bodySmall)
                            }
                            Button(onClick = {
                                pending = c.number
                                ctx.startActivity(
                                    Intent(Intent.ACTION_CALL, Uri.parse("tel:${c.number}"))
                                )
                            }) { Text("कॉल") }
                        }
                        if (!status[c.number].isNullOrEmpty()) {
                            Text(status[c.number] ?: "", Modifier.padding(top = 6.dp))
                        }
                        OutlinedTextField(
                            value = notes[c.number] ?: "",
                            onValueChange = {
                                notes[c.number] = it
                                prefs.edit().putString("n_${c.number}", it).apply()
                            },
                            label = { Text("उत्तर / नोंद") },
                            modifier = Modifier.fillMaxWidth().padding(top = 6.dp)
                        )
                        Row {
                            TextButton(onClick = {
                                status[c.number] = ""; notes[c.number] = ""
                                prefs.edit().remove("s_${c.number}").remove("n_${c.number}").apply()
                            }) { Text("डिटेल क्लिअर") }
                            TextButton(onClick = {
                                contacts.remove(c)
                                prefs.edit().remove("s_${c.number}").remove("n_${c.number}").apply()
                                saveList()
                            }) { Text("यादीतून काढा") }
                        }
                    }
                }
            }
            item { Spacer(Modifier.height(80.dp)) }
        }
    }

    if (showDialog) {
        AlertDialog(
            onDismissRequest = { showDialog = false },
            title = { Text("नंबर जोडा") },
            text = {
                Column {
                    OutlinedButton(
                        onClick = {
                            pickLauncher.launch(
                                Intent(
                                    Intent.ACTION_PICK,
                                    ContactsContract.CommonDataKinds.Phone.CONTENT_URI
                                )
                            )
                        },
                        modifier = Modifier.fillMaxWidth()
                    ) { Text("कॉन्टॅक्ट लिस्टमधून निवडा") }
                    OutlinedTextField(
                        value = nameInput,
                        onValueChange = { nameInput = it },
                        label = { Text("नाव") },
                        modifier = Modifier.fillMaxWidth().padding(top = 8.dp)
                    )
                    OutlinedTextField(
                        value = numberInput,
                        onValueChange = { numberInput = it },
                        label = { Text("मोबाईल नंबर") },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone),
                        modifier = Modifier.fillMaxWidth().padding(top = 8.dp)
                    )
                }
            },
            confirmButton = {
                TextButton(
                    enabled = numberInput.isNotBlank(),
                    onClick = {
                        val num = numberInput.replace(" ", "")
                        if (contacts.none { it.number == num }) {
                            contacts.add(Contact(nameInput.ifBlank { num }, num))
                            status[num] = ""; notes[num] = ""
                            saveList()
                        }
                        showDialog = false
                    }
                ) { Text("जोडा") }
            },
            dismissButton = { TextButton(onClick = { showDialog = false }) { Text("रद्द") } }
        )
    }
}
