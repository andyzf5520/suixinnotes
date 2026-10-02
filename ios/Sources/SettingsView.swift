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
    @State private var output: TransferFile?
    @State private var exporting = false
    @State private var filename = ""
    @State private var importing = false
    @State private var restorePrompt = false
    @State private var incoming: Data?
    @State private var restorePassword = ""
    var body: some View {
        Form {
            Section("关于") { Text("随心记事本"); Text("版本 1.0.0 · 作者 andy"); Text("iOS 原生版 · 本地加密 · 无需注册").font(.caption) }
            Section("个性化") {
                Toggle("深色模式", isOn: Binding(get: { store.vault.prefs.dark }, set: { value in var p = store.vault.prefs; p.dark = value; store.run { try await store.setPreferences(p) } }))
                Toggle("双列卡片", isOn: Binding(get: { store.vault.prefs.grid }, set: { value in var p = store.vault.prefs; p.grid = value; store.run { try await store.setPreferences(p) } }))
            }
            Section("分类") {
                ForEach(store.vault.categories) { c in HStack { Text(c.name); Spacer(); if c.sealed != nil { Button(store.isOpen(c) ? "锁定" : "已锁定") { store.lockCategory(c) }.disabled(!store.isOpen(c)) } } }
                TextField("新分类名称", text: $categoryName)
                SecureField("独立密码（留空为普通分类）", text: $categoryPassword)
                Button("新建分类") { let n = categoryName, p = categoryPassword; store.run { try await store.addCategory(n, password: p); categoryName = ""; categoryPassword = "" } }
                Text("分类密码至少 8 位，主密码不能替代。遗忘无找回。 ").font(.caption)
            }
            Section("加密备份") {
                Button("导出完整加密备份") { do { output = TransferFile(data: try store.backup()); filename = "随心记事本-\(Int(Date().timeIntervalSince1970)).mxbak"; exporting = true } catch { store.error = error.localizedDescription } }
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
        .alert("恢复并切换保险库", isPresented: $restorePrompt) {
            SecureField("备份主密码", text: $restorePassword)
            Button("校验并恢复") { let data = incoming, p = restorePassword; incoming = nil; restorePassword = ""; if let data { store.run { try await store.restore(data, password: p) } } }
            Button("取消", role: .cancel) { incoming = nil; restorePassword = "" }
        } message: { Text("原库保留为一个本地回退文件；连续恢复会更新它。") }
        .alert("导出文件为明文", isPresented: $confirm) {
            if secrets { SecureField("主密码", text: $master) }
            Button("导出") {
                let password = master, include = secrets, f = format, notes = store.notes; master = ""
                store.run { if include { try await store.confirmPassword(password) }; output = TransferFile(data: Exports.data(notes, format: f, passwords: include)); filename = "随心记事本-\(Int(Date().timeIntervalSince1970)).\(f.lowercased())"; exporting = true }
            }
            Button("取消", role: .cancel) { master = "" }
        } message: { Text("锁定分类不会导出；密码默认排除。请保护好导出文件。") }
    }
}
