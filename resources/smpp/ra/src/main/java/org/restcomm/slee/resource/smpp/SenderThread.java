package org.restcomm.slee.resource.smpp;

import com.cloudhopper.smpp.SmppConstants;
import com.cloudhopper.smpp.SmppSessionCounters;
import com.cloudhopper.smpp.impl.DefaultSmppSession;
import com.cloudhopper.smpp.pdu.PduRequest;
import com.cloudhopper.smpp.pdu.PduResponse;
import java.util.concurrent.LinkedBlockingQueue;
import javax.slee.facilities.Tracer;

class SenderThread extends Thread {

    private volatile Boolean running = true;
    private final LinkedBlockingQueue<SmppSendingTask> queue = new LinkedBlockingQueue();
    private static Tracer tracer;
    private SmppServerResourceAdaptor smppServerResourceAdaptor = null;

    public SenderThread(String name, Tracer tracer, SmppServerResourceAdaptor smppServerResourceAdaptor) {
        super(name);
        this.tracer=tracer;
        this.smppServerResourceAdaptor = smppServerResourceAdaptor;
    }
    
    

    public void deactivate() {
        running = false;
        this.interrupt();
        while (!queue.isEmpty()) {
            SmppSendingTask task = queue.poll();
            if (task != null) {
                Exception ex = new InterruptedException(getName() + " was stopped");
                fireSendPduStatusEvent(EventsType.SEND_PDU_STATUS, task.getSmppServerTransaction(), task.getRequest(), task.getResponse(), ex, false);
            }
        }
    }

    public void run() {
        while (running) {
            SmppSendingTask task = null;
            try {
                task = queue.take();
                if (task != null) {
                    DefaultSmppSession defaultSmppSession = task.getEsme()
                            .getSmppSession();
                    if (task.getResponse() == null) {
                        try {
                            defaultSmppSession.sendRequestPdu(task.getRequest(), task.getTimeoutMillis(), false);
                            fireSendPduStatusEvent(EventsType.SEND_PDU_STATUS, task.getSmppServerTransaction(), task.getRequest(), task.getResponse(), null, true);
                        } catch (Exception e) {
                            fireSendPduStatusEvent(EventsType.SEND_PDU_STATUS, task.getSmppServerTransaction(), task.getRequest(), task.getResponse(), e, false);
                        }
                    } else {
                        try {
                            defaultSmppSession.sendResponsePdu(task.getResponse());
                            fireSendPduStatusEvent(EventsType.SEND_PDU_STATUS, task.getSmppServerTransaction(), task.getRequest(), task.getResponse(), null, true);
                        } catch (Exception e) {
                            fireSendPduStatusEvent(EventsType.SEND_PDU_STATUS, task.getSmppServerTransaction(), task.getRequest(), task.getResponse(), e, false);
                        } finally {
                            SmppSessionCounters smppSessionCounters = task.getEsme().getSmppSession().getCounters();
                            SmppTransactionImpl smppTransactionImpl = (SmppTransactionImpl) task.getRequest().getReferenceObject();
                            long responseTime = System.currentTimeMillis() - smppTransactionImpl.getStartTime();
                            countSendResponsePdu(smppSessionCounters, task.getResponse(), responseTime, responseTime);

                            if (task.getSmppServerTransaction() == null) {
                                tracer.severe(String
                                        .format("SmppTransactionImpl Activity is null while trying to send PduResponse=%s",
                                                task.getResponse()));
                            } else {
                                smppServerResourceAdaptor.endActivity(task
                                        .getSmppServerTransaction());
                            }
                        }
                    }
                }
            } catch (Exception e) {
                if (task != null) {
                    fireSendPduStatusEvent(EventsType.SEND_PDU_STATUS, task.getSmppServerTransaction(), task.getRequest(), task.getResponse(), e, false);
                }
            }
        }
    }

    private void countSendResponsePdu(SmppSessionCounters counters, PduResponse pdu, long responseTime, long estimatedProcessingTime) {
        if (pdu.isResponse()) {
            switch (pdu.getCommandId()) {
                case SmppConstants.CMD_ID_SUBMIT_SM_RESP:
                    counters.getRxSubmitSM().incrementResponseAndGet();
                    counters.getRxSubmitSM().addRequestResponseTimeAndGet(responseTime);
                    counters.getRxSubmitSM().addRequestEstimatedProcessingTimeAndGet(estimatedProcessingTime);
                    counters.getRxSubmitSM().getResponseCommandStatusCounter().incrementAndGet(pdu.getCommandStatus());
                    break;
                case SmppConstants.CMD_ID_DELIVER_SM_RESP:
                    counters.getRxDeliverSM().incrementResponseAndGet();
                    counters.getRxDeliverSM().addRequestResponseTimeAndGet(responseTime);
                    counters.getRxDeliverSM().addRequestEstimatedProcessingTimeAndGet(estimatedProcessingTime);
                    counters.getRxDeliverSM().getResponseCommandStatusCounter().incrementAndGet(pdu.getCommandStatus());
                    break;
                case SmppConstants.CMD_ID_DATA_SM_RESP:
                    counters.getRxDataSM().incrementResponseAndGet();
                    counters.getRxDataSM().addRequestResponseTimeAndGet(responseTime);
                    counters.getRxDataSM().addRequestEstimatedProcessingTimeAndGet(estimatedProcessingTime);
                    counters.getRxDataSM().getResponseCommandStatusCounter().incrementAndGet(pdu.getCommandStatus());
                    break;
                case SmppConstants.CMD_ID_ENQUIRE_LINK_RESP:
                    counters.getRxEnquireLink().incrementResponseAndGet();
                    counters.getRxEnquireLink().addRequestResponseTimeAndGet(responseTime);
                    counters.getRxEnquireLink().addRequestEstimatedProcessingTimeAndGet(estimatedProcessingTime);
                    counters.getRxEnquireLink().getResponseCommandStatusCounter().incrementAndGet(pdu.getCommandStatus());
                    break;

                // TODO: adding here statistics for SUBMIT_MULTI ?
            }
        }
    }

    private void fireSendPduStatusEvent(String systemId,
            SmppTransactionImpl smppServerTransaction, PduRequest request,
            PduResponse response, Throwable exception, boolean status) {

        SendPduStatus event = new SendPduStatus(exception, request, response,
                systemId, status);

        try {
            smppServerResourceAdaptor.fireEvent(systemId,
                    smppServerTransaction.getActivityHandle(), event);
        } catch (Exception e) {
            tracer.severe(
                    String.format(
                            "Received fireRecoverablePduException. Error while processing RecoverablePduException=%s",
                            event), e);
        } finally {
            if (smppServerTransaction != null) {
                smppServerResourceAdaptor.endActivity(smppServerTransaction);
            }
        }

    }

    public void offer(SmppSendingTask task) {
        queue.offer(task);
    }
}
