package org.restcomm.slee.resource.smpp;

import org.restcomm.slee.resource.smpp.SmppSessionsImpl.SenderThread;

public class EsmeSender {
	
	private SenderThread requestThread;
	private SenderThread responseThread;

	public EsmeSender(SenderThread requestThread, SenderThread responseThread) {
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
