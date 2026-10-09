package restudio.resync.network;

public record NetworkAuthentication(String networkId, String nodeId, String credential, String enrollmentToken, String offeredCredential) {
    public NetworkAuthentication {
        networkId = NetworkValues.required(networkId, "Network ID");
        nodeId = NetworkValues.required(nodeId, "Node ID");
        credential = credential == null ? "" : credential;
        enrollmentToken = enrollmentToken == null ? "" : enrollmentToken;
        offeredCredential = offeredCredential == null ? "" : offeredCredential;
        if (networkId.length() > 128 || nodeId.length() > 128 || credential.length() > 128
            || enrollmentToken.length() > 512 || offeredCredential.length() > 128) {
            throw new IllegalArgumentException("Network Authentication Field Is Too Long");
        }
        if (credential.isBlank() && enrollmentToken.isBlank()) throw new IllegalArgumentException("Network Credential Required");
    }
}
