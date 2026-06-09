import model.NetworkMessage;
public class TestGson {
    public static void main(String[] args) {
        NetworkMessage msg = new NetworkMessage();
        msg.setType(NetworkMessage.Type.HANDSHAKE);
        msg.setRound(0);
        msg.setSignature("abc");
        System.out.println(msg.toJson());
    }
}
