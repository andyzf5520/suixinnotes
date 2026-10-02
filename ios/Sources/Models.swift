import Foundation

struct Note: Codable, Identifiable, Equatable {
    var id = UUID().uuidString
    var categoryId: String
    var kind = "note"
    var title = ""
    var body = ""
    var username = ""
    var password = ""
    var url = ""
    var images: [String] = []
    var fontSize = 17
    var font = "system"
    var bold = false
    var italic = false
    var paper = 0
    var favorite = false
    var updated = Int64(Date().timeIntervalSince1970 * 1000)
    var displayTitle: String {
        let explicit = title.trimmingCharacters(in: .whitespacesAndNewlines)
        if !explicit.isEmpty { return explicit }
        let line = body.components(separatedBy: .newlines).first?.trimmingCharacters(in: .whitespaces) ?? ""
        return line.isEmpty ? "无标题" : String(line.prefix(300))
    }
}
struct Category: Codable, Identifiable, Equatable {
    var id: String
    var name: String
    var color: Int
    var sealed: String?
    var pinned: Bool
    init(id: String = UUID().uuidString, name: String, color: Int = 0, sealed: String? = nil, pinned: Bool = false) {
        self.id = id; self.name = name; self.color = color; self.sealed = sealed; self.pinned = pinned
    }
    enum CodingKeys: CodingKey { case id, name, color, sealed, pinned }
    init(from decoder: Decoder) throws {
        let c = try decoder.container(keyedBy: CodingKeys.self)
        id = try c.decode(String.self, forKey: .id); name = try c.decode(String.self, forKey: .name)
        color = try c.decodeIfPresent(Int.self, forKey: .color) ?? 0
        sealed = try c.decodeIfPresent(String.self, forKey: .sealed)
        pinned = try c.decodeIfPresent(Bool.self, forKey: .pinned) ?? false
    }
}
struct Preferences: Codable {
    var dark = false
    var grid = false
    var accent = 0
    var fontSize = 17
    var sort = "updated_desc"
    var defaultDownloads = true
    enum CodingKeys: CodingKey { case dark, grid, accent, fontSize, sort, defaultDownloads }
    init() {}
    init(from decoder: Decoder) throws {
        let c = try decoder.container(keyedBy: CodingKeys.self)
        dark = try c.decodeIfPresent(Bool.self, forKey: .dark) ?? false
        grid = try c.decodeIfPresent(Bool.self, forKey: .grid) ?? false
        accent = try c.decodeIfPresent(Int.self, forKey: .accent) ?? 0
        fontSize = try c.decodeIfPresent(Int.self, forKey: .fontSize) ?? 17
        sort = try c.decodeIfPresent(String.self, forKey: .sort) ?? "updated_desc"
        defaultDownloads = try c.decodeIfPresent(Bool.self, forKey: .defaultDownloads) ?? true
    }
}
struct Vault: Codable {
    var version = 1
    var categories: [Category] = [Category(name: "随手记"), Category(name: "账号"), Category(name: "生活")]
    var notes: [Note] = []
    var prefs = Preferences()
    func validate() throws {
        guard version == 1, (1...100).contains(categories.count), notes.count <= 10_000,
            Set(categories.map(\.id)).count == categories.count, Set(notes.map(\.id)).count == notes.count,
            categories.allSatisfy({ !$0.name.isEmpty && $0.name.count <= 40 }),
            notes.allSatisfy({ n in categories.contains { $0.id == n.categoryId && $0.sealed == nil } }) else { throw VaultError.invalid }
        for n in notes { try validateNote(n) }
    }
}
func validateNote(_ n: Note) throws {
    guard ["note","account","todo"].contains(n.kind), n.title.count <= 300, n.body.count <= 100_000,
          n.password.count <= 10_000, n.username.count <= 10_000, n.url.count <= 10_000,
          (12...28).contains(n.fontSize), (0...4).contains(n.paper), n.images.count <= 6,
          n.images.allSatisfy({ $0.count <= 3_000_000 && Data(base64Encoded: $0) != nil }) else { throw VaultError.invalid }
}
struct Domain: Codable { var categoryId: String; var notes: [Note] }
enum VaultError: Error, LocalizedError {
    case invalid, password, locked, tooLarge
    var errorDescription: String? {
        switch self { case .invalid: return "文件格式或内容无效"; case .password: return "密码错误或文件已损坏"; case .locked: return "请先解锁"; case .tooLarge: return "文件超过 48MB 限制" }
    }
}
