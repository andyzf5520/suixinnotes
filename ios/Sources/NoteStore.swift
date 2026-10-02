import Foundation
import Combine

@MainActor final class NoteStore: ObservableObject {
    @Published var vault = Vault()
    @Published var unlocked = false
    @Published var error = ""
    @Published var busy = false
    private var master = ""
    private var opened: [String: Domain] = [:]
    private var passwords: [String: String] = [:]
    private var epoch = 0
    var beforeLock: (() async -> Void)?
    private let file: URL
    init() {
        let root = FileManager.default.urls(for: .applicationSupportDirectory, in: .userDomainMask)[0]
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
    func open(_ password: String) async throws {
        let url = file, generation = epoch
        let next = try await Task.detached { () throws -> Vault in
            let data = try Data(contentsOf: url, options: .mappedIfSafe)
            let v = try JSONDecoder().decode(Vault.self, from: VaultCrypto.decrypt(data, password: password)); try v.validate(); return v
        }.value
        guard generation == epoch else { throw VaultError.locked }
        master = password; vault = next; unlocked = true
    }
    func create(_ password: String) async throws {
        guard !exists, password.count >= 8 else { throw VaultError.invalid }
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
        opened.removeAll(); passwords.removeAll(); master = password; vault = next; unlocked = true
    }
}
