package io.replikativ.kabel;

import javax.websocket.MessageHandler;
import java.nio.ByteBuffer;

public interface MessageHandlerBinary extends MessageHandler.Whole<ByteBuffer> {

}
