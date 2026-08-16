package taostore.proxy;

import comunication.Message;
import comunication.MessageProcessor;
import taostore.messages.ClientRequestMessage;
import taostore.messages.MessageTypes;

public class ProxyCommunicationHandler extends MessageProcessor {
    private final Sequencer sequencer;

    public ProxyCommunicationHandler(Sequencer sequencer) {
        super(MessageTypes.CLIENT_REQUEST);
        this.sequencer = sequencer;
    }

    @Override
    public void deliverMessage(Message message) {
        ClientRequestMessage req = ClientRequestMessage.fromBytes(message.getSerializedMessage());
        sequencer.submitRequest(message.getSender(), req);
    }
}
