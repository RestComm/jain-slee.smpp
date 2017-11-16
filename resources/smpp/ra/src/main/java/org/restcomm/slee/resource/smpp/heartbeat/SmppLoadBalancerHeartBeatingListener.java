package org.restcomm.slee.resource.smpp.heartbeat;

public abstract interface SmppLoadBalancerHeartBeatingListener {

	public abstract void loadBalancerAdded(SmppLoadBalancer paramLoadBalancer);

	public abstract void loadBalancerRemoved(SmppLoadBalancer paramLoadBalancer);

	public abstract void pingingloadBalancer(SmppLoadBalancer paramLoadBalancer);

	public abstract void pingedloadBalancer(SmppLoadBalancer paramLoadBalancer);
	
}
