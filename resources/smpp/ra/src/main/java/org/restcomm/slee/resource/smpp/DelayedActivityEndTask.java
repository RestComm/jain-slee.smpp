package org.restcomm.slee.resource.smpp;

import java.util.Timer;
import java.util.TimerTask;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import javax.slee.facilities.Tracer;

public class DelayedActivityEndTask extends TimerTask {

    private transient Tracer tracer;
    private SmppServerResourceAdaptor ra;
    private SmppTransactionImpl txImpl;
    private Timer timer;
    
    private AtomicBoolean isActive = new AtomicBoolean(true);
    
    public DelayedActivityEndTask(Tracer tracer, SmppServerResourceAdaptor ra, SmppTransactionImpl txImpl,
            Timer timer) {
        this.tracer = tracer;
        this.ra = ra;
        this.txImpl = txImpl;
        this.timer = timer;
    }

    @Override
    public void run() {
        if(isActive.compareAndSet(true, false)) {
            if (this.tracer.isWarningEnabled()) {
                this.tracer.warning("Activity with handle " + txImpl.getActivityHandle() + " ended due to smppActivityTimeout");
            }
            ra.endActivity(txImpl);
        }
    }

    public void schedule(int delay, TimeUnit unit) {
        if(isActive.get())
            this.timer.schedule(this, unit.toMillis(delay));
    }
    
    public void unschedule(){
        this.isActive.set(false);
    }
}
