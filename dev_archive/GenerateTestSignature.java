import java.security.SecureRandom;
import java.util.Base64;
import java.nio.charset.StandardCharsets;
import app.Wallet;
import crypto.Ed25519Util;

public class GenerateTestSignature {
    public static void main(String[] args) {
        Wallet w = Wallet.fromSeedPhrase("GENESIS_NODE_1");
        String pubKeyBase64 = w.getPublicKeyBase64();
        
        byte[] dummyRecv = new byte[32];
        String recvBase64 = Base64.getEncoder().encodeToString(dummyRecv);
        
        String type = "DONATE";
        double amount = 50.0;
        long firstValid = 0;
        long lastValid = 999999;
        long timestamp = 1735689600000L;
        String note = null;
        
        String payload = type + "|" + pubKeyBase64 + "|" +
                         recvBase64 + "|" +
                         String.format(java.util.Locale.US, "%.8f", amount) + "|" +
                         firstValid + "|" + lastValid + "|" + timestamp + "|" +
                         (note != null ? note : "");
                         
        String txId = Ed25519Util.sha256Hex(payload);
        
        String sigBase64 = Ed25519Util.signToBase64(w.getPrivateKey(), payload);
        
        System.out.println("TXID: " + txId);
        System.out.println("SENDER: " + pubKeyBase64);
        System.out.println("RECV: " + recvBase64);
        System.out.println("SIG: " + sigBase64);
    }
}
