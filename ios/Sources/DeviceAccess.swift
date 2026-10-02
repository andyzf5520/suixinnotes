import Foundation
import Security

// Device-only credential unlocks ordinary local notes. Private passwords never enter Keychain.
enum DeviceAccess {
    private static let service = "com.andy.suixinnotes.local"
    private static var query: [String: Any] { [kSecClass as String: kSecClassGenericPassword, kSecAttrService as String: service, kSecAttrAccount as String: "vault"] }
    static func load() throws -> String? {
        var q = query; q[kSecReturnData as String] = true; q[kSecMatchLimit as String] = kSecMatchLimitOne
        var result: CFTypeRef?; let status = SecItemCopyMatching(q as CFDictionary, &result)
        if status == errSecItemNotFound { return nil }
        guard status == errSecSuccess, let data = result as? Data, let value = String(data: data, encoding: .utf8) else { throw VaultError.locked }; return value
    }
    static func save(_ value: String) throws {
        let fields: [String: Any] = [kSecValueData as String: Data(value.utf8), kSecAttrAccessible as String: kSecAttrAccessibleWhenUnlockedThisDeviceOnly]
        let status = SecItemUpdate(query as CFDictionary, fields as CFDictionary)
        if status == errSecItemNotFound { var q = query; fields.forEach { q[$0.key] = $0.value }; guard SecItemAdd(q as CFDictionary, nil) == errSecSuccess else { throw VaultError.locked } }
        else if status != errSecSuccess { throw VaultError.locked }
    }
    static func generate() throws -> String {
        var data = Data(count: 32)
        let status = data.withUnsafeMutableBytes { SecRandomCopyBytes(kSecRandomDefault, 32, $0.baseAddress!) }
        guard status == errSecSuccess else { throw VaultError.invalid }; return data.base64EncodedString()
    }
}
