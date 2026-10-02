package com.mobox.notes

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.BroadcastReceiver
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.compose.setContent
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.security.SecureRandom
import java.text.SimpleDateFormat
import java.util.Base64
import java.util.Date
import java.util.Locale

class MainActivity : ComponentActivity() {
    var onBackground: (() -> Unit)? = null
    private var externalUntil = 0L
    private var pickerTimeout: Runnable? = null
    private val handler = Handler(Looper.getMainLooper())
    private val screenOffReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) { if(intent?.action == Intent.ACTION_SCREEN_OFF) onBackground?.invoke() }
    }
    var lastInteraction = SystemClock.elapsedRealtime()
    fun allowPicker() {
        externalUntil = SystemClock.elapsedRealtime() + 120_000
        pickerTimeout?.let(handler::removeCallbacks)
        pickerTimeout = Runnable { externalUntil = 0; onBackground?.invoke() }.also { handler.postDelayed(it, 120_000) }
    }
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        if(android.os.Build.VERSION.SDK_INT >= 33) registerReceiver(screenOffReceiver, IntentFilter(Intent.ACTION_SCREEN_OFF), Context.RECEIVER_NOT_EXPORTED)
        else registerReceiver(screenOffReceiver, IntentFilter(Intent.ACTION_SCREEN_OFF))
        setContent { MoBox(this) }
    }
    override fun onUserInteraction() { super.onUserInteraction(); lastInteraction = SystemClock.elapsedRealtime() }
    override fun onStop() {
        super.onStop()
        if (SystemClock.elapsedRealtime() > externalUntil || !android.os.PowerManager::class.java.let { getSystemService(it).isInteractive }) onBackground?.invoke()
    }
    override fun onResume() {
        super.onResume()
        if (externalUntil != 0L && SystemClock.elapsedRealtime() > externalUntil) onBackground?.invoke()
        externalUntil = 0L
        pickerTimeout?.let(handler::removeCallbacks); pickerTimeout = null
    }
    override fun onDestroy() { unregisterReceiver(screenOffReceiver); pickerTimeout?.let(handler::removeCallbacks); super.onDestroy() }
}

private fun InputStream.limited(max: Int): ByteArray {
    val output = ByteArrayOutputStream()
    val buffer = ByteArray(8192)
    while (true) { val n = read(buffer); if (n < 0) break; require(output.size() + n <= max) { "文件超过允许大小" }; output.write(buffer, 0, n) }
    return output.toByteArray()
}
private fun loadPhoto(activity: MainActivity, uri: Uri): String {
    val bytes = activity.contentResolver.openInputStream(uri)?.use { it.limited(20 * 1024 * 1024) } ?: error("无法打开图片")
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
    require(bounds.outWidth > 0 && bounds.outHeight > 0 && bounds.outWidth.toLong() * bounds.outHeight <= 40_000_000) { "图片无效或超过 4000 万像素" }
    var sample = 1
    while (maxOf(bounds.outWidth, bounds.outHeight) / sample > 1600) sample *= 2
    val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = sample }) ?: error("图片解码失败")
    val output = ByteArrayOutputStream()
    try { bitmap.compress(Bitmap.CompressFormat.JPEG, 85, output) } finally { bitmap.recycle() }
    require(output.size() <= 2_000_000) { "图片仍然过大，请压缩后重试" }
    return Base64.getEncoder().encodeToString(output.toByteArray())
}

private val accents = listOf(Color(0xFFBA7840), Color(0xFF6862C5), Color(0xFF4F8C7C), Color(0xFFBE627D))
private val papers = listOf(Color(0xFFFFF8EA), Color(0xFFECEAFE), Color(0xFFEAF4EF), Color(0xFFFCEBF0), Color(0xFFEAF0FA))
private fun family(key: String) = when(key) { "serif" -> FontFamily.Serif; "mono" -> FontFamily.Monospace; else -> FontFamily.Default }

@Composable fun MoBox(activity: MainActivity) {
    val store = remember { VaultStore(activity) }
    val scope = rememberCoroutineScope()
    var session by remember { mutableStateOf<Session?>(null) }
    var vault by remember { mutableStateOf(Vault.fresh()) }
    var notes by remember { mutableStateOf<List<Note>>(emptyList()) }
    var busy by remember { mutableStateOf(false) }
    var epoch by remember { mutableIntStateOf(0) }
    var message by remember { mutableStateOf("") }
    var page by remember { mutableStateOf("notes") }
    var selected by remember { mutableStateOf<String?>(null) }
    var query by remember { mutableStateOf("") }
    var draft by remember { mutableStateOf<Note?>(null) }
    var savedDraft by remember { mutableStateOf<Note?>(null) }
    var failedDraft by remember { mutableStateOf<Note?>(null) }
    var history by remember { mutableStateOf<EditHistory?>(null) }
    var returnAfterSave by remember { mutableStateOf(false) }
    var saveStatus by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var confirmation by remember { mutableStateOf("") }
    var dialog by remember { mutableStateOf("") }
    var categoryTarget by remember { mutableStateOf<Category?>(null) }
    var categoryName by remember { mutableStateOf("") }
    var categoryColor by remember { mutableIntStateOf(0) }
    var categoryPrivate by remember { mutableStateOf(false) }
    var categoryPassword by remember { mutableStateOf("") }
    var exportFormat by remember { mutableStateOf("HTML") }
    var exportSecrets by remember { mutableStateOf(false) }
    var pendingOutput by remember { mutableStateOf<ByteArray?>(null) }
    var pendingName by remember { mutableStateOf("") }
    var incoming by remember { mutableStateOf<ByteArray?>(null) }
    var incomingSummary by remember { mutableStateOf<Vault?>(null) }
    var restorePassword by remember { mutableStateOf("") }
    var backupTime by remember { mutableStateOf(activity.getPreferences(0).getString("backupTime", "尚未备份") ?: "尚未备份") }
    fun recordOutput(name: String, location: String) {
        if(name.endsWith(".mxbak")) {
            backupTime = SimpleDateFormat("MM-dd HH:mm", Locale.CHINA).format(Date())
            activity.getPreferences(0).edit().putString("backupTime", backupTime).apply()
        }
        message = "已保存：$location"
    }
    fun refresh(s: Session) { vault = s.vault; notes = s.visibleNotes() }
    fun changeDraft(next: Note) {
        val current = draft ?: return
        history = (history ?: EditHistory(current)).record(next.copy(updated = System.currentTimeMillis()))
        draft = history!!.current
    }
    fun <T> work(task: () -> T, failure: (Exception) -> Unit = {}, done: (T) -> Unit = {}) {
        if (busy) return
        busy = true; message = ""; val token = epoch
        scope.launch {
            try {
                val result = withContext(Dispatchers.IO) { task() }
                if (token == epoch) done(result) else if (result is Session) result.close()
            } catch (e: Exception) {
                if (token == epoch) message = when(e) {
                    is javax.crypto.AEADBadTagException -> "密码错误或文件已损坏，请检查密码和备份"
                    else -> e.message ?: "操作失败，原数据仍保留"
                }
                if(token == epoch) failure(e)
            } finally { if (token == epoch) busy = false }
        }
    }
    fun clearDialogs() { dialog = ""; password = ""; confirmation = ""; categoryPassword = ""; incoming = null; incomingSummary = null; restorePassword = ""; pendingOutput = null }
    fun lock() {
        val s = session; val dirty = draft?.takeIf { it != savedDraft }
        epoch++; busy = false; session = null; notes = emptyList(); vault = Vault.fresh()
        draft = null; savedDraft = null; failedDraft = null; history = null; returnAfterSave = false; clearDialogs(); query = ""; selected = null; page = "notes"; message = ""
        if (s != null) scope.launch {
            try { if(dirty != null) withContext(Dispatchers.IO) { s.saveNote(dirty) } }
            catch (_: Exception) { message = "锁定前保存失败，请重新解锁检查最近记录" }
            finally { withContext(Dispatchers.IO) { s.close() } }
        }
    }
    DisposableEffect(activity) { activity.onBackground = { lock() }; onDispose { activity.onBackground = null } }
    LaunchedEffect(session) {
        while (session != null) { delay(10_000); if(SystemClock.elapsedRealtime() - activity.lastInteraction > 300_000) lock() }
    }
    fun edit(note: Note) { draft = note; savedDraft = note; failedDraft = null; history = EditHistory(note); returnAfterSave = false; saveStatus = ""; page = "editor" }
    fun save(close: Boolean = false) {
        val s = session ?: return; val d = draft ?: return
        if(close) returnAfterSave = true
        if(busy) return
        saveStatus = "正在保存…"
        work({ s.saveNote(d) }, failure = { returnAfterSave = false; failedDraft = d; saveStatus = "保存失败，内容暂留编辑器，请点击右上角重试" }) {
            refresh(s); savedDraft = d; failedDraft = null; saveStatus = "已加密保存"
            if(returnAfterSave && draft == d) { draft = null; history = null; returnAfterSave = false; page = "notes" }
        }
    }
    LaunchedEffect(draft, busy) {
        val d = draft
        if(d != null && !busy) {
            if(returnAfterSave) save(true)
            else if(d != savedDraft && d != failedDraft) { delay(1200); save() }
        }
    }
    BackHandler(session != null && page != "notes") { if(page == "editor") save(true) else page = "notes" }
    val createFile = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/octet-stream")) { uri ->
        val bytes = pendingOutput; pendingOutput = null
        if (uri != null && bytes != null) work({
            activity.contentResolver.openOutputStream(uri, "wt")?.use { it.write(bytes); it.flush() } ?: error("文件无法写入")
        }) {
            recordOutput(pendingName, "所选文件位置")
        }
    }
    val openFile = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if(uri != null) work({ activity.contentResolver.openInputStream(uri)?.use { it.limited(Crypto.MAX_BYTES) } ?: error("无法读取文件") }) {
            incoming = it; incomingSummary = null; dialog = "restore"; restorePassword = ""
        }
    }
    val pickPhoto = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if(uri != null && draft != null) work({ loadPhoto(activity, uri) }) { image ->
            draft?.let { changeDraft(it.copy(images = it.images + image)) }
        }
    }
    fun writeDefault(bytes: ByteArray, name: String) {
        val token = epoch
        scope.launch {
            kotlinx.coroutines.yield()
            if(token != epoch) { bytes.fill(0); return@launch }
            busy = true
            try {
                val location = withContext(Dispatchers.IO) { DownloadsWriter.write(activity, name, bytes) }
                if(token == epoch) recordOutput(name, location)
            } catch(e: Exception) { if(token == epoch) message = "保存失败：${e.message}；可关闭默认目录选项后另选位置" }
            finally { if(token == epoch) busy = false; bytes.fill(0) }
        }
    }
    val storagePermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        val bytes = pendingOutput; pendingOutput = null
        if(bytes != null) {
            if(granted) writeDefault(bytes, pendingName)
            else { pendingOutput = bytes; message = "未授权下载目录，请选择保存位置"; activity.allowPicker(); createFile.launch(pendingName) }
        }
    }
    fun output(bytes: ByteArray, name: String) {
        if(!vault.prefs.defaultDownloads) { pendingOutput = bytes; pendingName = name; activity.allowPicker(); createFile.launch(name) }
        else if(android.os.Build.VERSION.SDK_INT >= 29 || activity.checkSelfPermission(android.Manifest.permission.WRITE_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED) writeDefault(bytes, name)
        else { pendingOutput = bytes; pendingName = name; activity.allowPicker(); storagePermission.launch(android.Manifest.permission.WRITE_EXTERNAL_STORAGE) }
    }
    fun copySecret(text: String) {
        val cm = activity.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        val clip = ClipData.newPlainText("随心记事本敏感内容", text)
        if(android.os.Build.VERSION.SDK_INT >= 33) clip.description.extras = android.os.PersistableBundle().apply { putBoolean("android.content.extra.IS_SENSITIVE", true) }
        cm.setPrimaryClip(clip)
        Handler(Looper.getMainLooper()).postDelayed({ if(cm.primaryClip?.getItemAt(0)?.text?.toString() == text) {
            if(android.os.Build.VERSION.SDK_INT >= 28) cm.clearPrimaryClip() else cm.setPrimaryClip(ClipData.newPlainText("", ""))
        } }, 30_000)
        message = "已复制，30 秒后尝试清除；后台可能受系统限制"
    }
    val prefs = vault.prefs
    val colors = if(prefs.dark) darkColorScheme(primary = accents[prefs.accent], background = Color(0xFF1D1D23), surface = Color(0xFF27272F)) else lightColorScheme(primary = accents[prefs.accent], background = Color(0xFFF6F4EF), surface = Color(0xFFFFFDFA))
    MaterialTheme(colorScheme = colors) {
        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            if(session == null) {
                Column(Modifier.fillMaxSize().safeDrawingPadding().imePadding().padding(28.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.Center) {
                    Text("随心记事本", fontSize = 36.sp, fontWeight = FontWeight.Bold)
                    Text("v${BuildConfig.VERSION_NAME} · 作者 andy", style = MaterialTheme.typography.bodySmall)
                    Text("把日常与秘密，妥帖收藏。", color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(vertical = 12.dp))
                    Spacer(Modifier.height(32.dp))
                    Text(if(store.exists()) "解锁你的笔记" else "创建本地保险库", style = MaterialTheme.typography.titleLarge)
                    SecretField(password, { password = it }, "主密码", Modifier.padding(top = 18.dp))
                    if(!store.exists()) {
                        SecretField(confirmation, { confirmation = it }, "再次输入主密码", Modifier.padding(top = 12.dp))
                        Text("至少 8 个字符。主密码遗忘无法找回，备份仍需要原密码。", style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(vertical = 16.dp))
                    }
                    Button(onClick = {
                        val chars = password.toCharArray(); val create = !store.exists()
                        if(create && (password.length < 8 || password != confirmation)) { message = "主密码至少 8 位，两次输入须一致"; chars.fill('\u0000') }
                        else work({ try { if(create) store.create(chars) else store.open(chars) } finally { chars.fill('\u0000') } }) { s ->
                            session = s; refresh(s); password = ""; confirmation = ""; activity.lastInteraction = SystemClock.elapsedRealtime()
                        }
                    }, enabled = !busy, modifier = Modifier.fillMaxWidth().padding(top = 18.dp)) { Text(if(busy) "正在处理…" else if(store.exists()) "解锁" else "创建保险库") }
                    TextButton(onClick = { activity.allowPicker(); openFile.launch(arrayOf("*/*")) }, enabled = !busy, modifier = Modifier.fillMaxWidth()) { Text("从加密备份恢复") }
                    if(store.previousExists()) TextButton(onClick = { dialog = "previous" }, enabled = !busy) { Text("切换至恢复前的本地版本") }
                    if(message.isNotEmpty()) Text(message, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(vertical = 12.dp))
                    Text("离线使用 · 数据本地加密 · 无需注册", style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 28.dp))
                }
            } else {
                Scaffold(containerColor = MaterialTheme.colorScheme.background, topBar = {
                    Column(Modifier.statusBarsPadding().padding(horizontal = 20.dp, vertical = 12.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            if(page == "editor") IconButton(onClick = { save(true) }, enabled = !busy) { Icon(Icons.Outlined.ArrowBack, "保存并返回") }
                            Text(when(page) { "editor" -> "编辑"; "categories" -> "分类"; "settings" -> "个性化"; "backup" -> "备份与导出"; "me" -> "设置"; "todo" -> "待办"; else -> "笔记" }, style = if(page == "editor") MaterialTheme.typography.titleMedium else MaterialTheme.typography.headlineLarge, modifier = Modifier.weight(1f))
                            if(page == "editor") {
                                IconButton(onClick = { history = history?.undo(System.currentTimeMillis()); draft = history?.current }, enabled = history?.canUndo == true && !busy) { Icon(Icons.Outlined.Undo, "撤销", Modifier.size(22.dp)) }
                                IconButton(onClick = { history = history?.redo(System.currentTimeMillis()); draft = history?.current }, enabled = history?.canRedo == true && !busy) { Icon(Icons.Outlined.Redo, "重做", Modifier.size(22.dp)) }
                            }
                            IconButton(onClick = { lock() }) { Icon(Icons.Outlined.Lock, "锁定保险库") }
                            if(page == "editor") IconButton(onClick = { save(true) }, enabled = !busy) { Icon(Icons.Outlined.Check, "保存并返回") }
                        }
                        if(busy) LinearProgressIndicator(Modifier.fillMaxWidth())
                        if(message.isNotEmpty()) Text(message, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary, modifier = Modifier.clickable { message = "" }.padding(top = 8.dp))
                    }
                }, bottomBar = {
                    if(page != "editor") NavigationBar {
                        listOf(Triple("notes", "笔记", Icons.Outlined.Description), Triple("todo", "待办", Icons.Outlined.Checklist), Triple("me", "设置", Icons.Outlined.Settings)).forEach { (id,label,icon) ->
                            NavigationBarItem(selected = page == id || (id == "me" && page in listOf("categories", "settings", "backup")), onClick = { page = id }, icon = { Icon(icon, label) }, label = { Text(label) })
                        }
                    }
                }, floatingActionButton = {
                    if(page in listOf("notes", "todo")) FloatingActionButton(onClick = { dialog = "new" }) { Icon(Icons.Outlined.Add, "新建记录") }
                }) { padding ->
                    when(page) {
                        "notes", "todo" -> Column(Modifier.padding(padding).padding(horizontal = 16.dp)) {
                            OutlinedTextField(query, { query = it }, placeholder = { Text("搜索已解锁的记录") }, leadingIcon = { Icon(Icons.Outlined.Search, null) }, singleLine = true, modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(18.dp))
                            Row(Modifier.fillMaxWidth().padding(vertical = 10.dp).horizontalScrollCompat(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                FilterChip(selected == null, { selected = null }, label = { Text("全部") })
                                vault.categories.forEach { c -> FilterChip(selected == c.id, {
                                    selected = c.id
                                    if(session?.isOpen(c.id) == false) { categoryTarget = c; categoryPassword = ""; dialog = "unlock" }
                                }, label = { Text((if(c.sealed != null) "▣ " else "") + c.name) }) }
                                AssistChip({ page = "categories" }, label = { Text("管理") })
                            }
                            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                                var sortMenu by remember { mutableStateOf(false) }
                                Box(Modifier.weight(1f)) {
                                    TextButton(onClick = { sortMenu = true }) { Text(NoteOrdering.options[prefs.sort] ?: "排序"); Icon(Icons.Outlined.ArrowDropDown, null) }
                                    DropdownMenu(sortMenu, { sortMenu = false }) { NoteOrdering.options.forEach { (key,label) ->
                                        DropdownMenuItem(text = { Text(label) }, onClick = { sortMenu = false; val s = session!!; work({ s.updatePrefs(prefs.copy(sort = key)) }) { refresh(s) } }, enabled = !busy)
                                    } }
                                }
                                IconButton(onClick = { val s = session!!; work({ s.updatePrefs(prefs.copy(grid = !prefs.grid)) }) { refresh(s) } }, enabled = !busy) { Icon(if(prefs.grid) Icons.Outlined.ViewList else Icons.Outlined.GridView, if(prefs.grid) "切换为单行标题" else "切换为卡片") }
                            }
                            val visible = NoteOrdering.sorted(notes.filter { (selected == null || it.categoryId == selected) && (page != "todo" || it.kind == "todo") && (it.displayTitle() + it.body + it.username).contains(query, ignoreCase = true) }, prefs.sort)
                            if(vault.categories.any { it.sealed != null && session?.isOpen(it.id) == false }) Text("部分分类已锁定，内容不参与搜索", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(bottom = 10.dp))
                            if(visible.isEmpty()) Column(Modifier.fillMaxWidth().padding(top = 70.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                                Icon(Icons.Outlined.EditNote, null, Modifier.size(54.dp), tint = MaterialTheme.colorScheme.primary)
                                Text("留一页给今天", style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(top = 20.dp))
                                Text("点击 ＋，开始笔记、账号或待办", modifier = Modifier.padding(top = 8.dp), color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            if(prefs.grid) LazyVerticalGrid(GridCells.Fixed(2), verticalArrangement = Arrangement.spacedBy(12.dp), horizontalArrangement = Arrangement.spacedBy(12.dp), contentPadding = PaddingValues(bottom = 90.dp)) {
                                items(visible, key = { it.id }) { n -> NoteCard(n, prefs.dark) { edit(n) } }
                            } else LazyColumn(verticalArrangement = Arrangement.spacedBy(12.dp), contentPadding = PaddingValues(bottom = 90.dp)) {
                                items(visible, key = { it.id }) { n -> NoteRow(n) { edit(n) } }
                            }
                        }
                        "editor" -> draft?.let { n ->
                            Editor(n, saveStatus, busy, Modifier.padding(padding), change = ::changeDraft, photo = {
                                if(n.images.size >= 6) message = "每条记录最多 6 张图片"
                                else { activity.allowPicker(); pickPhoto.launch("image/*") }
                            }, copy = ::copySecret, delete = { dialog = "delete" })
                        }
                        "categories" -> LazyColumn(Modifier.padding(padding).padding(horizontal = 20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                            item { Text("分类名与颜色可自由调整；私密分类使用独立密码。", color = MaterialTheme.colorScheme.onSurfaceVariant) }
                            items(vault.categories, key = { it.id }) { c ->
                                Card {
                                    Column(Modifier.padding(16.dp)) {
                                        Row(verticalAlignment = Alignment.CenterVertically) {
                                            Box(Modifier.size(12.dp).background(papers[c.color], RoundedCornerShape(4.dp)))
                                            Text(c.name, Modifier.padding(start = 12.dp).weight(1f), style = MaterialTheme.typography.titleMedium)
                                            TextButton(onClick = { categoryTarget = c; categoryName = c.name; categoryColor = c.color; categoryPrivate = c.sealed != null; dialog = "category" }) { Text("编辑") }
                                        }
                                        if(c.sealed != null) Row {
                                            TextButton(onClick = {
                                                if(session?.isOpen(c.id) == true) { session?.lockCategory(c.id); session?.let(::refresh); message = "分类已锁定" }
                                                else { categoryTarget = c; categoryPassword = ""; dialog = "unlock" }
                                            }) { Text(if(session?.isOpen(c.id) == true) "锁定分类" else "解锁分类") }
                                            Text("独立密码保护", style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(12.dp))
                                        }
                                    }
                                }
                            }
                            item { Button(onClick = { categoryTarget = null; categoryName = ""; categoryColor = 0; categoryPrivate = false; categoryPassword = ""; dialog = "category" }, enabled = !busy) { Text("＋ 自定义分类") } }
                        }
                        "settings" -> Column(Modifier.padding(padding).padding(20.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(18.dp)) {
                            Text("打造你的笔记空间", style = MaterialTheme.typography.titleLarge)
                            SettingSwitch("深色模式", prefs.dark, !busy) { value -> val s = session!!; work({ s.updatePrefs(prefs.copy(dark = value)) }) { refresh(s) } }
                            SettingSwitch("双列卡片（关闭为单行标题）", prefs.grid, !busy) { value -> val s = session!!; work({ s.updatePrefs(prefs.copy(grid = value)) }) { refresh(s) } }
                            Text("主题色")
                            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) { accents.forEachIndexed { index, color ->
                                Button(onClick = { val s = session!!; work({ s.updatePrefs(prefs.copy(accent = index)) }) { refresh(s) } }, enabled = !busy, colors = ButtonDefaults.buttonColors(containerColor = color)) { Text(if(index == prefs.accent) "✓" else " ") }
                            } }
                            Text("新笔记默认字号：${prefs.fontSize}")
                            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) { listOf(15, 17, 20, 24).forEach { size ->
                                FilterChip(size == prefs.fontSize, { val s = session!!; work({ s.updatePrefs(prefs.copy(fontSize = size)) }) { refresh(s) } }, label = { Text("$size") }, enabled = !busy)
                            } }
                            Card { Column(Modifier.padding(20.dp)) { Text("今天值得记下来", fontSize = prefs.fontSize.sp); Text("纸张颜色、粗斜体与字体可以在每篇笔记单独设置。", Modifier.padding(top = 12.dp)) } }
                            Text("贴纸、自建模板与桌面小组件将在后续版本提供。", style = MaterialTheme.typography.bodySmall)
                        }
                        "backup" -> Column(Modifier.padding(padding).padding(20.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                            Card { Column(Modifier.padding(20.dp)) {
                                Text("完整加密备份", style = MaterialTheme.typography.titleLarge)
                                Text("包含所有分类、账号、图片与样式，包括未解锁的私密分类。恢复使用当前主密码；私密分类仍需独立密码。", Modifier.padding(vertical = 12.dp))
                                Text("上次手动备份：$backupTime", style = MaterialTheme.typography.bodySmall)
                                Button(onClick = { val s = session!!; work({ s.backup() }) { output(it, "随心记事本-${System.currentTimeMillis()}.mxbak") } }, enabled = !busy) { Text("立即备份") }
                                TextButton(onClick = { activity.allowPicker(); openFile.launch(arrayOf("*/*")) }, enabled = !busy) { Text("从备份恢复") }
                            } }
                            Card { Column(Modifier.padding(20.dp)) {
                                Text("导出记录", style = MaterialTheme.typography.titleLarge)
                                Text("范围：${selected?.let { id -> vault.categories.find { it.id == id }?.name } ?: "全部已解锁分类"}。锁定分类不会被导出。", Modifier.padding(vertical = 12.dp))
                                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) { listOf("HTML", "TXT", "CSV").forEach { f -> FilterChip(exportFormat == f, { exportFormat = f }, label = { Text(f) }) } }
                                Text(when(exportFormat) { "CSV" -> "仅账号字段，不包含图片"; "TXT" -> "纯文本，不包含图片和样式"; else -> "笔记、账号及图片，可用浏览器打开" }, style = MaterialTheme.typography.bodySmall)
                                SettingSwitch("包含账号密码", exportSecrets, !busy) { exportSecrets = it }
                                Text("导出文件为明文，请妥善保存。", color = MaterialTheme.colorScheme.error)
                                Button(onClick = { password = ""; dialog = "export" }, enabled = !busy) { Text("确认导出范围") }
                            } }
                            SettingSwitch("默认保存到 Download/Notes", prefs.defaultDownloads, !busy) { value -> val s = session!!; work({ s.updatePrefs(prefs.copy(defaultDownloads = value)) }) { refresh(s) } }
                            Text(if(prefs.defaultDownloads) "导出与备份默认保存到 Download/Notes，失败时会明确提示。Android 8/9 首次需要存储授权。" else "每次导出或备份时选择保存位置。", style = MaterialTheme.typography.bodySmall)
                            Text("支持手动备份。建议将重要备份再复制到手机之外，并定期演练恢复。", style = MaterialTheme.typography.bodySmall)
                        }
                        else -> Column(Modifier.padding(padding).padding(20.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                            Text("本地保险库", style = MaterialTheme.typography.headlineSmall)
                            Text("内容默认加密 · 不联网 · 截图保护", color = MaterialTheme.colorScheme.onSurfaceVariant)
                            listOf("分类管理" to "categories", "个性化" to "settings", "备份与导出" to "backup").forEach { (label,target) ->
                                OutlinedButton(onClick = { page = target }, modifier = Modifier.fillMaxWidth()) { Text(label, modifier = Modifier.padding(8.dp)) }
                            }
                            OutlinedButton(onClick = { password = ""; confirmation = ""; dialog = "master" }, modifier = Modifier.fillMaxWidth()) { Text("修改主密码") }
                            Text("随心记事本 ${BuildConfig.VERSION_NAME} · 作者 andy\n默认导出/备份位置：Download/Notes", style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 22.dp))
                        }
                    }
                }
            }
            if(dialog.isNotEmpty()) AlertDialog(onDismissRequest = { if(!busy) clearDialogs() }, title = { Text(when(dialog) {
                "new" -> "记下一点什么"; "category" -> if(categoryTarget == null) "新建分类" else "编辑分类"; "unlock" -> "解锁 ${categoryTarget?.name}";
                "restore" -> "恢复加密备份"; "export" -> "确认明文导出"; "delete" -> "删除记录？"; "master" -> "修改主密码"; else -> "切换本地版本？"
            }) }, text = {
                Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    when(dialog) {
                        "new" -> {
                            Text("保存到：${selected?.let { id -> vault.categories.find { it.id == id }?.name } ?: "随手记"}")
                            listOf("note" to "空白笔记", "account" to "账号卡片", "todo" to "待办清单").forEach { (kind,label) ->
                                OutlinedButton(onClick = {
                                    val c = selected?.let { id -> vault.categories.find { it.id == id } } ?: vault.categories.first { it.sealed == null }
                                    if(session?.isOpen(c.id) != true) { categoryTarget = c; dialog = "unlock"; categoryPassword = "" }
                                    else { clearDialogs(); edit(Note(categoryId = c.id, kind = kind, fontSize = prefs.fontSize, body = if(kind == "todo") "☐ " else "")) }
                                }, modifier = Modifier.fillMaxWidth()) { Text(label) }
                            }
                        }
                        "category" -> {
                            OutlinedTextField(categoryName, { if(it.length <= 40) categoryName = it }, label = { Text("分类名称") }, singleLine = true)
                            ColorChoices(categoryColor) { categoryColor = it }
                            if(categoryTarget == null) {
                                SettingSwitch("独立密码加密", categoryPrivate, !busy) { categoryPrivate = it }
                                if(categoryPrivate) SecretField(categoryPassword, { categoryPassword = it }, "分类密码（至少 8 位）")
                                Text("主密码不能代替分类密码，遗忘将无法恢复该分类。", style = MaterialTheme.typography.bodySmall)
                            } else Text("首版暂不支持修改已有分类的加密方式。", style = MaterialTheme.typography.bodySmall)
                        }
                        "unlock" -> { SecretField(categoryPassword, { categoryPassword = it }, "独立分类密码"); Text("主密码无法解锁此分类。") }
                        "master" -> { SecretField(password, { password = it }, "新主密码（至少 8 位）"); SecretField(confirmation, { confirmation = it }, "再次输入"); Text("旧备份仍使用备份时的旧主密码。") }
                        "restore" -> {
                            Text("恢复后会切换至备份内容。当前库保留为一个本地回退版本；连续恢复会更新回退版本。")
                            SecretField(restorePassword, { restorePassword = it; incomingSummary = null }, "备份时的主密码")
                            incomingSummary?.let { Text("校验通过：${it.categories.size} 个分类，${it.notes.size} 条普通记录，${it.categories.count { c -> c.sealed != null }} 个独立加密分类（不展示条目数）。") }
                            Text("加密分类仍需要备份时的分类密码。", style = MaterialTheme.typography.bodySmall)
                        }
                        "export" -> {
                            val exportNotes = notes.filter { selected == null || it.categoryId == selected }.filter { exportFormat != "CSV" || it.kind == "account" }
                            Text("$exportFormat · ${exportNotes.size} 条记录\n${if(exportSecrets) "包含密码，输出为明文" else "账号密码已隐藏"}")
                            val locked = vault.categories.filter { it.sealed != null && session?.isOpen(it.id) == false }
                            if(locked.isNotEmpty()) Text("未包含的锁定分类：${locked.joinToString { it.name }}", color = MaterialTheme.colorScheme.error)
                            if(exportSecrets) SecretField(password, { password = it }, "再次输入主密码以确认")
                        }
                        "delete" -> Text("删除后首版无法撤销，请先备份重要内容。")
                        else -> Text("将切换至上次恢复前的加密文件；需要该版本对应的主密码。")
                    }
                    if(message.isNotEmpty()) Text(message, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                }
            }, confirmButton = {
                if(dialog != "new") TextButton(enabled = !busy, onClick = {
                    when(dialog) {
                        "category" -> {
                            if(categoryPrivate && categoryTarget == null && categoryPassword.length < 8) message = "分类密码至少 8 位"
                            else {
                                val s = session!!; val chars = if(categoryPrivate && categoryTarget == null) categoryPassword.toCharArray() else null
                                val name = categoryName; val color = categoryColor; val id = categoryTarget?.id
                                work({ try { s.category(name, color, chars, id) } finally { chars?.fill('\u0000') } }) { refresh(s); clearDialogs() }
                            }
                        }
                        "unlock" -> { val s = session!!; val id = categoryTarget!!.id; val chars = categoryPassword.toCharArray()
                            work({ try { s.unlockCategory(id, chars) } finally { chars.fill('\u0000') } }) { refresh(s); clearDialogs(); page = "notes"; selected = id }
                        }
                        "master" -> {
                            if(password.length < 8 || password != confirmation) message = "密码至少 8 位，且两次输入一致"
                            else { val s = session!!; val chars = password.toCharArray(); work({ try { s.changeMaster(chars) } finally { chars.fill('\u0000') } }) { clearDialogs(); message = "主密码已修改，请重新备份" } }
                        }
                        "restore" -> {
                            val bytes = incoming ?: return@TextButton; val chars = restorePassword.toCharArray()
                            if(incomingSummary == null) work({ try { val plain = Crypto.decrypt(bytes, chars); try { Vault.read(plain) } finally { plain.fill(0) } } finally { chars.fill('\u0000') } }) { incomingSummary = it }
                            else work({ try { store.install(bytes, chars) } finally { chars.fill('\u0000') } }) { s ->
                                session?.close(); session = s; refresh(s); draft = null; savedDraft = null; history = null; returnAfterSave = false; selected = null; query = ""; clearDialogs(); page = "notes"; message = "已恢复；原库可在锁屏页切换回来"
                            }
                        }
                        "export" -> {
                            val s = session!!; val list = notes.filter { selected == null || it.categoryId == selected }; val secret = exportSecrets; val fmt = exportFormat; val chars = password.toCharArray()
                            work({ try {
                                if(secret) s.validateMaster(chars)
                                when(fmt) { "CSV" -> Exports.csv(list, secret); "TXT" -> Exports.text(list, secret); else -> Exports.html(list, secret) }.toByteArray(Charsets.UTF_8)
                            } finally { chars.fill('\u0000') } }) { bytes -> clearDialogs(); output(bytes, "随心记事本导出-${System.currentTimeMillis()}.${fmt.lowercase()}") }
                        }
                        "delete" -> { val s = session!!; val n = draft!!; work({ s.deleteNote(n) }) { refresh(s); draft = null; savedDraft = null; history = null; returnAfterSave = false; page = "notes"; clearDialogs() } }
                        else -> work({ store.swapPrevious() }) { clearDialogs(); message = "已切换，请输入该版本主密码" }
                    }
                }) { Text(if(busy) "处理中…" else if(dialog == "restore" && incomingSummary == null) "校验并预览" else "确认") }
            }, dismissButton = { TextButton(onClick = { clearDialogs() }, enabled = !busy) { Text("取消") } })
        }
    }
}

@Composable private fun SecretField(value: String, change: (String) -> Unit, label: String, modifier: Modifier = Modifier) {
    var reveal by remember { mutableStateOf(false) }
    OutlinedTextField(value, { if(it.length <= 1000) change(it) }, modifier.fillMaxWidth(), label = { Text(label) }, singleLine = true,
        visualTransformation = if(reveal) VisualTransformation.None else PasswordVisualTransformation(),
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
        trailingIcon = { IconButton(onClick = { reveal = !reveal }) { Icon(if(reveal) Icons.Outlined.VisibilityOff else Icons.Outlined.Visibility, if(reveal) "隐藏密码" else "显示密码") } })
}
@Composable private fun SettingSwitch(label: String, checked: Boolean, enabled: Boolean = true, change: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) { Text(label, Modifier.weight(1f)); Switch(checked, change, enabled = enabled) }
}
@Composable private fun ColorChoices(value: Int, change: (Int) -> Unit) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(5.dp)) { papers.forEachIndexed { index, color ->
        Button(onClick = { change(index) }, colors = ButtonDefaults.buttonColors(containerColor = color, contentColor = Color(0xFF30303C)), contentPadding = PaddingValues(0.dp), modifier = Modifier.weight(1f).height(48.dp)) { Text(if(index == value) "✓" else "${index+1}") }
    } }
}
@Composable private fun NoteCard(note: Note, dark: Boolean, onClick: () -> Unit) {
    Card(onClick, shape = RoundedCornerShape(18.dp), colors = CardDefaults.cardColors(containerColor = if(dark) Color(0xFF30303A) else papers[note.paper])) {
        Text(note.displayTitle(), fontSize = 18.sp, fontWeight = FontWeight.Medium, maxLines = 2, overflow = TextOverflow.Ellipsis,
            modifier = Modifier.fillMaxWidth().heightIn(min = 96.dp).padding(16.dp))
    }
}
@Composable private fun NoteRow(note: Note, onClick: () -> Unit) {
    Surface(onClick = onClick, shape = RoundedCornerShape(10.dp)) {
        Text(note.displayTitle(), maxLines = 1, overflow = TextOverflow.Ellipsis, fontSize = 17.sp,
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 17.dp))
    }
}
@Composable private fun CompactMenu(label: String, choices: List<Pair<String,String>>, selected: String, change: (String) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        TextButton(onClick = { expanded = true }, contentPadding = PaddingValues(horizontal = 7.dp)) {
            Text(label, maxLines = 1); Icon(Icons.Outlined.ArrowDropDown, null, Modifier.size(16.dp))
        }
        DropdownMenu(expanded, { expanded = false }) {
            choices.forEach { (value,title) -> DropdownMenuItem(text = { Text((if(value == selected) "✓ " else "") + title) }, onClick = { expanded = false; change(value) }) }
        }
    }
}
@Composable private fun Editor(note: Note, status: String, busy: Boolean, modifier: Modifier, change: (Note) -> Unit, photo: () -> Unit, copy: (String) -> Unit, delete: () -> Unit) {
    Column(modifier.fillMaxSize().imePadding().padding(horizontal = 16.dp)) {
        Row(Modifier.fillMaxWidth().horizontalScrollCompat(), verticalAlignment = Alignment.CenterVertically) {
            CompactMenu(when(note.font) { "serif" -> "衬线"; "mono" -> "等宽"; else -> "默认字体" }, listOf("system" to "默认字体", "serif" to "衬线", "mono" to "等宽"), note.font) { change(note.copy(font = it)) }
            CompactMenu("${note.fontSize}", (12..28).map { it.toString() to "$it 号" }, note.fontSize.toString()) { change(note.copy(fontSize = it.toInt())) }
            val style = if(note.bold && note.italic) "both" else if(note.bold) "bold" else if(note.italic) "italic" else "normal"
            CompactMenu(when(style) { "bold" -> "粗体"; "italic" -> "斜体"; "both" -> "粗斜体"; else -> "默认样式" }, listOf("normal" to "默认样式", "bold" to "粗体", "italic" to "斜体", "both" to "粗斜体"), style) { change(note.copy(bold = it == "bold" || it == "both", italic = it == "italic" || it == "both")) }
            IconButton(onClick = photo, enabled = !busy) { Icon(Icons.Outlined.AddPhotoAlternate, "插入图片", Modifier.size(22.dp)) }
            var more by remember { mutableStateOf(false) }
            Box {
                IconButton(onClick = { more = true }) { Icon(Icons.Outlined.MoreVert, "更多操作") }
                DropdownMenu(more, { more = false }) {
                    DropdownMenuItem(text = { Text(if(note.favorite) "取消置顶" else "置顶") }, onClick = { more = false; change(note.copy(favorite = !note.favorite)) })
                    DropdownMenuItem(text = { Text("插入待办") }, onClick = { more = false; change(note.copy(body = note.body + "\n☐ ")) })
                    listOf("暖黄", "淡紫", "浅绿", "淡粉", "浅蓝").forEachIndexed { i,label -> DropdownMenuItem(text = { Text("${if(note.paper == i) "✓ " else ""}卡片：$label") }, onClick = { more = false; change(note.copy(paper = i)) }) }
                    DropdownMenuItem(text = { Text("删除记录", color = MaterialTheme.colorScheme.error) }, onClick = { more = false; delete() }, enabled = !busy)
                }
            }
        }
        if(status.isNotBlank()) Text(status, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
        BoxWithConstraints(Modifier.weight(1f).fillMaxWidth()) {
            val minimumBody = (maxHeight - 90.dp).coerceAtLeast(160.dp)
            Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(top = 12.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                BasicTextField(note.title, { if(it.length <= 300) change(note.copy(title = it)) }, modifier = Modifier.fillMaxWidth(), singleLine = true,
                    textStyle = MaterialTheme.typography.titleLarge.copy(color = MaterialTheme.colorScheme.onSurface),
                    decorationBox = { inner -> Box { if(note.title.isEmpty()) Text("标题（留空取正文第一行）", color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.titleMedium); inner() } })
                if(note.kind == "account") {
                    OutlinedTextField(note.username, { if(it.length <= 1000) change(note.copy(username = it)) }, label = { Text("用户名 / 邮箱") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
                    SecretField(note.password, { change(note.copy(password = it)) }, "账号密码")
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        TextButton(onClick = { val r = SecureRandom(); val alphabet = "ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnopqrstuvwxyz23456789!@#%*-_"; change(note.copy(password = (1..20).map { alphabet[r.nextInt(alphabet.length)] }.joinToString(""))) }) { Text("生成密码") }
                        TextButton(onClick = { copy(note.password) }) { Text("复制密码") }
                    }
                    OutlinedTextField(note.url, { if(it.length <= 1000) change(note.copy(url = it)) }, label = { Text("网址") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
                }
                BasicTextField(note.body, { if(it.length <= 100_000) change(note.copy(body = it)) },
                    modifier = Modifier.fillMaxWidth().heightIn(min = if(note.kind == "account") 200.dp else minimumBody),
                    textStyle = LocalTextStyle.current.copy(color = MaterialTheme.colorScheme.onSurface, fontSize = note.fontSize.sp, fontFamily = family(note.font), fontWeight = if(note.bold) FontWeight.Bold else FontWeight.Normal, fontStyle = if(note.italic) FontStyle.Italic else FontStyle.Normal),
                    decorationBox = { inner -> Box { if(note.body.isEmpty()) Text(if(note.kind == "account") "补充说明…" else "开始记录…", color = MaterialTheme.colorScheme.onSurfaceVariant); inner() } })
                note.images.forEachIndexed { index, encoded ->
                    val bitmap = remember(encoded) { try { val data = Base64.getDecoder().decode(encoded); BitmapFactory.decodeByteArray(data, 0, data.size) } catch(_: Exception) { null } }
                    Box(Modifier.fillMaxWidth()) {
                        if(bitmap != null) Image(bitmap.asImageBitmap(), "笔记图片 ${index+1}", Modifier.fillMaxWidth().heightIn(max = 220.dp))
                        IconButton(onClick = { change(note.copy(images = note.images.filterIndexed { i, _ -> i != index })) }, modifier = Modifier.align(Alignment.TopEnd).background(MaterialTheme.colorScheme.surface, RoundedCornerShape(24.dp))) { Icon(Icons.Outlined.Close, "移除图片 ${index+1}") }
                    }
                }
                Spacer(Modifier.height(16.dp))
            }
        }
    }
}
@Composable private fun Modifier.horizontalScrollCompat(): Modifier = this.then(Modifier.horizontalScroll(rememberScrollState()))
