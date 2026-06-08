package app;

import crypto.Ed25519Util;
import org.bouncycastle.crypto.AsymmetricCipherKeyPair;
import org.bouncycastle.crypto.params.Ed25519PrivateKeyParameters;
import org.bouncycastle.crypto.params.Ed25519PublicKeyParameters;
import state.DatabaseManager;

/**
 * Wallet — Node Identity Container.
 *
 * This class holds the node's Ed25519 key pair in memory.
 * It maps to the Node_Private_Key variable referenced throughout
 * the ConsensusEngine (Phase 1, Phase 3, Phase 5, Phase 6).
 *
 * The Wallet does NOT have a consensus loop — it is purely a
 * cryptographic identity wrapper, as discussed in our architecture analysis.
 *
 * Address Derivation:
 *  address = SHA-256(publicKeyBytes)[0:40] (lowercase hex, 20 bytes)
 *
 * Persistence:
 *  The key pair is stored in the SQLite database (node_identity table).
 *  On restart, it is reloaded so the node maintains the same identity.
 */
public class Wallet {

    private final Ed25519PrivateKeyParameters privateKey;
    private final Ed25519PublicKeyParameters  publicKey;
    private final String                       publicKeyBase64;
    private final String                       address;

    // -------------------------------------------------------------------------
    // Constructors
    // -------------------------------------------------------------------------

    /**
     * Creates a Wallet from an existing Ed25519 key pair.
     */
    public Wallet(Ed25519PrivateKeyParameters privateKey, Ed25519PublicKeyParameters publicKey) {
        this.privateKey      = privateKey;
        this.publicKey       = publicKey;
        this.publicKeyBase64 = Ed25519Util.encodePublicKey(publicKey);
        this.address         = Ed25519Util.deriveAddress(this.publicKeyBase64);
    }

    // -------------------------------------------------------------------------
    // Factory Methods
    // -------------------------------------------------------------------------

    /**
     * Generates a brand-new random Wallet and persists it to the database.
     * Called on the first boot of a new node.
     *
     * @param db The DatabaseManager to persist the identity to.
     * @return A new Wallet with a fresh Ed25519 key pair.
     */
    public static Wallet generateAndPersist(DatabaseManager db) {
        AsymmetricCipherKeyPair kp = Ed25519Util.generateKeyPair();
        Ed25519PrivateKeyParameters privateKey = (Ed25519PrivateKeyParameters) kp.getPrivate();
        Ed25519PublicKeyParameters  publicKey  = (Ed25519PublicKeyParameters)  kp.getPublic();

        Wallet wallet = new Wallet(privateKey, publicKey);
        db.saveNodeIdentity(wallet.address, wallet.publicKeyBase64,
                            Ed25519Util.encodePrivateKey(privateKey));

        System.out.println("[Wallet] Generated new identity.");
        System.out.println("[Wallet] Address : " + wallet.address);
        System.out.println("[Wallet] PubKey  : " + wallet.publicKeyBase64.substring(0, 16) + "...");
        return wallet;
    }

    /**
     * Loads an existing Wallet from the database (node restart).
     *
     * @param db The DatabaseManager to load the identity from.
     * @return The persisted Wallet, or null if no identity exists yet.
     */
    public static Wallet loadFromDB(DatabaseManager db) {
        String[] identity = db.loadNodeIdentity();
        if (identity == null) return null;

        Ed25519PrivateKeyParameters privateKey = Ed25519Util.decodePrivateKey(identity[2]);
        Ed25519PublicKeyParameters  publicKey  = privateKey.generatePublicKey();

        Wallet wallet = new Wallet(privateKey, publicKey);
        System.out.println("[Wallet] Loaded identity from DB.");
        System.out.println("[Wallet] Address : " + wallet.address);
        return wallet;
    }

    /**
     * Creates a Wallet from a deterministic seed (used for genesis validators).
     * The seed is SHA-256 of "GENESIS_NODE_N" to ensure all nodes agree on
     * who the genesis validators are.
     *
     * @param seedPhrase A unique string (e.g., "GENESIS_NODE_1").
     * @return A deterministic Wallet derived from the seed.
     */
    public static Wallet fromSeedPhrase(String seedPhrase) {
        byte[] seed = Ed25519Util.sha256Hex(seedPhrase)
                                 .getBytes(java.nio.charset.StandardCharsets.UTF_8);
        // Use first 32 bytes of the SHA-256 hex (which is 64 chars -> 32 bytes in UTF-8)
        byte[] keyBytes = new byte[32];
        // Convert hex chars to actual bytes
        String hexSeed = Ed25519Util.sha256Hex(seedPhrase);
        for (int i = 0; i < 32; i++) {
            keyBytes[i] = (byte) Integer.parseInt(hexSeed.substring(i * 2, i * 2 + 2), 16);
        }

        AsymmetricCipherKeyPair kp = Ed25519Util.generateKeyPairFromSeed(keyBytes);
        Ed25519PrivateKeyParameters privateKey = (Ed25519PrivateKeyParameters) kp.getPrivate();
        Ed25519PublicKeyParameters  publicKey  = (Ed25519PublicKeyParameters)  kp.getPublic();
        return new Wallet(privateKey, publicKey);
    }

    // -------------------------------------------------------------------------
    // Getters
    // -------------------------------------------------------------------------

    /** Ed25519 private key used for VRF sortition and message signing. */
    public Ed25519PrivateKeyParameters getPrivateKey()    { return privateKey; }

    /** Ed25519 public key used for signature verification by peers. */
    public Ed25519PublicKeyParameters  getPublicKey()     { return publicKey; }

    /** Base64-encoded Ed25519 public key (used in all network messages). */
    public String getPublicKeyBase64()                    { return publicKeyBase64; }

    /** 40-char hex on-chain address derived from the public key. */
    public String getAddress()                            { return address; }

    @Override
    public String toString() {
        return "[Wallet addr=" + address + " pubKey=" + publicKeyBase64.substring(0, 8) + "...]";
    }
}
