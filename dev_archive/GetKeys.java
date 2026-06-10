import app.Wallet;
import app.GenesisConfig;

public class GetKeys {
    public static void main(String[] args) {
        Wallet w1 = Wallet.fromSeedPhrase("GENESIS_NODE_1");
        System.out.println("GENESIS_NODE_1_PUBKEY: " + w1.getPublicKeyBase64());
        System.out.println("SYSTEM_PUB_KEY: " + GenesisConfig.SYSTEM_PUB_KEY);
    }
}
