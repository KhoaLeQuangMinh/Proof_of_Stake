package tools;

import app.Wallet;
import com.google.gson.Gson;
import crypto.Ed25519Util;
import model.Transaction;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;

public class TxGenerator {

    private static final String MEMPOOL_URL = "http://localhost:8080/inject";

    public static void main(String[] args) throws Exception {
        System.out.println("[TxGenerator] Initializing 10-node transaction payload test...");
        
        // 1. Derive 10 User Wallets
        List<Wallet> users = new ArrayList<>();
        for (int i = 1; i <= 10; i++) {
            users.add(Wallet.fromSeedPhrase("GENESIS_NODE_" + i));
        }

        // 2. Derive System Key
        byte[] systemSeed = new byte[32];
        String hexSeed = Ed25519Util.sha256Hex("SYSTEM_DEPOSIT_KEY_V1");
        for (int i = 0; i < 32; i++) {
            systemSeed[i] = (byte) Integer.parseInt(hexSeed.substring(i * 2, i * 2 + 2), 16);
        }
        org.bouncycastle.crypto.AsymmetricCipherKeyPair kp = Ed25519Util.generateKeyPairFromSeed(systemSeed);
        org.bouncycastle.crypto.params.Ed25519PrivateKeyParameters sysPrivKey = (org.bouncycastle.crypto.params.Ed25519PrivateKeyParameters) kp.getPrivate();
        String sysPubKey = Ed25519Util.encodePublicKey((org.bouncycastle.crypto.params.Ed25519PublicKeyParameters) kp.getPublic());

        List<Transaction> txs = new ArrayList<>();
        long timestamp = System.currentTimeMillis();

        // Helper to sign transactions
        java.util.function.Consumer<Transaction> signTx = (tx) -> {
            org.bouncycastle.crypto.params.Ed25519PrivateKeyParameters privKey = null;
            if (tx.getSenderPubKey().equals(sysPubKey)) {
                privKey = sysPrivKey;
            } else {
                for (Wallet w : users) {
                    if (w.getPublicKeyBase64().equals(tx.getSenderPubKey())) {
                        privKey = w.getPrivateKey();
                        break;
                    }
                }
            }
            if (privKey == null) throw new RuntimeException("Private key not found for sender.");
            tx.setEd25519Signature(Ed25519Util.signToBase64(privKey, tx.getSignableData()));
            tx.setTxId(Ed25519Util.sha256Hex(tx.getSignableData()));
        };

        // Tx 1-10: DEPOSIT 50000 to all 10 users
        for (int i = 0; i < 10; i++) {
            Transaction tx = new Transaction();
            tx.setType(Transaction.Type.DEPOSIT);
            tx.setSenderPubKey(sysPubKey);
            tx.setReceiverPubKey(users.get(i).getPublicKeyBase64());
            tx.setAmount(50000.0);
            tx.setFirstValid(0);
            tx.setLastValid(999999);
            tx.setTimestamp(timestamp++);
            signTx.accept(tx);
            txs.add(tx);
        }

        // Tx 11-13: STAKE 5000 by User 6, 7, 8
        for (int i = 5; i < 8; i++) {
            Transaction tx = new Transaction();
            tx.setType(Transaction.Type.STAKE);
            tx.setSenderPubKey(users.get(i).getPublicKeyBase64());
            tx.setReceiverPubKey("");
            tx.setAmount(5000.0);
            tx.setFirstValid(0);
            tx.setLastValid(999999);
            tx.setTimestamp(timestamp++);
            signTx.accept(tx);
            txs.add(tx);
        }

        // Tx 14-18: UNSTAKE 10 by User 1, 2, 3, 4, 5
        for (int i = 0; i < 5; i++) {
            Transaction tx = new Transaction();
            tx.setType(Transaction.Type.UNSTAKE);
            tx.setSenderPubKey(users.get(i).getPublicKeyBase64());
            tx.setReceiverPubKey("");
            tx.setAmount(10.0);
            tx.setFirstValid(0);
            tx.setLastValid(999999);
            tx.setTimestamp(timestamp++);
            signTx.accept(tx);
            txs.add(tx);
        }

        // Tx 19-23: WITHDRAW 10 by User 1, 2, 3, 4, 5
        for (int i = 0; i < 5; i++) {
            Transaction tx = new Transaction();
            tx.setType(Transaction.Type.WITHDRAW);
            tx.setSenderPubKey(users.get(i).getPublicKeyBase64());
            tx.setReceiverPubKey(sysPubKey);
            tx.setAmount(10.0);
            tx.setFirstValid(0);
            tx.setLastValid(999999);
            tx.setTimestamp(timestamp++);
            signTx.accept(tx);
            txs.add(tx);
        }

        // Tx 24-100: DONATE 1.0 by Random User to Random User
        Random rand = new Random(42);
        for (int i = 23; i < 100; i++) {
            int senderIdx = rand.nextInt(10);
            int receiverIdx = rand.nextInt(10);
            while (receiverIdx == senderIdx) receiverIdx = rand.nextInt(10);

            Transaction tx = new Transaction();
            tx.setType(Transaction.Type.DONATE);
            tx.setSenderPubKey(users.get(senderIdx).getPublicKeyBase64());
            tx.setReceiverPubKey(users.get(receiverIdx).getPublicKeyBase64());
            tx.setAmount(1.0);
            tx.setFirstValid(0);
            tx.setLastValid(999999);
            tx.setTimestamp(timestamp++);
            signTx.accept(tx);
            txs.add(tx);
        }

        System.out.println("[TxGenerator] Successfully generated 100 transactions.");

        // Inject them!
        HttpClient client = HttpClient.newHttpClient();
        Gson gson = new Gson();
        int successCount = 0;

        for (int i = 0; i < txs.size(); i++) {
            Transaction tx = txs.get(i);
            String jsonPayload = gson.toJson(tx);
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(MEMPOOL_URL))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(jsonPayload))
                    .build();

            HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() == 200) {
                successCount++;
                if (i > 0 && i % 10 == 0) {
                    System.out.println("[TxGenerator] Injected " + i + "/100...");
                }
            }
        }

        System.out.println("[TxGenerator] Finished injecting " + successCount + "/100 transactions.");
    }
}
