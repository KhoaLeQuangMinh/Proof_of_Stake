package crypto;

import org.bouncycastle.crypto.AsymmetricCipherKeyPair;
import org.bouncycastle.crypto.generators.Ed25519KeyPairGenerator;
import org.bouncycastle.crypto.params.Ed25519KeyGenerationParameters;
import org.bouncycastle.crypto.params.Ed25519PrivateKeyParameters;
import org.bouncycastle.crypto.params.Ed25519PublicKeyParameters;
import org.bouncycastle.crypto.signers.Ed25519Signer;

import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;

/**
 * Ed25519Util — Ed25519 cryptographic operations using BouncyCastle.
 *
 * Integration Plan §1 (Core Data Structures):
 *  - Replaces ECDSA throughout the entire system.
 *  - Ed25519 is 40x faster than ECDSA for signature verification, which is
 *    critical when a node must batch-verify hundreds of votes per second in
 *    Phase 3 and Phase 4 of the consensus algorithm.
 *  - Key size: 32 bytes (private), 32 bytes (public). Much smaller than ECDSA.
 *  - All keys are Base64-encoded for storage in SQLite and JSON payloads.
 *
 * Address Derivation:
 *  An on-chain address is derived as SHA-256(publicKeyBytes)[0:20] in hex.
 *  This is NOT the public key itself — it is a compact identifier for the
 *  world_state table.
 */
public class Ed25519Util {

    // -------------------------------------------------------------------------
    // Key Generation
    // -------------------------------------------------------------------------

    /**
     * Generates a fresh random Ed25519 key pair.
     * Used when creating a new node identity for the first time.
     *
     * @return BouncyCastle AsymmetricCipherKeyPair (Ed25519 parameters).
     */
    public static AsymmetricCipherKeyPair generateKeyPair() {
        Ed25519KeyPairGenerator gen = new Ed25519KeyPairGenerator();
        gen.init(new Ed25519KeyGenerationParameters(new SecureRandom()));
        return gen.generateKeyPair();
    }

    /**
     * Derives a deterministic Ed25519 key pair from a 32-byte seed.
     * Used for genesis node key generation — ensures all nodes that
     * claim to be genesis node N will always generate the same keys.
     *
     * @param seed 32-byte deterministic seed (e.g., SHA-256 of a passphrase).
     * @return A deterministic key pair.
     */
    public static AsymmetricCipherKeyPair generateKeyPairFromSeed(byte[] seed) {
        if (seed.length < 32) throw new IllegalArgumentException("Seed must be at least 32 bytes");
        byte[] keyBytes = new byte[32];
        System.arraycopy(seed, 0, keyBytes, 0, 32);
        Ed25519PrivateKeyParameters privateKey = new Ed25519PrivateKeyParameters(keyBytes, 0);
        Ed25519PublicKeyParameters  publicKey  = privateKey.generatePublicKey();
        return new AsymmetricCipherKeyPair(publicKey, privateKey);
    }

    // -------------------------------------------------------------------------
    // Signing
    // -------------------------------------------------------------------------

    /**
     * Signs an arbitrary byte array using an Ed25519 private key.
     *
     * @param privateKey BouncyCastle Ed25519PrivateKeyParameters.
     * @param message    Raw bytes to sign.
     * @return 64-byte Ed25519 signature.
     */
    public static byte[] sign(Ed25519PrivateKeyParameters privateKey, byte[] message) {
        Ed25519Signer signer = new Ed25519Signer();
        signer.init(true, privateKey);
        signer.update(message, 0, message.length);
        return signer.generateSignature();
    }

    /**
     * Convenience: Sign a UTF-8 string and return the Base64-encoded signature.
     *
     * @param privateKey BouncyCastle Ed25519PrivateKeyParameters.
     * @param data       String to sign (encoded as UTF-8 bytes).
     * @return Base64-encoded 64-byte signature.
     */
    public static String signToBase64(Ed25519PrivateKeyParameters privateKey, String data) {
        byte[] sig = sign(privateKey, data.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        return Base64.getEncoder().encodeToString(sig);
    }

    // -------------------------------------------------------------------------
    // Verification
    // -------------------------------------------------------------------------

    /**
     * Verifies an Ed25519 signature over an arbitrary byte array.
     *
     * @param publicKey BouncyCastle Ed25519PublicKeyParameters.
     * @param message   The original message bytes.
     * @param signature The 64-byte signature to verify.
     * @return true if the signature is valid and covers the message.
     */
    public static boolean verify(Ed25519PublicKeyParameters publicKey, byte[] message, byte[] signature) {
        try {
            Ed25519Signer verifier = new Ed25519Signer();
            verifier.init(false, publicKey);
            verifier.update(message, 0, message.length);
            return verifier.verifySignature(signature);
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * Convenience: Verify a Base64-encoded signature over a UTF-8 string.
     *
     * @param publicKeyBase64  Base64-encoded 32-byte Ed25519 public key.
     * @param data             The original string that was signed.
     * @param signatureBase64  Base64-encoded 64-byte signature.
     * @return true if valid, false if invalid or on any decode error.
     */
    public static boolean verifyFromBase64(String publicKeyBase64, String data, String signatureBase64) {
        try {
            Ed25519PublicKeyParameters pubKey = decodePublicKey(publicKeyBase64);
            byte[] message   = data.getBytes(java.nio.charset.StandardCharsets.UTF_8);
            byte[] signature = Base64.getDecoder().decode(signatureBase64);
            return verify(pubKey, message, signature);
        } catch (Exception e) {
            return false;
        }
    }

    // -------------------------------------------------------------------------
    // Encoding / Decoding
    // -------------------------------------------------------------------------

    /**
     * Encodes an Ed25519 public key to a Base64 string for storage/transmission.
     *
     * @param publicKey BouncyCastle Ed25519PublicKeyParameters.
     * @return Base64-encoded 32-byte public key.
     */
    public static String encodePublicKey(Ed25519PublicKeyParameters publicKey) {
        return Base64.getEncoder().encodeToString(publicKey.getEncoded());
    }

    /**
     * Encodes an Ed25519 private key to a Base64 string for secure storage.
     * WARNING: Private keys must be stored securely (encrypted or in a secure vault).
     *
     * @param privateKey BouncyCastle Ed25519PrivateKeyParameters.
     * @return Base64-encoded 32-byte private key seed.
     */
    public static String encodePrivateKey(Ed25519PrivateKeyParameters privateKey) {
        return Base64.getEncoder().encodeToString(privateKey.getEncoded());
    }

    /**
     * Decodes a Base64-encoded Ed25519 public key.
     *
     * @param base64 Base64-encoded 32-byte public key string.
     * @return BouncyCastle Ed25519PublicKeyParameters.
     */
    public static Ed25519PublicKeyParameters decodePublicKey(String base64) {
        byte[] keyBytes = Base64.getDecoder().decode(base64);
        return new Ed25519PublicKeyParameters(keyBytes, 0);
    }

    /**
     * Decodes a Base64-encoded Ed25519 private key.
     *
     * @param base64 Base64-encoded 32-byte private key seed.
     * @return BouncyCastle Ed25519PrivateKeyParameters.
     */
    public static Ed25519PrivateKeyParameters decodePrivateKey(String base64) {
        byte[] keyBytes = Base64.getDecoder().decode(base64);
        return new Ed25519PrivateKeyParameters(keyBytes, 0);
    }

    // -------------------------------------------------------------------------
    // Address Derivation
    // -------------------------------------------------------------------------

    /**
     * Derives an on-chain address from a Base64-encoded Ed25519 public key.
     * Address = first 40 hex characters of SHA-256(raw_public_key_bytes).
     * This is a one-way function — the address cannot be used to recover the key.
     *
     * @param publicKeyBase64 Base64-encoded Ed25519 public key.
     * @return 40-character lowercase hex address string.
     */
    public static String deriveAddress(String publicKeyBase64) {
        try {
            byte[] keyBytes = Base64.getDecoder().decode(publicKeyBase64);
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(keyBytes);
            StringBuilder sb = new StringBuilder();
            for (byte b : hash) sb.append(String.format("%02x", b));
            return sb.toString().substring(0, 40);
        } catch (Exception e) {
            throw new RuntimeException("Address derivation failed", e);
        }
    }

    // -------------------------------------------------------------------------
    // Hashing Utilities
    // -------------------------------------------------------------------------

    /**
     * SHA-256 hash of a byte array, returned as a lowercase hex string.
     * Used throughout the codebase for message IDs, block hashes, etc.
     */
    public static String sha256Hex(byte[] data) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(data);
            StringBuilder sb = new StringBuilder();
            for (byte b : hash) sb.append(String.format("%02x", b));
            return sb.toString();
        } catch (Exception e) {
            throw new RuntimeException("SHA-256 failed", e);
        }
    }

    /**
     * SHA-256 hash of a UTF-8 string, returned as a lowercase hex string.
     */
    public static String sha256Hex(String data) {
        return sha256Hex(data.getBytes(java.nio.charset.StandardCharsets.UTF_8));
    }

    /**
     * SHA-512 hash of a byte array, returned as a lowercase hex string.
     * Used by the VRF to convert a VRF proof into a uniform hash output.
     */
    public static String sha512Hex(byte[] data) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-512");
            byte[] hash = digest.digest(data);
            StringBuilder sb = new StringBuilder();
            for (byte b : hash) sb.append(String.format("%02x", b));
            return sb.toString();
        } catch (Exception e) {
            throw new RuntimeException("SHA-512 failed", e);
        }
    }
}
