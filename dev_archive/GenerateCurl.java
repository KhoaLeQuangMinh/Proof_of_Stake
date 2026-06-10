import app.Wallet;
import model.Transaction;
import crypto.Ed25519Util;
import com.google.gson.Gson;

public class GenerateCurl {
    public static void main(String[] args) {
        Wallet w = Wallet.fromSeedPhrase("GENESIS_NODE_1");
        
        Transaction tx = new Transaction();
        tx.setTxId("TEST_DONATE_123");
        tx.setType(Transaction.Type.DONATE);
        tx.setSenderPubKey(w.getPublicKeyBase64());
        tx.setReceiverPubKey("TARGET_USER_PUBKEY");
        tx.setAmount(50.0);
        tx.setFirstValid(0);
        tx.setLastValid(999999);
        tx.setTimestamp(1735689600000L);
        
        String sig = Ed25519Util.signToBase64(w.getPrivateKey(), tx.getSignableData());
        tx.setEd25519Signature(sig);
        
        System.out.println("curl -X POST http://localhost:8080/inject -H \"Content-Type: application/json\" -d '" + new Gson().toJson(tx) + "'");
    }
}
