package org.restcomm.slee.resource.smpp;

import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

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

    private AtomicLong lastOfferTimestamp = new AtomicLong();

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
                        lastOfferTimestamp.set(System.currentTimeMillis());
                        defaultSmppSession.sendRequestPdu(task.getRequest(), task.getTimeoutMillis(), false);
                        fireSendPduStatusEvent(EventsType.SEND_PDU_STATUS, task.getSmppServerTransaction(), task.getRequest(),
                                null, null, true);
                    } catch (RecoverablePduException e) {
                        fireSendPduStatusEvent(EventsType.SEND_PDU_STATUS, task.getSmppServerTransaction(), task.getRequest(),
                                null, e, false);
                        smppServerResourceAdaptor.endActivity(task.getSmppServerTransaction());
                    } catch (UnrecoverablePduException e) {
                        fireSendPduStatusEvent(EventsType.SEND_PDU_STATUS, task.getSmppServerTransaction(), task.getRequest(),
                                null, e, false);
                        smppServerResourceAdaptor.endActivity(task.getSmppServerTransaction());
                    } catch (SmppTimeoutException e) {
                        fireSendPduStatusEvent(EventsType.SEND_PDU_STATUS, task.getSmppServerTransaction(), task.getRequest(),
                                null, e, false);
                        smppServerResourceAdaptor.endActivity(task.getSmppServerTransaction());
                    } catch (SmppChannelException e) {
                        fireSendPduStatusEvent(EventsType.SEND_PDU_STATUS, task.getSmppServerTransaction(), task.getRequest(),
                                null, e, false);
                        smppServerResourceAdaptor.endActivity(task.getSmppServerTransaction());
                    } catch (InterruptedException e) {
                        fireSendPduStatusEvent(EventsType.SEND_PDU_STATUS, task.getSmppServerTransaction(), task.getRequest(),
                                null, e, false);
                    }
                }
            } catch(InterruptedException ex) { 
            	//that should be legal if queue empty or we are stopping
            } catch (Exception e) {
                tracer.severe("Exception when sending of sendRequestPdu: " + e.getMessage(), e);
                if (task != null) {
                    fireSendPduStatusEvent(EventsType.SEND_PDU_STATUS, task.getSmppServerTransaction(), task.getRequest(),
                            null, e, false);
                }
            } finally {
            	if(task!=null) {
            		task.getSmppServerTransaction().releaseSemaphore();
            	}
            }
        }
    }

    public void offer(SmppRequestTask task) {
        if (tracer.isFineEnabled()) {
            logQueueSizeIfNecessary();
            logPreviousTaskLongRunIfNecessary();
        }
        queue.offer(task);
    }

    private static final int[] SIZE_STEP_ARRAY  = {10,100,200,500,1000};
    private void logQueueSizeIfNecessary() {
        int queueSize = queue.size();
        for (int i = 1; i < SIZE_STEP_ARRAY.length; i++) {
            int step = SIZE_STEP_ARRAY[i];
            if(queueSize == step) {
                tracer.fine("Request queue size reached " + step);
                break;
            }
        }
    }
    
    private static final int[] SIZE_STEP_ARRAY_2  = {1,10,100,200,500,1000};
    private void logPreviousTaskLongRunIfNecessary() {
        int queueSize = queue.size();
        if(queueSize > 0) {
            long diff = System.currentTimeMillis() - lastOfferTimestamp.get();
            if(diff > 1000) {
                for (int i = 0; i < SIZE_STEP_ARRAY_2.length; i++) {
                    int step = SIZE_STEP_ARRAY_2[i];
                    if(queueSize == step) {
                        tracer.fine("Previous request task takes too long to execute, diff:" + diff + "ms");
                        break;
                    }
                } 
            }
        }
    }
    
    private void fireSendPduStatusEvent(String systemId, SmppTransactionImpl smppServerTransaction, PduRequest request,
            PduResponse response, Throwable exception, boolean status) {

        SendPduStatus event = new SendPduStatus(exception, request, response, systemId, status);

        try {
            smppServerResourceAdaptor.fireEvent(systemId, smppServerTransaction.getActivityHandle(), event);
        } catch (Exception e) {
            tracer.severe(String.format(
                    "Received fireRecoverablePduException. Error while processing RecoverablePduException=%s", event), e);
        }
    }
    
    public long getPreviousIterationTime() {
        return this.lastOfferTimestamp.get();
    }
}
