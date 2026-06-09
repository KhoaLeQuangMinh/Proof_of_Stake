import app.Wallet;
import crypto.Ed25519Util;

public class TestSig4 {
    public static void main(String[] args) throws Exception {
        Wallet w = Wallet.fromSeedPhrase("GENESIS_NODE_5");
        String badSigData = "HANDSHAKE|1||";
        String badSig = Ed25519Util.signToBase64(w.getPrivateKey(), badSigData);
        System.out.println("Sig without hash: " + badSig);
        
        String goodSigData = "HANDSHAKE|1||44924618b5f2e605ee9e3328923d6be922c9af2141cc5e6f8e9d4aca11940b12";
        String goodSig = Ed25519Util.signToBase64(w.getPrivateKey(), goodSigData);
        System.out.println("Sig WITH hash: " + goodSig);
    }
}
