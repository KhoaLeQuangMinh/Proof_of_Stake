import app.Wallet;
import crypto.Ed25519Util;
import model.NetworkMessage;

public class TestHandlePeer {
    public static void main(String[] args) throws Exception {
        Wallet w = Wallet.fromSeedPhrase("GENESIS_NODE_4");
        
        long currentRound = 0;
        NetworkMessage msg = new NetworkMessage();
        msg.setType(NetworkMessage.Type.HANDSHAKE);
        msg.setRound(currentRound);
        msg.setSenderPubKey(w.getPublicKeyBase64());
        msg.setPayload("");
        String sig1 = Ed25519Util.signToBase64(w.getPrivateKey(), msg.getSignableData());
        msg.setSignature(sig1);
        
        msg.setLedgerTipHash("bc4ec1c2c02971f21f3b39dfad45b75ed576d2b15640153eef873962aa2d0bf0");
        String sig2 = Ed25519Util.signToBase64(w.getPrivateKey(), msg.getSignableData());
        msg.setSignature(sig2);
        
        System.out.println(msg.toJson());
    }
}
