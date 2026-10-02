import SwiftUI
import UniformTypeIdentifiers

struct SettingsView: View {
    @ObservedObject var store: NoteStore
    @State private var categoryName = ""
    @State private var categoryPassword = ""
    @State private var format = "HTML"
    @State private var secrets = false
    @State private var confirm = false
    @State private var master = ""
    @State private var boxPrompt = false
    @State private var oldBoxPassword = ""
    @State private var boxPassword = ""
    @State private var boxConfirmation = ""
    @State private var backupPrompt = false
    @State private var backupPassword = ""
    @State private var backupConfirmation = ""
    @State private var output: TransferFile?
    @State private var exporting = false
    @State private var filename = ""
    @State private var importing = false
    @State private var restorePrompt = false
    @State private var incoming: Data?
    @State private var restorePassword = ""
    var body: some View {
        Form {
            Section { NavigationLink("关于") {
                Form {
                    Section { Text("随心记事本"); Text("版本 \(Bundle.main.infoDictionary?["CFBundleShortVersionString"] as? String ?? "1.0.0")"); Text("作者 andy") }
                    Section("GitHub 仓库") { Text("andyzf5520/suixinnotes"); Link("打开 GitHub 仓库", destination: URL(string: "https://github.com/andyzf5520/suixinnotes")!) }
                }.navigationTitle("关于")
            } }
            Section("个性化") {
                Toggle("深色模式", isOn: Binding(get: { store.vault.prefs.dark }, set: { value in var p = store.vault.prefs; p.dark = value; store.run { try await store.setPreferences(p) } }))
                Toggle("双列卡片", isOn: Binding(get: { store.vault.prefs.grid }, set: { value in var p = store.vault.prefs; p.grid = value; store.run { try await store.setPreferences(p) } }))
            }
            Section("私密") {
                Button(store.vault.categories.contains(where: { $0.id == NoteStore.boxID }) ? "修改私密密码" : "首次设置私密密码") { oldBoxPassword = ""; boxPassword = ""; boxConfirmation = ""; boxPrompt = true }
                Text("普通笔记直接进入，只有私密内容需要独立密码。密码遗忘无法找回。").font(.caption)
            }
            Section("加密备份") {
                Button("导出完整加密备份") { backupPassword = ""; backupConfirmation = ""; backupPrompt = true }
                Button("从备份恢复") { importing = true }
                Text("包含未解锁分类；恢复仍需各分类密码。").font(.caption)
            }
            Section("导出") {
                Picker("格式", selection: $format) { ForEach(["HTML","TXT","CSV"], id: \.self) { Text($0) } }
                Toggle("包含账号密码", isOn: $secrets)
                Button("确认明文导出") { master = ""; confirm = true }
                Text("仅导出已解锁记录。iOS 使用系统文件位置选择器；建议选择“文件 / 下载 / Notes”，不保证 Android 的 Download/Notes 路径。").font(.caption)
            }
            if !store.error.isEmpty { Section { Text(store.error).foregroundStyle(.red) } }
        }.navigationTitle("设置").disabled(store.busy)
        .fileExporter(isPresented: $exporting, document: output, contentType: .data, defaultFilename: filename) { result in
            switch result { case .success: store.error = "文件已保存到所选位置"; case .failure(let error): store.error = error.localizedDescription }; output = nil
        }
        .fileImporter(isPresented: $importing, allowedContentTypes: [.data, .item]) { result in
            do { let url = try result.get(); let access = url.startAccessingSecurityScopedResource(); defer { if access { url.stopAccessingSecurityScopedResource() } }
                guard (try url.resourceValues(forKeys: [.fileSizeKey]).fileSize ?? 0) <= VaultCrypto.maxBytes else { throw VaultError.tooLarge }
                incoming = try Data(contentsOf: url); restorePassword = ""; restorePrompt = true
            } catch { store.error = error.localizedDescription }
        }
        .alert("设置私密密码", isPresented: $boxPrompt) {
            if store.vault.categories.contains(where: { $0.id == NoteStore.boxID }) { SecureField("原私密密码", text: $oldBoxPassword) }
            SecureField("私密密码（至少 8 位）", text: $boxPassword)
            SecureField("再次输入", text: $boxConfirmation)
            Button("保存") {
                let old = oldBoxPassword, next = boxPassword, match = boxConfirmation; oldBoxPassword = ""; boxPassword = ""; boxConfirmation = ""
                guard next.count >= 8, next == match else { store.error = "密码至少 8 位且两次输入一致"; return }
                store.run { try await store.setBoxPassword(old: old, next: next) }
            }
            Button("取消", role: .cancel) { oldBoxPassword = ""; boxPassword = ""; boxConfirmation = "" }
        } message: { Text("旧备份中的私密仍使用备份时的私密密码。") }
        .alert("设置备份密码", isPresented: $backupPrompt) {
            SecureField("备份密码（至少 8 位）", text: $backupPassword)
            SecureField("再次输入", text: $backupConfirmation)
            Button("备份") {
                let p = backupPassword, match = backupConfirmation; backupPassword = ""; backupConfirmation = ""
                guard p.count >= 8, p == match else { store.error = "密码至少 8 位且两次输入一致"; return }
                store.run { output = TransferFile(data: try await store.portableBackup(password: p)); filename = "随心记事本-\(Int(Date().timeIntervalSince1970)).mxbak"; exporting = true }
            }
            Button("取消", role: .cancel) { backupPassword = ""; backupConfirmation = "" }
        } message: { Text("换设备恢复使用此备份密码；私密内容仍需自己的密码。") }
        .alert("恢复并切换保险库", isPresented: $restorePrompt) {
            SecureField("备份密码", text: $restorePassword)
            Button("校验并恢复") { let data = incoming, p = restorePassword; incoming = nil; restorePassword = ""; if let data { store.run { try await store.restore(data, password: p) } } }
            Button("取消", role: .cancel) { incoming = nil; restorePassword = "" }
        } message: { Text("原库保留为一个本地回退文件；连续恢复会更新它。") }
        .alert("导出文件为明文", isPresented: $confirm) {
            if store.notes.contains(where: { $0.categoryId == NoteStore.boxID }) { SecureField("私密密码", text: $master) }
            Button("导出") {
                let password = master, include = secrets, f = format, notes = store.notes; master = ""
                store.run { if notes.contains(where: { $0.categoryId == NoteStore.boxID }), let c = store.vault.categories.first(where: { $0.id == NoteStore.boxID }) { try await store.unlockCategory(c, password: password) }; output = TransferFile(data: Exports.data(notes, format: f, passwords: include)); filename = "随心记事本-\(Int(Date().timeIntervalSince1970)).\(f.lowercased())"; exporting = true }
            }
            Button("取消", role: .cancel) { master = "" }
        } message: { Text("锁定分类不会导出；密码默认排除。请保护好导出文件。") }
    }
}
