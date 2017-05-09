package org.restcomm.slee.resource.smpp;

import com.cloudhopper.smpp.impl.DefaultSmppSession;
import com.cloudhopper.smpp.impl.DefaultSmppSessionCounters;
import com.cloudhopper.smpp.pdu.PduRequest;
import com.cloudhopper.smpp.pdu.SubmitSm;
import com.cloudhopper.smpp.pdu.SubmitSmResp;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import javax.slee.facilities.Tracer;
import junit.framework.Assert;
import org.junit.Test;
import org.mockito.Mockito;
import org.restcomm.smpp.Esme;
import static org.mockito.Mockito.*;

public class SenderThreadTest {

    private static final long ASSSERTION_TIMEOUT = 1000;
    private static final int NUM_THREADS = 8;

    public SenderThreadTest() {
    }

    class SendingTask implements Runnable {

        SmppSendingTask task;
        SenderThread sThread;

        public SendingTask(SmppSendingTask task, SenderThread sThread) {
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

    @Test
    public void testConcurrentReqRes() throws Exception {
        final long reqTimeout = 1000;

        ExecutorService pool = Executors.newFixedThreadPool(NUM_THREADS);

        //Mock preparation
        DefaultSmppSessionCounters counters = new DefaultSmppSessionCounters();
        SmppTransactionHandle handle = mock(SmppTransactionHandle.class);
        final SmppTransactionImpl transaction = mock(SmppTransactionImpl.class);
        when(transaction.getActivityHandle()).thenReturn(handle);
        when(transaction.getStartTime()).thenReturn(System.currentTimeMillis());
        
        DefaultSmppSession smppSession = mock(DefaultSmppSession.class);
        final Esme esme = mock(Esme.class);
        when(esme.getSmppSession()).thenReturn(smppSession);
        //return null, the senderthread is not using the returned window anyway
        when(smppSession.sendRequestPdu(any(PduRequest.class), eq(reqTimeout), eq(false))).thenReturn(null);
        when(smppSession.getCounters()).thenReturn(counters);
        Tracer tracer = mock(Tracer.class);
        SmppServerResourceAdaptor adaptor = mock(SmppServerResourceAdaptor.class);
        Mockito.doNothing().when(adaptor).fireEvent(eq(EventsType.SEND_PDU_STATUS), eq(handle), anyObject());

        //create tested object
        final SenderThread sThread = new SenderThread("testConcurrentSend", tracer, adaptor);
        sThread.start();

        //exercise concurrently
        PduRequest req = null;
        for (int i = 0; i < NUM_THREADS; i++) {
            SmppSendingTask task = null;
            req = new SubmitSm();
            req.setReferenceObject(transaction);
            if (i % 2 == 0) {
                task = new SmppSendingTask(esme, new SubmitSm(), reqTimeout, null, transaction);
            } else {
                task = new SmppSendingTask(esme, req, reqTimeout, new SubmitSmResp(), transaction);
            }
            pool.submit(new SendingTask(task, sThread));
        }

        //let things happen in the background
        Thread.sleep(ASSSERTION_TIMEOUT);

        //assert as much as possible, let mockito assert the expected behavior
        //TODO
        Assert.assertEquals(NUM_THREADS / 2, counters.getRxSubmitSM().getResponse());

    }

}
