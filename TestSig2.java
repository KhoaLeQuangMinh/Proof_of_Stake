import app.Wallet;
import crypto.Ed25519Util;
import model.NetworkMessage;

public class TestSig2 {
    public static void main(String[] args) throws Exception {
        Wallet w = Wallet.fromSeedPhrase("GENESIS_NODE_1"); // or another index
        for (int i=0; i<5; i++) {
            w = Wallet.fromSeedPhrase("GENESIS_NODE_" + i);
            if (w.getPublicKeyBase64().equals("vOiMnFvEkGYljrs9X5peF96EQSvdhs3VHFK4VkjQh9A=")) {
                System.out.println("Match! Node " + i);
                
                String badSigData = "HANDSHAKE|1||";
                String badSig = Ed25519Util.signToBase64(w.getPrivateKey(), badSigData);
                System.out.println("Sig without hash: " + badSig);
            }
        }
    }
}
