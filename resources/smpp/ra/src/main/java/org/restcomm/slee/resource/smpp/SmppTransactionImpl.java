package org.restcomm.slee.resource.smpp;

import java.util.concurrent.Semaphore;

import org.restcomm.slee.resource.smpp.SmppTransaction;
import org.restcomm.smpp.Esme;

import com.cloudhopper.smpp.pdu.PduRequest;

public class SmppTransactionImpl implements SmppTransaction {

	private final Esme esme;
	private final SmppServerResourceAdaptor ra;

	private SmppTransactionHandle activityHandle;
	private PduRequest wrappedPduRequest;
	private final long startTime;

	private Semaphore eventSemaphore=new Semaphore(1);
	
	protected SmppTransactionImpl(PduRequest wrappedPduRequest, Esme esme,
			SmppTransactionHandle smppServerTransactionHandle, SmppServerResourceAdaptor ra) {
		this.wrappedPduRequest = wrappedPduRequest;
		this.wrappedPduRequest.setReferenceObject(this);
		this.esme = esme;
		this.activityHandle = smppServerTransactionHandle;
		this.activityHandle.setActivity(this);
		this.ra = ra;
		this.startTime = System.currentTimeMillis();
	}

	public Esme getEsme() {
		return this.esme;
	}

	public SmppTransactionHandle getActivityHandle() {
		return this.activityHandle;
	}

	public PduRequest getWrappedPduRequest() {
		return this.wrappedPduRequest;
	}
	
	public long getStartTime() {
		return startTime;
	}

	protected SmppServerResourceAdaptor getRa() {
		return ra;
	}

	public void clear() {
		// TODO Any more cleaning here?
		if (this.activityHandle != null) {
			this.activityHandle.setActivity(null);
			this.activityHandle = null;
		}

		if (this.wrappedPduRequest != null) {
			this.wrappedPduRequest.setReferenceObject(null);
			this.wrappedPduRequest = null;
		}
	}

	protected void acquireSemaphore() {
		try
        {
        	eventSemaphore.acquire();
        }
        catch(InterruptedException ex)
        {
        	
        }
	}
	
	protected void releaseSemaphore() {
		eventSemaphore.release();
	}
}
