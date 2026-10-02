import XCTest
@testable import ShijianNotes

final class CoreTests: XCTestCase {
    func testAndroidCompatibleFixtureWithUtf8Password() throws {
        let url = try XCTUnwrap(Bundle(for: Self.self).url(forResource: "compatibility", withExtension: "json"))
        let fixture = try XCTUnwrap(JSONSerialization.jsonObject(with: Data(contentsOf: url)) as? [String: String])
        let password = try XCTUnwrap(fixture["password"])
        let encrypted = try XCTUnwrap(Data(base64Encoded: try XCTUnwrap(fixture["encryptedBase64"])))
        let v = try JSONDecoder().decode(Vault.self, from: VaultCrypto.decrypt(encrypted, password: password))
        try v.validate(); XCTAssertEqual(v.notes.first?.displayTitle, "首行标题"); XCTAssertEqual(v.notes.first?.images, ["AQID"])
        let c = try XCTUnwrap(v.categories.last?.sealed); let domain = try XCTUnwrap(Data(base64Encoded: c))
        XCTAssertThrowsError(try VaultCrypto.decrypt(domain, password: password))
        let decoded = try JSONDecoder().decode(Domain.self, from: VaultCrypto.decrypt(domain, password: try XCTUnwrap(fixture["categoryPassword"])))
        XCTAssertEqual(decoded.categoryId, "private")
    }
    func testEnvelopeAndWrongPassword() throws {
        let input = Data("中文-secret".utf8)
        let encrypted = try VaultCrypto.encrypt(input, password: "Password2026")
        XCTAssertEqual(try VaultCrypto.decrypt(encrypted, password: "Password2026"), input)
        XCTAssertThrowsError(try VaultCrypto.decrypt(encrypted, password: "wrong"))
        var changed = encrypted; changed[changed.count - 1] ^= 1
        XCTAssertThrowsError(try VaultCrypto.decrypt(changed, password: "Password2026"))
    }
    func testTitleFallbackAndExportHiding() {
        var n = Note(categoryId: "c"); n.body = "首行\n下一行"; n.kind = "account"; n.password = "Secret"
        XCTAssertEqual(n.displayTitle, "首行")
        XCTAssertFalse(String(data: Exports.data([n], format: "HTML", passwords: false), encoding: .utf8)!.contains("Secret"))
        n.title = "<script>"; XCTAssertTrue(String(data: Exports.data([n], format: "HTML", passwords: false), encoding: .utf8)!.contains("&lt;script&gt;"))
    }
    func testCSVFormulaGuardAndModelRoundTrip() throws {
        XCTAssertEqual(Exports.csvCell("=formula"), "\"'=formula\"")
        let v = Vault(); let decoded = try JSONDecoder().decode(Vault.self, from: JSONEncoder().encode(v))
        try decoded.validate(); XCTAssertEqual(v.categories, decoded.categories)
    }

    @MainActor func testMoveFoldersAndPrivateBoxKeepsFieldsAndBackupPortable() async throws {
        let root = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString)
        defer { try? FileManager.default.removeItem(at: root) }
        let store = NoteStore(directory: root); try await store.create("Local-Automatic2026")
        XCTAssertFalse(store.vault.prefs.grid)
        let first = store.vault.categories[0].id, second = store.vault.categories[1].id
        var n = Note(categoryId: first); n.title = "隐藏标题"; n.images = ["AQID"]; n.password = "Secret-Move"; n.bold = true
        try await store.save(n); try await store.moveNotes([n.id], to: second)
        XCTAssertEqual(store.notes.first?.categoryId, second)
        try await store.setBoxPassword(old: "", next: "Box-Private2026")
        let box = try XCTUnwrap(store.vault.categories.first(where: { $0.id == NoteStore.boxID }))
        do { try await store.moveToBox([n.id]); XCTFail("locked destination accepted") } catch { }
        try await store.unlockCategory(box, password: "Box-Private2026"); try await store.moveToBox([n.id]); store.lockCategory(box)
        XCTAssertTrue(store.notes.isEmpty()); XCTAssertTrue(store.vault.notes.isEmpty())
        let data = try await store.portableBackup(password: "Backup-Only2026")
        XCTAssertThrowsError(try VaultCrypto.decrypt(data, password: "Local-Automatic2026"))
        let plain = try VaultCrypto.decrypt(data, password: "Backup-Only2026")
        XCTAssertFalse(String(data: plain, encoding: .utf8)!.contains("Secret-Move"))
        try await store.restore(data, password: "Backup-Only2026")
        try await store.unlockCategory(try XCTUnwrap(store.vault.categories.first(where: { $0.id == NoteStore.boxID })), password: "Box-Private2026")
        XCTAssertEqual(store.notes.first?.images, n.images); XCTAssertEqual(store.notes.first?.bold, true)
        try await store.moveNotes([n.id], to: first); XCTAssertEqual(store.vault.notes.first?.id,n.id)
        XCTAssertEqual(store.notes.count,1)
    }
    @MainActor func testPrivatePasswordChangeAndPinnedCategoryPersistence() async throws {
        let root = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString)
        defer { try? FileManager.default.removeItem(at: root) }
        let store = NoteStore(directory: root); try await store.create("Local-Automatic2026")
        try await store.setBoxPassword(old: "", next: "Old-Private2026")
        let box = try XCTUnwrap(store.vault.categories.first(where: { $0.id == NoteStore.boxID }))
        try await store.unlockCategory(box, password: "Old-Private2026")
        var n = Note(categoryId: box.id); n.body = "protected"; try await store.save(n)
        let original = store.vault.categories
        do { try await store.setBoxPassword(old: "wrong", next: "New-Private2026"); XCTFail("wrong password accepted") } catch { }
        XCTAssertEqual(original,store.vault.categories)
        try await store.setBoxPassword(old: "Old-Private2026", next: "New-Private2026")
        let updated = try XCTUnwrap(store.vault.categories.first(where: { $0.id == NoteStore.boxID }))
        XCTAssertFalse(store.isOpen(updated))
        do { try await store.unlockCategory(updated, password: "Old-Private2026"); XCTFail("old password accepted") } catch { }
        try await store.unlockCategory(updated, password: "New-Private2026"); XCTAssertEqual(store.notes.first?.body,"protected")
        try await store.pinCategory(box.id,pinned: true)
        let data = try await store.portableBackup(password: "Backup-Only2026")
        let decoded = try JSONDecoder().decode(Vault.self, from: VaultCrypto.decrypt(data,password: "Backup-Only2026"))
        XCTAssertTrue(decoded.categories.first(where: { $0.id == box.id })!.pinned)
    }
}
