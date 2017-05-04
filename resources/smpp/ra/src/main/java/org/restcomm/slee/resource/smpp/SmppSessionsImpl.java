package org.restcomm.slee.resource.smpp;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.LinkedBlockingQueue;

import javax.slee.SLEEException;
import javax.slee.facilities.Tracer;
import javax.slee.resource.ActivityAlreadyExistsException;
import javax.slee.resource.StartActivityException;

import org.restcomm.smpp.Esme;
import org.restcomm.smpp.SmppSessionHandlerInterface;

import com.cloudhopper.commons.util.windowing.Window;
import com.cloudhopper.commons.util.windowing.WindowFuture;
import com.cloudhopper.smpp.PduAsyncResponse;
import com.cloudhopper.smpp.SmppConstants;
import com.cloudhopper.smpp.SmppSessionCounters;
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

	private static Tracer tracer;

	private SmppServerResourceAdaptor smppServerResourceAdaptor = null;

	protected SmppSessionHandlerInterfaceImpl smppSessionHandlerInterfaceImpl = null;

	private ConcurrentHashMap<String, SenderThread> esmeSenderThreads = new ConcurrentHashMap<>();

	public SmppSessionsImpl(SmppServerResourceAdaptor smppServerResourceAdaptor) {
		this.smppServerResourceAdaptor = smppServerResourceAdaptor;
		if (tracer == null) {
			tracer = this.smppServerResourceAdaptor.getRAContext().getTracer(
					SmppSessionHandlerInterfaceImpl.class.getSimpleName());
		}
		this.smppSessionHandlerInterfaceImpl = new SmppSessionHandlerInterfaceImpl();

	}

	protected SmppSessionHandlerInterface getSmppSessionHandlerInterface() {
		return this.smppSessionHandlerInterfaceImpl;
	}

	@Override
	public SmppTransaction sendRequestPdu(Esme esme, PduRequest request, long timeoutMillis) 
			throws RecoverablePduException, UnrecoverablePduException, SmppTimeoutException, SmppChannelException, 
			InterruptedException, ActivityAlreadyExistsException, NullPointerException, IllegalStateException,
			SLEEException, StartActivityException {

		DefaultSmppSession defaultSmppSession = esme.getSmppSession();

		if (defaultSmppSession == null) {
			throw new NullPointerException("Underlying SmppSession is Null!");
		}

		SenderThread sender = esmeSenderThreads.get(esme.getName());
		if (sender == null) {
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

		SmppSendingTask task = new SmppSendingTask(esme, request, timeoutMillis, null, smppServerTransaction);
		sender.offer(task);

		return smppServerTransaction;
	}

	@Override
	public void sendResponsePdu(Esme esme, PduRequest request, PduResponse response) throws UnrecoverablePduException {

		SmppTransactionImpl smppServerTransactionImpl = (SmppTransactionImpl) request.getReferenceObject();

		DefaultSmppSession defaultSmppSession = esme.getSmppSession();

		if (defaultSmppSession == null) {
			throw new NullPointerException("Underlying SmppSession is Null!");
		}

		SenderThread sender = esmeSenderThreads.get(esme.getName());
		if (sender == null) {
			throw new IllegalStateException("Esme sender not found");
		}

		if (request.getSequenceNumber() != response.getSequenceNumber()) {
			throw new UnrecoverablePduException(
					"Sequence number of response is not same as request");
		}

		SmppSendingTask task = new SmppSendingTask(esme, request, 0L, response, smppServerTransactionImpl);
		sender.offer(task);

		// TODO Should it catch UnrecoverablePduException and
		// SmppChannelException and close underlying SmppSession?
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

	protected class SmppSessionHandlerInterfaceImpl implements
			SmppSessionHandlerInterface {

		public SmppSessionHandlerInterfaceImpl() {

		}

		public void destroySmppSessionHandler(Esme esme) {
			if (esme != null && esme.getName() != null) {
				SenderThread senderThread = esmeSenderThreads.remove(esme
						.getName());
				if (senderThread != null)
					senderThread.deactivate();
			}
		}

		@Override
		public SmppSessionHandler createNewSmppSessionHandler(Esme esme) {

			SenderThread senderThread = new SenderThread("SMPP ESME Sender " + esme.getName());
			SenderThread existingThread = esmeSenderThreads.putIfAbsent(esme.getName(), senderThread);
			if (existingThread == null)
				senderThread.start();			
			
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
				if (smppServerTransaction != null) {
					smppServerResourceAdaptor.endActivity(smppServerTransaction);
				}
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

			try {
				smppServerResourceAdaptor.fireEvent(EventsType.REQUEST_TIMEOUT,
						smppServerTransaction.getActivityHandle(), event);
			} catch (Exception e) {
				tracer.severe(String.format("Received firePduRequestExpired. Error while processing PduRequest=%s",
						pduRequest), e);
			} finally {
				if (smppServerTransaction != null) {
                    smppServerResourceAdaptor.endActivity(smppServerTransaction);
                    pduRequest.setReferenceObject(null);
                }
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

			try {
				smppServerResourceAdaptor.fireEvent(EventsType.RECOVERABLE_PDU_EXCEPTION,
						smppServerTransaction.getActivityHandle(), recoverablePduException);
			} catch (Exception e) {
				tracer.severe(String.format(
						"Received fireRecoverablePduException. Error while processing RecoverablePduException=%s",
						recoverablePduException), e);
			} finally {
				if (smppServerTransaction != null) {
					smppServerResourceAdaptor.endActivity(smppServerTransaction);
				}
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

	public class SenderThread extends Thread {

		private Boolean running = true;
		private LinkedBlockingQueue<SmppSendingTask> queue = new LinkedBlockingQueue<>();

		public SenderThread(String name) {
			super(name);
		}

		public void deactivate() {
			running = false;
			this.interrupt();
			while (!queue.isEmpty()) 
			{
				try {
					SmppSendingTask task = queue.take();
					if (task != null) 
					{
						Exception ex = new InterruptedException("SMPP Sending Thread was stopped");
						fireSendPduStatusEvent(EventsType.SEND_PDU_STATUS, task.getSmppServerTransaction(), task.getRequest(), task.getResponse(), ex, false);
					}
				} 
				catch (InterruptedException e) 
				{
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
							try
							{
								defaultSmppSession.sendRequestPdu(task.getRequest(), task.getTimeoutMillis(), false);
								fireSendPduStatusEvent(EventsType.SEND_PDU_STATUS, task.getSmppServerTransaction(), task.getRequest(), task.getResponse(), null, true);
							} 
							catch (RecoverablePduException | UnrecoverablePduException | SmppTimeoutException | SmppChannelException| InterruptedException e)
							{
								fireSendPduStatusEvent(EventsType.SEND_PDU_STATUS, task.getSmppServerTransaction(), task.getRequest(), task.getResponse(), e, false);
							}
						} else {
							try
							{
								defaultSmppSession.sendResponsePdu(task.getResponse());
								fireSendPduStatusEvent(EventsType.SEND_PDU_STATUS, task.getSmppServerTransaction(), task.getRequest(), task.getResponse(), null, true);
							} 
							catch (RecoverablePduException | UnrecoverablePduException | SmppChannelException | InterruptedException e) 
							{
								fireSendPduStatusEvent(EventsType.SEND_PDU_STATUS, task.getSmppServerTransaction(), task.getRequest(), task.getResponse(), e, false);
							} 
							finally 
							{
								SmppSessionCounters smppSessionCounters = task.getEsme().getSmppSession().getCounters();
								SmppTransactionImpl smppTransactionImpl = (SmppTransactionImpl) task.getRequest().getReferenceObject();
								long responseTime = System.currentTimeMillis() - smppTransactionImpl.getStartTime();
								countSendResponsePdu(smppSessionCounters, task.getResponse(), responseTime, responseTime);

								if (task.getSmppServerTransaction() == null) {
									tracer.severe(String
											.format("SmppTransactionImpl Activity is null while trying to send PduResponse=%s",
													task.getResponse()));
								} else
									smppServerResourceAdaptor.endActivity(task
											.getSmppServerTransaction());
							}
						}
					}
				}
				catch (InterruptedException e) 
				{
					if (task != null)
						fireSendPduStatusEvent(EventsType.SEND_PDU_STATUS, task.getSmppServerTransaction(), task.getRequest(), task.getResponse(), e, false);
				}
			}
		}

		public void offer(SmppSendingTask task) {
			queue.offer(task);
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
}
