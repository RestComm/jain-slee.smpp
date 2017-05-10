package org.restcomm.slee.resource.smpp;

import org.restcomm.slee.resource.smpp.SmppSessionsImpl.RequestSender;
import org.restcomm.slee.resource.smpp.SmppSessionsImpl.ResponseSender;

public class EsmeSender {

    private RequestSender requestThread;
    private ResponseSender responseThread;

    public EsmeSender(RequestSender requestThread, ResponseSender responseThread) {
        this.requestThread = requestThread;
        this.responseThread = responseThread;
    }

    public void start() {
        requestThread.start();
        responseThread.start();
    }

    public void deactivate() {
        requestThread.deactivate();
        responseThread.deactivate();
    }

    public void offerRequest(SmppSendingTask task) {
        requestThread.offer(task);
    }

    public void offerResponse(SmppSendingTask task) {
        responseThread.offer(task);
    }
}
