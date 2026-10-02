import Foundation
import Combine

@MainActor final class NoteStore: ObservableObject {
    @Published var vault = Vault()
    @Published var unlocked = false
    @Published var error = ""
    @Published var busy = false
    @Published var legacyMigration = false
    private var master = ""
    private var opened: [String: Domain] = [:]
    private var passwords: [String: String] = [:]
    private var epoch = 0
    var beforeLock: (() async -> Void)?
    static let boxID = "secure-box"
    private let file: URL
    private let useDeviceAccess: Bool
    init(directory: URL? = nil) {
        useDeviceAccess = directory == nil
        let root = directory ?? FileManager.default.urls(for: .applicationSupportDirectory, in: .userDomainMask)[0]
        file = root.appendingPathComponent("vault.mobx")
        try? FileManager.default.createDirectory(at: root, withIntermediateDirectories: true)
        var excluded = root; var values = URLResourceValues(); values.isExcludedFromBackup = true
        try? excluded.setResourceValues(values)
    }
    var exists: Bool { FileManager.default.fileExists(atPath: file.path) }
    var notes: [Note] { vault.notes + opened.values.flatMap(\.notes) }
    func isOpen(_ c: Category) -> Bool { c.sealed == nil || opened[c.id] != nil }
    func run(_ action: @escaping () async throws -> Void) {
        guard !busy else { return }; busy = true; error = ""
        let generation = epoch
        Task { do { try await action() } catch { if generation == epoch { self.error = error.localizedDescription } }
            if generation == epoch { busy = false }
        }
    }
    func autoOpen() async throws {
        guard !unlocked else { return }
        legacyMigration = false
        if let password = try DeviceAccess.load() { if exists { try await open(password) } else { try await create(password) } }
        else if !exists { try await create(DeviceAccess.generate()) }
        else { legacyMigration = true; error = "旧版数据请首次输入原密码，之后自动进入普通笔记。" }
    }
    func open(_ password: String) async throws {
        let url = file, generation = epoch
        let next = try await Task.detached { () throws -> Vault in
            let data = try Data(contentsOf: url, options: .mappedIfSafe)
            let v = try JSONDecoder().decode(Vault.self, from: VaultCrypto.decrypt(data, password: password)); try v.validate(); return v
        }.value
        guard generation == epoch else { throw VaultError.locked }
        if useDeviceAccess { try DeviceAccess.save(password) }
        master = password; vault = next; unlocked = true
    }
    func create(_ password: String) async throws {
        guard !exists, password.count >= 8 else { throw VaultError.invalid }
        if useDeviceAccess { try DeviceAccess.save(password) }
        master = password
        do { try await persist(Vault()); unlocked = true } catch { master = ""; throw error }
    }
    func persist(_ next: Vault) async throws {
        let password = master, url = file, generation = epoch
        guard !password.isEmpty else { throw VaultError.locked }
        try await Task.detached {
            let data = try VaultCrypto.encrypt(JSONEncoder().encode(next), password: password)
            try data.write(to: url, options: [.atomic, .completeFileProtection])
        }.value
        guard generation == epoch else { throw VaultError.locked }; vault = next
    }
    func lock() { epoch += 1; beforeLock = nil; master = ""; opened.removeAll(); passwords.removeAll(); vault = Vault(); unlocked = false; busy = false }
    func flushAndLock() async {
        while busy { try? await Task.sleep(nanoseconds: 50_000_000) }
        if let flush = beforeLock { await flush() }; lock()
    }
    func addCategory(_ name: String, password: String) async throws {
        let name = name.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !name.isEmpty, name.count <= 40, vault.categories.count < 100, !vault.categories.contains(where: { $0.name == name }) else { throw VaultError.invalid }
        var c = Category(name: name)
        if !password.isEmpty {
            guard password.count >= 8 else { throw VaultError.invalid }
            let d = Domain(categoryId: c.id, notes: [])
            c.sealed = try await Task.detached { try VaultCrypto.encrypt(JSONEncoder().encode(d), password: password).base64EncodedString() }.value
        }
        var next = vault; next.categories.append(c); try await persist(next)
    }
    func unlockCategory(_ c: Category, password: String) async throws {
        guard let encoded = c.sealed, let data = Data(base64Encoded: encoded) else { throw VaultError.invalid }
        let generation = epoch
        let domain = try await Task.detached {
            let d = try JSONDecoder().decode(Domain.self, from: VaultCrypto.decrypt(data, password: password))
            guard d.categoryId == c.id, d.notes.count <= 10_000, d.notes.allSatisfy({ $0.categoryId == c.id }), Set(d.notes.map(\.id)).count == d.notes.count else { throw VaultError.invalid }
            for n in d.notes { try validateNote(n) }; return d
        }.value
        guard generation == epoch else { throw VaultError.locked }
        guard domain.notes.allSatisfy({ n in !notes.contains(where: { $0.id == n.id && $0.categoryId != c.id }) }) else { throw VaultError.invalid }
        opened[c.id] = domain; passwords[c.id] = password
    }
    func lockCategory(_ c: Category) { opened[c.id] = nil; passwords[c.id] = nil; objectWillChange.send() }
    func save(_ n: Note) async throws {
        try validateNote(n)
        guard let c = vault.categories.first(where: { $0.id == n.categoryId }), isOpen(c) else { throw VaultError.locked }
        var next = vault
        if c.sealed == nil { next.notes.removeAll { $0.id == n.id }; next.notes.append(n); try await persist(next) }
        else {
            guard var d = opened[c.id], let password = passwords[c.id] else { throw VaultError.locked }
            d.notes.removeAll { $0.id == n.id }; d.notes.append(n)
            let snapshot = d
            let encoded = try await Task.detached { try VaultCrypto.encrypt(JSONEncoder().encode(snapshot), password: password).base64EncodedString() }.value
            guard let i = next.categories.firstIndex(where: { $0.id == c.id }) else { throw VaultError.invalid }
            next.categories[i].sealed = encoded; try await persist(next); opened[c.id] = d
        }
    }
    func delete(_ n: Note) async throws {
        guard let c = vault.categories.first(where: { $0.id == n.categoryId }), isOpen(c) else { throw VaultError.locked }
        var next = vault
        if c.sealed == nil { next.notes.removeAll { $0.id == n.id }; try await persist(next) }
        else {
            guard var d = opened[c.id], let password = passwords[c.id] else { throw VaultError.locked }
            d.notes.removeAll { $0.id == n.id }; let snapshot = d
            let seal = try await Task.detached { try VaultCrypto.encrypt(JSONEncoder().encode(snapshot), password: password).base64EncodedString() }.value
            let index = next.categories.firstIndex(where: { $0.id == c.id })!; next.categories[index].sealed = seal
            try await persist(next); opened[c.id] = d
        }
    }
    func setBoxPassword(old: String, next password: String) async throws {
        guard password.count >= 8 else { throw VaultError.invalid }
        var next = vault
        if let i = next.categories.firstIndex(where: { $0.id == Self.boxID }) {
            guard let sealed = next.categories[i].sealed, let data = Data(base64Encoded: sealed) else { throw VaultError.invalid }
            let domain = try await Task.detached { try JSONDecoder().decode(Domain.self, from: VaultCrypto.decrypt(data, password: old)) }.value
            guard domain.categoryId == Self.boxID, domain.notes.allSatisfy({ $0.categoryId == Self.boxID }) else { throw VaultError.invalid }
            let encoded = try await Task.detached { try VaultCrypto.encrypt(JSONEncoder().encode(domain), password: password).base64EncodedString() }.value
            next.categories[i].sealed = encoded
        } else {
            let domain = Domain(categoryId: Self.boxID, notes: [])
            let encoded = try await Task.detached { try VaultCrypto.encrypt(JSONEncoder().encode(domain), password: password).base64EncodedString() }.value
            var c = Category(name: "私密"); c.id = Self.boxID; c.sealed = encoded; next.categories.append(c)
        }
        try await persist(next); opened[Self.boxID] = nil; passwords[Self.boxID] = nil
    }
    func pinCategory(_ id: String, pinned: Bool) async throws {
        var next = vault; guard let i = next.categories.firstIndex(where: { $0.id == id }) else { throw VaultError.invalid }
        next.categories[i].pinned = pinned; try await persist(next)
    }
    func moveToBox(_ ids: Set<String>) async throws { try await moveNotes(ids, to: Self.boxID) }
    func moveNotes(_ ids: Set<String>, to targetID: String) async throws {
        guard !ids.isEmpty, let target = vault.categories.first(where: { $0.id == targetID }), isOpen(target) else { throw VaultError.locked }
        let chosen = notes.filter { ids.contains($0.id) }
        guard chosen.count == ids.count else { throw VaultError.invalid }
        let selected = chosen.filter { $0.categoryId != targetID }; guard !selected.isEmpty else { throw VaultError.invalid }
        let movedIDs = Set(selected.map(\.id))
        var next = vault, changed: [String: Domain] = [:]
        for id in Set(selected.map(\.categoryId)) {
            guard let i = next.categories.firstIndex(where: { $0.id == id }) else { throw VaultError.invalid }
            if next.categories[i].sealed != nil {
                guard var domain = opened[id], let password = passwords[id] else { throw VaultError.locked }
                domain.notes.removeAll { movedIDs.contains($0.id) }; let snapshot = domain
                next.categories[i].sealed = try await Task.detached { try VaultCrypto.encrypt(JSONEncoder().encode(snapshot), password: password).base64EncodedString() }.value
                changed[id] = domain
            }
        }
        let moved = selected.map { n in var result = n; result.categoryId = targetID; result.updated = Int64(Date().timeIntervalSince1970 * 1000); return result }
        next.notes.removeAll { movedIDs.contains($0.id) }
        if target.sealed == nil { next.notes += moved }
        else {
            guard var destination = opened[targetID], let password = passwords[targetID], let i = next.categories.firstIndex(where: { $0.id == targetID }) else { throw VaultError.locked }
            destination.notes += moved; let snapshot = destination
            next.categories[i].sealed = try await Task.detached { try VaultCrypto.encrypt(JSONEncoder().encode(snapshot), password: password).base64EncodedString() }.value
            changed[targetID] = destination
        }
        try await persist(next); changed.forEach { opened[$0.key] = $0.value }
    }
    func portableBackup(password: String) async throws -> Data {
        guard unlocked, password.count >= 8 else { throw VaultError.invalid }; let snapshot = vault
        return try await Task.detached { try VaultCrypto.encrypt(JSONEncoder().encode(snapshot), password: password) }.value
    }
    func setPreferences(_ p: Preferences) async throws { var next = vault; next.prefs = p; try await persist(next) }
    func backup() throws -> Data { guard unlocked else { throw VaultError.locked }; return try Data(contentsOf: file) }
    func confirmPassword(_ password: String) async throws {
        let data = try backup(); _ = try await Task.detached { try VaultCrypto.decrypt(data, password: password) }.value
    }
    func restore(_ data: Data, password: String) async throws {
        let next = try await Task.detached {
            let v = try JSONDecoder().decode(Vault.self, from: VaultCrypto.decrypt(data, password: password)); try v.validate(); return v
        }.value
        let url = file, generation = epoch
        try await Task.detached {
            if FileManager.default.fileExists(atPath: url.path) {
                try Data(contentsOf: url).write(to: url.deletingLastPathComponent().appendingPathComponent("vault.previous.mobx"), options: [.atomic, .completeFileProtection])
            }
            try data.write(to: url, options: [.atomic, .completeFileProtection])
        }.value
        guard epoch == generation else { throw VaultError.locked }
        if useDeviceAccess { try DeviceAccess.save(password) }
        opened.removeAll(); passwords.removeAll(); master = password; vault = next; unlocked = true
    }
}
