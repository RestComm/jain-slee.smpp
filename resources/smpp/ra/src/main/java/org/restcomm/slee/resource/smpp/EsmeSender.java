package org.restcomm.slee.resource.smpp;

public class EsmeSender {

    public static final String LOGGER_TAG = "TAG ISSUE:34388";
    
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

    public void offerRequest(SmppRequestTask task) {
        requestThread.offer(task);
    }

    public void offerResponse(SmppResponseTask task) {
        responseThread.offer(task);
    }
    
    public long getRequestSenderPreviousIterationTime() {
        return requestThread.getPreviousIterationTime();
    }
    
    public long getResponseSenderPreviousIterationTime() {
        return responseThread.getPreviousIterationTime();
    }
}
