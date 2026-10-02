import XCTest
@testable import ShijianNotes

final class CoreTests: XCTestCase {
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
}
