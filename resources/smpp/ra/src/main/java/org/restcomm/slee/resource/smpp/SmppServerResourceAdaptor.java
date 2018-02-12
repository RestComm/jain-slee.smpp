package org.restcomm.slee.resource.smpp;

import javax.management.MBeanServer;
import javax.management.ObjectName;

import java.lang.management.ManagementFactory;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

import javax.slee.Address;
import javax.slee.AddressPlan;
import javax.slee.SLEEException;
import javax.slee.facilities.EventLookupFacility;
import javax.slee.facilities.Tracer;
import javax.slee.resource.ActivityAlreadyExistsException;
import javax.slee.resource.ActivityFlags;
import javax.slee.resource.ActivityHandle;
import javax.slee.resource.ActivityIsEndingException;
import javax.slee.resource.ConfigProperties;
import javax.slee.resource.FailureReason;
import javax.slee.resource.FireEventException;
import javax.slee.resource.FireableEventType;
import javax.slee.resource.IllegalEventException;
import javax.slee.resource.InvalidConfigurationException;
import javax.slee.resource.Marshaler;
import javax.slee.resource.ReceivableService;
import javax.slee.resource.ResourceAdaptor;
import javax.slee.resource.ResourceAdaptorContext;
import javax.slee.resource.SleeEndpoint;
import javax.slee.resource.StartActivityException;
import javax.slee.resource.UnrecognizedActivityHandleException;

import org.jboss.mx.util.MBeanServerLocator;
import org.restcomm.smpp.SmppManagement;

public class SmppServerResourceAdaptor implements ResourceAdaptor {

	private transient Tracer tracer;
    private transient ResourceAdaptorContext raContext;
    private transient SleeEndpoint sleeEndpoint = null;
    private transient EventLookupFacility eventLookup = null;
    private EventIDCache eventIdCache = null;
    private SmppSessionsImpl smppServerSession = null;
    private SmppServerResourceAdaptorStatisticsUsageParameters defaultUsageParameters;

    private transient static final Address address = new Address(AddressPlan.IP, "localhost");

    private ConcurrentHashMap<SmppTransactionImpl, DelayedActivityEndTask> activityEndTasks = new ConcurrentHashMap<SmppTransactionImpl, DelayedActivityEndTask>();

    public SmppServerResourceAdaptor() {
        // TODO Auto-generated constructor stub
    }

    @Override
    public void activityEnded(ActivityHandle activityHandle) {
        if (this.tracer.isFineEnabled()) {
            this.tracer.fine("Activity with handle " + activityHandle + " ended");
        }
        SmppTransactionHandle serverTxHandle = (SmppTransactionHandle) activityHandle;
        final SmppTransactionImpl serverTx = serverTxHandle.getActivity();
        serverTxHandle.setActivity(null);

        if (serverTx != null) {
            serverTx.clear();
            DelayedActivityEndTask activityEndTask = activityEndTasks.remove(serverTx);
            if (activityEndTask != null)
                activityEndTask.unschedule();
        }
    }

    @Override
    public void activityUnreferenced(ActivityHandle arg0) {
        // TODO Auto-generated method stub

    }

    @Override
    public void administrativeRemove(ActivityHandle arg0) {
        // TODO Auto-generated method stub

    }

    @Override
    public void eventProcessingFailed(ActivityHandle arg0, FireableEventType arg1, Object arg2, Address arg3,
            ReceivableService arg4, int arg5, FailureReason arg6) {
        // TODO Auto-generated method stub

    }

    @Override
    public void eventProcessingSuccessful(ActivityHandle arg0, FireableEventType arg1, Object arg2, Address arg3,
            ReceivableService arg4, int arg5) {
        // TODO Auto-generated method stub

    }

    @Override
    public void eventUnreferenced(ActivityHandle arg0, FireableEventType arg1, Object arg2, Address arg3,
            ReceivableService arg4, int arg5) {
        // TODO Auto-generated method stub

    }

    @Override
    public Object getActivity(ActivityHandle activityHandle) {
        SmppTransactionHandle serverTxHandle = (SmppTransactionHandle) activityHandle;
        return serverTxHandle.getActivity();
    }

    @Override
    public ActivityHandle getActivityHandle(Object activity) {
        if (activity instanceof SmppTransactionImpl) {
            final SmppTransactionImpl wrapper = ((SmppTransactionImpl) activity);
            if (wrapper.getRa() == this) {
                return wrapper.getActivityHandle();
            }
        }

        return null;
    }

    @Override
    public Marshaler getMarshaler() {
        // TODO Auto-generated method stub
        return null;
    }

    @Override
    public Object getResourceAdaptorInterface(String arg0) {
        return this.smppServerSession;
    }

    @Override
    public void queryLiveness(ActivityHandle activityHandle) {
        final SmppTransactionHandle handle = (SmppTransactionHandle) activityHandle;
        final SmppTransactionImpl smppServerTxActivity = handle.getActivity();
        if (smppServerTxActivity == null || smppServerTxActivity.getWrappedPduRequest() == null) {
            sleeEndpoint.endActivity(handle);
        }

    }

    @Override
    public void raActive() {
        try {
            MBeanServer mBeanServer = null;
            ObjectName objectName = new ObjectName("org.restcomm.smpp:name=SmppManagement");
            Object object = null;
            if (ManagementFactory.getPlatformMBeanServer().isRegistered(objectName)) {
                // trying to get via MBeanServer
                mBeanServer = ManagementFactory.getPlatformMBeanServer();
                object = mBeanServer.getAttribute(objectName, "SmppManagementInstance");
                if (tracer.isInfoEnabled()) {
                    tracer.info("Trying to get via Platform MBeanServer: " + objectName + ", object: " + object);
                }
            } else {
                // trying to get via locateJBoss
                mBeanServer = MBeanServerLocator.locateJBoss();
                object = mBeanServer.getAttribute(objectName, "SmppManagementInstance");
                if (tracer.isInfoEnabled()) {
                    tracer.info("Trying to get via JBoss MBeanServer: " + objectName + ", object: " + object);
                }
            }

            if (object != null && object instanceof SmppManagement) {
                SmppManagement smscManagement = (SmppManagement) object;

                smscManagement.setSmppSessionHandlerInterface(this.smppServerSession.getSmppSessionHandlerInterface());

                smscManagement.startSmppManagement();

                if (tracer.isInfoEnabled()) {
                    tracer.info("Activated RA Entity " + this.raContext.getEntityName());
                }
            } else {
                if (object != null) {
                    if (tracer.isWarningEnabled()) {
                        tracer.warning("RA Entity " + this.raContext.getEntityName()
                                + " can't be activated: SmppManagementInstance() returns object"
                                + " that isn't SmppManagement instance! Object is " + object);
                    }
                } else {
                    if (tracer.isWarningEnabled()) {
                        tracer.warning("RA Entity " + this.raContext.getEntityName()
                                + " can't be activated: SmppManagementInstance() returns null");
                    }
                }
            }
        } catch (Exception e) {
            this.tracer.severe("Failed to activate SMPP Server RA ", e);
        }
    }

    @Override
    public void raConfigurationUpdate(ConfigProperties properties) {
        raConfigure(properties);
    }

    @Override
    public void raConfigure(ConfigProperties properties) {
        if (tracer.isFineEnabled()) {
            tracer.fine("Configuring RA Entity " + this.raContext.getEntityName());
        }
    }

    @Override
    public void raInactive() {
        SmppManagement smscManagemet = SmppManagement.getInstance();
        String entityName = this.raContext.getEntityName();
        try {
            smscManagemet.stopSmppManagement();
        } catch (Exception e) {
            tracer.severe("Error while inactivating RA Entity " + this.raContext.getEntityName(), e);
        }
        if (tracer.isInfoEnabled()) {
            tracer.info("Inactivated RA Entity " + entityName);
        }
    }

    @Override
    public void raStopping() {
        // TODO Auto-generated method stub

    }

    @Override
    public void raUnconfigure() {
    }

    @Override
    public void raVerifyConfiguration(ConfigProperties properties) throws InvalidConfigurationException {
    }

    @Override
    public void serviceActive(ReceivableService arg0) {
        // TODO Auto-generated method stub

    }

    @Override
    public void serviceInactive(ReceivableService arg0) {
        // TODO Auto-generated method stub

    }

    @Override
    public void serviceStopping(ReceivableService arg0) {
        // TODO Auto-generated method stub

    }

    @Override
    public void setResourceAdaptorContext(ResourceAdaptorContext raContext) {
        this.tracer = raContext.getTracer(getClass().getSimpleName());
        this.raContext = raContext;
        this.sleeEndpoint = raContext.getSleeEndpoint();
        this.eventLookup = raContext.getEventLookupFacility();
        this.eventIdCache = new EventIDCache(raContext);
        this.smppServerSession = new SmppSessionsImpl(this);

        try {
            this.defaultUsageParameters = (SmppServerResourceAdaptorStatisticsUsageParameters) raContext
                    .getDefaultUsageParameterSet();

            tracer.info("defaultUsageParameters: " + this.defaultUsageParameters);
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    @Override
    public void unsetResourceAdaptorContext() {
        this.raContext = null;
        this.sleeEndpoint = null;
        this.eventLookup = null;
        this.eventIdCache = null;
        if (this.smppServerSession != null) {
            this.smppServerSession.stopInactivityTimer();
        }
        this.smppServerSession = null;
    }

    /**
     * Protected
     */

    protected void startNewSmppServerTransactionActivity(SmppTransactionImpl txImpl) throws ActivityAlreadyExistsException,
            NullPointerException, IllegalStateException, SLEEException, StartActivityException {
        sleeEndpoint.startActivity(txImpl.getActivityHandle(), txImpl, ActivityFlags.REQUEST_ENDED_CALLBACK);

        DelayedActivityEndTask activityEndTask = new DelayedActivityEndTask(tracer, this, txImpl, raContext.getTimer());
        activityEndTasks.put(txImpl, activityEndTask);
        SmppManagement smppManagemet = SmppManagement.getInstance();
        int delay = smppManagemet.getSmppServerManagement().getSmppActivityTimeout();
        activityEndTask.schedule(delay, TimeUnit.SECONDS);
    }

    protected void startNewSmppTransactionSuspendedActivity(SmppTransactionImpl txImpl) throws ActivityAlreadyExistsException,
            NullPointerException, IllegalStateException, SLEEException, StartActivityException {
        sleeEndpoint.startActivitySuspended(txImpl.getActivityHandle(), txImpl, ActivityFlags.REQUEST_ENDED_CALLBACK);

        DelayedActivityEndTask activityEndTask = new DelayedActivityEndTask(tracer, this, txImpl, raContext.getTimer());
        activityEndTasks.put(txImpl, activityEndTask);
        SmppManagement smppManagemet = SmppManagement.getInstance();
        int delay = smppManagemet.getSmppServerManagement().getSmppActivityTimeout();
        activityEndTask.schedule(delay, TimeUnit.SECONDS);
    }

    protected void endActivity(SmppTransactionImpl txImpl) {
        try {
            this.sleeEndpoint.endActivity(txImpl.getActivityHandle());
        } catch (Exception e) {
            this.tracer.severe("Error while Ending Activity " + txImpl, e);
        }
    }

    protected ResourceAdaptorContext getRAContext() {
        return this.raContext;
    }

    /**
     * Private methods
     */
    protected void fireEvent(String eventName, ActivityHandle handle, Object event) {

        FireableEventType eventID = eventIdCache.getEventId(this.eventLookup, eventName);

        if (eventID == null) {
            tracer.severe("Event id for " + eventID + " is unknown, cant fire!!!");
        } else {
            try {
                sleeEndpoint.fireEvent(handle, eventID, event, address, null);
            } catch (UnrecognizedActivityHandleException e) {
                this.tracer.severe("Error while firing event", e);
            } catch (IllegalEventException e) {
                this.tracer.severe("Error while firing event", e);
            } catch (ActivityIsEndingException e) {
                this.tracer.severe("Error while firing event", e);
            } catch (NullPointerException e) {
                this.tracer.severe("Error while firing event", e);
            } catch (SLEEException e) {
                this.tracer.severe("Error while firing event", e);
            } catch (FireEventException e) {
                this.tracer.severe("Error while firing event", e);
            }
        }
    }

	public SmppServerResourceAdaptorStatisticsUsageParameters getStatisticsUsageParameterSet() {
	    return this.defaultUsageParameters;
	}
}
