package restudio.resync.protocol.messages;

import restudio.resync.protocol.MessageType;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;

public class ErrorMessage extends Message {
    private int errorCode;
    private String errorText;

    @Override
    public MessageType getType() {
        return MessageType.ERROR;
    }

    @Override
    public byte[] serialize() {
        byte[] errorTextBytes = errorText != null ? errorText.getBytes(StandardCharsets.UTF_8) : new byte[0];

        ByteBuffer buffer = ByteBuffer.allocate(4 + 4 + errorTextBytes.length);

        buffer.putInt(errorCode);

        buffer.putInt(errorTextBytes.length);
        buffer.put(errorTextBytes);

        return buffer.array();
    }

    @Override
    public void deserialize(ByteBuffer buffer) {
        errorCode = buffer.getInt();

        int errorTextLen = buffer.getInt();
        if (errorTextLen > 0) {
            byte[] errorTextBytes = new byte[errorTextLen];
            buffer.get(errorTextBytes);
            errorText = new String(errorTextBytes, StandardCharsets.UTF_8);
        }
    }

    public int getErrorCode() {
        return errorCode;
    }

    public void setErrorCode(int errorCode) {
        this.errorCode = errorCode;
    }

    public String getErrorText() {
        return errorText;
    }

    public void setErrorText(String errorText) {
        this.errorText = errorText;
    }
}
