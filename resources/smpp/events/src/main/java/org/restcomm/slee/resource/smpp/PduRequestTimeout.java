package org.restcomm.slee.resource.smpp;

import com.cloudhopper.smpp.pdu.PduRequest;

public class PduRequestTimeout {

	private final PduRequest pduRequest;
	private final String systemId;

	public PduRequestTimeout(PduRequest pduRequest, String systemId) {
		this.pduRequest = pduRequest;
		this.systemId = systemId;
	}

	public PduRequest getPduRequest() {
		return pduRequest;
	}

	public String getSystemId() {
		return systemId;
	}

	@Override
	public String toString() {
		return "PduRequestTimeout [pduRequest=" + pduRequest + ", systemId=" + systemId + "]";
	}

}
