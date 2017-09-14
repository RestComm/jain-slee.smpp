package org.restcomm.slee.resource.smpp;

import java.util.Iterator;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Timer;
import java.util.TimerTask;
import java.util.concurrent.ConcurrentHashMap;

import javax.slee.SLEEException;
import javax.slee.facilities.Tracer;
import javax.slee.resource.ActivityAlreadyExistsException;
import javax.slee.resource.StartActivityException;

import org.restcomm.slee.resource.smpp.EventsType;
import org.restcomm.slee.resource.smpp.PduRequestTimeout;
import org.restcomm.slee.resource.smpp.SmppSessions;
import org.restcomm.slee.resource.smpp.SmppTransaction;
import org.restcomm.smpp.Esme;
import org.restcomm.smpp.SmppSessionHandlerInterface;

import com.cloudhopper.commons.util.windowing.Window;
import com.cloudhopper.commons.util.windowing.WindowFuture;
import com.cloudhopper.smpp.PduAsyncResponse;
import com.cloudhopper.smpp.SmppConstants;
import com.cloudhopper.smpp.SmppSessionHandler;
import com.cloudhopper.smpp.impl.DefaultSmppSession;
import com.cloudhopper.smpp.pdu.DataSm;
import com.cloudhopper.smpp.pdu.DataSmResp;
import com.cloudhopper.smpp.pdu.DeliverSm;
import com.cloudhopper.smpp.pdu.DeliverSmResp;
import com.cloudhopper.smpp.pdu.Pdu;
import com.cloudhopper.smpp.pdu.PduRequest;
import com.cloudhopper.smpp.pdu.PduResponse;
import com.cloudhopper.smpp.pdu.SubmitMulti;
import com.cloudhopper.smpp.pdu.SubmitMultiResp;
import com.cloudhopper.smpp.pdu.SubmitSm;
import com.cloudhopper.smpp.pdu.SubmitSmResp;
import com.cloudhopper.smpp.type.Address;
import com.cloudhopper.smpp.type.RecoverablePduException;
import com.cloudhopper.smpp.type.SmppChannelException;
import com.cloudhopper.smpp.type.SmppTimeoutException;
import com.cloudhopper.smpp.type.UnrecoverablePduException;

/**
 * 
 * @author Amit Bhayani
 * 
 */
public class SmppSessionsImpl implements SmppSessions {

    // setting static values. In case fields will be added to ESME will change to dynamic
    private static final long SMPP_REQUEST_TIMEOUT = 1000L;
    private static final long SMPP_RESPONSE_TIMEOUT = 1000L;
    
    private static Tracer tracer;

    private SmppServerResourceAdaptor smppServerResourceAdaptor = null;

    protected SmppSessionHandlerInterfaceImpl smppSessionHandlerInterfaceImpl = null;

    private ConcurrentHashMap<String, EsmeSender> esmeSenderThreads = new ConcurrentHashMap<String, EsmeSender>();
    
    public SmppSessionsImpl(SmppServerResourceAdaptor smppServerResourceAdaptor) {
        this.smppServerResourceAdaptor = smppServerResourceAdaptor;
        if (tracer == null) {
            tracer = this.smppServerResourceAdaptor.getRAContext().getTracer(
                    SmppSessionHandlerInterfaceImpl.class.getSimpleName());
        }
        this.smppSessionHandlerInterfaceImpl = new SmppSessionHandlerInterfaceImpl();
        startInactivityTimer();
    }

    private Timer esmeSenderInactiveStateTimer = new Timer();
    private void startInactivityTimer() {
        TimerTask esmeSenderInactiveStateThread = new TimerTask() {
            
            private long idleStateTimeout = 60 * 1000;
            
            @Override
            public void run() {
                try {
                    Iterator<Entry<String, EsmeSender>> iterator = esmeSenderThreads.entrySet().iterator();
                    while(iterator.hasNext()) {
                        
                        Entry<String, EsmeSender> entry = iterator.next();
                        String esmeName = entry.getKey();
                        EsmeSender sender = entry.getValue();
                        
                        if(sender.getRequestSenderPreviousIterationTime() > 0) {
                            long diff = System.currentTimeMillis() - sender.getRequestSenderPreviousIterationTime();
                            if(diff > idleStateTimeout) {
                                if (tracer.isFineEnabled()) {
                                    tracer.fine(esmeName + " RequestSender has been idle for " + diff);
                                }
                            } 
                        }
                        
                        if(sender.getResponseSenderPreviousIterationTime() > 0) {
                            long diff = System.currentTimeMillis() - sender.getResponseSenderPreviousIterationTime();
                            if(diff > idleStateTimeout) {
                                if (tracer.isFineEnabled()) {
                                    tracer.fine(esmeName + " ResponseSender has been idle for " + diff);
                                }
                            }
                        }
                    }
                } catch(Exception e) {
                }
            }
        };
        esmeSenderInactiveStateTimer.scheduleAtFixedRate(esmeSenderInactiveStateThread, 0, 10000L);
    }
    
    protected SmppSessionHandlerInterface getSmppSessionHandlerInterface() {
        return this.smppSessionHandlerInterfaceImpl;
    }

    @Override
    public SmppTransaction sendRequestPdu(Esme esme, PduRequest request, long timeoutMillis) throws RecoverablePduException,
            UnrecoverablePduException, SmppTimeoutException, SmppChannelException, InterruptedException,
            ActivityAlreadyExistsException, NullPointerException, IllegalStateException, SLEEException, StartActivityException {

        DefaultSmppSession defaultSmppSession = esme.getSmppSession();

        if (defaultSmppSession == null) {
            throw new NullPointerException("Underlying SmppSession is Null!");
        }

        EsmeSender esmeSender = esmeSenderThreads.get(esme.getName());
        if (esmeSender == null) {
            throw new IllegalStateException("Esme sender not found");
        }

        if (!request.hasSequenceNumberAssigned()) {
            // assign the next PDU sequence # if its not yet assigned
            request.setSequenceNumber(defaultSmppSession.getSequenceNumber().next());
        }

        SmppTransactionHandle smppServerTransactionHandle = new SmppTransactionHandle(esme.getName(),
                request.getSequenceNumber(), SmppTransactionType.OUTGOING);

        SmppTransactionImpl smppServerTransaction = new SmppTransactionImpl(request, esme, smppServerTransactionHandle,
                smppServerResourceAdaptor);

        smppServerResourceAdaptor.startNewSmppTransactionSuspendedActivity(smppServerTransaction);

        SmppRequestTask task = new SmppRequestTask(esme, request, timeoutMillis, smppServerTransaction);
        esmeSender.offerRequest(task);

        return smppServerTransaction;
    }

    @Override
    public void sendResponsePdu(Esme esme, PduRequest request, PduResponse response) throws UnrecoverablePduException {

        SmppTransactionImpl smppServerTransactionImpl = (SmppTransactionImpl) request.getReferenceObject();

        DefaultSmppSession defaultSmppSession = esme.getSmppSession();

        if (defaultSmppSession == null) {
            throw new NullPointerException("Underlying SmppSession is Null!");
        }

        EsmeSender esmeSender = esmeSenderThreads.get(esme.getName());
        if (esmeSender == null) {
            throw new IllegalStateException("Esme sender not found");
        }

        if (request.getSequenceNumber() != response.getSequenceNumber()) {
            throw new UnrecoverablePduException("Sequence number of response is not same as request");
        }

        SmppResponseTask task = new SmppResponseTask(esme, request, response, smppServerTransactionImpl);
        esmeSender.offerResponse(task);

        // TODO Should it catch UnrecoverablePduException and
        // SmppChannelException and close underlying SmppSession?
    }

    protected class SmppSessionHandlerInterfaceImpl implements SmppSessionHandlerInterface {

        public SmppSessionHandlerInterfaceImpl() {

        }

        @Override
        public void destroySmppSessionHandler(Esme esme) {
            if (esme != null && esme.getName() != null) {
                EsmeSender esmeSender = esmeSenderThreads.remove(esme.getName());
                if (esmeSender != null) {
                    esmeSender.deactivate();
                }
            }
        }

        @Override
        public SmppSessionHandler createNewSmppSessionHandler(Esme esme) {

            RequestSender requestThread = new RequestSender(smppServerResourceAdaptor, tracer, "SMPP ESME Request Sender "
                    + esme.getName(), SMPP_REQUEST_TIMEOUT);
            ResponseSender responseThread = new ResponseSender(smppServerResourceAdaptor, tracer, "SMPP ESME Response Sender "
                    + esme.getName(), SMPP_RESPONSE_TIMEOUT);
            EsmeSender esmeSender = new EsmeSender(requestThread, responseThread);

            EsmeSender existingQueue = esmeSenderThreads.put(esme.getName(), esmeSender);
            if (existingQueue != null) {
                existingQueue.deactivate();
            }

            esmeSender.start();

            return new SmppSessionHandlerImpl(esme);
        }
    }
    
    protected class SmppSessionHandlerImpl implements SmppSessionHandler {
        private Esme esme;

        public SmppSessionHandlerImpl(Esme esme) {
            this.esme = esme;
        }

        @Override
        public PduResponse firePduRequestReceived(PduRequest pduRequest) {
            
            PduResponse response = pduRequest.createResponse();
            try {
                SmppTransactionImpl smppServerTransaction = null;
                SmppTransactionHandle smppServerTransactionHandle = null;
                Address sourceAddress = null;
                switch (pduRequest.getCommandId()) {
                case SmppConstants.CMD_ID_ENQUIRE_LINK:
                    break;
                case SmppConstants.CMD_ID_UNBIND:
                    break;
                case SmppConstants.CMD_ID_SUBMIT_SM:
                    SubmitSm submitSm = (SubmitSm) pduRequest;
                    sourceAddress = submitSm.getSourceAddress();
                    if (!this.esme.isSourceAddressMatching(sourceAddress)) {
                        tracer.warning(String
                                .format("Incoming SUBMIT_SM's sequence_number=%d source_addr_ton=%d source_addr_npi=%d source_addr=%s doesn't match with configured ESME name=%s source_addr_ton=%d source_addr_npi=%d source_addr=%s",
                                        submitSm.getSequenceNumber(), sourceAddress.getTon(), sourceAddress.getNpi(),
                                        sourceAddress.getAddress(), this.esme.getName(), this.esme.getSourceTon(),
                                        this.esme.getSourceNpi(), this.esme.getSourceAddressRange()));

                        response.setCommandStatus(SmppConstants.STATUS_INVSRCADR);
                        return response;
                    }

                    smppServerTransactionHandle = new SmppTransactionHandle(this.esme.getName(),
                            pduRequest.getSequenceNumber(), SmppTransactionType.INCOMING);
                    smppServerTransaction = new SmppTransactionImpl(pduRequest, this.esme, smppServerTransactionHandle,
                            smppServerResourceAdaptor);

                    smppServerResourceAdaptor.startNewSmppServerTransactionActivity(smppServerTransaction);
                    smppServerResourceAdaptor.fireEvent(EventsType.SUBMIT_SM,
                            smppServerTransaction.getActivityHandle(), submitSm);

                    // Return null. Let SBB send response back
                    return null;
                case SmppConstants.CMD_ID_DATA_SM:
                    DataSm dataSm = (DataSm) pduRequest;
                    sourceAddress = dataSm.getSourceAddress();
                    if (!this.esme.isSourceAddressMatching(sourceAddress)) {
                        tracer.warning(String
                                .format("Incoming DATA_SM's sequence_number=%d source_addr_ton=%d source_addr_npi=%d source_addr=%s doesn't match with configured ESME name=%s source_addr_ton=%d source_addr_npi=%d source_addr=%s",
                                        dataSm.getSequenceNumber(), sourceAddress.getTon(), sourceAddress.getNpi(),
                                        sourceAddress.getAddress(), this.esme.getName(), this.esme.getSourceTon(),
                                        this.esme.getSourceNpi(), this.esme.getSourceAddressRange()));

                        response.setCommandStatus(SmppConstants.STATUS_INVSRCADR);
                        return response;
                    }

                    smppServerTransactionHandle = new SmppTransactionHandle(this.esme.getName(),
                            pduRequest.getSequenceNumber(), SmppTransactionType.INCOMING);
                    smppServerTransaction = new SmppTransactionImpl(pduRequest, this.esme, smppServerTransactionHandle,
                            smppServerResourceAdaptor);
                    smppServerResourceAdaptor.startNewSmppServerTransactionActivity(smppServerTransaction);
                    smppServerResourceAdaptor.fireEvent(EventsType.DATA_SM, smppServerTransaction.getActivityHandle(),
                            (DataSm) pduRequest);

                    // Return null. Let SBB send response back
                    return null;
                case SmppConstants.CMD_ID_DELIVER_SM:
                    DeliverSm deliverSm = (DeliverSm) pduRequest;
                    sourceAddress = deliverSm.getSourceAddress();
                    if (!this.esme.isSourceAddressMatching(sourceAddress)) {
                        tracer.warning(String
                                .format("Incoming DELIVER_SM's sequence_number=%d source_addr_ton=%d source_addr_npi=%d source_addr=%s doesn't match with configured ESME name=%s source_addr_ton=%d source_addr_npi=%d source_addr=%s",
                                        deliverSm.getSequenceNumber(), sourceAddress.getTon(), sourceAddress.getNpi(),
                                        sourceAddress.getAddress(), this.esme.getName(), this.esme.getSourceTon(),
                                        this.esme.getSourceNpi(), this.esme.getSourceAddressRange()));

                        response.setCommandStatus(SmppConstants.STATUS_INVSRCADR);
                        return response;
                    }

                    smppServerTransactionHandle = new SmppTransactionHandle(this.esme.getName(),
                            pduRequest.getSequenceNumber(), SmppTransactionType.INCOMING);
                    smppServerTransaction = new SmppTransactionImpl(pduRequest, this.esme, smppServerTransactionHandle,
                            smppServerResourceAdaptor);
                    smppServerResourceAdaptor.startNewSmppServerTransactionActivity(smppServerTransaction);
                    smppServerResourceAdaptor.fireEvent(EventsType.DELIVER_SM,
                            smppServerTransaction.getActivityHandle(), (DeliverSm) pduRequest);
                    return null;

                case SmppConstants.CMD_ID_SUBMIT_MULTI:
                    SubmitMulti submitMulti = (SubmitMulti) pduRequest;
                    sourceAddress = submitMulti.getSourceAddress();
                    if (!this.esme.isSourceAddressMatching(sourceAddress)) {
                        tracer.warning(String
                                .format("Incoming SUBMIT_MULTI's sequence_number=%d source_addr_ton=%d source_addr_npi=%d source_addr=%s doesn't match with configured ESME name=%s source_addr_ton=%d source_addr_npi=%d source_addr=%s",
                                        submitMulti.getSequenceNumber(), sourceAddress.getTon(), sourceAddress.getNpi(),
                                        sourceAddress.getAddress(), this.esme.getName(), this.esme.getSourceTon(),
                                        this.esme.getSourceNpi(), this.esme.getSourceAddressRange()));

                        response.setCommandStatus(SmppConstants.STATUS_INVSRCADR);
                        return response;
                    }

                    smppServerTransactionHandle = new SmppTransactionHandle(this.esme.getName(),
                            pduRequest.getSequenceNumber(), SmppTransactionType.INCOMING);
                    smppServerTransaction = new SmppTransactionImpl(pduRequest, this.esme, smppServerTransactionHandle,
                            smppServerResourceAdaptor);

                    smppServerResourceAdaptor.startNewSmppServerTransactionActivity(smppServerTransaction);
                    smppServerResourceAdaptor.fireEvent(EventsType.SUBMIT_MULTI,
                            smppServerTransaction.getActivityHandle(), submitMulti);

                    // Return null. Let SBB send response back
                    return null;

                default:
                    tracer.severe(String.format("Rx : Non supported PduRequest=%s. Will not fire event", pduRequest));
                    break;
                }
            } catch (Exception e) {
                tracer.severe(String.format("Error while processing PduRequest=%s", pduRequest), e);
                response.setCommandStatus(SmppConstants.STATUS_SYSERR);
            }

            return response;
        }

        @Override
        public String lookupResultMessage(int arg0) {
            return null;
        }

        @Override
        public String lookupTlvTagName(short arg0) {
            return null;
        }

        @Override
        public void fireChannelUnexpectedlyClosed() {
            tracer.severe(String
                    .format("Rx : fireChannelUnexpectedlyClosed for SmppSessionImpl=%s Default handling is to discard an unexpected channel closed",
                            this.esme.getName()));

            DefaultSmppSession defaultSession = esme.getSmppSession();

            // firing of onPduRequestTimeout() for sent messages for which we do not have responses
            Window<Integer, PduRequest, PduResponse> wind = defaultSession.getSendWindow();
            Map<Integer, WindowFuture<Integer, PduRequest, PduResponse>> futures = wind.createSortedSnapshot();
            for (WindowFuture<Integer, PduRequest, PduResponse> future : futures.values()) {
                tracer.warning("Firing of onPduRequestTimeout from DefaultSmppServerHandler.sessionDestroyed(): "
                        + future.getRequest().toString());
                defaultSession.expired(future);
            }
        }

        @Override
        public void fireExpectedPduResponseReceived(PduAsyncResponse pduAsyncResponse) {

            PduRequest pduRequest = pduAsyncResponse.getRequest();
            PduResponse pduResponse = pduAsyncResponse.getResponse();

            SmppTransactionImpl smppServerTransaction = (SmppTransactionImpl) pduRequest.getReferenceObject();

            if (smppServerTransaction == null) {
                tracer.severe(String
                        .format("Rx : fireExpectedPduResponseReceived for SmppSessionImpl=%s PduAsyncResponse=%s but SmppTransactionImpl is null",
                                this.esme.getName(), pduAsyncResponse));
                return;
            }

            smppServerTransaction.acquireSemaphore();
            
            try {
                switch (pduResponse.getCommandId()) {
                case SmppConstants.CMD_ID_DELIVER_SM_RESP:
                    smppServerResourceAdaptor.fireEvent(EventsType.DELIVER_SM_RESP,
                            smppServerTransaction.getActivityHandle(), (DeliverSmResp) pduResponse);
                    break;
                case SmppConstants.CMD_ID_DATA_SM_RESP:
                    smppServerResourceAdaptor.fireEvent(EventsType.DATA_SM_RESP,
                            smppServerTransaction.getActivityHandle(), (DataSmResp) pduResponse);
                    break;
                case SmppConstants.CMD_ID_SUBMIT_SM_RESP:
                    smppServerResourceAdaptor.fireEvent(EventsType.SUBMIT_SM_RESP,
                            smppServerTransaction.getActivityHandle(), (SubmitSmResp) pduResponse);
                    break;
                case SmppConstants.CMD_ID_SUBMIT_MULTI_RESP:
                    smppServerResourceAdaptor.fireEvent(EventsType.SUBMIT_MULTI_RESP,
                            smppServerTransaction.getActivityHandle(), (SubmitMultiResp) pduResponse);
                    break;
                default:
                    tracer.severe(String
                            .format("Rx : fireExpectedPduResponseReceived for SmppSessionImpl=%s PduAsyncResponse=%s but PduResponse is unidentified. Event will not be fired ",
                                    this.esme.getName(), pduAsyncResponse));
                    break;
                }

            } catch (Exception e) {
                tracer.severe(String.format("Error while processing PduAsyncResponse=%s", pduAsyncResponse), e);
            } finally {
                smppServerResourceAdaptor.endActivity(smppServerTransaction);  
                smppServerTransaction.releaseSemaphore();                
            }
        }

        @Override
        public void firePduRequestExpired(PduRequest pduRequest) {
            tracer.warning(String.format("PduRequestExpired=%s", pduRequest));

            SmppTransactionImpl smppServerTransaction = (SmppTransactionImpl) pduRequest.getReferenceObject();

            if (smppServerTransaction == null) {
                tracer.severe(String
                        .format("Rx : firePduRequestExpired for SmppSessionImpl=%s PduRequest=%s but SmppTransactionImpl is null",
                                this.esme.getName(), pduRequest));
                return;
            }

            PduRequestTimeout event = new PduRequestTimeout(pduRequest, this.esme.getName());
            smppServerTransaction.acquireSemaphore();
            
            try {
                smppServerResourceAdaptor.fireEvent(EventsType.REQUEST_TIMEOUT,
                        smppServerTransaction.getActivityHandle(), event);
            } catch (Exception e) {
                tracer.severe(String.format("Received firePduRequestExpired. Error while processing PduRequest=%s",
                        pduRequest), e);
            } finally {
                smppServerResourceAdaptor.endActivity(smppServerTransaction);
                smppServerTransaction.releaseSemaphore();
                pduRequest.setReferenceObject(null);                
            }
        }

        @Override
        public void fireRecoverablePduException(RecoverablePduException recoverablePduException) {
            tracer.warning("Received fireRecoverablePduException", recoverablePduException);

            Pdu partialPdu = recoverablePduException.getPartialPdu();

            SmppTransactionImpl smppServerTransaction = (SmppTransactionImpl) partialPdu.getReferenceObject();
            if (smppServerTransaction == null) {
                tracer.severe(String.format(
                        "Rx : fireRecoverablePduException for SmppSessionImpl=%s but SmppTransactionImpl is null",
                        this.esme.getName()), recoverablePduException);
                return;
            }

            smppServerTransaction.acquireSemaphore();
            
            try {
                smppServerResourceAdaptor.fireEvent(EventsType.RECOVERABLE_PDU_EXCEPTION,
                        smppServerTransaction.getActivityHandle(), recoverablePduException);
            } catch (Exception e) {
                tracer.severe(String.format(
                        "Received fireRecoverablePduException. Error while processing RecoverablePduException=%s",
                        recoverablePduException), e);
            } finally {
               smppServerResourceAdaptor.endActivity(smppServerTransaction);
               smppServerTransaction.releaseSemaphore();               
            }

        }

        @Override
        public void fireUnrecoverablePduException(UnrecoverablePduException unrecoverablePduException) {
            tracer.severe("Received fireUnrecoverablePduException", unrecoverablePduException);

            // TODO : recommendation is to close session
        }

        @Override
        public void fireUnexpectedPduResponseReceived(PduResponse pduResponse) {
            tracer.severe("Received fireUnexpectedPduResponseReceived PduResponse=" + pduResponse);
        }

        @Override
        public void fireUnknownThrowable(Throwable throwable) {
            DefaultSmppSession defaultSession = esme.getSmppSession();

            // firing of onPduRequestTimeout() for sent messages for which we do not have responses
            if (defaultSession != null) {
                Window<Integer, PduRequest, PduResponse> wind = defaultSession.getSendWindow();
                Map<Integer, WindowFuture<Integer, PduRequest, PduResponse>> futures = wind.createSortedSnapshot();
                for (WindowFuture<Integer, PduRequest, PduResponse> future : futures.values()) {
                    tracer.warning("Firing of onPduRequestTimeout from DefaultSmppServerHandler.sessionDestroyed(): "
                            + future.getRequest().toString());
                    defaultSession.expired(future);
                }
                tracer.severe("Received fireUnknownThrowable: ", throwable);
            } else {
                tracer.severe("Received fireUnknownThrowable with undifined defaultSession: ", throwable);
            }

            // TODO what here?
        }

    }
}
