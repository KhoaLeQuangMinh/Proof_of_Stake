import app.Wallet;
import crypto.Ed25519Util;
import model.NetworkMessage;

public class TestSig {
    public static void main(String[] args) throws Exception {
        for (int i=1; i<=5; i++) {
            Wallet w = Wallet.fromSeedPhrase("GENESIS_NODE_" + i);
            if (w.getPublicKeyBase64().equals("PCLI8/SxAnzLeMl+A6d2li03kRKLtCVDV8da7IpVkes=")) {
                System.out.println("Match! " + i);
                String sigData = "HANDSHAKE|0||bc4ec1c2c02971f21f3b39dfad45b75ed576d2b15640153eef873962aa2d0bf0";
                String expectedSig = Ed25519Util.signToBase64(w.getPrivateKey(), sigData);
                System.out.println("Expected Sig: " + expectedSig);
                System.out.println("Actual   Sig: c7BmgGlJb2Xzpej86ViFbLWQmzgW0mMrcRNH5mJCJT5mTFZ/qtDGTCfFVdfU8OYpB2gkBQFwVXO2AaYJPtVPBg==");
                
                String badSigData = "HANDSHAKE|0||";
                String badSig = Ed25519Util.signToBase64(w.getPrivateKey(), badSigData);
                System.out.println("Sig without hash: " + badSig);
            }
        }
    }
}
