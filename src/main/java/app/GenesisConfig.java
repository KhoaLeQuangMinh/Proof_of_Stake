package app;

import crypto.Ed25519Util;
import model.Block;
import model.BlockHeader;

import java.util.ArrayList;
import java.util.List;

/**
 * GenesisConfig — Bootstrap configuration for the chain's initial state.
 *
 * Integration Plan §1.1:
 *  - Defines the genesis block (round 0) with a hardcoded initial Seed.
 *  - Defines all initial validator accounts with balances and stakes.
 *  - The genesis block is the anchor of the entire chain. Its hash is
 *    the previousBlockHash for round 1's block.
 *
 * Genesis Seed:
 *  The initial Randomness Beacon seed is a hardcoded public constant.
 *  It is not derived from any private key — it is known to all nodes
 *  before the network starts. All subsequent seeds are derived from
 *  VRF proofs, making them unpredictable.
 */
public class GenesisConfig {

    // -------------------------------------------------------------------------
    // Genesis Constants
    // -------------------------------------------------------------------------

    /**
     * The initial Randomness Beacon seed (public constant, hardcoded).
     * Used as VRF_Input for round 1's Phase 1 lottery.
     * Chosen as SHA-256("ALGORAND_GENESIS_V1") for auditability.
     */
    public static final String GENESIS_SEED =
        Ed25519Util.sha256Hex("ALGORAND_GENESIS_V1");

    /**
     * The genesis block hash. Computed from the genesis header.
     * Used as previousBlockHash by round 1.
     */
    public static String GENESIS_HASH;

    // -------------------------------------------------------------------------
    // Genesis Account Structure
    // -------------------------------------------------------------------------

    /**
     * A genesis account: a validator with initial balance and stake.
     */
    public static class GenesisAccount {
        /** 40-char hex on-chain address (SHA-256(pubKey)[0:40]). */
        public final String address;

        /** Base64-encoded Ed25519 public key. */
        public final String pubKey;

        /** Starting balance in token units (available for transactions). */
        public final double initialBalance;

        /** Starting stake in token units (activates as validator immediately). */
        public final double initialStake;

        public GenesisAccount(String address, String pubKey, double initialBalance, double initialStake) {
            this.address        = address;
            this.pubKey         = pubKey;
            this.initialBalance = initialBalance;
            this.initialStake   = initialStake;
        }
    }

    // -------------------------------------------------------------------------
    // Network Configuration
    // -------------------------------------------------------------------------

    /**
     * Generates the genesis accounts for the testbed network.
     *
     * Integration Plan §5.1:
     *  Uses deterministic seed-based wallets for all genesis validators.
     *  A node is valid only if its stake > 0 AND registered before round R-320.
     *  At genesis, all validators are pre-registered with stake > 0.
     *
     * For the testbed: 5 genesis validators, each with 10,000 balance and 5,000 stake.
     * Total online stake at genesis = 5 * 5000 = 25,000 tokens.
     *
     * @param nodeCount Number of genesis validator nodes to pre-register.
     * @return List of genesis accounts.
     */
    public static List<GenesisAccount> getGenesisAccounts(int nodeCount) {
        List<GenesisAccount> accounts = new ArrayList<>();
        for (int i = 1; i <= nodeCount; i++) {
            Wallet w = Wallet.fromSeedPhrase("GENESIS_NODE_" + i);
            accounts.add(new GenesisAccount(
                w.getAddress(),
                w.getPublicKeyBase64(),
                10_000.0,  // Initial balance
                5_000.0    // Initial stake (makes the node a validator immediately)
            ));
        }
        return accounts;
    }

    // -------------------------------------------------------------------------
    // Genesis Block Construction
    // -------------------------------------------------------------------------

    /**
     * Constructs the genesis block (round 0).
     * No proposer, no transactions, no VRF proof.
     * Its hash is the chain anchor for all subsequent blocks.
     *
     * @return The genesis Block.
     */
    public static Block buildGenesisBlock() {
        BlockHeader header = new BlockHeader();
        header.setRound(0);
        header.setTimestamp(System.currentTimeMillis());
        header.setPreviousBlockHash(Block.BOTTOM_HASH); // No previous block
        header.setTransactionMerkleRoot(Block.BOTTOM_HASH);
        header.setProposerVRFProof("GENESIS");
        header.setProposerPubKey("GENESIS");
        header.setEd25519Signature("GENESIS");
        header.setSeed(GENESIS_SEED);

        // Genesis hash = SHA-256(header signable data + GENESIS_SEED)
        String genesisHash = Ed25519Util.sha256Hex(header.getSignableData() + GENESIS_SEED);
        header.setHash(genesisHash);
        GENESIS_HASH = genesisHash;

        Block genesis = new Block();
        genesis.setHeader(header);
        genesis.setTransactions(new ArrayList<>());
        return genesis;
    }

    // -------------------------------------------------------------------------
    // Seed Peers Configuration
    // -------------------------------------------------------------------------

    /**
     * Returns the list of seed relay node addresses for P2P bootstrapping.
     * Format: "host:port"
     *
     * For a local testbed, all nodes are on localhost with sequential ports.
     *
     * @param nodeCount Number of nodes in the testbed.
     * @param myPort    This node's own port (excluded from peers list).
     * @return List of "host:port" strings for seed peers.
     */
    public static List<String> getSeedPeers(int nodeCount, int myPort) {
        List<String> peers = new ArrayList<>();
        for (int i = 0; i < nodeCount; i++) {
            int port = 9000 + i;
            if (port != myPort) {
                peers.add("localhost:" + port);
            }
        }
        return peers;
    }
}
