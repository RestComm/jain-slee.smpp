package org.restcomm.slee.resource.smpp;

public interface SmppTransactionACIFactory {
	javax.slee.ActivityContextInterface getActivityContextInterface(SmppTransaction txn);
}
