package org.restcomm.slee.resource.smpp;

import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

import javax.slee.facilities.Tracer;

import com.cloudhopper.smpp.impl.DefaultSmppSession;
import com.cloudhopper.smpp.pdu.PduRequest;
import com.cloudhopper.smpp.pdu.PduResponse;
import com.cloudhopper.smpp.type.RecoverablePduException;
import com.cloudhopper.smpp.type.SmppChannelException;
import com.cloudhopper.smpp.type.SmppTimeoutException;
import com.cloudhopper.smpp.type.UnrecoverablePduException;

public class RequestSender extends Thread {
    private volatile Boolean running = true;
    private LinkedBlockingQueue<SmppRequestTask> queue = new LinkedBlockingQueue<SmppRequestTask>();
    private long timeout;

    private SmppServerResourceAdaptor smppServerResourceAdaptor;
    private Tracer tracer;

    public RequestSender(SmppServerResourceAdaptor smppServerResourceAdaptor, Tracer tracer, String name, long timeout) {
        super(name);
        this.timeout = timeout;
        this.smppServerResourceAdaptor = smppServerResourceAdaptor;
        this.tracer = tracer;
    }

    public void deactivate() {
        running = false;
        this.interrupt();
        while (!queue.isEmpty()) {
            SmppRequestTask task = queue.poll();
            if (task != null) {
                Exception ex = new InterruptedException(getName() + " was stopped");
                fireSendPduStatusEvent(EventsType.SEND_PDU_STATUS, task.getSmppServerTransaction(), task.getRequest(), null,
                        ex, false);
            }
        }
    }

    public void run() {
        while (running) {
            SmppRequestTask task = null;
            try {
                task = queue.poll(timeout, TimeUnit.MILLISECONDS);
                if (task != null) {
                    DefaultSmppSession defaultSmppSession = task.getEsme().getSmppSession();
                    task.getSmppServerTransaction().acquireSemaphore();
                    
                    try {
                        defaultSmppSession.sendRequestPdu(task.getRequest(), task.getTimeoutMillis(), false);
                        fireSendPduStatusEvent(EventsType.SEND_PDU_STATUS, task.getSmppServerTransaction(), task.getRequest(),
                                null, null, true);
                    } catch (RecoverablePduException e) {
                        fireSendPduStatusEvent(EventsType.SEND_PDU_STATUS, task.getSmppServerTransaction(), task.getRequest(),
                                null, e, false);
                        if(task.getSmppServerTransaction() !=null)
                        	task.getSmppServerTransaction().markActivityEndedByRequestSender();
                        smppServerResourceAdaptor.endActivity(task.getSmppServerTransaction());
                    } catch (UnrecoverablePduException e) {
                        fireSendPduStatusEvent(EventsType.SEND_PDU_STATUS, task.getSmppServerTransaction(), task.getRequest(),
                                null, e, false);
                        if(task.getSmppServerTransaction() !=null)
                        	task.getSmppServerTransaction().markActivityEndedByRequestSender();
                        smppServerResourceAdaptor.endActivity(task.getSmppServerTransaction());
                    } catch (SmppTimeoutException e) {
                        fireSendPduStatusEvent(EventsType.SEND_PDU_STATUS, task.getSmppServerTransaction(), task.getRequest(),
                                null, e, false);
                        if(task.getSmppServerTransaction() !=null)
                        	task.getSmppServerTransaction().markActivityEndedByRequestSender();
                        smppServerResourceAdaptor.endActivity(task.getSmppServerTransaction());
                    } catch (SmppChannelException e) {
                        fireSendPduStatusEvent(EventsType.SEND_PDU_STATUS, task.getSmppServerTransaction(), task.getRequest(),
                                null, e, false);
                        if(task.getSmppServerTransaction() !=null)
                        	task.getSmppServerTransaction().markActivityEndedByRequestSender();
                        smppServerResourceAdaptor.endActivity(task.getSmppServerTransaction());
                    } catch (InterruptedException e) {
                        fireSendPduStatusEvent(EventsType.SEND_PDU_STATUS, task.getSmppServerTransaction(), task.getRequest(),
                                null, e, false);
                    }
                }
            } catch (Exception e) {
                tracer.severe("Exception when sending of sendRequestPdu: " + e.getMessage(), e);
                if (task != null) {
                    fireSendPduStatusEvent(EventsType.SEND_PDU_STATUS, task.getSmppServerTransaction(), task.getRequest(),
                            null, e, false);
                }
            }
            finally
            {
            	if(task!=null)
            		task.getSmppServerTransaction().releaseSemaphore();
            }
        }
    }

    public void offer(SmppRequestTask task) {
        queue.offer(task);
    }

    private void fireSendPduStatusEvent(String systemId, SmppTransactionImpl smppServerTransaction, PduRequest request,
            PduResponse response, Throwable exception, boolean status) {

        SendPduStatus event = new SendPduStatus(exception, request, response, systemId, status);

        try {
        	if(smppServerTransaction.wasExpectedPduResponseReceived())
        		tracer.severe("invoking fireSendPduStatusEvent after expectedPduResponseReceived for esme " + getName() + ", sequenceNumber " + request.getSequenceNumber());
        	if(smppServerTransaction.wasPduRequestExpired())
        		tracer.severe("invoking fireSendPduStatusEvent after pduRequestExpired for esme " + getName() + ", sequenceNumber " + request.getSequenceNumber());
        	if(smppServerTransaction.wasRecoverablePduException())
        		tracer.severe("invoking fireSendPduStatusEvent after recoverablePduException for esme " + getName() + ", sequenceNumber " + request.getSequenceNumber());
        	if(smppServerTransaction.wasActivityEndedByResponseSender())
        		tracer.severe("invoking fireSendPduStatusEvent after activityEndedByResponseSender for esme " + getName() + ", sequenceNumber " + request.getSequenceNumber());
        	if(smppServerTransaction.wasActivityEndedByRequestSender())
        		tracer.severe("invoking fireSendPduStatusEvent after activityEndedByRequestSender for esme " + getName() + ", sequenceNumber " + request.getSequenceNumber());
        	
        	smppServerResourceAdaptor.fireEvent(systemId, smppServerTransaction.getActivityHandle(), event);
        } catch (Exception e) {
            tracer.severe(String.format(
                    "Received fireRecoverablePduException. Error while processing RecoverablePduException=%s", event), e);
        }
    }
}
