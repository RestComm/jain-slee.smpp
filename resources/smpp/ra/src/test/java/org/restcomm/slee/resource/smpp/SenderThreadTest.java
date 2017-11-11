package org.restcomm.slee.resource.smpp;

import static org.mockito.Matchers.any;
import static org.mockito.Matchers.anyObject;
import static org.mockito.Matchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import javax.slee.facilities.Tracer;

import junit.framework.Assert;

import org.junit.After;
import org.junit.Test;
import org.mockito.Mockito;
import org.mockito.invocation.InvocationOnMock;
import org.mockito.stubbing.Answer;
import org.restcomm.smpp.Esme;

import EDU.oswego.cs.dl.util.concurrent.Semaphore;

import com.cloudhopper.smpp.impl.DefaultSmppSession;
import com.cloudhopper.smpp.impl.DefaultSmppSessionCounters;
import com.cloudhopper.smpp.pdu.PduRequest;
import com.cloudhopper.smpp.pdu.PduResponse;
import com.cloudhopper.smpp.pdu.SubmitSm;
import com.cloudhopper.smpp.pdu.SubmitSmResp;

public class SenderThreadTest {

    private static final long ASSSERTION_TIMEOUT = 1000;
    private static final int NUM_THREADS = 8;
    private ExecutorService pool;

    public SenderThreadTest() {
    }

    class RequestSendingTask implements Runnable {

        SmppRequestTask task;
        RequestSender sThread;

        public RequestSendingTask(SmppRequestTask task, RequestSender sThread) {
            this.task = task;
            this.sThread = sThread;
        }

        public void run() {
            try {
                sThread.offer(task);
            } catch (Exception e) {
            }
        }

    }

    class ResponseSendingTask implements Runnable {

        SmppResponseTask task;
        ResponseSender sThread;

        public ResponseSendingTask(SmppResponseTask task, ResponseSender sThread) {
            this.task = task;
            this.sThread = sThread;
        }

        public void run() {
            try {
                sThread.offer(task);
            } catch (Exception e) {
            }
        }

    }

    @After
    public void cleanPool() {
        if (pool != null) {
            pool.shutdownNow();
        }
    }

    @Test
    public void testConcurrentReq() throws Exception {
        final long reqTimeout = 1000;

        pool = Executors.newFixedThreadPool(NUM_THREADS);

        // Mock preparation
        DefaultSmppSessionCounters counters = new DefaultSmppSessionCounters();
        SmppTransactionHandle handle = mock(SmppTransactionHandle.class);
        final SmppTransactionImpl transaction = mock(SmppTransactionImpl.class);
        when(transaction.getActivityHandle()).thenReturn(handle);
        when(transaction.getStartTime()).thenReturn(System.currentTimeMillis());

        DefaultSmppSession smppSession = mock(DefaultSmppSession.class);
        final Esme esme = mock(Esme.class);
        when(esme.getSmppSession()).thenReturn(smppSession);
        // return null, the senderthread is not using the returned window anyway
        when(smppSession.sendRequestPdu(any(PduRequest.class), eq(reqTimeout), eq(false))).thenReturn(null);
        when(smppSession.getCounters()).thenReturn(counters);
        Tracer tracer = mock(Tracer.class);
        SmppServerResourceAdaptor adaptor = mock(SmppServerResourceAdaptor.class);
        Mockito.doNothing().when(adaptor).fireEvent(eq(EventsType.SEND_PDU_STATUS), eq(handle), anyObject());

        // create tested object
        RequestSender sThread = new RequestSender(adaptor, tracer, esme, "testConcurrentReq", reqTimeout);
        sThread.start();

        // exercise concurrently
        PduRequest req = null;
        for (int i = 0; i < NUM_THREADS; i++) {
            SmppRequestTask task = null;
            req = new SubmitSm();
            req.setReferenceObject(transaction);
            task = new SmppRequestTask(esme, req, reqTimeout, transaction);
            pool.submit(new RequestSendingTask(task, sThread));
        }

        // let things happen in the background
        Thread.sleep(ASSSERTION_TIMEOUT);

        // assert as much as possible, let mockito assert the expected behavior
        verify(smppSession, times(NUM_THREADS)).sendRequestPdu(any(PduRequest.class), eq(reqTimeout), eq(false));
    }

    @Test
    public void testConcurrentRes() throws Exception {
        final long reqTimeout = 1000;

        pool = Executors.newFixedThreadPool(NUM_THREADS);

        // Mock preparation
        DefaultSmppSessionCounters counters = new DefaultSmppSessionCounters();
        SmppTransactionHandle handle = mock(SmppTransactionHandle.class);
        final SmppTransactionImpl transaction = mock(SmppTransactionImpl.class);
        when(transaction.getActivityHandle()).thenReturn(handle);
        when(transaction.getStartTime()).thenReturn(System.currentTimeMillis());

        DefaultSmppSession smppSession = mock(DefaultSmppSession.class);
        final Esme esme = mock(Esme.class);
        when(esme.getSmppSession()).thenReturn(smppSession);
        Mockito.doNothing().when(smppSession).sendResponsePdu(any(PduResponse.class));
        when(smppSession.getCounters()).thenReturn(counters);
        Tracer tracer = mock(Tracer.class);
        SmppServerResourceAdaptor adaptor = mock(SmppServerResourceAdaptor.class);
        Mockito.doNothing().when(adaptor).fireEvent(eq(EventsType.SEND_PDU_STATUS), eq(handle), anyObject());

        // create tested object
        final ResponseSender sThread = new ResponseSender(adaptor, tracer, "testConcurrentRes", reqTimeout);
        sThread.start();

        // exercise concurrently
        PduRequest req = null;
        for (int i = 0; i < NUM_THREADS; i++) {
            SmppResponseTask task = null;
            req = new SubmitSm();
            req.setReferenceObject(transaction);
            task = new SmppResponseTask(esme, req, new SubmitSmResp(), transaction);
            pool.submit(new ResponseSendingTask(task, sThread));
        }

        // let things happen in the background
        Thread.sleep(ASSSERTION_TIMEOUT);

        // assert as much as possible, let mockito assert the expected behavior
        // TODO
        Assert.assertEquals(NUM_THREADS, counters.getRxSubmitSM().getResponse());
        verify(smppSession, times(NUM_THREADS)).sendResponsePdu(any(PduResponse.class));
    }

    @Test
    public void deactivateWhileSendingReq() throws Exception {
        final long reqTimeout = 1000;

        pool = Executors.newFixedThreadPool(NUM_THREADS);

        // Mock preparation
        DefaultSmppSessionCounters counters = new DefaultSmppSessionCounters();
        SmppTransactionHandle handle = mock(SmppTransactionHandle.class);
        final SmppTransactionImpl transaction = mock(SmppTransactionImpl.class);
        when(transaction.getActivityHandle()).thenReturn(handle);
        when(transaction.getStartTime()).thenReturn(System.currentTimeMillis());

        DefaultSmppSession smppSession = mock(DefaultSmppSession.class);
        final Esme esme = mock(Esme.class);
        when(esme.getSmppSession()).thenReturn(smppSession);
        // return null, the senderthread is not using the returned window anyway
        when(smppSession.sendRequestPdu(any(PduRequest.class), eq(reqTimeout), eq(false))).thenAnswer(new Answer() {
            public Object answer(InvocationOnMock invocation) throws InterruptedException {
                Semaphore semaphore = new Semaphore(0);
                semaphore.acquire();
                return null;
            }
        });
        when(smppSession.getCounters()).thenReturn(counters);
        Tracer tracer = mock(Tracer.class);
        SmppServerResourceAdaptor adaptor = mock(SmppServerResourceAdaptor.class);
        Mockito.doNothing().when(adaptor).fireEvent(eq(EventsType.SEND_PDU_STATUS), eq(handle), anyObject());

        // create tested object
        final RequestSender sThread = new RequestSender(adaptor, tracer, esme, "deactivateWhileSendingReq", reqTimeout);
        sThread.start();

        // exercise concurrently
        PduRequest req = null;
        for (int i = 0; i < NUM_THREADS; i++) {
            SmppRequestTask task = null;
            req = new SubmitSm();
            req.setReferenceObject(transaction);
            task = new SmppRequestTask(esme, req, reqTimeout, transaction);
            pool.submit(new RequestSendingTask(task, sThread));
        }

        sThread.deactivate();
        // let things happen in the background
        Thread.sleep(ASSSERTION_TIMEOUT);
        Assert.assertFalse(sThread.isAlive());
    }

    @Test
    public void deactivateWhileSendingRes() throws Exception {
        final long reqTimeout = 1000;

        pool = Executors.newFixedThreadPool(NUM_THREADS);

        // Mock preparation
        DefaultSmppSessionCounters counters = new DefaultSmppSessionCounters();
        SmppTransactionHandle handle = mock(SmppTransactionHandle.class);
        final SmppTransactionImpl transaction = mock(SmppTransactionImpl.class);
        when(transaction.getActivityHandle()).thenReturn(handle);
        when(transaction.getStartTime()).thenReturn(System.currentTimeMillis());

        DefaultSmppSession smppSession = mock(DefaultSmppSession.class);
        final Esme esme = mock(Esme.class);
        when(esme.getSmppSession()).thenReturn(smppSession);
        // return null, the senderthread is not using the returned window anyway
        Mockito.doAnswer(new Answer() {
            public Object answer(InvocationOnMock invocation) throws InterruptedException {
                Semaphore semaphore = new Semaphore(0);
                semaphore.acquire();
                return null;
            }
        }).when(smppSession).sendResponsePdu(any(PduResponse.class));
        when(smppSession.getCounters()).thenReturn(counters);
        Tracer tracer = mock(Tracer.class);
        SmppServerResourceAdaptor adaptor = mock(SmppServerResourceAdaptor.class);
        Mockito.doNothing().when(adaptor).fireEvent(eq(EventsType.SEND_PDU_STATUS), eq(handle), anyObject());

        // create tested object
        final ResponseSender sThread = new ResponseSender(adaptor, tracer, "deactivateWhileSendingRes", reqTimeout);
        sThread.start();

        // exercise concurrently
        PduRequest req = null;
        for (int i = 0; i < NUM_THREADS; i++) {
            SmppResponseTask task = null;
            req = new SubmitSm();
            req.setReferenceObject(transaction);
            task = new SmppResponseTask(esme, req, new SubmitSmResp(), transaction);
            pool.submit(new ResponseSendingTask(task, sThread));
        }

        sThread.deactivate();
        // let things happen in the background
        Thread.sleep(ASSSERTION_TIMEOUT);
        Assert.assertFalse(sThread.isAlive());
    }

    @Test
    public void deactivateWhileInactivityReq() throws Exception {
        final long reqTimeout = 500;

        pool = Executors.newFixedThreadPool(NUM_THREADS);

        // Mock preparation
        DefaultSmppSessionCounters counters = new DefaultSmppSessionCounters();
        SmppTransactionHandle handle = mock(SmppTransactionHandle.class);
        final SmppTransactionImpl transaction = mock(SmppTransactionImpl.class);
        when(transaction.getActivityHandle()).thenReturn(handle);
        when(transaction.getStartTime()).thenReturn(System.currentTimeMillis());

        DefaultSmppSession smppSession = mock(DefaultSmppSession.class);
        final Esme esme = mock(Esme.class);
        when(esme.getSmppSession()).thenReturn(smppSession);
        when(smppSession.getCounters()).thenReturn(counters);
        Tracer tracer = mock(Tracer.class);
        SmppServerResourceAdaptor adaptor = mock(SmppServerResourceAdaptor.class);

        // create tested object
        final RequestSender sThread = new RequestSender(adaptor, tracer, esme, "deactivateWhileInactivity", reqTimeout);
        sThread.start();

        sThread.deactivate();

        // let things happen in the background
        Thread.sleep(ASSSERTION_TIMEOUT);
        Assert.assertFalse(sThread.isAlive());
    }

    @Test
    public void deactivateWhileInactivityRes() throws Exception {
        final long reqTimeout = 500;

        pool = Executors.newFixedThreadPool(NUM_THREADS);

        // Mock preparation
        DefaultSmppSessionCounters counters = new DefaultSmppSessionCounters();
        SmppTransactionHandle handle = mock(SmppTransactionHandle.class);
        final SmppTransactionImpl transaction = mock(SmppTransactionImpl.class);
        when(transaction.getActivityHandle()).thenReturn(handle);
        when(transaction.getStartTime()).thenReturn(System.currentTimeMillis());

        DefaultSmppSession smppSession = mock(DefaultSmppSession.class);
        final Esme esme = mock(Esme.class);
        when(esme.getSmppSession()).thenReturn(smppSession);
        when(smppSession.getCounters()).thenReturn(counters);
        Tracer tracer = mock(Tracer.class);
        SmppServerResourceAdaptor adaptor = mock(SmppServerResourceAdaptor.class);

        // create tested object
        final ResponseSender sThread = new ResponseSender(adaptor, tracer, "deactivateWhileInactivity", reqTimeout);
        sThread.start();

        sThread.deactivate();

        // let things happen in the background
        Thread.sleep(ASSSERTION_TIMEOUT);
        Assert.assertFalse(sThread.isAlive());
    }

}
