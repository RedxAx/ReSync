package restudio.resync.network;

public final class NetworkAuthenticationCodec {
    public static final int MAXIMUM_BYTES = 2048;

    private NetworkAuthenticationCodec() {
    }

    public static byte[] encode(NetworkAuthentication value) {
        byte[] bytes = NetworkEditorCodec.encode(output -> {
            NetworkEditorCodec.string(output, value.networkId(), 512);
            NetworkEditorCodec.string(output, value.nodeId(), 512);
            NetworkEditorCodec.string(output, value.credential(), 128);
            NetworkEditorCodec.string(output, value.enrollmentToken(), 512);
            NetworkEditorCodec.string(output, value.offeredCredential(), 128);
        });
        NetworkPayloads.requireLimit(bytes, MAXIMUM_BYTES);
        return bytes;
    }

    public static NetworkAuthentication decode(byte[] bytes) {
        NetworkPayloads.requireLimit(bytes, MAXIMUM_BYTES);
        return NetworkEditorCodec.decode(bytes, input -> new NetworkAuthentication(
            NetworkEditorCodec.string(input, 512), NetworkEditorCodec.string(input, 512),
            NetworkEditorCodec.string(input, 128), NetworkEditorCodec.string(input, 512), NetworkEditorCodec.string(input, 128)));
    }
}
