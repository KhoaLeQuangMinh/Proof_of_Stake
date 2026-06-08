package state;

import model.Block;
import model.BlockCertificate;
import model.BlockHeader;
import model.Transaction;

import java.sql.*;
import java.util.ArrayList;
import java.util.List;

/**
 * DatabaseManager — SQLite ACID ledger for permanent block storage.
 *
 * Integration Plan §4.1 (State Engine State Variables):
 *  - DatabaseManager ledger: SQLite database for final ACID commits.
 *  - Per the user's decision: SQLite is kept for the final commit phase.
 *    The PendingStateOverlay handles all pre-commit simulation in RAM.
 *
 * Schema:
 *  - blocks:            Immutable block header records.
 *  - transactions:      All committed transaction records. No nonce_order column.
 *                       Uses firstValid/lastValid for replay protection.
 *  - world_state:       Current balance and stake for each address.
 *  - block_certificates: Finalized certificates for each block.
 *  - node_identity:      This node's Ed25519 public/private keys and address.
 *
 * Threading:
 *  All public methods are synchronized. SQLite is single-writer by default.
 *  Reads can be done concurrently using WAL mode.
 */
public class DatabaseManager {

    private final String dbPath;
    private Connection  conn;

    // -------------------------------------------------------------------------
    // Initialization
    // -------------------------------------------------------------------------

    public DatabaseManager(String dbPath) {
        this.dbPath = dbPath;
    }

    /**
     * Opens the SQLite connection and creates all required tables.
     * Called once during node bootstrap (before any other DB operations).
     */
    public synchronized void initialize() {
        try {
            Class.forName("org.sqlite.JDBC");
            conn = DriverManager.getConnection("jdbc:sqlite:" + dbPath);

            // Enable Write-Ahead Logging for concurrent reads during consensus
            try (Statement st = conn.createStatement()) {
                st.execute("PRAGMA journal_mode=WAL");
                st.execute("PRAGMA synchronous=NORMAL");
                st.execute("PRAGMA foreign_keys=ON");
            }

            createTables();
            System.out.println("[DB] Initialized: " + dbPath);
        } catch (Exception e) {
            throw new RuntimeException("Database initialization failed: " + e.getMessage(), e);
        }
    }

    private void createTables() throws SQLException {
        try (Statement st = conn.createStatement()) {

            // Immutable block headers
            st.execute("""
                CREATE TABLE IF NOT EXISTS blocks (
                    round                   INTEGER PRIMARY KEY,
                    timestamp               INTEGER NOT NULL,
                    previous_hash           TEXT    NOT NULL,
                    hash                    TEXT    NOT NULL UNIQUE,
                    proposer_pub_key        TEXT    NOT NULL,
                    proposer_vrf_proof      TEXT    NOT NULL,
                    seed                    TEXT    NOT NULL,
                    transaction_merkle_root TEXT    NOT NULL,
                    is_empty                INTEGER NOT NULL DEFAULT 0
                )
            """);

            // All committed transactions — firstValid/lastValid replace nonce_order
            st.execute("""
                CREATE TABLE IF NOT EXISTS transactions (
                    tx_id            TEXT    PRIMARY KEY,
                    block_round      INTEGER NOT NULL,
                    type             TEXT    NOT NULL,
                    sender_pub_key   TEXT    NOT NULL,
                    receiver_pub_key TEXT,
                    amount           REAL    NOT NULL,
                    fee              REAL    NOT NULL DEFAULT 0,
                    first_valid      INTEGER NOT NULL,
                    last_valid       INTEGER NOT NULL,
                    timestamp        INTEGER NOT NULL,
                    note             TEXT,
                    signature        TEXT    NOT NULL,
                    FOREIGN KEY (block_round) REFERENCES blocks(round)
                )
            """);

            // Current world state: one row per address
            st.execute("""
                CREATE TABLE IF NOT EXISTS world_state (
                    address         TEXT    PRIMARY KEY,
                    pub_key         TEXT    NOT NULL,
                    balance         REAL    NOT NULL DEFAULT 0,
                    stake_amount    REAL    NOT NULL DEFAULT 0,
                    is_online       INTEGER NOT NULL DEFAULT 0,
                    updated_at_round INTEGER NOT NULL DEFAULT 0
                )
            """);

            // Finalized block certificates
            st.execute("""
                CREATE TABLE IF NOT EXISTS block_certificates (
                    round       INTEGER PRIMARY KEY,
                    block_hash  TEXT    NOT NULL,
                    cert_json   TEXT    NOT NULL,
                    total_weight INTEGER NOT NULL
                )
            """);

            // This node's Ed25519 identity (persisted across restarts)
            st.execute("""
                CREATE TABLE IF NOT EXISTS node_identity (
                    id          INTEGER PRIMARY KEY CHECK (id = 1),
                    address     TEXT    NOT NULL,
                    pub_key     TEXT    NOT NULL,
                    priv_key    TEXT    NOT NULL
                )
            """);

            // Performance indexes
            st.execute("CREATE INDEX IF NOT EXISTS idx_tx_block ON transactions(block_round)");
            st.execute("CREATE INDEX IF NOT EXISTS idx_tx_sender ON transactions(sender_pub_key)");
            st.execute("CREATE INDEX IF NOT EXISTS idx_world_stake ON world_state(stake_amount) WHERE is_online = 1");
        }
    }

    // -------------------------------------------------------------------------
    // Block Operations
    // -------------------------------------------------------------------------

    /**
     * Saves a complete block (header + transactions + world_state) atomically.
     *
     * Integration Plan §4.2 applyBlock():
     *  - Atomic Commit using conn.setAutoCommit(false).
     *  - Custom Transaction Logic for all 5 types.
     *  - DONATE 2% proposer fee credited to b.ProposerAddress.
     *
     * @param block         The certified block to save permanently.
     * @param proposerAddr  The on-chain address of the block proposer (for DONATE fee).
     */
    public synchronized boolean saveBlock(Block block, String proposerAddr) {
        try {
            conn.setAutoCommit(false);

            BlockHeader h = block.getHeader();

            // Insert block header record
            try (PreparedStatement ps = conn.prepareStatement("""
                INSERT OR IGNORE INTO blocks
                    (round, timestamp, previous_hash, hash, proposer_pub_key,
                     proposer_vrf_proof, seed, transaction_merkle_root, is_empty)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
            """)) {
                ps.setLong(1, h.getRound());
                ps.setLong(2, h.getTimestamp());
                ps.setString(3, h.getPreviousBlockHash());
                ps.setString(4, h.getHash() != null ? h.getHash() : Block.BOTTOM_HASH);
                ps.setString(5, h.getProposerPubKey() != null ? h.getProposerPubKey() : "");
                ps.setString(6, h.getProposerVRFProof() != null ? h.getProposerVRFProof() : "");
                ps.setString(7, h.getSeed() != null ? h.getSeed() : "");
                ps.setString(8, h.getTransactionMerkleRoot() != null ? h.getTransactionMerkleRoot() : Block.BOTTOM_HASH);
                ps.setInt(9, block.isEmpty() ? 1 : 0);
                ps.executeUpdate();
            }

            // Apply each transaction
            if (block.getTransactions() != null) {
                for (Transaction tx : block.getTransactions()) {
                    applyTransaction(tx, h.getRound(), proposerAddr);
                }
            }

            conn.commit();
            return true;

        } catch (Exception e) {
            try { conn.rollback(); } catch (SQLException re) { /* ignore */ }
            System.err.println("[DB] saveBlock FAILED round=" + block.getRound() + ": " + e.getMessage());
            return false;
        } finally {
            try { conn.setAutoCommit(true); } catch (SQLException e) { /* ignore */ }
        }
    }

    /**
     * Applies a single transaction's effect to the world_state and inserts the record.
     * Called inside saveBlock's transaction scope.
     */
    private void applyTransaction(Transaction tx, long blockRound, String proposerAddr)
            throws SQLException {
        // Insert transaction record
        try (PreparedStatement ps = conn.prepareStatement("""
            INSERT OR IGNORE INTO transactions
                (tx_id, block_round, type, sender_pub_key, receiver_pub_key,
                 amount, fee, first_valid, last_valid, timestamp, note, signature)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
        """)) {
            ps.setString(1, tx.getTxId());
            ps.setLong(2, blockRound);
            ps.setString(3, tx.getType().name());
            ps.setString(4, tx.getSenderPubKey());
            ps.setString(5, tx.getReceiverPubKey() != null ? tx.getReceiverPubKey() : "");
            ps.setDouble(6, tx.getAmount());
            ps.setDouble(7, tx.getFee());
            ps.setLong(8, tx.getFirstValid());
            ps.setLong(9, tx.getLastValid());
            ps.setLong(10, tx.getTimestamp());
            ps.setString(11, tx.getNote() != null ? tx.getNote() : "");
            ps.setString(12, tx.getEd25519Signature());
            ps.executeUpdate();
        }

        // Apply world_state changes based on transaction type
        switch (tx.getType()) {
            case STAKE -> {
                adjustBalance(tx.getSenderPubKey(), -tx.getAmount(), blockRound);
                adjustStake(tx.getSenderPubKey(), tx.getAmount(), blockRound);
                setOnlineStatus(tx.getSenderPubKey(), true);
            }
            case UNSTAKE -> {
                adjustStake(tx.getSenderPubKey(), -tx.getAmount(), blockRound);
                adjustBalance(tx.getSenderPubKey(), tx.getAmount(), blockRound);
                // If stake drops to 0, mark offline
                double remainingStake = getStakeAmount(tx.getSenderPubKey());
                if (remainingStake <= 0) setOnlineStatus(tx.getSenderPubKey(), false);
            }
            case WITHDRAW -> {
                adjustBalance(tx.getSenderPubKey(), -tx.getAmount(), blockRound);
            }
            case DEPOSIT -> {
                if (tx.getReceiverPubKey() != null && !tx.getReceiverPubKey().isEmpty()) {
                    adjustBalance(tx.getReceiverPubKey(), tx.getAmount(), blockRound);
                }
            }
            case DONATE -> {
                // DONATE: Sender pays full amount, receiver gets 98%, proposer gets 2% fee
                long amountCents   = Math.round(tx.getAmount() * 100.0);
                long feeCents      = (amountCents * 2) / 100;       // 2% proposer fee
                long receiverCents = amountCents - feeCents;         // 98% to receiver

                adjustBalance(tx.getSenderPubKey(), -tx.getAmount(), blockRound);
                if (tx.getReceiverPubKey() != null && !tx.getReceiverPubKey().isEmpty()) {
                    adjustBalance(tx.getReceiverPubKey(), receiverCents / 100.0, blockRound);
                }
                // Credit 2% fee to the proposer
                if (proposerAddr != null && !proposerAddr.isEmpty()) {
                    adjustBalance(proposerAddr, feeCents / 100.0, blockRound);
                }
            }
        }
    }

    /** Adjusts a world_state balance by delta (positive = increase, negative = decrease). */
    private void adjustBalance(String pubKey, double delta, long blockRound) throws SQLException {
        upsertWorldState(pubKey);
        try (PreparedStatement ps = conn.prepareStatement("""
            UPDATE world_state SET balance = balance + ?, updated_at_round = ? WHERE pub_key = ?
        """)) {
            ps.setDouble(1, delta);
            ps.setLong(2, blockRound);
            ps.setString(3, pubKey);
            ps.executeUpdate();
        }
    }

    /** Adjusts a world_state stake by delta. */
    private void adjustStake(String pubKey, double delta, long blockRound) throws SQLException {
        upsertWorldState(pubKey);
        try (PreparedStatement ps = conn.prepareStatement("""
            UPDATE world_state SET stake_amount = stake_amount + ?, updated_at_round = ? WHERE pub_key = ?
        """)) {
            ps.setDouble(1, delta);
            ps.setLong(2, blockRound);
            ps.setString(3, pubKey);
            ps.executeUpdate();
        }
    }

    private void setOnlineStatus(String pubKey, boolean online) throws SQLException {
        upsertWorldState(pubKey);
        try (PreparedStatement ps = conn.prepareStatement(
                "UPDATE world_state SET is_online = ? WHERE pub_key = ?")) {
            ps.setInt(1, online ? 1 : 0);
            ps.setString(2, pubKey);
            ps.executeUpdate();
        }
    }

    /** Ensures a row exists for this pubKey in world_state before update. */
    private void upsertWorldState(String pubKey) throws SQLException {
        String address = crypto.Ed25519Util.deriveAddress(pubKey);
        try (PreparedStatement ps = conn.prepareStatement("""
            INSERT OR IGNORE INTO world_state (address, pub_key, balance, stake_amount, is_online, updated_at_round)
            VALUES (?, ?, 0, 0, 0, 0)
        """)) {
            ps.setString(1, address);
            ps.setString(2, pubKey);
            ps.executeUpdate();
        }
    }

    // -------------------------------------------------------------------------
    // Certificate Storage
    // -------------------------------------------------------------------------

    public synchronized void saveCertificate(BlockCertificate cert) {
        try (PreparedStatement ps = conn.prepareStatement("""
            INSERT OR REPLACE INTO block_certificates (round, block_hash, cert_json, total_weight)
            VALUES (?, ?, ?, ?)
        """)) {
            ps.setLong(1, cert.getRound());
            ps.setString(2, cert.getBlockHash());
            ps.setString(3, cert.toJson());
            ps.setInt(4, cert.getTotalWeight());
            ps.executeUpdate();
        } catch (SQLException e) {
            System.err.println("[DB] saveCertificate failed: " + e.getMessage());
        }
    }

    // -------------------------------------------------------------------------
    // Genesis Bootstrapping
    // -------------------------------------------------------------------------

    /**
     * Injects the genesis state: genesis accounts with initial balances and stakes.
     * Called once on Day 1 when the blocks table is empty.
     *
     * @param genesisAccounts List of accounts to pre-fund at genesis.
     */
    public synchronized void injectGenesis(List<app.GenesisConfig.GenesisAccount> genesisAccounts, Block genesisBlock) {
        try {
            if (getLatestRound() >= 0) {
                System.out.println("[DB] Genesis already injected, skipping.");
                return;
            }

            conn.setAutoCommit(false);

            // Insert genesis block record
            BlockHeader h = genesisBlock.getHeader();
            try (PreparedStatement ps = conn.prepareStatement("""
                INSERT OR IGNORE INTO blocks
                    (round, timestamp, previous_hash, hash, proposer_pub_key,
                     proposer_vrf_proof, seed, transaction_merkle_root, is_empty)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
            """)) {
                ps.setLong(1, 0);
                ps.setLong(2, h.getTimestamp());
                ps.setString(3, Block.BOTTOM_HASH);
                ps.setString(4, h.getHash());
                ps.setString(5, "GENESIS");
                ps.setString(6, "GENESIS");
                ps.setString(7, h.getSeed());
                ps.setString(8, Block.BOTTOM_HASH);
                ps.setInt(9, 0);
                ps.executeUpdate();
            }

            // Create genesis accounts
            for (app.GenesisConfig.GenesisAccount acct : genesisAccounts) {
                try (PreparedStatement ps = conn.prepareStatement("""
                    INSERT OR REPLACE INTO world_state
                        (address, pub_key, balance, stake_amount, is_online, updated_at_round)
                    VALUES (?, ?, ?, ?, ?, 0)
                """)) {
                    ps.setString(1, acct.address);
                    ps.setString(2, acct.pubKey);
                    ps.setDouble(3, acct.initialBalance);
                    ps.setDouble(4, acct.initialStake);
                    ps.setInt(5, acct.initialStake > 0 ? 1 : 0);
                    ps.executeUpdate();
                }
            }

            conn.commit();
            System.out.println("[DB] Genesis block + " + genesisAccounts.size() + " accounts injected.");

        } catch (Exception e) {
            try { conn.rollback(); } catch (SQLException re) { /* ignore */ }
            throw new RuntimeException("Genesis injection failed: " + e.getMessage(), e);
        } finally {
            try { conn.setAutoCommit(true); } catch (SQLException e) { /* ignore */ }
        }
    }

    // -------------------------------------------------------------------------
    // Node Identity
    // -------------------------------------------------------------------------

    public synchronized void saveNodeIdentity(String address, String pubKey, String privKey) {
        try (PreparedStatement ps = conn.prepareStatement("""
            INSERT OR REPLACE INTO node_identity (id, address, pub_key, priv_key)
            VALUES (1, ?, ?, ?)
        """)) {
            ps.setString(1, address);
            ps.setString(2, pubKey);
            ps.setString(3, privKey);
            ps.executeUpdate();
        } catch (SQLException e) {
            System.err.println("[DB] saveNodeIdentity failed: " + e.getMessage());
        }
    }

    public synchronized String[] loadNodeIdentity() {
        try (PreparedStatement ps = conn.prepareStatement(
                "SELECT address, pub_key, priv_key FROM node_identity WHERE id = 1")) {
            ResultSet rs = ps.executeQuery();
            if (rs.next()) {
                return new String[]{ rs.getString("address"), rs.getString("pub_key"), rs.getString("priv_key") };
            }
        } catch (SQLException e) {
            System.err.println("[DB] loadNodeIdentity failed: " + e.getMessage());
        }
        return null;
    }

    // -------------------------------------------------------------------------
    // Reads
    // -------------------------------------------------------------------------

    /** Returns the balance of an address in the current world_state. */
    public synchronized double getBalance(String pubKey) {
        try (PreparedStatement ps = conn.prepareStatement(
                "SELECT balance FROM world_state WHERE pub_key = ?")) {
            ps.setString(1, pubKey);
            ResultSet rs = ps.executeQuery();
            if (rs.next()) return rs.getDouble("balance");
        } catch (SQLException e) { /* fallthrough */ }
        return 0.0;
    }

    /** Returns the staked amount for an address. */
    public synchronized double getStakeAmount(String pubKey) {
        try (PreparedStatement ps = conn.prepareStatement(
                "SELECT stake_amount FROM world_state WHERE pub_key = ?")) {
            ps.setString(1, pubKey);
            ResultSet rs = ps.executeQuery();
            if (rs.next()) return rs.getDouble("stake_amount");
        } catch (SQLException e) { /* fallthrough */ }
        return 0.0;
    }

    /**
     * Returns the total online stake at a historical block round (R-320 lookback).
     * Used by verifyVRFStake to prevent Flash Loan voting attacks.
     *
     * Integration Plan §4.2 getOnlineStake():
     *  Queries the ledger precisely 320 blocks in the past.
     *  Returns total registered online stake.
     */
    public synchronized long getOnlineStakeAtRound(long lookbackRound) {
        // Computes total stake by summing STAKE transactions - UNSTAKE transactions up to lookbackRound
        String sql = """
            SELECT
                COALESCE(SUM(CASE WHEN type='STAKE'   THEN amount ELSE 0 END), 0) -
                COALESCE(SUM(CASE WHEN type='UNSTAKE' THEN amount ELSE 0 END), 0)
            FROM transactions
            WHERE block_round <= ?
        """;
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setLong(1, lookbackRound);
            ResultSet rs = ps.executeQuery();
            if (rs.next()) return (long) rs.getDouble(1);
        } catch (SQLException e) { /* fallthrough */ }
        return 0L;
    }

    /**
     * Returns the stake of a specific address at a historical round (R-320 lookback).
     * Used by verifyVRFStake to validate a sender's claimed sortition weight.
     */
    public synchronized long getAddressStakeAtRound(String pubKey, long lookbackRound) {
        String sql = """
            SELECT
                COALESCE(SUM(CASE WHEN type='STAKE'   THEN amount ELSE 0 END), 0) -
                COALESCE(SUM(CASE WHEN type='UNSTAKE' THEN amount ELSE 0 END), 0)
            FROM transactions
            WHERE block_round <= ? AND sender_pub_key = ?
        """;
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setLong(1, lookbackRound);
            ps.setString(2, pubKey);
            ResultSet rs = ps.executeQuery();
            if (rs.next()) return (long) rs.getDouble(1);
        } catch (SQLException e) { /* fallthrough */ }
        return 0L;
    }

    /** Returns the hash of the latest block, or BOTTOM_HASH if no blocks exist. */
    public synchronized String getLatestBlockHash() {
        try (PreparedStatement ps = conn.prepareStatement(
                "SELECT hash FROM blocks ORDER BY round DESC LIMIT 1")) {
            ResultSet rs = ps.executeQuery();
            if (rs.next()) return rs.getString("hash");
        } catch (SQLException e) { /* fallthrough */ }
        return Block.BOTTOM_HASH;
    }

    /** Returns the seed of the latest block. */
    public synchronized String getLatestSeed() {
        try (PreparedStatement ps = conn.prepareStatement(
                "SELECT seed FROM blocks ORDER BY round DESC LIMIT 1")) {
            ResultSet rs = ps.executeQuery();
            if (rs.next()) return rs.getString("seed");
        } catch (SQLException e) { /* fallthrough */ }
        return app.GenesisConfig.GENESIS_SEED;
    }

    /** Returns the latest committed round number, or -1 if no blocks exist. */
    public synchronized long getLatestRound() {
        try (PreparedStatement ps = conn.prepareStatement(
                "SELECT MAX(round) FROM blocks")) {
            ResultSet rs = ps.executeQuery();
            if (rs.next()) {
                long v = rs.getLong(1);
                return rs.wasNull() ? -1 : v;
            }
        } catch (SQLException e) { /* fallthrough */ }
        return -1;
    }

    /** Checks if a transaction with a given txId already exists (replay protection). */
    public synchronized boolean transactionExists(String txId) {
        try (PreparedStatement ps = conn.prepareStatement(
                "SELECT 1 FROM transactions WHERE tx_id = ?")) {
            ps.setString(1, txId);
            return ps.executeQuery().next();
        } catch (SQLException e) { return false; }
    }
}
