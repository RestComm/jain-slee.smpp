package org.restcomm.slee.resource.smpp;

import org.restcomm.smpp.Esme;

import com.cloudhopper.smpp.pdu.PduRequest;
import com.cloudhopper.smpp.pdu.PduResponse;

public class SmppResponseTask {

    private Esme esme;
    private PduRequest request;
    private PduResponse response;
    private SmppTransactionImpl smppServerTransaction;

    public SmppResponseTask(Esme esme, PduRequest request, PduResponse response, SmppTransactionImpl smppServerTransaction) {
        this.esme = esme;
        this.request = request;
        this.response = response;
        this.smppServerTransaction = smppServerTransaction;
    }

    public Esme getEsme() {
        return esme;
    }

    public PduRequest getRequest() {
        return request;
    }

    public PduResponse getResponse() {
        return response;
    }

    public SmppTransactionImpl getSmppServerTransaction() {
        return smppServerTransaction;
    }
}
