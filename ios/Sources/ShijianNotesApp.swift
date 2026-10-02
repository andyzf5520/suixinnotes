import SwiftUI

@main struct ShijianNotesApp: App {
    @StateObject private var store = NoteStore()
    @Environment(\.scenePhase) private var scenePhase
    var body: some Scene {
        WindowGroup {
            RootView(store: store)
                .preferredColorScheme(store.vault.prefs.dark ? .dark : .light)
                .overlay { if scenePhase != .active { Color(.systemBackground).ignoresSafeArea().overlay(Text("随心记事本").font(.title)) } }
                .onChange(of: scenePhase) { phase in if phase == .background { Task { await store.flushAndLock() } } else if phase == .active { Task { while store.busy { try? await Task.sleep(nanoseconds: 50_000_000) }; store.run { try await store.autoOpen() } } } }
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
                    Text("记录生活，安心保存").font(.caption).foregroundStyle(.secondary)
                    Text("把日常与秘密，妥帖收藏。")
                    if store.legacyMigration {
                        SecureField("原密码（旧版首次迁移）", text: $password).textFieldStyle(.roundedBorder)
                        Button("迁移旧版数据") { let p = password; store.run { try await store.open(p); password = "" } }.buttonStyle(.borderedProminent).disabled(store.busy)
                    } else {
                        Text("正在准备本地笔记")
                        if !store.busy { Button("重试自动打开") { store.run { try await store.autoOpen() } } }
                    }
                    Button("从加密备份恢复") { importing = true }.disabled(store.busy)
                    if store.busy { ProgressView() }
                    if !store.error.isEmpty { Text(store.error).foregroundStyle(.red).font(.caption) }
                    Text("离线 · 无需注册 · 本地加密").font(.caption).foregroundStyle(.secondary)
                }.padding(28)
            }
        }
.task { store.run { try await store.autoOpen() } }
        .fileImporter(isPresented: $importing, allowedContentTypes: [.data, .item]) { result in
            do { let url = try result.get(); let access = url.startAccessingSecurityScopedResource(); defer { if access { url.stopAccessingSecurityScopedResource() } }
                let size = try url.resourceValues(forKeys: [.fileSizeKey]).fileSize ?? 0
                guard size <= VaultCrypto.maxBytes else { throw VaultError.tooLarge }
                restoreData = try Data(contentsOf: url); restorePassword = ""; restorePrompt = true
            } catch { store.error = error.localizedDescription }
        }
        .alert("恢复备份", isPresented: $restorePrompt) {
            SecureField("备份密码", text: $restorePassword)
            Button("校验并恢复") { let data = restoreData, p = restorePassword; restoreData = nil; restorePassword = ""; if let data { store.run { try await store.restore(data, password: p) } } }
            Button("取消", role: .cancel) { restoreData = nil; restorePassword = "" }
        } message: { Text("原库保留一个本地回退文件，恢复后私密分类仍需原分类密码。") }
    }
}

struct MainView: View {
    @ObservedObject var store: NoteStore
    @State private var sidebar = false
    @State private var categoryPrompt = false
    @State private var categoryName = ""
    @State private var category: String?
    @State private var query = ""
    @State private var editing: Note?
    @State private var createPrompt = false
    @State private var unlockTarget: Category?
    @State private var domainPassword = ""
    @State private var unlockPrompt = false
    @State private var selecting = false
    @State private var selection = Set<String>()
    @State private var moving = false
    @State private var movePrompt = false
    @State private var setupBox = false
    @State private var boxPassword = ""
    @State private var boxConfirmation = ""
    private var categories: [Category] { store.vault.categories.sorted { $0.pinned && !$1.pinned } }
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
                ZStack(alignment: .leading) {
                    mainList
                    if sidebar {
                        Color.black.opacity(0.25).ignoresSafeArea(edges: .bottom).onTapGesture { sidebar = false }
                        sidebarList
                    }
                }.navigationTitle("笔记").searchable(text: $query, prompt: "搜索已解锁记录")
                .toolbar {
                    ToolbarItem(placement: .navigationBarLeading) { Button { sidebar.toggle() } label: { Image(systemName: "sidebar.left") }.accessibilityLabel("打开左侧分类与记录") }
                    ToolbarItem(placement: .navigationBarTrailing) { Button { categories.filter { $0.sealed != nil }.forEach { store.lockCategory($0) }; selection.removeAll() } label: { Image(systemName: "lock") }.disabled(store.busy) }
                    ToolbarItem(placement: .bottomBar) { Button { createPrompt = true } label: { Label("新建", systemImage: "plus") }.disabled(store.busy) }
                }
            }.tabItem { Label("笔记", systemImage: "note.text") }
            NavigationStack { SettingsView(store: store) }.tabItem { Label("设置", systemImage: "gearshape") }
        }
        .alert("新建分类", isPresented: $categoryPrompt) {
            TextField("分类名称", text: $categoryName)
            Button("创建") { let name = categoryName; store.run { try await store.addCategory(name, password: ""); categoryName = "" } }
            Button("取消", role: .cancel) { categoryName = "" }
        }
        .sheet(item: $editing) { note in NoteEditor(store: store, initial: note) }
        .confirmationDialog("新建记录", isPresented: $createPrompt, titleVisibility: .visible) {
            Button("文本笔记") { create("note") }; Button("账号卡片") { create("account") }; Button("待办") { create("todo") }
        }
        .confirmationDialog("移入分类", isPresented: $movePrompt, titleVisibility: .visible) {
            ForEach(categories) { c in Button((c.sealed == nil ? "" : "🔒 ") + c.name) { move(to: c) } }
            if !categories.contains(where: { $0.id == NoteStore.boxID }) { Button("私密（首次设置密码）") { moving = true; setupBox = true } }
        }
        .alert("解锁分类", isPresented: $unlockPrompt) {
            SecureField("独立分类密码", text: $domainPassword)
            Button("解锁") {
                let p = domainPassword, ids = selection, shouldMove = moving; domainPassword = ""; moving = false
                if let c = unlockTarget { store.run { try await store.unlockCategory(c, password: p); if shouldMove { try await store.moveNotes(ids, to: c.id); store.lockCategory(c); selection.removeAll(); selecting = false } } }
            }
            Button("取消", role: .cancel) { domainPassword = ""; unlockTarget = nil; moving = false }
        } message: { Text("输入此分类的独立密码。") }
        .alert("首次设置私密密码", isPresented: $setupBox) {
            SecureField("私密密码（至少 8 位）", text: $boxPassword); SecureField("再次输入", text: $boxConfirmation)
            Button("设置") {
                let p = boxPassword, match = boxConfirmation, ids = selection, shouldMove = moving; boxPassword = ""; boxConfirmation = ""; moving = false
                guard p.count >= 8, p == match else { store.error = "密码至少 8 位且两次输入一致"; return }
                store.run { try await store.setBoxPassword(old: "", next: p); if shouldMove, let c = store.vault.categories.first(where: { $0.id == NoteStore.boxID }) { try await store.unlockCategory(c, password: p); try await store.moveToBox(ids); store.lockCategory(c); selection.removeAll(); selecting = false } }
            }
            Button("取消", role: .cancel) { moving = false; boxPassword = ""; boxConfirmation = "" }
        } message: { Text("普通笔记直接进入，只有私密需要密码；遗忘无法找回。") }
    }
    private var mainList: some View {
        VStack(spacing: 8) {
            HStack {
                Menu("排序") { ForEach(["updated_desc":"最新在前","updated_asc":"最早在前","title_asc":"标题升序","title_desc":"标题降序"].sorted(by: { $0.key < $1.key }), id: \.key) { item in Button(item.value) { var p = store.vault.prefs; p.sort = item.key; store.run { try await store.setPreferences(p) } } } }
                Spacer()
                Button { var p = store.vault.prefs; p.grid.toggle(); store.run { try await store.setPreferences(p) } } label: { Image(systemName: store.vault.prefs.grid ? "list.bullet" : "square.grid.2x2") }.accessibilityLabel("切换列表或卡片")
            }.padding(.horizontal).disabled(store.busy)
            HStack {
                Button(selecting ? "取消多选" : "选择记录") { selecting.toggle(); selection.removeAll() }
                Spacer()
                if selecting { Button("移入分类 (\(selection.count))") { movePrompt = true }.disabled(selection.isEmpty) }
            }.font(.caption).padding(.horizontal).disabled(store.busy)
            ScrollView {
                LazyVGrid(columns: Array(repeating: GridItem(.flexible()), count: store.vault.prefs.grid ? 2 : 1), spacing: 10) {
                    ForEach(filtered) { n in Button { if selecting { if selection.contains(n.id) { selection.remove(n.id) } else { selection.insert(n.id) } } else { editing = n } } label: { noteLabel(n) }.buttonStyle(.plain) }
                }.padding(.horizontal)
                if filtered.isEmpty { Text("点击 ＋ 开始记录").foregroundStyle(.secondary).padding(50) }
            }
            if categories.contains(where: { !store.isOpen($0) }) { Text("锁定分类内容不参与搜索").font(.caption).foregroundStyle(.secondary) }
            if !store.error.isEmpty { Text(store.error).font(.caption).foregroundStyle(.red) }
        }
    }
    private func noteLabel(_ n: Note) -> some View {
        VStack(alignment: .leading, spacing: 8) {
            Text((selecting ? (selection.contains(n.id) ? "☑ " : "☐ ") : "") + n.displayTitle).font(.headline).lineLimit(store.vault.prefs.grid ? 2 : 1)
            HStack(spacing: 5) {
                Text(Date(timeIntervalSince1970: Double(n.updated) / 1000), style: .date)
                Image(systemName: "folder")
                Text(categories.first(where: { $0.id == n.categoryId })?.name ?? "")
                if n.favorite { Image(systemName: "pin.fill") }
            }.font(.caption2).foregroundStyle(.secondary).lineLimit(1)
        }.frame(maxWidth: .infinity, minHeight: store.vault.prefs.grid ? 90 : 58, alignment: .leading).padding(12).background(Color.orange.opacity(0.08), in: RoundedRectangle(cornerRadius: 12))
    }
    private var sidebarList: some View {
        VStack(alignment: .leading, spacing: 12) {
            Text("随心记事本").font(.title3.bold()).padding(.horizontal)
            ScrollView {
                VStack(alignment: .leading, spacing: 4) {
                    Button("全部便签 (\(store.notes.count))") { category = nil; selection.removeAll(); sidebar = false }.padding(8)
                    HStack { Text("分组").font(.headline); Spacer(); Button { categoryName = ""; categoryPrompt = true } label: { Image(systemName: "folder.badge.plus") }.accessibilityLabel("新建分类") }.padding(8)
                    ForEach(categories) { c in HStack {
                        Button { category = c.id; selection.removeAll(); if !store.isOpen(c) { unlockTarget = c; domainPassword = ""; unlockPrompt = true } else { sidebar = false } } label: { Label("\(c.id == NoteStore.boxID ? "私密" : c.name) (\(store.isOpen(c) ? String(store.notes.filter { $0.categoryId == c.id }.count) : "已锁定"))", systemImage: c.sealed == nil ? "folder" : "lock") }.frame(maxWidth: .infinity, alignment: .leading)
                        Button { store.run { try await store.pinCategory(c.id, pinned: !c.pinned) } } label: { Image(systemName: c.pinned ? "pin.fill" : "pin") }.accessibilityLabel(c.pinned ? "取消分类置顶" : "置顶分类")
                    }.padding(8) }
                    if !categories.contains(where: { $0.id == NoteStore.boxID }) { Button("🔒 私密") { category = NoteStore.boxID; boxPassword = ""; boxConfirmation = ""; setupBox = true }.padding(8) }
                }.padding(.horizontal)
            }.frame(maxHeight: 220)
            Divider(); Text("分类内的标题").font(.caption).padding(.horizontal)
            ScrollView { LazyVStack(alignment: .leading) { ForEach(filtered) { n in Button { sidebar = false; editing = n } label: { Text(n.displayTitle).lineLimit(1).frame(maxWidth: .infinity, alignment: .leading).padding(10) }.buttonStyle(.plain) } }.padding(.horizontal) }
        }.padding(.vertical, 16).frame(width: 300).frame(maxHeight: .infinity).background(Color(.systemBackground)).shadow(radius: 8).disabled(store.busy)
    }
    private func move(to c: Category) {
        let ids = selection
        if c.sealed != nil { moving = true; unlockTarget = c; domainPassword = ""; unlockPrompt = true }
        else { store.run { try await store.moveNotes(ids, to: c.id); selection.removeAll(); selecting = false } }
    }
    private func create(_ kind: String) {
        if category == NoteStore.boxID && !categories.contains(where: { $0.id == NoteStore.boxID }) { setupBox = true; return }
        guard let c = categories.first(where: { $0.id == category }) ?? categories.first(where: { $0.sealed == nil }) else { return }
        if !store.isOpen(c) { unlockTarget = c; domainPassword = ""; unlockPrompt = true; return }
        var n = Note(categoryId: c.id); n.kind = kind; n.fontSize = store.vault.prefs.fontSize; if kind == "todo" { n.body = "☐ " }; editing = n
    }
}
