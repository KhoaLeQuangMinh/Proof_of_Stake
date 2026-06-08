package crypto;

import org.bouncycastle.crypto.params.Ed25519PrivateKeyParameters;
import org.bouncycastle.crypto.params.Ed25519PublicKeyParameters;

import java.util.Base64;

/**
 * VRF — Verifiable Random Function using Ed25519.
 *
 * Integration Plan §4.3 (Consensus Engine):
 *  - VRF_Proof = Ed25519_Sign(Node_Private_Key, VRF_Input)
 *    where VRF_Input = previousBlock.Seed (the chained Randomness Beacon).
 *  - VRF_Hash = SHA-512(VRF_Proof)
 *    This maps the 64-byte signature into a uniform 128-hex-char hash.
 *  - Verification: Ed25519_Verify(proposerPubKey, VRF_Input, VRF_Proof)
 *    AND SHA-512(VRF_Proof) == VRF_Hash
 *
 * Security Properties:
 *  1. Pseudorandomness: VRF_Hash is computationally indistinguishable from
 *     random for anyone who does not know the private key.
 *  2. Uniqueness: Only one valid proof exists for a given (privateKey, input) pair.
 *  3. Provability: Anyone with the public key can verify the proof without
 *     knowing the private key.
 *  4. Grinding Attack Resistance: The VRF_Input is the locked Seed from the
 *     previous block. A proposer cannot try different inputs to get a favorable
 *     hash — there is only one valid input for each round.
 *
 * This is a simplified but functionally equivalent VRF for the testbed.
 * A production system would use IETF ECVRF-EDWARDS25519-SHA512-ELL2.
 */
public class VRF {

    // -------------------------------------------------------------------------
    // VRF Output Container
    // -------------------------------------------------------------------------

    /**
     * Encapsulates the two outputs of a VRF evaluation:
     *  - proof:   Base64-encoded 64-byte Ed25519 signature (the proof π).
     *  - hash:    128-char lowercase hex SHA-512 hash of the proof (the output β).
     */
    public static class VRFResult {
        /** Base64-encoded 64-byte VRF proof (Ed25519 signature over VRF_Input). */
        public final String proof;

        /** 128-char hex VRF hash (SHA-512 of proof bytes). Used for sorting/comparison. */
        public final String hash;

        public VRFResult(String proof, String hash) {
            this.proof = proof;
            this.hash  = hash;
        }

        @Override
        public String toString() {
            return "[VRF proof=" + proof.substring(0, 8) + "... hash=" + hash.substring(0, 16) + "...]";
        }
    }

    // -------------------------------------------------------------------------
    // Evaluate
    // -------------------------------------------------------------------------

    /**
     * Evaluates the VRF for a given private key and input string.
     *
     * This is called by every node at the start of Phase 1 and Phase 3.
     * If the resulting VRF_Hash gives the node a positive sortition weight,
     * the node is elected to participate in that phase's committee.
     *
     * @param privateKey The node's Ed25519 private key (Node_Private_Key).
     * @param vrfInput   The VRF input string (typically the previous block's Seed).
     * @return VRFResult containing proof and hash.
     */
    public static VRFResult evaluate(Ed25519PrivateKeyParameters privateKey, String vrfInput) {
        // Step 1: Produce proof = Ed25519_Sign(privateKey, vrfInput)
        byte[] inputBytes = vrfInput.getBytes(java.nio.charset.StandardCharsets.UTF_8);
        byte[] proofBytes = Ed25519Util.sign(privateKey, inputBytes);
        String proofBase64 = Base64.getEncoder().encodeToString(proofBytes);

        // Step 2: Produce hash = SHA-512(proofBytes) -> uniform randomness
        String hashHex = Ed25519Util.sha512Hex(proofBytes);

        return new VRFResult(proofBase64, hashHex);
    }

    // -------------------------------------------------------------------------
    // Verify
    // -------------------------------------------------------------------------

    /**
     * Verifies a VRF proof produced by another node.
     *
     * Called during Phase 2 and Phase 4 when the ConsensusEngine processes
     * proposals and votes from peers. Before accepting a message, the receiver
     * must independently verify the sender's VRF claim.
     *
     * @param publicKey    The sender's Ed25519 public key.
     * @param vrfInput     The VRF input that was used (must match what the sender used).
     * @param proofBase64  The Base64-encoded proof received from the sender.
     * @param expectedHash The VRF hash the sender claimed (extracted from their message).
     * @return true if the proof is valid AND the hash matches SHA-512(proof).
     */
    public static boolean verify(Ed25519PublicKeyParameters publicKey,
                                 String vrfInput,
                                 String proofBase64,
                                 String expectedHash) {
        try {
            // Step 1: Verify the Ed25519 signature (proof)
            byte[] inputBytes = vrfInput.getBytes(java.nio.charset.StandardCharsets.UTF_8);
            byte[] proofBytes = Base64.getDecoder().decode(proofBase64);
            if (!Ed25519Util.verify(publicKey, inputBytes, proofBytes)) {
                return false;
            }

            // Step 2: Verify that SHA-512(proof) == the claimed hash
            String recomputedHash = Ed25519Util.sha512Hex(proofBytes);
            return recomputedHash.equals(expectedHash);
        } catch (Exception e) {
            return false;
        }
    }

    // -------------------------------------------------------------------------
    // Coin Generation (Phase 5 BBA* Common Coin)
    // -------------------------------------------------------------------------

    /**
     * Generates the Common Coin VRF output for a specific BBA* iteration.
     *
     * Integration Plan §5.2 Phase 5 Step C:
     *  Each node runs VRF with a coin-specific input and gossips the result.
     *  The global Common Coin = Lowest VRF Hash among all valid coin proofs.
     *
     * @param privateKey   The node's private key.
     * @param round        Current consensus round.
     * @param panicCounter Current BBA* iteration number (distinguishes each coin).
     * @return VRFResult for this specific BBA* coin iteration.
     */
    public static VRFResult generateCoin(Ed25519PrivateKeyParameters privateKey,
                                         long round, int panicCounter) {
        // Include "COIN:" prefix, round, and iteration to ensure uniqueness per iteration
        String coinInput = "COIN:" + round + ":" + panicCounter;
        return evaluate(privateKey, coinInput);
    }

    // -------------------------------------------------------------------------
    // Common Coin LSB Parity Check
    // -------------------------------------------------------------------------

    /**
     * Extracts the Least Significant Bit (LSB) of the lowest VRF hash
     * to determine the Common Coin's parity.
     *
     * Integration Plan §5.2 Phase 5 Step C:
     *  If LSB == 0 (Even): BBA_Choice = Hash X
     *  If LSB == 1 (Odd):  BBA_Choice = BOTTOM
     *
     * @param vrfHash 128-char hex string VRF hash output.
     * @return 0 (Even) or 1 (Odd) — the global coin flip result.
     */
    public static int getCoinParity(String vrfHash) {
        // The last hex character of the hash represents the least-significant nibble.
        // We extract the very last bit of the hash for even/odd determination.
        char lastHexChar = vrfHash.charAt(vrfHash.length() - 1);
        int lastNibble = Character.digit(lastHexChar, 16);
        return lastNibble & 1; // LSB: 0 = Even, 1 = Odd
    }
}
