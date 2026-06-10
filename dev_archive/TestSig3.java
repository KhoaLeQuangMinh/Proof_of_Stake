import app.Wallet;
public class TestSig3 {
    public static void main(String[] args) throws Exception {
        for (int i=0; i<10; i++) {
            Wallet w = Wallet.fromSeedPhrase("NODE_" + i);
            if (w.getPublicKeyBase64().equals("vOiMnFvEkGYljrs9X5peF96EQSvdhs3VHFK4VkjQh9A=")) {
                System.out.println("Match NODE " + i);
            }
            w = Wallet.fromSeedPhrase("GENESIS_NODE_" + i);
            if (w.getPublicKeyBase64().equals("vOiMnFvEkGYljrs9X5peF96EQSvdhs3VHFK4VkjQh9A=")) {
                System.out.println("Match GENESIS_NODE_" + i);
            }
        }
    }
}
