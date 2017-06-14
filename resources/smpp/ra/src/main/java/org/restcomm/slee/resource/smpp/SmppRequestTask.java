package org.restcomm.slee.resource.smpp;

import org.restcomm.smpp.Esme;

import com.cloudhopper.smpp.pdu.PduRequest;

public class SmppRequestTask {

    private Esme esme;
    private PduRequest request;
    long timeoutMillis;
    private SmppTransactionImpl smppServerTransaction;

    public SmppRequestTask(Esme esme, PduRequest request, long timeoutMillis, SmppTransactionImpl smppServerTransaction) {
        this.esme = esme;
        this.request = request;
        this.timeoutMillis = timeoutMillis;
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

    public SmppTransactionImpl getSmppServerTransaction() {
        return smppServerTransaction;
    }
}
