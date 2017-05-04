package org.restcomm.slee.resource.smpp;

public interface EventsType {
	
	public static final String REQUEST_TIMEOUT = "org.restcomm.slee.resource.smpp.REQUEST_TIMEOUT";
	public static final String SEND_PDU_STATUS = "org.restcomm.slee.resource.smpp.SEND_PDU_STATUS";

	public static final String SUBMIT_SM = "org.restcomm.slee.resource.smpp.SUBMIT_SM";
	public static final String DATA_SM = "org.restcomm.slee.resource.smpp.DATA_SM";
	public static final String DELIVER_SM = "org.restcomm.slee.resource.smpp.DELIVER_SM";
    public static final String SUBMIT_MULTI = "org.restcomm.slee.resource.smpp.SUBMIT_MULTI";

	public static final String SUBMIT_SM_RESP = "org.restcomm.slee.resource.smpp.SUBMIT_SM_RESP";
	public static final String DATA_SM_RESP = "org.restcomm.slee.resource.smpp.DATA_SM_RESP";
	public static final String DELIVER_SM_RESP = "org.restcomm.slee.resource.smpp.DELIVER_SM_RESP";
    public static final String SUBMIT_MULTI_RESP = "org.restcomm.slee.resource.smpp.SUBMIT_MULTI_RESP";
		
	public static final String RECOVERABLE_PDU_EXCEPTION = "org.restcomm.slee.resource.smpp.RECOVERABLE_PDU_EXCEPTION";
	public static final String UNRECOVERABLE_PDU_EXCEPTION = "org.restcomm.slee.resource.smpp.UNRECOVERABLE_PDU_EXCEPTION";
}
