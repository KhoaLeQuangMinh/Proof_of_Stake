package state;

import model.Block;
import model.BlockCertificate;
import model.BlockHeader;
import model.Transaction;

import java.io.File;
import java.sql.*;
import java.util.ArrayList;
import java.util.List;

/**
 * DatabaseManager — SQLite ACID ledger for permanent block storage.
 *
 * Integration Plan §4.1 (State Engine State Variables):
 *  - DatabaseManager ledger: SQLite database for final ACID commits.
 *  - The PendingStateOverlay handles all pre-commit simulation in RAM.
 *
 * Fixes applied:
 *  FIX #1:  DB parent directory (nodes/) is created before SQLite opens the file.
 *
 *  FIX #3/#4: injectGenesis() now inserts synthetic STAKE transactions at block_round=0
 *             for all genesis validators. This enables getOnlineStakeAtRound() and
 *             getAddressStakeAtRound() to find historical stake data for round 1 sortition.
 *
 *  FIX #52: getBlock() added — the StateEngine's applyBlock can retrieve a full block for
 *           re-simulation before committing, preventing double-apply on restart.
 *
 *  FIX #53: saveBlock() uses INSERT OR IGNORE + explicit round-existence check so
 *           duplicate save calls (e.g. on restart) don't overwrite already-committed data.
 *
 *  FIX #57: DONATE proposer-fee credit now uses proposerAddr derived from proposerPubKey
 *           (an address string), not a raw public key string — matching the world_state
 *           address column schema.
 *
 *  FIX #64: saveEmptyCertificate() added so Bottom-round certificates are persisted.
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
     *
     * FIX #1: Creates the parent directory (e.g., "nodes/") before opening the DB file.
     *         SQLite cannot create missing parent directories itself, causing startup failure.
     */
    public synchronized void initialize() {
        try {
            // FIX #1: Create parent directory if it doesn't exist
            File dbFile = new File(dbPath);
            File parentDir = dbFile.getParentFile();
            if (parentDir != null && !parentDir.exists()) {
                boolean created = parentDir.mkdirs();
                if (!created) {
                    throw new RuntimeException("Failed to create DB directory: " + parentDir.getAbsolutePath());
                }
            }

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

            // All committed transactions
            // FIX #55 side effect: tx_id is now SHA-256(signable_data), no circular dep
            st.execute("""
                CREATE TABLE IF NOT EXISTS transactions (
                    tx_id            TEXT    PRIMARY KEY,
                    block_round      INTEGER NOT NULL,
                    type             TEXT    NOT NULL,
                    sender_pub_key   TEXT    NOT NULL,
                    receiver_pub_key TEXT,
                    amount           REAL    NOT NULL,
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
                    address          TEXT    PRIMARY KEY,
                    pub_key          TEXT    NOT NULL,
                    balance          REAL    NOT NULL DEFAULT 0,
                    stake_amount     REAL    NOT NULL DEFAULT 0,
                    is_online        INTEGER NOT NULL DEFAULT 0,
                    updated_at_round INTEGER NOT NULL DEFAULT 0
                )
            """);

            // Finalized block certificates
            st.execute("""
                CREATE TABLE IF NOT EXISTS block_certificates (
                    round        INTEGER PRIMARY KEY,
                    block_hash   TEXT    NOT NULL,
                    cert_json    TEXT    NOT NULL,
                    total_weight INTEGER NOT NULL
                )
            """);

            // This node's Ed25519 identity (persisted across restarts)
            st.execute("""
                CREATE TABLE IF NOT EXISTS node_identity (
                    id       INTEGER PRIMARY KEY CHECK (id = 1),
                    address  TEXT    NOT NULL,
                    pub_key  TEXT    NOT NULL,
                    priv_key TEXT    NOT NULL
                )
            """);

            // Performance indexes
            st.execute("CREATE INDEX IF NOT EXISTS idx_tx_block  ON transactions(block_round)");
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
     * FIX #53: Uses INSERT OR IGNORE + pre-check so duplicate round saves are idempotent.
     *          If the round already exists (e.g. node restarted after partial commit),
     *          the existing data is left untouched and false is returned to signal
     *          the caller that no new data was written.
     *
     * @param block         The certified block to save permanently.
     * @param proposerAddr  The on-chain address of the block proposer (for DONATE fee).
     *                      FIX #57: This is an ADDRESS string (40-char hex), not a pub key.
     * @return true if the block was newly committed; false if it was already present.
     */
    public synchronized boolean saveBlock(Block block, String proposerAddr) {
        // FIX #53: Pre-check to avoid unnecessary work on duplicate round
        if (blockExists(block.getRound())) {
            System.out.println("[DB] Block round=" + block.getRound() + " already committed, skipping.");
            return false;
        }

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
     * Applies a single transaction's effect to world_state and inserts the record.
     * Called inside saveBlock's transaction scope.
     *
     * FIX #57: DONATE proposer fee credit uses proposerAddr (an address string, matching
     *          the world_state.address column), not a pub key. Previously this passed
     *          proposerPubKey to adjustBalance() which looked up by pub_key — but the
     *          world_state upsert uses address as primary key, so the fee was lost.
     */
    private void applyTransaction(Transaction tx, long blockRound, String proposerAddr)
            throws SQLException {
        // Insert transaction record
        try (PreparedStatement ps = conn.prepareStatement("""
            INSERT OR IGNORE INTO transactions
                (tx_id, block_round, type, sender_pub_key, receiver_pub_key,
                 amount, first_valid, last_valid, timestamp, note, signature)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
        """)) {
            ps.setString(1, tx.getTxId());
            ps.setLong(2, blockRound);
            ps.setString(3, tx.getType().name());
            ps.setString(4, tx.getSenderPubKey());
            ps.setString(5, tx.getReceiverPubKey() != null ? tx.getReceiverPubKey() : "");
            ps.setDouble(6, tx.getAmount());
            ps.setLong(7, tx.getFirstValid());
            ps.setLong(8, tx.getLastValid());
            ps.setLong(9, tx.getTimestamp());
            ps.setString(10, tx.getNote() != null ? tx.getNote() : "");
            ps.setString(11, tx.getEd25519Signature());
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
                long feeCents      = (amountCents * 2) / 100;
                long receiverCents = amountCents - feeCents;

                adjustBalance(tx.getSenderPubKey(), -tx.getAmount(), blockRound);
                if (tx.getReceiverPubKey() != null && !tx.getReceiverPubKey().isEmpty()) {
                    adjustBalance(tx.getReceiverPubKey(), receiverCents / 100.0, blockRound);
                }
                // FIX #57: Credit 2% fee to the proposer ADDRESS (not pub key)
                if (proposerAddr != null && !proposerAddr.isEmpty()) {
                    adjustBalanceByAddress(proposerAddr, feeCents / 100.0, blockRound);
                }
            }
        }
    }

    /** Adjusts a world_state balance by delta, keyed by pub_key. */
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

    /**
     * FIX #57: Adjusts a world_state balance by delta, keyed by ADDRESS.
     * Used for DONATE proposer-fee credit since proposerAddr is an address, not a pub_key.
     */
    private void adjustBalanceByAddress(String address, double delta, long blockRound) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement("""
            UPDATE world_state SET balance = balance + ?, updated_at_round = ? WHERE address = ?
        """)) {
            ps.setDouble(1, delta);
            ps.setLong(2, blockRound);
            ps.setString(3, address);
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
     * Injects the genesis state: genesis accounts with initial balances, stakes,
     * and synthetic STAKE transactions for historical stake queries.
     *
     * FIX #3/#4: Also inserts synthetic STAKE transactions at block_round=0.
     *             This ensures getOnlineStakeAtRound(lookback) and
     *             getAddressStakeAtRound(pubKey, lookback) return correct non-zero
     *             values for round 1 sortition.
     *
     * @param genesisAccounts List of accounts to pre-fund at genesis.
     * @param genesisBlock    The deterministic genesis block header.
     */
    public synchronized void injectGenesis(List<app.GenesisConfig.GenesisAccount> genesisAccounts,
                                           Block genesisBlock) {
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

            // Create genesis accounts in world_state
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

            // FIX #3/#4: Insert synthetic STAKE transactions for historical lookback queries
            List<model.Transaction> syntheticStakeTxs =
                app.GenesisConfig.buildGenesisSyntheticStakeTransactions(genesisAccounts);
            for (model.Transaction tx : syntheticStakeTxs) {
                try (PreparedStatement ps = conn.prepareStatement("""
                    INSERT OR IGNORE INTO transactions
                        (tx_id, block_round, type, sender_pub_key, receiver_pub_key,
                         amount, first_valid, last_valid, timestamp, note, signature)
                    VALUES (?, 0, 'STAKE', ?, NULL, ?, 0, 9223372036854775807, ?, 'GENESIS_SYNTHETIC_STAKE', ?)
                """)) {
                    ps.setString(1, tx.getTxId());
                    ps.setString(2, tx.getSenderPubKey());
                    ps.setDouble(3, tx.getAmount());
                    ps.setLong(4, app.GenesisConfig.GENESIS_TIMESTAMP);
                    ps.setString(5, tx.getEd25519Signature());
                    ps.executeUpdate();
                }
            }

            conn.commit();
            System.out.println("[DB] Genesis block + " + genesisAccounts.size() +
                               " accounts + " + syntheticStakeTxs.size() + " synthetic stake txs injected.");

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

    /** Returns the balance of an account keyed by public key. */
    public synchronized double getBalance(String pubKey) {
        try (PreparedStatement ps = conn.prepareStatement(
                "SELECT balance FROM world_state WHERE pub_key = ?")) {
            ps.setString(1, pubKey);
            ResultSet rs = ps.executeQuery();
            if (rs.next()) return rs.getDouble("balance");
        } catch (SQLException e) { /* fallthrough */ }
        return 0.0;
    }

    /** Returns the staked amount for a public key. */
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
     * FIX #3/#4: Now finds data because genesis synthetic STAKE txs exist in round 0.
     */
    public synchronized long getOnlineStakeAtRound(long lookbackRound) {
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
     * Returns the stake of a specific address at a historical round.
     * FIX #3/#4: Now finds data because genesis synthetic STAKE txs exist in round 0.
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
        try (PreparedStatement ps = conn.prepareStatement("SELECT MAX(round) FROM blocks")) {
            ResultSet rs = ps.executeQuery();
            if (rs.next()) {
                long v = rs.getLong(1);
                return rs.wasNull() ? -1 : v;
            }
        } catch (SQLException e) { /* fallthrough */ }
        return -1;
    }

    /** Checks if a block with the given round already exists. FIX #53. */
    public synchronized boolean blockExists(long round) {
        try (PreparedStatement ps = conn.prepareStatement("SELECT 1 FROM blocks WHERE round = ?")) {
            ps.setLong(1, round);
            return ps.executeQuery().next();
        } catch (SQLException e) { return false; }
    }

    /** Checks if a transaction with a given txId already exists (replay protection). */
    public synchronized boolean transactionExists(String txId) {
        try (PreparedStatement ps = conn.prepareStatement(
                "SELECT 1 FROM transactions WHERE tx_id = ?")) {
            ps.setString(1, txId);
            return ps.executeQuery().next();
        } catch (SQLException e) { return false; }
    }

    /**
     * FIX #52: Loads a committed block from the DB for re-simulation in applyBlock.
     * Returns null if the block does not exist.
     */
    public synchronized Block getBlock(long round) {
        try (PreparedStatement ps = conn.prepareStatement(
                "SELECT * FROM blocks WHERE round = ?")) {
            ps.setLong(1, round);
            ResultSet rs = ps.executeQuery();
            if (!rs.next()) return null;

            BlockHeader h = new BlockHeader();
            h.setRound(rs.getLong("round"));
            h.setTimestamp(rs.getLong("timestamp"));
            h.setPreviousBlockHash(rs.getString("previous_hash"));
            h.setHash(rs.getString("hash"));
            h.setProposerPubKey(rs.getString("proposer_pub_key"));
            h.setProposerVRFProof(rs.getString("proposer_vrf_proof"));
            h.setSeed(rs.getString("seed"));
            h.setTransactionMerkleRoot(rs.getString("transaction_merkle_root"));

            Block block = new Block();
            block.setHeader(h);
            block.setTransactions(getTransactionsForRound(round));
            return block;
        } catch (SQLException e) { return null; }
    }

    /** Loads all transactions committed in a specific block round. */
    private List<Transaction> getTransactionsForRound(long round) {
        List<Transaction> result = new ArrayList<>();
        try (PreparedStatement ps = conn.prepareStatement(
                "SELECT * FROM transactions WHERE block_round = ?")) {
            ps.setLong(1, round);
            ResultSet rs = ps.executeQuery();
            while (rs.next()) {
                Transaction tx = new Transaction();
                tx.setTxId(rs.getString("tx_id"));
                try {
                    tx.setType(Transaction.Type.valueOf(rs.getString("type")));
                } catch (IllegalArgumentException e) { continue; }
                tx.setSenderPubKey(rs.getString("sender_pub_key"));
                tx.setReceiverPubKey(rs.getString("receiver_pub_key"));
                tx.setAmount(rs.getDouble("amount"));
                tx.setFirstValid(rs.getLong("first_valid"));
                tx.setLastValid(rs.getLong("last_valid"));
                tx.setTimestamp(rs.getLong("timestamp"));
                tx.setNote(rs.getString("note"));
                tx.setEd25519Signature(rs.getString("signature"));
                result.add(tx);
            }
        } catch (SQLException e) { /* return partial */ }
        return result;
    }
}
