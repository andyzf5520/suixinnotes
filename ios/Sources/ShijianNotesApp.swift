import SwiftUI

@main struct ShijianNotesApp: App {
    @StateObject private var store = NoteStore()
    @Environment(\.scenePhase) private var scenePhase
    var body: some Scene {
        WindowGroup {
            RootView(store: store)
                .preferredColorScheme(store.vault.prefs.dark ? .dark : .light)
                .overlay { if scenePhase != .active { Color(.systemBackground).ignoresSafeArea().overlay(Text("随心记事本").font(.title)) } }
                .onChange(of: scenePhase) { phase in if phase == .background { Task { await store.flushAndLock() } } }
        }
    }
}

struct TransferFile: FileDocument {
    static var readableContentTypes: [UTType] { [.data] }
    var data: Data
    init(data: Data) { self.data = data }
    init(configuration: ReadConfiguration) throws { data = configuration.file.regularFileContents ?? Data() }
    func fileWrapper(configuration: WriteConfiguration) throws -> FileWrapper { FileWrapper(regularFileWithContents: data) }
}

import UniformTypeIdentifiers

struct RootView: View {
    @ObservedObject var store: NoteStore
    @State private var password = ""
    @State private var confirmation = ""
    @State private var restoreData: Data?
    @State private var importing = false
    @State private var restorePrompt = false
    @State private var restorePassword = ""
    var body: some View {
        Group {
            if store.unlocked { MainView(store: store) }
            else {
                VStack(alignment: .leading, spacing: 20) {
                    Text("随心记事本").font(.largeTitle.bold())
                    Text("v1.0.0 · 作者 andy").font(.caption).foregroundStyle(.secondary)
                    Text("把日常与秘密，妥帖收藏。")
                    SecureField("主密码", text: $password).textFieldStyle(.roundedBorder)
                    if !store.exists { SecureField("再次输入主密码", text: $confirmation).textFieldStyle(.roundedBorder); Text("至少 8 个字符，遗忘无法找回。独立分类仍需要自己的密码。").font(.caption) }
                    Button(store.exists ? "解锁" : "创建保险库") {
                        let p = password
                        if !store.exists && (p.count < 8 || p != confirmation) { store.error = "密码至少 8 位且两次输入一致"; return }
                        store.run { if store.exists { try await store.open(p) } else { try await store.create(p) }; password = ""; confirmation = "" }
                    }.buttonStyle(.borderedProminent).disabled(store.busy)
                    Button("从加密备份恢复") { importing = true }.disabled(store.busy)
                    if store.busy { ProgressView() }
                    if !store.error.isEmpty { Text(store.error).foregroundStyle(.red).font(.caption) }
                    Text("离线 · 无需注册 · 本地加密").font(.caption).foregroundStyle(.secondary)
                }.padding(28)
            }
        }
        .fileImporter(isPresented: $importing, allowedContentTypes: [.data, .item]) { result in
            do { let url = try result.get(); let access = url.startAccessingSecurityScopedResource(); defer { if access { url.stopAccessingSecurityScopedResource() } }
                let size = try url.resourceValues(forKeys: [.fileSizeKey]).fileSize ?? 0
                guard size <= VaultCrypto.maxBytes else { throw VaultError.tooLarge }
                restoreData = try Data(contentsOf: url); restorePassword = ""; restorePrompt = true
            } catch { store.error = error.localizedDescription }
        }
        .alert("恢复备份", isPresented: $restorePrompt) {
            SecureField("备份时的主密码", text: $restorePassword)
            Button("校验并恢复") { let data = restoreData, p = restorePassword; restoreData = nil; restorePassword = ""; if let data { store.run { try await store.restore(data, password: p) } } }
            Button("取消", role: .cancel) { restoreData = nil; restorePassword = "" }
        } message: { Text("原库保留一个本地回退文件，恢复后私密分类仍需原分类密码。") }
    }
}

struct MainView: View {
    @ObservedObject var store: NoteStore
    @State private var category: String?
    @State private var query = ""
    @State private var editing: Note?
    @State private var createPrompt = false
    @State private var unlockTarget: Category?
    @State private var domainPassword = ""
    @State private var unlockPrompt = false
    private var filtered: [Note] {
        let items = store.notes.filter { (category == nil || $0.categoryId == category) && (query.isEmpty || ($0.displayTitle + $0.body + $0.username).localizedCaseInsensitiveContains(query)) }
        return items.sorted { a,b in
            if a.favorite != b.favorite { return a.favorite }
            switch store.vault.prefs.sort {
            case "updated_asc": return a.updated < b.updated
            case "title_asc": return a.displayTitle.localizedCompare(b.displayTitle) == .orderedAscending
            case "title_desc": return a.displayTitle.localizedCompare(b.displayTitle) == .orderedDescending
            default: return a.updated > b.updated
            }
        }
    }
    var body: some View {
        TabView {
            NavigationStack {
                VStack(spacing: 12) {
                    ScrollView(.horizontal, showsIndicators: false) { HStack {
                        Button("全部") { category = nil }.buttonStyle(.bordered)
                        ForEach(store.vault.categories) { c in Button((c.sealed == nil ? "" : "🔒 ") + c.name) { category = c.id; if !store.isOpen(c) { unlockTarget = c; domainPassword = ""; unlockPrompt = true } }.buttonStyle(.bordered) }
                    }.padding(.horizontal) }
                    HStack {
                        Menu("排序") { ForEach(["updated_desc":"最新在前","updated_asc":"最早在前","title_asc":"标题升序","title_desc":"标题降序"].sorted(by: { $0.key < $1.key }), id: \.key) { item in Button(item.value) { var p = store.vault.prefs; p.sort = item.key; store.run { try await store.setPreferences(p) } } } }
                        Spacer()
                        Button { var p = store.vault.prefs; p.grid.toggle(); store.run { try await store.setPreferences(p) } } label: { Image(systemName: store.vault.prefs.grid ? "list.bullet" : "square.grid.2x2") }.accessibilityLabel("切换布局")
                    }.padding(.horizontal).disabled(store.busy)
                    ScrollView {
                        LazyVGrid(columns: Array(repeating: GridItem(.flexible()), count: store.vault.prefs.grid ? 2 : 1), spacing: 12) {
                            ForEach(filtered) { n in Button { editing = n } label: {
                                Text(n.displayTitle).font(.headline).lineLimit(store.vault.prefs.grid ? 2 : 1).frame(maxWidth: .infinity, minHeight: store.vault.prefs.grid ? 80 : 38, alignment: .leading).padding(12).background(Color.orange.opacity(0.10), in: RoundedRectangle(cornerRadius: 14))
                            }.buttonStyle(.plain) }
                        }.padding()
                        if filtered.isEmpty { Text("点击 ＋ 开始记录").foregroundStyle(.secondary).padding(50) }
                    }
                    if store.vault.categories.contains(where: { !store.isOpen($0) }) { Text("锁定分类内容不参与搜索").font(.caption).foregroundStyle(.secondary) }
                    if !store.error.isEmpty { Text(store.error).font(.caption).foregroundStyle(.red) }
                }.navigationTitle("笔记").searchable(text: $query, prompt: "搜索已解锁记录")
                .toolbar { ToolbarItem(placement: .navigationBarTrailing) { Button { Task { await store.flushAndLock() } } label: { Image(systemName: "lock") } }
                    ToolbarItem(placement: .bottomBar) { Button { createPrompt = true } label: { Label("新建", systemImage: "plus") } }
                }
            }.tabItem { Label("笔记", systemImage: "note.text") }
            NavigationStack { SettingsView(store: store) }.tabItem { Label("设置", systemImage: "gearshape") }
        }
        .sheet(item: $editing) { note in NoteEditor(store: store, initial: note) }
        .confirmationDialog("新建记录", isPresented: $createPrompt, titleVisibility: .visible) {
            Button("文本笔记") { create("note") }; Button("账号卡片") { create("account") }; Button("待办") { create("todo") }
        }
        .alert("解锁分类", isPresented: $unlockPrompt) {
            SecureField("独立分类密码", text: $domainPassword)
            Button("解锁") { let p = domainPassword; domainPassword = ""; if let c = unlockTarget { store.run { try await store.unlockCategory(c, password: p) } } }
            Button("取消", role: .cancel) { domainPassword = ""; unlockTarget = nil }
        } message: { Text("主密码不能代替分类密码。") }
    }
    private func create(_ kind: String) {
        guard let c = store.vault.categories.first(where: { $0.id == category }) ?? store.vault.categories.first(where: { $0.sealed == nil }) else { return }
        if !store.isOpen(c) { unlockTarget = c; unlockPrompt = true; return }
        var n = Note(categoryId: c.id); n.kind = kind; n.fontSize = store.vault.prefs.fontSize; if kind == "todo" { n.body = "☐ " }; editing = n
    }
}
