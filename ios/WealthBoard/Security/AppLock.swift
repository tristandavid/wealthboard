import CryptoKit
import Foundation
import Security
import LocalAuthentication
import UIKit

/// App lock: either the device's own biometric/passcode via `LAContext`, or a
/// separate six-digit app PIN stored as PBKDF2-SHA256 with a per-install random
/// salt, with escalating lockout after repeated failures.
///
/// Ported from `security/AppLock.kt`. The Android build also sets `FLAG_SECURE`
/// to keep the app out of the recents preview; iOS has no equivalent flag, so
/// the screenshot setting instead drives a privacy overlay in `WealthBoardApp`
/// when the app leaves the foreground.
///
/// The PIN is hashed rather than stored, and the hash lives in the keychain
/// rather than `UserDefaults`, because a defaults plist is readable from a
/// backup while a keychain item marked `ThisDeviceOnly` is not.
enum AppLock {

    enum Mode: String {
        case biometric
        case pin
    }

    private enum Key {
        static let enabled = "app_lock_enabled"
        static let mode = "app_lock_mode"
        static let allowScreenshots = "app_lock_allow_screenshots"
        static let failCount = "app_lock_fail_count"
        static let lockoutUntil = "app_lock_lockout_until"
    }

    private static let keychainAccount = "wealthboard.app_lock.pin"
    private static let iterations = 120_000
    private static let keyLengthBytes = 32

    private static let defaults = UserDefaults.standard

    // MARK: - State

    static var isEnabled: Bool {
        defaults.bool(forKey: Key.enabled)
    }

    static var mode: Mode {
        Mode(rawValue: defaults.string(forKey: Key.mode) ?? "") ?? .biometric
    }

    static var allowScreenshots: Bool {
        get { defaults.bool(forKey: Key.allowScreenshots) }
        set { defaults.set(newValue, forKey: Key.allowScreenshots) }
    }

    /// True when the device can actually perform a biometric or passcode check.
    /// Offering the biometric option on a device with no passcode set produces a
    /// lock that can never be opened.
    static var biometricsAvailable: Bool {
        var error: NSError?
        return LAContext().canEvaluatePolicy(
            .deviceOwnerAuthentication,
            error: &error
        )
    }

    /// What the device calls its own biometric, so the settings row can say
    /// "Face ID" rather than the generic word.
    static var biometryName: String {
        let context = LAContext()
        _ = context.canEvaluatePolicy(.deviceOwnerAuthentication, error: nil)
        switch context.biometryType {
        case .faceID: return "Face ID"
        case .touchID: return "Touch ID"
        default: return "your device passcode"
        }
    }

    // MARK: - Enabling

    static func enableBiometric() {
        defaults.set(true, forKey: Key.enabled)
        defaults.set(Mode.biometric.rawValue, forKey: Key.mode)
    }

    static func enablePin(_ pin: String) {
        var salt = Data(count: 16)
        _ = salt.withUnsafeMutableBytes { buffer -> Int32 in
            guard let base = buffer.baseAddress else { return -1 }
            return SecRandomCopyBytes(kSecRandomDefault, 16, base)
        }
        let hash = derive(pin: pin, salt: salt)
        let stored = StoredPin(salt: salt, hash: hash)
        guard let encoded = try? JSONEncoder().encode(stored) else { return }
        Keychain.set(encoded, account: keychainAccount)

        defaults.set(true, forKey: Key.enabled)
        defaults.set(Mode.pin.rawValue, forKey: Key.mode)
        defaults.set(0, forKey: Key.failCount)
        defaults.set(0.0, forKey: Key.lockoutUntil)
    }

    static func disable() {
        defaults.set(false, forKey: Key.enabled)
        Keychain.delete(account: keychainAccount)
        defaults.set(0, forKey: Key.failCount)
        defaults.set(0.0, forKey: Key.lockoutUntil)
    }

    // MARK: - Verifying

    /// Seconds left on the current lockout, or 0 when there isn't one.
    static var lockoutRemaining: TimeInterval {
        let until = defaults.double(forKey: Key.lockoutUntil)
        return max(until - Date().timeIntervalSince1970, 0)
    }

    static func verify(pin attempt: String) -> Bool {
        guard lockoutRemaining <= 0 else { return false }
        guard let data = Keychain.get(account: keychainAccount),
              let stored = try? JSONDecoder().decode(StoredPin.self, from: data) else {
            return false
        }

        let candidate = derive(pin: attempt, salt: stored.salt)
        // Constant-time compare: a byte-by-byte early exit leaks how much of the
        // hash matched, which over enough attempts is how a short PIN falls.
        let ok = constantTimeEquals(candidate, stored.hash)

        if ok {
            defaults.set(0, forKey: Key.failCount)
            defaults.set(0.0, forKey: Key.lockoutUntil)
        } else {
            let fails = defaults.integer(forKey: Key.failCount) + 1
            defaults.set(fails, forKey: Key.failCount)
            // Escalating lockout: 3 fails → 30s, 5 → 2min, 8+ → 10min.
            let lockout: TimeInterval
            if fails >= 8 { lockout = 600 }
            else if fails >= 5 { lockout = 120 }
            else if fails >= 3 { lockout = 30 }
            else { lockout = 0 }
            defaults.set(
                lockout > 0 ? Date().timeIntervalSince1970 + lockout : 0,
                forKey: Key.lockoutUntil
            )
        }
        return ok
    }

    /// Runs the system biometric/passcode prompt.
    static func authenticateWithBiometrics() async -> Result<Void, Error> {
        let context = LAContext()
        context.localizedCancelTitle = "Cancel"
        do {
            let ok = try await context.evaluatePolicy(
                .deviceOwnerAuthentication,
                localizedReason: "Unlock WealthBoard"
            )
            return ok ? .success(()) : .failure(LockError.failed)
        } catch {
            return .failure(error)
        }
    }

    enum LockError: LocalizedError {
        case failed
        var errorDescription: String? { "Authentication didn't succeed." }
    }

    // MARK: - Hashing

    private struct StoredPin: Codable {
        let salt: Data
        let hash: Data
    }

    /// PBKDF2-HMAC-SHA256, implemented over CryptoKit's HMAC so this needs no
    /// CommonCrypto bridging header.
    private static func derive(pin: String, salt: Data) -> Data {
        let password = SymmetricKey(data: Data(pin.utf8))
        var output = Data()
        var block: UInt32 = 1

        while output.count < keyLengthBytes {
            var salted = salt
            salted.append(contentsOf: [
                UInt8((block >> 24) & 0xFF),
                UInt8((block >> 16) & 0xFF),
                UInt8((block >> 8) & 0xFF),
                UInt8(block & 0xFF)
            ])

            var u = Data(HMAC<SHA256>.authenticationCode(for: salted, using: password))
            var result = u

            for _ in 1..<iterations {
                u = Data(HMAC<SHA256>.authenticationCode(for: u, using: password))
                for i in 0..<result.count {
                    result[result.startIndex + i] ^= u[u.startIndex + i]
                }
            }

            output.append(result)
            block += 1
        }

        return output.prefix(keyLengthBytes)
    }

    private static func constantTimeEquals(_ a: Data, _ b: Data) -> Bool {
        guard a.count == b.count else { return false }
        var diff: UInt8 = 0
        for i in 0..<a.count {
            diff |= a[a.startIndex + i] ^ b[b.startIndex + i]
        }
        return diff == 0
    }
}

// MARK: - Keychain

/// The smallest possible keychain wrapper — one generic-password item, set, get
/// and delete. Anything stored here is `ThisDeviceOnly`, so it never travels in
/// an iCloud or iTunes backup.
enum Keychain {
    private static let service = "ca.tristan.wealthboard"

    static func set(_ data: Data, account: String) {
        delete(account: account)
        let query: [String: Any] = [
            kSecClass as String: kSecClassGenericPassword,
            kSecAttrService as String: service,
            kSecAttrAccount as String: account,
            kSecValueData as String: data,
            kSecAttrAccessible as String: kSecAttrAccessibleWhenUnlockedThisDeviceOnly
        ]
        SecItemAdd(query as CFDictionary, nil)
    }

    static func get(account: String) -> Data? {
        let query: [String: Any] = [
            kSecClass as String: kSecClassGenericPassword,
            kSecAttrService as String: service,
            kSecAttrAccount as String: account,
            kSecReturnData as String: true,
            kSecMatchLimit as String: kSecMatchLimitOne
        ]
        var item: CFTypeRef?
        guard SecItemCopyMatching(query as CFDictionary, &item) == errSecSuccess else { return nil }
        return item as? Data
    }

    static func delete(account: String) {
        let query: [String: Any] = [
            kSecClass as String: kSecClassGenericPassword,
            kSecAttrService as String: service,
            kSecAttrAccount as String: account
        ]
        SecItemDelete(query as CFDictionary)
    }
}
