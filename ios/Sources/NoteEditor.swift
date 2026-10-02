import SwiftUI
import PhotosUI
import UIKit

struct NoteEditor: View {
    @ObservedObject var store: NoteStore
    @Environment(\.dismiss) private var dismiss
    @State private var draft: Note
    @State private var saved: Note?
    @State private var prior: Note
    @State private var past: [Note] = []
    @State private var future: [Note] = []
    @State private var status = ""
    @State private var saving = false
    @State private var photo: PhotosPickerItem?
    @State private var reveal = false
    @State private var deletePrompt = false
    init(store: NoteStore, initial: Note) { self.store = store; _draft = State(initialValue: initial); _prior = State(initialValue: initial) }
    private var textFont: Font { .system(size: CGFloat(draft.fontSize), weight: draft.bold ? .bold : .regular, design: draft.font == "mono" ? .monospaced : draft.font == "serif" ? .serif : .default).italicIf(draft.italic) }
    var body: some View {
        NavigationStack {
            VStack(spacing: 4) {
                HStack {
                    Picker("字体", selection: $draft.font) { Text("默认字体").tag("system"); Text("衬线").tag("serif"); Text("等宽").tag("mono") }.pickerStyle(.menu)
                    Picker("字号", selection: $draft.fontSize) { ForEach(12...28, id: \.self) { Text("\($0)").tag($0) } }.pickerStyle(.menu)
                    Menu { Toggle("粗体", isOn: $draft.bold); Toggle("斜体", isOn: $draft.italic); Toggle("置顶", isOn: $draft.favorite) } label: { Text("样式"); Image(systemName: "chevron.down") }
                    PhotosPicker(selection: $photo, matching: .images) { Image(systemName: "photo.badge.plus") }.disabled(draft.images.count >= 6).accessibilityLabel("插入图片")
                }.font(.caption).padding(.horizontal)
                if !status.isEmpty { Text(status).font(.caption).foregroundStyle(.secondary) }
                ScrollView {
                    VStack(alignment: .leading, spacing: 12) {
                        TextField("标题（留空取正文第一行）", text: $draft.title).font(.title2)
                        if draft.kind == "account" {
                            TextField("用户名 / 邮箱", text: $draft.username).textInputAutocapitalization(.never)
                            HStack { if reveal { TextField("密码", text: $draft.password) } else { SecureField("密码", text: $draft.password) }; Button { reveal.toggle() } label: { Image(systemName: reveal ? "eye.slash" : "eye") } }
                            HStack {
                                Button("生成密码") { let chars = Array("ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnopqrstuvwxyz23456789!@#%*-_"); draft.password = String((0..<20).map { _ in chars.randomElement()! }) }
                                Button("复制密码") { UIPasteboard.general.setItems([[UIPasteboard.typeAutomatic: draft.password]], options: [.localOnly: true, .expirationDate: Date().addingTimeInterval(30)]) }
                            }
                            TextField("网址", text: $draft.url).textInputAutocapitalization(.never)
                        }
                        TextEditor(text: $draft.body).font(textFont).frame(minHeight: draft.kind == "account" ? 260 : 430)
                        ForEach(Array(draft.images.enumerated()), id: \.offset) { index, value in
                            if let data = Data(base64Encoded: value), let image = UIImage(data: data) {
                                Image(uiImage: image).resizable().scaledToFit().frame(maxHeight: 230)
                                Button("移除图片", role: .destructive) { draft.images.remove(at: index) }
                            }
                        }
                    }.padding()
                }
            }
            .navigationTitle("编辑").navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .navigationBarLeading) { Button { Task { await commit(close: true) } } label: { Image(systemName: "chevron.left") }.accessibilityLabel("保存并返回") }
                ToolbarItemGroup(placement: .navigationBarTrailing) {
                    Button { undo() } label: { Image(systemName: "arrow.uturn.backward") }.disabled(past.isEmpty || saving).accessibilityLabel("撤销")
                    Button { redo() } label: { Image(systemName: "arrow.uturn.forward") }.disabled(future.isEmpty || saving).accessibilityLabel("重做")
                    Menu { Button("删除", role: .destructive) { deletePrompt = true } } label: { Image(systemName: "ellipsis") }
                    Button { Task { await commit(close: true) } } label: { Image(systemName: "checkmark") }.disabled(saving).accessibilityLabel("保存并退出")
                }
            }
        }
        .interactiveDismissDisabled(true)
        .onAppear { store.beforeLock = { await commit(close: false) }; status = "修改后自动加密保存" }
        .onDisappear { store.beforeLock = nil; past.removeAll(); future.removeAll() }
        .onChange(of: draft) { n in
            if n != prior { past.append(prior); past = Array(past.suffix(100)); future.removeAll(); prior = n }
        }
        .task(id: draft) {
            try? await Task.sleep(nanoseconds: 1_200_000_000)
            guard !Task.isCancelled, draft != saved else { return }; await commit(close: false)
        }
        .task(id: photo) {
            guard let photo else { return }
            do {
                guard let data = try await photo.loadTransferable(type: Data.self), data.count <= 20 * 1024 * 1024,
                      let image = UIImage(data: data), image.size.width * image.size.height * image.scale * image.scale <= 40_000_000 else { throw VaultError.invalid }
                let scale = min(1, 1600 / max(image.size.width, image.size.height))
                let size = CGSize(width: image.size.width * scale, height: image.size.height * scale)
                let format = UIGraphicsImageRendererFormat(); format.scale = 1; format.opaque = true
                let jpeg = UIGraphicsImageRenderer(size: size, format: format).image { _ in image.draw(in: CGRect(origin: .zero, size: size)) }.jpegData(compressionQuality: 0.85)
                guard let jpeg, jpeg.count <= 2_000_000, draft.images.count < 6 else { throw VaultError.tooLarge }; draft.images.append(jpeg.base64EncodedString())
            } catch { status = error.localizedDescription }
        }
        .alert("删除记录？", isPresented: $deletePrompt) {
            Button("删除", role: .destructive) { store.run { try await store.delete(draft); dismiss() } }; Button("取消", role: .cancel) {}
        } message: { Text("首版没有回收站，删除前请备份。") }
    }
    private func commit(close: Bool) async {
        while saving || store.busy { try? await Task.sleep(nanoseconds: 50_000_000); if !store.unlocked { return } }
        saving = true; store.busy = true
        defer { saving = false; store.busy = false }
        do {
            repeat {
                let snapshot = draft
                var n = snapshot; n.updated = Int64(Date().timeIntervalSince1970 * 1000)
                try await store.save(n); saved = snapshot
                if !close { break }
            } while draft != saved
            status = "已加密保存"; if close { dismiss() }
        } catch { status = "保存失败：\(error.localizedDescription)，请重试" }
    }
    private func undo() { guard let n = past.popLast() else { return }; future.append(draft); prior = n; draft = n }
    private func redo() { guard let n = future.popLast() else { return }; past.append(draft); prior = n; draft = n }
}
private extension Font { func italicIf(_ enabled: Bool) -> Font { enabled ? italic() : self } }
