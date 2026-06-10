import app.Wallet;

public class test_pubkey {
    public static void main(String[] args) {
        Wallet w1 = Wallet.fromSeedPhrase("GENESIS_NODE_1");
        System.out.println("GENESIS_NODE_1 pubkey: " + w1.getPublicKeyBase64());
        System.out.println("SYSTEM_PUB_KEY: " + app.GenesisConfig.SYSTEM_PUB_KEY);
    }
}
