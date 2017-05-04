package org.restcomm.slee.resource.smpp;

import org.restcomm.smpp.Esme;

import com.cloudhopper.smpp.pdu.PduRequest;
import com.cloudhopper.smpp.pdu.PduResponse;

public class SmppSendingTask {

	private Esme esme;
	private PduRequest request;
	long timeoutMillis;
	private PduResponse response;
	private SmppTransactionImpl smppServerTransaction;

	public SmppSendingTask(Esme esme, PduRequest request, long timeoutMillis,
			PduResponse response, SmppTransactionImpl smppServerTransaction) {
		this.esme = esme;
		this.request = request;
		this.timeoutMillis = timeoutMillis;
		this.response = response;
		this.smppServerTransaction = smppServerTransaction;
	}

	public Esme getEsme() {
		return esme;
	}

	public PduRequest getRequest() {
		return request;
	}

	public long getTimeoutMillis() {
		return timeoutMillis;
	}

	public PduResponse getResponse() {
		return response;
	}

	public SmppTransactionImpl getSmppServerTransaction() {
		return smppServerTransaction;
	}
}
