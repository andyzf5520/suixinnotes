import Foundation
import CryptoKit
import CommonCrypto
import Security

enum VaultCrypto {
    static let magic = Data([77,79,66,88,1])
    static let maxBytes = 48 * 1024 * 1024
    static func random(_ size: Int) throws -> Data {
        var data = Data(count: size)
        let result = data.withUnsafeMutableBytes { SecRandomCopyBytes(kSecRandomDefault, size, $0.baseAddress!) }
        guard result == errSecSuccess else { throw VaultError.invalid }; return data
    }
    static func key(_ password: String, salt: Data, rounds: UInt32) throws -> SymmetricKey {
        var result = Data(count: 32)
        let bytes = Array(password.utf8)
        guard !bytes.isEmpty else { throw VaultError.password }
        let status = result.withUnsafeMutableBytes { output in salt.withUnsafeBytes { input in
            bytes.withUnsafeBytes { p in CCKeyDerivationPBKDF(CCPBKDFAlgorithm(kCCPBKDF2), p.baseAddress!.assumingMemoryBound(to: Int8.self), bytes.count,
                input.baseAddress!.assumingMemoryBound(to: UInt8.self), salt.count, CCPseudoRandomAlgorithm(kCCPRFHmacAlgSHA256), rounds,
                output.baseAddress!.assumingMemoryBound(to: UInt8.self), 32) }
        } }
        guard status == kCCSuccess else { throw VaultError.invalid }
        defer { result.resetBytes(in: 0..<result.count) }
        return SymmetricKey(data: result)
    }
    static func encrypt(_ plain: Data, password: String) throws -> Data {
        guard plain.count <= maxBytes - 64 else { throw VaultError.tooLarge }
        let salt = try random(16), nonceData = try random(12)
        var count = UInt32(600_000).bigEndian
        var header = magic
        withUnsafeBytes(of: &count) { header.append(contentsOf: $0) }
        header.append(salt); header.append(nonceData)
        let box = try AES.GCM.seal(plain, using: key(password, salt: salt, rounds: 600_000), nonce: AES.GCM.Nonce(data: nonceData), authenticating: header)
        return header + box.ciphertext + box.tag
    }
    static func decrypt(_ data: Data, password: String) throws -> Data {
        guard data.count >= 53, data.count <= maxBytes, data.prefix(5) == magic else { throw VaultError.invalid }
        let rounds = data[5..<9].reduce(UInt32(0)) { ($0 << 8) | UInt32($1) }
        guard (100_000...1_000_000).contains(rounds) else { throw VaultError.invalid }
        do {
            let nonce = try AES.GCM.Nonce(data: data[25..<37])
            let box = try AES.GCM.SealedBox(nonce: nonce, ciphertext: data[37..<(data.count - 16)], tag: data.suffix(16))
            return try AES.GCM.open(box, using: key(password, salt: data.subdata(in: 9..<25), rounds: rounds), authenticating: data.prefix(37))
        } catch { throw VaultError.password }
    }
}
